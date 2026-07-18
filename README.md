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

## The ecosystem

Engines 1–6: [CSRBT](https://github.com/RicheyWorks/CSRBT) (index) · [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) (intake) · [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) (store) · [Carver](https://github.com/RicheyWorks/Carver) (read planner) · [Renderer](https://github.com/RicheyWorks/Renderer) (materialized views) · [Brine](https://github.com/RicheyWorks/Brine) (adaptive cache).
Engines 7–11: [PitBoss](https://github.com/RicheyWorks/PitBoss) (fleet conductor) · [DryAge](https://github.com/RicheyWorks/DryAge) (time travel) · [Twine](https://github.com/RicheyWorks/Twine) (atomic batches) · [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) (the wire) · [Jerky](https://github.com/RicheyWorks/Jerky) (cold archives).

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Seeded oracle tests in the house style. MIT license.
