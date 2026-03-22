# ROADMAP.md

## Guiding principles

- Agents are specialized — each owns a clear domain, no generalists
- Bash is off-limits for agents — MCP tools replace shell escape hatches
- Generally applicable software dev knowledge lives in metadev; domain-specific stays in projects
- devenv is the center of gravity

---

## Milestone 1: Eliminate Bash from all agents [DONE — 0.10.0]

**Goal:** Every agent operates within its named tool boundary. No Bash escape hatch.

### 1.1 Wire mcp-test into toolsmith [DONE]
The mcp-test server already exists. Tools added to toolsmith agent definition; Bash removed.

### 1.2 Tighten code-minion [DONE]
Removed the 4 polylith read tools (`polylith_info`, `polylith_deps`, `polylith_check`, `polylith_status`) from code-minion. These belong to the polylith agent.

### 1.3 Build deploy MCP tools and de-Bash devops [DONE]
SSH MCP server added. devops agent now uses `mcp__ssh__ssh_run` and `mcp__ssh__scp_transfer` for remote operations. Bash removed from devops.

---

## Milestone 2: Add the devenv agent [DONE — 0.10.0]

**Goal:** devenv has a dedicated owner, fulfilling the "center of gravity" principle from VISION.md.

### 2.1 Create devenv MCP tools [DONE]
`mcp__devenv__search_packages` and `mcp__devenv__search_options` available.

### 2.2 Define the devenv agent [DONE]
The devenv agent is read-only: audits projects for compliance with the devenv-as-single-source-of-truth principle. Delegates all fixes to other agents.

### 2.3 Clarify boundary between devenv agent and metadev agent [DONE]
metadev agent owns: skills, docs, conventions, onboarding.
devenv agent owns: environment auditing, package name lookup, devenv option lookup.

---

## Milestone 3: Add the CI agent

**Goal:** CI configuration has a dedicated owner, separate from release management.

### 3.1 Create gh-workflow MCP tools
Tools for reading/writing GitHub Actions workflow files, listing runs, reading logs.

### 3.2 Define the CI agent
Owns: workflow file creation/editing, pipeline debugging, CI configuration.
Tools: gh-ci read tools + gh-workflow write tools.

### 3.3 Narrow release-manager's CI scope
Release-manager keeps gh-ci read tools for gating. CI configuration changes delegate to the CI agent.

---

## Milestone 4: Language-agnostic code-minion

**Goal:** code-minion works well in any language, not just Rust.

### 4.1 Create a language-toolchain MCP server pattern
A template or convention for language-specific test/check/lint MCP servers. The toolsmith stamps these out per language.

### 4.2 Ship a devops skill template with metadev
Provide a scaffold template for `.claude/skills/devops/SKILL.md` so projects do not start from zero.

---

## Deferred

- **PR creation agent** — VISION.md says PRs are a checkpoint; `gh pr create` stays with the human for now
- **WebSearch for other agents** — brainstorm has it; nobody else needs it yet
- **Multi-language architect tools** — architect works read-only with generic principles; language MCP tools are nice-to-have
- **Helix agent expansion** — clean and narrow; leave it alone
