package io.github.richeyworks.dryage;

import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * DryAge — engine eight of the ecosystem: the time-travel engine, where cuts age until
 * they're ready. The ecosystem's founding doctrine — <b>the log is the only truth, and its
 * bytes never change once written</b> — means every historical state still exists; DryAge
 * makes it readable. A vault holds CRC'd backup generations ({@link #preserve}), and
 * {@link #asOf} opens any generation as a full read-only SmokeHouse of the past — every
 * index tier, every read surface, order statistics included — without disturbing the vault
 * (each view runs on a scratch copy, deleted on close).
 *
 * <h2>Honest bounds, stated loudly</h2>
 * Time travel reaches exactly as far as the vault: a generation you never preserved is a
 * past you cannot revisit, and compaction/retention on the live store never affects the
 * vault (backups are prefix copies, CRC'd at capture). Coordinates are generations, not
 * timestamps — v1's granularity is "when you called preserve". Record-granularity as-of
 * (a bounded-recovery stop condition on {@code SmokeHouse.open}) is the named next seam,
 * to be cut upstream when a consumer shows the generation granularity isn't enough.
 */
public final class DryAge<K, V> {

    /** A readable past: a full SmokeHouse on a scratch copy; close deletes the scratch. */
    public static final class AgedView<K, V> implements Closeable {
        private final SmokeHouse<K, V> store;
        private final Path scratch;

        private AgedView(SmokeHouse<K, V> store, Path scratch) {
            this.store = store;
            this.scratch = scratch;
        }

        /** The past, readable. Do not write to it — it's history. */
        public SmokeHouse<K, V> store() {
            return store;
        }

        @Override
        public void close() throws IOException {
            store.close();
            deleteRecursively(scratch);
        }
    }

    private static final String GEN_PREFIX = "gen-";

    private final Path vaultDir;
    private final SmokeHouseOptions<K, V> opts;

    private DryAge(Path vaultDir, SmokeHouseOptions<K, V> opts) {
        this.vaultDir = vaultDir;
        this.opts = opts;
    }

    /** Open (or create) a vault at {@code vaultDir}. */
    public static <K, V> DryAge<K, V> vault(Path vaultDir, SmokeHouseOptions<K, V> opts)
            throws IOException {
        Objects.requireNonNull(vaultDir, "vaultDir");
        Objects.requireNonNull(opts, "opts");
        Files.createDirectories(vaultDir);
        return new DryAge<>(vaultDir, opts);
    }

    /**
     * Preserve the store's current state as a new generation (a {@code SmokeHouse.backup}
     * under the store lock — segments + manifest, CRC'd). Returns the generation number.
     */
    public synchronized long preserve(SmokeHouse<K, V> store) throws IOException {
        Objects.requireNonNull(store, "store");
        Path staging = Files.createTempDirectory(vaultDir, "staging-");
        long generation = store.backup(staging);
        Path home = vaultDir.resolve(GEN_PREFIX + generation);
        if (Files.exists(home)) {
            deleteRecursively(staging);
            throw new IOException("generation " + generation + " already preserved");
        }
        Files.move(staging, home, StandardCopyOption.ATOMIC_MOVE);
        return generation;
    }

    /** Every preserved generation, ascending — the vault's timeline. */
    public synchronized List<Long> generations() throws IOException {
        List<Long> out = new ArrayList<>();
        try (var listing = Files.list(vaultDir)) {
            for (Path p : listing.toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith(GEN_PREFIX)) {
                    out.add(Long.parseLong(name.substring(GEN_PREFIX.length())));
                }
            }
        }
        out.sort(null);
        return out;
    }

    /**
     * Open generation {@code generation} as a readable past. The vault stays pristine: the
     * view recovers on a scratch copy (recovery may touch its directory; history may not be
     * touched), deleted when the view closes.
     */
    public synchronized AgedView<K, V> asOf(long generation) throws IOException {
        Path home = vaultDir.resolve(GEN_PREFIX + generation);
        if (!Files.isDirectory(home)) {
            throw new IllegalArgumentException("no generation " + generation
                    + " in the vault; preserved: " + generations());
        }
        Path scratch = Files.createTempDirectory("dryage-view");
        try (var listing = Files.list(home)) {
            for (Path f : listing.toList()) {
                Files.copy(f, scratch.resolve(f.getFileName().toString()),
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        return new AgedView<>(SmokeHouse.restore(scratch, opts), scratch);
    }

    /** Drop a generation from the vault — aging out old history is the caller's policy. */
    public synchronized void release(long generation) throws IOException {
        Path home = vaultDir.resolve(GEN_PREFIX + generation);
        if (Files.isDirectory(home)) {
            deleteRecursively(home);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
