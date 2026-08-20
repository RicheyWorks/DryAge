# DryAge

[![CI](https://github.com/RicheyWorks/DryAge/actions/workflows/ci.yml/badge.svg)](https://github.com/RicheyWorks/DryAge/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)

Engine eight of the ecosystem: the **time-travel engine** — where cuts age until they're
ready. The founding doctrine (the log is the only truth; its bytes never change) means every
historical state still exists. DryAge makes it readable: a vault of CRC'd backup generations,
and `asOf` opens any of them as a full read-only SmokeHouse of the past — every index tier,
every read surface, order statistics included.

```java
var vault = DryAge.vault(vaultDir, opts);
long g1 = vault.preserve(store);              // a moment, kept
// ... life goes on, compactions rewrite the live store ...
try (var past = vault.asOf(g1)) {
    past.store().range(...);                  // the world as it was, exactly
    past.store().medianKey();
}
```

Honest bounds: time travel reaches exactly as far as the vault (preserve is the shutter),
coordinates are generations, not timestamps, and views run on scratch copies so history is
never touched. Record-granularity as-of (a bounded-recovery stop condition upstream) is the
named next seam.

## Design notes

- **The vault is history: append and release, never mutate.** Generations land by atomic
  move; `asOf` views run on scratch copies (recovery may touch its directory — history may
  not be touched), deleted when the view closes. A second visit reads identically.
- **Preserve is the shutter.** No hidden auto-snapshots — the caller's cadence is the
  timeline, and `release` makes aging out old history an explicit policy decision.
- **The past is a full store.** An `AgedView` is a real SmokeHouse: ranges, gets, order
  statistics, even Carver over it. Reading history costs nothing in new machinery.
- **Named next seam:** record-granularity as-of needs a bounded-recovery stop condition
  upstream — to be cut with a consumer and evidence, not simulated here with a hack.
  Measured 2026-08-20: the workaround ("just preserve more often") is priced and dead as a
  substitute — checkpointing every 2k ops costs **191% of the churn's own time** (~1.4 MB
  per checkpoint; each backup is a full prefix copy), against a 25% viability bar
  ([`WholeHog/docs/EXPERIMENT-2026-08-20-cold-triggers.md`](https://github.com/RicheyWorks/WholeHog/blob/main/docs/EXPERIMENT-2026-08-20-cold-triggers.md)).
  The seam stays held on its consumer trigger, now knowing what the fallback costs.

## The ecosystem

Eleven engines, one organism — each in its own repo, composed by nested Gradle
composite builds:

| Engine | Role |
|---|---|
| [CSRBT](https://github.com/RicheyWorks/CSRBT) | the adaptive ordered index — orders the world |
| [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) | the intake tract — profiles, sorts, feeds in O(n) |
| [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) | the log-structured store — durability, tail, watchers, replicas |
| [Carver](https://github.com/RicheyWorks/Carver) | the read planner — decides how to read |
| [Renderer](https://github.com/RicheyWorks/Renderer) | the materialized-view engine — folds the tail into live aggregates |
| [Brine](https://github.com/RicheyWorks/Brine) | the adaptive cache — eviction policy evolved per workload |
| [PitBoss](https://github.com/RicheyWorks/PitBoss) | the fleet conductor — lag watch, re-bootstrap, the promotion runbook |
| **DryAge** (this repo) | the time-travel engine — as-of reads over preserved history |
| [Twine](https://github.com/RicheyWorks/Twine) | crash-atomic multi-key batches — journaled commit, idempotent replay |
| [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) | the wire — a loopback protocol face for the store |
| [Jerky](https://github.com/RicheyWorks/Jerky) | cold storage — compressed, CRC-verified backup archives |
| [WholeHog](https://github.com/RicheyWorks/WholeHog) | the integration organism — all of them, at once |
| [Rub](https://github.com/RicheyWorks/Rub) | the observability engine — tail meter + store gauge, fused into vitals |
| [Sizzle](https://github.com/RicheyWorks/Sizzle) | the chaos engine — deterministic fault injection at the write seam |

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Seeded oracle tests in the house style. MIT license.
