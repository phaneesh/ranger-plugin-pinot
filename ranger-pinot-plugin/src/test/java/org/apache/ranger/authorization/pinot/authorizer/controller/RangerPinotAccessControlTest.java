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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pinot.controller.api.access.AccessControl;
import org.apache.pinot.controller.api.access.AccessType;
import org.apache.pinot.core.auth.Actions;
import org.apache.pinot.core.auth.TargetType;
import org.apache.ranger.authorization.pinot.authorizer.RangerPinotAuthorizer;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link RangerPinotAccessControl} against a real Ranger policy engine, using
 * {@code RangerBasePlugin.setPolicies(ServicePolicies)} directly (Ranger's own proven
 * test-harness pattern) with a test-configured plugin injected through the package-private
 * constructor. Identity comes from a stub {@link HttpHeaders} carrying a Basic auth header,
 * mirroring how Pinot's own {@code BasicAuthAccessControl} derives its principal.
 */
class RangerPinotAccessControlTest {
    private static final String SERVICE_NAME = "pinot-test";
    private static final String TABLE_ORDERS = "orders";
    private static final String USER_ALICE   = "alice";
    private static final String USER_BOB     = "bob";

    private static RangerServiceDef freshServiceDef() throws IOException {
        try (InputStream is = RangerPinotAccessControlTest.class.getClassLoader()
                .getResourceAsStream("service-defs/ranger-servicedef-pinot.json")) {
            return new ObjectMapper().readValue(is, RangerServiceDef.class);
        }
    }

    private static final AtomicLong POLICY_ID_SEQ = new AtomicLong(1);

    /** Policy granting one accessType on one resource element ({@code table} or {@code cluster}). */
    private static RangerPolicy policy(String resourceKey, String resourceValue, String user, String accessType) {
        RangerPolicy.RangerPolicyResource   resource = new RangerPolicy.RangerPolicyResource(resourceValue, false, false);
        RangerPolicy.RangerPolicyItemAccess access   = new RangerPolicy.RangerPolicyItemAccess(accessType, true);
        RangerPolicy.RangerPolicyItem       item     = new RangerPolicy.RangerPolicyItem(
                Collections.singletonList(access), Collections.singletonList(user), null, null, null, false);

        long         id     = POLICY_ID_SEQ.getAndIncrement();
        RangerPolicy policy = new RangerPolicy();

        // Every RangerPolicy needs a unique id/guid (as a real Ranger Admin would assign) — two
        // policies sharing an id/guid confuses the policy engine's internal indexing.
        policy.setId(id);
        policy.setGuid("test-policy-" + id);
        policy.setService(SERVICE_NAME);
        policy.setName("policy-" + resourceKey + "-" + resourceValue + "-" + user + "-" + accessType);
        policy.setResources(Collections.singletonMap(resourceKey, resource));
        policy.setPolicyItems(Collections.singletonList(item));

        return policy;
    }

    private static RangerBasePlugin pluginWithPolicies(RangerPolicy... policies) throws IOException {
        RangerBasePlugin plugin          = new RangerBasePlugin("pinot", "pinot");
        ServicePolicies  servicePolicies = new ServicePolicies();

        servicePolicies.setServiceName(SERVICE_NAME);
        servicePolicies.setServiceDef(freshServiceDef());
        servicePolicies.setPolicyVersion(1L);
        servicePolicies.setPolicies(new ArrayList<>(Arrays.asList(policies)));
        plugin.setPolicies(servicePolicies);

        return plugin;
    }

    /** Headers carrying {@code Authorization: Basic base64(user:secret)} — the plugin's identity source. */
    private static HttpHeaders basicAuthHeadersFor(String user) {
        String token = Base64.getEncoder().encodeToString((user + ":secret").getBytes(StandardCharsets.UTF_8));

        return headersWith(HttpHeaders.AUTHORIZATION, "Basic " + token);
    }

