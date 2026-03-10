# metadev

Shared devenv module providing reusable Claude Code agents and MCP tools across projects.

## Usage

In your project's `devenv.yaml`:

```yaml
inputs:
  metadev:
    url: github:johlrogge/metadev
    flake: false

imports:
  - metadev
```

## Agents

| Agent | Model | Purpose |
|-------|-------|---------|
| brainstorm | opus | Draws ideas out of you through questions and reflection — builds on your ideas as suggestions, never creates artifacts without asking |
| toolsmith | sonnet | Creates MCP tool servers (Babashka/Clojure) for structured agent access |

## Extending Agents

Override or extend agent prompts in your project's `devenv.nix`:

```nix
{ lib, ... }:
{
  claude.code.agents.brainstorm.prompt = lib.mkForce ''
    Custom prompt here.
  '';
}
```

## MCP Tools

MCP tool servers live in `tools/` and are written in Babashka (bb).
Register them via `claude.code.mcpServers` in consuming projects:

```nix
claude.code.mcpServers.my-tool = {
  type = "stdio";
  command = "bb";
  args = [ "./tools/my-tool/server.bb" ];
};
```
