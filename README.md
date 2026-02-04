# ai-assistant-2

This repo contains BA/architecture instruction prompts and a small automation that can:

1. Receive a Jira webhook when an issue changes.
2. Trigger a GitHub Actions workflow.
3. Fetch the triggering Jira ticket details.
4. Call the GitHub Models API to run the BA assistant prompt (see `instructions/platform/ba-role.md`).
5. Produce a strict JSON plan as an artifact (`ba-output.json`).

## Automation Overview

There are two components: - Fetches the Jira issue JSON via Jira REST API - Builds a prompt from ticket content - Calls GitHub Models chat completions endpoint - Writes `ba-output.json`

## GitHub Actions Setup

Workflows:

- `.github/workflows/jira-ba-assistant.yml`
- `.github/workflows/jira-tech-assistant.yml`
- `.github/workflows/jira-content-spitter.yml`

Add these **GitHub repository secrets**:

- `JIRA_BASE_URL` (example: `https://your-domain.atlassian.net`)
- `JIRA_EMAIL` (the Atlassian account email)
- `JIRA_API_TOKEN` (Atlassian API token)
- `MODELS_TOKEN` (token/key for the GitHub Models endpoint)

Optional **GitHub repository variables**:

- `MODELS_ENDPOINT` (default: `https://models.inference.ai.azure.com`)
- `MODELS_MODEL` (default: `gpt-4o-mini`)
- `DEV_INSTRUCTIONS_PATH` (default: `instructions/platform/roles/dev-role.md`)
- `TARGET_REPO` (format: `owner/repo`, required for Tech Assistant)
- `TARGET_REF` (default: `main`)
- `TARGET_REPO_PATH` (default: `target-repo`)
- `JIRA_STORY_ISSUE_TYPE` (default: `Story`)
- `JIRA_TASK_ISSUE_TYPE` (default: `Task`)
- `JIRA_QUESTION_ISSUE_TYPE` (default: `Sub-task`)
- `JIRA_LINK_TYPE` (default: `Relates`)

Optional **GitHub repository secrets** (for private target repos):

- `TARGET_REPO_TOKEN` (GitHub token with read access)

## Tech Assistant Workflow

The Tech Assistant workflow generates a code-focused implementation plan using BA + senior developer instructions and the technical requirements.

- Output prompt: `tech-prompt.txt`
- Output JSON: `tech-output.json`
- Optional env overrides: `TECH_PROMPT_OUTPUT_PATH`, `TECH_OUTPUT_PATH`

## Notes

- The BA assistant output format is defined in `instructions/platform/roles/ba-role.md`.
- Senior developer behavior is defined in `instructions/platform/roles/dev-role.md`.
- The workflows upload `ba-output.json` or `tech-output.json` as artifacts; you can extend them to post results back to Jira or open a PR.
- The Jira automation directly triggers the GitHub Actions workflow using workflow dispatch API.
- The `ticket_summary` and `ticket_description` inputs are optional; if not provided, the runner will fetch from Jira API.

## BA Assistant Features

The BA assistant now includes enhanced decision-making capabilities:

### Autonomous Decision Making

- The BA assistant makes most technical decisions automatically based on best practices, project requirements, and industry standards
- Decisions are made for component structure, UI organization, styling, naming conventions, and minor technical choices

### Question Tickets

- For **business-critical** or **technically-critical** decisions, the BA assistant creates sub-tickets with questions
- Question tickets have the `[Question]` prefix in the title and are of type `task`
- These allow stakeholders to make important decisions while the BA assistant handles routine choices

### Ticket Type Classification

The BA assistant outputs only stories and tasks (the triggering ticket is treated as the epic):

- **Story**: Medium features (3-8 story points) delivering user value
- **Task**: Small, focused work (1-3 story points)
- **Question**: Sub-tasks for critical decisions requiring human input

### Output Schema

The JSON output includes:

- `ticket_type`: story or task (for main tickets), question (for sub-tickets)
- `story_points`: 1-3 for tasks, 5-8 for stories
- `sub_tickets`: Array of question tickets when critical decisions are needed (created as Jira subtasks)
- Each question ticket includes context, options, and reasoning for why human input is required

See `instructions/platform/roles/ba-role.md` for detailed examples and guidelines.

## Content-Spitter Assistant

The Content-Spitter assistant analyzes content-focused Jira tickets and automatically breaks them down into logical subtopics, creating child tasks for each subtopic.

### Purpose

- Analyzes content requirements from a Jira ticket
- Identifies logical subtopics or content areas
- Creates separate Jira tasks for each subtopic
- Each created task is linked to the parent ticket and labeled with **"Content-breaker"**

### How It Works

1. Takes a Jira ticket describing content needs (e.g., documentation, marketing content, guides)
2. AI analyzes the content area and breaks it into focused subtopics
3. For each subtopic, a child Task is created in Jira with:
   - Clear title describing the subtopic
   - Detailed description of what should be covered
   - Linked to the original parent ticket
   - Tagged with `Content-breaker` label (only label, no `DEV-AI`)

### Usage

**Manual Trigger (GitHub Actions):**

```bash
# Navigate to Actions > Jira -> Content-Spitter
# Click "Run workflow"
# Enter ticket_id (e.g., PROJ-123)
# Optionally provide ticket_summary and ticket_description
```

**Environment Variables:**

- `CONTENT_SPITTER_INSTRUCTIONS_PATH` (default: `instructions/platform/roles/content-spitter-role.md`)
- `CONTENT_SPITTER_OUTPUT_PATH` (default: `content-spitter-output.json`)
- `CONTENT_SPITTER_PROMPT_OUTPUT_PATH` (default: `content-spitter-prompt.txt`)
- `JIRA_TASK_ISSUE_TYPE` (default: `Task`) - used for created subtopic tasks

**Output Files:**

- `content-spitter-prompt.txt` - Generated prompt combining role instructions and ticket content
- `content-spitter-output.json` - AI-generated JSON with subtopics breakdown

### Example

**Input Ticket:**
```
Title: Create developer documentation for REST API
Description: Need comprehensive docs covering authentication, endpoints, error handling, and examples
```

**Output (Jira Tasks Created):**
- `PROJ-124`: Getting Started and Authentication [Content-breaker]
- `PROJ-125`: Endpoint Reference - User Management [Content-breaker]
- `PROJ-126`: Endpoint Reference - Data Operations [Content-breaker]
- `PROJ-127`: Error Handling and Status Codes [Content-breaker]
- `PROJ-128`: Code Examples and SDKs [Content-breaker]

All tasks are linked to `PROJ-123` as child tasks.

### Runner Class

`com.ayerma.assistant.ContentSpitterRunner` - Can be run standalone or via workflow

### Dual-Mode Support

Like other assistants, Content-Spitter supports both:
- **GitHub Models API mode** (default): Direct API calls to AI model
- **GitHub Copilot CLI mode**: Uses local Copilot CLI (set `USE_MODELS_API=false`)

