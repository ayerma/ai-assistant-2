package com.ayerma.assistant;

import com.ayerma.assistant.client.BaAssistantClient;
import com.ayerma.assistant.client.cli.GitHubCopilotCliClient;
import com.ayerma.assistant.client.models.GitHubModelsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ContentCreatorRunner {
    public static void main(String[] args) throws Exception {
        System.out.println("[INFO] Starting Content Creator Runner...");

        String mode = Env.optional("CONTENT_CREATOR_MODE", "legacy");
        System.out.println("[INFO] Mode: " + mode);

        switch (mode) {
            case "questions":
                runQuestionsMode(args);
                break;
            case "answers":
                runAnswersMode(args);
                break;
            case "legacy":
            default:
                runLegacyMode(args);
                break;
        }
    }

    private static void runQuestionsMode(String[] args) throws Exception {
        System.out.println("[INFO] Running in QUESTIONS mode - generating question list...");

        String issueKey = args.length > 0 ? args[0] : Env.required("JIRA_ISSUE_KEY");
        System.out.println("[INFO] Processing Jira issue: " + issueKey);

        String jiraBaseUrl = Env.required("JIRA_BASE_URL");
        String jiraEmail = Env.required("JIRA_EMAIL");
        String jiraApiToken = Env.required("JIRA_API_TOKEN");

        HttpJson jiraHttp = new HttpJson();
        JiraClient jiraClient = new JiraClient(jiraHttp, jiraBaseUrl, jiraEmail, jiraApiToken);

        // Get topic from Jira or input
        String providedSummary = Env.optional("JIRA_ISSUE_SUMMARY", null);
        String topic;
        if (providedSummary != null && !providedSummary.isBlank()) {
            System.out.println("[INFO] Using provided summary as topic (skipping Jira API call)");
            topic = providedSummary;
        } else {
            System.out.println("[INFO] Fetching issue details from Jira API...");
            JsonNode issue = jiraClient.getIssue(issueKey);
            System.out.println("[INFO] Successfully fetched issue from Jira");
            topic = textAt(issue, "/fields/summary");
        }

        // Load instructions for question generation
        String instructionsPath = Env.contentCreatorQuestionsInstructionsPath();
        System.out.println("[INFO] Loading question generation instructions from: " + instructionsPath);
        String systemPrompt = Files.readString(Path.of(instructionsPath), StandardCharsets.UTF_8);

        // Build user prompt for question generation
        String userPrompt = buildQuestionsUserPrompt(issueKey, topic);

        // Always use GitHub Models API for question generation
        String modelsEndpoint = Env.optional("MODELS_ENDPOINT", "https://models.inference.ai.azure.com");
        String modelsApiKey = Env.required("MODELS_TOKEN");
        String model = Env.optional("MODELS_MODEL", "gpt-4o");

        System.out.println("[INFO] Using GitHub Models API: " + model + " at " + modelsEndpoint);

        HttpJson modelsHttp = new HttpJson();
        BaAssistantClient client = new GitHubModelsClient(modelsHttp, modelsEndpoint, modelsApiKey, model);

        System.out.println("[INFO] Calling AI to generate question list...");
        String assistantOutput = client.runBaAssistant(systemPrompt, userPrompt);
        System.out.println("[INFO] Received response from AI");

        // Validate and write questions JSON
        System.out.println("[INFO] Validating and formatting JSON output...");
        JsonNode parsed = HttpJson.MAPPER.readTree(assistantOutput);

        String outputPath = Env.contentCreatorQuestionsOutputPath();
        Files.writeString(Path.of(outputPath),
                HttpJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(parsed),
                StandardCharsets.UTF_8);
        System.out.println("[SUCCESS] Wrote questions list to: " + outputPath);
    }

    private static void runAnswersMode(String[] args) throws Exception {
        System.out.println("[INFO] Running in ANSWERS mode - answering questions...");

        String issueKey = args.length > 0 ? args[0] : Env.required("JIRA_ISSUE_KEY");
        System.out.println("[INFO] Processing Jira issue: " + issueKey);

        String jiraBaseUrl = Env.required("JIRA_BASE_URL");
        String jiraEmail = Env.required("JIRA_EMAIL");
        String jiraApiToken = Env.required("JIRA_API_TOKEN");

        HttpJson jiraHttp = new HttpJson();
        JiraClient jiraClient = new JiraClient(jiraHttp, jiraBaseUrl, jiraEmail, jiraApiToken);

        // Read questions JSON
        String questionsInputPath = Env.contentCreatorQuestionsInputPath();
        System.out.println("[INFO] Reading questions from: " + questionsInputPath);
        String questionsJson = Files.readString(Path.of(questionsInputPath), StandardCharsets.UTF_8);
        JsonNode questionsData = HttpJson.MAPPER.readTree(questionsJson);

        String topic = questionsData.path("topic").asText("");
        JsonNode questions = questionsData.path("questions");

        if (questions == null || !questions.isArray() || questions.isEmpty()) {
            throw new IllegalStateException("Questions array is missing or empty in " + questionsInputPath);
        }

        System.out.println("[INFO] Topic: " + topic);
        System.out.println("[INFO] Found " + questions.size() + " questions to answer");

        // Validate ADDING_CONTENT.md file exists in target repository
        String targetRepoPath = Env.targetRepoPath();
        Path addingContentPath = Path.of(targetRepoPath, "public", "data", "ADDING_CONTENT.md");

        System.out.println("[INFO] Validating ADDING_CONTENT.md at: " + addingContentPath);
        if (!Files.exists(addingContentPath)) {
            System.err.println("[ERROR] ADDING_CONTENT.md file not found at: " + addingContentPath);
            System.err.println("[ERROR] This file contains instructions for properly formatting questions.");
            System.err.println("[ERROR] Please ensure the target repository is checked out to: " + targetRepoPath);
            throw new IllegalStateException("Required file ADDING_CONTENT.md not found at " + addingContentPath);
        }

        if (!Files.isReadable(addingContentPath)) {
            System.err.println("[ERROR] ADDING_CONTENT.md file exists but cannot be read: " + addingContentPath);
            throw new IllegalStateException("Cannot read required file: " + addingContentPath);
        }

        System.out.println("[SUCCESS] ADDING_CONTENT.md file validated successfully");
        String addingContentGuidelines = Files.readString(addingContentPath, StandardCharsets.UTF_8);

        // Load instructions for answering (role + technical Java specialist)
        String answerInstructionsPath = Env.contentCreatorAnswerInstructionsPath();
        String javaSpecialistPath = Env.optional("TECHNICAL_REQUIREMENTS_PATH",
                "instructions/platform/technical/java-specialist.md");

        System.out.println("[INFO] Loading answer instructions from: " + answerInstructionsPath);
        String roleInstructions = Files.readString(Path.of(answerInstructionsPath), StandardCharsets.UTF_8);

        String javaSpecialist = "";
        Path javaSpecPath = Path.of(javaSpecialistPath);
        if (Files.exists(javaSpecPath)) {
            System.out.println("[INFO] Loading Java specialist guidance from: " + javaSpecialistPath);
            javaSpecialist = "\n\n" + Files.readString(javaSpecPath, StandardCharsets.UTF_8);
        }

        // Load content instructions (includes ADDING_CONTENT.md guidelines)
        String contentInstructions = "";
        String contentInstructionsPath = Env.contentInstructionsPath();
        Path contentInstructionsFile = Path.of(contentInstructionsPath);
        if (Files.exists(contentInstructionsFile)) {
            System.out.println("[INFO] Loading content formatting guidelines from: " + contentInstructionsPath);
            contentInstructions = "\n\n" + Files.readString(contentInstructionsFile, StandardCharsets.UTF_8);
            // Note: ADDING_CONTENT.md reference is omitted to reduce prompt size
            // The guidelines in content-instructions.md should be sufficient
        }

        String systemPrompt = roleInstructions + javaSpecialist + contentInstructions
                + "\n\nIMPORTANT: Follow the instructions exactly. Return ONLY the strict JSON object.";

        // Determine which client to use
        boolean useModelsApi = Env.optional("USE_MODELS_API", "true").equalsIgnoreCase("true");
        System.out.println("[INFO] Client mode: " + (useModelsApi ? "GitHub Models API" : "GitHub Copilot CLI"));

        BaAssistantClient client;
        if (useModelsApi) {
            String modelsEndpoint = Env.optional("MODELS_ENDPOINT", "https://models.inference.ai.azure.com");
            String modelsApiKey = Env.required("MODELS_TOKEN");
            String model = Env.optional("MODELS_MODEL", "gpt-4o");
            System.out.println("[INFO] Using model: " + model + " at " + modelsEndpoint);

            HttpJson modelsHttp = new HttpJson();
            client = new GitHubModelsClient(modelsHttp, modelsEndpoint, modelsApiKey, model);
        } else {
            String cliCommand = Env.optional("COPILOT_CLI_COMMAND", "copilot");
            System.out.println("[INFO] Using CLI command: " + cliCommand);
            client = new GitHubCopilotCliClient(cliCommand);
        }

        // Answer each question
        ArrayNode answeredQuestions = HttpJson.MAPPER.createArrayNode();
        int questionNumber = 1;
        int maxRetries = 3;
        for (JsonNode questionNode : questions) {
            String question = questionNode.asText("");
            System.out
                    .println("[INFO] Answering question " + questionNumber + "/" + questions.size() + ": " + question);

            String userPrompt = buildAnswerUserPrompt(topic, question);
            
            // Retry logic for API failures
            String assistantOutput = null;
            Exception lastError = null;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    assistantOutput = client.runBaAssistant(systemPrompt, userPrompt);
                    break; // Success, exit retry loop
                } catch (Exception e) {
                    lastError = e;
                    String errorMsg = e.getMessage();
                    if (errorMsg != null && errorMsg.contains("missing finish_reason")) {
                        System.err.println("[WARN] API call failed (attempt " + attempt + "/" + maxRetries + "): " + errorMsg);
                        if (attempt < maxRetries) {
                            int waitSeconds = attempt * 2; // Exponential backoff: 2s, 4s, 6s
                            System.out.println("[INFO] Retrying in " + waitSeconds + " seconds...");
                            Thread.sleep(waitSeconds * 1000);
                        }
                    } else {
                        // Different error, don't retry
                        throw e;
                    }
                }
            }
            
            if (assistantOutput == null) {
                System.err.println("[ERROR] Failed to get answer after " + maxRetries + " attempts");
                throw new RuntimeException("API call failed: " + (lastError != null ? lastError.getMessage() : "Unknown error"), lastError);
            }
            
            System.out.println("[DEBUG] Received answer for question " + questionNumber);

            // Extract JSON from markdown code blocks if present
            String cleanedOutput = extractJsonFromMarkdown(assistantOutput);

            // Parse the answer JSON
            JsonNode answerData = HttpJson.MAPPER.readTree(cleanedOutput);
            answeredQuestions.add(answerData);

            questionNumber++;
        }

        // Build final output
        ObjectNode finalOutput = HttpJson.MAPPER.createObjectNode();
        finalOutput.put("topic", topic);
        finalOutput.set("questions", answeredQuestions);

        String outputPath = Env.contentCreatorOutputPath();
        Files.writeString(Path.of(outputPath),
                HttpJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(finalOutput),
                StandardCharsets.UTF_8);
        System.out.println("[SUCCESS] Wrote final Q&A output to: " + outputPath);

        // Enrich Jira ticket with Q&A content
        enrichJiraTicketFromOutput(jiraClient, issueKey, finalOutput);
    }

    private static void runLegacyMode(String[] args) throws Exception {
        System.out.println("[INFO] Running in LEGACY mode - single-step Q&A generation...");

        String issueKey = args.length > 0 ? args[0] : Env.required("JIRA_ISSUE_KEY");
        System.out.println("[INFO] Processing Jira issue: " + issueKey);

        String jiraBaseUrl = Env.required("JIRA_BASE_URL");
        String jiraEmail = Env.required("JIRA_EMAIL");
        String jiraApiToken = Env.required("JIRA_API_TOKEN");

        HttpJson jiraHttp = new HttpJson();
        JiraClient jiraClient = new JiraClient(jiraHttp, jiraBaseUrl, jiraEmail, jiraApiToken);

        String outputPath = Env.contentCreatorOutputPath();

        // Check if we should enrich Jira from existing output file
        boolean enrichFromOutput = Env.optional("ENRICH_JIRA_FROM_OUTPUT", "false").equalsIgnoreCase("true");
        if (enrichFromOutput) {
            System.out.println("[INFO] ENRICH_JIRA_FROM_OUTPUT mode - reading from " + outputPath);
            String outputContent = Files.readString(Path.of(outputPath), StandardCharsets.UTF_8);
            JsonNode parsed = HttpJson.MAPPER.readTree(outputContent);
            System.out.println("[INFO] Successfully loaded Content Creator output from file");
            enrichJiraTicketFromOutput(jiraClient, issueKey, parsed);
            return;
        }

        // Determine which client to use
        boolean useModelsApi = Env.optional("USE_MODELS_API", "true").equalsIgnoreCase("true");
        System.out.println("[INFO] Client mode: " + (useModelsApi ? "GitHub Models API" : "GitHub Copilot CLI"));

        String modelsEndpoint = Env.optional("MODELS_ENDPOINT", "https://models.inference.ai.azure.com");
        String modelsApiKey = Env.optional("MODELS_TOKEN", null);
        String model = Env.optional("MODELS_MODEL", "gpt-4o");
        String cliCommand = Env.optional("COPILOT_CLI_COMMAND", "copilot");

        if (useModelsApi) {
            System.out.println("[INFO] Using model: " + model + " at " + modelsEndpoint);
        } else {
            System.out.println("[INFO] Using CLI command: " + cliCommand);
        }

        String instructionsPath = Env.contentCreatorInstructionsPath();
        String technicalReqPath = Env.optional("TECHNICAL_REQUIREMENTS_PATH",
                "instructions/platform/technical/technical-requirements.md");

        System.out.println("[INFO] Loading instructions from: " + instructionsPath);
        String systemPrompt = loadSystemPrompt(instructionsPath, technicalReqPath);

        // Check if summary and description are provided to skip Jira API call
        String providedSummary = Env.optional("JIRA_ISSUE_SUMMARY", null);
        String providedDescription = Env.optional("JIRA_ISSUE_DESCRIPTION", null);

        String userPrompt;
        if (providedSummary != null && !providedSummary.isBlank()) {
            System.out.println("[INFO] Using provided summary as topic (skipping Jira API call)");
            userPrompt = buildUserPrompt(issueKey, providedSummary);
        } else {
            System.out.println("[INFO] Fetching issue details from Jira API...");
            JsonNode issue = jiraClient.getIssue(issueKey);
            System.out.println("[INFO] Successfully fetched issue from Jira");
            String summary = textAt(issue, "/fields/summary");
            userPrompt = buildUserPrompt(issueKey, summary);
        }

        // Always write the prompt to file first
        String promptOutputPath = Env.contentCreatorPromptOutputPath();
        String combinedPrompt = systemPrompt + "\n\n" + userPrompt;
        Files.writeString(Path.of(promptOutputPath), combinedPrompt, StandardCharsets.UTF_8);
        System.out.println("[INFO] Wrote combined prompt to: " + promptOutputPath);
        System.out.println("[INFO] Total prompt length: " + combinedPrompt.length() + " characters");

        // Check if we should just output the prompt and exit
        boolean outputPromptOnly = Env.optional("OUTPUT_PROMPT_ONLY", "false").equalsIgnoreCase("true");

        if (outputPromptOnly) {
            System.out.println("[INFO] OUTPUT_PROMPT_ONLY mode - prompt written, exiting");
            System.out.println("[INFO] Workflow will call Copilot CLI or Models API with the prompt");
            return;
        }

        System.out.println("[INFO] Calling AI to generate Content Creator output...");

        // Create appropriate client based on flag
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
        System.out.println("[SUCCESS] Wrote Content Creator output to: " + outputPath);

        // Enrich Jira ticket with Q&A content
        enrichJiraTicketFromOutput(jiraClient, issueKey, parsed);
    }

    private static String buildQuestionsUserPrompt(String issueKey, String topic) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate a list of Java interview questions for the following topic:\n\n");
        prompt.append("Jira Issue: ").append(issueKey).append("\n");
        prompt.append("Topic: ").append(topic).append("\n\n");
        prompt.append("Generate exactly 15 questions:\n");
        prompt.append("- Include the most popular questions\n");
        prompt.append("- Include underrated, high-signal questions\n");
        prompt.append("- Questions suitable to ask an experienced professional\n");
        prompt.append("- Ordered by gradually increasing difficulty\n\n");
        prompt.append("Return ONLY the JSON object following the exact schema defined in the instructions.\n");
        return prompt.toString();
    }

    private static String buildAnswerUserPrompt(String topic, String question) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Topic: ").append(topic).append("\n\n");
        prompt.append("Question: ").append(question).append("\n\n");
        prompt.append(
                "Provide a comprehensive answer to this Java interview question following the Java specialist guidance.\n");
        prompt.append("Return ONLY the JSON object following the exact schema defined in the instructions.\n");
        return prompt.toString();
    }

    private static String loadSystemPrompt(String instructionsPath, String technicalReqPath) throws Exception {
        System.out.println("[INFO] Reading role instructions from: " + instructionsPath);
        String roleInstructions = Files.readString(Path.of(instructionsPath), StandardCharsets.UTF_8);

        String technicalReq = "";
        Path techPath = Path.of(technicalReqPath);
        if (Files.exists(techPath)) {
            System.out.println("[INFO] Reading technical requirements from: " + technicalReqPath);
            technicalReq = "\n\n" + Files.readString(techPath, StandardCharsets.UTF_8);
        }

        return roleInstructions + technicalReq
                + "\n\nIMPORTANT: Follow the instructions exactly. Return ONLY the strict JSON object.";
    }

    private static String buildUserPrompt(String issueKey, String summary) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate Java interview questions for the following topic:\n\n");
        prompt.append("Jira Issue: ").append(issueKey).append("\n");
        prompt.append("Topic: ").append(summary).append("\n\n");
        prompt.append(
                "Generate 8-12 most common interview questions for this Java topic with comprehensive answers.\n");
        prompt.append("Return ONLY the JSON object following the exact schema defined in the instructions.\n");
        return prompt.toString();
    }

    private static void enrichJiraTicketFromOutput(JiraClient jiraClient, String issueKey, JsonNode output)
            throws Exception {
        System.out.println("[INFO] Enriching Jira ticket with interview Q&A content...");

        String topic = output.path("topic").asText("");
        JsonNode questions = output.path("questions");

        if (questions == null || !questions.isArray() || questions.isEmpty()) {
            System.out.println("[WARN] No questions found in output - skipping Jira enrichment");
            return;
        }

        // Build formatted comment with Q&A content
        StringBuilder comment = new StringBuilder();
        comment.append("📚 Interview Questions Generated\n\n");
        comment.append("*Topic: ").append(topic).append("*\n\n");
        comment.append("---\n\n");

        int questionNumber = 1;
        for (JsonNode questionNode : questions) {
            String question = questionNode.path("question").asText("");
            String answer = questionNode.path("answer").asText("");

            comment.append("*Q").append(questionNumber).append(": ").append(question).append("*\n\n");
            comment.append("A").append(questionNumber).append(": ").append(answer).append("\n\n");
            comment.append("---\n\n");

            questionNumber++;
        }

        comment.append("Total questions: ").append(questions.size()).append("\n");

        // Add comment to Jira ticket
        jiraClient.addComment(issueKey, comment.toString());
        System.out.println("[SUCCESS] Added " + questions.size() + " interview questions to Jira ticket " + issueKey);
    }

    private static String textAt(JsonNode node, String path) {
        JsonNode result = node.at(path);
        if (result.isMissingNode() || result.isNull()) {
            return "";
        }
        return result.asText("");
    }

    /**
     * Extract JSON from markdown code blocks.
     * Handles both ```json ... ``` and ``` ... ``` formats.
     */
    private static String extractJsonFromMarkdown(String output) {
        if (output == null || output.isEmpty()) {
            return output;
        }

        String trimmed = output.trim();

        // Check if output is wrapped in markdown code blocks
        if (trimmed.startsWith("```")) {
            // Find the first newline after opening fence
            int startIndex = trimmed.indexOf('\n');
            if (startIndex == -1) {
                // No newline found, try without fence
                startIndex = 3; // Skip ```
                if (trimmed.startsWith("```json")) {
                    startIndex = 7; // Skip ```json
                }
            } else {
                startIndex++; // Skip the newline
            }

            // Find the closing fence
            int endIndex = trimmed.lastIndexOf("```");
            if (endIndex > startIndex) {
                return trimmed.substring(startIndex, endIndex).trim();
            }
        }

        // No markdown wrapping, return as-is
        return trimmed;
    }
}
