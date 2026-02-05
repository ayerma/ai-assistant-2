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
- `.github/workflows/jira-content-splitter.yml`

Add these **GitHub repository secrets**:

- `JIRA_BASE_URL` (example: `https://your-domain.atlassian.net`)
- `JIRA_EMAIL` (the Atlassian account email)
- `JIRA_API_TOKEN` (Atlassian API token)
- `MODELS_TOKEN` (token/key for the GitHub Models endpoint)

Optional **GitHub repository variables**:

- `MODELS_ENDPOINT` (default: `https://models.inference.ai.azure.com`)
- `MODELS_MODEL` (default: `gpt-4o-mini`)
- `DEV_INSTRUCTIONS_PATH` (default: `instructions/platform/roles/dev-role.md`)
- `TARGET_REPO` (format: `owner/repo`, required for Tech Assistant and Content-Creator)
- `TARGET_REF` (default: `main`)
- `TARGET_REPO_PATH` (default: `target-repo`)
- `AUTOMERGE` (default: `false`) - Auto-merge PRs after creation (Tech Assistant & Content-Creator)
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

## Content-Splitter Assistant

The Content-Splitter assistant analyzes content-focused Jira tickets and automatically breaks them down into logical subtopics, creating child tasks for each subtopic.

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
# Navigate to Actions > Jira -> Content-Splitter
# Click "Run workflow"
# Enter ticket_id (e.g., PROJ-123)
# Optionally provide ticket_summary and ticket_description
```

**Environment Variables:**

- `CONTENT_SPLITTER_INSTRUCTIONS_PATH` (default: `instructions/platform/roles/content-splitter-role.md`)
- `CONTENT_SPLITTER_OUTPUT_PATH` (default: `content-splitter-output.json`)
- `CONTENT_SPLITTER_PROMPT_OUTPUT_PATH` (default: `content-splitter-prompt.txt`)
- `JIRA_TASK_ISSUE_TYPE` (default: `Task`) - used for created subtopic tasks

**Output Files:**

- `content-splitter-prompt.txt` - Generated prompt combining role instructions and ticket content
- `content-splitter-output.json` - AI-generated JSON with subtopics breakdown

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

`com.ayerma.assistant.ContentSplitterRunner` - Can be run standalone or via workflow

### Dual-Mode Support

Like other assistants, Content-Splitter supports both:

- **GitHub Models API mode** (default): Direct API calls to AI model
- **GitHub Copilot CLI mode**: Uses local Copilot CLI (set `USE_MODELS_API=false`)

## Content-Creator Assistant

The Content-Creator assistant generates comprehensive Java interview questions with detailed answers based on a given topic from a Jira ticket.

### Two-Stage Workflow

The Content-Creator now operates as a **two-stage pipeline**:

#### Stage 1: Question Generation (jira-content-creator.yml)

- Takes a Jira ticket with a Java topic as the summary/title
- Generates exactly **15 interview questions** ordered by gradually increasing difficulty
- Includes: most popular questions + underrated questions + questions suitable for experienced professionals
- Always uses GitHub Models API for consistent, high-quality question generation
- Outputs: `content-creator-questions.json`
- Automatically triggers Stage 2

#### Stage 2: Question Answering (jira-answer.yml)

- Can be triggered automatically by Stage 1 OR manually/externally with a `questions_json` input
- Answers each question individually using either GitHub Models API or Copilot CLI (based on `USE_MODELS_API`)
- Uses Java specialist guidance from `instructions/platform/technical/java-specialist.md`
- Provides comprehensive, senior-level answers (3-15 sentences with inline code examples)
- **Creates a Pull Request** with the generated content in the target repository
- Auto-merges PR if `AUTOMERGE=true` (requires appropriate permissions)
- Enriches the Jira ticket with PR link and formatted Q&A content
- Labels the ticket with `interview-content` and `ai-generated`
- Outputs: `content-creator-output.json`

### Purpose

- Generate high-quality Java interview content for educational/training purposes
- Create structured Q&A pairs with professional-level depth
- Automatically organize content into a target repository
- Track content generation via Jira tickets

### How It Works

**End-to-End Flow:**

1. Trigger Stage 1 with a Jira ticket (e.g., "Java Streams API")
2. Stage 1 generates 15 questions using GitHub Models API
3. Stage 1 automatically triggers Stage 2, passing the questions JSON
4. Stage 2 answers each question using the configured AI client (Models/CLI)
5. Stage 2 creates a new branch and commits markdown files in the target repository
6. Stage 2 creates a Pull Request with the content
7. If `AUTOMERGE=true`, the PR is automatically merged
8. Stage 2 enriches the Jira ticket with PR link and formatted Q&A content

**External Triggering:**

You can also trigger Stage 2 (jira-answer.yml) independently by providing your own `questions_json`:

```json
{
  "topic": "Custom Topic",
  "questions": ["Question 1", "Question 2", ...]
}
```

### Usage

**Manual Trigger (GitHub Actions):**

```bash
# Stage 1: Generate Questions
# Navigate to Actions > Jira -> Content-Creator (Questions)
# Click "Run workflow"
# Enter ticket_id (e.g., PROJ-123)
# Optionally provide ticket_summary (topic name)
# This automatically triggers Stage 2