    /**
     * HttpHeaders stub with one header set. Only {@code getRequestHeader} is consulted by the
     * plugin; the rest are minimal implementations of the interface's remaining abstract methods.
     */
    private static HttpHeaders headersWith(String name, String value) {
        Map<String, List<String>> headers = new HashMap<>();

        headers.put(name, Collections.singletonList(value));

        return new HttpHeaders() {
            @Override
            public List<String> getRequestHeader(String name) {
                return headers.get(name);
            }

            @Override
            public MultivaluedMap<String, String> getRequestHeaders() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<MediaType> getAcceptableMediaTypes() {
                return Collections.emptyList();
            }

            @Override
            public List<Locale> getAcceptableLanguages() {
                return Collections.emptyList();
            }

            @Override
            public MediaType getMediaType() {
                return null;
            }

            @Override
            public Locale getLanguage() {
                return null;
            }

            @Override
            public Map<String, Cookie> getCookies() {
                return Collections.emptyMap();
            }
        };
    }

    private static RangerPinotAccessControl accessControl(RangerBasePlugin plugin) {
        return new RangerPinotAccessControl(new RangerPinotAuthorizer(plugin), null);
    }

    @Test
    void crudOnTableIsAllowedOnlyForTheGrantedAccessTypeAndUser() throws IOException {
        RangerBasePlugin plugin = pluginWithPolicies(policy("table", TABLE_ORDERS, USER_ALICE, "update"));

        RangerPinotAccessControl accessControl = accessControl(plugin);

        assertTrue(accessControl.hasAccess(TABLE_ORDERS, AccessType.UPDATE, basicAuthHeadersFor(USER_ALICE), "/tables/orders"),
                "granted user + granted CRUD accessType should pass");
        assertFalse(accessControl.hasAccess(TABLE_ORDERS, AccessType.DELETE, basicAuthHeadersFor(USER_ALICE), "/tables/orders"),
                "granted user + un-granted CRUD accessType should fail");
        assertFalse(accessControl.hasAccess(TABLE_ORDERS, AccessType.UPDATE, basicAuthHeadersFor(USER_BOB), "/tables/orders"),
                "un-granted user should fail");
    }

    @Test
    void nonTableEndpointCrudCheckGoesThroughTheClusterResource() throws IOException {
        RangerBasePlugin plugin = pluginWithPolicies(policy("cluster", "*", USER_ALICE, "read"));

        RangerPinotAccessControl accessControl = accessControl(plugin);

        assertTrue(accessControl.hasAccess(AccessType.READ, basicAuthHeadersFor(USER_ALICE), "/cluster/config"),
                "cluster-scoped CRUD grant should pass");
        assertFalse(accessControl.hasAccess(AccessType.READ, basicAuthHeadersFor(USER_BOB), "/cluster/config"),
                "user without the cluster grant should fail");
        assertFalse(accessControl.hasAccess(AccessType.DELETE, basicAuthHeadersFor(USER_ALICE), "/cluster/config"),
                "un-granted cluster CRUD accessType should fail");
    }

    @Test
    void fineGrainedTableActionCheckUsesTheActionsNamedAccessType() throws IOException {
        // Uses the real constant strings from org.apache.pinot.core.auth.Actions.Table.
        RangerBasePlugin plugin = pluginWithPolicies(policy("table", TABLE_ORDERS, USER_ALICE, Actions.Table.RELOAD_SEGMENT));

        RangerPinotAccessControl accessControl = accessControl(plugin);

        assertTrue(accessControl.hasAccess(basicAuthHeadersFor(USER_ALICE), TargetType.TABLE, TABLE_ORDERS, Actions.Table.RELOAD_SEGMENT),
                "granted fine-grained table action should pass");
        assertFalse(accessControl.hasAccess(basicAuthHeadersFor(USER_ALICE), TargetType.TABLE, TABLE_ORDERS, Actions.Table.UPLOAD_SEGMENT),
                "un-granted fine-grained table action should fail");
    }

