# ADR-001: Skills are declared via devenv claude.code.skills, never copied
## Status
Accepted
## Decision
- metadev declares its shared skills (`conventional-commits`, `helix`, `rust-architect`) in
  `modules/skills.nix` through `claude.code.skills`. They are on by default in every importing
  project (`lib.mkDefault`), and each can be turned off with `metadev.skills.<name>.enable = false`.
  Sources live in `skills/<name>/`; `SKILL.md` holds only the body, devenv generates the frontmatter.
- Projects keep their own skill Markdown in `claude/skills/<name>/` and declare it in their
  `devenv.nix`, optionally with the `metadevLib.skillFromDir` helper.
- `.claude/skills/` is generated and gitignored everywhere. No agent writes there.
- `/migrate` detects hand-copied skills and migrates them after confirmation.
## Why
The metadev agent used to copy skill files from the nix store into each project's
`.claude/skills/`. The copies were committed, drifted from metadev, and broke the rule that
devenv is the single source of truth. devenv now generates `.claude/skills/<name>/` as store
symlinks from `claude.code.skills.<name>`, the same way MCP servers and agents are declared.
## Alternatives considered
- **Keep copying, add a version check** — still drifts, and the copies stay committed.
- **`copyMode = "seed"`** — written once, so updates from metadev never arrive.
- **Shared skills off by default (opt-in)** — the shared agents depend on them.
## Consequences
- Skill updates reach projects on the next `devenv shell`.
- Hand edits under `.claude/skills/` are no longer possible; change the source instead.
- Consumers need a devenv input new enough to have `claude.code.skills` (`devenv update`).
- Leftover hand copies block the generated symlinks (devenv prints "Conflicting file") until removed.
