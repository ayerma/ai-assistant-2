# Changes Log

## February 4, 2026 - Add Content-Spitter Assistant

### Summary

Added a new Content-Spitter assistant that analyzes content-focused Jira tickets and automatically breaks them down into logical subtopic tasks, creating child tasks in Jira with only the "Content-breaker" label.

### Key Accomplishments

- ✅ Created `ContentSpitterRunner.java` following the established BA/Tech/Troubleshooter pattern
- ✅ Added `createIssueWithParentAndLabels()` method to `JiraClient.java` for custom label support
- ✅ Refactored `createIssueWithParent()` to use new method with "DEV-AI" label by default
- ✅ Created `content-spitter-role.md` with Content Analyst role definition and subtopic breakdown instructions
- ✅ Added Content-Spitter environment variables to `Env.java` with helper methods
- ✅ Created `.github/workflows/jira-content-spitter.yml` workflow supporting both API and CLI modes
- ✅ Updated README.md with comprehensive Content-Spitter documentation and usage examples
- ✅ All created subtopic tasks are labeled with "Content-breaker" only (no DEV-AI label)

### Configuration

- New environment variables:
  - `CONTENT_SPITTER_INSTRUCTIONS_PATH` (default: `instructions/platform/roles/content-spitter-role.md`)
  - `CONTENT_SPITTER_OUTPUT_PATH` (default: `content-spitter-output.json`)
  - `CONTENT_SPITTER_PROMPT_OUTPUT_PATH` (default: `content-spitter-prompt.txt`)
- Reuses existing: `JIRA_TASK_ISSUE_TYPE`, `USE_MODELS_API`, Jira secrets

### Output Schema

```json
{
  "content_area": "string",
  "summary": "string",
  "subtopics": [
    {
      "title": "string",
      "description": "string"
    }
  ]
}
```

---

## February 3, 2026 - Milestone: Enhanced Tech Flow with Automerge and Validation

### Summary

Added comprehensive logging for instruction file loading, improved JSON extraction robustness from Copilot CLI output, implemented automerge capability with Jira ticket auto-transition, and enhanced error handling with Jira notifications.

### Key Accomplishments

- ✅ Added detailed logging to verify instruction files are loaded (dev-role.md, technical-requirements.md)
- ✅ Fixed workflow artifacts to include prompt files (tech-prompt.txt, ba-prompt.txt)
- ✅ Improved JSON extraction with multiple patterns and graceful fallback for malformed output
- ✅ Added Jira warning comment when JSON extraction fails instead of failing the workflow
- ✅ Implemented AUTOMERGE repository variable for automatic PR merge and branch cleanup
- ✅ Auto-transition Jira tickets to Done status after successful merge
- ✅ Updated dev instructions to exclude temporary workflow artifacts from commits
- ✅ Workflow now handles extraction failures gracefully with user notification

### Configuration

- New repository variable: `AUTOMERGE` (true/false) - enables automatic PR merge
- New repository variable: `JIRA_DONE_TRANSITION_ID` (default: 31) - Jira transition ID for Done status
- Temp file exclusion instructions added to dev-role.md

---

## February 1, 2026 - Milestone: CLI Workflow Jira Creation Complete

### Summary

Fixed the Copilot CLI workflow path to properly create Jira tickets after parsing the JSON response. Both execution paths (Models API and CLI) now create child tickets linked to the initial triggering ticket.

### Key Accomplishments

- ✅ Added "Create Jira tickets from output" step in BA workflow after CLI execution
- ✅ Implemented `CREATE_JIRA_FROM_OUTPUT` mode in BaAssistantRunner
- ✅ Extracted Jira creation logic into reusable `createJiraTicketsFromOutput()` method
- ✅ Verified complete flow: prompt generation → CLI execution → JSON extraction → Jira creation
- ✅ Both CLI and Models API paths now create Stories/Tasks linked to parent with Question subtasks

---

## February 1, 2026 - Milestone: BA Issue Creation and Debug Logging

### Summary

Updated BA flow to treat the triggering ticket as an epic, generate stories/tasks, create linked Jira issues with question subtasks, and expanded debug logging for Jira operations.

### Key Accomplishments

- ✅ BA instructions updated to remove epics and clarify story/task output
- ✅ Linked Jira issue creation for stories/tasks under the parent ticket
- ✅ Question sub-tickets created as Jira subtasks under their parent task
- ✅ Enhanced debug logging for issue creation and linking

---

## February 1, 2026 - App Description

### Summary