    @Test
    void fineGrainedClusterActionCheckUsesTheActionsNamedAccessType() throws IOException {
        RangerBasePlugin plugin = pluginWithPolicies(policy("cluster", "*", USER_ALICE, Actions.Cluster.GET_CLUSTER_CONFIG));

        RangerPinotAccessControl accessControl = accessControl(plugin);

        assertTrue(accessControl.hasAccess(basicAuthHeadersFor(USER_ALICE), TargetType.CLUSTER, null, Actions.Cluster.GET_CLUSTER_CONFIG),
                "granted fine-grained cluster action should pass");
        assertFalse(accessControl.hasAccess(basicAuthHeadersFor(USER_BOB), TargetType.CLUSTER, null, Actions.Cluster.GET_CLUSTER_CONFIG),
                "user without the cluster action grant should fail");
    }

    @Test
    void basicAuthHeaderIsDecodedToTheUserName() {
        RangerPinotAccessControl accessControl = new RangerPinotAccessControl(
                new RangerPinotAuthorizer(new RangerBasePlugin("pinot", "pinot")), null);

        assertEquals(USER_ALICE, accessControl.extractUser(basicAuthHeadersFor(USER_ALICE)));
        assertEquals(null, accessControl.extractUser(null));
    }

    @Test
    void gatewayUserHeaderTakesPrecedenceWhenConfigured() {
        RangerPinotAccessControl accessControl = new RangerPinotAccessControl(
                new RangerPinotAuthorizer(new RangerBasePlugin("pinot", "pinot")), "X-User");

        assertEquals(USER_BOB, accessControl.extractUser(headersWith("X-User", USER_BOB)),
                "gateway header wins when present");
        assertEquals(USER_ALICE, accessControl.extractUser(basicAuthHeadersFor(USER_ALICE)),
                "falls back to the Basic header when the gateway header is absent");
    }

    @Test
    void defaultAccessIsTrueProtectAnnotatedOnlyIsFalseAndWorkflowIsBasic() {
        RangerPinotAccessControl accessControl = new RangerPinotAccessControl(
                new RangerPinotAuthorizer(new RangerBasePlugin("pinot", "pinot")), null);

        // Documented contract: defaultAccess only fires for non-@Authorize endpoints, which all
        // already passed the coarse CRUD check; protectAnnotatedOnly=false runs that coarse check
        // for every endpoint (mirrors Pinot's own BasicAuthAccessControl); WORKFLOW_BASIC drives
        // the UI's basic-auth prompt.
        assertTrue(accessControl.defaultAccess(basicAuthHeadersFor(USER_BOB)));
        assertFalse(accessControl.protectAnnotatedOnly());
        assertEquals(AccessControl.WORKFLOW_BASIC, accessControl.getAuthWorkflowInfo().getWorkflow());
    }

    @Test
    void noPolicyCacheFailsClosed() {
        // Fresh plugin, no setPolicies: the policy engine is genuinely null, matching "Ranger
        // Admin unreachable and no local policy cache exists". Every check must deny.
        RangerPinotAccessControl accessControl = accessControl(new RangerBasePlugin("pinot", "pinot"));

        assertFalse(accessControl.hasAccess(TABLE_ORDERS, AccessType.READ, basicAuthHeadersFor(USER_ALICE), "/tables/orders"));
        assertFalse(accessControl.hasAccess(AccessType.READ, basicAuthHeadersFor(USER_ALICE), "/cluster/config"));
        assertFalse(accessControl.hasAccess(basicAuthHeadersFor(USER_ALICE), TargetType.TABLE, TABLE_ORDERS, Actions.Table.RELOAD_SEGMENT));
        assertFalse(accessControl.hasAccess(basicAuthHeadersFor(USER_ALICE), TargetType.CLUSTER, null, Actions.Cluster.GET_CLUSTER_CONFIG));
    }

