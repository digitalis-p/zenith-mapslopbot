package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.entity.Entity;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.mc.block.CollisionBox;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Predicate;

import static com.zenith.Globals.BLOCK_DATA;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INPUTS;

/**
 * One walker's elytra flight legs ({@code build.elytraLegs}). At a restock leg's
 * start the {@link Walker} asks {@link #start}: the gear rules ({@link FlightGear#refusal}) and the planner
 * ({@link FlightPlanner}) decide whether it flies. A flight then runs in the walker's ticks instead of Baritone: the
 * elytra on ({@link FlightGear#swap}, standing still), rockets in the main hand, then the {@link FlightControl} keys
 * through ZenithProxy's input manager at the task's priority (rotation, jump, the rocket's right click: the Bot's own
 * physics move the bot, as a vanilla client's would), and after the landing the chestplate back on when
 * {@link FlightGear#chestBack} says so. The walk goes on from where the bot stands. Client tick thread.
 *
 * <p>A walk stopped in the air (its task released, a fight) hands the flight to {@link #afterEngine}: the controller's
 * bail-out lands the bot (the soonest, slowest safe touch-down, no rockets) until its feet are on the ground. A run
 * into a block in the air logs one WARN line per tick with the blocks around ({@code hitLog}); a scrape of the
 * touch-down (landed, or the feet within 0.2 of the floor) one INFO line.
 */
final class FlightLeg {
    /** What the walker's task gives the flight. */
    interface Host {
        MapArtConfig.BuildCfg build();

        Clicker clicker();

        Hotbar hotbar();

        SprintLegs.Walk kind();

        boolean inRestock();

        /** This walk may fly at all (false: a retreat from mobs, which {@code walkTo} also walks). */
        default boolean mayFly() {
            return true;
        }
    }

    /** {@code CHECK}: an elytra already worn at the leg's start (not put on by this leg) is checked before the take-off. */
    enum Phase { IDLE, SETTLE, CHECK, GEAR_ON, HOLD, FLY, GEAR_OFF }

    /** The elytra the plugin put on (session): the chestplate's id it took off (null: the chest slot was empty). */
    static final class Worn {
        volatile boolean ours;
        @Nullable String chestId;
    }

    static final Worn WORN = new Worn();
    /** The flight a stopped walk left in the air ({@link #afterEngine}), and the owner of its inputs. */
    private static @Nullable FlightLeg orphan;
    private static final Object ORPHAN = new Object();
    private static long engineTicks;

    /** The rockets flights use: plain, duration 1 to 3 ({@link Inv#FLIGHT_ROCKET}); any other rocket stays where it is. */
    private static final Predicate<ItemStack> ROCKET = Inv.FLIGHT_ROCKET;
    /**
     * This leg's rocket duration: the rockets it fires (the one in the hand, else the first flight rocket carried);
     * only rockets of that duration go into the hand ({@link #legRocket}), and the controller plans with their boost.
     */
    private int legDuration = 1;
    /** The longest boost (bot ticks) this leg plans for ({@link FlightControl#worstBoost}). */
    private int legWorst;
    /** The leg flies with two rockets ({@link #withLanding}, {@link FlightControl#relay}). */
    private boolean legRelay;
    /** The server's TPS and the longest recent boost the leg's {@link #legWorst} came from (the take-off line). */
    private double legTps = Double.NaN;
    private int legRecent;
    /** The recent duration-2/3 boosts, the server's stretch as measured ({@link FlightControl#worstBoost}). */
    static final FlightControl.BoostWatch WATCH = new FlightControl.BoostWatch();
    private Predicate<ItemStack> legRocket = Inv.flightRocket(1);
    private static final Predicate<ItemStack> ELYTRA = Inv.is("elytra");
    /** Hotbar slots the rockets go into when they are in the main inventory. */
    private static final int[] ROCKET_SLOTS = {8, 7, 6};
    /** Ticks a gear swap or the rocket hold may take before the leg walks instead. */
    static final int GEAR_TICKS = 60;

    private final Host host;
    private Phase phase = Phase.IDLE;
    private long phaseStart;
    private @Nullable RequestFuture request;
    private long requestAt;
    private long lastTicked = -1;
    private @Nullable FlightControl ctl;
    private FlightPlanner.@Nullable Plan plan;
    /** The leg for its log line: kind, blocks, start tick, the flight's ticks and numbers, why it walked. */
    private boolean leg;
    private String legKind = "";
    private double legBlocks;
    private long legStart;
    private long flyStart = -1;
    private long flyEnd = -1;
    private long gearTicks;
    private int gearClicks;
    private @Nullable String walkWhy;
    private @Nullable String flightWhy;
    private int elytraBefore = -1;
    private int rocketsBefore;
    private int rocketsUsed;
    /** Flight ticks whose input request was not ours in the end (another module or request took the tick). */
    private int lostInputs;
    /** fly_into_wall damage the server dealt during the leg's flights ({@link DamageEvents#takeWall}). */
    private int wallHits;
    private boolean landedHere;
    /** Where the flight ended: blocks from R. */
    private double restOffR = Double.NaN;
    /** The leg's goal and terrain, for a further flight ({@code build.elytraHops}). */
    private @Nullable GoalNearXZ legGoal;
    private @Nullable NoGoZones legZones;
    private @Nullable BorderTop legBorder;
    /**
     * Further flights this leg planned ({@code build.elytraHops}), the flights it took off on, the last one's plan, and
     * the earlier flights' air ticks and blocks.
     */
    private int hops;
    private int flights;
    private FlightPlanner.@Nullable Plan flownPlan;
    private long airBefore;
    /**
     * The blocks the leg's flights covered: each from its take-off spot to its touch-down (to where it stopped without
     * one), not the plans' lengths (live j0016 2026-10-25: a take-off that ended at once and a second flight logged
     * "flown 228" of a 115-block leg); whether this flight is in it yet; its take-off spot; arc flights' way in the air.
     */
    private double flownActual;
    private boolean flightCounted = true;
    private double flyX0;
    private double flyZ0;
    private double arcAir;
    private int arcFlights;
    /** The tick this flight's gear and rockets began (the leg's start for the first flight). */
    private long hopStart;
    /** Rockets this flight fired (the boost watch counts one-rocket flights), and whether its boost went in. */
    private int hopRockets;
    private boolean boostCounted;
    /** Why no further flight went after a landing short of the goal ({@code build.elytraHops}), for the leg's line. */
    private @Nullable String hopWhy;
    /** The leg flies outside the restock ({@code build.elytraWalkLegs} and the others): its lines say so. */
    private boolean legOutside;
    /** Where the leg began (the blocks walked before an on-the-way take-off). */
    private double legX;
    private double legZ;
    /**
     * {@code build.elytraOnTheWayBlocks}: the planner turned the leg's last flight down (a plan further on may pass),
     * where the last plan was made, the plans made on the way, the last one's reason, the flights that took off on the
     * way, and the first such flight's blocks on foot before it (-1: none) and why the leg's start walked.
     */
    private boolean wayRetry;
    private double wayX;
    private double wayZ;
    private int wayTries;
    private @Nullable String wayWhy;
    private int wayFlights;
    private double wayWalked = -1;
    private @Nullable String wayFirstWhy;
    /** The last {@link #takeOff} refusal came from the planner or the pre-flight check (not the rockets or the distance). */
    private boolean plannerRefused;
    /** The last refusal was the take-off hold ({@link TakeoffGuard}): planned again on the way once it has passed. */
    private boolean heldBack;
    /** This leg put the elytra on itself (its own GEAR_ON): no {@link Phase#CHECK} before its further flights. */
    private boolean legGearOn;
    /** {@link Phase#CHECK}: ticks in a row the worn elytra passed; its ticks in all, for the take-off line. */
    private int checkOk;
    private long checkTicks = -1;
    /** The plan is a short landing's ({@code build.elytraShortLandings}): its kind for the lines ("" for a long pad). */
    private String legSpot = "";
    /** Plans a leg may make on the way ({@code build.elytraOnTheWayBlocks}). */
    static final int ON_THE_WAY_TRIES = 24;
    /** A leg the take-off hold walked plans again each this many blocks on foot ({@link TakeoffGuard}). */
    static final int HOLD_RETRY_BLOCKS = 20;
    /** ... and while the goal is not loaded, after this many times {@code build.elytraOnTheWayBlocks} only. */
    static final int UNLOADED_SPACING = 4;
    /** An on-the-way take-off waits for the stopped bot: still (blocks a tick across the ground), or this many ticks. */
    static final double SETTLE_SPEED = 0.02;
    static final int SETTLE_TICKS = 10;

    FlightLeg(Host host) {
        this.host = host;
    }

    boolean busy() {
        return phase != Phase.IDLE;
    }

    /** In the air on a flight of this walk (phase FLY, the feet off the ground or gliding). */
    boolean aloft() {
        return phase == Phase.FLY && (!BOT.isOnGround() || BOT.isFallFlying());
    }

    /**
     * On a flight of this walk: phase FLY, from the take-off's jump until the bot stands still after the touch-down
     * (or the bail-out is down). The walk must not end now ({@link Walker#landFirst}).
     */
    boolean inFlight() {
        return phase == Phase.FLY;
    }

    /** The walk's goal list began (not mid-walk): plans the leg; true when it flies first (the walker waits for it). */
    boolean start(long now, List<? extends Goal> goals, NoGoZones zones, @Nullable BorderTop border) {
        leg = false;
        MapArtConfig.BuildCfg b = host.build();
        if (!b.elytraLegs || goals.isEmpty()) return false;
        SprintLegs.Walk kind = host.kind();
        boolean restock = host.inRestock();
        if (!flies(b, kind, restock) || !host.mayFly()) return false;
        if (!(goals.getFirst() instanceof GoalNearXZ g)) return false;
        leg = true;
        legKind = kind.name();
        legOutside = !(restock && kind.restockLeg());
        legStart = now;
        hopStart = now;
        legGoal = g;
        legZones = zones;
        legBorder = border;
        legBlocks = Math.hypot(g.x() + 0.5 - BOT.getX(), g.z() + 0.5 - BOT.getZ());
        legX = BOT.getX();
        legZ = BOT.getZ();
        flyStart = -1;
        flyEnd = -1;
        gearTicks = 0;
        gearClicks = 0;
        flightWhy = null;
        rocketsUsed = 0;
        hopRockets = 0;
        lostInputs = 0;
        wallHits = 0;
        elytraBefore = -1;
        landedHere = false;
        hops = 0;
        flights = 0;
        flownPlan = null;
        airBefore = 0;
        flownActual = 0;
        flightCounted = true;
        arcAir = 0;
        arcFlights = 0;
        hopWhy = null;
        wayRetry = false;
        wayX = legX;
        wayZ = legZ;
        wayTries = 0;
        wayWhy = null;
        wayFlights = 0;
        wayWalked = -1;
        wayFirstWhy = null;
        plannerRefused = false;
        heldBack = false;
        legGearOn = false;
        checkTicks = -1;
        noMoreFlights = false;
        rocketWhy = null;
        legRoute = null; // Baritone has no path yet: a long hop waits for the walk's route (onTheWay)
        routeWait = false;
        walkWhy = refusal(b, legBlocks);
        if (walkWhy != null) {
            wayRetry = heldBack; // the take-off hold: plans again on the way once it has passed
            walkLine();
            return false;
        }
        walkWhy = takeOff(b, legBlocks, true);
        if (walkWhy != null) {
            wayRetry = plannerRefused; // build.elytraOnTheWayBlocks: plans again further on
            walkLine();
            return false;
        }
        to(gearPhase(b), now);
        return true;
    }

    /**
     * A long leg that walks says why at its start too: the leg's own line comes when the walk ends, and a walk that
     * never ends never says it (live 2026-11-15 09:28: a 196-block leg to the rocket chest turned back and forth for
     * 4.5 minutes, its reason unknown).
     */
    static final double START_LINE_BLOCKS = 120;

    private void walkLine() {
        String line = startLine(legKind, legBlocks, walkWhy, legOutside);
        if (line != null) ZenithTask.LOG.info("{}", line);
    }

    /** {@link #walkLine}'s text, or null for a leg under {@link #START_LINE_BLOCKS}. */
    static @Nullable String startLine(String kind, double blocks, @Nullable String why, boolean outside) {
        if (blocks < START_LINE_BLOCKS) return null;
        return "Elytra leg " + kind + ": " + String.format("%.0f", blocks) + " blocks, on foot from the start (" + why + ")"
            + (outside ? "; outside the restock" : "");
    }