# Stage 2: Answer Questions (Manual/External Trigger)
# Navigate to Actions > Jira -> Answer Questions
# Click "Run workflow"
# Enter ticket_id and questions_json (see format above)
```

**Command-Line Execution:**

```bash
# Stage 1: Generate questions
CONTENT_CREATOR_MODE=questions \
MODELS_TOKEN=<token> \
JIRA_ISSUE_KEY=PROJ-123 \
java -cp target/ai-assistant-2-automation-0.1.0-all.jar com.ayerma.assistant.ContentCreatorRunner

# Stage 2: Answer questions (using Models API)
CONTENT_CREATOR_MODE=answers \
USE_MODELS_API=true \
MODELS_TOKEN=<token> \
JIRA_ISSUE_KEY=PROJ-123 \
CONTENT_CREATOR_QUESTIONS_INPUT_PATH=content-creator-questions.json \
java -cp target/ai-assistant-2-automation-0.1.0-all.jar com.ayerma.assistant.ContentCreatorRunner

# Stage 2: Answer questions (using Copilot CLI)
CONTENT_CREATOR_MODE=answers \
USE_MODELS_API=false \
COPILOT_GITHUB_TOKEN=<token> \
JIRA_ISSUE_KEY=PROJ-123 \
CONTENT_CREATOR_QUESTIONS_INPUT_PATH=content-creator-questions.json \
java -cp target/ai-assistant-2-automation-0.1.0-all.jar com.ayerma.assistant.ContentCreatorRunner
```

**Environment Variables:**

**Stage 1 (Questions):**

- `CONTENT_CREATOR_MODE` (set to `questions`)
- `CONTENT_CREATOR_QUESTIONS_INSTRUCTIONS_PATH` (default: `instructions/platform/roles/content-creator-questions-role.md`)
- `CONTENT_CREATOR_QUESTIONS_OUTPUT_PATH` (default: `content-creator-questions.json`)
- `MODELS_TOKEN` (required) - GitHub Models API token
- `MODELS_MODEL` (default: `gpt-5`)
- `MODELS_ENDPOINT` (default: `https://models.inference.ai.azure.com`)

**Stage 2 (Answers):**

- `CONTENT_CREATOR_MODE` (set to `answers`)
- `CONTENT_CREATOR_ANSWER_INSTRUCTIONS_PATH` (default: `instructions/platform/roles/content-creator-role.md`)
- `TECHNICAL_REQUIREMENTS_PATH` (default: `instructions/platform/technical/java-specialist.md`)
- `CONTENT_INSTRUCTIONS_PATH` (default: `instructions/platform/technical/content-instructions.md`)
- `CONTENT_CREATOR_QUESTIONS_INPUT_PATH` (default: `content-creator-questions.json`)
- `CONTENT_CREATOR_OUTPUT_PATH` (default: `content-creator-output.json`)
- `TARGET_REPO_PATH` (default: `target-repo`) - Location of target repository
- `USE_MODELS_API` (default: `true`) - set to `false` to use GitHub Copilot CLI
- `MODELS_TOKEN` (required if `USE_MODELS_API=true`)
- `COPILOT_GITHUB_TOKEN` (required if `USE_MODELS_API=false`)

**Legacy Mode:**

- `CONTENT_CREATOR_MODE` (set to `legacy` or omit) - runs the original single-step Q&A generation

**Output Files:**

- `content-creator-questions.json` - Generated question list (Stage 1)
- `content-creator-output.json` - Final Q&A pairs (Stage 2)

### Example

**Input Ticket:**

```
Jira Issue: PROJ-123
Title/Summary: Java Streams API
```

**Stage 1 Output (Questions JSON):**

```json
{
  "topic": "Java Streams API",
  "questions": [
    "What is the Java Stream API and when was it introduced?",
    "What is the difference between intermediate and terminal operations in Streams?",
    "...",
    "How do you debug complex Stream pipelines and what tools are available?"
  ]
}
```

**Stage 2 Output (Final Q&A JSON):**

```json
{
  "topic": "Java Streams API",
  "questions": [
    {
      "question": "What is the Java Stream API and when was it introduced?",
      "answer": "The Java Stream API, introduced in Java 8, provides a functional programming approach to process sequences of elements..."
    },
    {
      "question": "What is the difference between intermediate and terminal operations in Streams?",
      "answer": "Intermediate operations (filter, map, flatMap, distinct, sorted, peek, limit, skip) return a new Stream..."
    }
  ]
}
```

**Jira Ticket Enrichment:**

The ticket receives a formatted comment with:

- Topic header
- Numbered Q&A pairs (15 questions total)
- Total question count
- Labels: `interview-content`, `ai-generated`

### Key Features

- **15 questions per topic**: Balanced coverage from fundamental to advanced
- **Dual-mode support**: Stage 2 can use GitHub Models API or Copilot CLI
- **External triggering**: Stage 2 can be triggered independently with custom questions
- **Pull Request workflow**: Always creates PR for review; optional auto-merge with `AUTOMERGE=true`
- **ADDING_CONTENT.md validation**: Ensures proper content formatting guidelines are available
- **Java specialist guidance**: Answers follow senior developer best practices
- **Structured difficulty progression**: Questions ordered from easiest to most complex
- **Mix of question types**: Popular + underrated + professional-level questions

### Instruction Files

- `instructions/platform/roles/content-creator-questions-role.md` - Question generation instructions (Stage 1)
- `instructions/platform/roles/content-creator-role.md` - Answer format instructions (Stage 2)
- `instructions/platform/technical/java-specialist.md` - Senior Java developer persona and answer quality guidance (Stage 2)

### Runner Class

`com.ayerma.assistant.ContentCreatorRunner` - Supports three modes: `questions`, `answers`, `legacy`
