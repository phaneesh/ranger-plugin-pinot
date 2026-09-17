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
import org.apache.pinot.spi.auth.AuthorizationResult;
import org.apache.pinot.spi.auth.BasicAuthorizationResultImpl;
import org.apache.pinot.spi.auth.TableAuthorizationResult;
import org.apache.pinot.spi.auth.TableRowColAccessResult;
import org.apache.pinot.spi.auth.TableRowColAccessResultImpl;
import org.apache.pinot.spi.auth.broker.RequesterIdentity;
import org.apache.ranger.authorization.pinot.authorizer.RangerPinotAuthorizer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        String      user       = deriveUser(requesterIdentity);
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
     * {@link RequesterIdentity} is Pinot's own abstract marker type and carries no direct notion
     * of "user" — only {@code getClientIp()} on the base class; the concrete
     * {@code HttpRequesterIdentity} used at runtime adds raw HTTP headers + endpoint URL, but no
     * parsed principal. TODO(Phase 2 follow-up, tracked as an open question, not a blocker): wire
     * real identity extraction here (e.g. an auth header or a pluggable {@code AuthProvider}).
     * Until then the client IP is used as a stand-in principal, so table ACLs are at least
     * partitioned per caller instead of collapsing every request onto one shared identity.
     */
    private static String deriveUser(RequesterIdentity requesterIdentity) {
        return requesterIdentity != null ? requesterIdentity.getClientIp() : "";
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

        return filter.isPresent() ? new TableRowColAccessResultImpl(List.of(filter.get())) : TableRowColAccessResultImpl.unrestricted();
    }
}
