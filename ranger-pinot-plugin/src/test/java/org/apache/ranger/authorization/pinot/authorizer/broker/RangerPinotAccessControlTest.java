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
import org.apache.pinot.spi.auth.TableRowColAccessResult;
import org.apache.pinot.spi.auth.broker.RequesterIdentity;
import org.apache.ranger.authorization.pinot.authorizer.RangerPinotAuthorizer;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.ranger.plugin.util.ServicePolicies;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

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
        RangerPolicy.RangerPolicyResource resource = new RangerPolicy.RangerPolicyResource(table, false, false);
        RangerPolicy.RangerPolicyItemAccess access = new RangerPolicy.RangerPolicyItemAccess(ACCESS_QUERY, true);
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

    private static RangerPolicy rowFilterPolicy(String table, String user, String filterExpr) {
        RangerPolicy.RangerPolicyItemRowFilterInfo rowFilterInfo = new RangerPolicy.RangerPolicyItemRowFilterInfo(filterExpr);
        RangerPolicy.RangerPolicyItemAccess access = new RangerPolicy.RangerPolicyItemAccess(ACCESS_QUERY, true);
        RangerPolicy.RangerRowFilterPolicyItem item = new RangerPolicy.RangerRowFilterPolicyItem(
                rowFilterInfo, Collections.singletonList(access), Collections.singletonList(user), null, null, null, false);
        long id = POLICY_ID_SEQ.getAndIncrement();
        RangerPolicy policy = new RangerPolicy();

        policy.setId(id);
        policy.setGuid("test-policy-" + id);
        policy.setService(SERVICE_NAME);
        policy.setName("row-filter-policy-" + table + "-" + user);
        policy.setPolicyType(RangerPolicy.POLICY_TYPE_ROWFILTER);
        policy.setResources(Collections.singletonMap("table", new RangerPolicy.RangerPolicyResource(table, false, false)));
        policy.setRowFilterPolicyItems(Collections.singletonList(item));

        return policy;
    }

    private static RangerBasePlugin pluginWithPolicies(RangerPolicy... policies) throws IOException {
        RangerBasePlugin plugin = new RangerBasePlugin("pinot", "pinot");
        ServicePolicies servicePolicies = new ServicePolicies();

        servicePolicies.setServiceName(SERVICE_NAME);
        servicePolicies.setServiceDef(freshServiceDef());
        servicePolicies.setPolicyVersion(1L);
        servicePolicies.setPolicies(new ArrayList<>(Arrays.asList(policies)));
        plugin.setPolicies(servicePolicies);

        return plugin;
    }

    /**
     * Requester identity whose {@code getClientIp()} stands in as the request's user.
     */
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
        Set<String> tables = Set.of("orders", "shipments", "secret_table");

        TableAuthorizationResult result = accessControl.authorize(identityFor("alice"), tables);

        assertEquals(Set.of("secret_table"), result.getFailedTables());
        assertFalse(result.hasAccess());
    }

    @Test
    void allAuthorizedTablesReportSuccess() throws IOException {
        RangerBasePlugin plugin = pluginWithPolicies(tablePolicy("orders", "alice"));
        RangerPinotAccessControl accessControl = new RangerPinotAccessControl(new RangerPinotAuthorizer(plugin));

        TableAuthorizationResult result = accessControl.authorize(identityFor("alice"), Set.of("orders"));

        assertTrue(result.hasAccess());
    }

    @Test
    void rowFilterPolicyIsReturnedViaGetRowColFilters() throws IOException {
        RangerBasePlugin plugin = pluginWithPolicies(
                tablePolicy("orders", "alice"),
                rowFilterPolicy("orders", "alice", "region = 'emea'"));

        RangerPinotAccessControl accessControl = new RangerPinotAccessControl(new RangerPinotAuthorizer(plugin));

        TableRowColAccessResult result = accessControl.getRowColFilters(identityFor("alice"), "orders");

        assertTrue(result.getRLSFilters().isPresent(), "expected RLS filters to be present");
        assertEquals(List.of("region = 'emea'"), result.getRLSFilters().get());
    }

    @Test
    void tableWithoutRowFilterPolicyIsUnrestricted() throws IOException {
        RangerBasePlugin plugin = pluginWithPolicies(
                tablePolicy("orders", "alice"),
                rowFilterPolicy("other_table", "alice", "region = 'emea'"));

        RangerPinotAccessControl accessControl = new RangerPinotAccessControl(new RangerPinotAuthorizer(plugin));

        TableRowColAccessResult result = accessControl.getRowColFilters(identityFor("alice"), "orders");

        assertFalse(result.getRLSFilters().isPresent(), "expected no RLS filters");
    }

    /**
     * Documented fail-open for row filters: a null result from {@code evalRowFilterPolicies} (no
     * policy engine — policies never loaded) means "no filter", not deny-all rows. The access
     * check itself already failed closed at {@code authorize()} time; see
     * {@code RangerPinotAuthorizer#getRowFilter} for the full reasoning.
     */
    @Test
    void nullPolicyEngineMeansUnrestrictedNotDeniedRows() {
        RangerBasePlugin plugin = new RangerBasePlugin("pinot", "pinot"); // no setPolicies: no policy engine

        RangerPinotAccessControl accessControl = new RangerPinotAccessControl(new RangerPinotAuthorizer(plugin));

        TableRowColAccessResult result = accessControl.getRowColFilters(identityFor("alice"), "orders");

        assertFalse(result.getRLSFilters().isPresent(), "expected no RLS filters");
    }
}