    /**
     * The phase before a take-off: an elytra already worn that this leg did not put on is checked first
     * ({@link Phase#CHECK}: live 2026-10-25 08:47, a leg took off at once with the elytra an aborted leg's swap had put
     * on); "worn" mode or this leg's own elytra: the rockets ({@link Phase#HOLD}); else the swap ({@link Phase#GEAR_ON}).
     */
    private Phase gearPhase(MapArtConfig.BuildCfg b) {
        if (elytraWorn()) return legGearOn ? Phase.HOLD : Phase.CHECK;
        return FlightGear.Mode.of(b.elytraGear) == FlightGear.Mode.WORN ? Phase.HOLD : Phase.GEAR_ON;
    }

    /**
     * Whether a walk of {@code kind} may fly: a restock leg in the restock ({@code build.elytraLegs}); outside it, or
     * any other kind, only with its own switch. Never an escape, a fight's chase or retreat, a
     * break task's transit, a button, a lane's own walk or a hop down. Pure.
     */
    static boolean flies(MapArtConfig.BuildCfg b, SprintLegs.Walk kind, boolean restock) {
        if (restock && kind.restockLeg()) return true;
        return switch (kind) {
            case WALK -> b.elytraWalkLegs;
            case CONTAINER -> b.elytraContainerLegs;
            case LANE_START -> b.elytraLaneStartLegs;
            case STAND_SPOT -> b.elytraStandSpotLegs;
            case MAP_STATION -> b.elytraMapStationLegs;
            default -> false;
        };
    }

    /**
     * Plans a flight from the feet toward the leg's goal ({@code blocks} away) and, with {@code log}, logs its take-off
     * line: null when it flies ({@link #plan} set), else why not ({@link #plannerRefused} when the planner or the
     * pre-flight check said so).
     */
    private @Nullable String takeOff(MapArtConfig.BuildCfg b, double blocks, boolean log) {
        String why = takeOffPlan(b, blocks, log);
        if (why == null) {
            why = rocketLookBlocked(FlightControl.takeoffYaw(plan.profile()));
            if (why != null) {
                plannerRefused = true; // build.elytraOnTheWayBlocks / long hops: plans again further on
                if (log) ZenithTask.LOG.info("Elytra take-off {}: not flown, {}", legKind, why);
            }
        }
        if (why != null && spotWhy != null) why = why + "; short landing: " + spotWhy;
        return why;
    }

    /**
     * Why the take-off rocket could not be used from here, or null: a block within reach in its look (the take-off's
     * yaw, {@link FlightControl#CLIMB_PITCH}) from both {@link FlightControl#ROCKET_EYE_LOW} and
     * {@link FlightControl#ROCKET_EYE_HIGH} over the feet. A right click on a block puts the rocket there instead of
     * boosting; the controller waits 3 ticks for a clear look, then bails out (live 2026-10-25, LANE_START legs after a
     * chest withdraw: "no rocket could be used (not held, or a block in the look)" 4 times in 5 s, then the walk).
     */
    private static @Nullable String rocketLookBlocked(float yaw) {
        double reach = BOT.getBlockReachDistance();
        String first = null;
        for (double eye : new double[]{FlightControl.ROCKET_EYE_LOW, FlightControl.ROCKET_EYE_HIGH}) {
            var r = RaycastHelper.blockRaycastFromPos(BOT.getX(), BOT.getY() + eye, BOT.getZ(), yaw, FlightControl.CLIMB_PITCH,
                reach, false);
            if (!r.hit()) return null;
            if (first == null) first = lookHit(r);
        }
        return "a block in the take-off rocket's look (" + first + ")";
    }

    /** A raycast's block, where it lies from the bot's feet cell (no coordinates in the log). */
    private static String lookHit(com.zenith.feature.player.raycast.BlockRaycastResult r) {
        return String.format("%s %+d,%+d,%+d from the feet", r.block() == null ? "a block" : r.block().name(),
            r.x() - (int) Math.floor(BOT.getX()), r.y() - (int) Math.floor(BOT.getY()), r.z() - (int) Math.floor(BOT.getZ()));
    }

    /** Why the last short landing did not fly ({@code build.elytraShortLandings}), for the leg's line; null: none tried. */
    private @Nullable String spotWhy;

    /**
     * {@code build.elytraLongHops}: the walk's route from here toward the goal for this plan ({@link #onTheWay} sets it
     * from the walker's path; null at the leg's start and after a landing, where no path runs), and whether the last
     * plan's long hop waited for it ({@link #NO_ROUTE}): the next plan goes as soon as the walk has a route.
     */
    private @Nullable List<Pos> legRoute;
    private boolean routeWait;

    /**
     * {@code build.elytraShortLandings}: why a leg of {@code blocks} from feet height {@code sy} to the goal's feet level
     * {@code gy} does not try a short landing, or null. Under {@link #STRAIGHT_SPOT_MIN} only a hover lands at the goal
     * (the boost burnt over the spot): about 150-220 ticks with the gear, where a walk on the flat takes about 2.4 ticks
     * a block (live 10-25..27: legs of 30-45 blocks 92 ticks, 45-60 blocks 123). Such a leg tries one only when the goal
     * lies at least {@link #HOVER_CLIMB} above or below the feet (the clear stand, 6 over the art layer: walked 500-800
     * ticks round to its way up every map).
     */
    static @Nullable String flatHover(double blocks, double sy, int gy) {
        if (blocks >= STRAIGHT_SPOT_MIN) return null;
        int startLevel = (int) Math.floor(sy + 0.1251);
        if (Math.abs(gy - startLevel) >= HOVER_CLIMB) return null;
        return String.format("%.0f blocks on the flat (goal %+d from the feet): a walk is faster than a hover", blocks,
            gy - startLevel);
    }

    /** {@link #flatHover}: the goal's feet level this far from the start's or more lets a leg under 60 blocks hover. */
    static final int HOVER_CLIMB = 3;

    private @Nullable String takeOffPlan(MapArtConfig.BuildCfg b, double blocks, boolean log) {
        GoalNearXZ g = legGoal;
        plannerRefused = false;
        routeWait = false;
        legSpot = "";
        spotWhy = null;
        String why = chooseDuration(b, blocks);
        boolean arcs = b.elytraArcLegs && legDuration >= 2 && legWorstKnown && blocks >= b.elytraArcMinBlocks;
        FlightPlanner.Cfg pc = FlightPlanner.Cfg.of(b.elytraMinBlocks, b.elytraCruise).overWater(b.elytraOverWater);
        FlightPlanner.Terrain t = terrain(legZones, legBorder);
        String flat = b.elytraShortLandings ? flatHover(blocks, BOT.getY(), g.y()) : null;
        if (flat != null) spotWhy = flat;
        if (flat == null && b.elytraShortLandings && legDuration >= 2 && legWorstKnown && blocks >= b.elytraShortMinBlocks) {
            // build.elytraShortLandings: onto a spot at the goal first; the long pad below stays the fallback
            long t0 = System.nanoTime();
            Landing l = withSpot(BOT.getX(), BOT.getY(), BOT.getZ(), g.x(), g.y(), g.z(), g.range(), t, pc,
                FlightControl.Cfg.forDuration(b.elytraSlideTicks, legDuration, 1, legWorst), b.elytraShortMinBlocks,
                b.elytraArcLegs, b.elytraArcMinBlocks, b.elytraMaxRockets);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (l.result().plan() != null) {
                legRelay = l.relay();
                plan = l.result().plan();
                FlightPlanner.Profile pp = plan.profile();
                legSpot = String.format("; a short landing on a %s spot at T, %.1f from the goal%s%s (planned in %d ms)",
                    pp.pad.size() == 4 ? "2 x 2" : "3 x 3", Math.hypot(pp.restX - g.x() - 0.5, pp.restZ - g.z() - 0.5),
                    pp.path != null ? ", by an arc: " + pp.path : "",
                    pp.hover ? (pp.high ? ", over the end rods" : "") + ", hovering over it (" + pp.hoverNote + ")" : "", ms);
                if (log) logTakeOff(blocks, legSpot);
                return null;
            }
            spotWhy = l.result().refusal() + " (" + ms + " ms)";
        }
        if (b.elytraLongHops && legDuration >= 2 && legWorstKnown && blocks >= STRAIGHT_SPOT_MIN + HOP_BACK
            && !t.loaded(g.x(), g.z())) {
            // build.elytraLongHops: the goal not loaded yet (far storage): a stop on a spot in the loaded part along the
            // way, the next flight from there; no search near the goal (thousands of cells not loaded, rarely passes)
            long t0 = System.nanoTime();
            Hop h = withLongHop(BOT.getX(), BOT.getY(), BOT.getZ(), g.x(), g.y(), g.z(), t, pc,
                FlightControl.Cfg.forDuration(b.elytraSlideTicks, legDuration, 1, legWorst), b.elytraShortMinBlocks,
                b.elytraMaxRockets, legRoute);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (h.landing().result().plan() != null) {
                legRelay = h.landing().relay();
                plan = h.landing().result().plan();
                FlightPlanner.Profile pp = plan.profile();
                legSpot = String.format("; a hop toward the goal, not loaded yet: a stop on a %s spot %.0f blocks off, %.0f"
                    + " blocks along the walk's route, %.0f short of the goal (planned in %d ms)",
                    pp.pad.size() == 4 ? "2 x 2" : "3 x 3", Math.hypot(pp.restX - BOT.getX(), pp.restZ - BOT.getZ()),
                    h.skipped(), Math.hypot(pp.restX - g.x() - 0.5, pp.restZ - g.z() - 0.5), ms);
                if (log) logTakeOff(blocks, legSpot);
                return null;
            }
            plannerRefused = true; // build.elytraOnTheWayBlocks / long hops: plans again further on
            // no route yet (the leg's start, a landing): planned again as soon as the walk has one (onTheWay)
            routeWait = NO_ROUTE.equals(h.landing().result().refusal());
            String hw = "long hop: " + h.landing().result().refusal() + " (" + ms + " ms)";
            spotWhy = spotWhy == null ? hw : spotWhy + "; " + hw;
            return "the goal is not loaded yet";
        }
        if (why != null) {
            // build.elytraArcLegs: under the straight flight's shortest leg, an arc may fly it
            if (!arcs || !legUnderMin) return why;
            String arc = arcTakeOff(b, blocks, pc, t, log);
            if (arc == null) return null;
            plannerRefused = true;
            return why + "; arc: " + arc;
        }
        FlightPlanner.Result r = FlightPlanner.plan(BOT.getX(), BOT.getY(), BOT.getZ(), g.x(), g.y(), g.z(), t, pc);
        if (r.plan() == null) {
            plannerRefused = true;
            if (!arcs) return r.refusal();
            String arc = arcTakeOff(b, blocks, pc, t, log);
            return arc == null ? null : r.refusal() + "; arc: " + arc;
        }
        boolean relay = false;
        if (legDuration >= 2) {
            // the look-ahead's verdict before the take-off: a way onto the pad whenever the rocket burns out
            Landing l = withLanding(r, BOT.getX(), BOT.getY(), BOT.getZ(), g.x(), g.y(), g.z(), t, pc,
                FlightControl.Cfg.forDuration(b.elytraSlideTicks, legDuration, 1, legWorst));
            r = l.result();
            if (r.plan() == null) {
                plannerRefused = true;
                if (!arcs) return r.refusal();
                String arc = arcTakeOff(b, blocks, pc, t, log);
                return arc == null ? null : r.refusal() + "; arc: " + arc;
            }
            relay = l.relay();
        }
        legRelay = relay;
        plan = r.plan();
        if (log) logTakeOff(blocks, "");
        return null;
    }

    /**
     * {@code build.elytraArcLegs}: an arc flight from the feet toward the leg's goal ({@link #withArc}); null when it
     * flies ({@link #plan} set, one rocket), else why not.
     */
    private @Nullable String arcTakeOff(MapArtConfig.BuildCfg b, double blocks, FlightPlanner.Cfg pc,
                                        FlightPlanner.Terrain t, boolean log) {
        GoalNearXZ g = legGoal;
        long t0 = System.nanoTime();
        Landing l = withArc(BOT.getX(), BOT.getY(), BOT.getZ(), g.x(), g.y(), g.z(), t, pc,
            FlightControl.Cfg.forDuration(b.elytraSlideTicks, legDuration, 1, legWorst), b.elytraArcMinBlocks);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (l.result().plan() == null) return l.result().refusal() + " (" + ms + " ms)";
        legRelay = false;
        plan = l.result().plan();
        if (log) logTakeOff(blocks, "; " + plan.profile().path + " (planned in " + ms + " ms)");
        return null;
    }

