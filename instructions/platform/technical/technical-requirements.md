# Project Technical Requirements

## Tech Stack

- SolidJS (Vite)
- TailwindCSS
- Routing: @solidjs/router (Hash mode)
- Hosting: GitHub Pages

## Product Context

- App: "Java Interview Q&A" web application
- Layout: split-screen UI
  - Left sidebar: Topics/Questions
  - Center: Answer display
- Key feature: Each answer supports level toggles (Junior, Middle, Senior, Tricky). Selecting a toggle updates the displayed answer content to match depth/complexity.

## Implementation Preferences

- Prefer SolidJS idioms and best practices:
  - Signals/store patterns where appropriate
  - Solid control flow components like <Show> and <For>
  - Resource patterns such as createResource when loading JSON/data
  - Prefer Signals over other reactive patterns when applicable
