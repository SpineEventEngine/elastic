# Team memory

Durable, repository-wide knowledge for agents and humans — build/workflow gotchas,
feedback rules, project rationale, and pointers to external systems. Checked into git
so the whole team (and every agent session) shares it.

## Layout

- `MEMORY.md` — the index, reviewed at the start of each session. One bullet per
  memory: `- [Title](file.md) — one-line hook`.
- One `*.md` file per fact, with frontmatter:

  ```
  ---
  name: <kebab-case-slug>
  description: <one-line summary used to judge relevance during recall>
  metadata:
    type: feedback | project | reference
  ---
  <the fact. For `feedback`/`project`, follow with **Why:** and **How to apply:** lines.>
  ```

## Write protocol

- Before adding, check for an existing file that already covers the topic and update it
  rather than duplicating. Delete memories that turn out to be wrong.
- Do not record what the repo already states plainly (code structure, git history,
  config files); capture only what was non-obvious.
- Keep per-developer / per-machine facts (local paths, personal preferences) out of
  here — those belong in the agent's local auto-memory, not the shared repo.
- After adding or editing a file, update the `MEMORY.md` index.
