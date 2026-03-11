# git-write MCP Server

Provides safe, scoped git write operations for Claude Code agents. This server deliberately exposes only a minimal set of write operations and enforces safety constraints at the tool boundary.

## Tools

### `git_add`
Stages specific files for commit.

Parameters:
- `path` (string) — absolute path to the git repository root
- `files` (string) — space-separated list of file paths to stage

Safety: rejects `-A`, `.`, and `*` — agents must name files explicitly.

### `git_commit`
Creates a commit with the currently staged changes.

Parameters:
- `path` (string) — absolute path to the git repository root
- `message` (string) — commit message (must not be empty)

### `git_stash`
Pushes or pops the git stash.

Parameters:
- `path` (string) — absolute path to the git repository root
- `action` (string) — `"push"` to stash current changes, `"pop"` to restore the most recent stash

## What Is NOT Exposed

- `git push` — no remote write operations
- `git reset --hard` — no destructive history rewrites
- Force operations (`--force`, `-f`)
- Branch deletion
- Rebase or merge

## Usage

Run directly to test:
```bash
bb tools/git-write/server.bb
```

## devenv.nix Registration

```nix
claude.code.mcpServers.git-write = {
  type = "stdio";
  command = "bb";
  args = [ "/home/johlrogge/projects/metadev/tools/git-write/server.bb" ];
};
```

For projects that import metadev, use a path relative to the project if the tools directory is available, or use the absolute path above.