    private void logTakeOff(double blocks, String arc) {
        ZenithTask.LOG.info("Elytra take-off {}: {} blocks, flying {} of them{}{}{}{}{}", legKind, String.format("%.0f", blocks),
            String.format("%.0f", plan.flightBlocks()), legDuration == 1 ? ""
                : String.format(" (duration-%d rockets%s, boosts of up to %d ticks planned for; server %.1f TPS, longest"
                    + " recent %d)", legDuration, legRelay ? ", two" : "", legWorst, legTps, legRecent), arc,
            flights > 0 ? "; flight " + (flights + 1) + " of the leg" : "", phase == Phase.SETTLE
                ? String.format("; on the way, %.0f blocks on foot", Math.hypot(BOT.getX() - legX, BOT.getZ() - legZ)) : "",
            legOutside ? "; outside the restock" : "");
    }

    /**
     * {@code build.elytraArcLegs}: the first arc way of {@link FlightPlanner#planArc} that {@link FlightControl#preflight}
     * lands on its pad with one rocket whenever it burns out; else no plan, with the first way's verdict. The ways are at
     * least as long as the shortest straight leg that flies with the longest boost expected.
     */
    static Landing withArc(double sx, double sy, double sz, int gx, int gy, int gz, FlightPlanner.Terrain t,
                           FlightPlanner.Cfg pc, FlightControl.Cfg fc, double arcMin) {
        int straightMin = FlightControl.minLegBlocks(3, 0, fc.boostWorst());
        if (straightMin == Integer.MAX_VALUE) {
            return new Landing(FlightPlanner.Result.no("no arc for boosts of up to " + fc.boostWorst() + " ticks"), false);
        }
        FlightPlanner.ArcResult r = FlightPlanner.planArc(sx, sy, sz, gx, gy, gz, t, pc, arcMin,
            Math.max(FlightPlanner.ARC_PATH_MIN, straightMin));
        if (r.plans().isEmpty()) return new Landing(FlightPlanner.Result.no(r.refusal()), false);
        FlightControl.Cfg one = fc.withRockets(1);
        String first = null;
        for (FlightPlanner.Plan p : r.plans()) {
            FlightControl.Choice c = FlightControl.preflight(p.profile(), one, sx, sy, sz);
            if (c.score() < FlightControl.OFF_PAD) {
                // and safe when the boost ends before the turn (FlightControl.earlyEndProblem)
                String early = FlightControl.earlyEndProblem(p.profile(), one, sx, sy, sz);
                if (early == null) return new Landing(new FlightPlanner.Result(p, null), false);
                if (first == null) first = early + " on the " + p.profile().path;
                continue;
            }
            if (first == null) first = verdict(c) + " on the " + p.profile().path;
        }
        return new Landing(FlightPlanner.Result.no(String.format("no arc lands on its pad with boosts of %d to %d ticks (%d"
            + " ways, the first: %s)", one.boostTicks(), one.boostWorst(), r.plans().size(), first)), false);
    }

    /** Straight flights onto a spot are planned for legs of at least this many blocks (the boost alone carries ~60). */
    static final double STRAIGHT_SPOT_MIN = 60;

    /**
     * {@code build.elytraShortLandings}: the first short landing ({@code FlightPlanner#planSpot}, R = T at the goal) that
     * {@link FlightControl#preflight} lands on its spot with one rocket whenever it burns out, or with two
     * ({@link FlightControl#relayLands}, {@code maxRockets} 2 or more) when one rocket's glide falls short: straight
     * flights from {@link #STRAIGHT_SPOT_MIN} blocks, then arcs ({@code arcs}, from {@code arcMin}) onto a spot, then
     * flights over the end rods ({@link FlightPlanner#planHigh}: a spot higher up, the boost burnt circling over it). Else
     * no plan, with each kind's first verdict.
     */
    static Landing withSpot(double sx, double sy, double sz, int gx, int gy, int gz, int range, FlightPlanner.Terrain t,
                            FlightPlanner.Cfg pc, FlightControl.Cfg fc, double minBlocks, boolean arcs, double arcMin,
                            int maxRockets) {
        return withSpot(sx, sy, sz, gx, gy, gz, range, t, pc, fc, minBlocks, arcs, arcMin, maxRockets, true);
    }

    /** {@link #withSpot} with the hovers ({@code hover}) or straight flights and arcs only (a long hop's stop). */
    static Landing withSpot(double sx, double sy, double sz, int gx, int gy, int gz, int range, FlightPlanner.Terrain t,
                            FlightPlanner.Cfg pc, FlightControl.Cfg fc, double minBlocks, boolean arcs, double arcMin,
                            int maxRockets, boolean hover) {
        double leg = Math.hypot(gx + 0.5 - sx, gz + 0.5 - sz);
        FlightControl.Cfg one = fc.withRockets(1);
        StringBuilder why = new StringBuilder();
        FlightPlanner.SpotResult straight = null;
        if (leg >= Math.max(minBlocks, STRAIGHT_SPOT_MIN)) {
            FlightPlanner.SpotResult r = FlightPlanner.planSpot(sx, sy, sz, gx, gy, gz, range, t, pc, minBlocks,
                Double.POSITIVE_INFINITY);
            straight = r;
            String first = r.refusal();
            for (FlightPlanner.Plan p : r.plans()) {
                String over = FlightPlanner.overrunProblem(t, p.profile());
                if (over != null) {
                    if (first == null) first = over;
                    continue;
                }
                FlightControl.Choice c = FlightControl.preflight(p.profile(), one, sx, sy, sz);
                if (c.score() < FlightControl.OFF_PAD) return new Landing(new FlightPlanner.Result(p, null), false);
                if (maxRockets >= 2 && c.score() < FlightControl.NO_TOUCH && c.shortBy() > 0
                    && FlightControl.relayLands(p.profile(), fc.withRockets(2), sx, sy, sz)) {
                    return new Landing(new FlightPlanner.Result(p, null), true);
                }
                if (first == null) first = verdict(c);
            }
            why.append("straight: ").append(first);
        }
        if (hover && leg >= minBlocks) {
            // a hover: the same spots, the boost burnt over T (a leg short of the boost's reach, or one whose glide
            // misses the spot)
            FlightPlanner.SpotResult r = straight != null ? straight
                : FlightPlanner.planSpot(sx, sy, sz, gx, gy, gz, range, t, pc, minBlocks, Double.POSITIVE_INFINITY);
            String[] first = {null};
            FlightPlanner.Plan p = hovers(r, t, one, sx, sy, sz, first);
            if (p != null) return new Landing(new FlightPlanner.Result(p, null), false);
            why.append(why.isEmpty() ? "" : "; ").append("hover: ").append(first[0]);
            // over the end rods: a spot higher up (the clear stand), the boost burnt over it
            FlightPlanner.SpotResult hi = FlightPlanner.planHigh(sx, sy, sz, gx, gy, gz, range, t, pc, minBlocks);
            first[0] = null;
            p = hovers(hi, t, one, sx, sy, sz, first);
            if (p != null) return new Landing(new FlightPlanner.Result(p, null), false);
            why.append("; over the end rods: ").append(first[0]);
        }
        if (arcs && leg >= arcMin) {
            int straightMin = FlightControl.minLegBlocks(3, 0, fc.boostWorst());
            if (straightMin != Integer.MAX_VALUE) {
                FlightPlanner.ArcResult r = FlightPlanner.planArc(sx, sy, sz, gx, gy, gz, t, pc, arcMin,
                    Math.max(FlightPlanner.ARC_PATH_MIN, straightMin), range);
                String first = r.refusal();
                for (FlightPlanner.Plan p : r.plans()) {
                    String over = FlightPlanner.overrunProblem(t, p.profile());
                    if (over != null) {
                        if (first == null) first = over + " on the " + p.profile().path;
                        continue;
                    }
                    FlightControl.Choice c = FlightControl.preflight(p.profile(), one, sx, sy, sz);
                    if (c.score() < FlightControl.OFF_PAD) return new Landing(new FlightPlanner.Result(p, null), false);
                    if (first == null) first = verdict(c) + " on the " + p.profile().path;
                }
                why.append(why.isEmpty() ? "" : "; ").append("arc: ").append(first);
            }
        }
        return new Landing(FlightPlanner.Result.no(why.isEmpty() ? String.format("%.0f blocks: no kind of short landing"
            + " tried", leg) : why.toString()), false);
    }

    /**
     * {@link #withLongHop}'s verdict: the landing (or why none), the stop it flies to, and the blocks of the walk's
     * route that stop skips (0 for none).
     */
    record Hop(Landing landing, int x, int y, int z, double skipped) {
        Hop(Landing landing, int x, int y, int z) {
            this(landing, x, y, z, 0);
        }
    }

    /** {@link #withLongHop}'s refusal when the walk's route is not known yet (the leg plans again once it is). */
    static final String NO_ROUTE = "the walk's route is not known yet";

    /**
     * {@code build.elytraLongHops}: a leg whose goal is not loaded yet (far storage) flies to a stop ON THE WALK'S ROUTE
     * ({@code route}: the feet cells the pathfinder planned from here toward the goal, in order; it ends where the loaded
     * world ends), so the walk on from the stop is the rest of that route and nothing else. Stops are tried from the
     * route's far end back, each {@link #HOP_STEP} of route nearer, up to {@link #HOP_STOPS}, at least
     * {@link #STRAIGHT_SPOT_MIN} from the feet and {@link #HOP_MIN_SKIP} along the route: a straight short landing onto a
     * spot at the route cell (its feet level, one below or above), else an arc onto one ({@link #withSpot}, no hovers: a
     * stop is a long leg; the simulation: hovers to stops 60-100 blocks off landed none, arcs most), checked by
     * {@link FlightControl#preflight} as any; the spot must cover a route cell at its level or one off
     * ({@link #routeIndex}). The next flight plans from there ({@code build.elytraHops}, at least {@link #LONG_HOPS})
     * once the walk's route from there is known; the walk goes on along its route when none passes.
     *
     * <p>Stage 5 tried stops on the straight line to the goal (or 6-12 beside it). Locally (the
     * store to the platform, 318 blocks) the first hop flew 95 blocks to such a stop, from which the walk went round the
     * platform's no-go zone and along an edge: 811 ticks against 643 with hops off. A stop on the route cannot leave the
     * walk a longer way than it had.
     */
    static Hop withLongHop(double sx, double sy, double sz, int gx, int gy, int gz, FlightPlanner.Terrain t,
                           FlightPlanner.Cfg pc, FlightControl.Cfg fc, double minBlocks, int maxRockets,
                           @Nullable List<Pos> route) {
        if (t.loaded(gx, gz)) return new Hop(new Landing(FlightPlanner.Result.no("the goal is loaded"), false), gx, gy, gz);
        if (route == null || route.size() < 2) return new Hop(new Landing(FlightPlanner.Result.no(NO_ROUTE), false), gx, gy, gz);
        double[] along = routeAlong(route, sx, sz);
        double min = Math.max(minBlocks, STRAIGHT_SPOT_MIN);
        int startLevel = (int) Math.floor(sy + 0.1251);
        String first = null;
        int stops = 0;
        double farthest = 0;
        // the route cells a straight line reaches first (loaded, no no-go cell, no water unless allowed), the farthest
        // first; then the others (an arc may go round); each pass HOP_STEP of route apart
        for (int pass = 0; pass < 2 && stops < HOP_STOPS; pass++) {
            double lastTried = Double.POSITIVE_INFINITY;
            for (int i = route.size() - 1; i >= 0 && stops < HOP_STOPS; i--) {
                if (Double.isNaN(along[i])) break; // behind the feet
                farthest = Math.max(farthest, along[i]);
                if (along[i] < HOP_MIN_SKIP) break;
                if (along[i] > lastTried - HOP_STEP) continue;
                Pos c = route.get(i);
                double straight = Math.hypot(c.x() + 0.5 - sx, c.z() + 0.5 - sz);
                if (!t.loaded(c.x(), c.z()) || straight < min || straight > pc.maxBlocks()) continue;
                boolean open = lineOpen(t, sx, sz, c.x(), c.z(), Math.min(startLevel, c.y()) - 1,
                    Math.max(startLevel, c.y()) + pc.cruiseMax(), pc.overWater());
                if (open != (pass == 0)) continue;
                stops++;
                lastTried = along[i];
                Landing l = withSpot(sx, sy, sz, c.x(), c.y(), c.z(), HOP_RANGE, t, pc, fc, min, true, min, maxRockets, false);
                FlightPlanner.Plan p = l.result().plan();
                String why;
                if (p != null) {
                    int j = routeIndex(route, p.profile());
                    if (j >= 0 && along[j] >= HOP_MIN_SKIP) return new Hop(l, c.x(), c.y(), c.z(), along[j]);
                    why = "its spot lies off the walk's route";
                } else {
                    why = l.result().refusal();
                }
                if (first == null) first = String.format("a stop %.0f blocks along the route: %s", along[i], why);
            }
        }
        String why = first != null ? first : String.format("no stop on the %.0f blocks of the walk's route planned (%d cells;"
            + " a stop at least %.0f from the feet and %.0f along the route)", farthest, route.size(), min, HOP_MIN_SKIP);
        return new Hop(new Landing(FlightPlanner.Result.no(why), false), gx, gy, gz);
    }

