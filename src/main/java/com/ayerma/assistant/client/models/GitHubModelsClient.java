package com.ayerma.assistant.client.models;

import com.ayerma.assistant.HttpJson;
import com.ayerma.assistant.client.BaAssistantClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;

public final class GitHubModelsClient implements BaAssistantClient {
    private final HttpJson http;
    private final String endpoint;
    private final String apiKey;
    private final String model;

    public GitHubModelsClient(HttpJson http, String endpoint, String apiKey, String model) {
        this.http = http;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * Calls a Chat Completions compatible endpoint.
     *
     * Defaults are aimed at GitHub Models (Azure AI Inference compatible).
     */
    @Override
    public String runBaAssistant(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        ObjectNode payload = HttpJson.MAPPER.createObjectNode();
        payload.put("model", model);

        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        // Encourage strict JSON output.
        payload.putObject("response_format").put("type", "json_object");
        payload.put("temperature", 0.2);

        URI uri = URI.create(endpoint + "/chat/completions");

        HttpRequest request = HttpJson.baseRequest(uri)
                .header("Content-Type", "application/json")
                // GitHub Models uses api-key header on the Azure AI Inference endpoint.
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        JsonNode response = http.postJson(request);
        JsonNode content = response.at("/choices/0/message/content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IOException("Unexpected model response shape: missing choices[0].message.content");
        }
        return content.asText();
    }
}
