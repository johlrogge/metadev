# Release Workflow

Metadev uses git flow with a multi-agent pre-release checklist.

## Branch Model

- `master` — released code only, always tagged
- `develop` — integration branch, features merge here
- `feature/*` — individual features, branch from develop
- `release/*` — release prep, branch from develop
- `hotfix/*` — urgent fixes, branch from master

## Versioning (semver)

- `feat` commits → minor bump (0.4.0 → 0.5.0)
- `fix` / `chore` commits → patch bump (0.5.0 → 0.5.1)
- Breaking change (`!`) → major bump (0.5.0 → 1.0.0)

## Release Checklist

Run these agents in order before cutting a release:

1. **rust-architect** — review new code for correctness and quality
   > "Review changes since last release"

2. **product-owner** — confirm the release delivers intended value
   > "Review the planned 0.x.0 release"

3. **documenter** — update README files to reflect the release
   > "Update docs for release 0.x.0"

4. **devops** — start and finish the release branch
   > "Start release 0.x.0" → confirm → "Finish release 0.x.0"

5. **Human** — push to remote
   ```
   git push origin master develop --tags
   ```

## Hotfix Checklist

1. **devops** — start hotfix
2. **commit** — commit the fix
3. **devops** — finish hotfix (confirm before calling)
4. **Human** — push

## Notes

- Agents never push — that always stays with the human
- Always confirm with devops before finishing a release or hotfix
- The commit agent reads `.claude/skills/conventional-commits/SKILL.md` for format
