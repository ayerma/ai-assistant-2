package com.ayerma.assistant.client.cli;

import com.ayerma.assistant.client.BaAssistantClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Client that uses GitHub Copilot CLI to generate BA implementation plans.
 */
public final class GitHubCopilotCliClient implements BaAssistantClient {
    private final String cliCommand;

    public GitHubCopilotCliClient(String cliCommand) {
        this.cliCommand = cliCommand;
    }

    @Override
    public String runBaAssistant(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        // Combine system and user prompts into a single prompt for CLI
        String combinedPrompt = systemPrompt + "\n\n" + userPrompt;

        List<String> command = new ArrayList<>();
        command.add(cliCommand);
        command.add("--allow-all-tools");
        command.add("-p");
        command.add(combinedPrompt);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        // Pass authentication token to CLI process
        // Copilot CLI accepts COPILOT_GITHUB_TOKEN, GH_TOKEN, or GITHUB_TOKEN
        String authToken = System.getenv("COPILOT_GITHUB_TOKEN");
        if (authToken != null && !authToken.isEmpty()) {
            pb.environment().put("COPILOT_GITHUB_TOKEN", authToken);
            System.out.println("[INFO] Authentication token provided via COPILOT_GITHUB_TOKEN");
        } else {
            System.out.println("[WARN] No COPILOT_GITHUB_TOKEN found in environment");
        }

        System.out.println("[INFO] Executing CLI command: " + cliCommand + " --allow-all-tools -p \"<prompt>\"");

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException(
                    "Failed to execute CLI command '" + cliCommand + "'. " +
                            "Make sure GitHub Copilot CLI is installed and accessible in PATH. " +
                            "Alternatively, set USE_MODELS_API=true to use the API instead. " +
                            "Original error: " + e.getMessage(),
                    e);
        }

        // Read the output
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        // Wait for process to complete with timeout
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("CLI command timed out after 5 minutes");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("CLI command failed with exit code " + exitCode + ": " + output);
        }

        String result = output.toString().trim();
        if (result.isEmpty()) {
            throw new IOException("CLI command returned empty output");
        }

        return result;
    }
}
