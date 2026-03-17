# Polylith Architecture in Rust

Polylith is a monorepo architecture for maximum component reuse across multiple deployable artifacts.
Originally from Clojure; this document describes the Rust/Cargo mapping.

## Core Concepts

### Component
A Cargo library crate under `components/<name>/`. Its **interface is the public surface of `lib.rs`** — the `pub` items re-exported from private submodules. Nothing beyond the crate boundary is accessible to other bricks.

```
components/user/
  Cargo.toml
  src/
    lib.rs        ← ONLY pub use re-exports
    user.rs       ← private implementation
```

```rust
// src/lib.rs — the interface: what other bricks can see
mod user;
pub use user::create_user;
pub use user::get_user;
pub use user::User;
```

```rust
// src/user.rs — the implementation: private
pub fn create_user(email: &str) -> Result<User> { ... }
pub fn get_user(id: UserId) -> Result<User> { ... }
```

**No traits required.** The interface is plain named functions, as Joakim Tengstrand (polylith inventor) intends. The crate's pub surface IS the interface contract.

### Base
A Cargo **library crate** under `bases/<name>/`. Exposes a runtime API (HTTP server, CLI, IPC, gRPC …) as ordinary Rust functions (`run()`, `serve()`, `create_sockets()`). Bases wire components together but do not hardcode which implementations are used.

**Bases must NOT have `src/main.rs`.** If a base were a binary, two bases could never share one process.

```
bases/http_api/
  Cargo.toml
  src/
    lib.rs        ← pub fn serve(...) / run(...) / create_sockets(...)
    handler.rs    ← private implementation
```

### Project
A Cargo workspace root under `projects/<name>/`. Owns `src/main.rs` and calls the bases' runtime-API functions. Projects CAN depend on components directly (valid polylith). Projects MUST depend on at least one base.

```
projects/production/
  Cargo.toml      ← project workspace root + [package] + [[bin]]
  src/main.rs     ← entry point: calls base fns, wires components
```

A project has **no domain logic** — all logic lives in bricks. The `main.rs` is a thin wiring point.

### Development Workspace
The repo root `Cargo.toml`. Lists ALL components and bases as members. Used for `cargo check`, IDE support, and day-to-day development. Not a deployment artifact.

## Directory Layout

```
repo-root/
  Cargo.toml              ← development workspace: members = all components + bases
  .cargo/config.toml      ← [build] target-dir = "target"  (shared across all workspaces)
  components/             ← library crates (NOT a workspace root)
    user/
      Cargo.toml
      src/lib.rs          ← pub re-exports only
      src/user.rs         ← private impl
    user_inmemory/        ← alternative implementation (same crate name "user")
      Cargo.toml
      src/lib.rs
      src/user.rs
  bases/                  ← runtime-API library crates (lib only, no main.rs)
    http_api/
      Cargo.toml
      src/lib.rs          ← pub fn serve(...)
  projects/
    production/
      Cargo.toml          ← project workspace root + [package] + [[bin]]
      src/main.rs         ← entry point: calls base fns
    test-env/
      Cargo.toml          ← different implementation choices
      src/main.rs
```

## `cargo polylith check` Violations

| Violation                | Kind    | Exit |
|--------------------------|---------|------|
| Component missing lib.rs | error   | 1    |
| Base missing lib.rs      | error   | 1    |
| Base has main.rs         | warning | 0    |
| Base depends on base     | error   | 1    |
| Project has no base dep  | warning | 0    |
| Component not reachable  | warning | 0    |
| Wildcard re-export       | warning | 0    |

Projects depending directly on components is valid and not flagged.

## Swappable Implementations

Alternative implementations of the same component share the same Cargo package name:

```toml
# components/user_inmemory/Cargo.toml
[package]
name = "user"             # same name as components/user/
version = "0.1.0"
```

The compiler enforces compatibility — if a function is missing or has the wrong signature, it is a compile error everywhere that function is called. This is Rust's type system doing the interface checking, with no traits involved.

### How Projects Select Implementations

Bases declare component dependencies as **workspace-inherited deps** (`workspace = true`). The project workspace defines which path each name resolves to:

```toml
# bases/http_api/Cargo.toml
[dependencies]
user = { workspace = true }   # resolves to whatever the project workspace says
```

```toml
# projects/production/Cargo.toml
[workspace]
members = ["../../bases/http_api"]

[workspace.dependencies]
user = { path = "../../components/user" }           # Postgres implementation
```

```toml
# projects/test-env/Cargo.toml
[workspace]
members = ["../../bases/http_api"]

[workspace.dependencies]
user = { path = "../../components/user_inmemory" }  # in-memory implementation
```

The `cargo-polylith` tool generates and manages these project workspace files. Build a specific project with:
```bash
cargo build --manifest-path projects/production/Cargo.toml
```

## Project Workspace Structure

A project workspace lists bases as `[workspace].members`. Components are not workspace members (they live outside the project directory) — they come in as workspace-level path dependencies resolved transitively.

```toml
# projects/production/Cargo.toml
[workspace]
members = [
  ".",
  "../../bases/http_api",
  "../../bases/cli",
]

[package]
name = "production"
version = "0.1.0"

[[bin]]
name = "production"
path = "src/main.rs"

[dependencies]
http_api = { path = "../../bases/http_api" }
cli      = { path = "../../bases/cli" }

[workspace.dependencies]
user     = { path = "../../components/user" }
library  = { path = "../../components/library_service" }
```

All bases in the same project share the same `[workspace.dependencies]` pool — one implementation choice per interface name per project.

## Shared Target Directory

Without configuration, each project workspace would have its own `target/` directory and recompile everything. Solve this with a single `.cargo/config.toml` at the repo root:

```toml
[build]
target-dir = "target"
```

Cargo hashes artifacts by (crate + features + profile + target triple), so identical builds across projects share compiled artifacts.

## The Development Workspace

The repo root workspace lists all components and bases:

```toml
# Cargo.toml (repo root)
[workspace]
members = [
  "components/*",
  "bases/*",
]
```

This gives full IDE support and lets you run `cargo check` across the entire codebase. Component dependencies here can use direct path deps (no `workspace = true` needed since the dev workspace isn't a project).

## What the cargo-polylith Tool Does

Managing this structure by hand is tedious. `cargo-polylith` handles:

- **Scaffolding**: `cargo polylith component new <name>` creates the crate with the correct `lib.rs` re-export skeleton
- **Base scaffolding**: `cargo polylith base new <name>` creates `bases/<name>/` with `lib.rs` (pub fn run() skeleton) and Cargo.toml
- **Dependency wiring**: `cargo polylith base add-dep <base> <component>` adds the `workspace = true` dep and updates the project workspace dependencies
- **Project management**: `cargo polylith project new <name>` generates the project workspace manifest
- **Overview**: `cargo polylith deps` shows which components are used by which bases and projects
- **Interface checking**: `cargo polylith check` verifies structural correctness and reports violations

## Polylith in mdma

The `modular-digital-music-array` project at `~/projects/modular-digital-music-array` is the primary migration target. It has 25 components and 3 bases, plus a `projects/` layer with 9 projects.

Current status:
- Most bases are correctly lib crates: `http-server` exposes `serve()`, `service` exposes `create_sockets()`
- `mdma-library` base incorrectly has `main.rs` instead of `lib.rs` (flagged as warning by `cargo polylith check`)
- Three projects (`mdma-cli`, `mdma-gateway`, `mdma-tui`) have no base dependency yet (flagged as warnings)

`cargo polylith check` reports these violations to guide the migration.
