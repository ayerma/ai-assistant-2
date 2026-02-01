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

### Output Schema

The JSON output includes:

- `ticket_type`: story or task
- `sub_tickets`: Array of question tickets when critical decisions are needed (created as subtasks)
- Each question ticket includes context, options, and reasoning for why human input is required

See `instructions/platform/roles/ba-role.md` for detailed examples and guidelines.
