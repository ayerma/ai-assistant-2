package com.ayerma.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    public String createIssue(String projectKey, String issueTypeName, String summary, String description)
            throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/rest/api/3/issue");

        System.out.println("[DEBUG] Jira createIssue => project=" + projectKey + ", type=" + issueTypeName
                + ", summary=" + summary);

        ObjectNode fields = HttpJson.MAPPER.createObjectNode();
        fields.putObject("project").put("key", projectKey);
        fields.putObject("issuetype").put("name", issueTypeName);
        fields.put("summary", summary);

        if (description != null && !description.isBlank()) {
            fields.set("description", toAdf(description));
        }

        // Add DEV-AI label
        ArrayNode labels = HttpJson.MAPPER.createArrayNode();
        labels.add("DEV-AI");
        fields.set("labels", labels);

        ObjectNode payload = HttpJson.MAPPER.createObjectNode();
        payload.set("fields", fields);

        HttpRequest request = HttpJson.baseRequest(uri)
                .header("Authorization", basicAuth(email, apiToken))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        JsonNode response = http.postJson(request);
        JsonNode keyNode = response.get("key");
        if (keyNode == null || keyNode.isNull()) {
            throw new IOException("Jira issue creation response missing key");
        }
        return keyNode.asText();
    }

    public String createIssueWithParent(String projectKey, String issueTypeName, String parentKey, String summary,
            String description) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/rest/api/3/issue");

        System.out.println("[DEBUG] Jira createIssueWithParent => project=" + projectKey + ", type=" + issueTypeName
                + ", parent=" + parentKey + ", summary=" + summary);

        ObjectNode fields = HttpJson.MAPPER.createObjectNode();
        fields.putObject("project").put("key", projectKey);
        fields.putObject("issuetype").put("name", issueTypeName);
        fields.putObject("parent").put("key", parentKey);
        fields.put("summary", summary);

        if (description != null && !description.isBlank()) {
            fields.set("description", toAdf(description));
        }

        // Add DEV-AI label
        ArrayNode labels = HttpJson.MAPPER.createArrayNode();
        labels.add("DEV-AI");
        fields.set("labels", labels);

        ObjectNode payload = HttpJson.MAPPER.createObjectNode();
        payload.set("fields", fields);

        HttpRequest request = HttpJson.baseRequest(uri)
                .header("Authorization", basicAuth(email, apiToken))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        JsonNode response = http.postJson(request);
        JsonNode keyNode = response.get("key");
        if (keyNode == null || keyNode.isNull()) {
            throw new IOException("Jira issue creation response missing key");
        }
        return keyNode.asText();
    }

    public String createSubtask(String projectKey, String issueTypeName, String parentKey, String summary,
            String description) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/rest/api/3/issue");

        System.out.println("[DEBUG] Jira createSubtask => project=" + projectKey + ", type=" + issueTypeName
                + ", parent=" + parentKey + ", summary=" + summary);

        ObjectNode fields = HttpJson.MAPPER.createObjectNode();
        fields.putObject("project").put("key", projectKey);
        fields.putObject("issuetype").put("name", issueTypeName);
        fields.putObject("parent").put("key", parentKey);
        fields.put("summary", summary);

        if (description != null && !description.isBlank()) {
            fields.set("description", toAdf(description));
        }

        ObjectNode payload = HttpJson.MAPPER.createObjectNode();
        payload.set("fields", fields);

        HttpRequest request = HttpJson.baseRequest(uri)
                .header("Authorization", basicAuth(email, apiToken))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        JsonNode response = http.postJson(request);
        JsonNode keyNode = response.get("key");
        if (keyNode == null || keyNode.isNull()) {
            throw new IOException("Jira subtask creation response missing key");
        }
        return keyNode.asText();
    }

    public void linkIssues(String inwardKey, String outwardKey, String linkType)
            throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/rest/api/3/issueLink");

        System.out.println("[DEBUG] Jira linkIssues => " + inwardKey + " -> " + outwardKey + " (" + linkType + ")");

        ObjectNode payload = HttpJson.MAPPER.createObjectNode();
        payload.putObject("type").put("name", linkType);
        payload.putObject("inwardIssue").put("key", inwardKey);
        payload.putObject("outwardIssue").put("key", outwardKey);

        HttpRequest request = HttpJson.baseRequest(uri)
                .header("Authorization", basicAuth(email, apiToken))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        http.postJson(request);
    }

    private static String basicAuth(String email, String token) {
        String raw = email + ":" + token;
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null)
            return null;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public void addComment(String issueKey, String comment) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(issueKey, StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl + "/rest/api/3/issue/" + encoded + "/comment");

        System.out.println("[DEBUG] Adding comment to issue: " + issueKey);

        ObjectNode body = HttpJson.MAPPER.createObjectNode();
        body.set("body", toAdf(comment));

        String payload = HttpJson.MAPPER.writeValueAsString(body);
        HttpRequest request = HttpJson.baseRequest(uri)
                .header("Authorization", basicAuth(email, apiToken))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        JsonNode response = http.postJson(request);
        System.out.println("[SUCCESS] Added comment to " + issueKey);
    }

    public void addLabels(String issueKey, String... labels) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(issueKey, StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl + "/rest/api/3/issue/" + encoded);

        System.out.println("[DEBUG] Adding labels to issue: " + issueKey + " => " + String.join(", ", labels));

        ObjectNode update = HttpJson.MAPPER.createObjectNode();
        ArrayNode labelsArray = HttpJson.MAPPER.createArrayNode();
        for (String label : labels) {
            ObjectNode addOp = HttpJson.MAPPER.createObjectNode();
            addOp.put("add", label);
            labelsArray.add(addOp);
        }
        update.set("labels", labelsArray);

        ObjectNode payload = HttpJson.MAPPER.createObjectNode();
        payload.set("update", update);

        HttpRequest request = HttpJson.baseRequest(uri)
                .header("Authorization", basicAuth(email, apiToken))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        http.send(request);
        System.out.println("[SUCCESS] Added labels to " + issueKey);
    }

    private static ObjectNode toAdf(String text) {
        ObjectNode doc = HttpJson.MAPPER.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);

        ArrayNode content = HttpJson.MAPPER.createArrayNode();
        doc.set("content", content);

        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            ObjectNode paragraph = HttpJson.MAPPER.createObjectNode();
            paragraph.put("type", "paragraph");

            ArrayNode pContent = HttpJson.MAPPER.createArrayNode();
            paragraph.set("content", pContent);

            ObjectNode textNode = HttpJson.MAPPER.createObjectNode();
            textNode.put("type", "text");
            textNode.put("text", line);
            pContent.add(textNode);

            content.add(paragraph);
        }

        return doc;
    }
}
