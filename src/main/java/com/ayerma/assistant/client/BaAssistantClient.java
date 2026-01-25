package com.ayerma.assistant.client;

import java.io.IOException;

/**
 * Interface for BA Assistant clients that can generate implementation plans
 * from system and user prompts.
 */
public interface BaAssistantClient {
    /**
     * Generate a BA implementation plan based on the provided prompts.
     *
     * @param systemPrompt The system prompt containing BA role instructions and
     *                     technical requirements
     * @param userPrompt   The user prompt containing the Jira ticket details
     * @return The generated JSON implementation plan as a string
     * @throws IOException          If communication with the client fails
     * @throws InterruptedException If the operation is interrupted
     */
    String runBaAssistant(String systemPrompt, String userPrompt) throws IOException, InterruptedException;
}