Documented the application overview: a Java-based BA/tech assistant automation that ingests Jira tickets, builds instruction-driven prompts, and produces structured implementation plans while optionally creating linked Jira issues.

---

## January 31, 2026 - Milestone: Tech Assistant Workflow and Instructions

### Summary

Introduced the Tech Assistant workflow with repo cloning support, added senior developer instructions, and updated BA instructions to capture clearer specs and related issue references.

### Key Accomplishments

- ✅ New Tech Assistant runner and workflow
- ✅ Dev role instructions for code-focused execution and PR flow
- ✅ Target repo cloning support via workflow variables/secrets
- ✅ BA instructions enhanced for specs, I/O formats, and related issues
- ✅ Documentation updates for new workflows and variables

---

## January 25, 2026 - Milestone: Dual-Mode Client Architecture Complete

### Summary

Successfully implemented dual-mode client architecture with complete abstraction layer, allowing the BA Assistant to use either GitHub Models API or Copilot CLI. Added comprehensive logging, technical requirements integration, and workspace instructions for future development.

### Key Accomplishments

- ✅ Client abstraction layer (`BaAssistantClient` interface)
- ✅ GitHub Models API client (refactored into `client/models` package)
- ✅ GitHub Copilot CLI client (new `client/cli` package)
- ✅ `USE_MODELS_API` flag for runtime client selection
- ✅ Technical requirements loading and integration
- ✅ Comprehensive logging with DEBUG output
- ✅ Jira API optimization (skip when summary provided)
- ✅ GitHub Copilot workspace instructions with milestone protocol

---

## January 25, 2026 - Client Abstraction and Dual-Mode Support

### Overview

Implemented a client abstraction layer to support both GitHub Models API and GitHub Copilot CLI as execution backends. This provides flexibility to use either HTTP API calls or local CLI tooling.

### Major Changes

#### 1. Package Restructuring

Created new client package hierarchy:

- `com.ayerma.assistant.client` - Common interface
- `com.ayerma.assistant.client.models` - GitHub Models API implementation
- `com.ayerma.assistant.client.cli` - GitHub Copilot CLI implementation

**Files:**

- `client/BaAssistantClient.java` - Interface for all BA assistant clients
- `client/models/GitHubModelsClient.java` - Moved from root package
- `client/cli/GitHubCopilotCliClient.java` - New CLI implementation

#### 2. Dual Execution Modes

**`USE_MODELS_API` Environment Variable:**

- `true` (default) - Uses GitHub Models HTTP API
- `false` - Uses GitHub Copilot CLI executable

**Models API Mode:**

- Requires: `MODELS_TOKEN`, `MODELS_ENDPOINT`, `MODELS_MODEL`
- Makes HTTP requests to AI inference endpoint
- Current production mode

**CLI Mode:**

- Requires: GitHub Copilot CLI installed and accessible
- Uses: `COPILOT_CLI_COMMAND` (defaults to `copilot`)
- Executes: `copilot --allow-all-tools -p "<combined prompt>"`
- 5-minute timeout for CLI execution
- Useful for local testing or environments where CLI is preferred

#### 3. Technical Requirements Loading

**File:** `src/main/java/com/ayerma/assistant/BaAssistantRunner.java`

- Loads both BA role instructions and technical requirements
- Combines them into single system prompt with separator
- Checks file existence before loading
- New env var: `TECHNICAL_REQUIREMENTS_PATH`

**Folder Reorganization:**

- `instructions/platform/ba-role.md` → `instructions/platform/roles/ba-role.md`
- `instructions/platform/project-technical-requirements.md` → `instructions/platform/technical/technical-requirements.md`

#### 4. Comprehensive Logging

Added detailed logging throughout execution:

- `[INFO]` - Progress and configuration details
- `[WARN]` - Non-critical warnings
- `[SUCCESS]` - Completion messages
- `[DEBUG]` - Raw AI responses for debugging

**Logged Information:**

- Issue being processed
- Client mode (API vs CLI)
- Model configuration
- Data source (provided vs Jira API)
- File loading status
- System prompt length
- Complete AI response
- JSON validation status

#### 5. Jira API Call Optimization (Completed)

**Files:**

- `BaAssistantRunner.java` - Selection logic
- `BaPromptBuilder.java` - New `buildUserPrompt()` method

**Behavior:**

- If `JIRA_ISSUE_SUMMARY` is provided → Skip Jira API call
- If not provided → Fetch from Jira API (original behavior)
- Saves API calls and improves performance when summary/description are available

### Environment Variables

#### New Variables:

