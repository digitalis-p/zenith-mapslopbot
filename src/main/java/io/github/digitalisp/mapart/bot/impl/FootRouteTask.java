package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.pathfinder.movement.MovementHelper;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

/**
 * {@code walkOnFoot}: to within {@code range} blocks (each horizontal axis, at most 1 up or down) of the target along
 * a {@link FootRoute}, in hops (a straight level run of at most {@link #HOP} cells, or one step up or down) the
 * pathfinder walks one after the other. Never an elytra flight;
 * no cell of the route and no planned path passes a tripwire (it would fire the machine it belongs to); the route
 * stays out of every no-go zone but the one the bot starts in, which this task does not know (its hops end in it). For the walk off the muncher's rail field after a lost
 * ride (one-wide lines of rails over open air).
 */
final class FootRouteTask extends ZenithTask {
    /** Cells per hop at most (a hop is one straight level run, or one step up or down). */
    static final int HOP = 8;
    /** Cells the route search looks at, at most. */
    static final int MAX_NODES = 120_000;

    private final Pos target;
    private final int range;
    private List<Pos> route;
    private int next;
    private GoalNearXZ hop;
    /** Legs walked to the cell nearest the target when the search found no way within the loaded chunks, at most. */
    static final int MAX_LEGS = 6;
    /** A leg must bring the bot at least this many steps nearer the target ({@link FootRoute#legEnd}). */
    static final int LEG_GAIN = 16;
    /** Opened cells the steps to the target are counted through, at most. */
    static final int OPENED_CELLS = 200_000;
    /** Ticks the bot stands at a leg's end before the next search (the chunks beyond arrive). */
    static final int LEG_SETTLE_TICKS = 10;
    /** With no way and no leg the search is repeated this often, this many ticks apart, before the task fails. */
    static final int SEARCH_WAITS = 2;
    static final int SEARCH_WAIT_TICKS = 40;
    private int legs;
    private int waits;
    private long searchAt;
    /** The route in hand ends short of the target (a leg): a new search follows it. */
    private boolean partial;

    FootRouteTask(ZenithBotActions ctx, Pos target, int range) {
        super(ctx, 20L * 60 * 20, outOfTheZoneAtTheFeet(ctx.noGoZones()));
        this.target = target;
        this.range = Math.max(0, range);
        walker.pathCheck(cells -> {
            for (Pos p : cells) if (tripwire(p)) return "passes the tripwire at " + p.key();
            return null;
        });
    }

    /**
     * The task's zones: all but the configured zone the bot stands in now. The route leads out of that zone, its hops
     * end inside it (a walk refuses a goal in a zone), and every cell of it is one the route search chose.
     */
    private static NoGoZones outOfTheZoneAtTheFeet(NoGoZones all) {
        NoGoZones.Zone here = all.isEmpty() ? null : all.at(Walker.feet());
        return here == null ? all : all.without(here.label());
    }

    @Override
    protected boolean flightAllowed() {
        return false;
    }

    @Override
    public String name() {
        return "walkOnFoot " + target + " r=" + range;
    }

    private boolean tripwire(Pos feet) {
        return ctx.world().block(feet).id().endsWith("tripwire") || ctx.world().block(feet.above()).id().endsWith("tripwire");
    }

    private boolean arrivedAt(Pos c) {
        return Math.abs(c.x() - target.x()) <= range && Math.abs(c.z() - target.z()) <= range
            && Math.abs(c.y() - target.y()) <= 1;
    }

