/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.authorization.pinot.authorizer.broker;

import org.apache.pinot.broker.api.AccessControl;
import org.apache.pinot.common.request.BrokerRequest;
import org.apache.pinot.spi.auth.*;
import org.apache.pinot.spi.auth.broker.RequesterIdentity;
import org.apache.ranger.authorization.pinot.authorizer.RangerPinotAuthorizer;

import java.util.*;

/**
 * Phase 2: real Ranger table-ACL enforcement for Pinot broker queries, replacing Phase 1's
 * allow-all stub. Delegates every table check to {@link RangerPinotAuthorizer}
 * (shared with the Phase 4 controller side per ADMIN-03).
 */
public class RangerPinotAccessControl implements AccessControl {
    private static final String ACCESS_TYPE_QUERY = "query";

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
     * requester is not authorized for.
     */
    @Override
    public TableAuthorizationResult authorize(RequesterIdentity requesterIdentity, Set<String> tables) {
        String user = deriveUser(requesterIdentity);
        Set<String> userGroups = Collections.emptySet();
        Set<String> failedTables = new HashSet<>();

        for (String table : tables) {
            if (!authorizer.isTableAccessAllowed(table, ACCESS_TYPE_QUERY, user, userGroups)) {
                failedTables.add(table);
            }
        }

        return failedTables.isEmpty() ? TableAuthorizationResult.success() : new TableAuthorizationResult(failedTables);
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
     * Reads the Authorization header reflectively from the runtime identity. The 1.4/1.5
     * {@code HttpRequesterIdentity.getHttpHeaders()} signature references unshaded guava
     * (com.google.common.collect.Multimap) while the pinot docker image shades guava — direct
     * linkage from this class throws NoSuchMethodError at link time. Reflection sidesteps
     * the type resolution entirely: the header multimap is only touched as Object.
     */
    private static String basicAuthUserFromIdentity(RequesterIdentity identity) {
        try {
            java.lang.reflect.Method getter = null;
            for (java.lang.reflect.Method m : identity.getClass().getMethods()) {
                if ("getHttpHeaders".equals(m.getName()) && m.getParameterCount() == 0) {
                    getter = m;
                    break;
                }
            }
            if (getter == null) {
                return null;
            }
            Object headers = getter.invoke(identity);
            if (headers == null) {
                return null;
            }
            // Grizzly lowercases header names; look up case-insensitively via the keySet.
            Object keys = headers.getClass().getMethod("keySet").invoke(headers);
            for (Object key : (java.util.Collection<?>) keys) {
                if (!"authorization".equalsIgnoreCase(String.valueOf(key))) {
                    continue;
                }
                Object values = headers.getClass().getMethod("get", Object.class).invoke(headers, key);
                if (values instanceof java.util.Collection && !((java.util.Collection<?>) values).isEmpty()) {
                    Object first = ((java.util.Collection<?>) values).iterator().next();
                    return basicAuthUser(String.valueOf(first));
                }
            }
            return null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
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
            String decoded = new String(java.util.Base64.getDecoder().decode(trimmed.substring(6).trim()),
                    java.nio.charset.StandardCharsets.UTF_8);
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
        Optional<String> filter = authorizer.getRowFilter(table, deriveUser(requesterIdentity), Collections.emptySet());
        System.err.println("[RLS-PROBE] getRowColFilters called: table=" + table + " user=" + deriveUser(requesterIdentity) + " filter=" + filter);

        return filter.isPresent() ? new TableRowColAccessResultImpl(List.of(filter.get())) : TableRowColAccessResultImpl.unrestricted();
    }
}
