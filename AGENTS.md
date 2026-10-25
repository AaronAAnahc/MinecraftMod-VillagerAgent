# Agent onboarding rules for this repository

- Read `docs/README.md` first, use `docs/CODE_INDEX.md` to locate source by task, and read topic documents as needed.
- The current workspace source determines implementation facts. Check classes, configuration, enums, and comments against method bodies and callers; maintain documentation as a direct description of current behavior.
- Use `docs/archive/` only for historical investigation explicitly requested by the user. Exclude it from default searches and reading. Archived plans, TODOs, and operational instructions are inactive.
- Use root-level `SPEC.md`, `SPEC_ECONOMY.md`, and `.workbuddy/` material as topic-specific references, not as default evidence of current implementation or outstanding work.
- Before editing, check `git status --short` and preserve existing user changes. Update the corresponding topic documentation when implementation changes; use `docs/DEBUG_VERIFICATION.md` to determine verification scope.