    /**
     * {@link #withLongHop}'s quick look before a stop's planning: the straight line from the feet (sx, sz) to column
     * (cx, cz) is loaded, crosses no no-go cell from {@code lo} to {@code hi} and, unless {@code overWater}, no water
     * from {@link #LINE_WATER_DEPTH} under {@code lo} up (a canal's water lies 3 under the platform's feet level). The
     * planner's corridor decides the rest; a stop this fails is tried after those it passes (an arc).
     */
    static boolean lineOpen(FlightPlanner.Terrain t, double sx, double sz, int cx, int cz, int lo, int hi,
                            boolean overWater) {
        double ex = cx + 0.5;
        double ez = cz + 0.5;
        double len = Math.hypot(ex - sx, ez - sz);
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (double s = 0; s <= len; s += 0.5) {
            int x = (int) Math.floor(sx + (ex - sx) * s / len);
            int z = (int) Math.floor(sz + (ez - sz) * s / len);
            if (x == lastX && z == lastZ) continue;
            lastX = x;
            lastZ = z;
            if (!t.loaded(x, z)) return false;
            for (int y = lo - LINE_WATER_DEPTH; y <= hi; y++) {
                if (y >= lo && t.forbidden(x, y, z) || !overWater && t.fluid(x, y, z)) return false;
            }
        }
        return true;
    }

