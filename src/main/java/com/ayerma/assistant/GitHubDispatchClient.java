package com.ayerma.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;

public final class GitHubDispatchClient {
    private final HttpJson http;
    private final String owner;
    private final String repo;
    private final String token;

    public GitHubDispatchClient(HttpJson http, String owner, String repo, String token) {
        this.http = http;
        this.owner = owner;
        this.repo = repo;
        this.token = token;
    }

    public void repositoryDispatch(String eventType, String issueKey) throws IOException, InterruptedException {
        ObjectNode payload = HttpJson.MAPPER.createObjectNode();
        payload.put("event_type", eventType);
        payload.putObject("client_payload").put("issue_key", issueKey);

        URI uri = URI.create("https://api.github.com/repos/" + owner + "/" + repo + "/dispatches");
        HttpRequest request = HttpJson.baseRequest(uri)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        // GitHub returns 204 No Content on success.
        http.postJson(request);
    }
}
