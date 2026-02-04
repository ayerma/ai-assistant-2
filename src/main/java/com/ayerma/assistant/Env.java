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

    // Content-Spitter Assistant environment variables
    public static String contentSpitterInstructionsPath() {
        return optional("CONTENT_SPITTER_INSTRUCTIONS_PATH", "instructions/platform/roles/content-spitter-role.md");
    }

    public static String contentSpitterOutputPath() {
        return optional("CONTENT_SPITTER_OUTPUT_PATH", "content-spitter-output.json");
    }

    public static String contentSpitterPromptOutputPath() {
        return optional("CONTENT_SPITTER_PROMPT_OUTPUT_PATH", "content-spitter-prompt.txt");
    }
}
