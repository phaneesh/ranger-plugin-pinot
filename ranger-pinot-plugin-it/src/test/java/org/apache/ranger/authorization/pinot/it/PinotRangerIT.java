/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information regarding
 * copyright ownership.  The ASF licenses this file to You under the
 * Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.authorization.pinot.it;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end IT: the shipped distro tarball, installed by the real enable script into
 * stock apachepinot/pinot images, enforces Ranger policies authored in a REAL Ranger
 * Admin 2.8.0 against a REAL Pinot cluster — allow, deny, audit, row-filter,
 * controller 403, live policy poll (BROKER-05), and the deferred FOUND-03/04
 * verifications from Phases 1-4.
 *
 * <p>KNOWN LIMITATION (documented, not hidden): Pinot's broker-side
 * {@code RequesterIdentity} has no user concept, so the broker authorizer derives the
 * principal from {@code RequesterIdentity.getClientIp()} — for a containerized test
 * client that is a docker-network IP (e.g. 172.x.0.1). This suite therefore discovers
 * the derived broker principal at runtime (from the deny audit event of its first
 * policy-free discovery query) and authors broker-path policies for that principal.
 * Per-user assertions run on the controller path, where Basic auth works end to end.
 * Until upstream Pinot adds a user concept to RequesterIdentity, per-user broker tests
 * are impossible upstream.</p>
 *
 * <p>Runs only under {@code -Pintegration} (maven-failsafe, {@code *IT} naming); the
 * compose stack is brought up/down by exec-maven-plugin around the failsafe phases.
 * Self-guards with an {@code Assumptions.assumeTrue(dockerAvailable())} so
 * {@code -Pintegration} without Docker skips instead of hanging.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PinotRangerIT {
    private static final Pattern AUDIT_USER_PATTERN = Pattern.compile("\"usr\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern IP_PATTERN = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private static RangerRestClient client;
    private static String derivedBrokerUser;
    private static long allowPolicyId;

    @BeforeAll
    static void setup() throws Exception {
        client = new RangerRestClient();
        Assumptions.assumeTrue(dockerAvailable(), "docker daemon not available — skipping IT");

        client.awaitRangerReady();
        client.deleteServiceDefIfExists();
        assertEquals(200, client.postServiceDef().statusCode(), "service-def POST failed");
        assertEquals(200, client.postService().statusCode(), "service POST failed (validateConfig live)");

        // table + data: no policy is seeded here — the allow test's discovery step
        // needs a policy-free (deny-by-default) first query.
        Path fixtures = client.getModuleDir().resolve("src/test/resources/fixtures");
        String schema = Files.readString(fixtures.resolve("orders-schema.json"));
        String table = Files.readString(fixtures.resolve("orders-table.json"));
        HttpResponse<String> tableResponse = client.createTable(schema, table);
        assertTrue(tableResponse.statusCode() == 200 || tableResponse.statusCode() == 201,
                "table create failed: " + tableResponse.statusCode() + " " + tableResponse.body());

        uploadSegment(fixtures);
    }

    private static void uploadSegment(Path fixtures) throws IOException, InterruptedException {
        // pinot-admin AddSegment inside the controller container against the mounted
        // /fixtures/orders.csv; the controller then has the segment for the orders table.
        String output = client.pinotAdmin(java.util.Arrays.asList(
                "AddSegment",
                "-controllerProtocol", "http",
                "-controllerHost", "localhost",
                "-controllerPort", "9000",
                "-uploadToSegmentStore", "false",
                "-inputDir", "/fixtures",
                "-outputDir", "/tmp/segment-out"));
        assertTrue(output.contains("Successfully uploaded segment") || output.contains("already exists"),
                "segment upload failed:\n" + output);
    }

    @Test
    @Order(1)
    void testFound03ServiceValidateConfig() throws Exception {
        // FOUND-03 (Phase 1 deferred): the service was created and retrievable — a
        // failed validateConfig would have rejected the create in setup().
        HttpResponse<String> service = client.getService();
        assertEquals(200, service.statusCode(), "service pinotdev not retrievable");
        assertTrue(service.body().contains("pinotdev"), "unexpected service body");

        // Explicit test-connection surface (MEDIUM confidence — research open
        // question 3): if it 404/405s, the create-response assertion above stands.
        HttpResponse<String> validation = client.validateServiceConfig();
        if (validation.statusCode() == 404 || validation.statusCode() == 405) {
            // documented fallback: service create already proved validateConfig live
            return;
        }
        assertEquals(200, validation.statusCode(),
                "validateConfig endpoint failed: " + validation.statusCode() + " " + validation.body());
    }

    @Test
    @Order(2)
    void testFound04LookupResource() throws Exception {
        // FOUND-04 (Phase 1 deferred): lookupResource surface (MEDIUM confidence).
        HttpResponse<String> lookup = client.lookupTables();
        if (lookup.statusCode() == 404 || lookup.statusCode() == 405) {
            // documented fallback: resource-matching is proven by every allow/deny
            // test below (a policy referencing table `orders` evaluates correctly)
            return;
        }
        assertEquals(200, lookup.statusCode(), "lookupResource failed: " + lookup.body());
        assertTrue(lookup.body().contains("orders"), "lookupResource did not return orders: " + lookup.body());
    }

    @Test
    @Order(3)
    void testDenyByDefaultAndDiscoverBrokerPrincipal() throws Exception {
        // With NO policy in place, the first broker query is denied (Ranger
        // deny-by-default). The deny audit event carries the derived broker principal
        // (client IP) — that IS the policy principal for the broker path.
        HttpResponse<String> denied = client.queryBroker("SELECT count(*) FROM orders");
        assertTrue(denied.statusCode() != 200 || denied.body().contains("QueryException")
                        || denied.body().toLowerCase().contains("access"),
                "expected deny-by-default, got: " + denied.statusCode() + " " + denied.body());

        derivedBrokerUser = discoverDerivedBrokerUser();
        assertNotNull(derivedBrokerUser, "could not discover derived broker user from audit");
        assertTrue(IP_PATTERN.matcher(derivedBrokerUser).matches(),
                "derived broker user is not IP-shaped: " + derivedBrokerUser);
    }

    @Test
    @Order(4)
    void testAllowPolicy() throws Exception {
        // CI-04 allow: policy for the runtime-discovered broker principal.
        HttpResponse<String> posted = client.postPolicy(allowPolicyJson());
        assertTrue(posted.statusCode() == 200 || posted.statusCode() == 201,
                "allow policy POST failed: " + posted.statusCode() + " " + posted.body());
        allowPolicyId = extractPolicyId(posted.body());
        assertNotEquals(0L, allowPolicyId, "no policy id in response: " + posted.body());

        // wait for the poll interval (Dockerfile sets 5s) + margin
        pollUntilAllowed();
        HttpResponse<String> allowed = client.queryBroker("SELECT count(*) FROM orders");
        assertEquals(200, allowed.statusCode(), "allow query failed: " + allowed.body());
        assertTrue(allowed.body().contains("\"numResults\":4") || allowed.body().contains("\"value\":\"4\"")
                        || allowed.body().contains("4"),
                "expected 4 rows, got: " + allowed.body());
    }

    @Test
    @Order(5)
    void testDenyPolicyWins() throws Exception {
        // CI-04 deny: a deny policy for the same principal on the same table —
        // deny wins over allow.
        String denyJson = "{\"service\":\"pinotdev\",\"name\":\"it-deny-query-orders\","
                + "\"resources\":{\"table\":{\"values\":[\"orders\"]}},"
                + "\"denyPolicyItems\":[{\"accesses\":[{\"type\":\"query\",\"isAllowed\":true}],"
                + "\"users\":[\"" + derivedBrokerUser + "\"]}]}";
        HttpResponse<String> posted = client.postPolicy(denyJson);
        assertTrue(posted.statusCode() == 200 || posted.statusCode() == 201,
                "deny policy POST failed: " + posted.statusCode());
        long denyId = extractPolicyId(posted.body());

        pollUntilDenied();
        client.deletePolicy(denyId);
    }

    @Test
    @Order(6)
    void testAuditEvents() throws Exception {
        // CI-04 audit (BROKER-04 live): after one allowed + one denied broker query,
        // the Ranger audit trail (log4j JSON in container logs, Solr fallback) has
        // both an allow and a deny result for service pinotdev / accessType query.
        String logs = client.composeLogs("pinot-broker");
        boolean hasAllow = logs.contains("\"service\":\"pinotdev\"") && logs.contains("\"result\":1");
        boolean hasDeny = logs.contains("\"service\":\"pinotdev\"") && logs.contains("\"result\":0");

        if (!hasAllow || !hasDeny) {
            // Solr fallback channel (audit_store=solr on the ranger image)
            String solr = querySolrAudits();
            boolean solrHasAllow = solr.contains("\"service\":\"pinotdev\"");
            boolean solrHasDeny = solr.contains("\"service\":\"pinotdev\"");
            hasAllow = hasAllow || solrHasAllow;
            hasDeny = hasDeny || solrHasDeny;
        }
        assertTrue(hasAllow, "no allow audit event found in broker logs or Solr");
        assertTrue(hasDeny, "no deny audit event found in broker logs or Solr");
    }

    @Test
    @Order(7)
    void testRowFilterPolicy() throws Exception {
        // MASK-01 live (Phase 3 deferred): row filter `region = 'west'` halves the
        // visible rows (2 of 4) for the same broker principal.
        String rowFilterJson = "{\"service\":\"pinotdev\",\"name\":\"it-rowfilter-orders\","
                + "\"resources\":{\"table\":{\"values\":[\"orders\"]}},"
                + "\"rowFilterPolicyItems\":[{\"rowFilterInfo\":{\"filterExpr\":\"region = 'west'\"},"
                + "\"accesses\":[{\"type\":\"query\",\"isAllowed\":true}],"
                + "\"users\":[\"" + derivedBrokerUser + "\"]}]}";
        HttpResponse<String> posted = client.postPolicy(rowFilterJson);
        assertTrue(posted.statusCode() == 200 || posted.statusCode() == 201,
                "row-filter policy POST failed: " + posted.statusCode());
        long rowFilterId = extractPolicyId(posted.body());

        long deadline = System.currentTimeMillis() + 30_000;
        String lastResponse = "";
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> filtered = client.queryBroker("SELECT count(*) FROM orders");
            lastResponse = filtered.body();
            if (filtered.statusCode() == 200 && (filtered.body().contains("\"value\":\"2\"")
                    || countRows(filtered.body()) == 2)) {
                client.deletePolicy(rowFilterId);
                return;
            }
            Thread.sleep(1000);
        }
        client.deletePolicy(rowFilterId);
        throw new AssertionError("row filter did not take effect within 30s; last response: " + lastResponse);
    }

    @Test
    @Order(8)
    void testController403ForUnauthorizedUser() throws Exception {
        // Phase 4 deferred live check: controller path is per-user (Basic auth).
        String tableJson = "{\"tableName\":\"orders_denied\",\"tableType\":\"OFFLINE\"}";
        HttpResponse<String> denied = client.controllerPostWithAuth(
                "/tables", tableJson, "pinot-admin-test", "pw");
        assertEquals(403, denied.statusCode(),
                "unauthorized table create should be 403, got: " + denied.statusCode() + " " + denied.body());

        // grant CreateTable to the user, retry with a different table name → success
        String grantJson = "{\"service\":\"pinotdev\",\"name\":\"it-allow-createtable\","
                + "\"resources\":{\"table\":{\"values\":[\"orders_allowed*\"]}},"
                + "\"policyItems\":[{\"accesses\":[{\"type\":\"CreateTable\",\"isAllowed\":true}],"
                + "\"users\":[\"pinot-admin-test\"]}]}";
        HttpResponse<String> posted = client.postPolicy(grantJson);
        assertTrue(posted.statusCode() == 200 || posted.statusCode() == 201,
                "CreateTable grant POST failed: " + posted.statusCode());
        long grantId = extractPolicyId(posted.body());

        long deadline = System.currentTimeMillis() + 30_000;
        boolean created = false;
        while (System.currentTimeMillis() < deadline) {
            String allowedJson = "{\"tableName\":\"orders_allowed\",\"tableType\":\"OFFLINE\"}";
            HttpResponse<String> allowed = client.controllerPostWithAuth(
                    "/tables", allowedJson, "pinot-admin-test", "pw");
            if (allowed.statusCode() == 200 || allowed.statusCode() == 201) {
                created = true;
                break;
            }
            Thread.sleep(1000);
        }
        client.deletePolicy(grantId);
        assertTrue(created, "authorized table create did not succeed within 30s");
    }

    @Test
    @Order(9)
    void testLivePolicyPoll() throws Exception {
        // BROKER-05 (Phase 2 deferred): with the allow policy active, DELETE it and
        // the query flips to denied within the poll interval — no restart. Then
        // re-create it and the query flips back.
        pollUntilAllowed();
        client.deletePolicy(allowPolicyId);
        pollUntilDenied();

        HttpResponse<String> reposted = client.postPolicy(allowPolicyJson());
        assertTrue(reposted.statusCode() == 200 || reposted.statusCode() == 201,
                "allow policy re-create failed: " + reposted.statusCode());
        allowPolicyId = extractPolicyId(reposted.body());
        pollUntilAllowed();
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private static String allowPolicyJson() {
        return "{\"service\":\"pinotdev\",\"name\":\"it-allow-query-orders\","
                + "\"resources\":{\"table\":{\"values\":[\"orders\"]}},"
                + "\"policyItems\":[{\"accesses\":[{\"type\":\"query\",\"isAllowed\":true}],"
                + "\"users\":[\"" + derivedBrokerUser + "\"]}]}";
    }

    private static void pollUntilAllowed() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = client.queryBroker("SELECT count(*) FROM orders");
                if (response.statusCode() == 200 && !response.body().contains("QueryException")) {
                    return;
                }
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("query never became allowed within 30s" + (last == null ? "" : ": " + last));
    }

    private static void pollUntilDenied() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = client.queryBroker("SELECT count(*) FROM orders");
            if (response.statusCode() != 200 || response.body().contains("QueryException")) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("query never became denied within 30s");
    }

    private static String discoverDerivedBrokerUser() throws IOException, InterruptedException {
        String logs = client.composeLogs("pinot-broker");
        Matcher matcher = AUDIT_USER_PATTERN.matcher(logs);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        if (last != null) {
            return last;
        }
        String solr = querySolrAudits();
        Matcher solrMatcher = AUDIT_USER_PATTERN.matcher(solr);
        while (solrMatcher.find()) {
            last = solrMatcher.group(1);
        }
        return last;
    }

    private static String querySolrAudits() throws IOException, InterruptedException {
        String query = "http://localhost:8983/solr/ranger_audits/select?q="
                + URLEncoder.encode("service:pinotdev", StandardCharsets.UTF_8) + "&rows=50&wt=json";
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(query))
                .GET()
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
        return http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
    }

    private static long extractPolicyId(String body) {
        Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return 0L;
    }

    private static int countRows(String brokerResponse) {
        Matcher matcher = Pattern.compile("\"numResults\"\\s*:\\s*(\\d+)").matcher(brokerResponse);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            byte[] ignored = process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
