package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.player.ClickResult;
import com.zenith.feature.player.InputRequestFuture;
import com.zenith.feature.player.raycast.RaycastHelper;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.MapHold;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.zenith.Globals.BOT;

/**
 * createMap / holdMap: stand on {@code standAt}; for createMap use an empty map ("minecraft:map") while looking
 * where no block is within reach (so the click is a plain use-item, as a vanilla client would send it) and detect
 * the new filled_map by its map-id component; then keep the filled map in the main hand for the hold time, re-
 * submitting a still look/hold input every tick so nothing else (AntiAFK-style movement) moves the bot. With
 * {@link MapHold} options: a near stand walked to first (the station and its rings only if that walk fails), and a hold
 * that ends once the map shows the art (after {@link MapHold#minTicks}).
 */
final class MapTask extends ZenithTask {
    private static final String EMPTY_MAP = "minecraft:map";
    private static final int USE_WAIT = 60;
    private static final int MAX_USES = 3;
    /**
     * The station itself, then anywhere within these ranges of it (blocks): a map fills from anywhere on its tile, the
     * server updating its pixels up to 126 blocks around the holder, so 24 blocks off the centre still covers every
     * corner. Live 2026-10-13, remu [2,2]: the soul sand station (the floor under the button cross) lay inside a
     * pointed dripstone field the pathfinder never crosses; FILL_MAP went BLOCKED after 6 attempts.
     */
    static final int[] STAND_RANGES = {0, 8, 16, 24};
    /**
     * Then the floor cells inside the tile the bot can walk to (a flood over the art layer from its feet), nearest the
     * station first, this many of them: a new map centres on the 128 x 128 cell the bot stands in, so never one outside
     * the tile (live 2026-10-13, remu [2,2]: nothing within 24 blocks of the station was reachable).
     */
    static final int REACHABLE_TRIES = 3;
    /** The flood's budget (cells) and its reach beyond the tile (the bot may start on the walkway outside it). */
    static final int FLOOD_MAX = 40_000;
    static final int FLOOD_MARGIN = 32;

    private final Pos standAt;
    private final int holdTicks;
    private final @Nullable Consumer<Integer> created;
    /** The near stand and the early end of the hold ({@link MapHold}); {@link MapHold#PLAIN} without options. */
    private final MapHold opts;
    /** The near stand still to try first; null once tried (or none): the station and its rings as before. */
    private @Nullable Pos near;
    private @Nullable Integer mapId;
    private boolean atStand;
    private @Nullable Set<Integer> knownIds;
    private @Nullable InputRequestFuture useFuture;
    private long usedAt = -1;
    private int uses;
    private int held;
    private float lookYaw;
    private float lookPitch;
    private boolean lookChosen;
    private int rangeIdx;
    private @Nullable List<Pos> reachable;
    private int reachableIdx;

    private MapTask(ZenithBotActions ctx, Pos standAt, int holdTicks, @Nullable Integer mapId, @Nullable Consumer<Integer> created,
                    MapHold opts) {
        super(ctx, WalkTask.walkTimeout(standAt) + 1200L + Math.max(0, holdTicks)
            + (opts.nearStand() != null ? WalkTask.walkTimeout(opts.nearStand()) : 0));
        this.standAt = standAt;
        this.holdTicks = Math.max(0, holdTicks);
        this.mapId = mapId;
        this.created = created;
        this.opts = opts;
        this.near = opts.nearStand() != null && !opts.nearStand().equals(standAt) ? opts.nearStand() : null;
        int known = ctx.mapStandRange.getOrDefault(standAt, 0);
        while (rangeIdx + 1 < STAND_RANGES.length && STAND_RANGES[rangeIdx] < known) rangeIdx++;
    }

