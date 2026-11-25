package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.stock.StockIndex;

import java.util.List;
import java.util.Optional;

/**
 * The seam between the build engine and whoever hands out work and arbitrates the base's shared resources.
 * {@link LocalCoordination} is the plugin's one implementation: the local queue, Discord intake on, every claim granted
 * at once, the stock in stock.json, reports dropped (the job file is the record). Tests drive the engine through a
 * scripted implementation (units granted and taken back, claims that wait), which is why the engine works on units,
 * claims and reports rather than on the store directly.
 *
 * <p>Threading: the engine calls every method on the client tick thread, and each returns at once. Nothing may block
 * there: claims and kv calls answer through handles ({@link Claim}, {@link Pending}) that the engine polls on later
 * ticks. {@link #jobs()} and {@link #job} may also be called from the executor (the checkpoint restore), so they must
 * be thread-safe.
 *
 * <p>Resuming a tile: INSPECT takes a platform's partial build of the tile (90 % matching) as this tile's only when it
 * may be a resume. The bot knows that from memory (the tile ACTIVE on that platform, or its own checkpoint), or from a
 * unit that is {@link Unit#resumable()}: attempt > 1, or the previous holders' progress ({@link Unit#handedOver()})
 * showing a start. A first unit (attempt 1, nothing handed over) treats a build with any block not the tile's as
 * foreign and nukes it; a build whose every block is the tile's own is resumed regardless.
 */
public interface Coordination {

    // =====================================================================================
    // Job / unit intake
    // =====================================================================================

    /** Whether the Discord intake (uploads to the bot's channel) is on. Local: true. */
    boolean discordIntake();

    /**
     * The jobs the engine picks tiles from, in queue order, terminal ones included (local: the JobStore's queue).
     */
    List<Job> jobs();

    /** A job of {@link #jobs()} by id (the live instance), e.g. to see that it was cancelled. */
    Optional<Job> job(String id);

    /**
     * The unit for tile {@code tile} of {@code job} while the engine may start or go on with it, else null. Local:
     * always a unit (the local queue owns every tile).
     */
    @Nullable Unit unit(Job job, Job.TileState tile);

    /**
     * Polled every tick, also while paused or BLOCKED: a unit the engine must stop now (revoked, lease lost), once per
     * event, or null. Local: always null.
     */
    @Nullable Abandon pollAbandon();

    // =====================================================================================
    // Claims: platforms, chests, stations
    // =====================================================================================

    /**
     * Asks for a lock on a world resource ({@link Claim}: {@code platform:<id>}, {@code chest:<x,y,z>},
     * {@code station:cartography}, {@code station:anvil}). Returns at once; the handle is polled. With {@code wait} a
     * held resource queues the request (first come, first served) instead of denying it. Local: granted at once.
     */
    Claim claim(String key, Claim.Mode mode, boolean wait);

    // =====================================================================================
    // Shared stock index (kv namespace "ch:mapart", entries "stock/<container>")
    // =====================================================================================

    /**
     * The stock index the engine reads (synchronously, a local view): the store's (stock.json). The engine's own
     * bookkeeping in it (discovery, near sweep times, scope) lives there too.
     */
    StockIndex stock();

    /** The version of {@code stock/<container>} as this bot last saw it; 0 = never written. */
    long stockVersion(Pos container);

    /**
     * Writes a container's snapshot, compare-and-set on {@code expectedVersion}: done with the new version, or the
     * error {@code cas_failed} when someone wrote it meanwhile ({@link SharedStock} retries while its reading is the
     * newer one). The view behind {@link #stock()} shows the snapshot at once. Local: StockIndex.update.
     */
    default Pending<Long> putStock(StockEntry entry, long expectedVersion) {
        return Pending.map(kvPut(StockEntry.key(entry.container()), entry.toJson(), expectedVersion), KvEntry::version);
    }

    /** Reads a key of the plugin's kv namespace {@code ch:mapart} (the current value and version). */
    Pending<KvEntry> kvGet(String key);

    /**
     * Writes a key of {@code ch:mapart}: compare-and-set on {@code expectedVersion} (0 = must not exist; negative = no
     * check); a null value deletes. Done with the new entry, or an error ({@code cas_failed}, {@code limit}, ...).
     */
    Pending<KvEntry> kvPut(String key, @Nullable JsonElement value, long expectedVersion);

    // =====================================================================================
    // Reports
    // =====================================================================================

    /**
     * Progress of a held unit: {@code done} of {@code total} cells placed, plus the data a successor cannot read from the
     * world ({@link TileProgress}). The engine reports at every phase change, on the tick a fact changes (stored,
     * counted, ride pressed / settled), and at most every {@code UNIT_PROGRESS_MS} (5 s) while a phase runs and the
     * placed count changed. Local: dropped.
     */
    void progress(Unit unit, int done, int total, TileProgress data);

    /** The unit is finished (the map stored, counted, the platform cleared). Local: nothing (the job file says so). */
    void done(Unit unit, TileProgress result);

    /**
     * {@link #done}, the platform not cleared: a carry-over left the cells of tile {@code continuedBy}
     * ({@code <map>/<i>,<j>}) on it. Default and local:
     * {@link #done}.
     */
    default void done(Unit unit, TileProgress result, @Nullable String continuedBy) {
        done(unit, result);
    }

    /**
     * The unit failed: {@code retry} true sends it back to the pool (an attempt), false fails it for good (the tile is
     * unreadable). Local: nothing (the job file says so).
     */
    void fail(Unit unit, String reason, boolean retry, @Nullable String detail);

    /** Gives the unit back without fault (job cancelled here, player took over for good). Local: nothing. */
    void release(Unit unit, String reason);
}
