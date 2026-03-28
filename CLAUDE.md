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
- `modular-digital-music-array` (aka `mdma`) — Distributed DJ system (Rust, Raspberry Pi)
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

## Working in Projects

**Always enter a project's devenv before running commands in it.** This ensures the correct tools, agents, and environment are available.

```bash
# Run a command in a project's devenv
devenv -d ~/projects/<project> shell -- <command>

# Run Claude in a project's devenv
devenv -d ~/projects/<project> shell -- claude "<task>"
```

If a project has no `devenv.nix`, create one that imports metadev:

```nix
{ inputs, ... }:
{
  imports = [ inputs.metadev.devenvModules.default ];

  # Add project-specific configuration here
}
```

And a `devenv.yaml` with metadev as an input:

```yaml
inputs:
  metadev:
    url: path:///home/johlrogge/projects/metadev
```

When `devenv.nix` doesn't exist and a command or tool is missing, create an ad-hoc environment:

```bash
devenv -d ~/projects/<project> -O languages.rust.enable:bool true -O packages:pkgs "mypackage" shell -- <command>
```

When the setup becomes complex, create `devenv.nix` and `devenv.yaml` as above instead.

## Git Flow

All code changes in metadev **must** follow git flow. Never commit directly to `develop` or `main`.

- `main` — released code only, always tagged
- `develop` — integration branch
- `feature/*` — branch from develop for every change, no matter how small
- `release/*` — release prep, branched from develop by release-manager
- `hotfix/*` — urgent fixes only, branched from main

**Workflow (mandatory):**
1. Ask the **release-manager** to start a feature branch before touching any code
2. Implement on the feature branch via **architect** → **code-minion** → **commit**
3. Ask the **release-manager** to finish the feature branch when done

See `RELEASING.md` for the full release checklist and agent sequence.

## GitHub Issues

Use the `gh-issues` MCP server to report bugs or tasks that must persist across sessions. When you discover a problem that cannot be fixed immediately, file an issue so it survives context resets:

```
gh_issue_create(title: "...", body: "...", label: "bug", repo: "johlrogge/metadev")
```

Other projects' Claude agents can also file issues here. Check open issues at the start of sessions:

```
gh_issue_list(repo: "johlrogge/metadev")
```

## Conventions

- Agent definitions live in `devenv.nix` — they are the module other projects import
- MCP tools are Babashka scripts in `tools/<name>/server.bb`
- Generic agents go here; project-specific agents stay in their project
- Polymorphic agents load project-specific context via Skills
