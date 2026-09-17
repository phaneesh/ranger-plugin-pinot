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

package org.apache.ranger.authorization.pinot.authorizer.controller;

import org.apache.pinot.controller.api.access.AccessControl;
import org.apache.pinot.controller.api.access.AccessType;
import org.apache.pinot.core.auth.TargetType;
import org.apache.ranger.authorization.pinot.authorizer.RangerPinotAuthorizer;

import javax.ws.rs.core.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 4: Ranger enforcement for Pinot controller admin-API calls, replacing Phase 1's allow-all
 * stub. Pinot's {@code AuthenticationFilter} runs every protected endpoint through TWO checks
 * (see {@code AccessControlUtils.validatePermission} + {@code FineGrainedAuthUtils.validateFineGrainedAuth}):
 *
 * <ol>
 *   <li>Coarse CRUD: {@code hasAccess(table-or-null, CREATE/READ/UPDATE/DELETE, headers, url)} —
 *       this implementation maps it to a {@code table} resource (table endpoints) or the
 *       {@code cluster} resource (non-table endpoints) with the lowercased CRUD accessType
 *       ({@code create}/{@code read}/{@code update}/{@code delete} service-def entries).</li>
 *   <li>Fine-grained (only for {@code @Authorize}-annotated endpoints; none in Pinot 1.4.0, many in
 *       1.5.1): {@code hasAccess(headers, CLUSTER/TABLE, targetId, action)} — mapped to the
 *       matching {@code Actions.Cluster.*}/{@code Actions.Table.*}-named service-def accessType on
 *       the same {@code table}/{@code cluster} resources.</li>
 * </ol>
 *
 * <p>{@link #defaultAccess(HttpHeaders)} returns true by design: it is only consulted for endpoints
 * that are NOT {@code @Authorize}-annotated, and every such endpoint has already been through the
 * coarse CRUD check above — denying there would fail-close every endpoint on Pinot 1.4.0, where no
 * endpoint is annotated (same reasoning as Pinot's own {@code BasicAuthAccessControl}, which also
 * answers true from its non-annotated default path).</p>
 *
 * <p>All decisions go through the shared {@link RangerPinotAuthorizer} (ADMIN-03) — no
 * resource/request building is duplicated here beyond the small private map helpers.</p>
 */
public class RangerPinotAccessControl implements AccessControl {
    /**
     * Ranger's cluster resource value for cluster-wide checks. {@code "*"} makes the check depend
     * only on the policy's own cluster-resource value (a policy granting an action on
     * {@code cluster=*} covers any cluster name, mirroring how Ranger's Kafka plugin models its
     * {@code cluster} resource) — the plugin never sees a real cluster name to key on.
     */
    private static final String CLUSTER_RESOURCE_VALUE = "*";

    private static final String RESOURCE_TABLE   = "table";
    private static final String RESOURCE_CLUSTER = "cluster";

    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_AUTHORIZATION_PREFIX = "Basic ";

    private final RangerPinotAuthorizer authorizer;

    /**
     * Name of an extra HTTP header carrying the authenticated user, for deployments that front the
     * controller with a gateway (e.g. a proxy setting {@code X-User}) instead of Basic auth; null
     * disables the fallback. Not configurable in this build — a constant field is the seam Phase 5's
     * install config wires up.
     */
    private final String userHeaderName;

    public RangerPinotAccessControl() {
        this(RangerPinotAuthorizer.getInstance(), null);
    }

    /**
     * Test-only constructor: inject a {@link RangerPinotAuthorizer} wrapping a test-configured
     * {@code RangerBasePlugin} (Ranger's own {@code setPolicies(...)} test-harness pattern), instead
     * of always going through the production {@link RangerPinotAuthorizer#getInstance()} singleton.
     */
    RangerPinotAccessControl(RangerPinotAuthorizer authorizer, String userHeaderName) {
        this.authorizer     = authorizer;
        this.userHeaderName = userHeaderName;
    }

    /** Coarse CRUD check for table endpoints: table resource + lowercased CRUD accessType. */
    @Override
    public boolean hasAccess(String tableName, AccessType accessType, HttpHeaders httpHeaders, String endpointUrl) {
        return checkAccess(tableResource(tableName), crudAccessType(accessType), httpHeaders);
    }

    /**
     * Coarse CRUD check for non-table endpoints (no table name in the URL): the cluster-wide
     * resource. Uses the same cluster modeling as {@link #hasAccess(HttpHeaders, TargetType, String, String)}.
     */
    @Override
    public boolean hasAccess(AccessType accessType, HttpHeaders httpHeaders, String endpointUrl) {
        return checkAccess(clusterResource(), crudAccessType(accessType), httpHeaders);
    }

    /**
     * Fine-grained check (ADMIN-02): TABLE targets map the raw table name to the {@code table}
     * resource; CLUSTER targets use the cluster-wide resource. The action string is already the
     * exact name of a service-def accessType (every {@code Actions.Cluster.*}/{@code Actions.Table.*}
     * constant is one).
     */
    @Override
    public boolean hasAccess(HttpHeaders httpHeaders, TargetType targetType, String targetId, String action) {
        return checkAccess(targetType == TargetType.TABLE ? tableResource(targetId) : clusterResource(), action, httpHeaders);
    }

    /**
     * Only consulted for endpoints that are NOT {@code @Authorize}-annotated — every one of which
     * already passed the coarse CRUD check. Returning true is correct, not a hole: see the class
     * javadoc.
     */
    @Override
    public boolean defaultAccess(HttpHeaders httpHeaders) {
        return true;
    }

    /** All endpoints get the coarse CRUD check (matches Pinot's own BasicAuthAccessControl). */
    @Override
    public boolean protectAnnotatedOnly() {
        return false;
    }

    /** Drives the UI's basic-auth prompt, mirroring Pinot's own BasicAuthAccessControl. */
    @Override
    public AuthWorkflowInfo getAuthWorkflowInfo() {
        return new AuthWorkflowInfo(WORKFLOW_BASIC);
    }

    private boolean checkAccess(Map<String, Object> resourceElements, String accessType, HttpHeaders httpHeaders) {
        String user = extractUser(httpHeaders);

        // ponytail: groups are always empty — Ranger group policies for pinot are untested until a
        // user-group source exists; wire a group lookup here when one is added.
        return authorizer.isAccessAllowed(resourceElements, accessType, user, Collections.emptySet());
    }

    private static Map<String, Object> tableResource(String tableName) {
        return Collections.singletonMap(RESOURCE_TABLE, tableName);
    }

    private static Map<String, Object> clusterResource() {
        return Collections.singletonMap(RESOURCE_CLUSTER, CLUSTER_RESOURCE_VALUE);
    }

    private static String crudAccessType(AccessType accessType) {
        return accessType.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Controller-side identity, mirroring how Pinot's own {@code BasicAuthAccessControl} derives its
     * principal: decode the {@code Authorization: Basic <base64(user:password)>} header and take the
     * user part. A null/failed decode yields {@code null}, which the policy engine treats as an
     * anonymous user (any policy only grants named users/groups denies it — fail-closed in effect).
     * The optional {@code userHeaderName} fallback (see the field javadoc) takes precedence when set
     * and present.
     */
    String extractUser(HttpHeaders httpHeaders) {
        if (httpHeaders == null) {
            return null;
        }

        if (userHeaderName != null) {
            List<String> gatewayHeaders = httpHeaders.getRequestHeader(userHeaderName);

            if (gatewayHeaders != null && !gatewayHeaders.isEmpty() && !gatewayHeaders.get(0).isEmpty()) {
                return gatewayHeaders.get(0);
            }
        }

        List<String> authHeaders = httpHeaders.getRequestHeader(HEADER_AUTHORIZATION);

        if (authHeaders == null || authHeaders.isEmpty()) {
            return null;
        }

        String token = authHeaders.get(0);

        if (token == null || !token.startsWith(HEADER_AUTHORIZATION_PREFIX)) {
            return null;
        }

        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(token.substring(HEADER_AUTHORIZATION_PREFIX.length())), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null; // malformed base64: treat as anonymous
        }

        int colon = decoded.indexOf(':');

        return colon > 0 ? decoded.substring(0, colon) : decoded;
    }
}
