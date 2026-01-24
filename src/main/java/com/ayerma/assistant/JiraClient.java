package com.ayerma.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class JiraClient {
    private final HttpJson http;
    private final String baseUrl;
    private final String email;
    private final String apiToken;

    public JiraClient(HttpJson http, String baseUrl, String email, String apiToken) {
        this.http = http;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.email = email;
        this.apiToken = apiToken;
    }

    public JsonNode getIssue(String issueKey) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(issueKey, StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl + "/rest/api/3/issue/" + encoded);
        HttpRequest request = HttpJson.baseRequest(uri)
                .header("Authorization", basicAuth(email, apiToken))
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.getJson(request);
    }

    private static String basicAuth(String email, String token) {
        String raw = email + ":" + token;
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) return null;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
