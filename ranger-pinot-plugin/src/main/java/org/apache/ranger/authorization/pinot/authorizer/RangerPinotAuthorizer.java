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

package org.apache.ranger.authorization.pinot.authorizer;

import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shared Ranger policy-engine wrapper for the Pinot plugin, used by both the broker (query-time)
 * and controller (admin-API, Phase 4) enforcement points. Kept in this package rather than under
 * {@code .broker}/{@code .controller} so Phase 4's controller-side code reuses it instead of
 * duplicating the {@link RangerBasePlugin} wiring (ADMIN-03).
 *
 * <p>Constructed the same way Ranger's Kafka plugin builds its {@link RangerBasePlugin} — direct
 * instantiation, no subclass — since there's already exactly one {@link
 * org.apache.ranger.plugin.classloader.RangerPluginClassLoader} per plugin type ("pinot"), so one
 * authorizer instance per isolated classloader is correct.</p>
 */
public class RangerPinotAuthorizer {
    private static final Logger LOG = LoggerFactory.getLogger(RangerPinotAuthorizer.class);

    private static final String RANGER_SERVICE_TYPE = "pinot";
    private static final String RANGER_APP_ID        = "pinot";
    private static final String RESOURCE_TABLE       = "table";
    private static final String ACCESS_TYPE_QUERY   = "query";

    private final RangerBasePlugin rangerPlugin;

    public RangerPinotAuthorizer() {
        this(createPlugin());
    }

    /**
     * Test-only constructor: hands in a pre-built {@link RangerBasePlugin} so unit tests can call
     * {@code setPolicies(...)} directly (Ranger's own proven test-harness pattern) without
     * production {@code init()} ever starting a {@code PolicyRefresher} thread or touching a live
     * Ranger Admin. Public (rather than package-private) so both the {@code .broker} and future
     * {@code .controller} test packages can inject a test-configured plugin.
     */
    public RangerPinotAuthorizer(RangerBasePlugin rangerPlugin) {
        this.rangerPlugin = rangerPlugin;
    }

    private static RangerBasePlugin createPlugin() {
        RangerBasePlugin plugin = new RangerBasePlugin(RANGER_SERVICE_TYPE, RANGER_APP_ID);

        plugin.setResultProcessor(new RangerDefaultAuditHandler());
        plugin.init();

        return plugin;
    }

    /**
     * Lazily-initialized singleton (initialization-on-demand holder idiom — thread-safe without
     * explicit synchronization, per JLS classloading guarantees).
     */
    public static RangerPinotAuthorizer getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private static final class InstanceHolder {
        private static final RangerPinotAuthorizer INSTANCE = new RangerPinotAuthorizer();
    }

    /**
     * @param tableName  Pinot table name (Ranger's single {@code table} resource)
     * @param accessType must match a {@code query}/{@code all} accessTypes entry from the pinot
     *                   service-def
     * @param user       requesting user
     * @param userGroups requesting user's groups
     * @return {@code true} only if the policy engine explicitly allowed the request. A null
     *         result (no policy cache loaded yet, e.g. Ranger Admin unreachable) is treated as a
     *         deny — {@link RangerBasePlugin} does not fail closed on its own, the caller must.
     */
    public boolean isTableAccessAllowed(String tableName, String accessType, String user, Set<String> userGroups) {
        return isAccessAllowed(Collections.singletonMap(RESOURCE_TABLE, tableName), accessType, user, userGroups);
    }

    /**
     * Generalized access check over any service-def resource map (ADMIN-01/02/03): the broker uses
     * {@code {"table": ...}} for query-time checks, the controller uses {@code {"table": ...}} for
     * table CRUD/endpoint checks and {@code {"cluster": ...}} for cluster-wide actions.
     *
     * @param resourceElements service-def resource names to values, e.g. {@code {"table": "orders"}}
     *                          or {@code {"cluster": "*"}}
     * @param accessType       must match an {@code accessTypes[].name} entry from the pinot service-def
     *                          ({@code query}/{@code create}/{@code read}/{@code update}/{@code delete}/
     *                          the {@code Actions.*}-derived names/{@code all})
     * @param user             requesting user
     * @param userGroups       requesting user's groups
     * @return {@code true} only if the policy engine explicitly allowed the request. A null
     *                         result (no policy cache loaded yet, e.g. Ranger Admin unreachable) is treated as a
     *                         deny — {@link RangerBasePlugin} does not fail closed on its own, the caller must.
     */
    public boolean isAccessAllowed(Map<String, Object> resourceElements, String accessType, String user, Set<String> userGroups) {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl(resourceElements);
        RangerAccessRequestImpl  request  = new RangerAccessRequestImpl(resource, accessType, user, userGroups, null);

        request.setAccessTime(new Date());

        RangerAccessResult result    = rangerPlugin.isAccessAllowed(request);
        boolean             isAllowed = result != null && result.getIsAllowed();

        LOG.debug("isAccessAllowed(resource={}, accessType={}, user={}) = {}", resourceElements, accessType, user, isAllowed);

        return isAllowed;
    }

    /**
     * Row-level-security filter for a table (MASK-01). Evaluates the service-def's
     * {@code rowFilterDef} policies ({@code RangerBasePlugin#evalRowFilterPolicies}) and returns
     * the matching policy item's filter expression, e.g. {@code "region = 'emea'"}. Pinot's broker
     * wraps each returned filter as {@code ( f )}, joins multiple filters with {@code AND} and rewrites
     * the query with them (CalciteSqlParser/RlsFiltersRewriter), so the expression must be a valid
     * Pinot SQL predicate over the queried table.
     *
     * <p>Note Pinot only applies returned filters when the broker-side
     * {@code pinot.broker.query.reader[...].rls.enabled} / {@code enableRowColumnLevelAuth} config is
     * switched on — that is broker configuration, not plugin configuration.</p>
     *
     * <p>A null result (no policy engine — policies never loaded, e.g. Ranger Admin unreachable)
     * is deliberately treated as "no filter" rather than a deny-all-rows expression: there is no
     * safe deny-all SQL expression this method could return, and {@link #isTableAccessAllowed}
     * already failed closed for the access itself at {@code authorize()} time —
     * {@code getRowColFilters} is only consulted by Pinot for tables whose access was already
     * granted, so an unloaded policy engine means "no row filter on top of an already-denied query",
     * not a hole in the access check.</p>
     *
     * @param tableName  Pinot table name (Ranger's single {@code table} resource)
     * @param user       requesting user
     * @param userGroups requesting user's groups
     * @return the row-filter SQL predicate, or empty if no row-filter policy matches
     */
    public Optional<String> getRowFilter(String tableName, String user, Set<String> userGroups) {
        RangerAccessResourceImpl resource = new RangerAccessResourceImpl(Collections.singletonMap(RESOURCE_TABLE, tableName));
        RangerAccessRequestImpl  request  = new RangerAccessRequestImpl(resource, ACCESS_TYPE_QUERY, user, userGroups, null);

        request.setAccessTime(new Date());

        RangerAccessResult result = rangerPlugin.evalRowFilterPolicies(request, null);
        Optional<String>     ret   = result != null && result.isRowFilterEnabled() ? Optional.of(result.getFilterExpr()) : Optional.empty();

        LOG.debug("getRowFilter(table={}, user={}) = {}", tableName, user, ret);

        return ret;
    }
}
