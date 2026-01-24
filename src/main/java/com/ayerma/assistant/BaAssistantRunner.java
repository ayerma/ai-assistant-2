package com.ayerma.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BaAssistantRunner {
    public static void main(String[] args) throws Exception {
        String issueKey = args.length > 0 ? args[0] : Env.required("JIRA_ISSUE_KEY");

        String jiraBaseUrl = Env.required("JIRA_BASE_URL");
        String jiraEmail = Env.required("JIRA_EMAIL");
        String jiraApiToken = Env.required("JIRA_API_TOKEN");

        String modelsEndpoint = Env.optional("MODELS_ENDPOINT", "https://models.inference.ai.azure.com");
        String modelsApiKey = Env.required("MODELS_TOKEN");
        String model = Env.optional("MODELS_MODEL", "gpt-4o-mini");

        String instructionsPath = Env.optional("BA_INSTRUCTIONS_PATH", "instructions/platform/ba-role.md");
        String outputPath = Env.optional("BA_OUTPUT_PATH", "ba-output.json");

        String systemPrompt = loadSystemPrompt(instructionsPath);

        HttpJson http = new HttpJson();
        JiraClient jira = new JiraClient(http, jiraBaseUrl, jiraEmail, jiraApiToken);
        JsonNode issue = jira.getIssue(issueKey);

        String userPrompt = BaPromptBuilder.buildUserPromptFromJiraIssue(issue);

        GitHubModelsClient models = new GitHubModelsClient(http, modelsEndpoint, modelsApiKey, model);
        String assistantOutput = models.runBaAssistant(systemPrompt, userPrompt);

        // Validate that output is JSON.
        JsonNode parsed = HttpJson.MAPPER.readTree(assistantOutput);

        Files.writeString(Path.of(outputPath),
                HttpJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(parsed), StandardCharsets.UTF_8);
        System.out.println("Wrote BA output to: " + outputPath);
    }

    private static String loadSystemPrompt(String instructionsPath) throws IOException {
        String instructions = Files.readString(Path.of(instructionsPath), StandardCharsets.UTF_8);
        return instructions + "\n\nIMPORTANT: Follow the instructions exactly. Return ONLY the strict JSON object.";
    }
}
