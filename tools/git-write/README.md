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

### Worktrees

All worktrees managed by these tools live under `<repo>/.worktrees/<name>`, where `<repo>` is the
**main** checkout (resolved via `git worktree list`, even when `path` points at a worktree). Bare
repositories are not supported.

#### `git_worktree_add`
Creates a worktree at `<repo>/.worktrees/<worktree>`. Never creates branches.

Parameters:
- `path` (string) — absolute path to the repository root or any of its worktrees
- `worktree` (string) — bare name, e.g. `old-code` → `<repo>/.worktrees/old-code`. Letters, digits, `.`, `_`, `-`; no `/` or `..`
- `ref` (string) — commit-ish (branch, tag, sha); must not start with `-`
- `detach` (boolean, default `true`) — when true, the ref is resolved to a commit and the worktree is created detached at that sha. Set to `false` to check out an **existing local branch** (fails if the branch does not exist or is checked out elsewhere)

If `/.worktrees/` is not already ignored, `/.worktrees/` is appended to the repository's
`info/exclude` (never to a tracked `.gitignore`).

#### `git_worktree_list`
Lists all worktrees (`git worktree list --porcelain`).

Parameters:
- `path` (string) — absolute path to the repository root or any of its worktrees

#### `git_worktree_remove`
Removes a worktree previously created under `<repo>/.worktrees/`.

Parameters:
- `path` (string) — absolute path to the main checkout
- `worktree` (string) — bare name, as passed to `git_worktree_add`
- `force` (boolean, default `false`) — pass `--force`, removing the worktree even with uncommitted or untracked changes. **Destroys those changes.**

Safety: refuses the main worktree, anything that is not a registered worktree under
`<repo>/.worktrees/` (including symlinks escaping it), and the worktree you are calling from
(call with the main repo path instead). Without `force`, git refuses to remove a dirty worktree.

## Errors

A failing git command returns git's own error text, not a generic failure message.

## What Is NOT Exposed

- `git push` — no remote write operations
- `git reset --hard` — no destructive history rewrites
- Force operations, except `git_worktree_remove force`, which is limited to `.worktrees/`
- Branch deletion
- Rebase

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
