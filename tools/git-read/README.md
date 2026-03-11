# git-read MCP Server

Read-only git operations over MCP. Lets agents inspect any repository without needing broad Bash access.

## Tools

| Tool | Description | Required params | Optional params |
|------|-------------|-----------------|-----------------|
| `git_status` | Short working-tree status (`git status --short`) | `path` | — |
| `git_diff` | Show diffs (`git diff [args]`) | `path` | `args` (e.g. `"--staged"`, `"HEAD~1"`) |
| `git_log` | Recent commits one-line (`git log --oneline -n`) | `path` | `n` (default: 10) |
| `git_show` | Show a commit or object | `path`, `ref` | — |
| `git_branch` | List local branches | `path` | — |

All tools take an absolute `path` to the repository so a single server instance can inspect any repo.

## Registration in devenv.nix

```nix
claude.code.mcpServers.git-read = {
  type = "stdio";
  command = "bb";
  args = [ "/home/johlrogge/projects/metadev/tools/git-read/server.bb" ];
};
```

Or, if the consuming project lives next to metadev and imports it:

```nix
claude.code.mcpServers.git-read = {
  type = "stdio";
  command = "bb";
  args = [ "${inputs.metadev}/tools/git-read/server.bb" ];
};
```

## Running locally

```bash
bb tools/git-read/server.bb
```

The server reads JSON-RPC messages on stdin and writes responses to stdout.
It requires `git` and `bb` to be on the PATH.
