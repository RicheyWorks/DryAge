package io.github.richeyworks.dryage;

import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Time travel against the oracle: preserve at three points in a store's life, keep the
 * oracle's snapshot at each, and every {@code asOf} view must equal its moment exactly —
 * unaffected by everything that happened after, and repeatably (the vault stays pristine
 * across views). Seeded and deterministic.
 */
class DryAgeTest {

    private static SmokeHouseOptions<Long, String> opts() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .segmentBytes(2048)
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);
    }

    private static void churn(SmokeHouse<Long, String> store, TreeMap<Long, String> oracle,
                              Random rnd, int ops) throws IOException {
        for (int i = 0; i < ops; i++) {
            long key = rnd.nextInt(100);
            if (rnd.nextInt(6) == 0) {
                store.delete(key);
                oracle.remove(key);
            } else {
                String v = "v" + key + ":" + i;
                store.put(key, v);
                oracle.put(key, v);
            }
        }
    }

    private static TreeMap<Long, String> scan(SmokeHouse<Long, String> store) throws IOException {
        TreeMap<Long, String> out = new TreeMap<>();
        if (store.size() > 0) {
            store.range(store.firstKey(), store.lastKey(), out::put);
        }
        return out;
    }

    @Test
    void everyPreservedMomentStaysReadableExactly(@TempDir Path storeDir, @TempDir Path vaultDir)
            throws IOException {
        Random rnd = new Random(42);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            DryAge<Long, String> vault = DryAge.vault(vaultDir, opts());

            churn(store, oracle, rnd, 200);
            long g1 = vault.preserve(store);
            TreeMap<Long, String> at1 = new TreeMap<>(oracle);

            churn(store, oracle, rnd, 200);
            store.compact();                                   // live-store history rewritten…
            long g2 = vault.preserve(store);
            TreeMap<Long, String> at2 = new TreeMap<>(oracle);

            churn(store, oracle, rnd, 200);
            long g3 = vault.preserve(store);
            TreeMap<Long, String> at3 = new TreeMap<>(oracle);

            assertEquals(List.of(g1, g2, g3), vault.generations(), "the timeline, ascending");

            try (DryAge.AgedView<Long, String> v1 = vault.asOf(g1);
                 DryAge.AgedView<Long, String> v2 = vault.asOf(g2);
                 DryAge.AgedView<Long, String> v3 = vault.asOf(g3)) {
                assertEquals(at1, scan(v1.store()), "…but the vault's past is untouched");
                assertEquals(at2, scan(v2.store()));
                assertEquals(at3, scan(v3.store()));
                assertEquals(at1.size(), v1.store().size());
                if (v1.store().size() >= 3) {
                    assertEquals(v1.store().nthKey(1), v1.store().firstKey(),
                            "order statistics work on the past");
                }
            }
            // A second visit must read identically — views never disturb the vault.
            try (DryAge.AgedView<Long, String> again = vault.asOf(g1)) {
                assertEquals(at1, scan(again.store()));
            }
        }
    }

    @Test
    void aFailedPreserveLeavesTheVaultExactlyAsItFoundIt(@TempDir Path storeDir,
                                                         @TempDir Path vaultDir)
            throws IOException {
        // Ninth-pass finding 1: a failed backup used to leak its staging directory inside the
        // vault, forever. Force the failure honestly: preserve from a CLOSED store.
        DryAge<Long, String> vault = DryAge.vault(vaultDir, opts());
        SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts());
        store.put(1L, "one");
        long good = vault.preserve(store);                     // one real generation first
        store.close();

        assertThrows(Exception.class, () -> vault.preserve(store, true),
                "preserving from a closed store must fail loudly");

        try (var listing = java.nio.file.Files.list(vaultDir)) {
            List<String> leftovers = listing.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("staging-")).toList();
            assertTrue(leftovers.isEmpty(), "no staging leak: " + leftovers);
        }
        assertEquals(List.of(good), vault.generations(), "the vault is exactly as it was");
    }

    @Test
    void retainNewestAgesOutTheOldAndReportsIt(@TempDir Path storeDir, @TempDir Path vaultDir)
            throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            DryAge<Long, String> vault = DryAge.vault(vaultDir, opts());
            long g1 = preserveAfterPut(vault, store, 1L, "one");
            long g2 = preserveAfterPut(vault, store, 2L, "two");
            long g3 = preserveAfterPut(vault, store, 3L, "three");
            long g4 = preserveAfterPut(vault, store, 4L, "four");

            // Keep more than exist: nothing released.
            assertEquals(List.of(), vault.retainNewest(9), "nothing to age out yet");

            // Keep 2: the two OLDEST go, ascending, on the record.
            assertEquals(List.of(g1, g2), vault.retainNewest(2), "the released, for the log");
            assertEquals(List.of(g3, g4), vault.generations(), "the newest two remain");
            assertThrows(IllegalArgumentException.class, () -> vault.asOf(g1),
                    "released history is really gone");
            try (DryAge.AgedView<Long, String> v = vault.asOf(g4)) {
                assertEquals(4, v.store().size(), "the survivors still read true");
            }

            // Zero empties the vault; negative is a caller defect.
            assertEquals(List.of(g3, g4), vault.retainNewest(0));
            assertTrue(vault.generations().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> vault.retainNewest(-1));
        }
    }

    @Test
    void aFailedReleaseLeavesTheGenerationWholeAndReportsTheAudit(@TempDir Path storeDir,
                                                                  @TempDir Path vaultDir)
            throws IOException {
        // Tenth-pass D3: a throw mid-release used to lose the audit list and leave a half-deleted
        // gen- that generations() still lists and asOf restores truncated. Now the release renames
        // gen- out of the namespace atomically first, and retainNewest presses past a failure while
        // reporting the full outcome. Force one drop to fail honestly: pre-plant the tombstone name
        // the oldest generation's atomic rename would claim, so that rename refuses (target exists)
        // while the generation itself stays untouched.
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            DryAge<Long, String> vault = DryAge.vault(vaultDir, opts());
            long g1 = preserveAfterPut(vault, store, 1L, "one");
            long g2 = preserveAfterPut(vault, store, 2L, "two");
            long g3 = preserveAfterPut(vault, store, 3L, "three");

            java.nio.file.Files.createFile(vaultDir.resolve(".releasing-" + g1));   // blocks g1's rename

            // Keep 1 (g3): g1 and g2 are doomed. g2 ages out; g1's atomic rename refuses.
            DryAge.RetentionException partial = assertThrows(DryAge.RetentionException.class,
                    () -> vault.retainNewest(1), "a partial failure surfaces, not a silent loss");
            assertEquals(List.of(g2), partial.released(), "g2 really aged out, and it's on the record");
            assertEquals(List.of(g1), partial.failed(), "g1 could not be released — reported, not lost");

            // The core of the fix: the failed generation is WHOLE, not half-deleted.
            assertEquals(List.of(g1, g3), vault.generations(),
                    "g2 gone; g1 survived the failed release intact; g3 kept");
            try (DryAge.AgedView<Long, String> v = vault.asOf(g1)) {
                assertEquals(1, v.store().size(), "g1 restores fully — never a truncated ruin");
                assertEquals("one", v.store().get(1L));
            }
            assertThrows(IllegalArgumentException.class, () -> vault.asOf(g2),
                    "the released generation is really gone");
        }
    }

    @Test
    void aTombstoneNeverShowsInTheTimelineAndReopenSweepsIt(@TempDir Path storeDir,
                                                            @TempDir Path vaultDir)
            throws IOException {
        // The commit-point invariant, directly: a tombstone (a release interrupted after the
        // rename but before the delete) is invisible to the timeline and unreachable via asOf,
        // and the next vault() open reclaims its bytes.
        long g;
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            DryAge<Long, String> vault = DryAge.vault(vaultDir, opts());
            g = preserveAfterPut(vault, store, 1L, "one");
        }
        // Simulate a crash right after the rename: the generation now lives under a tombstone name.
        java.nio.file.Files.move(vaultDir.resolve("gen-" + g),
                vaultDir.resolve(".releasing-" + g));
        DryAge<Long, String> reopened = DryAge.vault(vaultDir, opts());   // sweeps the tombstone
        assertTrue(reopened.generations().isEmpty(), "a tombstone is not a generation");
        try (var listing = java.nio.file.Files.list(vaultDir)) {
            assertTrue(listing.map(p -> p.getFileName().toString())
                            .noneMatch(n -> n.startsWith(".releasing-")),
                    "reopen reclaimed the orphan tombstone's bytes");
        }
    }

    @Test
    void aStoreRollbackDoesNotBrickPreserveOrDropTheNewest(@TempDir Path storeA,
                                                           @TempDir Path storeB,
                                                           @TempDir Path vaultDir)
            throws IOException {
        // Tenth-pass D4: preserve used to label the generation by the store-issued backup number.
        // A store rollback (or a fresh store) re-issues low numbers, so the next preserve collided
        // with an existing generation ("already preserved") and bricked the vault; and retainNewest
        // ordered by that number, so it could release the actually-newest data. The vault now issues
        // its own monotonic labels.
        DryAge<Long, String> vault = DryAge.vault(vaultDir, opts());
        long gA0;
        long gA1;
        try (SmokeHouse<Long, String> a = SmokeHouse.open(storeA, opts())) {
            gA0 = preserveAfterPut(vault, a, 1L, "one");       // vault label 0
            gA1 = preserveAfterPut(vault, a, 2L, "two");       // vault label 1
        }
        // A different store whose backup numbering restarts low (the rollback picture): its first
        // backup reuses a number the vault already holds. The old scheme threw "already preserved".
        long gB;
        try (SmokeHouse<Long, String> b = SmokeHouse.open(storeB, opts())) {
            b.put(3L, "three");
            gB = vault.preserve(b);                            // must NOT brick
        }
        assertTrue(gA0 < gA1 && gA1 < gB, "vault labels strictly increase in preserve order: "
                + gA0 + " < " + gA1 + " < " + gB);
        assertEquals(List.of(gA0, gA1, gB), vault.generations(), "all three coexist");

        // Retention ages by preservation order — keep-newest-1 keeps the LAST preserved (gB),
        // never whichever carries the highest store-issued number.
        assertEquals(List.of(gA0, gA1), vault.retainNewest(1), "the two oldest age out");
        assertEquals(List.of(gB), vault.generations());
        try (DryAge.AgedView<Long, String> v = vault.asOf(gB)) {
            assertEquals("three", v.store().get(3L), "the survivor is the actually-newest data");
        }
    }

    private static long preserveAfterPut(DryAge<Long, String> vault, SmokeHouse<Long, String> s,
                                         long key, String value) throws IOException {
        s.put(key, value);
        return vault.preserve(s);
    }

    @Test
    void aScanCarryingGenerationHoldsItsRunFromBirth(@TempDir Path storeDir,
                                                     @TempDir Path vaultDir) throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            DryAge<Long, String> vault = DryAge.vault(vaultDir, opts());
            store.put(1L, "one");
            store.put(2L, "two");
            store.put(3L, "three");
            store.delete(2L);
            long g = vault.preserve(store, true);              // carry the sorted run

            // The run is inside the generation, and reads back as the live set in key order.
            Path run = vault.generationPath(g).resolve(DryAge.SCAN_RUN);
            assertTrue(java.nio.file.Files.isRegularFile(run), "the generation holds its run");
            TreeMap<Long, String> scanned = new TreeMap<>();
            assertEquals(2, SmokeHouse.scanSorted(run, opts(), scanned::put));
            assertEquals(new TreeMap<>(java.util.Map.of(1L, "one", 3L, "three")), scanned);

            // Recovery is indifferent to the sidecar: asOf still answers identically.
            try (DryAge.AgedView<Long, String> view = vault.asOf(g)) {
                assertEquals(2, view.store().size(), "the sidecar is invisible to recovery");
                assertEquals("one", view.store().get(1L));
            }

            // A plain preserve carries no run — the cost is opt-in.
            store.put(4L, "four");
            long plain = vault.preserve(store);
            assertTrue(!java.nio.file.Files.exists(
                            vault.generationPath(plain).resolve(DryAge.SCAN_RUN)),
                    "no sidecar unless asked");
        }
    }

    @Test
    void generationPathHandsOutThePreservedBytesReadOnly(@TempDir Path storeDir,
                                                         @TempDir Path vaultDir)
            throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            DryAge<Long, String> vault = DryAge.vault(vaultDir, opts());
            store.put(1L, "one");
            store.put(2L, "two");
            long g = vault.preserve(store);

            // The path is the preserved generation's own directory, populated with the backup.
            Path home = vault.generationPath(g);
            assertTrue(java.nio.file.Files.isDirectory(home), "the generation home exists");
            try (var listing = java.nio.file.Files.list(home)) {
                assertTrue(listing.findAny().isPresent(), "and holds the preserved bytes");
            }

            // Reading through it never disturbs the vault: asOf still answers identically.
            try (DryAge.AgedView<Long, String> view = vault.asOf(g)) {
                assertEquals(2, view.store().size(), "the vault is undisturbed by the path read");
            }

            // Unknown generations fail loudly, exactly like asOf.
            assertThrows(IllegalArgumentException.class, () -> vault.generationPath(g + 999));
        }
    }

    @Test
    void unknownGenerationsAndReleaseFailAndWorkLoudly(@TempDir Path storeDir,
                                                       @TempDir Path vaultDir)
            throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(storeDir, opts())) {
            DryAge<Long, String> vault = DryAge.vault(vaultDir, opts());
            store.put(1L, "one");
            long g = vault.preserve(store);
            assertThrows(IllegalArgumentException.class, () -> vault.asOf(g + 999));
            vault.release(g);
            assertTrue(vault.generations().isEmpty(), "released history is gone");
            assertThrows(IllegalArgumentException.class, () -> vault.asOf(g));
        }
    }
}
