package com.ayerma.assistant;

import com.fasterxml.jackson.databind.JsonNode;

public final class BaPromptBuilder {
    private BaPromptBuilder() {}

    public static String buildUserPromptFromJiraIssue(JsonNode issue) {
        String key = textAt(issue, "/key");
        String summary = textAt(issue, "/fields/summary");
        String description = extractDescription(issue.at("/fields/description"));

        StringBuilder sb = new StringBuilder();
        sb.append("Jira issue key: ").append(orUnknown(key)).append("\n");
        sb.append("Title: ").append(orUnknown(summary)).append("\n\n");

        if (description != null && !description.isBlank()) {
            sb.append("Description:\n").append(description).append("\n\n");
        }

        sb.append("Task: Convert this Jira ticket into an implementation plan following the BA role instructions. ")
          .append("Return ONLY the STRICT JSON as specified (no markdown).\n");

        return sb.toString();
    }

    private static String extractDescription(JsonNode descriptionNode) {
        if (descriptionNode == null || descriptionNode.isMissingNode() || descriptionNode.isNull()) {
            return null;
        }
        // Jira Cloud description is often Atlassian Document Format (ADF). We try to extract plain text best-effort.
        StringBuilder sb = new StringBuilder();
        flattenAdfText(descriptionNode, sb);
        String text = sb.toString().replaceAll("[\\u0000-\\u001F]+", " ").trim();
        return text.isBlank() ? null : text;
    }

    private static void flattenAdfText(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) return;

        if (node.isObject()) {
            JsonNode type = node.get("type");
            if (type != null && type.isTextual() && "text".equals(type.asText())) {
                JsonNode text = node.get("text");
                if (text != null && text.isTextual()) {
                    out.append(text.asText());
                }
            }

            JsonNode content = node.get("content");
            if (content != null && content.isArray()) {
                for (JsonNode child : content) {
                    flattenAdfText(child, out);
                    // Heuristic: add whitespace between block-ish nodes.
                    out.append(' ');
                }
            }

            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                flattenAdfText(child, out);
                out.append(' ');
            }
        }
    }

    private static String textAt(JsonNode node, String pointer) {
        JsonNode value = node.at(pointer);
        if (value.isMissingNode() || value.isNull()) return null;
        return value.asText(null);
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "(unknown)" : value;
    }
}
