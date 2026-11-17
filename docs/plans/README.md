# Plans

This directory is the source of truth for what is being built, how, and where the work stands. It exists so that a new session or a new person can read it and continue without the earlier conversation.

## Files

| File | Role |
|---|---|
| `master-plan.md` | The overall plan: goals, decisions made by the owner, the stack, and the build order in slices 0 to 6. It supersedes the original local draft; this copy is authoritative, so edit it here. |
| `slice-0-brief.md` | Implementation brief for slice 0 (tooling, Makefile, dev stack, PR pipeline). Its "Amendments" section, added after the design challenge, overrides the body. |
| `slice-0-tests-brief.md` | Brief for the slice 0 follow-up: unit, integration and end-to-end tests, coverage gates and test documentation. Same rule: its amendments (T1 to T17) override the body. |
| `STATUS.md` | Where each slice stands: a table of steps and their state, changes made to the brief during implementation, review findings and how they were resolved, open items for the owner, and how to resume. |

A brief is named `slice-N-brief.md` for a slice, or `slice-N-<topic>-brief.md` for a follow-up to that slice.

## Process per slice

1. Read the code, check versions against registries and docs, and write the brief.
2. A separate reviewer challenges the brief. Accepted findings are appended to the brief as amendments; the body is not rewritten.
3. Implementers work in parallel on non-overlapping files, each verifying with real commands in containers.
4. The integrated checks run (`make ci`, and `make dev` where relevant).
5. Two fresh reviewers look at the diff independently. Findings are fixed and resubmitted until both approve.
6. The result and the reviewers' findings and resolutions are reported. The owner runs all git commands.

## Keeping STATUS.md current

`STATUS.md` is updated at every step change: a brief written, a challenge done, an implementation finished, a review approved. Do not batch the updates until the end of a slice. If a decision in a brief changes during implementation, record it in the "Changes to the brief" list there as well as in the brief's amendments.

## Resuming work

1. Read `master-plan.md`, the brief for the current slice (amendments first) and `STATUS.md`.
2. Find the slice or follow-up marked in progress in `STATUS.md` and read its "To resume" notes.
3. Compare the files that exist against the layout in the brief and finish what is missing.
4. Run `make ci`. Docker works only in a shell with the `docker` group; otherwise prefix commands with `sg docker -c "..."`.
5. Continue from the first step in the status table that is not done, and update the table.

Nothing is installed on the host: every tool runs in a container through the `Makefile`.

## Related

- `docs/adr/`: architecture decisions and their reasons.
- `tests/README.md`: what each kind of test proves and how to run it.
- `tests/manual/README.md`: checks that must be done by hand.
