# CLAUDE.md

## What This Is

metadev is a development meta-environment. It provides shared Claude Code agents and MCP tools that are imported by other projects via devenv module composition.

## Your Role Here

When running Claude in metadev, you are an **orchestrator across projects**. You can:

1. **Brainstorm** — use the brainstorm agent for idea generation across any domain
2. **Bootstrap projects** — create new project directories with devenv configurations
3. **Cross-project work** — spawn Claude Code tasks in other project devenvs using `devenv -d <path> shell -- claude "<task>"`
4. **Build tools** — use the toolsmith agent to create MCP servers in `tools/`
5. **Maintain metadev** — add/improve shared agents, update documentation

## Project Locations

Projects live in `~/projects/`. Projects with a `devenv.yaml` that imports metadev get shared agents automatically.

Known projects:
- `modular-digital-music-array` — Distributed DJ system (Rust, Raspberry Pi)
- `stainless-facts` — Facts/knowledge management
- `ctx` — Context management
- `corsett` — (needs devenv setup)

## Cross-Project Commands

```bash
# Run a task in another project's devenv
devenv -d ~/projects/stainless-facts shell -- claude "run tests"

# Interactive session
devenv -d ~/projects/mdma shell -- claude
```

## File Structure

```
metadev/
├── devenv.nix         # Shared agents + packages (imported by other projects)
├── devenv.yaml        # Inputs (nixpkgs, claude-code-nix)
├── tools/             # MCP tool servers (Babashka)
├── CLAUDE.md          # This file
└── README.md          # Usage documentation
```

## Conventions

- Agent definitions live in `devenv.nix` — they are the module other projects import
- MCP tools are Babashka scripts in `tools/<name>/server.bb`
- Generic agents go here; project-specific agents stay in their project
- Polymorphic agents load project-specific context via Skills