    /**
     * Tag serviceDef for the tag-based policy repository (TAG-01): resource {@code tag}, accessTypes
     * carrying the component prefix ({@code pinot:<accessType>}) exactly as Ranger Admin serializes them —
     * the policy engine strips the prefix during normalization ({@code normalizeAccessTypeDefs("pinot")}).
     */
    private static RangerServiceDef tagServiceDef() {
        RangerServiceDef.RangerResourceDef tagResource = new RangerServiceDef.RangerResourceDef(1L, "tag", "string", 1, "", true,
                false, false, false, "org.apache.ranger.plugin.resourcematcher.RangerDefaultResourceMatcher",
                Collections.singletonMap("wildCard", "true"), "", "", "", "TAG", "TAG", "", "", "", null, true);

        List<RangerServiceDef.RangerAccessTypeDef> accessTypes = new ArrayList<>();

        for (String accessType : Arrays.asList("update", "read", "all")) {
            accessTypes.add(new RangerServiceDef.RangerAccessTypeDef(null, "pinot:" + accessType, accessType, "", Collections.emptyList()));
        }

        RangerServiceDef.RangerContextEnricherDef tagEnricher = new RangerServiceDef.RangerContextEnricherDef(1L, "TagEnricher",
                "org.apache.ranger.plugin.contextenricher.RangerTagEnricher",
                new HashMap<String, String>() {
                    {
                        put("tagRetrieverClassName", "org.apache.ranger.plugin.contextenricher.RangerFileBasedTagRetriever");
                        put("serviceTagsFileName", "/service-tags/pinot-test-tags.json");
                    }
                });

        RangerServiceDef serviceDef = new RangerServiceDef();

        serviceDef.setName("tag");
        serviceDef.setResources(Collections.singletonList(tagResource));
        serviceDef.setAccessTypes(accessTypes);
        serviceDef.setContextEnrichers(Collections.singletonList(tagEnricher));

        return serviceDef;
    }

    /** Tag policy granting an accessType on tag {@code PII} to a user, as Ranger Admin would store it. */
    private static RangerPolicy tagPolicy(String tag, String user, String accessType) {
        RangerPolicy policy = policy("tag", tag, user, accessType);

        policy.setService("tagdev");

        return policy;
    }

    /** ServicePolicies with tagPolicies attached — the shape Ranger Admin downloads to plugins. */
    private static ServicePolicies servicePoliciesWithTags(RangerPolicy... policies) throws IOException {
        ServicePolicies             servicePolicies = new ServicePolicies();
        ServicePolicies.TagPolicies tagPolicies    = new ServicePolicies.TagPolicies();

        tagPolicies.setServiceName("tagdev");
        tagPolicies.setServiceDef(tagServiceDef());
        tagPolicies.setPolicyVersion(1L);
        tagPolicies.setPolicies(new ArrayList<>(Arrays.asList(policies)));

        servicePolicies.setServiceName(SERVICE_NAME);
        servicePolicies.setServiceDef(freshServiceDef());
        servicePolicies.setPolicyVersion(1L);
        servicePolicies.setPolicies(new ArrayList<>());
        servicePolicies.setTagPolicies(tagPolicies);

        return servicePolicies;
    }

    @Test
    void tagBasedPolicyGrantsAccessOnTaggedTable() throws IOException {
        // No table-resource policy at all: the ONLY grant is the PII tag policy. If tag enrichment
        // or tag-policy evaluation were broken, every check below would fail.
        RangerBasePlugin plugin = new RangerBasePlugin("pinot", "pinot");

        plugin.setPolicies(servicePoliciesWithTags(tagPolicy("PII", USER_ALICE, "pinot:update")));

        RangerPinotAccessControl accessControl = accessControl(plugin);

        assertTrue(accessControl.hasAccess(TABLE_ORDERS, AccessType.UPDATE, basicAuthHeadersFor(USER_ALICE), "/tables/orders"),
                "PII-tagged table + tag policy grant should pass (TAG-01)");
        assertFalse(accessControl.hasAccess("otherTable", AccessType.UPDATE, basicAuthHeadersFor(USER_ALICE), "/tables/otherTable"),
                "untagged table must fail: no table policy grants it");
        assertFalse(accessControl.hasAccess(TABLE_ORDERS, AccessType.UPDATE, basicAuthHeadersFor(USER_BOB), "/tables/orders"),
                "user not in the tag policy must fail");
        assertFalse(accessControl.hasAccess(TABLE_ORDERS, AccessType.DELETE, basicAuthHeadersFor(USER_ALICE), "/tables/orders"),
                "accessType not in the tag policy must fail");
    }
}
