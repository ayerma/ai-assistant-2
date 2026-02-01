package com.ayerma.assistant;

import com.ayerma.assistant.client.BaAssistantClient;
import com.ayerma.assistant.client.cli.GitHubCopilotCliClient;
import com.ayerma.assistant.client.models.GitHubModelsClient;
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

        HttpJson jiraHttp = new HttpJson();
        JiraClient jiraClient = new JiraClient(jiraHttp, jiraBaseUrl, jiraEmail, jiraApiToken);

        String outputPath = Env.optional("BA_OUTPUT_PATH", "ba-output.json");

        // Check if we should create Jira tickets from existing output file (CLI workflow post-processing)
        boolean createFromOutput = Env.optional("CREATE_JIRA_FROM_OUTPUT", "false").equalsIgnoreCase("true");
        if (createFromOutput) {
            System.out.println("[INFO] CREATE_JIRA_FROM_OUTPUT mode - reading from " + outputPath);
            
            // Read and parse the BA output JSON file
            String outputContent = Files.readString(Path.of(outputPath), StandardCharsets.UTF_8);
            JsonNode parsed = HttpJson.MAPPER.readTree(outputContent);
            System.out.println("[INFO] Successfully loaded BA output from file");
            
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

        String instructionsPath = Env.optional("BA_INSTRUCTIONS_PATH", "instructions/platform/roles/ba-role.md");
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
            userPrompt = BaPromptBuilder.buildUserPrompt(issueKey, providedSummary, providedDescription);
        } else {
            // Fetch from Jira (existing behavior)
            System.out.println("[INFO] Fetching issue details from Jira API...");
            issue = jiraClient.getIssue(issueKey);
            System.out.println("[INFO] Successfully fetched issue from Jira");
            userPrompt = BaPromptBuilder.buildUserPromptFromJiraIssue(issue);
        }

        // Always write the prompt to file first
        String promptOutputPath = Env.optional("PROMPT_OUTPUT_PATH", "ba-prompt.txt");
        String combinedPrompt = systemPrompt + "\n\n" + userPrompt;
        Files.writeString(Path.of(promptOutputPath), combinedPrompt, StandardCharsets.UTF_8);
        System.out.println("[INFO] Wrote combined prompt to: " + promptOutputPath);

        // Check if we should just output the prompt and exit (CLI mode handles
        // execution in workflow)
        boolean outputPromptOnly = Env.optional("OUTPUT_PROMPT_ONLY", "false").equalsIgnoreCase("true");

        if (outputPromptOnly) {
            System.out.println("[INFO] OUTPUT_PROMPT_ONLY mode - prompt written, exiting");
            System.out.println("[INFO] Workflow will call Copilot CLI with the prompt");
            return;
        }

        System.out.println("[INFO] Calling AI to generate BA output...");

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

        // Validate that output is JSON.
        System.out.println("[INFO] Validating and formatting JSON output...");
        JsonNode parsed = HttpJson.MAPPER.readTree(assistantOutput);

        Files.writeString(Path.of(outputPath),
                HttpJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(parsed), StandardCharsets.UTF_8);
        System.out.println("[SUCCESS] Wrote BA output to: " + outputPath);

        // Create Jira tickets from the parsed output
        createJiraTicketsFromOutput(jiraClient, issueKey, parsed);
    }

    private static void createJiraTicketsFromOutput(JiraClient jiraClient, String issueKey, JsonNode parsed) throws Exception {
        System.out.println("[INFO] Creating Jira linked issues from BA output...");

        JsonNode issue = jiraClient.getIssue(issueKey);
        String projectKey = textAt(issue, "/fields/project/key");
        if (projectKey == null || projectKey.isBlank()) {
            throw new IllegalStateException("Unable to resolve project key from Jira issue: " + issueKey);
        }

        String storyIssueType = Env.optional("JIRA_STORY_ISSUE_TYPE", "Story");
        String taskIssueType = Env.optional("JIRA_TASK_ISSUE_TYPE", "Task");
        String questionIssueType = Env.optional("JIRA_QUESTION_ISSUE_TYPE", "Sub-task");
        String linkType = Env.optional("JIRA_LINK_TYPE", "Relates");
        System.out.println("[DEBUG] Issue types => story: " + storyIssueType + ", task: " + taskIssueType
                + ", question: " + questionIssueType + ", link: " + linkType);
        JsonNode tasks = parsed.get("tasks");
        if (tasks == null || !tasks.isArray() || tasks.isEmpty()) {
            System.out.println("[WARN] No tasks found in BA output - skipping Jira creation");
            return;
        }

        System.out.println("[INFO] Tasks found: " + tasks.size());

        int createdCount = 0;
        java.util.Map<String, String> taskKeyById = new java.util.HashMap<>();
        for (JsonNode task : tasks) {
            String title = textAt(task, "/title");
            String id = textAt(task, "/id");
            String summary = title != null && !title.isBlank() ? title : "Task " + (id != null ? id : "(unnamed)");
            String description = buildTaskDescription(task);

            String ticketType = textAt(task, "/ticket_type");
            String issueTypeName = resolveIssueType(ticketType, storyIssueType, taskIssueType);
            System.out.println("[DEBUG] Creating issue for task " + (id != null ? id : "(no-id)")
                    + " with type=" + issueTypeName + ", summary=" + summary);
            String createdKey = jiraClient.createIssue(projectKey, issueTypeName, summary, description);
            System.out.println("[DEBUG] Linking issue " + createdKey + " to parent " + issueKey
                    + " with link type " + linkType);
            jiraClient.linkIssues(issueKey, createdKey, linkType);
            createdCount++;
            System.out.println("[SUCCESS] Created Jira issue: " + createdKey + " (" + summary + ")");

            if (id != null && !id.isBlank()) {
                taskKeyById.put(id, createdKey);
            }

            JsonNode subTickets = task.get("sub_tickets");
            if (subTickets != null && subTickets.isArray() && !subTickets.isEmpty()) {
                System.out.println("[INFO] Question sub-tickets found for " + createdKey + ": " + subTickets.size());
                for (JsonNode subTicket : subTickets) {
                    String subTitle = textAt(subTicket, "/title");
                    String subId = textAt(subTicket, "/id");
                    String subSummary = subTitle != null && !subTitle.isBlank()
                            ? subTitle
                            : "Question " + (subId != null ? subId : "(unnamed)");
                    String subDescription = buildQuestionDescription(subTicket);

                    System.out.println("[DEBUG] Creating question subtask for parent " + createdKey
                            + " with type=" + questionIssueType + ", summary=" + subSummary);
                    String questionKey = jiraClient.createSubtask(projectKey, questionIssueType, createdKey,
                            subSummary, subDescription);
                    System.out.println("[SUCCESS] Created question subtask: " + questionKey + " (" + subSummary + ")");
                }
            }
        }

        System.out.println("[SUCCESS] Created " + createdCount + " Jira linked issues");
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

    private static String buildTaskDescription(JsonNode task) {
        StringBuilder sb = new StringBuilder();

        String description = textAt(task, "/description");
        String technicalNotes = textAt(task, "/technical_notes");
        String type = textAt(task, "/type");
        String storyPoints = textAt(task, "/story_points");

        if (description != null && !description.isBlank()) {
            sb.append("Description:\n").append(description).append("\n\n");
        }

        if (technicalNotes != null && !technicalNotes.isBlank()) {
            sb.append("Technical Notes:\n").append(technicalNotes).append("\n\n");
        }

        if (type != null && !type.isBlank()) {
            sb.append("Type: ").append(type).append("\n");
        }

        if (storyPoints != null && !storyPoints.isBlank()) {
            sb.append("Story Points: ").append(storyPoints).append("\n");
        }

        JsonNode acceptance = task.get("acceptance_criteria");
        if (acceptance != null && acceptance.isArray() && !acceptance.isEmpty()) {
            sb.append("\nAcceptance Criteria:\n");
            for (JsonNode item : acceptance) {
                if (item != null && item.isTextual()) {
                    sb.append("- ").append(item.asText()).append("\n");
                }
            }
        }

        String result = sb.toString().trim();
        return result.isBlank() ? "See task details in BA output." : result;
    }

    private static String buildQuestionDescription(JsonNode subTicket) {
        StringBuilder sb = new StringBuilder();

        String description = textAt(subTicket, "/description");
        String reason = textAt(subTicket, "/reason");

        if (description != null && !description.isBlank()) {
            sb.append("Description:\n").append(description).append("\n\n");
        }

        if (reason != null && !reason.isBlank()) {
            sb.append("Reason:\n").append(reason).append("\n");
        }

        String result = sb.toString().trim();
        return result.isBlank() ? "Question details required." : result;
    }

    private static String resolveIssueType(String ticketType, String storyIssueType, String taskIssueType) {
        if (ticketType == null) {
            return taskIssueType;
        }
        String normalized = ticketType.trim().toLowerCase();
        if ("story".equals(normalized)) {
            return storyIssueType;
        }
        return taskIssueType;
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
