package com.ayerma.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Minimal webhook receiver intended to be deployed somewhere reachable by Jira.
 *
 * Flow:
 * - Jira Webhook -> POST /jira-webhook with issue payload
 * - Server extracts issue key
 * - Server calls GitHub repository_dispatch to trigger a GitHub Action
 */
public final class JiraWebhookServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(Env.optional("PORT", "8080"));
        String sharedSecret = Env.optional("JIRA_WEBHOOK_SECRET", "");

        String ghOwner = Env.required("GITHUB_OWNER");
        String ghRepo = Env.required("GITHUB_REPO");
        String ghToken = Env.required("GITHUB_TOKEN");
        String eventType = Env.optional("GITHUB_DISPATCH_EVENT", "jira_issue_updated");

        HttpJson http = new HttpJson();
        GitHubDispatchClient dispatch = new GitHubDispatchClient(http, ghOwner, ghRepo, ghToken);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/jira-webhook", new JiraWebhookHandler(sharedSecret, dispatch, eventType));
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        System.out.println("JiraWebhookServer listening on port " + port);
    }

    private static final class JiraWebhookHandler implements HttpHandler {
        private final String sharedSecret;
        private final GitHubDispatchClient dispatch;
        private final String eventType;

        private JiraWebhookHandler(String sharedSecret, GitHubDispatchClient dispatch, String eventType) {
            this.sharedSecret = sharedSecret;
            this.dispatch = dispatch;
            this.eventType = eventType;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    send(exchange, 405, "Method Not Allowed");
                    return;
                }

                if (sharedSecret != null && !sharedSecret.isBlank()) {
                    Headers headers = exchange.getRequestHeaders();
                    String provided = headers.getFirst("X-Webhook-Secret");
                    if (provided == null || !provided.equals(sharedSecret)) {
                        send(exchange, 401, "Unauthorized");
                        return;
                    }
                }

                String body;
                try (InputStream in = exchange.getRequestBody()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }

                JsonNode payload = HttpJson.MAPPER.readTree(body);
                String issueKey = extractIssueKey(payload);
                if (issueKey == null || issueKey.isBlank()) {
                    send(exchange, 400, "Could not determine issue key from payload");
                    return;
                }

                try {
                    dispatch.repositoryDispatch(eventType, issueKey);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }

                send(exchange, 202, "Dispatched GitHub workflow for " + issueKey);
            } catch (Exception e) {
                send(exchange, 500, "Error: " + e.getMessage());
            }
        }

        private static String extractIssueKey(JsonNode payload) {
            // Jira webhook common patterns:
            // - payload.issue.key
            // - payload.issue.id (not useful alone)
            // - payload.key (sometimes)
            JsonNode issueKey = payload.at("/issue/key");
            if (!issueKey.isMissingNode() && issueKey.isTextual()) return issueKey.asText();

            JsonNode key = payload.get("key");
            if (key != null && key.isTextual()) return key.asText();

            return null;
        }

        private static void send(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
