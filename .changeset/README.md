# Changesets

This folder is managed by `@changesets/cli`. Per [D18](../ROADMAP.md#confirmed-decisions), this repo uses the same release-notes workflow as `@adcp/sdk`.

## When to add a changeset

Add a changeset whenever your PR changes adopter-visible behavior on any of the eight published artifacts (`adcp`, `adcp-server`, `adcp-testing`, `adcp-spring-boot-starter`, `adcp-cli`, `adcp-reactor`, `adcp-mutiny`, `adcp-kotlin`).

You don't need a changeset for:

- Test-only changes
- Internal refactors with no public-API impact
- `docs:` commits that don't touch generated docs
- `chore:` / `ci:` commits

The `changeset-check` workflow on PRs is informational for borderline cases — when in doubt, add one.

## How to add a changeset

```bash
npx changeset
```

Pick the affected artifacts, pick a bump level (`patch` / `minor` / `major`), and write a short user-facing summary. Commit the generated `.changeset/*.md` file with your PR.

## Versioning

Maven Central releases are produced from accumulated changesets. The first publish is **at v0.3 alpha** (per [D6](../ROADMAP.md#confirmed-decisions)); v0.1 and v0.2 ship as local Gradle artifacts only. SDK semver is independent of AdCP spec major (per RFC §Versioning).

## More

[Changesets docs](https://github.com/changesets/changesets) · [Common questions](https://github.com/changesets/changesets/blob/main/docs/common-questions.md)
