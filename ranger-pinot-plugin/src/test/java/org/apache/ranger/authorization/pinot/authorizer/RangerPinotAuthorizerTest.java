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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerAccessResultProcessor;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link RangerPinotAuthorizer} against a real Ranger policy engine, using
 * {@code RangerBasePlugin.setPolicies(ServicePolicies)} directly — Ranger's own proven
 * test-harness pattern. No live Ranger Admin server or network access is involved.
 */
class RangerPinotAuthorizerTest {
    private static final String SERVICE_NAME = "pinot-test";
    private static final String TABLE_ORDERS = "orders";
    private static final String ACCESS_QUERY = "query";

    private static RangerServiceDef freshServiceDef() throws IOException {
        // A fresh deserialize per ServicePolicies avoids sharing one RangerServiceDef object
        // (and any internal caches it accumulates) across multiple independently-built policy
        // engines in different test methods.
        try (InputStream is = RangerPinotAuthorizerTest.class.getClassLoader()
                .getResourceAsStream("service-defs/ranger-servicedef-pinot.json")) {
            return new ObjectMapper().readValue(is, RangerServiceDef.class);
        }
    }

    private static final AtomicLong POLICY_ID_SEQ = new AtomicLong(1);

    private static RangerPolicy tablePolicy(String table, String user, String accessType) {
        RangerPolicy.RangerPolicyResource resource = new RangerPolicy.RangerPolicyResource(table, false, false);
        RangerPolicy.RangerPolicyItemAccess access = new RangerPolicy.RangerPolicyItemAccess(accessType, true);
        RangerPolicy.RangerPolicyItem item = new RangerPolicy.RangerPolicyItem(
                Collections.singletonList(access), Collections.singletonList(user), null, null, null, false);

        long id = POLICY_ID_SEQ.getAndIncrement();
        RangerPolicy policy = new RangerPolicy();

        // Every RangerPolicy needs a unique id/guid (as a real Ranger Admin would assign) — two
        // policies sharing an id/guid confuses the policy engine's internal indexing.
        policy.setId(id);
        policy.setGuid("test-policy-" + id);
        policy.setService(SERVICE_NAME);
        policy.setName("policy-" + table + "-" + user);
        policy.setResources(Collections.singletonMap("table", resource));
        policy.setPolicyItems(Collections.singletonList(item));

        return policy;
    }

    private static ServicePolicies servicePolicies(long policyVersion, RangerPolicy... policies) throws IOException {
        ServicePolicies servicePolicies = new ServicePolicies();

        servicePolicies.setServiceName(SERVICE_NAME);
        servicePolicies.setServiceDef(freshServiceDef());
        servicePolicies.setPolicyVersion(policyVersion);
        servicePolicies.setPolicies(new ArrayList<>(Arrays.asList(policies)));

        return servicePolicies;
    }

    private static RangerPinotAuthorizer authorizerWithPolicies(RangerPolicy... policies) throws IOException {
        RangerBasePlugin plugin = new RangerBasePlugin("pinot", "pinot");

        plugin.setPolicies(servicePolicies(1L, policies));

        return new RangerPinotAuthorizer(plugin);
    }

    @Test
    void grantedUserIsAllowedDeniedUserIsNot() throws IOException {
        RangerPinotAuthorizer authorizer = authorizerWithPolicies(tablePolicy(TABLE_ORDERS, "alice", ACCESS_QUERY));

        assertTrue(authorizer.isTableAccessAllowed(TABLE_ORDERS, ACCESS_QUERY, "alice", Collections.emptySet()));
        assertFalse(authorizer.isTableAccessAllowed(TABLE_ORDERS, ACCESS_QUERY, "bob", Collections.emptySet()));
    }

    @Test
    void noPolicyCacheFailsClosed() {
        // Neither init() nor setPolicies() called: the plugin's policyEngine is genuinely null,
        // matching "Ranger Admin unreachable and no local policy cache exists".
        RangerPinotAuthorizer authorizer = new RangerPinotAuthorizer(new RangerBasePlugin("pinot", "pinot"));

        assertFalse(authorizer.isTableAccessAllowed(TABLE_ORDERS, ACCESS_QUERY, "alice", Collections.emptySet()));
    }

    @Test
    void auditEmittedForBothAllowAndDeny() throws IOException {
        RangerBasePlugin plugin = new RangerBasePlugin("pinot", "pinot");
        List<RangerAccessResult> recordedResults = new ArrayList<>();

        plugin.setResultProcessor(new RangerAccessResultProcessor() {
            @Override
            public void processResult(RangerAccessResult result) {
                recordedResults.add(result);
            }

            @Override
            public void processResults(Collection<RangerAccessResult> results) {
                recordedResults.addAll(results);
            }
        });
        plugin.setPolicies(servicePolicies(1L, tablePolicy(TABLE_ORDERS, "alice", ACCESS_QUERY)));

        RangerPinotAuthorizer authorizer = new RangerPinotAuthorizer(plugin);

        assertTrue(authorizer.isTableAccessAllowed(TABLE_ORDERS, ACCESS_QUERY, "alice", Collections.emptySet()));
        assertFalse(authorizer.isTableAccessAllowed(TABLE_ORDERS, ACCESS_QUERY, "bob", Collections.emptySet()));

        assertEquals(2, recordedResults.size());
        assertTrue(recordedResults.get(0).getIsAllowed());
        assertFalse(recordedResults.get(1).getIsAllowed());
    }

    @Test
    void policyChangeIsReflectedWithoutRestart() throws IOException {
        RangerBasePlugin plugin = new RangerBasePlugin("pinot", "pinot");

        plugin.setPolicies(servicePolicies(1L, tablePolicy(TABLE_ORDERS, "alice", ACCESS_QUERY)));

        RangerPinotAuthorizer authorizer = new RangerPinotAuthorizer(plugin);

        assertFalse(authorizer.isTableAccessAllowed(TABLE_ORDERS, ACCESS_QUERY, "bob", Collections.emptySet()));

        // Same plugin instance, no restart: grant bob access and confirm it takes effect. The
        // policy version is bumped, matching how a real Ranger Admin poll response always carries
        // a newer version on an actual policy change.
        plugin.setPolicies(servicePolicies(2L,
                tablePolicy(TABLE_ORDERS, "alice", ACCESS_QUERY),
                tablePolicy(TABLE_ORDERS, "bob", ACCESS_QUERY)));

        assertTrue(authorizer.isTableAccessAllowed(TABLE_ORDERS, ACCESS_QUERY, "bob", Collections.emptySet()));
    }
}
