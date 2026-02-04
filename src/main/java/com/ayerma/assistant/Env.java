package com.ayerma.assistant;

public final class Env {
    private Env() {
    }

    public static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + name);
        }
        return value;
    }

    public static String optional(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    // Content-Splitter Assistant environment variables
    public static String contentSplitterInstructionsPath() {
        return optional("CONTENT_SPLITTER_INSTRUCTIONS_PATH", "instructions/platform/roles/content-splitter-role.md");
    }

    public static String contentSplitterOutputPath() {
        return optional("CONTENT_SPLITTER_OUTPUT_PATH", "content-splitter-output.json");
    }

    public static String contentSplitterPromptOutputPath() {
        return optional("CONTENT_SPLITTER_PROMPT_OUTPUT_PATH", "content-splitter-prompt.txt");
    }

    // Content-Creator Assistant environment variables
    public static String contentCreatorInstructionsPath() {
        return optional("CONTENT_CREATOR_INSTRUCTIONS_PATH", "instructions/platform/roles/content-creator-role.md");
    }

    public static String contentCreatorOutputPath() {
        return optional("CONTENT_CREATOR_OUTPUT_PATH", "content-creator-output.json");
    }

    public static String contentCreatorPromptOutputPath() {
        return optional("CONTENT_CREATOR_PROMPT_OUTPUT_PATH", "content-creator-prompt.txt");
    }
}
