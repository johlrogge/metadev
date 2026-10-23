# align-nixpkgs

A weekly job that keeps every devenv project under `~/projects` on the same
`cachix/devenv-nixpkgs` `rolling` revision. When projects share one nixpkgs revision they
share most of their toolchain closure, so `/nix/store` holds one copy of it instead of one
per project.

This is a Babashka CLI, not an MCP server. Agents run it through the `just` MCP using the
recipes in metadev's `justfile`.

## What it does

For each `<projects-dir>/*/devenv.yaml` it handles metadev first, then the rest in
alphabetical order. One project failing never stops the run.

1. Reads `devenv.yaml` and `devenv.lock` from the project's integration branch. That is
   `gitflow.branch.develop` if set, otherwise `master`, otherwise `main`. It uses the local
   branch only and never fetches.
2. Skips the project if its nixpkgs input is not `github:cachix/devenv-nixpkgs/rolling`
   (reported as NONSTANDARD), if it has relative `path:` inputs, if `devenv.lock` is not
   tracked, and so on.
3. Creates a throwaway worktree `<project>/.worktrees/align-nixpkgs` on branch
   `chore/align-nixpkgs`, branched from the integration branch.
4. Runs `devenv update nixpkgs`. If the lock does not land on the current rolling revision,
   it falls back to `--override-input nixpkgs github:cachix/devenv-nixpkgs/<rev>`. After that
   it updates `devenv` and `metadev` when those inputs exist.
5. Builds with `devenv shell -- true` (timeout `--build-timeout-min`, default 45). After the
   build it reads the lock again and fails the project if nixpkgs drifted.
6. Commits only `devenv.lock` with the trailer `Aligned-by: metadev align-nixpkgs`, using
   `--no-verify` and the repo's configured identity.
7. Always removes the worktree afterwards. If the build changed the shared `.git/hooks`
   directory or `core.hooksPath`, both are restored.

It never pushes and never fetches. Merging is up to you.

### Branch handling

| State of `chore/align-nixpkgs` | Action |
|---|---|
| does not exist | create |
| already merged into the integration branch | recreate |
| unmerged, holds only earlier proposals from this job (trailer present, diff is only `devenv.lock`) | recreate; the report notes which proposal it replaced |
| checked out at the job's worktree path (left over from a crash) | remove the worktree, then decide as above |
| checked out anywhere else | SKIPPED (branch-checked-out) |
| has other commits | SKIPPED (foreign-commits) |

### The metadev input

- A `github:johlrogge/metadev` input is updated to the pushed default branch.
- An absolute `path:///…/metadev` input is not updated while the metadev checkout is dirty,
  and the report gets a warning about it. You also get a warning when the checkout is not
  on its integration branch, because a path input follows whatever is checked out.

## Usage

```bash
just align-nixpkgs-dry                       # show what would happen; writes nothing
just align-nixpkgs                           # real run
just align-nixpkgs --only ctx --only corsett # some projects only
just align-nixpkgs --exclude mdma
just align-nixpkgs-install-timer             # weekly systemd user timer
just align-nixpkgs-status                    # timer state + latest report path
just align-nixpkgs-test                      # unit tests
```

Or directly: `bb tools/align-nixpkgs/align.bb run [--dry-run] [--only NAME]... [--exclude NAME]...
[--projects-dir DIR] [--gc] [--no-notify] [--min-free-gb N] [--build-timeout-min N]`.

Exit codes:

| Code | Meaning |
|---|---|
| 0 | ok or nothing to do |
| 1 | fatal: rolling revision could not be resolved, bad arguments, or an unknown `--only`/`--exclude` name |
| 2 | at least one project FAILED |
| 75 | another run holds the lock |

Set `ALIGN_NIXPKGS_DEBUG=1` to print every external command and its exit code to stderr.

## Where things go

All state lives in `~/.local/state/metadev/align-nixpkgs/`:

| Path | Contents |
|---|---|
| `<YYYY-MM-DD>.md` | the run's report (a second run that day gets `-2`, `-3`, …) |
| `latest.md` | symlink to the newest report |
| `logs/<date>/<project>.log` | full `devenv update` / `devenv shell` output for each project |
| `logs/<date>/gc.log` | `nix-collect-garbage` output |
| `gcroots/<project>/` | GC roots that keep the new closure alive until you merge |
| `run.lock` | lock file for the current run |

A dry run writes none of this. It only prints the table.

## Reviewing and merging

The report lists the exact commands for each ALIGNED project:

```bash
git -C ~/projects/<p> log -p <base>..chore/align-nixpkgs
git -C ~/projects/<p> merge --ff-only chore/align-nixpkgs   # with <base> checked out
cd ~/projects/<p> && devenv shell
```

## GC expectations

`--gc` (the timer passes it) runs `nix-collect-garbage` without `-d` after all projects are
done. Expect the first runs to free little. Old toolchains stay rooted by each project's
main checkout, through its `.devenv/gc` links. The space comes back once you merge
`chore/align-nixpkgs` and re-enter `devenv shell` in that main checkout, because that moves
the project's GC root to the shared closure. To see which roots remain:

```bash
nix-store --gc --print-roots | grep projects
```

The job keeps its own roots under `gcroots/<project>/`, so the closure it just built
survives GC until you merge.

## systemd timer

`install-timer` renders `systemd/metadev-align-nixpkgs.service.in` and writes the service
and the timer to `~/.config/systemd/user/`. It then runs `systemd-analyze --user verify`,
`daemon-reload`, and `enable --now` on the timer. The service runs every Monday around
11:00 (`Persistent=true`, up to 1 h random delay) inside metadev's devenv shell:
`devenv shell -- bb tools/align-nixpkgs/align.bb run --gc`.

The unit needs a stable `devenv` path, such as `~/.nix-profile/bin/devenv` or a
non-`/nix/store` path from `which devenv`. If only a `/nix/store` path is found, install
refuses. `uninstall-timer` disables the timer and removes both files. Running either
command again is safe.

## Caveats

- The job uses the local integration branch and never fetches, so pull first if you want
  the newest base.
- Submodules are not initialised in the worktree. Projects that need them show up as
  FAILED (build).
- A slow `enterShell` counts toward the build timeout.
- Child commands run with a scrubbed environment that has no `DEVENV_*` or `IN_NIX_SHELL`.
  Commit signing that needs `SSH_AUTH_SOCK` or `GPG_TTY` fails, and the project is reported
  as FAILED (commit).
- devenv 2.x drops stale lock nodes when it updates. Expect lock diffs that are larger than
  the nixpkgs change alone.
