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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin REST helper for the Ranger-Pinot ITs: Basic-auth provisioning against the live
 * Ranger Admin (PublicAPIsv2), Pinot controller REST calls, broker SQL queries, and
 * docker-compose process helpers (container logs, pinot-admin). Only the JDK HttpClient
 * is used — no new dependencies.
 */
public class RangerRestClient {
    private static final String AUTH_HEADER;

    static {
        String token = "admin:rangerR0cks!";
        AUTH_HEADER = "Basic " + Base64.getEncoder()
                .encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    // HTTP/1.1 pinned: the JDK client's default HTTP/2 upgrade hangs against Pinot's
    // Grizzly server (verified live: h2c upgrade POST never gets a response; 1.1 answers in ms).
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String rangerBase;
    private final String controllerBase;
    private final String brokerBase;
    private final Path moduleDir;
    private final Path composeFile;
    private final String composeProject;
    private int lastStatusCode = -1;

    public RangerRestClient() {
        rangerBase = systemProperty("ranger.baseUrl", "http://localhost:6080");
        controllerBase = systemProperty("pinot.controller.baseUrl", "http://localhost:9000");
        brokerBase = systemProperty("broker.baseUrl", "http://localhost:8099");
        moduleDir = Path.of(systemProperty("it.module.dir", "."));
        composeFile = moduleDir.resolve("docker/docker-compose.yml");
        composeProject = systemProperty("it.compose.project", "ranger-pinot-it");
    }

    public String getRangerBase() {
        return rangerBase;
    }

    public Path getModuleDir() {
        return moduleDir;
    }

    /**
     * Polls Ranger Admin until its login page answers — first boot (DB setup +
     * create-ranger-services.py) takes 2-5 minutes, so the deadline is 5 minutes.
     * Poll, never sleep.
     */
    public void awaitRangerReady() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(rangerBase))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();
                client.send(request, HttpResponse.BodyHandlers.ofString());
                return;
            } catch (IOException e) {
                Thread.sleep(5000);
            }
        }
        throw new IllegalStateException("Ranger Admin not ready after 5 minutes at " + rangerBase);
    }

    /**
     * idempotent re-run guard: deletes a pre-existing pinot service-def and its services.
     * GET-first is load-bearing: a DELETE on a non-existent service-def surfaces as a bare 500
     * (the @Transactional proxy rewraps ServiceREST's 404 WebApplicationException), while GET
     * answers a clean 404. So: GET, and only DELETE when found.
     */
    public void deleteServiceDefIfExists() throws IOException, InterruptedException {
        request(rangerBase + "/service/public/v2/api/servicedef/name/pinot", "GET", null, 200, 404);
        if (lastStatusCode == 200) {
            request(rangerBase + "/service/public/v2/api/servicedef/name/pinot?forceDelete=true", "DELETE", null, 204, 200);
        }
    }

    /**
     * Polls the Pinot controller until /health answers — the controller takes ~20-40s
     * past compose's "healthy" (which only waits for the process, not the HTTP endpoint),
     * and Ranger setup can occupy most of that window. Poll, never sleep.
     */
    public void awaitControllerReady() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(controllerBase + "/health"))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();
                client.send(request, HttpResponse.BodyHandlers.ofString());
                return;
            } catch (IOException e) {
                Thread.sleep(2000);
            }
        }
        throw new IllegalStateException("Pinot controller not ready after 3 minutes at " + controllerBase);
    }

    /**
     * POSTs the repo's own service-def (single source of truth — read from the checkout).
     */
    public HttpResponse<String> postServiceDef() throws IOException, InterruptedException {
        Path serviceDef = moduleDir.resolve("../ranger-pinot-plugin/src/main/resources/service-defs/ranger-servicedef-pinot.json").normalize();
        if (!Files.isRegularFile(serviceDef)) {
            throw new IOException("service-def not found at " + serviceDef);
        }
        return request(rangerBase + "/service/public/v2/api/servicedef", "POST",
                Files.readString(serviceDef), 200, 201);
    }

    /**
     * POSTs the pinotdev service; configs keys mirror the service-def's configs[]
     * (username / password / controller.url — the service-def's mandatory key).
     */
    public HttpResponse<String> postService() throws IOException, InterruptedException {
        String controllerUrl = "http://pinot-controller:9000";
        String body = "{\"name\":\"pinotdev\",\"type\":\"pinot\",\"configs\":{"
                + "\"username\":\"admin\",\"password\":\"admin\","
                + "\"controller.url\":\"" + controllerUrl + "\"}}";
        return request(rangerBase + "/service/public/v2/api/service", "POST", body, 200, 201);
    }

    public HttpResponse<String> getService() throws IOException, InterruptedException {
        return request(rangerBase + "/service/public/v2/api/service/name/pinotdev", "GET", null, 200);
    }

    /**
     * FOUND-03 surface: explicit validateConfig on the live service. Lives on the v1 ServiceREST
     * API (`POST /service/plugins/services/validateConfig`, body = the full RangerService JSON),
     * not under public/v2. Returns VXResponse {"statusCode":0|1,...}.
     */
    public HttpResponse<String> validateServiceConfig() throws IOException, InterruptedException {
        String body = "{\"name\":\"pinotdev\",\"type\":\"pinot\",\"configs\":{"
                + "\"username\":\"admin\",\"password\":\"admin\","
                + "\"controller.url\":\"http://pinot-controller:9000\"}}";
        return request(rangerBase + "/service/plugins/services/validateConfig", "POST", body, 200, 404, 405);
    }

    /**
     * FOUND-04 surface: lookupResource autocomplete on the v1 ServiceREST API
     * (`POST /service/plugins/services/lookupResource/{serviceName}`). Returns a JSON string array.
     */
    public HttpResponse<String> lookupTables() throws IOException, InterruptedException {
        String body = "{\"resourceName\":\"table\",\"resourceValue\":\"ord\",\"user\":[]}";
        return request(rangerBase + "/service/plugins/services/lookupResource/pinotdev", "POST", body, 200, 404, 405);
    }

    /**
     * Creates a Ranger user (idempotent): Ranger 2.8 rejects policies naming users that
     * do not exist in Ranger admin ("Operation denied ... does not exist in ranger admin").
     */
    public void createUserIfMissing(String name, String password) throws IOException, InterruptedException {
        request(rangerBase + "/service/xusers/users", "POST",
                "{\"name\":\"" + name + "\",\"password\":\"" + password + "\",\"userRole\":[\"ROLE_USER\"]}",
                200, 201, 400, 409);
    }

    public HttpResponse<String> postPolicy(String policyJson) throws IOException, InterruptedException {
        return request(rangerBase + "/service/public/v2/api/policy", "POST", policyJson, 200, 201);
    }

    public HttpResponse<String> deletePolicy(long id) throws IOException, InterruptedException {
        return request(rangerBase + "/service/public/v2/api/policy/" + id, "DELETE", null, 204, 200, 404);
    }

    public HttpResponse<String> updatePolicy(long id, String policyJson) throws IOException, InterruptedException {
        return request(rangerBase + "/service/public/v2/api/policy/" + id, "PUT", policyJson, 200, 201);
    }

    /**
     * Uploads a schema as the provisioning admin principal. Used by the controller-403
     * test: the schema is not the authorization subject under test, but Pinot requires
     * a table's schema to exist before the table create, and the restricted user has
     * no schema grant.
     */
    public HttpResponse<String> postSchemaAsAdmin(String schemaJson) throws IOException, InterruptedException {
        return request(controllerBase + "/schemas", "POST", schemaJson, 200, 201, 400, 409);
    }

    public HttpResponse<String> createTable(String schemaJson, String tableJson) throws IOException, InterruptedException {
        HttpResponse<String> schemaResponse = request(controllerBase + "/schemas", "POST", schemaJson, 200, 201, 400, 403, 409);
        if (schemaResponse.statusCode() != 200 && schemaResponse.statusCode() != 201) {
            return schemaResponse;
        }
        return request(controllerBase + "/tables", "POST", tableJson, 200, 201, 400, 403, 409);
    }

    /**
     * Retries {@link #createTable} until the controller grants the request (200/201)
     * or the deadline passes. The plugin polls Ranger for policies (5s interval), so
     * the first POSTs after service creation can transiently 403.
     */
    public void awaitPolicyEffect(String schemaJson, String tableJson) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                // table create needs the pinot-server registered as DefaultTenant_SERVER /
                // the broker as DefaultTenant_BROKER — registration lags /health by 60s+.
                // A "Failed to find instances with tag" 400 means "not yet" — keep polling.
                HttpResponse<String> response = createTable(schemaJson, tableJson);
                if (response.statusCode() == 200 || response.statusCode() == 201) {
                    return;
                }
                if (response.statusCode() == 409) {
                    return; // idempotent: table already created by an earlier retry
                }
                last = new IOException("table create failed: " + response.statusCode() + " " + response.body());
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(3_000);
        }
        throw new IOException("controller did not grant 'admin' within 120s", last);
    }

    public HttpResponse<String> queryBroker(String sql) throws IOException, InterruptedException {
        String body = "{\"sql\":\"" + sql.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        // Distinct principal from the IT's Ranger-admin user: the broker path derives the
        // principal from this Basic header (see RangerPinotAccessControl.deriveUser), so
        // 'brokeruser' policies are exercised independently of 'admin' policies.
        String brokerAuth = "Basic " + Base64.getEncoder()
                .encodeToString("brokeruser:Brokerpass1".getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(brokerBase + "/query/sql"))
                .header("Authorization", brokerAuth)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> controllerGet(String path) throws IOException, InterruptedException {
        return request(controllerBase + path, "GET", null, 200, 400, 403, 404, 500);
    }

    public HttpResponse<String> controllerPostWithAuth(String path, String body, String user, String password)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(controllerBase + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60));
        if (user != null) {
            String plain = user + ":" + (password == null ? "" : password);
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(plain.getBytes(StandardCharsets.UTF_8)));
        }
        if (body == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * docker compose logs for one service (audit-event grep source). Returns the raw
     * log text; empty string on any process failure.
     */
    public String composeLogs(String service) throws IOException, InterruptedException {
        return composeExec("logs", service);
    }

    /**
     * Runs pinot-admin.sh inside the pinot-controller container
     * (segment generation/upload).
     */
    public String pinotAdmin(List<String> args) throws IOException, InterruptedException {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("/opt/pinot/bin/pinot-admin.sh");
        command.addAll(args);
        return composeExec(execPrefix("pinot-controller", command));
    }

    private String composeExec(String... args) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command().add("docker");
        processBuilder.command().add("compose");
        processBuilder.command().add("-f");
        processBuilder.command().add(composeFile.toString());
        processBuilder.command().add("-p");
        processBuilder.command().add(composeProject);
        for (String arg : args) {
            processBuilder.command().add(arg);
        }
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("docker compose " + String.join(" ", args) + " failed:\n" + output);
        }
        return output;
    }

    private List<String> execPrefix(String service, List<String> args) {
        java.util.ArrayList<String> all = new java.util.ArrayList<>();
        all.add("exec");
        all.add("-T");
        all.add(service);
        all.addAll(args);
        return all;
    }

    /**
     * docker compose exec — list-argument variant used by pinotAdmin(List).
     */
    private String composeExec(List<String> args) throws IOException, InterruptedException {
        return composeExec(args.toArray(new String[0]));
    }

    private HttpResponse<String> request(String url, String method, String body, int... allowedStatus)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", AUTH_HEADER)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60));
        if ("GET".equals(method) || "DELETE".equals(method)) {
            if ("DELETE".equals(method)) {
                builder.DELETE();
            } else {
                builder.GET();
            }
        } else if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        lastStatusCode = response.statusCode();
        for (int status : allowedStatus) {
            if (response.statusCode() == status) {
                return response;
            }
        }
        throw new IOException(method + " " + url + " -> " + response.statusCode() + ": " + response.body());
    }

    private static String systemProperty(String key, String defaultValue) {
        String value = System.getProperty(key);
        return value == null ? defaultValue : value;
    }
}
