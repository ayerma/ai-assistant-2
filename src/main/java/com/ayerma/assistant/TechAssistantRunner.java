package com.ayerma.assistant;

import com.ayerma.assistant.client.BaAssistantClient;
import com.ayerma.assistant.client.cli.GitHubCopilotCliClient;
import com.ayerma.assistant.client.models.GitHubModelsClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TechAssistantRunner {
    public static void main(String[] args) throws Exception {
        System.out.println("[INFO] Starting Tech Assistant Runner...");

        String issueKey = args.length > 0 ? args[0] : Env.required("JIRA_ISSUE_KEY");
        System.out.println("[INFO] Processing Jira issue: " + issueKey);

        String jiraBaseUrl = Env.required("JIRA_BASE_URL");
        String jiraEmail = Env.required("JIRA_EMAIL");
        String jiraApiToken = Env.required("JIRA_API_TOKEN");

        boolean useModelsApi = Env.optional("USE_MODELS_API", "true").equalsIgnoreCase("true");
        System.out.println("[INFO] Client mode: " + (useModelsApi ? "GitHub Models API" : "GitHub Copilot CLI"));

        String modelsEndpoint = Env.optional("MODELS_ENDPOINT", "https://models.inference.ai.azure.com");
        String modelsApiKey = Env.optional("MODELS_TOKEN", null);
        String model = Env.optional("MODELS_MODEL", "gpt-4o-mini");
        String cliCommand = Env.optional("COPILOT_CLI_COMMAND", "copilot");

        if (useModelsApi) {
            System.out.println("[INFO] Using model: " + model + " at " + modelsEndpoint);
        } else {
            System.out.println("[INFO] Using CLI command: " + cliCommand);
        }

        String baInstructionsPath = Env.optional("BA_INSTRUCTIONS_PATH", "instructions/platform/roles/ba-role.md");
        String devInstructionsPath = Env.optional("DEV_INSTRUCTIONS_PATH", "instructions/platform/roles/dev-role.md");
        String technicalReqPath = Env.optional("TECHNICAL_REQUIREMENTS_PATH",
                "instructions/platform/technical/technical-requirements.md");
        String outputPath = Env.optional("TECH_OUTPUT_PATH", "tech-output.json");

        System.out.println("[INFO] Loading instructions from: " + baInstructionsPath);
        String systemPrompt = loadSystemPrompt(baInstructionsPath, devInstructionsPath, technicalReqPath);

        HttpJson jiraHttp = new HttpJson();

        String providedSummary = Env.optional("JIRA_ISSUE_SUMMARY", null);
        String providedDescription = Env.optional("JIRA_ISSUE_DESCRIPTION", null);

        String userPrompt;
        if (providedSummary != null && !providedSummary.isBlank()) {
            System.out.println("[INFO] Using provided summary and description (skipping Jira API call)");
            userPrompt = BaPromptBuilder.buildUserPrompt(issueKey, providedSummary, providedDescription);
        } else {
            System.out.println("[INFO] Fetching issue details from Jira API...");
            JiraClient jira = new JiraClient(jiraHttp, jiraBaseUrl, jiraEmail, jiraApiToken);
            JsonNode issue = jira.getIssue(issueKey);
            System.out.println("[INFO] Successfully fetched issue from Jira");
            userPrompt = BaPromptBuilder.buildUserPromptFromJiraIssue(issue);
        }

        String promptOutputPath = Env.optional("TECH_PROMPT_OUTPUT_PATH", "tech-prompt.txt");
        String targetRepoPath = Env.optional("TARGET_REPO_PATH", null);
        if (targetRepoPath != null && !targetRepoPath.isBlank()) {
            userPrompt = userPrompt + "\nRepository path: " + targetRepoPath + "\n";
        }

        String combinedPrompt = systemPrompt + "\n\n" + userPrompt;
        Files.writeString(Path.of(promptOutputPath), combinedPrompt, StandardCharsets.UTF_8);
        System.out.println("[INFO] Wrote combined prompt to: " + promptOutputPath);

        boolean outputPromptOnly = Env.optional("OUTPUT_PROMPT_ONLY", "false").equalsIgnoreCase("true");

        if (outputPromptOnly) {
            System.out.println("[INFO] OUTPUT_PROMPT_ONLY mode - prompt written, exiting");
            System.out.println("[INFO] Workflow will call Copilot CLI with the prompt");
            return;
        }

        System.out.println("[INFO] Calling AI to generate Tech output...");

        BaAssistantClient client;
        if (useModelsApi) {
            if (modelsApiKey == null || modelsApiKey.isBlank()) {
                throw new IllegalStateException("MODELS_TOKEN is required when USE_MODELS_API=true");
            }
            HttpJson modelsHttp = new HttpJson();
            client = new GitHubModelsClient(modelsHttp, modelsEndpoint, modelsApiKey, model);
        } else {
            client = new GitHubCopilotCliClient(cliCommand);
        }

        String assistantOutput = client.runBaAssistant(systemPrompt, userPrompt);
        System.out.println("[INFO] Received response from AI");
        System.out.println("[DEBUG] AI Response:\n" + assistantOutput);

        System.out.println("[INFO] Validating and formatting JSON output...");
        JsonNode parsed = HttpJson.MAPPER.readTree(assistantOutput);

        Files.writeString(Path.of(outputPath),
                HttpJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(parsed), StandardCharsets.UTF_8);
        System.out.println("[SUCCESS] Wrote Tech output to: " + outputPath);
    }

    private static String loadSystemPrompt(String baInstructionsPath, String devInstructionsPath,
            String technicalReqPath)
            throws IOException {
        StringBuilder systemPrompt = new StringBuilder();

        System.out.println("[INFO] Loading BA role instructions...");
        String baInstructions = Files.readString(Path.of(baInstructionsPath), StandardCharsets.UTF_8);
        systemPrompt.append(baInstructions);

        Path devPath = Path.of(devInstructionsPath);
        if (Files.exists(devPath)) {
            System.out.println("[INFO] Loading senior developer instructions from: " + devInstructionsPath);
            systemPrompt.append("\n\n---\n\n");
            String devInstructions = Files.readString(devPath, StandardCharsets.UTF_8);
            systemPrompt.append(devInstructions);
        } else {
            System.out.println("[WARN] Senior developer instructions file not found: " + devInstructionsPath);
        }

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
