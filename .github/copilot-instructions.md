# GitHub Copilot Workspace Instructions

## Project Overview

This is a Java-based BA (Business Analyst) assistant automation that processes Jira tickets and generates structured implementation plans using AI (GitHub Models API or Copilot CLI).

## Code Style & Conventions

- **Java Version:** Java 17
- **Logging:** Use System.out with prefixes: `[INFO]`, `[WARN]`, `[SUCCESS]`, `[DEBUG]`
- **Classes:** Prefer `final` classes
- **Error Handling:** Always log errors with context, use `IllegalStateException` for config errors
- **Environment Variables:** Read through `Env` class, use UPPER_SNAKE_CASE naming

## Architecture Rules

- **Client Pattern:** All AI clients must implement `BaAssistantClient` interface
- **Package Structure:**
  - `client/` - Abstractions
  - `client/models/` - API-based clients
  - `client/cli/` - CLI-based clients
- **Configuration:** All env vars must be documented in README and workflow files

## Milestone Protocol

When the user mentions the words **"make a milestone"** or **"do a milestone"**, follow this workflow:

1. **Update Changelog:**
   - Edit `notes/changes-log.md`
   - Add new entry at the top with current date
   - Document what was accomplished
   - Keep it structured and concise

2. **Commit Changes:**
   - Use format: `Milestone: <brief description of what was achieved>`
   - Examples:
     - `Milestone: Add dual-mode client support`
     - `Milestone: Complete technical requirements integration`
   - Be descriptive but concise (1 line, ~50-70 chars)
   - Push to repository

## Documentation Requirements

- Update `README.md` when adding features or changing configuration
- Keep `notes/changes-log.md` synchronized with major changes
- Document all new environment variables in both README and workflow comments

## Git Workflow

- Stage all relevant files before committing
- Verify changes with `git status` before commit
- Push immediately after successful commit
- Use descriptive but concise commit messages
