# metadev

Your development meta-environment. Shared agents, MCP tools, and a home base for orchestrating work across projects.

## Quick Start

```bash
cd ~/projects/metadev
devenv shell
claude
```

On shell entry you'll see available commands and a list of your projects.

## What metadev Does

1. **Shared agents** — Generic Claude Code agents available to all projects that import metadev
2. **MCP tools** — Babashka-based tool servers that give agents structured access without broad Bash permissions
3. **Cross-project orchestration** — Run Claude Code tasks in other project devenvs from here
4. **Project bootstrapping** — Create new projects with devenv configurations

## Agents

| Agent | Model | Purpose |
|-------|-------|---------|
| architect | opus | Software architect — reviews code, advises on design, loads language skills on demand. Read-only. |
| brainstorm | opus | Thinking partner — draws ideas out through questions, reflection, and structured techniques |
| code-minion | sonnet | Implementation specialist — writes code, implements planned features, writes tests |
| commit | haiku | Git commits with conventional commit format, hook-aware |
| devenv | sonnet | Environment guardian — audits projects for compliance with the devenv-as-single-source-of-truth principle. Read-only. |
| devops | sonnet | Deployment agent — builds, deploys, and operates project infrastructure via SSH MCP tools. No Bash. |
| documenter | sonnet | README maintenance, polymorphic via project skills |
| helix | opus | Helix keymap expert — advises on TUI keymap design using Helix/Kakoune conventions |
| metadev | sonnet | Metadev project guide — installs skills, checks workspace docs, detects outdated conventions |
| polylith | sonnet | Polylith architecture expert — design, scaffold, analyse, and migrate Rust/Cargo projects (cargo-polylith 0.10.0) |
| product-owner | opus | Product ownership and strategic guidance — scope, prioritise, and deliver continuous value |
| release-manager | sonnet | Git flow lifecycle — features, releases, hotfixes. Never pushes. |
| toolsmith | sonnet | Creates Babashka MCP tool servers for permission-free agent access |

All agents operate within a strict named-tool boundary. No agent has a Bash escape hatch.

The polylith agent uses the `cargo-polylith` MCP server (0.10.0), which exposes read tools (`polylith_info`, `polylith_deps`, `polylith_check`, `polylith_status`) and write tools including `polylith_migrate_package_meta` — for moving `[workspace.package]` metadata from `Polylith.toml` to the root `Cargo.toml [package]` section.

## Importing into a Project

Add to your project's `devenv.yaml`:

```yaml
inputs:
  metadev:
    url: github:johlrogge/metadev
    flake: false

imports:
  - metadev
```

This gives the project all shared agents. Project-specific agents stay in the project's own `devenv.nix`.

## Cross-Project Tasks

From the metadev shell, run Claude Code in another project's devenv:

```bash
# Run claude in another project's environment
devenv -d ~/projects/stainless-facts shell -- claude "run tests"

# Interactive session in another project
devenv -d ~/projects/mdma shell -- claude
```

## Bootstrapping a New Project

```bash
# 1. Create the project directory
mkdir ~/projects/my-new-project && cd ~/projects/my-new-project
git init

# 2. Create a minimal devenv.yaml that imports metadev
cat > devenv.yaml << 'EOF'
allowUnfree: true

inputs:
  nixpkgs:
    url: github:cachix/devenv-nixpkgs/rolling
  claude-code-nix:
    url: github:sadjow/claude-code-nix
    inputs:
      nixpkgs:
        follows: nixpkgs
  metadev:
    url: github:johlrogge/metadev
    flake: false

imports:
  - metadev
EOF

# 3. Create a project-specific devenv.nix
cat > devenv.nix << 'EOF'
{ pkgs, lib, config, inputs, ... }:
{
  # Add project-specific packages here
  # packages = with pkgs; [ ];

  # Add project-specific agents here
  # claude.code.agents = { };
}
EOF

# 4. Enter the shell — agents are ready
devenv shell
claude
```

## Adding metadev to an Existing Project

If a project already has a `devenv.yaml`, add the metadev input and import:

```yaml
# Add under inputs:
  metadev:
    url: github:johlrogge/metadev
    flake: false

# Add imports section (or append to existing):
imports:
  - metadev
```

If a project has no devenv at all, use the bootstrapping steps above.

## Extending Shared Agents

Override or extend agent prompts in your project's `devenv.nix`:

```nix
{ lib, config, ... }:
{
  # Extend a shared agent's prompt with project context
  claude.code.agents.documenter.prompt = ''
    ${config.claude.code.agents.documenter.prompt}

    ## Project-Specific Structure
    This project uses a Polylith layout with bases/ and components/.
  '';

  # Or override completely
  claude.code.agents.brainstorm.prompt = lib.mkForce ''
    Custom prompt here.
  '';
}
```

## MCP Tools

MCP tool servers live in `tools/` and are written in Babashka (bb). Register them in your project's `devenv.nix`:

```nix
claude.code.mcpServers.my-tool = {
  type = "stdio";
  command = "${pkgs.babashka}/bin/bb";
  args = [ "./tools/my-tool/server.bb" ];
};
```

Using the absolute nix store path for `command` means MCP servers connect regardless of whether Claude is started inside or outside `devenv shell`.

## MCP Permissions

`.claude/settings.local.json` is generated automatically by the devenv module on `devenv shell`. Importing projects get the standard MCP permission set with no manual step.

To grant additional permissions beyond the metadev defaults, override the file in your project's `devenv.nix`:

```nix
files.".claude/settings.local.json".json = {
  permissions.allow = [
    # metadev defaults are not inherited here — list everything needed
    "mcp__my-tool__my_operation"
  ];
};
```

If the file is missing or stale, re-enter the shell (`devenv shell`) to regenerate it.

## Philosophy

**"Don't ask for permission, ask for a tool."**

Every Bash permission prompt is a sign that a proper MCP tool is missing. The toolsmith agent creates these tools so other agents can work autonomously.

As of 0.10.0, all agents operate within strict named-tool boundaries. No agent uses Bash as a fallback. The devops agent deploys over SSH via `mcp__ssh__ssh_run` and `mcp__ssh__scp_transfer`. The devenv agent audits environments read-only via `mcp__devenv__search_packages` and `mcp__devenv__search_options`.
