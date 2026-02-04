package com.ayerma.assistant;

import com.ayerma.assistant.client.BaAssistantClient;
import com.ayerma.assistant.client.cli.GitHubCopilotCliClient;
import com.ayerma.assistant.client.models.GitHubModelsClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TroubleshooterRunner {
    public static void main(String[] args) throws Exception {
        System.out.println("[INFO] Starting Troubleshooter Runner...");

        String issueKey = args.length > 0 ? args[0] : Env.required("JIRA_ISSUE_KEY");
        System.out.println("[INFO] Processing Jira issue: " + issueKey);

        String jiraBaseUrl = Env.required("JIRA_BASE_URL");
        String jiraEmail = Env.required("JIRA_EMAIL");
        String jiraApiToken = Env.required("JIRA_API_TOKEN");

        HttpJson jiraHttp = new HttpJson();
        JiraClient jiraClient = new JiraClient(jiraHttp, jiraBaseUrl, jiraEmail, jiraApiToken);

        String outputPath = Env.optional("TROUBLESHOOTER_OUTPUT_PATH", "troubleshooter-output.json");

        // Check if we should create Jira tickets from existing output file (CLI
        // workflow post-processing)
        boolean createFromOutput = Env.optional("CREATE_JIRA_FROM_OUTPUT", "false").equalsIgnoreCase("true");
        if (createFromOutput) {
            System.out.println("[INFO] CREATE_JIRA_FROM_OUTPUT mode - reading from " + outputPath);

            String outputContent = Files.readString(Path.of(outputPath), StandardCharsets.UTF_8);
            JsonNode parsed = HttpJson.MAPPER.readTree(outputContent);
            System.out.println("[INFO] Successfully loaded troubleshooter output from file");

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

        String instructionsPath = Env.optional("TROUBLESHOOTER_INSTRUCTIONS_PATH",
                "instructions/platform/roles/troubleshooter-role.md");

        System.out.println("[INFO] Loading instructions from: " + instructionsPath);
        String systemPrompt = loadSystemPrompt(instructionsPath);

        System.out.println("[INFO] Fetching issue details from Jira API...");
        JsonNode issue = jiraClient.getIssue(issueKey);
        System.out.println("[INFO] Successfully fetched issue from Jira");

        String userPrompt = buildTroubleshooterPrompt(jiraClient, issue);

        String promptOutputPath = Env.optional("TROUBLESHOOTER_PROMPT_OUTPUT_PATH", "troubleshooter-prompt.txt");
        String combinedPrompt = systemPrompt + "\n\n" + userPrompt;
        Files.writeString(Path.of(promptOutputPath), combinedPrompt, StandardCharsets.UTF_8);
        System.out.println("[INFO] Wrote combined prompt to: " + promptOutputPath);

        boolean outputPromptOnly = Env.optional("OUTPUT_PROMPT_ONLY", "false").equalsIgnoreCase("true");

        if (outputPromptOnly) {
            System.out.println("[INFO] OUTPUT_PROMPT_ONLY mode - prompt written, exiting");
            System.out.println("[INFO] Workflow will call Copilot CLI with the prompt");
            return;
        }

        System.out.println("[INFO] Calling AI to generate troubleshooting output...");

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
        System.out.println("[SUCCESS] Wrote troubleshooter output to: " + outputPath);

        createJiraTicketsFromOutput(jiraClient, issueKey, parsed);
    }

    private static String loadSystemPrompt(String instructionsPath) throws IOException {
        System.out.println("[INFO] Loading troubleshooter instructions from: " + instructionsPath);
        String instructions = Files.readString(Path.of(instructionsPath), StandardCharsets.UTF_8);
        System.out.println("[SUCCESS] Troubleshooter instructions loaded (" + instructions.length() + " chars)");

        String result = instructions
                + "\n\nIMPORTANT: Follow the instructions exactly. Return ONLY the strict JSON object.";
        System.out.println("[SUCCESS] System prompt finalized (total length: " + result.length() + " chars)");
        return result;
    }

    private static String buildTroubleshooterPrompt(JiraClient jira, JsonNode issue) throws Exception {
        String issueKey = textAt(issue, "/key");
        String summary = textAt(issue, "/fields/summary");
        String description = textAt(issue, "/fields/description");
        String issueType = textAt(issue, "/fields/issuetype/name");

        System.out.println("[INFO] Building troubleshooter prompt for: " + issueKey);

        StringBuilder prompt = new StringBuilder();

        prompt.append("# Issue to Troubleshoot\n\n");
        prompt.append("**Issue Key:** ").append(issueKey).append("\n");
        prompt.append("**Type:** ").append(issueType != null ? issueType : "Unknown").append("\n");
        prompt.append("**Summary:** ").append(summary != null ? summary : "No summary").append("\n\n");

        if (description != null && !description.isBlank()) {
            prompt.append("**Description:**\n").append(description).append("\n\n");
        }

        // Fetch related issues (issuelinks)
        JsonNode issueLinks = issue.at("/fields/issuelinks");
        if (issueLinks.isArray() && issueLinks.size() > 0) {
            System.out.println("[INFO] Found " + issueLinks.size() + " related issues");
            prompt.append("# Related Issues\n\n");

            int relatedCount = 0;
            for (JsonNode link : issueLinks) {
                JsonNode relatedIssue = null;
                String linkType = textAt(link, "/type/name");

                // Check both inward and outward issue
                if (link.has("inwardIssue")) {
                    relatedIssue = link.get("inwardIssue");
                } else if (link.has("outwardIssue")) {
                    relatedIssue = link.get("outwardIssue");
                }

                if (relatedIssue != null) {
                    String relatedKey = textAt(relatedIssue, "/key");
                    System.out.println("[INFO] Fetching related issue: " + relatedKey);

                    try {
                        JsonNode relatedDetails = jira.getIssue(relatedKey);
                        String relatedSummary = textAt(relatedDetails, "/fields/summary");
                        String relatedDescription = textAt(relatedDetails, "/fields/description");
                        String relatedStatus = textAt(relatedDetails, "/fields/status/name");

                        relatedCount++;
                        prompt.append("## Related Issue ").append(relatedCount).append(": ")
                                .append(relatedKey).append("\n\n");
                        prompt.append("**Link Type:** ").append(linkType != null ? linkType : "Related").append("\n");
                        prompt.append("**Status:** ").append(relatedStatus != null ? relatedStatus : "Unknown")
                                .append("\n");
                        prompt.append("**Summary:** ").append(relatedSummary != null ? relatedSummary : "No summary")
                                .append("\n\n");

                        if (relatedDescription != null && !relatedDescription.isBlank()) {
                            prompt.append("**Description:**\n").append(relatedDescription).append("\n\n");
                        }

                        prompt.append("---\n\n");
                    } catch (Exception e) {
                        System.out
                                .println("[WARN] Could not fetch related issue " + relatedKey + ": " + e.getMessage());
                    }
                }
            }
        }

        prompt.append("# Task\n\n");
        prompt.append("Analyze the issue and all related context provided above. ");
        prompt.append("Provide troubleshooting guidance split into:\n");
        prompt.append("1. **Technical fixes** - code or configuration changes in the repository\n");
        prompt.append("2. **Manual actions** - steps requiring human intervention outside the repository\n\n");
        prompt.append("Return the strict JSON format as specified in the instructions.\n");

        return prompt.toString();
    }

    private static void createJiraTicketsFromOutput(JiraClient jiraClient, String issueKey, JsonNode parsed)
            throws Exception {
        System.out.println("[INFO] Creating Jira tickets from troubleshooter output...");

        String projectKey = issueKey.contains("-") ? issueKey.substring(0, issueKey.indexOf('-')) : issueKey;
        System.out.println("[INFO] Extracted project key: " + projectKey);

        String taskIssueType = Env.optional("JIRA_TASK_ISSUE_TYPE", "Task");
        String linkType = Env.optional("JIRA_LINK_TYPE", "Blocks");

        int createdCount = 0;

        // Create technical fix ticket
        JsonNode technicalFix = parsed.get("technical_fix");
        if (technicalFix != null && !technicalFix.isNull()) {
            String title = textAt(technicalFix, "/title");
            String description = textAt(technicalFix, "/description");
            JsonNode steps = technicalFix.get("steps");
            String verification = textAt(technicalFix, "/verification");
            JsonNode relatedTickets = technicalFix.get("related_tickets");

            StringBuilder fullDescription = new StringBuilder();
            if (description != null && !description.isBlank()) {
                fullDescription.append(description).append("\n\n");
            }

            if (steps != null && steps.isArray() && steps.size() > 0) {
                fullDescription.append("## Steps to Fix\n\n");
                int stepNum = 1;
                for (JsonNode step : steps) {
                    fullDescription.append(stepNum++).append(". ").append(step.asText()).append("\n");
                }
                fullDescription.append("\n");
            }

            if (verification != null && !verification.isBlank()) {
                fullDescription.append("## Verification\n\n").append(verification).append("\n\n");
            }

            if (relatedTickets != null && relatedTickets.isArray() && relatedTickets.size() > 0) {
                fullDescription.append("## Related Tickets\n\n");
                for (JsonNode ticket : relatedTickets) {
                    fullDescription.append("- ").append(ticket.asText()).append("\n");
                }
            }

            String summary = title != null && !title.isBlank() ? title : "Technical Fix for " + issueKey;

            System.out.println("[INFO] Creating technical fix ticket");
            String technicalKey = jiraClient.createIssue(projectKey, taskIssueType, summary,
                    fullDescription.toString());
            System.out.println("[SUCCESS] Created technical fix ticket: " + technicalKey);

            // Add BA-DEV label
            System.out.println("[INFO] Adding BA-DEV label to " + technicalKey);
            jiraClient.addLabels(technicalKey, "BA-DEV");

            // Link as blocker
            System.out.println("[INFO] Linking " + technicalKey + " as blocker to " + issueKey);
            jiraClient.linkIssues(technicalKey, issueKey, linkType);

            createdCount++;
        } else {
            System.out.println("[INFO] No technical fix needed");
        }

        // Create manual actions ticket
        JsonNode manualActions = parsed.get("manual_actions");
        if (manualActions != null && !manualActions.isNull()) {
            String title = textAt(manualActions, "/title");
            String description = textAt(manualActions, "/description");
            JsonNode steps = manualActions.get("steps");
            String verification = textAt(manualActions, "/verification");
            String reason = textAt(manualActions, "/reason");

            StringBuilder fullDescription = new StringBuilder();
            if (description != null && !description.isBlank()) {
                fullDescription.append(description).append("\n\n");
            }

            if (reason != null && !reason.isBlank()) {
                fullDescription.append("## Why Manual Intervention is Required\n\n").append(reason).append("\n\n");
            }

            if (steps != null && steps.isArray() && steps.size() > 0) {
                fullDescription.append("## Steps to Complete\n\n");
                int stepNum = 1;
                for (JsonNode step : steps) {
                    fullDescription.append(stepNum++).append(". ").append(step.asText()).append("\n");
                }
                fullDescription.append("\n");
            }

            if (verification != null && !verification.isBlank()) {
                fullDescription.append("## Verification\n\n").append(verification).append("\n");
            }

            String summary = title != null && !title.isBlank() ? title : "Manual Actions for " + issueKey;

            System.out.println("[INFO] Creating manual actions ticket");
            String manualKey = jiraClient.createIssue(projectKey, taskIssueType, summary, fullDescription.toString());
            System.out.println("[SUCCESS] Created manual actions ticket: " + manualKey);

            // Add Attention label
            System.out.println("[INFO] Adding Attention label to " + manualKey);
            jiraClient.addLabels(manualKey, "Attention");

            // Link as blocker
            System.out.println("[INFO] Linking " + manualKey + " as blocker to " + issueKey);
            jiraClient.linkIssues(manualKey, issueKey, linkType);

            createdCount++;
        } else {
            System.out.println("[INFO] No manual actions needed");
        }

        System.out.println("[SUCCESS] Created " + createdCount + " troubleshooting tickets");
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