    /**
     * Blocks along {@code route} from the feet (sx, sz) to each of its cells: from the route cell nearest the feet among
     * its first {@link #ROUTE_START_CELLS} (the walk may have gone on a few cells since it was planned) on, each step the
     * distance between the cells' centres; NaN for the cells before it.
     */
    static double[] routeAlong(List<Pos> route, double sx, double sz) {
        int n = route.size();
        int k = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < Math.min(n, ROUTE_START_CELLS); i++) {
            Pos c = route.get(i);
            double d = Math.hypot(c.x() + 0.5 - sx, c.z() + 0.5 - sz);
            if (d < best) {
                best = d;
                k = i;
            }
        }
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        out[k] = best;
        for (int i = k + 1; i < n; i++) {
            Pos a = route.get(i - 1);
            Pos b = route.get(i);
            out[i] = out[i - 1] + Math.hypot(b.x() - a.x(), b.z() - a.z());
        }
        return out;
    }

    /**
     * The last index of a {@code route} cell that the landing's spot or pad covers (its column one of the pad's, its
     * feet level within one of the landing's), or -1: the stop's walk on starts there.
     */
    static int routeIndex(List<Pos> route, FlightPlanner.Profile p) {
        for (int i = route.size() - 1; i >= 0; i--) {
            Pos c = route.get(i);
            if (Math.abs(c.y() - p.level) <= 1 && p.pad.contains(FlightPlanner.Profile.key(c.x(), c.z()))) return i;
        }
        return -1;
    }

    /** {@link #lineOpen}: water this far under the lowest feet level counts. */
    static final int LINE_WATER_DEPTH = 4;

    /** {@link #routeAlong}: the walk's first cells looked at for the one nearest the feet. */
    static final int ROUTE_START_CELLS = 12;

    /**
     * {@link #withLongHop}: route between stops tried, stops tried, a stop's spot range, and the least route a stop must
     * skip (a straight short landing's shortest leg: a flight that skips less walks about as fast).
     */
    static final double HOP_STEP = 10;
    static final int HOP_STOPS = 4;
    static final int HOP_RANGE = 0;
    static final double HOP_MIN_SKIP = STRAIGHT_SPOT_MIN;
    /** {@link #takeOffPlan}: a long hop is tried for legs of at least this many blocks. */
    static final double HOP_BACK = 8;
    /** Further flights a leg may take with {@code build.elytraLongHops} at the least ({@link #hopsAllowed}). */
    static final int LONG_HOPS = 4;
    /** {@code build.elytraLongHops} without {@code build.elytraOnTheWayBlocks}: plans again each this many blocks on foot. */
    static final int LONG_HOP_WAY_BLOCKS = 10;

    /**
     * The first of {@code r}'s spots clear around ({@link FlightPlanner#hoverProblem}) that
     * {@link FlightControl#hoverPreflight} lands on with every boost, flown as a hover
     * ({@link FlightPlanner.Profile#hover}, its check's numbers in {@link FlightPlanner.Profile#hoverNote}); else null and
     * the first verdict (or the planner's refusal) in {@code first[0]}.
     */
    static FlightPlanner.@Nullable Plan hovers(FlightPlanner.SpotResult r, FlightPlanner.Terrain t, FlightControl.Cfg one,
                                              double sx, double sy, double sz, String[] first) {
        for (FlightPlanner.Plan p : r.plans()) {
            FlightPlanner.Profile pp = p.profile();
            String near = FlightPlanner.hoverProblem(t, pp);
            if (near != null) {
                if (first[0] == null) first[0] = near;
                continue;
            }
            pp.hover = true;
            FlightControl.HoverCheck h = FlightControl.hoverPreflight(pp, one, sx, sy, sz);
            if (h.why() == null) {
                pp.hoverNote = String.format("simulated with boosts of %d to %d ticks: touch-down at most %.2f b/t across"
                    + " and %.2f down, %.1f from T", one.boostTicks(), one.boostWorst(), h.touchH(), h.sink(), h.offT());
                return p;
            }
            pp.hover = false;
            if (first[0] == null) first[0] = h.why();
        }
        if (first[0] == null) first[0] = r.refusal() == null ? "no spot" : r.refusal();
        return null;
    }

    /**
     * {@code build.elytraHops}: after a landing short of the leg's goal, a further flight from here when the same
     * checks as a take-off pass; the elytra stays on. True when it goes (the earlier flight folds into the leg's totals
     * at the take-off).
     */
    private boolean hop(long now, MapArtConfig.BuildCfg b) {
        if (legGoal == null || hops >= hopsAllowed(b) || !BOT.isOnGround() || BOT.isFallFlying()) return false;
        double left = Math.hypot(legGoal.x() + 0.5 - BOT.getX(), legGoal.z() + 0.5 - BOT.getZ());
        if (left < minBlocks(b)) return false;
        String why = refusal(b, left);
        hops++;
        plannerRefused = false;
        legRoute = null; // no path runs after a landing: a long hop waits for the walk's route (onTheWay)
        if (why == null) why = takeOff(b, left, true);
        if (why != null) {
            hopWhy = why;
            wayRetry = plannerRefused; // build.elytraOnTheWayBlocks: plans again further on
            wayX = BOT.getX();
            wayZ = BOT.getZ();
            return false;
        }
        hopStart = now;
        to(gearPhase(b), now);
        return true;
    }

    /**
     * Further flights a leg may take ({@code build.elytraHops}); with {@code build.elytraLongHops} at least
     * {@link #LONG_HOPS} (a far goal flies in stops).
     */
    static int hopsAllowed(MapArtConfig.BuildCfg b) {
        return b.elytraLongHops ? Math.max(b.elytraHops, LONG_HOPS) : b.elytraHops;
    }

    /**
     * {@code build.elytraOnTheWayBlocks}: a leg on foot because the planner or the pre-flight check turned its flight
     * down (a wall beside the take-off spot, a pad not loaded yet) plans again after each that many blocks walked, up to
     * {@link #ON_THE_WAY_TRIES} times, while the bot is on the ground, out of water and the leg still flies (the kill
     * switch, its kind's switch, the gear rules, the shortest leg). The walker asks while its path runs. True when the
     * plan passes from here: the walker stops its path, and the leg waits for the bot to stand still
     * ({@link Phase#SETTLE}), plans once more from there and takes off, or hands the walk back. {@code route}: the feet
     * cells the walker's path still walks from here, in order, or null, for {@code build.elytraLongHops}'s stops
     * ({@link #withLongHop}); a long hop that waited for a route ({@link #routeWait}) plans again as soon as one is
     * there, whatever the spacing.
     */
    boolean onTheWay(long now, java.util.function.Supplier<@Nullable List<Pos>> route) {
        if (!leg || phase != Phase.IDLE || !wayRetry || legGoal == null || noMoreFlights) return false;
        MapArtConfig.BuildCfg b = host.build();
        if (!b.elytraLegs || wayTries >= ON_THE_WAY_TRIES) return false;
        // build.elytraOnTheWayBlocks; a leg held back by the take-off hold plans again each HOLD_RETRY_BLOCKS;
        // build.elytraLongHops each LONG_HOP_WAY_BLOCKS (out from under a roof, a far goal's next stop)
        if (b.elytraOnTheWayBlocks <= 0 && !heldBack && !b.elytraLongHops) return false;
        if (!flies(b, host.kind(), host.inRestock()) || !host.mayFly()) return false;
        if (!BOT.isOnGround() || BOT.isFallFlying() || BOT.isTouchingWater()) return false;
        List<Pos> r = b.elytraLongHops ? route.get() : null;
        boolean routeNow = routeWait && r != null && r.size() >= 2;
        // a goal not loaded yet: every fourth spacing only (such a plan searches thousands of pad cells and rarely
        // passes; local run s3d: 18 of them, the counter's walk 161 ticks longer at 10x)
        int every = b.elytraOnTheWayBlocks > 0 ? b.elytraOnTheWayBlocks : b.elytraLongHops ? LONG_HOP_WAY_BLOCKS
            : HOLD_RETRY_BLOCKS;
        // (long hops: a stop's search that finds none takes up to ~0.45 s in the simulation, its arcs: the same spacing)
        int spacing = every * (World.isChunkLoadedBlockPos(legGoal.x(), legGoal.z()) ? 1 : UNLOADED_SPACING);
        if (!routeNow && Math.hypot(BOT.getX() - wayX, BOT.getZ() - wayZ) < spacing) return false;
        wayX = BOT.getX();
        wayZ = BOT.getZ();
        wayTries++;
        legRoute = r;
        long t0 = System.nanoTime();
        String why = replan(b, false);
        if (why != null) {
            var dbg = io.github.digitalisp.mapart.Services.LOG; // the plugin's logger: its DEBUG lines go to debug.log
            if (dbg != null && dbg.isDebugEnabled()) {
                dbg.debug("Elytra leg {}: on the way, plan {} after {} blocks on foot ({} ms): {}", legKind, wayTries,
                    fmt(Math.hypot(BOT.getX() - legX, BOT.getZ() - legZ)), (System.nanoTime() - t0) / 1_000_000, why);
            }
            return false;
        }
        to(Phase.SETTLE, now);
        return true;
    }

    /** A plan from here toward the leg's goal ({@link #onTheWay}): null when it flies, else why not (the leg's line). */
    private @Nullable String replan(MapArtConfig.BuildCfg b, boolean log) {
        double left = Math.hypot(legGoal.x() + 0.5 - BOT.getX(), legGoal.z() + 0.5 - BOT.getZ());
        plannerRefused = false;
        heldBack = false;
        String why = refusal(b, left);
        if (why == null) why = takeOff(b, left, log);
        if (why != null) {
            wayWhy = why;
            wayRetry = plannerRefused || heldBack;
        }
        return why;
    }

    /**
     * {@link #legDuration}: the duration of the rockets this leg fires (the one in the hand, else the first flight
     * rocket carried). Null when the leg reaches that duration's shortest leg ({@link FlightControl#minLegBlocks});
     * else why it walks.
     */
    private @Nullable String chooseDuration(MapArtConfig.BuildCfg b, double blocks) {
        legWorstKnown = false;
        legUnderMin = false;
        int d = Inv.flightDuration(Inv.mainHand());
        if (d == 0) {
            int slot = Inv.find(ROCKET);
            d = slot < 0 ? 0 : Inv.flightDuration(Inv.stack(slot));
        }
        if (d == 0) return "no flight rockets";
        legDuration = d;
        legRocket = Inv.flightRocket(d);
        double tps = tps();
        int recent = WATCH.recentMax(System.currentTimeMillis());
        legTps = tps;
        legRecent = recent;
        legWorst = FlightControl.worstBoost(d, tps, recent);
        int min = FlightControl.minLegBlocks(d, b.elytraMinBlocks, legWorst);
        legWorstKnown = min != Integer.MAX_VALUE;
        legUnderMin = false;
        if (min == Integer.MAX_VALUE) {
            return String.format("boosts of up to %d ticks expected (server %.1f TPS, longest recent %d): more than the %d"
                + " planned for", legWorst, tps, recent, FlightControl.MIN_LEG_BY_WORST[FlightControl.MIN_LEG_BY_WORST.length - 1][0]);
        }
        legUnderMin = blocks < min;
        // one decimal: a 99.6-block leg is not "100 blocks, under the 100"
        return blocks >= min ? null : String.format("%.1f blocks, under the %d a duration-%d rocket needs (boosts of up to %d"
            + " ticks)", blocks, min, d, legWorst);
    }

    /** {@link #chooseDuration}: the boosts expected lie within the sweeps' rows; the leg is under the shortest leg. */
    private boolean legWorstKnown;
    private boolean legUnderMin;

    /**
     * The shortest leg that may fly at all: {@code build.elytraMinBlocks}, or with arcs or short landings on the shortest
     * of it and theirs.
     */
    static int minBlocks(MapArtConfig.BuildCfg b) {
        int min = b.elytraArcLegs ? Math.min(b.elytraMinBlocks, b.elytraArcMinBlocks) : b.elytraMinBlocks;
        return b.elytraShortLandings ? Math.min(min, b.elytraShortMinBlocks) : min;
    }

    /** {@link #withLanding}'s verdict: the plan (or why none), and whether it flies with two rockets. */
    record Landing(FlightPlanner.Result result, boolean relay) { }

    /**
     * Durations 2 and 3: {@code first} (a plan) if {@link FlightControl#preflight} finds its landing with one rocket.
     * If the best way comes to rest short of R (one rocket's glide does not reach it): the whole flight with two
     * rockets if {@link FlightControl#relayLands}; else R moves back along the line (the planner's {@code maxFlight}) and
     * the rest of the leg walks: first to the reach less {@link #SHORTEN_FIRST}, then a further {@link #SHORTEN_STEP} a
     * try, or to where that try came to rest less {@link #SHORTEN_FIRST} when that is further back (R's band under the
     * roofs changes the reach), up to {@link #SHORTEN_TRIES} tries. Else no plan, with the check's reason.
     */
    static Landing withLanding(FlightPlanner.Result first, double sx, double sy, double sz, int gx, int gy, int gz,
                               FlightPlanner.Terrain t, FlightPlanner.Cfg pc, FlightControl.Cfg fc) {
        FlightControl.Choice pre = FlightControl.preflight(first.plan().profile(), fc, sx, sy, sz);
        if (pre.score() < FlightControl.OFF_PAD) return new Landing(first, false);
        String why = preflightRefusal(pre, fc);
        if (pre.score() >= FlightControl.NO_TOUCH || pre.shortBy() <= 0) return new Landing(FlightPlanner.Result.no(why), false);
        if (FlightControl.relayLands(first.plan().profile(), fc.withRockets(2), sx, sy, sz)) return new Landing(first, true);
        double reach = first.plan().profile().length() - pre.shortBy();
        double max = reach - SHORTEN_FIRST;
        double lastLength = first.plan().profile().length();
        StringBuilder tried = new StringBuilder(); // each shortened R's outcome, for the walk's reason
        for (int i = 0; i < SHORTEN_TRIES && max >= pc.minBlocks() * 0.75; i++) {
            FlightPlanner.Result r = FlightPlanner.plan(sx, sy, sz, gx, gy, gz, t, pc, max);
            String got;
            double next = max - SHORTEN_STEP;
            if (r.plan() == null) {
                got = padRefusal(r.refusal());
            } else {
                double len = r.plan().profile().length();
                if (len >= lastLength - 0.5) {
                    got = "the same R";
                } else {
                    FlightControl.Choice c = FlightControl.preflight(r.plan().profile(), fc, sx, sy, sz);
                    if (c.score() < FlightControl.OFF_PAD) return new Landing(r, false);
                    got = verdict(c);
                    if (c.score() < FlightControl.NO_TOUCH && c.shortBy() > 0) {
                        next = Math.min(next, len - c.shortBy() - SHORTEN_FIRST);
                    }
                    lastLength = len;
                }
                next = Math.min(next, len - SHORTEN_STEP);
            }
            tried.append(tried.isEmpty() ? "" : ", ").append(String.format("%.0f: %s", max, got));
            max = next;
        }
        return new Landing(FlightPlanner.Result.no(why + "; R moved back to " + tried), false);
    }

    /** A shortened flight's R: the reach less this at first ({@link #withLanding}). */
    static final double SHORTEN_FIRST = 2;
    /** ... then this much further back a try at least. */
    static final double SHORTEN_STEP = 3;
    /** ... for at most this many tries. */
    static final int SHORTEN_TRIES = 8;

    /** A shortened flight's planner refusal, short: the nearest pad's problem without its position, or as it is. */
    static String padRefusal(@Nullable String refusal) {
        if (refusal == null) return "no plan";
        String head = "no landing pad near the goal (";
        if (!refusal.startsWith(head)) return refusal;
        String inner = refusal.substring(head.length());
        int semi = inner.indexOf(';');
        return FlightPlanner.kind(semi < 0 ? inner : inner.substring(0, semi));
    }

    /** Why a leg walks after {@link FlightControl#preflight} found no landing for it. */
    static String preflightRefusal(FlightControl.Choice pre, FlightControl.Cfg cfg) {
        return String.format("no landing on the pad with boosts of %d to %d ticks (%s)", cfg.boostTicks(), cfg.boostWorst(),
            verdict(pre));
    }

    /**
     * {@link FlightControl#preflight}'s best way when it misses the pad, in words: where the shortest boost's way comes
     * to rest, and what puts it off the pad when that is not the rest point.
     */
    static String verdict(FlightControl.Choice pre) {
        if (pre.score() >= FlightControl.INVALID) return "every way runs into a block or out of the corridor";
        if (pre.score() >= FlightControl.WORST_UNSAFE) return "with the longest boost every way runs into a block or out of"
            + " the corridor";
        if (pre.score() >= FlightControl.NO_TOUCH) return "a way stays in the air";
        String rest = String.format("the best comes to rest %.1f %s R", Math.abs(pre.shortBy()),
            pre.shortBy() >= 0 ? "short of" : "past");
        String miss = FlightControl.missWords(pre.miss());
        return miss == null ? rest : rest + ", " + miss;
    }

    /** The server's ticks a second as ZenithProxy estimates them; NaN when unknown. */
    private static double tps() {
        try {
            return com.zenith.Globals.TPS.getTPSValue();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** Ticks in a row an elytra already worn must pass {@link #wornProblem} before the take-off ({@link Phase#CHECK}). */
    static final int CHECK_TICKS = 5;

    /**
     * Why an elytra already worn may not take off now ({@link Phase#CHECK}), or null: on the ground, the glide off,
     * standing still, no gear swap's clicks going out and nothing on the cursor, the chest slot an elytra, the plugin's
     * own (or "worn" mode), and the gear rules ({@link FlightGear#refusal}: durability, health, rockets).
     */
    private @Nullable String wornProblem(MapArtConfig.BuildCfg b) {
        if (!BOT.isOnGround() || BOT.isFallFlying()) return "not standing on the ground";
        var v = BOT.getVelocity();
        if (Math.hypot(v.getX(), v.getZ()) >= SETTLE_SPEED) return "still moving";
        if (gearClicksPending()) return "a gear swap's clicks still going out";
        if (!Inv.empty(Inv.cursor())) return "a stack on the cursor";
        if (!elytraWorn()) return "the elytra is not worn";
        if (!WORN.ours && FlightGear.Mode.of(b.elytraGear) != FlightGear.Mode.WORN && chestplateSlot() >= 0) {
            return "an elytra worn that no flight put on";
        }
        return gearRefusal(b, legGoal == null ? legBlocks : Math.hypot(legGoal.x() + 0.5 - BOT.getX(),
            legGoal.z() + 0.5 - BOT.getZ()));
    }

    /** Why this leg walks (the gear rules; the distance is the planner's), or null. */
    private @Nullable String refusal(MapArtConfig.BuildCfg b, double blocks) {
        int min = minBlocks(b);
        if (blocks < min) {
            return String.format("%.0f blocks, under %s %d", blocks, min == b.elytraMinBlocks ? "elytraMinBlocks"
                : "elytraArcMinBlocks", min);
        }
        // the take-off hold: a player's control just ended, a teleport, a silent server, a login (TakeoffGuard)
        long ms = System.currentTimeMillis();
        String hold = TakeoffGuard.SESSION.refusal(ms, Math.max(0, b.elytraTakeoffHoldSeconds) * 1000L);
        if (hold != null) {
            heldBack = true;
            return hold;
        }
        if (TakeoffGuard.SESSION.handoffPending()) {
            // the first take-off since the bot's ticks started: the gear as the plugin left it, or not at all
            String g = handoffProblem(b);
            if (g != null) {
                heldBack = true;
                return g;
            }
            TakeoffGuard.SESSION.handoffChecked();
        }
        return gearRefusal(b, blocks);
    }

    /**
     * The first take-off after the bot's ticks started (live 2026-10-25, 08:33 server time: a player flew the bot's
     * elytra and left it worn; the bot took off 2 s later): why its gear is not what the plugin left, or null. Gliding:
     * no. The plugin's elytra no longer worn: forgotten. An elytra worn that no flight put on, a chestplate carried:
     * the plugin's from here ({@link #adoptWorn(MapArtConfig.BuildCfg)}; the backstop puts the chestplate back on
     * when the gear mode has it off, and the gear rules and the take-off hold apply as to its own). Refusing it instead
     * walked every leg after a ZenithProxy restart that left the elytra worn (local 10-25: 41 refusals in 7 minutes,
     * nothing put the chestplate back on, BLOCKED in the NUKE).
     */
    private static @Nullable String handoffProblem(MapArtConfig.BuildCfg b) {
        if (BOT.isFallFlying()) return "gliding after the bot's ticks started";
        if (WORN.ours && !elytraWorn()) {
            WORN.ours = false;
            WORN.chestId = null;
            ZenithTask.LOG.info("Elytra gear: the elytra the plugin put on is no longer worn after the bot's ticks started;"
                + " forgotten");
        }
        adoptWorn(b);
        return null;
    }

    /**
     * {@link ZenithTask}'s backstop is due: the plugin's elytra is worn, or an elytra it did not put on (the backstop
     * first takes that over, {@link #adoptWorn(MapArtConfig.BuildCfg)}: a restart or a player's control left it worn).
     */
    static boolean backstopDue() {
        return WORN.ours || elytraWorn();
    }

    /** The gear rules for a leg of {@code blocks} ({@link FlightGear#refusal}), or null. */
    private @Nullable String gearRefusal(MapArtConfig.BuildCfg b, double blocks) {
        ItemStack worn = chest();
        boolean wornElytra = ELYTRA.test(worn);
        int slot = Inv.find(ELYTRA);
        ItemStack e = wornElytra ? worn : slot >= 0 ? Inv.stack(slot) : null;
        FlightGear.State s = new FlightGear.State(CONFIG.client.extra.autoArmor.enabled, wornElytra, slot,
            e == null ? 0 : Wear.remaining(e), e == null ? 0 : Wear.maxUses(e), Inv.count(ROCKET),
            CACHE.getPlayerCache().getThePlayer().getHealth(), !Inv.empty(worn));
        return FlightGear.refusal(s, FlightGear.Mode.of(b.elytraGear), b.elytraMinPercent, b.elytraMinHealth,
            (int) Math.ceil(blocks / 20.0) + 2);
    }

    /** One tick of a busy leg (the walker returns RUNNING); the walk goes on with Baritone once it is not busy. */
    void tick(long now) {
        lastTicked = engineTicks;
        Clicker c = host.clicker();
        switch (phase) {
            case SETTLE -> {
                c.submit(now, null, null, null); // no keys: the bot slows to a stop where the walker left its path
                var v = BOT.getVelocity();
                boolean still = BOT.isOnGround() && Math.hypot(v.getX(), v.getZ()) < SETTLE_SPEED;
                if (!still && now - phaseStart < SETTLE_TICKS) return;
                MapArtConfig.BuildCfg b = host.build();
                String why;
                if (!BOT.isOnGround() || BOT.isFallFlying()) {
                    why = "not on the ground after the stop";
                    wayWhy = why;
                } else {
                    why = replan(b, true); // from where it stands: the plan it flies
                }
                if (why != null) {
                    wayX = BOT.getX();
                    wayZ = BOT.getZ();
                    to(Phase.IDLE, now); // the walker takes its path up again
                    return;
                }
                if (flights == 0) {
                    wayWalked = Math.hypot(BOT.getX() - legX, BOT.getZ() - legZ);
                    wayFirstWhy = walkWhy;
                }
                wayFlights++;
                wayRetry = false;
                hopWhy = null;
                hopStart = now;
                adoptWorn(b);
                to(gearPhase(b), now);
            }
            case CHECK -> {
                c.submit(now, null, null, null); // no keys: the bot stands while the worn elytra is checked
                String bad = wornProblem(host.build());
                checkOk = bad == null ? checkOk + 1 : 0;
                if (checkOk >= CHECK_TICKS) {
                    checkTicks = now - phaseStart;
                    legGearOn = true; // checked: this leg's own from here (its further flights)
                    to(Phase.HOLD, now);
                    return;
                }
                if (now - phaseStart > GEAR_TICKS) walk(now, "the worn elytra's check did not pass in " + GEAR_TICKS
                    + " ticks (" + bad + ")");
            }
            case GEAR_ON -> {
                c.submit(now, null, null, null);
                if (elytraWorn() && swapSettled(request)) {
                    WORN.ours = true;
                    legGearOn = true;
                    to(Phase.HOLD, now);
                    return;
                }
                if (now - phaseStart > GEAR_TICKS) {
                    walk(now, "the elytra did not go on in " + GEAR_TICKS + " ticks");
                    return;
                }
                if (request == null || request.isCompleted() && now - requestAt > 20) {
                    if (!Inv.empty(Inv.cursor())) {
                        cursorDown(now);
                        return;
                    }
                    ItemStack worn = chest();
                    int from = Inv.find(ELYTRA);
                    if (from < 0 || !OffhandGuard.movable(from)) {
                        walk(now, "no elytra in the inventory");
                        return;
                    }
                    List<FlightGear.Click> clicks = FlightGear.swap(from, FlightGear.borrowKey(Inv.heldSlot()));
                    RequestFuture f = host.hotbar().submit(actions(clicks), now);
                    if (f != null) {
                        request = f;
                        requestAt = now;
                        gearSent(f);
                        WORN.chestId = Inv.empty(worn) ? null : Inv.id(worn);
                        gearClicks += clicks.size();
                    }
                }
            }
            case HOLD -> {
                if (!elytraWorn()) {
                    c.submit(now, null, null, null);
                    walk(now, "the elytra is not worn");
                    return;
                }
                // the take-off's first keys go in this very tick: one input request a tick (the first one counts)
                if (host.hotbar().ensureHeld(legRocket, ROCKET_SLOTS, now)) {
                    gearTicks += now - hopStart;
                    if (elytraBefore < 0) elytraBefore = Wear.remaining(chest()); // the leg's first flight
                    rocketsBefore = Inv.count(ROCKET);
                    hopRockets = 0;
                    rocketWhy = null;
                    boostCounted = false;
                    DamageEvents.takeWall(); // a wall hit before this flight is not its own
                    MapArtConfig.BuildCfg b = host.build();
                    // durations 2 and 3: the take-off rocket only (a second one's boost overshoots), or two on a relay
                    // flight (the second where the look-ahead lands it with either boost)
                    ctl = new FlightControl(plan.profile(), legDuration <= 1
                        ? FlightControl.Cfg.forDuration(b.elytraSlideTicks, legDuration, b.elytraMaxRockets)
                        : FlightControl.Cfg.forDuration(b.elytraSlideTicks, legDuration, legRelay ? 2 : 1, legWorst),
                        BOT.getPitch());
                    if (legRelay) ctl.relay();
                    if (flownPlan != null) { // build.elytraHops: the leg's earlier flight into its totals
                        airBefore += flyEnd - flyStart;
                    }
                    flownPlan = plan;
                    flights++;
                    flyStart = now;
                    flyStartMs = System.currentTimeMillis();
                    flyEnd = -1;
                    flyX0 = BOT.getX();
                    flyZ0 = BOT.getZ();
                    flightCounted = false;
                    if (plan.profile().path != null) {
                        arcFlights++;
                        arcAir += plan.profile().path.length();
                    }
                    to(Phase.FLY, now);
                    fly(now);
                    return;
                }
                c.submit(now, null, null, null);
                if (now - phaseStart > GEAR_TICKS) walk(now, "the rockets did not come into the hand in " + GEAR_TICKS + " ticks");
            }
            case FLY -> fly(now);
            case GEAR_OFF -> {
                c.submit(now, null, null, null);
                if (chestBackDone() && swapSettled(request)) {
                    WORN.ours = false;
                    ZenithTask.LOG.info("Elytra gear: the chestplate is back on after the landing ({} ticks)", now - phaseStart);
                    to(Phase.IDLE, now);
                    return;
                }
                if (now - phaseStart > GEAR_TICKS) {
                    ZenithTask.LOG.warn("Elytra gear: the chestplate did not go back on in {} ticks; walking on with the elytra",
                        GEAR_TICKS);
                    to(Phase.IDLE, now);
                    return;
                }
                if (request == null || request.isCompleted() && now - requestAt > 20) {
                    if (!Inv.empty(Inv.cursor())) {
                        cursorDown(now);
                        return;
                    }
                    List<FlightGear.Click> clicks = chestBackClicks();
                    if (clicks == null) {
                        to(Phase.IDLE, now);
                        return;
                    }
                    RequestFuture f = host.hotbar().submit(actions(clicks), now);
                    if (f != null) {
                        request = f;
                        requestAt = now;
                        gearSent(f);
                        gearClicks += clicks.size();
                    }
                }
            }
            case IDLE -> { }
        }
    }

    /**
     * The controller's world this tick: the longest boost from the server's TPS now (a dip in flight raises it,
     * {@link FlightControl#raiseWorst}), a silent server ({@link TakeoffGuard#silent}), the players near.
     */
    /** Wall time of this flight's take-off: a server teleport or setback after it ends the flight ({@link #inFlight}). */
    private long flyStartMs;
    /** Why the take-off rocket could not be used, the flight's last such tick ({@link #fly}); null: it could. */
    private @Nullable String rocketWhy;
    /** A take-off of this leg ended without a boost: no further one (no hop, no plan on the way). */
    private boolean noMoreFlights;

    /** The last teleport told to the controller ({@link TakeoffGuard#lastTeleport}). */
    private long tpSeen = Long.MIN_VALUE;

    private void inFlight(FlightControl k0) {
        long ms = System.currentTimeMillis();
        // a server teleport or setback since this flight's take-off: the controller ends the take-off or goes down
        long tp = TakeoffGuard.SESSION.lastTeleport();
        if (tp >= flyStartMs && tp != tpSeen) {
            tpSeen = tp;
            ZenithTask.LOG.warn("Elytra flight: a server teleport or setback during the flight ({}); no further rocket,"
                + " down from here", k0.phase());
            k0.teleported();
        }
        if (legDuration >= 2) k0.raiseWorst(FlightControl.worstBoost(legDuration, tps(), WATCH.recentMax(ms)));
        k0.serverSilent(TakeoffGuard.SESSION.silent(ms));
        k0.players(playersNear(BOT.getX(), BOT.getZ(), PLAYERS_WITHIN));
    }

    /** Players are looked for this far from the bot (blocks, across the ground). */
    static final double PLAYERS_WITHIN = 160;

    /** The feet (x, y, z) of every player but the bot within {@code within} of (x, z): teammates and anyone else. */
    static List<double[]> playersNear(double x, double z, double within) {
        List<double[]> out = new ArrayList<>();
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (!(e instanceof com.zenith.cache.data.entity.EntityPlayer pl) || pl.isSelfPlayer() || e.isRemoved()) continue;
            if (Math.hypot(e.getX() - x, e.getZ() - z) > within) continue;
            out.add(new double[]{e.getX(), e.getY(), e.getZ()});
        }
        return out;
    }

    /** The bot for the controller this tick; {@code canRocket} only when asked and possible. */
    private static FlightControl.In botIn(boolean rocketsOk, Predicate<ItemStack> rocket) {
        boolean held = rocket.test(Inv.mainHand());
        float pitch = BOT.getPitch();
        boolean clear = rocketsOk && held && !RaycastHelper.blockRaycastFromPos(BOT.getX(), BOT.getEyeY(), BOT.getZ(),
            BOT.getYaw(), pitch, BOT.getBlockReachDistance(), false).hit();
        var v = BOT.getVelocity();
        return new FlightControl.In(BOT.getX(), BOT.getY(), BOT.getZ(), v.getX(), v.getY(), v.getZ(),
            BOT.isOnGround(), BOT.isFallFlying(), boosting(), Inv.count(rocket), pitch, clear);
    }

    private void fly(long now) {
        FlightControl k0 = ctl;
        boolean held = legRocket.test(Inv.mainHand());
        double reach = BOT.getBlockReachDistance();
        FlightControl.In in = botIn(true, legRocket);
        if (BOT.isHorizontalCollision() && !in.onGround()) hitLog(k0, in, false);
        if (DamageEvents.takeWall() >= 0) {
            wallHits++;
            hitLog(k0, in, true);
        }
        boolean owned = k0.ticks() == 0 || host.clicker().ownedLastTick(now);
        if (!owned) lostInputs++;
        if (k0.phase() == FlightControl.Phase.ROCKET && !in.canRocket()) {
            // why the controller's take-off rocket cannot go this tick (the no-boost line says it)
            if (!held) {
                ItemStack h = Inv.mainHand();
                rocketWhy = "the main hand holds " + (Inv.empty(h) ? "nothing" : Inv.id(h)) + ", not the leg's rockets";
            } else {
                var r = RaycastHelper.blockRaycastFromPos(BOT.getX(), BOT.getEyeY(), BOT.getZ(), BOT.getYaw(), in.pitch(),
                    reach, false);
                rocketWhy = r.hit() ? String.format("a block in the look at yaw %.0f pitch %.0f, eyes %.2f over the feet: %s",
                    BOT.getYaw(), in.pitch(), BOT.getEyeY() - BOT.getY(), lookHit(r)) : "the look clear and the rockets held";
            }
            if (!owned) rocketWhy += "; last tick's input not ours";
        }
        inFlight(k0);
        FlightControl.Keys k = k0.tick(in);
        var dbg = io.github.digitalisp.mapart.Services.LOG; // the plugin's logger: its DEBUG lines go to debug.log
        if (dbg != null && dbg.isDebugEnabled()) {
            FlightPlanner.Profile p = plan.profile();
            FlightControl.Choice ch = k0.lastChoice();
            dbg.debug("Elytra flight t {} {}: along {} of {}, across {}, up {}, h {}, vy {}, pitch {} -> {}, ground {}, "
                    + "glide {}, boost {}, rockets {}{}{}{}", k0.ticks(), k0.phase(), fmt(p.along(in.x(), in.z())), fmt(p.length()),
                fmt(p.across(in.x(), in.z())), fmt(in.y() - p.surface), fmt(in.hSpeed()), fmt(in.vy()), fmt(in.pitch()), fmt(k.pitch()),
                in.onGround(), in.fallFlying(), in.boosting(), k0.rocketsFired(), k.jump() ? ", jump" : "",
                owned ? "" : ", last tick's input not ours", ch == null ? ""
                    : String.format(", best %.1f score %.1f short %.1f in %d t", ch.pitch(), ch.score(), ch.shortBy(), ch.ticks()));
        }
        if (BOT.isTouchingWater() && k.abort() == null && !k.done()) {
            ZenithTask.LOG.warn("Elytra leg: in water; gliding down");
        }
        if (k.abort() != null) {
            flightWhy = k.abort();
            flyEnd = now;
            afterFlight(now);
            return;
        }
        if (k.done()) {
            flyEnd = now;
            flightWhy = k.abort();
            afterFlight(now);
            return;
        }
        if (k.rocket()) {
            // the rocket's right click must aim with this tick's rotation: no block within reach in the look
            boolean ok = held && !RaycastHelper.blockRaycastFromPos(BOT.getX(), BOT.getEyeY(), BOT.getZ(), k.yaw(), k.pitch(),
                reach, false).hit();
            if (ok) {
                rocketsUsed++;
                hopRockets++;
                Input i = Input.builder().jumping(k.jump()).rightClick(true).hand(Hand.MAIN_HAND)
                    .clickTarget(ClickTarget.None.INSTANCE).clickRequiresRotation(true).build();
                if (k.jump()) StrayGlideGuard.flightJump = true;
                host.clicker().submit(now, k.yaw(), k.pitch(), i);
                return;
            }
        }
        if (k.jump()) StrayGlideGuard.flightJump = true; // the glide start: not held back (StrayGlideGuard)
        host.clicker().submit(now, k.yaw(), k.pitch(), Input.builder().jumping(k.jump()).build());
    }

    /**
     * ZenithProxy's Bot ran into a block in the air (the server deals fly_into_wall damage for a fast one): where, and
     * the blocks with a collision box around the bot. WARN; INFO for a touch-down scrape (phase LANDED, or the feet
     * within {@link #SCRAPE_GAP} of the floor under them: live 2026-10-25, a slide 0.15 over the soul sand clipped a
     * carpet's edge, no damage).
     */
    private static void hitLog(FlightControl k0, FlightControl.In in, boolean serverDamage) {
        int bx = (int) Math.floor(in.x());
        int by = (int) Math.floor(in.y());
        int bz = (int) Math.floor(in.z());
        double floor = Double.NEGATIVE_INFINITY;
        for (int x = (int) Math.floor(in.x() - 0.3); x <= (int) Math.floor(in.x() + 0.3); x++) {
            for (int z = (int) Math.floor(in.z() - 0.3); z <= (int) Math.floor(in.z() + 0.3); z++) {
                for (int y = by - 1; y <= by; y++) {
                    int id = World.getBlockStateId(x, y, z);
                    if (id == 0) continue;
                    for (CollisionBox cb : BLOCK_DATA.getCollisionBoxesFromBlockStateId(id)) {
                        if (y + cb.maxY() <= in.y() + 0.01) floor = Math.max(floor, y + cb.maxY());
                    }
                }
            }
        }
        double gap = in.y() - floor;
        boolean scrape = k0.phase() == FlightControl.Phase.LANDED || gap <= SCRAPE_GAP;
        StringBuilder near = new StringBuilder();
        for (int y = by - 1; y <= by + 1; y++) {
            for (int x = bx - 1; x <= bx + 1; x++) {
                for (int z = bz - 1; z <= bz + 1; z++) {
                    int id = World.getBlockStateId(x, y, z);
                    if (id == 0 || BLOCK_DATA.getCollisionBoxesFromBlockStateId(id).isEmpty()) continue;
                    near.append(' ').append(BlockStates.spec(id).id()).append('@').append(x).append(',').append(y)
                        .append(',').append(z);
                }
            }
        }
        String over = Double.isInfinite(gap) ? "no floor under the feet" : fmt(gap) + " over the floor";
        if (serverDamage) {
            ZenithTask.LOG.warn("Elytra flight ran into a block: the server dealt fly_into_wall damage; t {} {} at {},{},{},"
                    + " h {}, vy {}, {}; blocks around:{}", k0.ticks(), k0.phase(), fmt(in.x()), fmt(in.y()), fmt(in.z()),
                fmt(in.hSpeed()), fmt(in.vy()), over, near);
            return;
        }
        if (scrape) {
            ZenithTask.LOG.info("Elytra touch-down scrape: t {} {} at {},{},{}, h {}, vy {}, {}; blocks around:{}",
                k0.ticks(), k0.phase(), fmt(in.x()), fmt(in.y()), fmt(in.z()), fmt(in.hSpeed()), fmt(in.vy()), over, near);
            return;
        }
        ZenithTask.LOG.warn("Elytra flight ran into a block: t {} {} at {},{},{}, h {}, vy {}, {}; blocks around:{}",
            k0.ticks(), k0.phase(), fmt(in.x()), fmt(in.y()), fmt(in.z()), fmt(in.hSpeed()), fmt(in.vy()), over, near);
    }

    /** {@link #hitLog}: feet this near the floor under them, a scrape of the touch-down (the controller's contact). */
    static final double SCRAPE_GAP = 0.2;

    /** The blocks this flight covered (take-off spot to touch-down, or to here without one). */
    private double flightCovered() {
        FlightControl k = ctl;
        boolean td = k != null && !Double.isNaN(k.touchX());
        return Math.hypot((td ? k.touchX() : BOT.getX()) - flyX0, (td ? k.touchZ() : BOT.getZ()) - flyZ0);
    }

    /** This flight into the leg's {@link #flownActual}, once. */
    private void countFlown() {
        if (flightCounted) return;
        flightCounted = true;
        flownActual += flightCovered();
    }

    private void afterFlight(long now) {
        FlightControl c0 = ctl;
        if (c0 != null && c0.boostTicks() == 0) {
            // live j0016 2026-10-25: a take-off that ended at once; its reason went unlogged under the next flight's
            ZenithTask.LOG.info("Elytra flight {} of the leg {} ended without a boost after {} ticks ({}, {} rocket{} used):"
                    + " {}{}; no further take-off this leg", flights, legKind, c0.ticks(), c0.phase(), hopRockets,
                hopRockets == 1 ? "" : "s", flightWhy != null ? flightWhy : "the rocket never boosted",
                rocketWhy != null ? " (" + rocketWhy + ")" : "");
            // the same take-off again from about the same place fails the same way (live 2026-10-25: 4 in 5 s through
            // build.elytraHops, each "landed" from its jump): this leg walks on
            noMoreFlights = true;
        }
        countFlown();
        landedHere = ctl != null && ctl.phase() == FlightControl.Phase.LANDED;
        restOffR = Math.hypot(BOT.getX() - plan.profile().restX, BOT.getZ() - plan.profile().restZ);
        countBoost();
        MapArtConfig.BuildCfg b = host.build();
        // build.elytraHops: on toward the goal with a further flight, the elytra still on (the kill switch stops it)
        if (landedHere && !noMoreFlights && b.elytraLegs && hopsAllowed(b) > 0 && hop(now, b)) return;
        FlightGear.Mode mode = FlightGear.Mode.of(b.elytraGear);
        boolean mendWanted = Wear.fraction(chest()) * 100 < b.armorMendBelowPercent;
        // a long hop waiting for the walk's route (routeWait): the elytra stays on in the restock for the next flight a
        // few ticks on, as between restock legs ("flight" mode: no swap off and on again, about 35 ticks)
        boolean justLanded = !(routeWait && wayRetry && !noMoreFlights);
        if (WORN.ours && BOT.isOnGround() && FlightGear.chestBack(mode, justLanded, host.inRestock(), false, mendWanted)) {
            to(Phase.GEAR_OFF, now);
            return;
        }
        to(Phase.IDLE, now);
    }

    /** No flight: the walk goes on foot from here, with the reason in its log line. */
    private void walk(long now, String why) {
        flightWhy = why;
        walkWhy = why;
        if (phase == Phase.FLY) flyEnd = now;
        MapArtConfig.BuildCfg b = host.build();
        boolean mendWanted = Wear.fraction(chest()) * 100 < b.armorMendBelowPercent;
        // as after a landing: "flight" puts the chestplate back on at once, "restock" when the restock ends
        boolean back = WORN.ours && BOT.isOnGround()
            && FlightGear.chestBack(FlightGear.Mode.of(b.elytraGear), true, host.inRestock(), false, mendWanted);
        to(back ? Phase.GEAR_OFF : Phase.IDLE, now);
    }

    private void to(Phase next, long now) {
        phase = next;
        phaseStart = now;
        request = null;
    }

    /**
     * The walk ended (arrived, failed or stopped): its one "Elytra leg" line (the walks {@link #flies} lets fly).
     * A flight still in the air goes to {@link #afterEngine}.
     */
    void walkEnded(long now, boolean arrived) {
        if (aloft()) {
            orphan = this;
            if (ctl != null) ctl.endInTheAir("the walk stopped in the air");
            ZenithTask.LOG.warn("Elytra leg: the walk stopped in the air; gliding down");
        }
        phase = Phase.IDLE;
        if (!leg) return;
        leg = false;
        long total = now - legStart;
        FlightPlanner.Plan fp = flownPlan;
        if (flights == 0 || fp == null) {
            ZenithTask.LOG.info("Elytra leg {}: {} blocks walked in {} ticks ({}){}{}{}", legKind, String.format("%.0f", legBlocks),
                total, walkWhy == null ? "?" : walkWhy, wayTries == 0 ? "" : String.format("; planned again %d time%s on the"
                    + " way, the last: %s", wayTries, wayTries == 1 ? "" : "s", wayWhy == null ? "?" : wayWhy),
                legOutside ? "; outside the restock" : "", arrived ? "" : "; the task ended the walk");
            return;
        }
        long air = airBefore + (flyEnd < 0 ? now : flyEnd) - flyStart;
        FlightControl k = ctl;
        if (lostInputs > 0) {
            ZenithTask.LOG.warn("Elytra leg {}: {} of {} flight ticks went with another request's input (not the flight's)",
                legKind, lostInputs, air);
        }
        int used = elytraBefore < 0 ? 0 : Math.max(0, elytraBefore - Wear.remaining(elytraNow()));
        countBoost();
        String touch = k == null || Double.isNaN(k.touchX()) ? "no touch-down" : String.format(
            "touch-down %.1f blocks before R, %.1f across, at %.2f b/t, sink %.2f; rest %.1f off R",
            fp.profile().length() - fp.profile().along(k.touchX(), k.touchZ()), fp.profile().across(k.touchX(), k.touchZ()),
            k.touchSpeed(), -k.touchVy(), restOffR);
        if (k != null) touch += stageFive(k, fp);
        if (DamageEvents.takeWall() >= 0) {
            wallHits++;
            ZenithTask.LOG.warn("Elytra flight ran into a block: the server dealt fly_into_wall damage after the touch-down"
                + " of the leg's flight ({} {})", legKind, touch);
        }
        if (wallHits > 0) touch += String.format("; fly_into_wall damage %d time%s", wallHits, wallHits == 1 ? "" : "s");
        ZenithTask.LOG.info("Elytra leg {}: {} blocks, flown {} of them: gear and rockets {} ticks ({} clicks), flight {} ticks "
                + "(take-off to still{}), {}; rockets {} (boost {} ticks), elytra -{}; walk on {} ticks; leg {} ticks{}{}{}{}{}",
            legKind, String.format("%.0f", legBlocks),
            String.format("%.0f", flownActual + (flightCounted ? 0 : flightCovered())), gearTicks,
            gearClicks, air, flights > 1 ? ", " + flights + " flights" : "", touch, rocketsUsed,
            k == null ? 0 : k.boostTicks(), used, Math.max(0, now - (flyEnd < 0 ? now : flyEnd)), total,
            flightWhy == null ? "" : " (" + flightWhy + ")", hopWhy == null ? "" : "; no further flight: " + hopWhy,
            wayLine() + (arcFlights == 0 ? "" : String.format("; %s an arc (%.0f blocks of way)",
                arcFlights == flights ? (flights == 1 ? "flown on" : "all flown on") : arcFlights + " flown on", arcAir)),
            legOutside ? "; outside the restock" : "", arrived ? "" : "; the task ended the walk");
    }

    /**
     * Stage 5's part of the leg's line ("" when none of it happened): a short landing (the brake's start, the rest off
     * T), a burn of a boost past the longest planned for, ticks on the ground under a boost, a landing given up for a
     * player, a worn elytra's check.
     */
    private String stageFive(FlightControl k, FlightPlanner.Plan fp) {
        StringBuilder sb = new StringBuilder();
        if (fp.profile().spot) {
            sb.append(String.format("; short landing: brake from flight tick %d, rest %.2f off T", k.brakeTick(), restOffR));
        } else if (k.brakeTick() >= 0) {
            sb.append(String.format("; brake from flight tick %d", k.brakeTick()));
        }
        if (!k.burnWords().isEmpty()) sb.append("; ").append(k.burnWords());
        if (k.groundBoostTicks() > 0) sb.append(String.format("; %d ticks on the ground under the boost (looking down)",
            k.groundBoostTicks()));
        if (k.playerWhy() != null) sb.append("; landing given up: ").append(k.playerWhy());
        if (checkTicks >= 0) sb.append(String.format("; the elytra already worn, checked in %d ticks", checkTicks));
        return sb.toString();
    }

    /** The flown leg's on-the-way part ({@code build.elytraOnTheWayBlocks}) for its line, or "". */
    private String wayLine() {
        if (wayFlights == 0) return "";
        String first = wayWalked < 0 ? "" : String.format("; took off on the way after %.0f blocks on foot (the start walked:"
            + " %s)", wayWalked, wayFirstWhy == null ? "?" : wayFirstWhy);
        int more = wayFlights - (wayWalked < 0 ? 0 : 1);
        return first + (more > 0 ? "; " + more + " later flight" + (more == 1 ? "" : "s") + " took off on the way" : "");
    }

    /** The boost of a one-rocket duration-2/3 flight into {@link #WATCH}, once a flight. */
    private void countBoost() {
        FlightControl k = ctl;
        if (boostCounted || legDuration < 2 || hopRockets != 1 || k == null || k.boostTicks() <= 0) return;
        boostCounted = true;
        WATCH.add(k.boostTicks(), System.currentTimeMillis());
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /** The elytra's uses left: worn, else the carried one. */
    private static @Nullable ItemStack elytraNow() {
        ItemStack worn = chest();
        if (ELYTRA.test(worn)) return worn;
        int s = Inv.find(ELYTRA);
        return s < 0 ? null : Inv.stack(s);
    }

    // ---- an elytra a player left worn: not flown while a chestplate is carried (the handoff check)

    /** Chestplates looked for, best first ({@link #chestplateSlot}). */
    private static final String[] CHESTPLATES = {"minecraft:netherite_chestplate", "minecraft:diamond_chestplate",
        "minecraft:iron_chestplate", "minecraft:golden_chestplate", "minecraft:chainmail_chestplate",
        "minecraft:leather_chestplate"};

    /** The player slot of the best chestplate carried (hotbar or main inventory), or -1. */
    static int chestplateSlot() {
        for (String id : CHESTPLATES) {
            int s = Inv.find(Inv.is(id));
            if (s >= 0) return s;
        }
        return Inv.find(st -> {
            String id = Inv.id(st);
            return id != null && id.endsWith("_chestplate");
        });
    }

    // ---- the chestplate back on (FlightGear.chestBack), also outside a flight (ZenithTask's backstop)

    /**
     * The chestplate goes back on before a task's next step ({@link FlightGear#chestBack}: the restock is over, a
     * fight or an escape): true while this tick was the swap's (the task waits, standing still). Only for an elytra
     * the plugin put on, outside a flight, with no menu open.
     */
    static boolean backstop(Host host, long now, Clicker clicker, @Nullable FlightLeg busyLeg) {
        if ((busyLeg == null || !busyLeg.busy()) && orphan == null) adoptWorn(host.build());
        if (!WORN.ours || busyLeg != null && busyLeg.busy() || orphan != null || Inv.containerOpen()) return false;
        MapArtConfig.BuildCfg b = host.build();
        SprintLegs.Walk kind = host.kind();
        boolean danger = kind == SprintLegs.Walk.CHASE || kind == SprintLegs.Walk.ESCAPE || kind == SprintLegs.Walk.RETREAT;
        boolean mendWanted = Wear.fraction(chest()) * 100 < b.armorMendBelowPercent;
        if (!FlightGear.chestBack(FlightGear.Mode.of(b.elytraGear), false, host.inRestock(), danger, mendWanted)) return false;
        if (!BOT.isOnGround() || BOT.isFallFlying()) return false;
        if (chestBackDone() && swapSettled(BACKSTOP.request)) {
            WORN.ours = false;
            if (BACKSTOP.first >= 0 && now >= BACKSTOP.first) {
                ZenithTask.LOG.info("Elytra gear: the chestplate is back on ({} ticks)", now - BACKSTOP.first);
            }
            BACKSTOP.first = -1;
            return false;
        }
        clicker.submit(now, null, null, null);
        if (BACKSTOP.request == null || BACKSTOP.request.isCompleted() && now - BACKSTOP.since > 20 || now < BACKSTOP.since) {
            List<FlightGear.Click> clicks = Inv.empty(Inv.cursor()) ? chestBackClicks() : cursorDownClicks();
            if (clicks == null) {
                WORN.ours = false;
                return false;
            }
            RequestFuture f = host.hotbar().submit(actions(clicks), now);
            if (f != null) {
                BACKSTOP.request = f;
                gearSent(f);
                if (BACKSTOP.first < 0 || now < BACKSTOP.since) BACKSTOP.first = now;
                BACKSTOP.since = now;
                ZenithTask.LOG.info("Elytra gear: the chestplate goes back on ({} clicks; {})", clicks.size(),
                    danger ? "a fight or an escape" : !host.inRestock() ? "the restock is over" : "after the flight");
            }
        }
        return true;
    }

    private static final class Pending {
        @Nullable RequestFuture request;
        long since;
        /** The task tick of the swap's first click (its log line), or -1. */
        long first = -1;
    }

    private static final Pending BACKSTOP = new Pending();

    /**
     * An elytra in the chest slot the plugin did not put on (a reload or deploy after a flight, or a player left it on)
     * is the plugin's from here when a chestplate is carried: the chestplate goes back on as after its own flights
     * (2026-10-25: after the 11:57 deploy it wore the elytra with no chest armour into a pillager fight).
     * Not for gear mode WORN (the elytra stays on by choice).
     */
    static void adoptWorn(MapArtConfig.BuildCfg b) {
        if (WORN.ours || !elytraWorn() || FlightGear.Mode.of(b.elytraGear) == FlightGear.Mode.WORN) return;
        String id = carriedChestplate();
        if (id == null) return;
        WORN.chestId = id;
        WORN.ours = true;
        ZenithTask.LOG.info("Elytra gear: an elytra the plugin did not put on is worn; the carried {} goes back on", id);
    }

    /** The first chestplate in the main inventory or the hotbar, or null. */
    private static @Nullable String carriedChestplate() {
        for (int s = ItemIds.MAIN_START; s <= ItemIds.HOTBAR_END; s++) {
            String id = Inv.id(Inv.stack(s));
            if (id != null && id.endsWith("_chestplate")) return id;
        }
        return null;
    }

    /** The chest slot holds the chestplate taken off (or is empty when there was none). */
    private static boolean chestBackDone() {
        ItemStack worn = chest();
        if (WORN.chestId == null) return Inv.empty(worn);
        return Inv.is(WORN.chestId).test(worn);
    }

    /** The clicks that put the chestplate taken off back on (the elytra going where it was), or null: nothing to do. */
    private static @Nullable List<FlightGear.Click> chestBackClicks() {
        int key = FlightGear.borrowKey(Inv.heldSlot());
        if (WORN.chestId == null) {
            // the chest slot was empty: the elytra into a free main-inventory slot (the same hotbar-key trades)
            for (int s = ItemIds.MAIN_START; s < ItemIds.HOTBAR_START; s++) {
                if (Inv.empty(Inv.stack(s))) return FlightGear.swap(s, key);
            }
            return null;
        }
        int from = Inv.find(Inv.is(WORN.chestId));
        if (from < 0) return null;
        return FlightGear.swap(from, key);
    }

    private static List<InventoryAction> actions(List<FlightGear.Click> clicks) {
        List<InventoryAction> out = new ArrayList<>();
        for (FlightGear.Click k : clicks) {
            if (k.swapKey()) out.add(new MoveToHotbarSlot(k.slot(), MoveToHotbarAction.from(k.hotbarKey())));
            else out.add(new ClickItem(0, k.slot(), ClickItemAction.LEFT_CLICK));
        }
        return out;
    }

    // ---- the gear swap's clicks: settled before anything else touches the inventory

    /** The last gear swap's inventory request (GEAR_ON, GEAR_OFF, the backstop) and the engine tick it went out on. */
    private static volatile @Nullable RequestFuture gearRequest;
    private static volatile long gearRequestAt;
    /** {@link #gearClicksPending} gives up on a request after this many engine ticks. */
    static final int GEAR_PENDING_MAX = 100;

    private static void gearSent(RequestFuture f) {
        gearRequest = f;
        gearRequestAt = engineTicks;
    }

    /**
     * A gear swap's clicks still going out (ZenithTask waits before its step). A swap from the main inventory is 3
     * hotbar-key clicks ({@link FlightGear#swap}); the swap looks done after the second, and a menu opened before the
     * third makes ZenithProxy skip it ("requested container: 0 != N"): the borrowed hotbar slot keeps the worn piece.
     * With the old pick-up swap the worn piece stayed on the cursor and the inventory went out of step (local s4e
     * 2026-10-25: a CONTAINER leg landed at its chest, the chest opened 4 ticks after the second click, the elytra was
     * gone ("no elytra carried") and the lanes stalled ("inventory busy") into BLOCKED).
     */
    static boolean gearClicksPending() {
        RequestFuture r = gearRequest;
        return r != null && !r.isCompleted() && engineTicks - gearRequestAt < GEAR_PENDING_MAX;
    }

    /** The swap is over: its request done (or none) and nothing on the cursor. */
    private static boolean swapSettled(@Nullable RequestFuture r) {
        return (r == null || r.isCompleted()) && Inv.empty(Inv.cursor());
    }

    /** A click that puts the cursor's stack down in the first empty slot of the player inventory, or null: none free. */
    private static @Nullable List<FlightGear.Click> cursorDownClicks() {
        for (int s = ItemIds.MAIN_START; s <= ItemIds.HOTBAR_END; s++) {
            if (Inv.empty(Inv.stack(s))) return List.of(new FlightGear.Click(s, -1));
        }
        return null;
    }

    /** The gear phases: something on the cursor (a swap cut short) goes down first. */
    private void cursorDown(long now) {
        List<FlightGear.Click> clicks = cursorDownClicks();
        if (clicks == null) {
            ZenithTask.LOG.warn("Elytra gear: {} on the cursor and no free slot", Inv.id(Inv.cursor()));
            if (phase == Phase.GEAR_ON) walk(now, "a stack on the cursor and no free slot");
            else to(Phase.IDLE, now);
            return;
        }
        RequestFuture f = host.hotbar().submit(actions(clicks), now);
        if (f != null) {
            ZenithTask.LOG.info("Elytra gear: {} on the cursor goes down into slot {}", Inv.id(Inv.cursor()), clicks.getFirst().slot());
            request = f;
            requestAt = now;
            gearClicks++;
            gearSent(f);
        }
    }

    private static @Nullable ItemStack chest() {
        return CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().getItemStack(FlightGear.CHEST_SLOT);
    }

    private static boolean elytraWorn() {
        return ELYTRA.test(chest());
    }

    /** An elytra in the chest slot (StrayGlideGuard). */
    static boolean elytraInChestSlot() {
        return elytraWorn();
    }

    /** A firework rocket attached to the bot exists (ZenithProxy's Bot boosts the glide each tick then). */
    static boolean boosting() {
        int me = CACHE.getPlayerCache().getEntityId();
        for (Entity e : CACHE.getEntityCache().getEntities().values()) {
            if (e.getEntityType() != EntityType.FIREWORK_ROCKET) continue;
            OptionalInt a = e.getMetadataValue(9, MetadataTypes.OPTIONAL_VARINT, OptionalInt.class);
            if (a != null && a.isPresent() && a.getAsInt() == me) return true;
        }
        return false;
    }

    /**
     * Every bot tick after the engine: a flight whose walk stopped in the air lands with its controller's bail-out (the
     * soonest, slowest safe touch-down; {@link FlightControl#DESCENT_PITCH} without a controller) until the feet are on
     * the ground. No rockets.
     */
    static void afterEngine() {
        engineTicks++;
        TakeoffGuard.SESSION.tick(BOT.isOnGround(), BOT.getX(), BOT.getZ(), System.currentTimeMillis());
        FlightLeg o = orphan;
        if (o == null) return;
        if (o.phase != Phase.IDLE && o.lastTicked == engineTicks) return; // its walk took it up again
        if (BOT.isOnGround() && !BOT.isFallFlying()) {
            orphan = null;
            ZenithTask.LOG.info("Elytra leg: down after the walk stopped in the air");
            return;
        }
        float yaw = BOT.getYaw();
        float pitch = BOT.isFallFlying() ? FlightControl.DESCENT_PITCH : FlightControl.GROUND_PITCH;
        if (o.ctl != null && o.plan != null) {
            o.inFlight(o.ctl);
            FlightControl.Keys k = o.ctl.tick(botIn(false, o.legRocket));
            yaw = k.yaw();
            pitch = k.pitch();
        }
        INPUTS.submit(InputRequest.builder().owner(ORPHAN).priority(Clicker.PRIORITY + 1).yaw(yaw).pitch(pitch).build());
    }

    // ---- the world for the planner

    static FlightPlanner.Terrain terrain(NoGoZones zones, @Nullable BorderTop border) {
        List<double[]> players = playersNear(BOT.getX(), BOT.getZ(), PLAYERS_WITHIN + 400);
        return new FlightPlanner.Terrain() {
            @Override public boolean playerNear(int x, int y, int z) {
                for (double[] f : players) {
                    if (Math.abs(f[1] - y) <= FlightControl.PLAYER_DY
                        && FlightControl.cellDist(f[0], f[2], x, z) < FlightControl.PLAYER_MARGIN) return true;
                }
                return false;
            }

            @Override public boolean loaded(int x, int z) {
                return World.isChunkLoadedBlockPos(x, z);
            }

            @Override public double top(int x, int y, int z) {
                int id = World.getBlockStateId(x, y, z);
                if (id == 0) return 0;
                double top = 0;
                for (CollisionBox cb : BLOCK_DATA.getCollisionBoxesFromBlockStateId(id)) top = Math.max(top, cb.maxY());
                return top;
            }

            @Override public double bottom(int x, int y, int z) {
                int id = World.getBlockStateId(x, y, z);
                if (id == 0) return 1;
                double bottom = 1;
                for (CollisionBox cb : BLOCK_DATA.getCollisionBoxesFromBlockStateId(id)) bottom = Math.min(bottom, cb.minY());
                return bottom;
            }

            @Override public boolean fluid(int x, int y, int z) {
                int id = World.getBlockStateId(x, y, z);
                if (id == 0) return false;
                if (World.getFluidState(id) != null) return true;
                return "true".equals(BlockStates.spec(id).props().get("waterlogged"));
            }

            @Override public boolean forbidden(int x, int y, int z) {
                return !zones.isEmpty() && zones.at(new Pos(x, y, z)) != null;
            }

            @Override public boolean noLanding(int x, int y, int z) {
                return border != null && border.contains(x, y, z);
            }
        };
    }
}
