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

package org.apache.ranger.services.pinot.client;

import org.apache.commons.lang3.StringUtils;
import org.apache.ranger.plugin.client.BaseClient;
import org.apache.ranger.plugin.service.ResourceLookupContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to a Pinot controller's REST API for Ranger Admin's "Test Connection" and
 * table-name autocomplete features. Uses the JDK's built-in {@link HttpClient} rather than
 * pulling in a separate HTTP library dependency.
 */
public class PinotClient {
    private static final Logger LOG = LoggerFactory.getLogger(PinotClient.class);

    static final String CONFIG_CONTROLLER_URL = "controller.url";
    static final String CONFIG_USERNAME = "username";
    static final String CONFIG_PASSWORD = "password";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    // ponytail: hand-rolled scrape of the {"tables":[...]} shape instead of a JSON library
    // dependency; swap in Jackson (already transitively present via ranger-plugins-common) if
    // the controller response shape ever grows past a flat string array.
    private static final Pattern TABLES_ARRAY_PATTERN = Pattern.compile("\"tables\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern QUOTED_STRING_PATTERN = Pattern.compile("\"([^\"]*)\"");

    private final String serviceName;
    private final Map<String, String> configs;
    private final HttpClient httpClient;

    public PinotClient(String serviceName, Map<String, String> configs) {
        this.serviceName = serviceName;
        this.configs = new HashMap<>(configs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    public Map<String, Object> connectionTest() {
        Map<String, Object> responseData = new HashMap<>();
        String controllerUrl = getControllerUrl();

        LOG.debug("==> PinotClient.connectionTest({})", serviceName);

        try {
            HttpResponse<String> response = get(controllerUrl + "/health");

            if (response.statusCode() == 200) {
                String successMsg = "ConnectionTest Successful";

                BaseClient.generateResponseDataMap(true, successMsg, successMsg, null, null, responseData);
            } else {
                String failureMsg = "Unable to connect to Pinot controller [" + controllerUrl + "]. HTTP status: " + response.statusCode();

                BaseClient.generateResponseDataMap(false, failureMsg, failureMsg, null, null, responseData);
            }
        } catch (Exception e) {
            LOG.error("Error connecting to Pinot controller [{}]", controllerUrl, e);

            String failureMsg = "Unable to connect to Pinot controller [" + controllerUrl + "]: " + e.getMessage();

            BaseClient.generateResponseDataMap(false, failureMsg, failureMsg, null, null, responseData);
        }

        LOG.debug("<== PinotClient.connectionTest({}): ret={}", serviceName, responseData);

        return responseData;
    }

    public List<String> getResources(ResourceLookupContext context) {
        List<String> ret = new ArrayList<>();
        String userInput = context == null ? null : context.getUserInput();
        String controllerUrl = getControllerUrl();

        LOG.debug("==> PinotClient.getResources({}) userInput={}", serviceName, userInput);

        try {
            HttpResponse<String> response = get(controllerUrl + "/tables");

            if (response.statusCode() == 200) {
                for (String tableName : parseTableNames(response.body())) {
                    if (StringUtils.isBlank(userInput) || StringUtils.containsIgnoreCase(tableName, userInput)) {
                        ret.add(tableName);
                    }
                }
            } else {
                LOG.warn("Unable to list Pinot tables from [{}]. HTTP status: {}", controllerUrl, response.statusCode());
            }
        } catch (Exception e) {
            LOG.error("Error listing Pinot tables from [{}]", controllerUrl, e);
        }

        LOG.debug("<== PinotClient.getResources({}): ret={}", serviceName, ret);

        return ret;
    }

    @Override
    public String toString() {
        return "PinotClient [serviceName=" + serviceName + ", controllerUrl=" + getControllerUrl() + "]";
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET();

        String basicAuthHeader = buildBasicAuthHeader();

        if (basicAuthHeader != null) {
            requestBuilder.header("Authorization", basicAuthHeader);
        }

        return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String buildBasicAuthHeader() {
        String username = configs.get(CONFIG_USERNAME);
        String password = configs.get(CONFIG_PASSWORD);

        if (StringUtils.isBlank(username)) {
            return null;
        }

        String credentials = username + ":" + (password == null ? "" : password);

        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String getControllerUrl() {
        String controllerUrl = configs.get(CONFIG_CONTROLLER_URL);

        return StringUtils.isBlank(controllerUrl) ? "http://localhost:9000" : StringUtils.removeEnd(controllerUrl, "/");
    }

    private static List<String> parseTableNames(String responseBody) {
        List<String> ret = new ArrayList<>();
        Matcher matcher = TABLES_ARRAY_PATTERN.matcher(responseBody);

        if (matcher.find()) {
            Matcher nameMatcher = QUOTED_STRING_PATTERN.matcher(matcher.group(1));

            while (nameMatcher.find()) {
                ret.add(nameMatcher.group(1));
            }
        }

        return ret;
    }
}
