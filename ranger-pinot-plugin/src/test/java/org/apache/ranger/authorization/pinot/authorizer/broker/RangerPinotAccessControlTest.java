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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pinot.spi.auth.TableAuthorizationResult;
import org.apache.pinot.spi.auth.broker.RequesterIdentity;
import org.apache.ranger.authorization.pinot.authorizer.RangerPinotAuthorizer;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link RangerPinotAccessControl} itself (not just the {@link RangerPinotAuthorizer}
 * helper), using a test-configured {@link RangerBasePlugin} injected through the package-private
 * constructor, so the real {@link org.apache.pinot.broker.api.AccessControl} contract is verified
 * end to end.
 */
class RangerPinotAccessControlTest {
    private static final String SERVICE_NAME = "pinot-test";
    private static final String ACCESS_QUERY = "query";

    private static RangerServiceDef freshServiceDef() throws IOException {
        try (InputStream is = RangerPinotAccessControlTest.class.getClassLoader()
                .getResourceAsStream("service-defs/ranger-servicedef-pinot.json")) {
            return new ObjectMapper().readValue(is, RangerServiceDef.class);
        }
    }

    private static final AtomicLong POLICY_ID_SEQ = new AtomicLong(1);

    private static RangerPolicy tablePolicy(String table, String user) {
        RangerPolicy.RangerPolicyResource   resource = new RangerPolicy.RangerPolicyResource(table, false, false);
        RangerPolicy.RangerPolicyItemAccess access   = new RangerPolicy.RangerPolicyItemAccess(ACCESS_QUERY, true);
        RangerPolicy.RangerPolicyItem       item     = new RangerPolicy.RangerPolicyItem(
                Collections.singletonList(access), Collections.singletonList(user), null, null, null, false);

        long         id     = POLICY_ID_SEQ.getAndIncrement();
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

    /** Requester identity whose {@code getClientIp()} stands in as the request's user. */
    private static RequesterIdentity identityFor(String user) {
        return new RequesterIdentity() {
            @Override
            public String getClientIp() {
                return user;
            }
        };
    }

    @Test
    void reportsOnlyTheTablesThatFailedAuthorization() throws IOException {
        RangerBasePlugin plugin = pluginWithPolicies(
                tablePolicy("orders", "alice"),
                tablePolicy("shipments", "alice"));

        RangerPinotAccessControl accessControl = new RangerPinotAccessControl(new RangerPinotAuthorizer(plugin));
        Set<String>               tables        = Set.of("orders", "shipments", "secret_table");

        TableAuthorizationResult result = accessControl.authorize(identityFor("alice"), tables);

        assertEquals(Set.of("secret_table"), result.getFailedTables());
        assertFalse(result.hasAccess());
    }

    @Test
    void allAuthorizedTablesReportSuccess() throws IOException {
        RangerBasePlugin          plugin        = pluginWithPolicies(tablePolicy("orders", "alice"));
        RangerPinotAccessControl  accessControl = new RangerPinotAccessControl(new RangerPinotAuthorizer(plugin));

        TableAuthorizationResult result = accessControl.authorize(identityFor("alice"), Set.of("orders"));

        assertTrue(result.hasAccess());
    }
}
