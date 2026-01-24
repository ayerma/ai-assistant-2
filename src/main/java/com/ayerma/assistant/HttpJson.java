package com.ayerma.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpJson {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;

    public HttpJson() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public JsonNode getJson(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " for " + request.uri() + ": " + truncate(response.body()));
        }
        return MAPPER.readTree(response.body());
    }

    public JsonNode postJson(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " for " + request.uri() + ": " + truncate(response.body()));
        }
        if (response.body() == null || response.body().isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(response.body());
    }

    public static HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60));
    }

    private static String truncate(String body) {
        if (body == null)
            return "";
        int max = 1500;
        if (body.length() <= max)
            return body;
        return body.substring(0, max) + "…";
    }
}
