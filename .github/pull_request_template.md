<!--
Thanks for your PR. Before it's mergeable:

1. The AAO IPR Bot will ask you to comment `I have read the IPR Policy` on
   your first PR ever to an AAO repo. One-time per contributor across all
   AAO repos.
2. CI (`build`, `IPR Policy / Signature`, `commitlint`, `changeset-check`)
   must be green.
3. One code-owner approving review (per D21).
4. PR title and commits follow Conventional Commits.
5. If the change touches public surface, add a changeset via `npx changeset`.
-->

## Summary

<!-- Two or three sentences. Why is this change happening, not what changed (the diff says what). -->

## Tracks / decisions touched

<!-- ROADMAP.md track IDs and D-numbers this PR affects. e.g. `signing`, D11. -->

## Test plan

<!--
Bulleted checklist. What you ran to verify, what you'd want a reviewer to run.
At minimum: `./gradlew build` green.
-->

- [ ] `./gradlew build` green
- [ ] New tests added for new behavior
- [ ] Public API surface change → changeset added
- [ ] Builder/transport/lifecycle/third-party SDK change → integration or manual smoke test proves the real composed path runs (not only stubs/mocks)
- [ ] Invalid/overlong public names or IDs are rejected, not silently truncated or normalized server-side
- [ ] No workspace-local artifacts committed (`.wt-claim`, `.wt-*`, `.context/`, `.local/`, `.DS_Store`)
