/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.authorization.pinot.authorizer.broker;

import org.apache.pinot.broker.api.AccessControl;
import org.apache.pinot.common.request.BrokerRequest;
import org.apache.pinot.spi.auth.AuthorizationResult;
import org.apache.pinot.spi.auth.BasicAuthorizationResultImpl;
import org.apache.pinot.spi.auth.TableAuthorizationResult;
import org.apache.pinot.spi.auth.TableRowColAccessResult;
import org.apache.pinot.spi.auth.TableRowColAccessResultImpl;
import org.apache.pinot.spi.auth.broker.RequesterIdentity;
import org.apache.ranger.authorization.pinot.authorizer.RangerPinotAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 2: real Ranger table-ACL enforcement for Pinot broker queries, replacing Phase 1's
 * allow-all stub. Delegates every table check to {@link RangerPinotAuthorizer}
 * (shared with the Phase 4 controller side per ADMIN-03).
 *
 * <p>Hot-path discipline: this class runs once per query on the broker. Header access is
 * resolved through {@link MethodHandle}s cached per identity class (see
 * {@link HeaderAccessors}), so the steady state costs a pair of map lookups and a direct
 * invoked call — no per-request reflection metadata scans, and only failed tables allocate.</p>
 */
public class RangerPinotAccessControl implements AccessControl {
    private static final Logger LOG = LoggerFactory.getLogger(RangerPinotAccessControl.class);

    private static final String ACCESS_TYPE_QUERY = "query";
    private static final String HEADER_AUTHORIZATION = "authorization";

    private final RangerPinotAuthorizer authorizer;

    public RangerPinotAccessControl() {
        this(RangerPinotAuthorizer.getInstance());
    }

