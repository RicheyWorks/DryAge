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

    private static long preserveAfterPut(DryAge<Long, String> vault, SmokeHouse<Long, String> s,
                                         long key, String value) throws IOException {
        s.put(key, value);
        return vault.preserve(s);
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
