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
        return preserve(store, false);
    }

    /** The name of the sorted-run sidecar a scan-carrying generation holds. */
    public static final String SCAN_RUN = "scan.run";

    /**
     * Preserve, optionally carrying the sorted-run sidecar (ADR scan-sidecar, 2026-08-20):
     * with {@code withScanRun}, the store's {@code exportSorted} run is written into the
     * staging directory <b>before</b> the atomic move, so the generation holds its scan run
     * from birth — the vault's founding rule (history's bytes never change) is never bent by
     * a post-hoc write. Recovery is indifferent to the extra file (segments are
     * pattern-matched), so {@link #asOf} behaves identically either way; archival consumers
     * reach the run through {@link #generationPath} — or, once cured, through
     * {@code Jerky.extract(archive, DryAge.SCAN_RUN)}, which is the whole point: history,
     * scanned without resurrection. Opt-in because the export costs one ordered walk and the
     * run's bytes ride in the vault; a vault used only for time travel pays nothing.
     *
     * <p><b>Honest bound:</b> backup and export each hold the store's lock, but not across the
     * pair — a writer landing between them puts records in the run that the generation's
     * segments don't hold. Preserve from a quiesced writer (the ecosystem's single-writer
     * discipline makes this natural) when the run must equal the generation exactly.</p>
     */
    public synchronized long preserve(SmokeHouse<K, V> store, boolean withScanRun)
            throws IOException {
        Objects.requireNonNull(store, "store");
        Path staging = Files.createTempDirectory(vaultDir, "staging-");
        try {
            long generation = store.backup(staging);
            if (withScanRun) {
                store.exportSorted(staging.resolve(SCAN_RUN));
            }
            Path home = vaultDir.resolve(GEN_PREFIX + generation);
            if (Files.exists(home)) {
                throw new IOException("generation " + generation + " already preserved");
            }
            Files.move(staging, home, StandardCopyOption.ATOMIC_MOVE);
            return generation;
        } catch (IOException | RuntimeException failed) {
            // Ninth-pass finding 1 (2026-08-20): a failed backup or export used to LEAK its
            // staging directory inside the vault, forever. A failed preserve now leaves the
            // vault exactly as it found it.
            deleteRecursively(staging);
            throw failed;
        }
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

    /**
     * The preserved generation's directory, for <b>read-only</b> consumers (2026-08-19,
     * named by WholeHog): an archiver that only reads — {@code Jerky.cure} is the canonical
     * one — can take the preserved bytes directly, with no scratch copy, no recovery pass,
     * and no re-backup. The old route (open an {@link #asOf} view, back up the view's store,
     * cure the re-backup) copied history twice and recovered it once just to read bytes that
     * were already CRC'd at capture; integration showed the dance was pure ceremony.
     *
     * <p><b>The contract is the vault's founding rule:</b> history's bytes never change. The
     * returned path is handed out for reading; a consumer that writes into it is corrupting
     * the vault, and no code path in this class will save it. To read the generation <em>as a
     * store</em> (queries, order statistics), use {@link #asOf} — that is what the scratch
     * copy is for.</p>
     *
     * @throws IllegalArgumentException if the generation was never preserved
     */
    public synchronized Path generationPath(long generation) throws IOException {
        Path home = vaultDir.resolve(GEN_PREFIX + generation);
        if (!Files.isDirectory(home)) {
            throw new IllegalArgumentException("no generation " + generation
                    + " in the vault; preserved: " + generations());
        }
        return home;
    }

    /** Drop a generation from the vault — aging out old history is the caller's policy. */
    public synchronized void release(long generation) throws IOException {
        Path home = vaultDir.resolve(GEN_PREFIX + generation);
        if (Files.isDirectory(home)) {
            deleteRecursively(home);
        }
    }

    /**
     * The aging policy, as one call (2026-08-19): keep the newest {@code count} generations
     * and release everything older. Returns the released generations, ascending — an audit
     * line for the caller's log, because dropping history deserves a record. {@code count}
     * of zero empties the vault; a count at or above the vault's size releases nothing.
     * Caller-cadenced like every policy in the ring — the vault never ages on its own clock,
     * because it has none.
     *
     * @throws IllegalArgumentException if {@code count} is negative
     */
    public synchronized List<Long> retainNewest(int count) throws IOException {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0: " + count);
        }
        List<Long> timeline = generations();
        int drop = timeline.size() - count;
        if (drop <= 0) {
            return List.of();
        }
        List<Long> released = new ArrayList<>(timeline.subList(0, drop));
        for (long generation : released) {
            release(generation);
        }
        return List.copyOf(released);
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
