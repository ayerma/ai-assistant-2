package com.ayerma.assistant;

import com.ayerma.assistant.client.BaAssistantClient;
import com.ayerma.assistant.client.cli.GitHubCopilotCliClient;
import com.ayerma.assistant.client.models.GitHubModelsClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ContentSpitterRunner {
    public static void main(String[] args) throws Exception {
        System.out.println("[INFO] Starting Content-Spitter Runner...");

        String issueKey = args.length > 0 ? args[0] : Env.required("JIRA_ISSUE_KEY");
        System.out.println("[INFO] Processing Jira issue: " + issueKey);

        String jiraBaseUrl = Env.required("JIRA_BASE_URL");
        String jiraEmail = Env.required("JIRA_EMAIL");
        String jiraApiToken = Env.required("JIRA_API_TOKEN");

        HttpJson jiraHttp = new HttpJson();
        JiraClient jiraClient = new JiraClient(jiraHttp, jiraBaseUrl, jiraEmail, jiraApiToken);

        String outputPath = Env.contentSpitterOutputPath();

        // Check if we should create Jira tickets from existing output file (CLI workflow post-processing)
        boolean createFromOutput = Env.optional("CREATE_JIRA_FROM_OUTPUT", "false").equalsIgnoreCase("true");
        if (createFromOutput) {
            System.out.println("[INFO] CREATE_JIRA_FROM_OUTPUT mode - reading from " + outputPath);

            // Read and parse the Content-Spitter output JSON file
            String outputContent = Files.readString(Path.of(outputPath), StandardCharsets.UTF_8);
            JsonNode parsed = HttpJson.MAPPER.readTree(outputContent);
            System.out.println("[INFO] Successfully loaded Content-Spitter output from file");

            // Create Jira tickets from the parsed output
            createJiraTicketsFromOutput(jiraClient, issueKey, parsed);
            return;
        }

        // Determine which client to use
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

        String instructionsPath = Env.contentSpitterInstructionsPath();
        String technicalReqPath = Env.optional("TECHNICAL_REQUIREMENTS_PATH",
                "instructions/platform/technical/technical-requirements.md");

        System.out.println("[INFO] Loading instructions from: " + instructionsPath);
        String systemPrompt = loadSystemPrompt(instructionsPath, technicalReqPath);

        // Check if summary and description are provided to skip Jira API call
        String providedSummary = Env.optional("JIRA_ISSUE_SUMMARY", null);
        String providedDescription = Env.optional("JIRA_ISSUE_DESCRIPTION", null);

        String userPrompt;
        JsonNode issue = null;
        if (providedSummary != null && !providedSummary.isBlank()) {
            // Build prompt from provided inputs (skip Jira API call)
            System.out.println("[INFO] Using provided summary and description (skipping Jira API call)");
            userPrompt = buildUserPrompt(issueKey, providedSummary, providedDescription);
        } else {
            // Fetch from Jira (existing behavior)
            System.out.println("[INFO] Fetching issue details from Jira API...");
            issue = jiraClient.getIssue(issueKey);
            System.out.println("[INFO] Successfully fetched issue from Jira");
            userPrompt = buildUserPromptFromJiraIssue(issue);
        }

        // Always write the prompt to file first
        String promptOutputPath = Env.contentSpitterPromptOutputPath();
        String combinedPrompt = systemPrompt + "\n\n" + userPrompt;
        Files.writeString(Path.of(promptOutputPath), combinedPrompt, StandardCharsets.UTF_8);
        System.out.println("[INFO] Wrote combined prompt to: " + promptOutputPath);

        // Check if we should just output the prompt and exit (CLI mode handles execution in workflow)
        boolean outputPromptOnly = Env.optional("OUTPUT_PROMPT_ONLY", "false").equalsIgnoreCase("true");

        if (outputPromptOnly) {
            System.out.println("[INFO] OUTPUT_PROMPT_ONLY mode - prompt written, exiting");
            System.out.println("[INFO] Workflow will call Copilot CLI with the prompt");
            return;
        }

        System.out.println("[INFO] Calling AI to generate Content-Spitter output...");

        // Create appropriate client based on flag (API mode only reaches here)
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

        // Validate that output is JSON
        System.out.println("[INFO] Validating and formatting JSON output...");
        JsonNode parsed = HttpJson.MAPPER.readTree(assistantOutput);

        Files.writeString(Path.of(outputPath),
                HttpJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(parsed), StandardCharsets.UTF_8);
        System.out.println("[SUCCESS] Wrote Content-Spitter output to: " + outputPath);

        // Create Jira tickets from the parsed output
        createJiraTicketsFromOutput(jiraClient, issueKey, parsed);
    }

    private static void createJiraTicketsFromOutput(JiraClient jiraClient, String issueKey, JsonNode parsed)
            throws Exception {
        System.out.println("[INFO] Creating Jira child tasks from Content-Spitter output...");

        // Extract project key from issue key (e.g., "IN-1" -> "IN")
        String projectKey = issueKey.contains("-") ? issueKey.substring(0, issueKey.indexOf('-')) : issueKey;
        System.out.println("[INFO] Extracted project key: " + projectKey + " from issue: " + issueKey);

        String taskIssueType = Env.optional("JIRA_TASK_ISSUE_TYPE", "Task");
        System.out.println("[DEBUG] Task issue type: " + taskIssueType);

        JsonNode subtopics = parsed.get("subtopics");
        if (subtopics == null || !subtopics.isArray() || subtopics.isEmpty()) {
            System.out.println("[WARN] No subtopics found in Content-Spitter output - skipping Jira creation");
            return;
        }

        System.out.println("[INFO] Subtopics found: " + subtopics.size());

        int createdCount = 0;
        for (JsonNode subtopic : subtopics) {
            String title = textAt(subtopic, "/title");
            String description = textAt(subtopic, "/description");

            String summary = title != null && !title.isBlank() ? title : "Content Subtopic";

            System.out.println("[DEBUG] Creating task for subtopic with type=" + taskIssueType + ", parent=" + issueKey
                    + ", summary=" + summary);

            String createdKey = jiraClient.createIssueWithParentAndLabels(projectKey, taskIssueType, issueKey, summary,
                    description, "Content-breaker");
            createdCount++;
            System.out.println("[SUCCESS] Created Jira task: " + createdKey + " (" + summary + ")");
        }

        System.out.println("[SUCCESS] Created " + createdCount + " Jira child tasks with 'Content-breaker' label");
    }

    private static String loadSystemPrompt(String instructionsPath, String technicalReqPath) throws IOException {
        StringBuilder systemPrompt = new StringBuilder();

        // Load Content-Spitter role instructions
        System.out.println("[INFO] Loading Content-Spitter role instructions from: " + instructionsPath);
        String csInstructions = Files.readString(Path.of(instructionsPath), StandardCharsets.UTF_8);
        systemPrompt.append(csInstructions);
        System.out.println("[SUCCESS] Content-Spitter instructions loaded (" + csInstructions.length() + " chars)");

        // Load technical requirements if file exists
        Path techPath = Path.of(technicalReqPath);
        if (Files.exists(techPath)) {
            System.out.println("[INFO] Technical requirements file found: " + technicalReqPath);
            System.out.println("[INFO] Loading technical requirements...");
            systemPrompt.append("\n\n---\n\n");
            systemPrompt.append("## Technical Requirements\n\n");
            String techRequirements = Files.readString(techPath, StandardCharsets.UTF_8);
            systemPrompt.append(techRequirements);
            System.out.println("[SUCCESS] Technical requirements loaded (" + techRequirements.length() + " chars)");
        } else {
            System.out.println("[WARN] Technical requirements file NOT FOUND at: " + technicalReqPath);
            System.out.println("[WARN] Continuing without technical requirements");
        }

        systemPrompt.append("\n\nIMPORTANT: Follow the instructions exactly. Return ONLY the strict JSON object.");
        System.out.println("[SUCCESS] System prompt finalized (total length: " + systemPrompt.length() + " chars)");
        return systemPrompt.toString();
    }

    private static String buildUserPrompt(String issueKey, String summary, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Content Breakdown Request\n\n");
        sb.append("Issue Key: ").append(issueKey).append("\n\n");
        sb.append("## Content Area\n\n");
        sb.append(summary).append("\n\n");

        if (description != null && !description.isBlank()) {
            sb.append("## Content Description\n\n");
            sb.append(description).append("\n\n");
        }

        sb.append(
                "Please analyze this content request and break it down into logical subtopics. Return the JSON structure as specified.");
        return sb.toString();
    }

    private static String buildUserPromptFromJiraIssue(JsonNode issue) {
        JsonNode fields = issue.get("fields");
        if (fields == null) {
            throw new IllegalStateException("Jira issue missing 'fields' node");
        }

        String key = issue.has("key") ? issue.get("key").asText() : "UNKNOWN";
        String summary = fields.has("summary") ? fields.get("summary").asText() : "";
        String description = extractPlainText(fields.get("description"));

        return buildUserPrompt(key, summary, description);
    }

    private static String extractPlainText(JsonNode adfNode) {
        if (adfNode == null || adfNode.isNull()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        extractTextRecursive(adfNode, sb);
        return sb.toString();
    }

    private static void extractTextRecursive(JsonNode node, StringBuilder sb) {
        if (node.isObject()) {
            if (node.has("text")) {
                sb.append(node.get("text").asText());
            }
            if (node.has("content")) {
                JsonNode content = node.get("content");
                if (content.isArray()) {
                    for (JsonNode child : content) {
                        extractTextRecursive(child, sb);
                    }
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                extractTextRecursive(child, sb);
            }
        }
    }

    private static String textAt(JsonNode node, String jsonPointer) {
        JsonNode target = node.at(jsonPointer);
        if (target.isMissingNode() || target.isNull()) {
            return null;
        }
        return target.asText();
    }
}
