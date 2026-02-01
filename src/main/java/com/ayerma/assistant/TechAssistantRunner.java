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

        // Check if we're in ATTACH_PR mode
        boolean attachPrMode = Env.optional("ATTACH_PR_TO_JIRA", "false").equalsIgnoreCase("true");
        if (attachPrMode) {
            attachPrToJira();
            return;
        }

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

        String devInstructionsPath = Env.optional("DEV_INSTRUCTIONS_PATH", "instructions/platform/roles/dev-role.md");
        String technicalReqPath = Env.optional("TECHNICAL_REQUIREMENTS_PATH",
                "instructions/platform/technical/technical-requirements.md");
        String outputPath = Env.optional("TECH_OUTPUT_PATH", "tech-output.json");

        System.out.println("[INFO] Loading instructions from: " + devInstructionsPath);
        String systemPrompt = loadSystemPrompt(devInstructionsPath, technicalReqPath);

        HttpJson jiraHttp = new HttpJson();
        JiraClient jira = new JiraClient(jiraHttp, jiraBaseUrl, jiraEmail, jiraApiToken);

        String providedSummary = Env.optional("JIRA_ISSUE_SUMMARY", null);
        String providedDescription = Env.optional("JIRA_ISSUE_DESCRIPTION", null);

        String userPrompt;
        if (providedSummary != null && !providedSummary.isBlank()) {
            System.out.println("[INFO] Using provided summary and description");
            userPrompt = buildContextualPrompt(jira, issueKey, providedSummary, providedDescription);
        } else {
            System.out.println("[INFO] Fetching issue details from Jira API...");
            JsonNode issue = jira.getIssue(issueKey);
            System.out.println("[INFO] Successfully fetched issue from Jira");
            String summary = textAt(issue, "/fields/summary");
            String description = textAt(issue, "/fields/description");
            userPrompt = buildContextualPrompt(jira, issueKey, summary, description);
        }

        String promptOutputPath = Env.optional("TECH_PROMPT_OUTPUT_PATH", "tech-prompt.txt");
        String targetRepoPath = Env.optional("TARGET_REPO_PATH", null);
        if (targetRepoPath != null && !targetRepoPath.isBlank()) {
            userPrompt = userPrompt + "\n\nRepository path: " + targetRepoPath + "\n";
        }

        String combinedPrompt = systemPrompt + "\n\n" + userPrompt;
        Files.writeString(Path.of(promptOutputPath), combinedPrompt, StandardCharsets.UTF_8);
        System.out.println("[INFO] Wrote combined prompt to: " + promptOutputPath);
        System.out.println("[INFO] Total prompt length: " + combinedPrompt.length() + " characters");
        System.out.println("[DEBUG] Prompt preview (first 500 chars):");
        System.out.println(combinedPrompt.substring(0, Math.min(500, combinedPrompt.length())) + "...");

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

    private static void attachPrToJira() throws Exception {
        System.out.println("[INFO] ATTACH_PR_TO_JIRA mode - attaching PR URL to Jira ticket");

        String issueKey = Env.required("JIRA_ISSUE_KEY");
        String outputPath = Env.optional("TECH_OUTPUT_PATH", "tech-output.json");

        System.out.println("[INFO] Reading tech output from: " + outputPath);
        String jsonContent = Files.readString(Path.of(outputPath), StandardCharsets.UTF_8);
        JsonNode output = HttpJson.MAPPER.readTree(jsonContent);

        String prUrl = output.path("pull_request_url").asText(null);
        String summary = output.path("summary").asText("");

        if (prUrl == null || prUrl.isBlank()) {
            throw new IllegalStateException("No pull_request_url found in tech output JSON");
        }

        System.out.println("[INFO] Found PR URL: " + prUrl);

        String jiraBaseUrl = Env.required("JIRA_BASE_URL");
        String jiraEmail = Env.required("JIRA_EMAIL");
        String jiraApiToken = Env.required("JIRA_API_TOKEN");

        HttpJson jiraHttp = new HttpJson();
        JiraClient jira = new JiraClient(jiraHttp, jiraBaseUrl, jiraEmail, jiraApiToken);

        String comment = "✅ Implementation completed\n\n";
        if (!summary.isBlank()) {
            comment += summary + "\n\n";
        }
        comment += "Pull Request: " + prUrl;

        jira.addComment(issueKey, comment);
        System.out.println("[SUCCESS] Attached PR URL to Jira ticket: " + issueKey);
    }

    private static String loadSystemPrompt(String devInstructionsPath, String technicalReqPath)
            throws IOException {
        StringBuilder systemPrompt = new StringBuilder();

        System.out.println("[INFO] Loading senior developer instructions...");
        String devInstructions = Files.readString(Path.of(devInstructionsPath), StandardCharsets.UTF_8);
        systemPrompt.append(devInstructions);

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

    private static String buildContextualPrompt(JiraClient jira, String issueKey, String summary, String description)
            throws Exception {
        System.out.println("[INFO] Building contextual prompt for: " + issueKey);

        StringBuilder prompt = new StringBuilder();

        // Fetch current issue for parent traversal
        JsonNode currentIssue = jira.getIssue(issueKey);

        // Traverse up to find Epic or root ticket for context
        JsonNode parentIssue = findParentEpic(jira, currentIssue);
        if (parentIssue != null) {
            String parentKey = textAt(parentIssue, "/key");
            String parentType = textAt(parentIssue, "/fields/issuetype/name");
            String parentSummary = textAt(parentIssue, "/fields/summary");
            String parentDescription = textAt(parentIssue, "/fields/description");

            System.out.println("[INFO] Found parent context: " + parentKey + " (type: " + parentType + ")");
            prompt.append("# Context: Original Application Idea\n\n");
            prompt.append(parentType).append(": ").append(parentKey).append("\n");
            prompt.append("Summary: ").append(parentSummary).append("\n\n");
            if (parentDescription != null && !parentDescription.isBlank()) {
                prompt.append(parentDescription).append("\n\n");
            }
            prompt.append("---\n\n");
        }

        // Current task
        prompt.append("# Current Task\n\n");
        prompt.append("Issue: ").append(issueKey).append("\n");
        prompt.append("Summary: ").append(summary).append("\n\n");
        if (description != null && !description.isBlank()) {
            prompt.append("Description:\n").append(description).append("\n\n");
        }

        // Fetch question subtasks with answers
        JsonNode subtasks = currentIssue.at("/fields/subtasks");
        if (subtasks.isArray() && subtasks.size() > 0) {
            StringBuilder questionsBlock = new StringBuilder();
            int questionCount = 0;

            for (JsonNode subtask : subtasks) {
                String subtaskKey = textAt(subtask, "/key");
                JsonNode subtaskDetails = jira.getIssue(subtaskKey);
                String subtaskSummary = textAt(subtaskDetails, "/fields/summary");

                // Only include questions (starting with [Question])
                if (subtaskSummary != null && subtaskSummary.startsWith("[Question]")) {
                    String subtaskDescription = textAt(subtaskDetails, "/fields/description");
                    String resolution = textAt(subtaskDetails, "/fields/resolution/name");

                    questionCount++;
                    questionsBlock.append("## Question ").append(questionCount).append("\n");
                    questionsBlock.append("**Q:** ").append(subtaskSummary.replace("[Question]", "").trim())
                            .append("\n\n");

                    if (subtaskDescription != null && !subtaskDescription.isBlank()) {
                        questionsBlock.append("**Context:** ").append(subtaskDescription).append("\n\n");
                    }

                    // Try to get answer from comments or resolution
                    JsonNode comments = subtaskDetails.at("/fields/comment/comments");
                    if (comments.isArray() && comments.size() > 0) {
                        // Get the latest comment as answer
                        JsonNode lastComment = comments.get(comments.size() - 1);
                        String answer = textAt(lastComment, "/body");
                        if (answer != null && !answer.isBlank()) {
                            questionsBlock.append("**A:** ").append(answer).append("\n\n");
                        }
                    } else if (resolution != null && !resolution.equals("Unresolved")) {
                        questionsBlock.append("**Status:** ").append(resolution).append("\n\n");
                    }

                    questionsBlock.append("---\n\n");
                }
            }

            if (questionCount > 0) {
                System.out.println("[INFO] Found " + questionCount + " question subtasks");
                prompt.append("# Additional Details (Questions & Answers)\n\n");
                prompt.append(questionsBlock);
            }
        }

        // Important scope instructions
        prompt.append("# Important Instructions\n\n");
        prompt.append("- You MUST work ONLY on the current task defined above (").append(issueKey).append(")\n");
        prompt.append("- Parent tickets are provided for context only\n");
        prompt.append("- Follow all technical requirements from the technical guide\n");
        prompt.append("- Implement only what is specified in the task description and answered questions\n");
        prompt.append("- Do not add features or functionality beyond the current task scope\n");

        return prompt.toString();
    }

    private static JsonNode findParentEpic(JiraClient jira, JsonNode issue) throws Exception {
        System.out.println("[INFO] Traversing parent hierarchy to find Epic or root ticket");

        JsonNode current = issue;
        int depth = 0;
        int maxDepth = 10; // Prevent infinite loops

        while (depth < maxDepth) {
            String issueType = textAt(current, "/fields/issuetype/name");
            String issueKey = textAt(current, "/key");

            System.out.println("[DEBUG] Checking issue " + issueKey + " (type: " + issueType + ")");

            // STOP CONDITION 1: Found Epic
            if ("Epic".equalsIgnoreCase(issueType)) {
                System.out.println("[INFO] Found Epic: " + issueKey);
                return current;
            }

            // Try to get parent issue
            JsonNode parent = current.at("/fields/parent");
            if (!parent.isMissingNode() && !parent.isNull()) {
                String parentKey = textAt(parent, "/key");
                System.out.println("[DEBUG] Following parent link to: " + parentKey);
                current = jira.getIssue(parentKey);
                depth++;
                continue;
            }

            // STOP CONDITION 2: No more parent tickets (reached root)
            System.out.println("[INFO] No parent ticket found - using " + issueKey + " as root context");
            return current;
        }

        System.out.println("[WARN] Max depth reached while traversing parents");
        return current; // Return current as fallback
    }

    private static String textAt(JsonNode node, String pointer) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.at(pointer);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText(null);
    }
}