    @Override
    protected TaskStatus step() {
        if (route == null) {
            if (now < searchAt) {
                holdStill();
                return TaskStatus.RUNNING;
            }
            Pos feet = Walker.feet();
            NoGoZones.Zone here = zones.isEmpty() ? null : zones.at(feet);
            FootRoute.Cells cells = new FootRoute.Cells() {
                @Override
                public boolean stand(Pos c) {
                    return MovementHelper.canWalkThrough(c.x(), c.y(), c.z())
                        && MovementHelper.canWalkThrough(c.x(), c.y() + 1, c.z())
                        && MovementHelper.canWalkOn(c.x(), c.y() - 1, c.z());
                }

                @Override
                public boolean pass(Pos c) {
                    return MovementHelper.canWalkThrough(c.x(), c.y(), c.z());
                }
            };
            java.util.Set<Pos> reached = new java.util.HashSet<>();
            int[] refusals = new int[3];
            java.util.function.Predicate<Pos> refused = c -> {
                if (!ctx.world().isLoaded(c)) {
                    refusals[0]++;
                    return true;
                }
                if (tripwire(c)) {
                    refusals[1]++;
                    return true;
                }
                NoGoZones.Zone z = zones.isEmpty() ? null : zones.at(c);
                if (z != null && !z.sameZone(here)) {
                    refusals[2]++;
                    return true;
                }
                return false;
            };
            partial = false;
            route = FootRoute.find(cells, feet, this::arrivedAt, refused, MAX_NODES, reached);
            if (route == null) {
                int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = x0, y1 = x1, z0 = x0, z1 = x1;
                Pos west = feet;
                for (Pos c : reached) {
                    x0 = Math.min(x0, c.x());
                    x1 = Math.max(x1, c.x());
                    y0 = Math.min(y0, c.y());
                    y1 = Math.max(y1, c.y());
                    z0 = Math.min(z0, c.z());
                    z1 = Math.max(z1, c.z());
                    if (c.x() < west.x()) west = c;
                }
                LOG.warn("Foot route: the search came to {} cells, {} to {} (relative to the feet: x {}..{}, y {}..{}, z {}..{});"
                        + " refused: {} not loaded, {} tripwire, {} other zone; the westmost cell {}", reached.size(),
                    new Pos(x0, y0, z0).key(), new Pos(x1, y1, z1).key(), x0 - feet.x(), x1 - feet.x(), y0 - feet.y(),
                    y1 - feet.y(), z0 - feet.z(), z1 - feet.z(), refusals[0], refusals[1], refusals[2], west.key());
                // the cells between the nearest cell reached and the target, as the pathfinder sees them
                Pos near = feet;
                long best = Long.MAX_VALUE;
                for (Pos c : reached) {
                    long dx = c.x() - target.x(), dz = c.z() - target.z();
                    if (dx * dx + dz * dz < best) {
                        best = dx * dx + dz * dz;
                        near = c;
                    }
                }
                StringBuilder sb = new StringBuilder();
                int ax = Math.min(near.x(), target.x()) - 1, bx = Math.max(near.x(), target.x()) + 1;
                int az = Math.min(near.z(), target.z()) - 1, bz = Math.max(near.z(), target.z()) + 1;
                for (int y = Math.max(near.y(), target.y()) + 2; y >= Math.min(near.y(), target.y()) - 1; y--)
                    for (int z = az; z <= bz; z++)
                        for (int x = ax; x <= bx; x++) {
                            Pos c = new Pos(x, y, z);
                            String id = ctx.world().block(c).id().replace("minecraft:", "");
                            if (id.equals("air")) continue;
                            NoGoZones.Zone zn = zones.isEmpty() ? null : zones.at(c);
                            sb.append(String.format(" (%+d,%+d,%+d %s%s%s%s)", x - target.x(), y - target.y(), z - target.z(), id,
                                MovementHelper.canWalkThrough(x, y, z) ? " thru" : "", MovementHelper.canWalkOn(x, y, z) ? " on" : "",
                                zn == null ? "" : zn.sameZone(here) ? "" : " ZONE"));
                        }
                LOG.warn("Foot route: nearest cell reached {} (target-relative {},{},{}); blocks between, relative to the target:{}",
                    near.key(), near.x() - target.x(), near.y() - target.y(), near.z() - target.z(), sb);
                // the way may lead on through chunks the client does not have yet (a way round a field: local 2026-11-15,
                // from the north-west stair the far row's east end was out of view, "no route"): a leg to the reached
                // cell with the fewest steps left through the site's opened cells (the boxes of the way stand for it
                // where nothing is loaded; a straight line would lead east along the walkway), then a new search
                List<io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg> openings = cfg.site.openings;
                java.util.function.Predicate<Pos> opened = p ->
                    io.github.digitalisp.mapart.config.SiteRules.regionAt(openings, p.x(), p.y(), p.z()) != null;
                java.util.List<Pos> seeds = new java.util.ArrayList<>();
                for (int dx = -range; dx <= range; dx++)
                    for (int dz = -range; dz <= range; dz++)
                        for (int dy = -1; dy <= 1; dy++) seeds.add(target.offset(dx, dy, dz));
                java.util.Set<Pos> wanted = new java.util.HashSet<>();
                for (Pos c : reached) if (opened.test(c)) wanted.add(c);
                java.util.Map<Pos, Integer> steps = FootRoute.openedSteps(opened, seeds, wanted, OPENED_CELLS);
                Pos legEnd = legs < MAX_LEGS ? FootRoute.legEnd(feet, target, reached, steps, LEG_GAIN) : null;
                if (legEnd != null && !legEnd.equals(feet)) {
                    route = FootRoute.find(cells, feet, legEnd::equals, refused, MAX_NODES);
                    if (route != null) {
                        legs++;
                        partial = true;
                        Integer left = steps.get(legEnd), from = steps.get(feet);
                        int held = 0, span = 0;
                        for (int cx = -16; cx <= 16; cx++)
                            for (int cz = -16; cz <= 16; cz++)
                                if (ctx.world().isLoaded(new Pos(feet.x() + cx * 16, feet.y(), feet.z() + cz * 16))) {
                                    held++;
                                    span = Math.max(span, Math.max(Math.abs(cx), Math.abs(cz)));
                                }
                        LOG.info("Foot route: no way to {} in the loaded chunks; leg {} to {} ({}), then a new search;"
                            + " the client holds {} chunks, up to {} from the feet", target.key(), legs, legEnd.key(),
                            left != null && from != null
                                ? left + " steps left through the opened cells, " + from + " from here"
                                : "the cell reached nearest it in a straight line", held, span);
                    }
                }
                if (route == null && waits < SEARCH_WAITS) {
                    // right after a login or a leg the chunks may still be on their way: look again shortly
                    waits++;
                    searchAt = now + SEARCH_WAIT_TICKS;
                    LOG.info("Foot route: no way and no leg from {}; looking again in {} ticks ({} of {})", feet.key(),
                        SEARCH_WAIT_TICKS, waits, SEARCH_WAITS);
                    holdStill();
                    return TaskStatus.RUNNING;
                }
            }
            if (route == null) {
                // why: what the search sees at the feet and around them
                StringBuilder why = new StringBuilder();
                for (int[] d : new int[][]{{0, 0}, {0, -1}, {0, 1}, {-1, 0}, {1, 0}}) {
                    Pos c = new Pos(feet.x() + d[0], feet.y(), feet.z() + d[1]);
                    NoGoZones.Zone z = zones.isEmpty() ? null : zones.at(c);
                    why.append(String.format(" [%+d,%+d: %s through=%b head=%b on(%s)=%b loaded=%b zone=%s]", d[0], d[1],
                        ctx.world().block(c).id(), MovementHelper.canWalkThrough(c.x(), c.y(), c.z()),
                        MovementHelper.canWalkThrough(c.x(), c.y() + 1, c.z()), ctx.world().block(c.below()).id(),
                        MovementHelper.canWalkOn(c.x(), c.y() - 1, c.z()), ctx.world().isLoaded(c),
                        z == null ? "none" : z.sameZone(here) ? "same" : "other"));
                }
                LOG.warn("Foot route: none from {}; around the feet:{}", feet.key(), why);
            }
            if (route == null)
                return fail("no route on foot (level steps, one up, at most " + FootRoute.MAX_DROP + " down, no tripwire) from "
                    + feet.key() + " to within " + range + " of " + target.key());
            int ups = 0, downs = 0;
            for (int i = 1; i < route.size(); i++) {
                int dy = route.get(i).y() - route.get(i - 1).y();
                if (dy > 0) ups++;
                if (dy < 0) downs++;
            }
            LOG.info("Foot route: {} cells from {} to {} ({} steps up, {} down), walked in hops of {}", route.size(),
                feet.key(), route.getLast().key(), ups, downs, HOP);
            next = 0;
            hop = null;
        }
        if (hop == null) {
            if (next >= route.size() - 1) {
                if (!partial) return TaskStatus.SUCCESS;
                route = null; // a leg's end: the chunks beyond come into view, search again from here
                searchAt = now + LEG_SETTLE_TICKS;
                walker.stop();
                return TaskStatus.RUNNING;
            }
            next = FootRoute.nextWaypoint(route, next, HOP);
            Pos wp = route.get(next);
            hop = new GoalNearXZ(wp.x(), wp.y(), wp.z(), 0, 0);
        }
        GoalNearXZ goal = hop;
        TaskStatus s = walkRaw(List.of(goal), () -> feetIn(goal), 2);
        if (s == TaskStatus.FAILED)
            return fail("hop to " + route.get(next).key() + " (cell " + next + " of " + (route.size() - 1) + "): " + walker.error());
        if (s == TaskStatus.SUCCESS) hop = null;
        return TaskStatus.RUNNING;
    }
}
