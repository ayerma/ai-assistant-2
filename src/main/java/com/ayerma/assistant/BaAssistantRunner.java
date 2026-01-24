package com.ayerma.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BaAssistantRunner {
    public static void main(String[] args) throws Exception {
        System.out.println("[INFO] Starting BA Assistant Runner...");

        String issueKey = args.length > 0 ? args[0] : Env.required("JIRA_ISSUE_KEY");
        System.out.println("[INFO] Processing Jira issue: " + issueKey);

        String jiraBaseUrl = Env.required("JIRA_BASE_URL");
        String jiraEmail = Env.required("JIRA_EMAIL");
        String jiraApiToken = Env.required("JIRA_API_TOKEN");

        String modelsEndpoint = Env.optional("MODELS_ENDPOINT", "https://models.inference.ai.azure.com");
        String modelsApiKey = Env.required("MODELS_TOKEN");
        String model = Env.optional("MODELS_MODEL", "gpt-4o-mini");
        System.out.println("[INFO] Using model: " + model + " at " + modelsEndpoint);

        String instructionsPath = Env.optional("BA_INSTRUCTIONS_PATH", "instructions/platform/roles/ba-role.md");
        String technicalReqPath = Env.optional("TECHNICAL_REQUIREMENTS_PATH",
                "instructions/platform/technical/technical-requirements.md");
        String outputPath = Env.optional("BA_OUTPUT_PATH", "ba-output.json");

        System.out.println("[INFO] Loading instructions from: " + instructionsPath);
        String systemPrompt = loadSystemPrompt(instructionsPath, technicalReqPath);

        HttpJson http = new HttpJson();

        // Check if summary and description are provided to skip Jira API call
        String providedSummary = Env.optional("JIRA_ISSUE_SUMMARY", null);
        String providedDescription = Env.optional("JIRA_ISSUE_DESCRIPTION", null);

        String userPrompt;
        if (providedSummary != null && !providedSummary.isBlank()) {
            // Build prompt from provided inputs (skip Jira API call)
            System.out.println("[INFO] Using provided summary and description (skipping Jira API call)");
            userPrompt = BaPromptBuilder.buildUserPrompt(issueKey, providedSummary, providedDescription);
        } else {
            // Fetch from Jira (existing behavior)
            System.out.println("[INFO] Fetching issue details from Jira API...");
            JiraClient jira = new JiraClient(http, jiraBaseUrl, jiraEmail, jiraApiToken);
            JsonNode issue = jira.getIssue(issueKey);
            System.out.println("[INFO] Successfully fetched issue from Jira");
            userPrompt = BaPromptBuilder.buildUserPromptFromJiraIssue(issue);
        }

        System.out.println("[INFO] Calling AI model to generate BA output...");
        GitHubModelsClient models = new GitHubModelsClient(http, modelsEndpoint, modelsApiKey, model);
        String assistantOutput = models.runBaAssistant(systemPrompt, userPrompt);
        System.out.println("[INFO] Received response from AI model");

        // Validate that output is JSON.
        System.out.println("[INFO] Validating and formatting JSON output...");
        JsonNode parsed = HttpJson.MAPPER.readTree(assistantOutput);

        Files.writeString(Path.of(outputPath),
                HttpJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(parsed), StandardCharsets.UTF_8);
        System.out.println("[SUCCESS] Wrote BA output to: " + outputPath);
    }

    private static String loadSystemPrompt(String instructionsPath, String technicalReqPath) throws IOException {
        StringBuilder systemPrompt = new StringBuilder();

        // Load BA role instructions
        System.out.println("[INFO] Loading BA role instructions...");
        String baInstructions = Files.readString(Path.of(instructionsPath), StandardCharsets.UTF_8);
        systemPrompt.append(baInstructions);

        // Load technical requirements if file exists
        Path techPath = Path.of(technicalReqPath);
        if (Files.exists(techPath)) {
            System.out.println("[INFO] Loading technical requirements from: " + technicalReqPath);
            systemPrompt.append("\n\n---\n\n");
            systemPrompt.append("## Technical Requirements\n\n");
            String techRequirements = Files.readString(techPath, StandardCharsets.UTF_8);
            systemPrompt.append(techRequirements);
        } else {
            System.out.println("[WARN] Technical requirements file not found: " + technicalReqPath);
        }

        systemPrompt.append("\n\nIMPORTANT: Follow the instructions exactly. Return ONLY the strict JSON object.");
        System.out.println("[INFO] System prompt loaded (total length: " + systemPrompt.length() + " chars)");
        return systemPrompt.toString();
    }
}
