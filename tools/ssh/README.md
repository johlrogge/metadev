# ssh MCP Server

SSH and SCP access scoped to a project's SSH config. Lets agents run remote commands and transfer files without needing broad Bash access or touching `~/.ssh/`.

## Tools

| Tool | Description | Required params |
|------|-------------|-----------------|
| `ssh_run` | Run a command on a remote host | `host`, `command` |
| `scp_transfer` | Transfer a file between local and remote | `src`, `dest` |

`ssh_run` returns the exit code, stdout, and stderr of the remote command as structured text.

`scp_transfer` uses `host:path` format for remote sides (e.g. `myserver:/tmp/file.txt`). Either `src` or `dest` (or both) can be a remote path.

## Security model

- Every SSH/SCP call uses `-F ~/.metadev/projects/<project>/.ssh/config` — the user's `~/.ssh/` is never consulted.
- Only hosts defined in the project config can be reached (SSH will reject any host not in the config).
- No tools for editing configs, adding host keys, or managing key material.

## Project SSH config setup

The server resolves the project name from the `METADEV_PROJECT` environment variable.

Create the config directory for your project:

```bash
mkdir -p ~/.metadev/projects/<project-name>/.ssh
chmod 700 ~/.metadev/projects/<project-name>/.ssh
```

Add an SSH config file at `~/.metadev/projects/<project-name>/.ssh/config`:

```
Host myserver
    HostName 192.168.1.10
    User deploy
    IdentityFile ~/.metadev/projects/<project-name>/.ssh/id_ed25519
    StrictHostKeyChecking yes
    UserKnownHostsFile ~/.metadev/projects/<project-name>/.ssh/known_hosts
```

Place private keys in the same directory and restrict permissions:

```bash
chmod 600 ~/.metadev/projects/<project-name>/.ssh/id_ed25519
```

Populate `known_hosts` by connecting once manually (with your own SSH config), or copy the relevant line from `~/.ssh/known_hosts`.

## Setting METADEV_PROJECT

The environment variable is normally set by the project's `devenv.nix`:

```nix
env.METADEV_PROJECT = "my-project";
```

## Registration in devenv.nix

```nix
claude.code.mcpServers.ssh = {
  type = "stdio";
  command = "bb";
  args = [ "${inputs.metadev}/tools/ssh/server.bb" ];
};
```

If consuming from a local path:

```nix
claude.code.mcpServers.ssh = {
  type = "stdio";
  command = "bb";
  args = [ "/home/johlrogge/projects/metadev/tools/ssh/server.bb" ];
};
```

## Running locally

```bash
METADEV_PROJECT=my-project bb tools/ssh/server.bb
```

Requires `ssh`, `scp`, and `bb` on the PATH.