    /**
     * Test-only constructor: inject a {@link RangerPinotAuthorizer} wrapping a test-configured
     * {@code RangerBasePlugin} (Ranger's own {@code setPolicies(...)} test-harness pattern),
     * instead of always going through the production {@link RangerPinotAuthorizer#getInstance()}
     * singleton.
     */
    RangerPinotAccessControl(RangerPinotAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    /**
     * First-step / coarse gate before query planning (per {@link AccessControl}'s own docs — the
     * request may still be rejected at table level later). The real per-table decision happens in
     * {@link #authorize(RequesterIdentity, Set)}, so this always allows through to that check.
     */
    @Override
    public AuthorizationResult authorize(RequesterIdentity requesterIdentity) {
        return BasicAuthorizationResultImpl.success();
    }

    /**
     * Mirrors Pinot's own {@code BasicAuthAccessControl} reference implementation: the single-stage
     * broker request handler calls this overload directly in the real query path
     * ({@code BaseSingleStageBrokerRequestHandler}), so leaving it on the interface's default
     * (which throws {@code UnsupportedOperationException}) would break every query. Delegate to
     * the table-set check below.
     */
    @Override
    public AuthorizationResult authorize(RequesterIdentity requesterIdentity, BrokerRequest brokerRequest) {
        if (brokerRequest == null || !brokerRequest.isSetQuerySource() || !brokerRequest.getQuerySource().isSetTableName()) {
            return TableAuthorizationResult.success();
        }

        return authorize(requesterIdentity, Collections.singleton(brokerRequest.getQuerySource().getTableName()));
    }

    /**
     * The main Ranger table-ACL hook: checks every queried table and reports back which ones the
     * requester is not authorized for. The failed-table set is allocated lazily — the all-allowed
     * common case allocates nothing.
     */
    @Override
    public TableAuthorizationResult authorize(RequesterIdentity requesterIdentity, Set<String> tables) {
        String user = deriveUser(requesterIdentity);
        Set<String> userGroups = Collections.emptySet();
        Set<String> failedTables = null;

        for (String table : tables) {
            if (!authorizer.isTableAccessAllowed(table, ACCESS_TYPE_QUERY, user, userGroups)) {
                if (failedTables == null) {
                    failedTables = new HashSet<>(Math.max(2, tables.size()));
                }

                failedTables.add(table);
            }
        }

        return failedTables == null ? TableAuthorizationResult.success() : new TableAuthorizationResult(failedTables);
    }

    /**
     * Derives the requesting principal from {@link RequesterIdentity}. Pinot 1.4/1.5's
     * runtime {@code HttpRequesterIdentity} carries raw HTTP headers but never populates
     * {@code getClientIp()} (the base class returns "unknown"), so identity extraction
     * works through the headers: Basic-auth username when present, else the client IP,
     * else "unknown".
     */
    private static String deriveUser(RequesterIdentity requesterIdentity) {
        if (requesterIdentity == null) {
            return "";
        }

        String fromHeaders = basicAuthUserFromIdentity(requesterIdentity);
        if (fromHeaders != null) {
            return fromHeaders;
        }

        return requesterIdentity.getClientIp();
    }

    /**
     * Reads the Basic-auth user from the identity's header multimap via cached
     * {@link MethodHandle}s (see {@link HeaderAccessors} for why reflection — and why it is
     * not a per-query cost).
     */
    private static String basicAuthUserFromIdentity(RequesterIdentity identity) {
        HeaderAccessors accessors = HeaderAccessors.of(identity.getClass());
        Object headers = accessors.getHeaders(identity);

        if (headers == null) {
            return null;
        }

        for (Object key : accessors.keys(headers)) {
            if (!HEADER_AUTHORIZATION.equalsIgnoreCase(String.valueOf(key))) {
                continue;
            }

            Object values = accessors.get(headers, key);

            if (values instanceof Collection && !((Collection<?>) values).isEmpty()) {
                Object first = ((Collection<?>) values).iterator().next();
                return basicAuthUser(String.valueOf(first));
            }
        }

        return null;
    }

    /**
     * Decodes a {@code Basic base64(user:password)} header value to the username.
     */
    private static String basicAuthUser(String header) {
        try {
            String trimmed = header == null ? "" : header.trim();
            if (!trimmed.regionMatches(true, 0, "Basic ", 0, 6)) {
                return null;
            }

            String decoded = new String(Base64.getDecoder().decode(trimmed.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon > 0 ? decoded.substring(0, colon) : decoded;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Row-level-security hook (MASK-01): returns the Ranger row-filter policy's SQL predicate for
     * this user/table, wrapped for Pinot's RLS machinery. Pinot wraps each returned filter as
     * {@code ( f )}, joins multiple filters with {@code AND} and rewrites the query with them —
     * but only when the broker-side RLS config is switched on (see
     * {@link RangerPinotAuthorizer#getRowFilter}). No column masking here: Pinot 1.4/1.5's broker
     * SPI has no masking channel at all.
     */
    @Override
    public TableRowColAccessResult getRowColFilters(RequesterIdentity requesterIdentity, String table) {
        String user = deriveUser(requesterIdentity);
        Optional<String> filter = authorizer.getRowFilter(table, user, Collections.emptySet());

        if (LOG.isDebugEnabled()) {
            LOG.debug("getRowColFilters(table={}, user={}) = {}", table, user, filter);
        }

        return filter.isPresent() ? new TableRowColAccessResultImpl(List.of(filter.get())) : TableRowColAccessResultImpl.unrestricted();
    }

    /**
     * Cached reflective access to the runtime identity's header multimap. The 1.4/1.5
     * {@code HttpRequesterIdentity.getHttpHeaders()} signature references unshaded guava
     * (com.google.common.collect.Multimap) while the pinot docker image shades guava — direct
     * linkage from this class throws NoSuchMethodError at link time, so the multimap must stay
     * untyped. Resolution happens once per identity class (a broker has one, maybe two) and is
     * cached in a {@link ConcurrentHashMap}; afterwards every query pays only a map lookup and
     * {@link MethodHandle#invokeExact invoke} calls, which JIT-inline like ordinary virtual calls.
     */
    private static final class HeaderAccessors {
        private static final ConcurrentHashMap<Class<?>, HeaderAccessors> CACHE = new ConcurrentHashMap<>();

        private final MethodHandle getHeaders;
        private final MethodHandle keySet;
        private final MethodHandle get;

        private HeaderAccessors(MethodHandle getHeaders, MethodHandle keySet, MethodHandle get) {
            this.getHeaders = getHeaders;
            this.keySet = keySet;
            this.get = get;
        }

        static HeaderAccessors of(Class<?> identityClass) {
            return CACHE.computeIfAbsent(identityClass, HeaderAccessors::resolve);
        }

        private static HeaderAccessors resolve(Class<?> identityClass) {
            try {
                Method headersGetter = findNoArgMethod(identityClass, "getHttpHeaders");
                if (headersGetter == null) {
                    return missing();
                }

                Class<?> headersType = headersGetter.getReturnType();
                Method keySetMethod = findNoArgMethod(headersType, "keySet");
                Method getMethod = headersType.getMethod("get", Object.class);

                MethodHandles.Lookup lookup = MethodHandles.lookup();

                // asType to fully-generic (Object...)Object descriptors so every call site can
                // use invokeExact against Object-typed arguments — the cheapest invoke form.
                return new HeaderAccessors(
                        lookup.unreflect(headersGetter).asType(MethodType.methodType(Object.class, Object.class)),
                        lookup.unreflect(keySetMethod),
                        lookup.unreflect(getMethod).asType(MethodType.genericMethodType(2)));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return missing();
            }
        }

        private static HeaderAccessors missing() {
            return new HeaderAccessors(null, null, null);
        }

        private static Method findNoArgMethod(Class<?> type, String name) {
            for (Method m : type.getMethods()) {
                if (name.equals(m.getName()) && m.getParameterCount() == 0) {
                    return m;
                }
            }

            return null;
        }

        Object getHeaders(RequesterIdentity identity) {
            try {
                return getHeaders == null ? null : (Object) getHeaders.invokeExact((Object) identity);
            } catch (Throwable t) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        Collection<Object> keys(Object headers) {
            try {
                return keySet == null ? Collections.emptySet() : (Collection<Object>) keySet.invoke(headers);
            } catch (Throwable t) {
                return Collections.emptySet();
            }
        }

        Object get(Object headers, Object key) {
            try {
                return this.get == null ? null : this.get.invokeExact(headers, key);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
