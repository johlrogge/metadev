# VISION.md

## What metadev is

metadev is a development meta-environment. It provides general-purpose software development agents, tools, and conventions that any project can import via devenv module composition.

## Core principle

**metadev holds generally applicable software development knowledge. Projects hold domain-specific knowledge.**

metadev knows how to build, test, release, and maintain software. It does not know what the software does — that belongs to each project.

## How it works

### Polymorphic agents

Agents are general-purpose but adapt to each project through convention documents found at the project root:

- **VISION.md** — what the project is and where it is going
- **ROADMAP.md** — what is planned and prioritized
- **RELEASING.md** — how releases are made
- **Skills** — project-specific capabilities agents can learn

The same agent behaves differently in different projects because it reads different context, not because it has different code.

### Agent autonomy and boundaries

Agents do autonomous work within clear limits:

- **Commits:** yes — agents commit their work
- **Pushing branches:** no — that crosses a trust boundary
- **Pull requests:** yes — PRs are the lightweight checkpoint between agent work and integration
- **Bash:** no — every agent is constrained to its named MCP tools. No escape hatch.

### Specialized agents own their domain

Each agent has a clear domain of responsibility (devenv, CI, architecture, toolsmithing, etc.). When work crosses into another agent's domain, agents defer rather than duplicate. No agent is a generalist that does everything.

### devenv as center of gravity

devenv hooks are the single source of truth for build, test, and check operations. CI runs the same hooks. There is one definition of correctness, not two.

## Goals

- **Fast bootstrap:** a new project gets the full agent stack by importing one devenv module
- **Fast migration:** existing projects can adopt metadev incrementally
- **Reusable, not rigid:** agents provide structure without imposing project-specific decisions
- **Easy upgrades:** the metadev agent guides projects through upgrades, handling migration steps and flagging breaking changes
