# ai-assistant-2

This repo contains BA/architecture instruction prompts and a small automation that can:

1) Receive a Jira webhook when an issue changes.
2) Trigger a GitHub Actions workflow.
3) Fetch the triggering Jira ticket details.
4) Call the GitHub Models API to run the BA assistant prompt (see `instructions/platform/ba-role.md`).
5) Produce a strict JSON plan as an artifact (`ba-output.json`).

## Automation Overview

There are two components:
	- Fetches the Jira issue JSON via Jira REST API
	- Builds a prompt from ticket content
	- Calls GitHub Models chat completions endpoint
	- Writes `ba-output.json`

## GitHub Actions Setup

Workflow: `.github/workflows/jira-ba-assistant.yml`

Add these **GitHub repository secrets**:

- `JIRA_BASE_URL` (example: `https://your-domain.atlassian.net`)
- `JIRA_EMAIL` (the Atlassian account email)
- `JIRA_API_TOKEN` (Atlassian API token)
- `MODELS_TOKEN` (token/key for the GitHub Models endpoint)

Optional **GitHub repository variables**:

- `MODELS_ENDPOINT` (default: `https://models.inference.ai.azure.com`)
- `MODELS_MODEL` (default: `gpt-4o-mini`)

## Notes

- The BA assistant output format is defined in `instructions/platform/ba-role.md`.
- The workflow currently uploads `ba-output.json` as an artifact; you can extend it to post results back to Jira or open a PR.
- The Jira automation directly triggers the GitHub Actions workflow using workflow dispatch API.
- The `ticket_summary` and `ticket_description` inputs are optional; if not provided, the runner will fetch from Jira API.