- `USE_MODELS_API` - Boolean flag (default: `true`)
- `COPILOT_CLI_COMMAND` - CLI executable path (default: `copilot`)
- `TECHNICAL_REQUIREMENTS_PATH` - Path to technical requirements file

#### Existing Variables (Still Required):

- `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN`
- `MODELS_TOKEN` (required when USE_MODELS_API=true)
- `MODELS_ENDPOINT`, `MODELS_MODEL` (optional)
- `JIRA_ISSUE_SUMMARY`, `JIRA_ISSUE_DESCRIPTION` (optional)

### GitHub Actions Workflow Updates

**File:** `.github/workflows/jira-ba-assistant.yml`

```yaml
# Client configuration
USE_MODELS_API: ${{ vars.USE_MODELS_API || 'true' }}

# GitHub Models (when USE_MODELS_API=true)
MODELS_TOKEN: ${{ secrets.MODELS_TOKEN }}
MODELS_ENDPOINT: ${{ vars.MODELS_ENDPOINT }}
MODELS_MODEL: ${{ vars.MODELS_MODEL }}

# GitHub Copilot CLI (when USE_MODELS_API=false)
COPILOT_CLI_COMMAND: ${{ vars.COPILOT_CLI_COMMAND || 'copilot' }}
```

### Benefits

1. **Flexibility** - Choose between API and CLI based on environment
2. **Better Debugging** - Comprehensive logging shows execution flow
3. **Richer Context** - Technical requirements now included in prompts
4. **Performance** - Skip Jira API when data is already available
5. **Testability** - CLI mode enables local testing without API setup

---

## January 24, 2026 - Environment Variables Refactoring

### Overview

Simplified and cleaned up environment variable names by removing the `GITHUB_` prefix from model-related variables.

### Changes Made

#### 1. GitHub Actions Workflow Updates

**File:** `.github/workflows/jira-ba-assistant.yml`

- Added two new input environment variables for optimization:
  - `JIRA_ISSUE_SUMMARY` - Optional ticket summary to skip API call
  - `JIRA_ISSUE_DESCRIPTION` - Optional ticket description to skip API call

- Renamed environment variables:
  - `GITHUB_MODELS_TOKEN` → `MODELS_TOKEN`
  - `GITHUB_MODELS_ENDPOINT` → `MODELS_ENDPOINT`
  - `GITHUB_MODELS_MODEL` → `MODELS_MODEL`

#### 2. Java Code Updates

**File:** `src/main/java/com/ayerma/assistant/BaAssistantRunner.java`

Updated environment variable lookups:

- `Env.required("GITHUB_MODELS_TOKEN")` → `Env.required("MODELS_TOKEN")`
- `Env.optional("GITHUB_MODELS_ENDPOINT", ...)` → `Env.optional("MODELS_ENDPOINT", ...)`
- `Env.optional("GITHUB_MODELS_MODEL", ...)` → `Env.optional("MODELS_MODEL", ...)`

#### 3. Documentation Updates

**File:** `README.md`

Updated the secrets and variables documentation to reflect new naming convention:

- Renamed `GITHUB_MODELS_TOKEN` → `MODELS_TOKEN` in secrets section
- Renamed `GITHUB_MODELS_ENDPOINT` → `MODELS_ENDPOINT` in variables section
- Renamed `GITHUB_MODELS_MODEL` → `MODELS_MODEL` in variables section

### GitHub Secrets/Variables to Update

When setting up GitHub Actions, configure these:

**Secrets:**

- `JIRA_BASE_URL`
- `JIRA_EMAIL`
- `JIRA_API_TOKEN`
- `MODELS_TOKEN` (formerly GITHUB_MODELS_TOKEN)

**Variables (optional):**

- `MODELS_ENDPOINT` (formerly GITHUB_MODELS_ENDPOINT)
- `MODELS_MODEL` (formerly GITHUB_MODELS_MODEL)

### Benefits

1. **Cleaner naming** - Removed redundant `GITHUB_` prefix
2. **Performance optimization** - Can now pass summary/description to skip Jira API calls
3. **More flexible** - Easier to switch between different model providers in the future

### Next Steps (Future Improvements)

The Java code currently always fetches from Jira API. To leverage the new `JIRA_ISSUE_SUMMARY` and `JIRA_ISSUE_DESCRIPTION` variables:

1. Check if both summary and description are provided in environment
2. If provided, construct prompt directly without Jira API call
3. If missing, fall back to existing Jira fetch behavior

This would require:

- New method in `BaPromptBuilder` to build prompt from raw strings
- Logic in `BaAssistantRunner` to choose between direct prompt or API fetch
