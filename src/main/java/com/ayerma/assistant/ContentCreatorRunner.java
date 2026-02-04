package com.ayerma.assistant;

import com.ayerma.assistant.client.BaAssistantClient;
import com.ayerma.assistant.client.cli.GitHubCopilotCliClient;
import com.ayerma.assistant.client.models.GitHubModelsClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ContentCreatorRunner {
    public static void main(String[] args) throws Exception {
        System.out.println("[INFO] Starting Content Creator Runner...");

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
        String model = Env.optional("MODELS_MODEL", "gpt-5");
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

        // Check if we should just output the prompt and exit (CLI mode handles
        // execution in workflow)
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

        // Add label to indicate content has been generated
        jiraClient.addLabels(issueKey, "interview-content", "ai-generated");
        System.out.println("[SUCCESS] Added labels to Jira ticket");
    }

    private static String textAt(JsonNode node, String path) {
        JsonNode result = node.at(path);
        if (result.isMissingNode() || result.isNull()) {
            return "";
        }
        return result.asText("");
    }
}