    /**
     * Floor cells of the station's tile the bot can walk to from its feet over the art layer ({@link #artWalkGrid}),
     * nearest the station first, at most {@link #REACHABLE_TRIES}; none when the station lies on no platform.
     */
    private List<Pos> reachableNear() {
        io.github.digitalisp.mapart.model.PlatformGeometry geo = null;
        for (var pc : cfg.site.platforms) {
            if (pc.isColorSuppression()) continue;
            var g = pc.geometry();
            if (g.contains(standAt.x(), standAt.z())) geo = g;
        }
        if (geo == null) return List.of();
        BreakIn.Grid grid = artWalkGrid();
        Pos start = Walker.feet();
        int minX = geo.minX() - FLOOD_MARGIN, maxX = geo.minX() + 127 + FLOOD_MARGIN;
        int minZ = geo.minZ() - FLOOD_MARGIN, maxZ = geo.minZ() + 127 + FLOOD_MARGIN;
        Set<Pos> seen = new HashSet<>();
        java.util.ArrayDeque<Pos> queue = new java.util.ArrayDeque<>();
        List<Pos> inside = new java.util.ArrayList<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty() && seen.size() < FLOOD_MAX) {
            Pos c = queue.poll();
            if (c.y() == geo.artY() && geo.contains(c.x(), c.z()) && grid.floor(c)) inside.add(c);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (c.x() + d[0] < minX || c.x() + d[0] > maxX || c.z() + d[1] < minZ || c.z() + d[1] > maxZ) continue;
                // the same level first, then a step up or down (the storage walkway is a block above the art layer)
                for (int dy : new int[]{0, 1, -1}) {
                    Pos n = new Pos(c.x() + d[0], c.y() + dy, c.z() + d[1]);
                    if (!grid.standAllowed(n) || !grid.passable(n)) continue;
                    if (seen.add(n)) queue.add(n);
                    break;
                }
            }
        }
        inside.sort(java.util.Comparator.comparingDouble(c -> Math.hypot(c.x() - standAt.x(), c.z() - standAt.z())));
        List<Pos> out = new java.util.ArrayList<>(inside.subList(0, Math.min(REACHABLE_TRIES, inside.size())));
        // last: where the bot stands, if that is on the tile (the flood's floor rules are not Baritone's: live
        // 2026-10-13, remu [2,2], none of the 3 flood cells could be walked to while the bot stood on the tile)
        if (geo.contains(start.x(), start.z()) && !out.contains(start)) {
            double far = 0;
            for (int[] k : new int[][]{{0, 0}, {127, 0}, {0, 127}, {127, 127}})
                far = Math.max(far, Math.hypot(geo.minX() + k[0] - start.x(), geo.minZ() + k[1] - start.z()));
            LOG.info("map station fallback: the bot's own cell {} on the tile, farthest corner {} blocks away (the server"
                + " updates map pixels up to about 126 blocks around the holder)", start, Math.round(far));
            out.add(start);
        }
        return List.copyOf(out);
    }

    /** Never through the art: the map would show the broken blocks (it fills after VERIFY). */
    @Override
    protected boolean recoverArtWalks() {
        return false;
    }

    static MapTask create(ZenithBotActions ctx, Pos standAt, int holdTicks, Consumer<Integer> mapId) {
        return create(ctx, standAt, holdTicks, mapId, MapHold.PLAIN);
    }

    static MapTask create(ZenithBotActions ctx, Pos standAt, int holdTicks, Consumer<Integer> mapId, MapHold opts) {
        return new MapTask(ctx, standAt, holdTicks, null, mapId, opts);
    }

    static MapTask hold(ZenithBotActions ctx, Pos standAt, int mapId, int ticks) {
        return hold(ctx, standAt, mapId, ticks, MapHold.PLAIN);
    }

    static MapTask hold(ZenithBotActions ctx, Pos standAt, int mapId, int ticks, MapHold opts) {
        return new MapTask(ctx, standAt, ticks, mapId, null, opts);
    }

    /** {@code BotActions.holdMapExactly}: into the cell {@code standAt} itself or FAILED; the map in hand only there. */
    static MapTask holdExactly(ZenithBotActions ctx, Pos standAt, int mapId, int ticks) {
        MapTask t = new MapTask(ctx, standAt, ticks, mapId, null, MapHold.PLAIN);
        t.exact = true;
        return t;
    }

    /** No ring and no other cell: the stand's own block column or nothing ({@link #holdExactly}). */
    private boolean exact;

    @Override
    public String name() {
        return created != null ? "createMap at " + standAt : "holdMap " + mapId + " at " + standAt;
    }

    @Override
    protected String progress() {
        return " (map " + mapId + ", held " + held + "/" + holdTicks + ")";
    }

    /** The walk to the map station; the map's use waits for the quiet window. */
    @Override
    protected SprintLegs.Walk walkKind() {
        return SprintLegs.Walk.MAP_STATION;
    }

    @Override
    protected TaskStatus step() {
        if (!atStand && exact) {
            TaskStatus s = walkNear(standAt, 0, 1);
            if (s == TaskStatus.RUNNING) return s;
            if (s == TaskStatus.FAILED) return fail("walking onto " + standAt + ": " + walker.error());
            atStand = true;
        }
        if (!atStand && near != null) {
            // the near stand (map.fillNearStand): exactly there, else the station as before
            TaskStatus s = walkNear(near, 0, 1);
            if (s == TaskStatus.RUNNING) return s;
            if (s == TaskStatus.FAILED) {
                LOG.info("map near stand {} out of reach ({}); filling at the station {} instead", near, walker.error(),
                    standAt);
                near = null;
                walker.stop();
                return TaskStatus.RUNNING;
            }
            LOG.info("filling the map at the near stand {} ({} blocks from the station {})", near,
                Math.round(Math.hypot(near.x() - standAt.x(), near.z() - standAt.z())), standAt);
            atStand = true;
        }
        if (!atStand) {
            if (reachable != null) {
                if (reachableIdx >= reachable.size()) return fail("walking to " + standAt + " or a reachable cell of its"
                    + " tile near it: " + walker.error());
                Pos c = reachable.get(reachableIdx);
                TaskStatus s = walkNear(c, 0, 1);
                if (s == TaskStatus.FAILED) {
                    reachableIdx++;
                    walker.stop();
                    return TaskStatus.RUNNING;
                }
                if (s == TaskStatus.RUNNING) return s;
                LOG.info("map filled from {} ({} blocks from the station {})", c,
                    Math.round(Math.hypot(c.x() - standAt.x(), c.z() - standAt.z())), standAt);
                atStand = true;
            } else {
                int range = STAND_RANGES[rangeIdx];
                TaskStatus s = walkNear(standAt, range, range == 0 ? 1 : 2);
                if (s == TaskStatus.FAILED && rangeIdx + 1 < STAND_RANGES.length) {
                    rangeIdx++;
                    LOG.info("map station {} out of reach ({}); the map is filled from within {} blocks of it", standAt,
                        walker.error(), STAND_RANGES[rangeIdx]);
                    walker.stop();
                    return TaskStatus.RUNNING;
                }
                if (s == TaskStatus.FAILED) {
                    reachable = reachableNear();
                    LOG.info("map station {} and {} blocks around it out of reach; {} reachable cells of the tile next",
                        standAt, STAND_RANGES[rangeIdx], reachable.size());
                    walker.stop();
                    return TaskStatus.RUNNING;
                }
                if (s == TaskStatus.RUNNING) return s;
                atStand = true;
                if (range > 0) ctx.mapStandRange.put(standAt, range);
            }
        }
        if (!lookChosen) chooseLook();
        if (mapId == null) return createStep();
        return holdStep();
    }

    private TaskStatus createStep() {
        if (knownIds == null) knownIds = new HashSet<>(Inv.mapIds());
        for (Integer id : Inv.mapIds()) {
            if (!knownIds.contains(id)) {
                mapId = id;
                if (created != null) created.accept(id);
                return holdStep();
            }
        }
        if (useFuture != null && usedAt >= 0) {
            if (now - usedAt < USE_WAIT) {
                clicker.submit(now, lookYaw, lookPitch, null);
                return TaskStatus.RUNNING;
            }
            useFuture = null;
            usedAt = -1;
        }
        if (uses >= MAX_USES) return fail("no new filled map after " + uses + " uses");
        if (Inv.count(Inv.is(EMPTY_MAP)) == 0) return fail("no empty map (" + EMPTY_MAP + ") in inventory");
        if (!hotbar.ensureHeld(Inv.is(EMPTY_MAP), Hotbar.SPECIAL_SLOT, now)) {
            clicker.submit(now, lookYaw, lookPitch, null);
            return TaskStatus.RUNNING;
        }
        if (useFuture == null || useFuture.isCompleted()) {
            if (useFuture != null && executed(useFuture)) {
                usedAt = now;
                uses++;
                clicker.submit(now, lookYaw, lookPitch, null);
                return TaskStatus.RUNNING;
            }
            if (clicker.ownedLastTick(now)) {
                useFuture = clicker.submitUseItem(now, lookYaw, lookPitch);
            } else {
                clicker.submit(now, lookYaw, lookPitch, null);
            }
            return TaskStatus.RUNNING;
        }
        clicker.submit(now, lookYaw, lookPitch, null);
        return TaskStatus.RUNNING;
    }

    private static boolean executed(InputRequestFuture f) {
        return f.getNow() && f.getClickResult() instanceof ClickResult.RightClickResult r
            && r.getType() == ClickResult.RightClickResult.RightClickType.USE_ITEM;
    }

    private TaskStatus holdStep() {
        int id = mapId;
        if (Inv.count(Inv.isMap(id)) == 0) return fail("filled map " + id + " not in inventory");
        boolean holding = hotbar.ensureHeld(Inv.isMap(id), Hotbar.SPECIAL_SLOT, now);
        clicker.submit(now, lookYaw, lookPitch, null);
        if (holding && clicker.ownedLastTick(now)) held++;
        if (holding && opts.done(held, holdTicks, id)) {
            if (held < holdTicks) LOG.info("map {} shows the art after {} of {} held ticks: hold ended", id, held, holdTicks);
            return TaskStatus.SUCCESS;
        }
        if (held >= holdTicks) return TaskStatus.SUCCESS;
        return TaskStatus.RUNNING;
    }

    /** A look direction with no block within reach (+1): horizon first, then upward. */
    private void chooseLook() {
        lookChosen = true;
        float yaw = BOT.getYaw();
        for (float pitch : List.of(0f, -30f, -60f, -90f)) {
            var ray = RaycastHelper.blockRaycastFromPos(BOT.getX(), BOT.getEyeY(), BOT.getZ(), yaw, pitch, reach() + 1.0, false);
            if (!ray.hit()) {
                lookYaw = yaw;
                lookPitch = pitch;
                return;
            }
        }
        lookYaw = yaw;
        lookPitch = -90f;
    }
}
