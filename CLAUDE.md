# DryAge — working notes for agents

Engine 8: vault of SmokeHouse backup generations + as-of reads. One class (`DryAge`):
`preserve` (backup → `gen-<n>` via atomic move), `generations`, `asOf` (scratch-copy
open — recovery may touch its dir; history may not be touched), `release`.

## Invariants (do not break)
- **The vault is history: append and release only, never mutate.** Views always run on
  scratch copies; if a view could write through to a generation, that's the bug.
- **Preserve is the shutter.** No hidden auto-snapshots; the caller's cadence is the
  timeline. Record-granularity as-of needs an upstream bounded-recovery seam — cut it in
  CSRBT/SmokeHouse with a consumer and evidence, not here with a hack.
- Oracle tests in `DryAgeTest` (three moments, exact equality each).

## Git is host-side
Same as the siblings: agent sandboxes cannot write `.git`. Run all git commands from a host
terminal (PowerShell). Stale `.git/index.lock` fix: `Remove-Item .git\index.lock -Force`.
