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
