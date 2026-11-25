package io.github.digitalisp.mapart.config;

import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * Plugin configuration, persisted by ZenithProxy as plugins/config/mapart.json (Gson).
 * All fields public and mutable. Keep this file free of polymorphism: a config that fails to parse
 * shuts ZenithProxy down. Large or fast-changing state (jobs, checkpoints, stock) lives in the
 * JobStore directory instead. CONTRACT (lead-owned) — add fields only via the lead.
 */
public class MapArtConfig {
    /** Builder module on/off. When off, intake still queues jobs. */
    public boolean enabled = false;
    /** Soft pause (persisted): engine finishes nothing new, releases inputs, waits for resume. */
    public boolean paused = false;
    /** Why the engine is paused (shown by status, kept across restarts; "SAFETY STOP: ..." after a safety halt). */
    public String pauseReason = "";
    /** Directory for jobs, checkpoints, stock index (relative to the ZenithProxy working dir). */
    public String dataDir = "plugins/mapart";

    public final DiscordCfg discord = new DiscordCfg();
    public final BuildCfg build = new BuildCfg();
    public final MapCfg map = new MapCfg();
    public final SiteCfg site = new SiteCfg();
    public final SafetyCfg safety = new SafetyCfg();
    public final CombatCfg combat = new CombatCfg();
    /**
     * Teammates: players that are never a threat. A teammate is never a fight target, never counted as an attacker or
     * a threat (self-defence, the health hold's retreat), even with {@code player} among {@code combat.mobs}, and while
     * the plugin's fight has KillAura on, players are not targeted while a teammate that KillAura's own friend list does
     * not spare is near. Each entry is a UUID (with or without dashes) or a player name (any case)
     * ({@code coord.Teammates}). Empty (default): nobody is a teammate.
     */
    public List<String> teammates = new ArrayList<>();

    /**
     * Fighting hostile mobs on a platform (the owner 2026-10-12: "the soulsand platform will occasionally spawn pillagers. so
     * it needs to be able to switch into like an attack mode and killaura the pillagers before resuming work"). On a
     * platform whose {@code fightMobs} is on (default: soul sand on, moss off), a listed mob within {@link #radius} of
     * the tile (or of the bot) stops the running task at a safe point; the build modules come back, ZenithProxy's
     * KillAura is switched on for the fight, the bot holds its best weapon above {@code build.toolSafetyFloor} (sword,
     * then axe, else a bare hand) and walks within reach of a mob that stays out of reach; once no listed mob has been
     * in the area for {@link #quietSeconds} the modules are suppressed again and the task carries on. Never during the
     * clear (the bot stays on the centre structure there).
     */
    public static class CombatCfg {
        /** Global kill switch (each platform has its own {@code fightMobs}). */
        public boolean enabled = true;
        /** Entity types fought (namespace optional). */
        public List<String> mobs = new ArrayList<>(List.of(
            "pillager", "vindicator", "ravager", "evoker", "witch", "illusioner", "zombie"));
        /** Blocks around the tile (x and z) and around the bot in which a listed mob starts and keeps a fight. */
        public int radius = 16;
        /**
         * Fight back anywhere: while a listed mob hits the bot (the server's damage events: its blow or its arrow; a
         * fall, a wall, fire or a player is no such hit), a listed mob within {@link #radius} of it starts a fight on
         * every platform ({@code fightMobs} off too) and away from the tile (storage, walkways), in the phases that fight;
         * the mobs that hit it first. Live 2026-10-12: two pillagers shot the bot dead on the moss walkway during a stock
         * scan (moss fights nothing).
         */
        public boolean selfDefense = true;
        /**
         * Creepers are fought by hit and run (the owner 2026-10-14: "strike and back off, repeat
         * until dead"): creeper joins the fought mobs only through this mode. The bot walks up, lands one hit with a full
         * attack (ZenithProxy's KillAura is allowed to attack only while the creeper is in reach), at once walks away to
         * more than {@link #creeperRetreatBlocks} from it (a swelling creeper stays put until it is 7 away or out of
         * sight; its fuse is 30 ticks), stays there {@link #creeperWaitSeconds} and goes again. Within 3 blocks with no
         * full attack ready, or with a fuse running that is too far on, it backs off first. While a creeper is within 7
         * blocks nothing else is fought. Off: creepers are ignored as before.
         */
        public boolean creeperHitAndRun = true;
        /** Hit and run: the distance (blocks) the bot walks away to after a hit; above vanilla's 7 where a creeper stops swelling. */
        public double creeperRetreatBlocks = 7.5;
        /** Hit and run: seconds the bot stays out there before it goes again (the swell decays one a tick). */
        public double creeperWaitSeconds = 2.0;
        /** The fight ends once no listed mob has been in the area this long. */
        public int quietSeconds = 3;
        /** A fight still going after this long ends with one alert; the mobs then left are ignored for cooldown. */
        public int maxSeconds = 120;
        /** After a fight that ran out of time, mobs do not start another for this long. */
        public int cooldownSeconds = 60;
    }

    public static class DiscordCfg {
        public boolean enabled = false;
        /** Separate bot token for the plugin's own Discord client (not ZenithProxy's bot). */
        public String token = "";
        /** The mapart channel: uploads + commands + reports. Anyone who can post there may submit. */
        public String channelId = "";
        public String commandPrefix = "!map";
        /** Mention text (e.g. "<@&roleId>" or "<@userId>") prepended to alerts that need a human. Empty = none. */
        public String alertMention = "";
        /**
         * Verbose channel posts. Off (default): routine steps (back-offs, frequent setbacks, tile built, map locked,
         * clearing, next map) and the periodic progress card go to the log only; alerts, job started/done/cancelled,
         * pause/resume, waits that may need a human and one "Map done" post per stored map are always posted. The
         * bot's presence is not affected. {@code !map verbose on|off} (or {@code mapart verbose}) switches it live.
         */
        public boolean verbose = false;
        public int progressIntervalMinutes = 15;
        public int maxAttachmentBytes = 25 * 1024 * 1024;
        /** Coordinates in Discord messages: REAL, or RELATIVE (to the first enabled platform's map centre, Y unchanged). */
        public String coordinates = "REAL";
        /** Show what the builder is doing (step, map, progress %) as the bot's Discord custom status. */
        public boolean presence = true;
        /** Minimum seconds between presence updates (Discord rate-limits them); values below 15 count as 15. */
        public int presenceIntervalSeconds = 15;
    }

    public static class BuildCfg {
        /**
         * Max eye→target distance for placing/breaking (LOOK clicks, containers, breaks). The 2b2t printer meta's
         * "range 4.5 - 4.3": 4.5 is the most (vanilla survival reach, Grim's FarPlace limit), 4.3 what is left while
         * walking. Lane LOOK placement uses min(reach, 4.5 − last step − 0.05) with {@link #reachStepMargin}
         * (about 4.23 walking, 4.45 standing), else min(reach, 4.45).
         */
        public double reach = 4.5;
        /**
         * Lane placement: also keep the walking step of the last tick below the range, i.e. LOOK reach is
         * min(reach, 4.5 − last step − 0.05) and AIRPLACE reach airPlaceRange − last step − 0.05; false = the range
         * − 0.05. Grim 2.3.73's source checks a placement from the click-time position, which would make the step
         * term unnecessary (to verify live).
         */
        public boolean reachStepMargin = true;
        /**
         * How lane blocks are placed. "AIRPLACE" (default; the 2b2t printer
         * meta, printerAirPlace true): per block, in one tick, swap the hotbar item into the offhand, use-item-on the
         * target cell itself (face UP, cursor at its centre, inside), swing the offhand and swap back. No rotation and
         * no line of sight; blocks whose placed state would follow the look (lichen, resin clump, lanterns, pointed
         * dripstone, coral fans, torches) are still placed by LOOK. "LOOK": aim at the top face of the block below and
         * click it (pipelined), every block. Unknown values count as LOOK.
         */
        public String placeMode = "AIRPLACE";
        /** AIRPLACE: max eye → target cell centre distance (the printer's printerAirPlaceRange). */
        public double airPlaceRange = 5.0;
        /**
         * AIRPLACE lane half width (lane = 2w+1 columns). -1 (default) = the widest whose outer cells stay within
         * airPlaceRange of the eye on the lane line with a walking margin: 4 (9-wide lanes) at 5.0. LOOK lanes use
         * {@link #laneHalfWidth}.
         */
        public int airPlaceLaneHalfWidth = -1;
        /**
         * Placements that need a look (LOOK mode, and LOOK blocks in AIRPLACE mode): true = turn the bot's real
         * rotation to the target (sent with ZenithProxy's movement packet, as before); false (default, the printer's
         * printerRotatePlayer false) = keep the real rotation and send the look silently, see {@link #grimRotate}.
         */
        public boolean rotatePlayer = false;
        /**
         * With rotatePlayer false (the printer's printerGrimRotate, default true): the look is an extra PlayerInput +
         * MovePlayerPosRot (same position, target rotation) sent before ZenithProxy's movement packet, which then
         * re-sends the real rotation in the same tick; the click follows on the next tick. false (with rotatePlayer
         * false) = real rotation as with rotatePlayer true.
         */
        public boolean grimRotate = true;
        /**
         * Lane placement: request the next target's rotation in the tick that clicks the current one (up to one
         * placement per tick). false = the previous cadence: aim on one tick, click on the next with the rotation
         * unchanged, two ticks per placement. A fallback if an anti-cheat flags the turn right after a click.
         */
        public boolean pipelinePlacement = true;
        /**
         * Adaptive placement interval in ticks (1 = a click every tick, pipelined): start, fastest, slowest. A click the
         * server rejected doubles it (once per event: rejections of clicks already in flight do not double it again),
         * one with no answer within the confirm window adds a tick, {@link #placeRecoverConfirms} successes halve it.
         * 2b2t takes 1 (live replays: no rate limit at one click per tick; every rejection was a used-up stack).
         */
        public int placeIntervalStartTicks = 2;
        public int placeIntervalMinTicks = 1;
        public int placeIntervalMaxTicks = 8;
        /**
         * Successes in a row (of clicks sent at the current interval) before the interval halves, rounded up. At least
         * the clicks one ack round trip holds are waited for. The old pacer took 32 per tick of speed-up.
         */
        public int placeRecoverConfirms = 16;
        /**
         * How failed clicks move the interval. "BURST" (default): a click rejected
         * with its stack used up by the clicks in flight (a stack end) never slows it down and its cell is retried at
         * once without using up a send; any other rejection slows down (doubles) only when a second one comes within
         * {@link #placeBurstWindowTicks} (one isolated refusal changes nothing); a click the server's click limiter
         * dropped doubles as before; {@link #placeBurstRecoverConfirms} successes halve it. On 2b2t (live replays
         * 2026-10-11, 14,537 clicks at up to 20/s) refusals away from stack ends and setbacks were isolated, about one
         * per 1,000 clicks. "LEGACY": every rejection or drop doubles, {@link #placeRecoverConfirms} successes halve it
         * (the kill switch; with {@link #placeStackSettleTicks} 2 and {@link #placeStackSwitch} false it is the
         * behaviour before the burst pacer). Unknown values count as BURST.
         */
        public String placePacer = "BURST";
        /** BURST: two refusal events (not stack ends) within this many ticks are a burst: the interval doubles. */
        public int placeBurstWindowTicks = 100;
        /** BURST: successes in a row before the interval halves (LEGACY: {@link #placeRecoverConfirms}). */
        public int placeBurstRecoverConfirms = 8;
        /**
         * A click stops counting against its hotbar stack (the used-up-stack wait) this many ticks after its ack: the
         * slot update that shows it arrives with the ack (2b2t: the same server tick). 2 = the previous rule; every
         * extra tick makes the stack look used up a tick longer near its end (live: 2-3 tick stalls at counts 4-15).
         */
        public int placeStackSettleTicks = 1;
        /**
         * When the held stack is used up by clicks in flight and another hotbar stack of the same item has items left,
         * the next click goes from that one (a hotbar select in the click tick) instead of waiting about one round
         * trip for the acks. false = wait, as before.
         */
        public boolean placeStackSwitch = true;
        /**
         * A lane's move from the main inventory into the hotbar replaces the lane material stack needed last by the
         * cells still to place (Belady's rule: fewest moves); the selected slot still last. false = round robin, as
         * before.
         */
        public boolean placeHotbarBelady = true;
        /** Pre-lane/restock layout: nearest lane materials, then second stacks of the busiest items; false skips it. */
        public boolean placeHotbarStacks = true;
        /** One offhand swap pair around a tick's airplaces; false uses one pair per click. */
        public boolean placeAirPlaceBatch = true;
        /**
         * Deadline-paced lane walk (AIRPLACE lanes walked forward, not over a walled line): the walk keeps moving at
         * the speed the click queue allows, each queued cell clicked before it
         * leaves reach, instead of stopping for every cell behind it: full walking speed while the queue keeps up,
         * a stop when it does not; it still stops for an inventory move, a LOOK cell behind, and a cell in flight or
         * waiting to retry close to leaving reach. Same packets: one movement packet per tick, walking keys, no
         * sprint. false (default until a supervised live trial) = stop and go, as before.
         */
        public boolean pacedLaneWalk = false;
        /** Bounded steps back for cells just passed out of reach. */
        public boolean laneStepBack = true;
        /**
         * A lane step back neither clicks into nor walks into a cell its box may still go back over before the proxy
         * shows that cell's block (clear return path). Live 2026-10-23 (soul
         * sand): 8 of 10 setback bursts began with the server's own teleport, 5 of them within 1 s of a step back's
         * start (the box backs into a carpet clicked 0.05 behind it whose block update has not arrived), then 4 Grim
         * setbacks. Changes when keys and clicks go out (the walk back waits; fewer clicks while backing), so false
         * (default) until the owner's go = as before.
         */
        public boolean laneStepBackClearPath = false;
        /**
         * The forward twin of {@link #laneStepBackClearPath} (forward clear
         * path): a cell beside the walking line goes up only where the box will not reach it while its answer is on
         * the way (the box swept forward by the walk's speed over the answer time, at most 1.5 blocks; such cells are
         * deferred, placed once passed), and the walk holds its keys for a tick in which the next move would take the
         * box into a side cell whose block the proxy does not show yet. Live 2026-10-27 (soul sand): riding 0.2-0.25
         * off the line the box pokes 0.01-0.06 into the side column, and a carpet clicked just ahead went up 1-56 ms
         * before the move that entered its cell (Paper's isPlayerCollidingWithAnythingNew teleport, then 5 Grim
         * setbacks in 0.3 s and a 10 s back-off). True (default); false = as before.
         */
        public boolean laneForwardClearPath = true;
        /**
         * In-reach greedy (AIRPLACE mode): besides
         * its own cells, a lane run airplaces any cell of the load's later lanes that is in reach and whose item is in
         * the hotbar, in a tick without a click for its own cells, and while Baritone walks to the lane start (a lane
         * change, after a restock) or back to it after a stop it airplaces those and the lane's own cells off its
         * walking line. Never a full block on or beside a later lane's walking line, never one within a cell of the
         * bot or of Baritone's path, never more of an item than the load can spare, no inventory move for it, no click
         * while the bot sprints. Same packets as the walk. false (default until a supervised live trial) = each lane
         * places only its own cells.
         */
        public boolean placeInReachGreedy = false;
        /**
         * Fast placement (a research prototype, off until a supervised live trial with the owner's
         * go): in a lane walk (AIRPLACE mode) up to this many airplaces in one tick, all from the held hotbar slot (no
         * slot change after a click in the tick), only blocks that neither stand in the way nor hide anything
         * (carpets, pressure plates), with one swap pair around the tick's use/swing pairs when placeAirPlaceBatch
         * is enabled, otherwise the usual swap/use/swing/swap per click; the first click of the tick is the usual one. Only while the pacer is at one tick. 1 (default) = one click per tick, as before: the kill
         * switch. Above 1 every lane click also keeps to {@link #placeClickWindowMax} in any
         * {@link #placeClickWindowTicks} ticks (Paper's click limiter). Anti-cheat: one tick with two or more places
         * is what Grim's MultiPlace looks for (an experimental check upstream; 2b2t's config unknown).
         */
        public int placeClicksPerTick = 1;
        /**
         * Fast placement: the ticks of the click window. Paper drops every use-item and use-item-on packet after the
         * 9th within {@code spam-limiter.incoming-packet-threshold} (300 ms, 6 ticks) of the first one; with up to 50 ms
         * of jitter such a window can hold the clicks of 7 of the bot's ticks, so 7 (default, 25.7 clicks/s at 9) never
         * meets it; 6 allows 30/s and meets it whenever jitter shifts a window.
         */
        public int placeClickWindowTicks = 7;
        /** Fast placement: clicks at most in any {@link #placeClickWindowTicks} ticks in a row (Paper: 9 per window). */
        public int placeClickWindowMax = 9;
        /** Send prepared lane airplaces between ticks on the client loop; false retains the tick window. */
        public boolean placeSubTickDispatch = false;
        /** Fixed anchored click window with sub-tick dispatch, milliseconds (Paper: 300; jitter margin: 10). */
        public int placeClickWindowMillis = 310;
        /** Adapt Paper's click window only with more than one click per tick; false keeps a fixed window. */
        public boolean placeAdaptiveWindow = true;
        /** Smallest adaptive click window, ticks. The starting window remains placeClickWindowTicks (7). */
        public int placeClickWindowTicksMin = 6;
        /** Clean shared clicks since the last window change before tightening the window by one tick. */
        public int placeClickWindowCleanClicks = 60;
        /** Widen by one tick when more than this fraction of the last 100 clicks was dropped. */
        public double placeDropRateMax = 0.02;
        /** Exact-ack refusals without a block update (excluding stack ends) that latch fast placement off. */
        public int placeLatchFails = 3;
        /** Shared-click outcomes retained by the refusal latch. */
        public int placeLatchWindow = 20;
        /** LOOK lanes: lane width = 2*laneHalfWidth+1 pixels placed while walking one line (AIRPLACE: airPlaceLaneHalfWidth). */
        public int laneHalfWidth = 2;
        /**
         * AIRPLACE lanes entered again after a restock: true
         * (default) = a lane whose walking line already holds full blocks (a lane cut at a load's end, a load that ran
         * out, a repair pass) is walked, the bot stepping up onto them and jumping across pockets, instead of placing
         * the whole rest from stand spots. false = the previous behaviour (every cell of a walled lane from stand spots).
         */
        public boolean walkWalledLanes = true;
        /**
         * A lane whose walking line would be walled by full blocks (LOOK lanes, airplace off, or a walk over the line
         * refused) is walked between the full blocks at the line's ends, those at or
         * before its first row of cells and at or after its last, instead of placing every cell from stand spots. A lane
         * cut by the restock planner and re-planned from the world ends, or starts, in the first pass's last row: live
         * j0002 2026-10-12, airplace off, 2,232 cells of 3 tiles went to stand spots at 4 b/s because of that one block.
         * true (default) = trim; false = the previous behaviour (the kill switch).
         */
        public boolean trimLaneEnds = true;
        /**
         * A line still walled after trimming, whose blocks cannot be walked over, walks the open segment serving the
         * most remaining cells (at least 16 and a quarter of the lane); the rest goes to stand spots.
         * false = every cell from stand spots (the kill switch).
         */
        public boolean laneOpenSegment = true;
        /**
         * A lane walk ending in front of a wall on its line (a line end, an open segment) lets go of its keys early
         * enough, and brakes, that its slide never takes the box past the end cell.
         * Live 2026-10-20, Abaddon [3,4]: at Soul Speed the bot slid 0.6 blocks on into a pointed dripstone's cell and
         * no walk could start from there (BLOCKED). true (default) = on; false = the previous behaviour (the kill switch).
         */
        public boolean laneWallBrake = true;
        /**
         * A walk whose feet stand in a cell ZenithProxy's pathfinder neither walks through nor onto (pointed dripstone:
         * the box goes into its cell) first steps with the bot's own keys into the nearest neighbour cell it can stand
         * in, then asks for a path. Live 2026-10-20, Abaddon [3,4]: every path from a
         * dripstone's cell pushed into the column until VERIFY BLOCKED. true (default) = on; false = the previous
         * behaviour (the kill switch).
         */
        public boolean walkStepOut = true;
        /**
         * BUILD: when the walk to a lane's start fails, the fewest placed walls between the bot and the start that the
         * pathfinder can neither walk through nor onto (pointed dripstone, decorated pots: only the platform's
         * clearableBlocks, never a protected block or region, the tool floor holds) are broken, one log line names
         * them, and the lane starts again; VERIFY places them again. Live
         * 2026-10-12, ellen [0,6]: BUILD blocked three times in ten minutes in a dripstone field. true (default) = on;
         * false = the previous behaviour (the kill switch).
         */
        public boolean laneBreakOut = true;
        /** Recover failed walks through art walls; at most six blocks per walk. */
        public boolean dripstoneBreakThrough = true;
        /**
         * The last resort of a walk that failed (also after the break-through): once more with ZenithProxy's pathfinder
         * allowed to break, at a cost that keeps it to where no walk exists ({@code bot.impl.BreakGuard}); once per task,
         * never for the map station. Live 2026-10-13, remu [2,2]: sealed in by pointed dripstone, BLOCKED for an hour.
         * false = the walk fails as before (the kill switch).
         */
        public boolean baritoneBreakEscape = true;
        /**
         * A lane whose start stays out of reach after {@link #laneBreakOut} and {@link #baritoneBreakEscape} is placed
         * from stand spots, every cell, once per task, instead of failing toward BLOCKED (live 2026-10-23, j0015 [4,1]
         * lane 14: the tile's corner cell beside the east trench is entered only by a step down toward the trench,
         * which the edge check refuses). false = the task fails as before (the kill switch).
         */
        public boolean laneStartSpots = true;
        /**
         * AIRPLACE lanes: the cells behind a lane's start that the start does not reach are placed from stand spots
         * before the walk, not after it. A walking line clamped off the tile's edge (lethal-edge and no-go boxes on a
         * colour-suppression layer, a wall or trench on other platforms) starts up to three rows in; the edge row's
         * outer columns are then never in reach of the line, the walk "passed them out of reach", and the bot came
         * back for them from wherever the lane ended, to a last-resort spot on top of the blocks it had placed (live
         * colour-suppression prints 2026-11-13/14: 23 of 78 lane runs, 13 s each).
         * Same clicks and looks as any stand spot. false (default) = as before.
         */
        public boolean laneStartTail = false;
        /**
         * The walk to a lane start ends on the art layer, never on the wall along the tile's edge. Live 2026-10-23/24,
         * soul sand, lanes starting on the tile's south row: Baritone walked on top of
         * the glass wall two blocks high south of that row (soul sand costs it twice as much to walk on) and its goals
         * (feet within 2 blocks of the art layer) took the wall's top edge above the start cell as arrived; the lane's
         * arrival test (feet within 1.5) did not, the break walk "arrived" at once, and the run started again failed at
         * once with "no walking goal left" (the walker's goal list from the failed walk): the whole lane from stand
         * spots (j0016 [2,0] lane 5, 1,152 cells in about 5 min). With this on: the lane-start goals and the break walk's
         * take the feet at most one block above the art layer, the cells on top of the tile's border look far to
         * Baritone (as a no-go zone's do) for those walks, and the walker forgets its goal list before the lane runs
         * again. false (default) = as before.
         */
        public boolean laneStartOnFloor = false;
        /**
         * Lanes never wall the bot in with blocks the pathfinder can neither walk through nor onto (pointed dripstone,
         * decorated pots): such a block does not go up while it would leave the bot's
         * floor area closed (it is placed later from outside, or from a stand spot), the walk does not step onto one,
         * and the bot does not climb back onto one at a lane end. Live 2026-10-12 10:09, ellen [0,6]: lane 8 ended in a
         * dripstone field with dripstone on every side, the next lane start unreachable, BLOCKED. true (default) = on;
         * false = the previous behaviour (the kill switch).
         */
        public boolean laneWayOut = true;
        /**
         * Colour suppression: a lane end the run-time clamp cannot move to a cell clear of every zone and zone neighbour
         * (the last column x 63: its east neighbour lies in the machinery zone, so no cell beside a single-cell lane at
         * the south edge qualifies) moves to the nearest cell that is itself in no zone, instead of staying where it is
         * (the walk to it fails: BLOCKED "lies inside no-go zone"; local CS run 2026-10-14, lane 14's last cell at the
         * south-east corner). true (default) = on; false = the previous behaviour (the kill switch).
         */
        public boolean suppressionLaneEndFallback = true;
        /**
         * Colour suppression: the lazy leftovers drop ({@code build.lazyDrop}) is off for a pass: materials the pass does
         * not use go back at once. The gain of keeping them is judged on the pass alone, and a pass of a few cells (a
         * noobline row) saves nothing by dropping; the 13 stacks then stayed and crowded the next pass, whose main item
         * (moss, 1,800 cells) got one stack: loads of 98 cells for 15 minutes (local CS run 2026-10-14, top layer).
         * true (default) = on; false = the previous behaviour (the kill switch).
         */
        public boolean suppressionDropLeftovers = true;
        /**
         * Colour suppression: worn boots below 95% are mended before the ride (XP bottles from the kit, thrown at the top
         * layer's centre), not by RENAME's trip from the booth to the XP chests by the supply column (about 200 blocks and
         * the whole stair each way; local CS run 2026-10-14). true (default) = on; false = the previous behaviour.
         */
        public boolean suppressionMendBeforeRide = true;

        /**
         * Colour suppression: RESTOCK drops the cells already placed (and the lanes that leave empty) from the cached lane
         * plan when the platform's chunks are not all in view. true (default) = on; false = the previous behaviour.
         */
        public boolean suppressionFreshLanes = true;
        /**
         * Colour suppression: a level's noobline row is placed by the lanes of that level's main pass.
         * The main pass is built before its noobline pass, its lanes reach one row
         * north and place the row's cells from their north ends; the noobline pass that follows finds its cells in
         * place and is only verified (cells a lane left are built by it as before). Saves the row's own restock round
         * and lane trip, four times a map. The stored job is unchanged (the order is decided when a pass is chosen), so
         * queued and started jobs follow the switch as it is then. Not for the job's last pass (the ride follows it),
         * and not for a level whose main pass does not place every item of its noobline row (the bottom layer of the
         * exports seen so far: a row of moss, no moss in the map): every load of the main pass would carry that item
         * for a few cells, two restock rounds more than the merge saves in the local 10x run of 2026-11-14; such a
         * level keeps its stored order. false (default) = every pass in its stored order, as before.
         */
        public boolean suppressionMergeNoobline = false;
        /**
         * Colour suppression: both levels of a layer in one sweep (docs/COLOR_SUPPRESSION.md). The lanes of a layer's
         * level-0 main pass, feet on the glass, also place the cells of the
         * layer's level-1 main pass (one block higher, airplaced; no column holds both levels) and, with
         * {@code suppressionMergeNoobline}, its level-1 noobline row when the sweep places every item of that row. The
         * lane placer treats a raised block as a wall at head height nobody stands on: placed only once the walk is
         * clear of it, never climbed, never a stand spot, a line holding one is not walked over (LaneRun "Raised
         * cells"). The level-1 passes that follow find their cells in place and are only verified (cells a lane left are
         * built by them as before); the stored job and its pass order are unchanged, so the ride still follows the last
         * pass. Needs AIRPLACE mode; a raised cell is skipped while airplace is not available. false (default) = each
         * level its own sweep, as before.
         */
        public boolean suppressionMergeLevels = false;
        /**
         * Lanes never shut a cell still to place into a small closed area of blocks the pathfinder can neither walk
         * through nor onto: such a block waits while it would close the last way
         * in to a cell of the run still to place (that cell goes first), stand spots are never on top of one, and they
         * do not count as walls of a floor stand spot (a slot between them is no pocket for the bot). Live 2026-10-13
         * 00:42, ellen [1,6]: stand-spot placements closed a 2-cell pocket in a dripstone field around an unplaced
         * dripstone cell, which no stand spot could reach afterwards (VERIFY BLOCKED). true (default) = on; false = the
         * previous behaviour (the kill switch).
         */
        public boolean laneKeepReachable = true;
        /**
         * VERIFY: a missing cell no stand spot of which the pathfinder can reach, because blocks it can neither walk
         * through nor onto wall it in, gets a corridor: the fewest correct art blocks are broken (at most 4, the usual
         * tool rules), the cell is placed from inside, and the broken blocks again from the bot's side, the far end
         * first, so the corridor closes behind the bot. Live
         * 2026-10-13 01:03, ellen [1,6]: one dripstone hole in a field of placed dripstone, BLOCKED after 4 passes.
         * true (default) = on; false = the previous behaviour (the kill switch).
         */
        public boolean verifyBreakIn = true;
        /**
         * VERIFY: refill enclosed pockets and corridors in one walk, farthest from the normal approach first; a bot
         * sealed in first breaks the cheapest way out and restores it too.
         * Live 2026-10-13, remu [0,2]: 41 dripstone holes, 0 wrong, BLOCKED after 4 repair passes.
         * true (default) = on; false = per-cell repair lanes (the kill switch).
         */
        public boolean verifySealOut = true;
        /**
         * VERIFY: cells that only wait for stock never use up repair passes. When every cell left waits for an item
         * short in stock the pass count starts again from 0 (the stock wait), and
         * the BLOCKED line counts them apart. Live 2026-10-13 01:03, ellen [1,6]: "0 wrong, 123 missing" with 122 of them
         * waiting for cyan terracotta. true (default) = on; false = the previous behaviour (the kill switch).
         */
        public boolean verifyStockWaitFree = true;
        /**
         * Hotbar moves (an item from the main inventory into the hotbar): the
         * server answers each with the whole inventory (a state id it does not have), and the task moves nothing until that answer shows the moved item in its slot (40 ticks at most). Until then server updates
         * sent before the move was handled still put the slot's old item back into ZenithProxy's cache: live 2b2t
         * 2026-10-12, every "placed as" cell came within 0.5 s of a move. true (default) = on; false = the previous
         * behaviour (the kill switch).
         */
        public boolean hotbarMoveFence = true;
        /** Lane clicks may use other hotbar slots while the move fence waits; requests still block all clicks. */
        public boolean placeDuringMoveFence = true;
        /** Prefetch the next missing lane material into an unused slot during an existing paced idle standing tick. */
        public boolean placeHotbarPrefetch = true;
        /**
         * Standing idle gaps: prefetch shortages in the next 40 lane cells, including a second stack before the
         * current one runs out. Requires hotbarMoveFence, placeDuringMoveFence and placeStackSwitch. Never moves
         * while walking or clicks the moved slot before its server answer. After 40 ticks without the expected item,
         * request a full inventory; after 100 more ticks release the inventory hold and keep the slot unknown to
         * placement until a report ({@link #unknownSlotResync} asks for one, {@link #unknownSlotTrustTicks} bounds the
         * wait). Inventory/food/offhand tasks and disabling this path clear the hold immediately.
         * Sub-tick lookahead additionally bounds rolling click windows ({@link #placeRollingClickWindow}). false retains
         * the retired-slot prefetch.
         */
        public boolean placeHotbarLookahead = false;
        /**
         * With {@link #placeHotbarLookahead} and sub-tick dispatch: every rolling {@code placeClickWindowMillis} holds at
         * most {@code placeClickWindowMax} clicks, on top of the anchored window (lookahead's speculative gaps can shift
         * Paper's anchor ahead of ours). Live 2026-10-14 (soul sand, 3 clicks per tick): drops 1-3 % -> 0.0 %,
         * but the walked print rate fell from 27.7 to 24.3 blocks/s, the window holding clicks back in 514 of 927 walk
         * ticks of a 1152-cell lane. false = the anchored window alone, as without lookahead.
         */
        public boolean placeRollingClickWindow = true;
        /**
         * With placeHotbarLookahead, allow eviction of a future lane material needed beyond the 40-cell horizon;
         * choose the farthest next use. Reachable stacks, unsettled clicks, food, tools and totems remain protected.
         * false limits lookahead prefetch to empty/non-material/retired slots.
         */
        public boolean placeHotbarLookaheadEvict = false;
        /**
         * Hotbar slots left unknown by a lookahead move that got no answer ask for the server's full inventory: a lane
         * sends the recovery's no-op click (a still
         * tick, no request in flight, not within 50 ms of a placement) at most once per 40 ticks until a report names
         * them, and a lane whose item is only in unknown slots holds for that answer instead of skipping its cells as
         * "missing item". Live 2026-10-15: unknown slots waited for a report nothing asked for. false = the slots wait
         * for a report, as before (the kill switch).
         */
        public boolean unknownSlotResync = true;
        /**
         * Ticks a hotbar slot stays unknown with no server report before the inventory cache is trusted for it again
         * (one log line); 0 = never: it waits for a report, as before.
         */
        public int unknownSlotTrustTicks = 100;
        /**
         * The lane watchdog: a lane task with no placement,
         * click, hotbar change or movement for this many ticks logs one line with what holds it and ends, its cells left
         * for the next round (the engine re-plans: RESTOCK when the load ran out). Live 2026-10-15: a lane stood still
         * for 11 minutes without a log line. 0 = off.
         */
        public int laneWatchdogTicks = 200;
        /**
         * The item the offhand holds while the bot builds in AIRPLACE mode: the
         * airplace guard adopts only this item or a totem as the offhand's item. Blank = the totem.
         */
        public String offhandItem = "minecraft:totem_of_undying";
        /**
         * AIRPLACE mode: the offhand holding anything but {@link #offhandItem} or a totem (live j0002 2026-10-12: clay,
         * after a corrective swap acted on a stale cache; airplace stayed off for three tiles) is never adopted; while
         * the inventory holds the item, the engine puts it back into the offhand at the next safe point (the running task
         * parks; standing still, one container click). The Discord alerts (airplace off, the offhand without its item)
         * are on either way. false = the previous behaviour (any stable offhand item is adopted, nothing moved).
         */
        public boolean offhandRepair = true;
        /**
         * LOOK cells whose state comes from the look alone (pointed dripstone, lanterns, candles): when the
         * top face of the block below is hidden or out of reach (a 1×1 hole
         * between full blocks, live 2026-10-12), click the side face of a full neighbour that looks at the cell instead,
         * the look pitched down, rotation sent before the click as for any LOOK click. false = top faces only, as before
         * (the kill switch).
         */
        public boolean lookSideFaces = true;
        /**
         * Confirm window, minimum and initial, in ticks: a click with neither its block nor the server's ack of its
         * sequence by then is "unknown" (the pacer slows by a tick). A click the server acked without the block is
         * "rejected" at once (1-2 round trips), whatever the window. The window widens to the highest recent latency
         * + 10 ticks when a lag spike delivers placements late (2b2t live: ack round trip p50 ~3 ticks, p99 8-22).
         */
        public int placeConfirmTicks = 40;
        /** Cap of the confirm window in ticks; with acks, an unanswered click is given up (retried) only after this. */
        public int placeConfirmMaxTicks = 200;
        /** Verify/repair passes before giving up on a tile. */
        public int maxRepairPasses = 4;
        /** Hotbar/inventory slots kept free of materials (tools, food, map, panes). */
        public int reservedSlots = 5;
        /**
         * How each restock load is chosen. "SPLIT": the
         * split DP over the rest of the tile, cutting lanes at any cell, weighing walking and chest time. "LANES": the
         * same DP, but while the current lane and the next fit in one load, loads end at lane ends (the roadmap's
         * two-lane rule); when no pair fits, lanes are cut (its split-DP fallback). "AUTO": LANES for lanes up to 5 wide
         * (LOOK), SPLIT for wider ones (AIRPLACE, 9-wide: a pair needs 38-47 slots, never fits, so both are the same
         * there). "GREEDY": the previous planner (whole lanes while they fit, then the first cells of the next; BUILD
         * walks that lane to its end). Unknown values count as AUTO.
         */
        public String restockPlanner = "AUTO";
        /**
         * The split DP ({@code restockPlanner} SPLIT, LANES, AUTO) does not cut a load short on a next load that the
         * carried stacks would leave no room for: its later loads count only their own cells, so with partial stacks
         * carried for further on a load of one run looked cheapest ("the next load does the rest"), and every replan
         * planned the same one-run load again (live 2026-10-31, map "haruhi", lanes 6-7: six loads of 9 to 108 cells,
         * "planned: 3 loads left" each time, where the load as carried covered 650-780 cells). Such a load gives way
         * to the longest one that fits. false = the DP's own first load, as before.
         */
        public boolean restockCarriedGuard = true;
        /**
         * Withdrawals top up a carried partial stack (pick up a full stack, click it onto the partial one, put the rest
         * back) when that saves a slot, so an item with n cells planned takes ceil(n / 64) slots. false = whole-stack
         * shift-clicks merged into the partial stack (one slot more for most carried colours).
         */
        public boolean topUpStacks = true;
        /** GEAR wears carried boots (keepItems) into an EMPTY feet slot on every platform, so they free a slot. */
        public boolean wearBoots = true;
        /**
         * Worn boots below this durability (percent) are mended between tasks with the carried XP bottles, up to 95 %,
         * not only at the map's rename (live 2026-10-13: Soul Speed boots went from 95 % to 26 % within one soul sand
         * map). 0 = only at the rename, as before.
         */
        public int bootsMendBelowPercent = 50;
        /**
         * The empty map and the glass pane are withdrawn one each (pick up, right-click one into the inventory, put the
         * rest back) instead of a stack each, and only with the tile's last load: no slot stays taken by them.
         */
        public boolean exactMapAndPane = true;
        /**
         * Armour goes on and comes off without the cursor: GEAR's
         * boots and the durability guard's pieces move by hotbar-key trades (SWAP clicks, as the elytra gear swap since
         * 25741b8), so nothing is ever on the cursor, which the server drops when the connection ends between two
         * clicks (local run r1off 2026-10-25 lost the elytra that way). Put on: from the hotbar 1 click, from the main
         * inventory 2 through an empty hotbar slot or 3 through a borrowed one (the old way 3). Taken off: into a hotbar
         * slot 1, into the main inventory 2 through an empty hotbar slot or 3 through a borrowed one (the old way 2;
         * the 3 only with no hotbar slot empty). The hotbar ends as it was. false = the old pick-up and put-down clicks.
         */
        public boolean swapWithoutCursor = true;
        /**
         * The cartography table lock and the anvil rename move the map, and the lock its glass pane, by hotbar-key
         * trades (SWAP clicks) instead of picking them up and putting them down: the map never rides the cursor. Lock 3
         * to 7 clicks (the old way 7; a stack of panes in the main
         * inventory still goes by the cursor, only with exactMapAndPane off), rename 2 from the hotbar or 4 from the main
         * inventory (the old way 4). The map in the table's or anvil's slot 0 is still dropped by a disconnect before
         * the result is out (recoverDisconnectDrops). false = the old pick-up and put-down clicks.
         */
        public boolean mapSwapsWithoutCursor = true;
        /**
         * After a reconnect, before anything but a fight or a health hold: a valuable stack (tools, armour, the elytra,
         * maps, totems, XP bottles, rockets:
         * {@code Valuables}) that was on the cursor, or in the cartography table's or anvil's slots, when the connection
         * ended, which the server drops at the bot's feet, is picked up from within 6 blocks: one walk to each stack of
         * it on the ground, one log line. Gone, or the bot more than 6 blocks from where it dropped: one line naming
         * what was lost, and on. Materials (carpets, glass panes, anvils) are not worth the walk: a debug line.
         * false = no look.
         */
        public boolean recoverDisconnectDrops = true;
        /**
         * Centring on a stand spot (chests, buttons, single placements) counts the bot's speed: it holds still while it
         * coasts to the cell centre, presses towards the centre only while it would come to rest off it, and aims only
         * once it stands still within 0.15 of the centre (or centres again, twice at most, when it came to rest or found
         * no face in sight off the centre). false = the old centring, done as soon as the bot passes within 0.15 of the
         * centre, at speed too (live 2b2t: it then slid 0.25 to 0.6 block on, and every "no face centre in sight after
         * all" in an hour of restocks, 47 of them, came from there).
         */
        public boolean standSpotBrake = true;
        /**
         * Coast-click at containers: the bot lets go of the keys once it would slide
         * to rest on the stand spot, opens the container while it still slides, and clicks in the menu without waiting to
         * stand still (no movement keys, no sprint: Grim MultiActionsC/D). false = walk in, brake, stand still, then open.
         */
        public boolean coastClick = false;
        /**
         * Container stand spots: one from which no face centre of a container was in sight after all is not tried for
         * that container again this session (nor planned for it in a restock sweep), and the spot each container was
         * last opened from is kept in {@code dataDir/stand-spots.txt} across restarts and tried first. false = the
         * session's last good spot only, as before.
         */
        public boolean standSpotMemory = true;
        /**
         * RESTOCK gets rid of the leftovers and of the carried materials that crowd the load in one drop: the load's
         * crowded check is planned first, on the inventory as the leftovers drop leaves it (live: the second drop right
         * after the first took 1 to 6 s more, in 6 of 13 restocks). false = leftovers first, then the crowded check
         * and its own drop, as before.
         */
        public boolean mergeDrops = true;
        /**
         * Junk the bot picks up (mob drops after a fight, items on the ground by the storage) goes out with RESTOCK's
         * leftovers at the drop point, in the same drop: it takes slots the loads need (the owner, 2026-10-14). Only
         * {@link #junkItems}; never a valuable (totems, bows and crossbows, tools, armour, emeralds are none of
         * these), food, a working item or an item of a queued job. Thrown at the platform's junk spot
         * ({@link PlatformCfg#junkDropStand}), never into the sorter; no spot, no drop. false = junk stays in the
         * inventory, as before.
         */
        public boolean dropJunk = true;
        /** Blocks from the bot to {@link PlatformCfg#junkDropStand} at most for junk to be thrown there now (no long detour). */
        public int junkDropMaxDetour = 40;
        /** Item ids {@link #dropJunk} may drop (namespace optional). */
        public List<String> junkItems = new ArrayList<>(List.of("lead", "rotten_flesh", "bone", "arrow", "spectral_arrow",
            "tipped_arrow", "ominous_bottle", "string", "spider_eye", "gunpowder", "white_banner", "leather", "feather", "chicken",
            "poisonous_potato", "stick", "wheat_seeds", "slime_ball", "glass_bottle", "rabbit_hide", "sugar",
            "glowstone_dust", "iron_nugget", "crossbow", "bow"));
        /**
         * Platforms with {@link PlatformCfg#sorterRefills}: minutes the near count of a short item may stay flat while
         * RESTOCK waits for the sorter, before the item is fetched from far storage as usual.
         */
        public double sorterRefillMinutes = 3;
        /**
         * Sorter refills: an item short by more than this many cells (near chests and inventory) is fetched from far
         * storage as before; the sorter's return covers small gaps only (live 2026-10-14 01:20, remu [2,0]: ~7,000
         * white carpet short, the sorter returned about 300 per 30 s VERIFY round).
         */
        public int sorterRefillMaxCells = 640;
        /** The sorter pickup stand ends when nothing more came for this many seconds ({@link PlatformCfg#sorterPickup}). */
        public int sorterPickupStallSeconds = 8;
        /**
         * Times a pickup stand that has not been covered goes back to {@link PlatformCfg#sorterPickupPrime} and again to the
         * edge when nothing came for the stall time, before it ends as stalled (the sorter's far part unloads while the
         * bot waits at the edge; the owner, 2026-10-14). 0 = end at the first stall, as before. The walks count in the max time.
         */
        public int sorterPickupReprimes = 2;
        /**
         * While waiting at the pickup edge the stand looks at the item entities within this many blocks (the server streams
         * them within its tracking range): a wanted item that is new or moving there means items are coming, and the stall
         * exit and the re-prime wait for it (bounded by the max time). 0 = off: the stall counts only what reached the
         * inventory, as before.
         */
        public int sorterPickupWatchRadius = 24;
        /**
         * The pickup legs (to the prime cell, to the edge) walk along the row of soul sand near the straight line when one
         * is at least 80 % soul sand/soul soil on the floor: Soul Speed boots work only on it (live: 5.6 blocks/s without).
         * false = straight, as before.
         */
        public boolean soulSandRoute = true;
        /** Rows this many blocks either side of the two cells are looked at for {@link #soulSandRoute}. */
        public int soulSandRouteSpread = 4;
        /** {@link PlatformCfg#runRow}: false = Baritone walks everything, as before. */
        public boolean rowRun = true;
        /** Blocks (in z) either side of the run row within which a walk's start and goal take the row. */
        public int rowRunBand = 6;
        /** The least x distance (blocks) of a walk that takes the run row. */
        public int rowRunMinDx = 12;
        /**
         * Elytra legs (a local prototype; a live trial needs the owner's go): a
         * restock leg (to a chest's stand cell, to the drop point, back to the lane start) of {@link #elytraMinBlocks}
         * or more flies instead of walking when the planner finds a straight corridor (loaded, no water or side canal
         * under it, no no-go cell, one height band over every floor and under every roof on the way, such as the soul
         * sand platform's end rods 5 above the tile, within {@link #elytraCruise} blocks of the start's floor) and a
         * level landing pad near the goal (never a wall, a wall top or the tile's border tops): jump, start the glide,
         * a firework rocket, a low glide steered by ZenithProxy's own physics simulation, a landing at under 0.5 blocks
         * a tick of sink (no fall damage), the walk on from there. Any trouble walks instead: no elytra, no rockets, the
         * elytra at {@link #elytraMinPercent}, ZenithProxy's AutoArmor on, health under {@link #elytraMinHealth}, no
         * corridor or pad, a take-off that does not take. Every restock leg logs one "Elytra leg" line (flown or walked,
         * its blocks and ticks). false: every leg walks, as before, and nothing is logged.
         */
        public boolean elytraLegs = false;
        /**
         * {@link #elytraLegs} outside the restock, one switch a
         * leg kind, each off until tested: {@code walkTo}'s walks (between the platforms and the storage, to the nuke's
         * start, off the art layer to the clear stand, to the map's centre; never a retreat from mobs). With
         * {@link #elytraLegs} off nothing flies.
         */
        public boolean elytraWalkLegs = false;
        /** As {@link #elytraWalkLegs}: walks to a container's stand spot outside the restock (cartography table, anvil, the map store). */
        public boolean elytraContainerLegs = false;
        /** As {@link #elytraWalkLegs}: walks to a lane's start outside the restock (lane to lane). */
        public boolean elytraLaneStartLegs = false;
        /** As {@link #elytraWalkLegs}: walks to a lane's stand spot before its clicks. */
        public boolean elytraStandSpotLegs = false;
        /** As {@link #elytraWalkLegs}: walks to the map station (creating or holding the map). */
        public boolean elytraMapStationLegs = false;
        /**
         * {@link #elytraLegs}: further flights a leg may take after a landing short of its goal:
         * from where it came to rest, the same checks as a take-off, the elytra kept on between them. 0: one flight a
         * leg, the rest walks.
         */
        public int elytraHops = 0;
        /**
         * {@link #elytraLegs}: a leg that walked because its flight path or landing pad failed the planner (a wall beside
         * the take-off spot, the map counter's) plans again after each this many blocks on foot,
         * up to {@code FlightLeg.ON_THE_WAY_TRIES} times, and takes off from where the bot stops when it passes. 0: off,
         * such a leg walks to its goal.
         */
        public int elytraOnTheWayBlocks = 0;
        /**
         * {@link #elytraLegs}: water may lie under the middle of a flight: not within 8 blocks of
         * the take-off, in the landing pad or 2 before it, in runs of at most 4 blocks along the line (a canal or a
         * channel); the look-ahead never comes down on it. false: a flight over any water walks.
         */
        public boolean elytraOverWater = false;
        /**
         * {@link #elytraLegs}, duration-2 and -3 rockets: a leg too short for a straight flight (under the 90-95 blocks
         * one boost needs to burn out) or one whose straight flight the planner or the pre-flight check turns down flies
         * an arc instead: a turn inside the boost burns the
         * distance the leg has no room for, then a straight final line onto a landing pad laid along it; the same
         * corridor, band, pad and pre-flight rules as a straight flight, and never over water. From
         * {@link #elytraArcMinBlocks} blocks. false: such legs walk.
         */
        public boolean elytraArcLegs = false;
        /**
         * {@link #elytraArcLegs}: the shortest leg (blocks, feet to goal) that may fly an arc. Arcs fly safely from 40 in
         * the simulation; locally an arc leg takes 108-160 ticks with the gear and the walk on,
         * so legs under about 55 blocks saved nothing (arcs with 44-47 blocks left: -2 to -10 ticks) and arcs with 64-109
         * left saved 12-129.
         */
        public int elytraArcMinBlocks = 60;
        /** {@link #elytraLegs}: the shortest leg (blocks, feet to goal) that flies; a rocket's boost alone carries ~45. */
        public int elytraMinBlocks = 60;
        /**
         * {@link #elytraLegs}: "flight" (the elytra goes on in place of the chestplate for each flight and the chestplate
         * back on after the landing: two swaps a flight, the chestplate off only in the air; an elytra below its mend
         * threshold stays on until the restock ends, for its mend), "restock" (the elytra on from the restock's first
         * flight until the restock ends or a fight starts: two swaps a restock, about 0.9 s less a further flight, no
         * chestplate in the menus and hops between) or "worn" (the owner wears the elytra; no swaps).
         */
        public String elytraGear = "flight";
        /** {@link #elytraLegs}: no flight with the elytra at or below this % of its durability (the durability floor). */
        public int elytraMinPercent = 10;
        /** {@link #elytraLegs}: no flight under this health (half hearts). */
        public int elytraMinHealth = 14;
        /**
         * {@link #elytraLegs}: rockets a flight may use (the first one at the take-off; another when the pad is out of
         * reach without it, or when it lands as well and 1 s sooner).
         */
        public int elytraMaxRockets = 3;
        /** {@link #elytraLegs}: the highest a flight may go above the start's floor (blocks). */
        public int elytraCruise = 8;
        /**
         * {@link #elytraLegs}: ticks the glide is counted on to go on after the touch-down (the server's answer that ends
         * it: about a round trip), for the landing's slide.
         */
        public int elytraSlideTicks = 3;
        /**
         * {@link #elytraLegs}: the flight kit's rocket stock. In a RESTOCK, with
         * fewer than {@link #elytraRocketLow} flight rockets carried (plain, flight duration 1 to 3: no firework star, whose
         * explosion would hurt the bot), the bot tops them up to this many from the rocket chest
         * ({@link StorageCfg#rocketChests}, else the chest or barrel a sign saying "rocket" is on). 64 = one slot.
         */
        public int elytraRocketStock = 64;
        /** {@link #elytraLegs}: the flight-rocket count under which a RESTOCK tops them up ({@link #elytraRocketStock}). */
        public int elytraRocketLow = 16;
        /**
         * {@link #elytraLegs}: a job that keeps to its platform (a colour-suppression job with its own chests) makes the
         * kit's trip to a rocket chest that is far storage for that platform ({@link #farStorageBlocks}, the far trip):
         * down the platform's access path to its {@link PlatformCfg#foot}, the visit from
         * the storage walkway (a restock leg: it flies where the planner passes it, else it walks), and back by the foot
         * and the {@link PlatformCfg#entry}. The ride's openings are closed meanwhile; a walk of the way there that fails
         * ends the trip (the visit is held for 10 minutes, the print goes on with what is carried). Needs the platform's
         * {@code foot} and {@code entry}. false (the default): no trip to such a chest, one log line a RESTOCK.
         */
        public boolean elytraKitFarTrip = false;
        /**
         * {@link #elytraLegs}, elytra stage 5: land on a
         * small spot at the walk's goal (a few level cells, two clear cells above) with a brake that bleeds the speed in
         * the air and comes down slowly onto it, instead of the 2.4 x 20 pad near the goal and the walk on from there;
         * flights to a spot higher than the take-off (the clear stand) go over the end rods and burn the boost circling
         * over it. The long pad stays the fallback when no spot passes the pre-flight check. false: as before.
         */
        public boolean elytraShortLandings = false;
        /** {@link #elytraShortLandings}: the shortest leg (blocks, feet to goal) a flight onto a spot may fly. */
        public int elytraShortMinBlocks = 30;
        /**
         * {@link #elytraLegs}, elytra stage 5: a leg whose goal is not loaded yet (far
         * storage) flies toward it in stops: a straight flight onto a small spot in the loaded part along the way, then
         * the next flight from there ({@link #elytraHops} of them, at least 4 with this on); a leg on foot plans again
         * each 10 blocks (unless {@link #elytraOnTheWayBlocks} says otherwise), so it takes off once out from under a
         * roof. false: such legs search near the goal only (and walk).
         */
        public boolean elytraLongHops = false;
        /**
         * {@link #elytraLegs}: seconds no flight takes off after the bot's ticks start (a player's control ended, a
         * reconnect, a deploy), after a server teleport or setback, after the server was silent, and after gear the
         * plugin adopted; the bot must also have moved on the ground since.
         */
        public int elytraTakeoffHoldSeconds = 10;
        /**
         * At the end of a pickup stand, at the edge, the materials picked up that the tile does not need go back into the
         * sorter's fall ({@link PlatformCfg#sorterPickupReturn}), and junk to the junk spot, instead of being carried to the
         * drop point later (the owner, 2026-10-14). false = they stay for the restock's leftovers drop, as before.
         */
        public boolean pickupThrowBack = true;
        /** ... or after this many seconds there. */
        public int sorterPickupMaxSeconds = 90;
        /** ... or when this many or fewer inventory slots are empty. */
        public int sorterPickupMinFreeSlots = 1;
        /**
         * After the pickup, a lagging item still short by at least this many cells that far storage holds is fetched from
         * far storage in a full load, not trickled from the sorter again (live 2026-10-14 remu [0,6]: 63 restocks, each
         * with the 128-250 the sorter had put back).
         */
        public int sorterPickupFarMinCells = 256;
        /**
         * VERIFY: a pass after which the missing cells fell by at least max(64, 10 %) and nothing is wrong is progress
         * and is not counted towards {@code maxRepairPasses} (a big stock gap filled over several restock rounds).
         */
        public boolean verifyProgressFree = true;
        /** The same wait: seconds between looks at the near chests that hold or held the short items. */
        public int sorterRefillPollSeconds = 20;
        /**
         * RESTOCK drops the leftovers (materials the tile no longer needs) only when that pays: the seconds of restock
         * the load planned with them gone saves over the load planned with them carried (the planner's estimate of the
         * loads left), added up over the restocks that kept them, must reach {@link #lazyDropSeconds}. Live (8 maps,
         * 2026-10-13): 52 of 98 restocks dropped, mostly one stack of 55-63 carpets (a 64 round-up of an item the tile
         * used a few cells of); the leftovers stay in the inventory until they crowd a load (or the tile is stored).
         * false = every restock drops them, as before. Needs a planner with estimates (SPLIT, LANES, AUTO); the
         * greedy planner always drops.
         */
        public boolean lazyDrop = true;
        /**
         * What a leftovers drop adds to a restock, in seconds ({@link #lazyDrop}): 5.9 s with the drop over without it
         * at the same number of containers (regression over the 101 restocks of the live day, 7 s raw: the walk to the
         * drop point is on the way, the sorter's first chest is next to it, so only the edge stop, the aim and the
         * drops are extra).
         */
        public double lazyDropSeconds = 6;
        /**
         * The crowded-load return ({@code RestockLoads.choose}) keeps a carried material that the load without the
         * returned ones needs again (it reaches lanes that use it) instead of returning it and withdrawing it again
         * in the same restock (live: 9 of 52 drops, up to 9 of 10 stacks of one drop withdrawn again, each a chest
         * visit). false = the return list is the items the load as carried does not use, as before.
         */
        public boolean crowdKeepReused = true;
        /**
         * The crowded-load return works within the lane the load ends in: when the carried items the load does not
         * reach are needed further on in that lane (or later), those whose first use is furthest go back, as many as
         * let the load cover the most cells without reaching any of them (live 2026-10-31, map "haruhi": a palette of
         * many items in short runs, the inventory full of partial stacks the lane used later; loads of 9 to 108 cells,
         * one item each, 15-20 s a trip). Only with {@link #crowdKeepReused}. false = only the items the freed load
         * does not reach go back, as before (none when it reaches the whole lane).
         */
        public boolean crowdLaneCells = true;
        /**
         * Before skipping a short tile or planning a far withdrawal, re-read the near material containers that hold or
         * could hold the short items; before a far withdrawal also, now and then, every near container
         * ({@link #nearScanFullMaxAgeMinutes}). false = the stock index as it is, as before.
         */
        public boolean nearScanFirst = true;
        /** Minutes an item's targeted near look (or a full near sweep) remains usable; 0 = a new look each round. */
        public int nearScanMaxAgeMinutes = 10;
        /**
         * Minutes between full near sweeps before far withdrawals (every near container: stock put by hand into an
         * unrelated chest); 0 = one at each far round. Live 2026-10-13: a full sweep of the 118 soul sand
         * containers takes about 100-130 s.
         */
        public int nearScanFullMaxAgeMinutes = 60;
        /**
         * The waiting tile's looks (STOCK_CHECK "before skipping" and SELECT's for the in-order map: both only when
         * the tile is still short after the targeted look), the look before a pickup stand and the
         * one before the sorter wait gives up for far storage open every near container of the platform, not only those
         * the stock index says hold or could hold the item; at most once per {@link #nearRecheckAllSeconds} (a sweep
         * that recent stands for the look). A round's scan sweep also opens its near containers first, planned apart
         * from the far ones. Live 2026-10-29: carpet put by hand into an
         * unrelated platform chest was found only by a requested scan 7 min later, twice. false = the targeted looks
         * and one sweep of near and far together, as before.
         */
        public boolean nearRecheckAll = true;
        /** Seconds a full near sweep stands for the next look ({@link #nearRecheckAll}); 0 = a sweep at each look. */
        public int nearRecheckAllSeconds = 60;

        /**
         * Far storage (live 2026-10-13, ellen on the soul sand platform: 07:47 a
         * load wanted 64 purple terracotta, the near chest had 51, and the sweep walked about 250 blocks to the moss
         * sorter for about 12). A load whose cells only far storage ({@link #farStorageBlocks}) can supply are fewer
         * than {@link #farTripMinCells} leaves those cells out when a later load of the tile goes there anyway (they go
         * with it), else takes them now. The stack round-up of a withdrawal never comes from far storage, and near containers are
         * emptied before far ones. false (the kill switch) = each item from the chest closest to the platform's centre
         * for whatever the load asks, as before.
         */
        public boolean farStorage = true;
        /**
         * A material container more than this many blocks (horizontally) outside the platform's 128×128 map area is
         * far storage for that platform (the owner's base: each platform's own sorter 5-9 blocks off its area, the other
         * platform's 129 or more). 0 = no container is far (the far-storage rules off).
         */
        public int farStorageBlocks = 64;
        /**
         * With {@link #farStorage}: a load does not go to far storage for fewer than this many cells (all items together)
         * when a later load of the tile goes there anyway (at least this many far cells after this load): those cells
         * wait for it. With no later far trip the load takes them now (waiting would only move the trip to the tile's end).
         * 0 = a far trip for any number.
         */
        public int farTripMinCells = 64;
        /**
         * With {@link #farStorage}: a load that has to go to far storage also takes, from the far region it visits, the
         * items only far storage supplies for the upcoming lanes: as many lanes' worth as leave room for the near items
         * of its first lane and, at every later lane of the batch, for that lane's near items plus a quarter of the
         * load's slots. Later loads then need no far trip for them, and carried items near storage cannot supply again
         * are never returned to make room (live 2026-10-13, ellen on the soul sand
         * platform: far trips at 06:56, 07:13, 07:16, 07:19 and 07:22 for the same few items). false (the kill switch) =
         * each load takes its own cells only.
         */
        public boolean farStorageBatching = true;
        /**
         * Sprint on the restock legs: the Baritone
         * walks from the end of a load to the drop point, along the sorter between chests and back to the start of the
         * next lane sprint; every other walk only with {@link #sprintWalks}. Never in the first
         * {@link #sprintStartTicks} ticks of a walk, never within {@link #sprintStopDistance} of its goal, only while the
         * hunger bar is at least {@link #sprintMinFood}; a container open, click, placement, hotbar move or eating waits
         * until the bot has not sprinted for {@link #sprintQuietTicks} ticks. false (default until a supervised live
         * trial) = no sprint, as before.
         */
        public boolean sprintRestockLegs = false;
        /**
         * Kill switch: sprint on every plain Baritone walk, the walks where
         * the bot does nothing else on the way: to a lane's start and back to it after a park, to stand spots, to
         * containers, the drop point, buttons, the map station and the cart rail, the nuke's walks between break
         * positions, walks between platforms and the storage, escapes, retreats and a fight's chase. Same rules and
         * machinery as {@link #sprintRestockLegs} ({@link #sprintMinFood}, {@link #sprintStopDistance},
         * {@link #sprintStartTicks}, {@link #sprintQuietTicks}, {@link #sprintSync}, {@link #sprintOnce}). Never: the
         * lane's own walk while it places, the nuke's sweep while it breaks, a hop down a ledge, and any walk while the
         * clear runs (CLEAR once pressed, WAIT_CLEAR). false = only the restock legs sprint (with
         * {@link #sprintRestockLegs}), as before.
         */
        public boolean sprintWalks = true;
        /**
         * With {@link #sprintWalks}: the walk from one lane's end to the next lane's start (or back to it after a park)
         * sprints too; the first one after a restock is a restock leg and sprints as before. Live 2026-10-23 (j0015 on
         * the soul sand platform): those short legs (36-122 ticks) were set back right at the lane start 6 times in 78
         * lane changes (7.7 %), against 0-3 % for the same walk unsprinted (10-20..10-23) and 1 in 205 for the restock
         * legs back to a lane. false (default) = the walk from lane to lane does not sprint.
         */
        public boolean sprintWalksLaneStart = false;
        /** Sprint only at this hunger or above (Grim SprintA flags sprinting at 6 or below; vanilla needs above 6). */
        public int sprintMinFood = 8;
        /** No sprint while the feet are within this many blocks (horizontally) of the walk's goal. */
        public double sprintStopDistance = 4.0;
        /**
         * No sprint in a walk's first ticks. 0 (default): sprint from the first step, so no block the bot entered on the
         * soul-soil walkway before the START can bring a stale attribute update after it; the walk starts at least 3
         * ticks after a container closed.
         */
        public int sprintStartTicks = 0;
        /** Clicks, opens, placements, hotbar moves and eating wait until the bot has not sprinted for this many ticks. */
        public int sprintQuietTicks = 5;
        /**
         * With {@link #sprintRestockLegs} or {@link #sprintWalks}: the bot's sprint state as a vanilla client keeps it,
         * where ZenithProxy's Bot differs: the sprinting speed modifier
         * follows the server's attribute updates until the bot itself starts or stops sprinting (MC-69459, Grim's
         * model), and a server teleport does not clear the sprint state. Live 2026-10-11 without it: two setback bursts
         * of 10 at sprint starts on the soul-soil walkway. false = ZenithProxy's behaviour.
         */
        public boolean sprintSync = true;
        /**
         * With {@link #sprintRestockLegs} or {@link #sprintWalks}: one sprint per walk, started from rest. A walk sprints
         * only if its sprint starts before the feet leave their first block, and never again once it has stopped (a wall
         * bump, the stop distance; a walk whose goal moves, a chase, is one walk until it stops). Every other start or
         * stop races the server's attribute updates (soul-soil edges, the server's answer to the
         * last toggle) and costs a Simulation flag about every second time even with {@link #sprintSync} (local 1x,
         * 150 ms round trip, 2026-10-11: 12 flag chains in 39 legs, all in legs with such a race).
         */
        public boolean sprintOnce = true;
        /**
         * With {@link #sprintOnce}: a sprint that the walk itself ended (Baritone drops the key at a turn, a step or the end
         * of a path segment; live 2026-10-13: restock legs back to a lane's start sprinted 31 % of their ticks, the
         * drop-point legs 23 %) may start again once the bot has not sprinted for this many ticks, so the server's answer
         * to the stop (2b2t round trip p95 about 7 ticks) and the attribute updates of the blocks since have arrived.
         * 0 (default until the 2b2t trial: every restart is a sprint toggle
         * in mid-walk, the race that cost Simulation flags locally) = never again, as before.
         */
        public int sprintRestartTicks = 0;
        /**
         * Fast container clicks: the
         * {@code actionDelayTicks} d of a scan's, withdrawal's or deposit's inventory request. 0 (default until a
         * supervised live trial) = ZenithProxy's {@code client.inventory.actionDelayTicks} (5), as before. On the wire
         * every action of the request (a click, a wait, the snapshot) takes d + 1 ticks, not d: ZenithProxy 3.7.2's
         * InventoryManager runs the next action only when {@code SyncedTickTimer.tick(d)} holds, {@code tickTime - last
         * > d}. So 1 = one click every 2 ticks (100 ms), 5 = every 6 ticks (300 ms); one click per tick needs
         * {@link #containerClickEveryTick}. A pick-up and a shift-click in one tick is Grim PacketOrderA. The waits for
         * the server's answers are {@link #containerAckTicks} actions long. Tool, cartography and anvil menus keep
         * their cadence.
         */
        public int containerClickTicks = 0;
        /**
         * With {@link #containerClickTicks} above 0: the wait for the server's answer to a click, counted in the
         * request's actions: the first shift-click goes out {@code ceil(containerAckTicks / containerClickTicks)}
         * actions after the last cursor click, and the container is read that many actions after the last click and
         * closed 2 ticks later, each action {@code containerClickTicks + 1} real ticks. Live 2026-10-24 replay (last 10
         * maps, containerClickTicks 1): 8 is an ack wait of 902 ms on the wire (7 waits and the fix-up action, 18 ticks
         * to the shift-click) and 858 ms from the last answer to the close, while the server answers a container click
         * in 41-43 ms (median), 80 ms (p99), 99.9 % within 100 ms. With {@link #containerAnswerWaits} it is the cap of
         * those two waits in real ticks.
         */
        public int containerAckTicks = 8;
        /**
         * Least milliseconds between two container opens (any task). Live 2026-10-14 (2b2t): the connection was
         * lost twice ("You have lost connection to the server") in the middle of a near sweep opening 2-4 chests a second
         * from the same stand spot. 0 = no spacing, as before.
         */
        public int containerOpenMinMillis = 700;
        /**
         * With {@link #containerClickTicks} above 0: a shift-click-only withdrawal or deposit closes its menu as soon
         * as the transfer shows in the player inventory (the server's answers are in), at most
         * {@link #containerAckTicks} after the last click, instead of always waiting that long (live ack p50 2-3
         * ticks: about 5 ticks, 0.25 s, of the 20 a one-stack container takes, 6 containers a restock). Withdrawals
         * with cursor clicks (top-ups) keep the fixed wait. A transfer that comes up short waits the full time, as
         * before. false = the fixed wait, as before.
         */
        public boolean containerAnswerEarly = true;
        /**
         * Answer waits, for withdrawals with cursor clicks (top-ups,
         * exact counts) and with {@link #containerClickTicks} above 0 and ZenithProxy's {@code client.inventory.ncpStrict}
         * off, as live: the wait between the last cursor click and the first shift-click, and the one between the last
         * click and the close, end on the first tick the server's answer to that click is in the cache, at most
         * {@link #containerAckTicks} real ticks after it. An answer is a slot update of a slot the click changed, a
         * cursor update, or the menu's full contents, showing what the click left (empty or not); no other slot update
         * ends a wait (the sorter's hoppers update chest slots every 250-400 ms while a chest is open). Live 2026-10-24
         * replay (102 container uses a map): the fixed 902 ms between the cursor clicks and the shift-clicks (47 chests,
         * 42 s a map) and 858 ms between the last answer and the close (68 chests, 58 s a map), against answers in
         * 41-43 ms. Shift-click-only deposits and withdrawals also close on their last click's answer. A tail whose
         * last click is a shift-click (shift-clicks only, or a top-up that ends in shift-clicks) ends on that answer or
         * on the plan's moves showing in full in the inventory, at most {@link #containerShiftAnswerTicks} real ticks
         * after the click. Between the cursor clicks and the shift-clicks the cursor fix-up holds while the cache shows
         * items on a cursor the plan's cursor clicks left empty, until the cache shows it empty (a stale resync of an
         * earlier click: 8 of 28 chests at 10 ms each way; 8 holds of 2-4 ticks, no item left on
         * the cursor), and a shift-click ZenithProxy refused for the cache's
         * cursor goes again once it shows empty (at most twice), both within {@link #containerShiftAnswerTicks} of that
         * click. false (default; switched on live by config) = the fixed waits, as before.
         */
        public boolean containerAnswerWaits = false;
        /**
         * With {@link #containerAnswerWaits}: real ticks a tail whose last click is a shift-click may wait for that
         * click's answer or for the plan's moves to show in the inventory before the menu closes. ZenithProxy's
         * ShiftClick predicts nothing and the server always answers it, so the wait
         * normally ends on the answer; a close before it counts the moves short (with
         * {@link #containerAckTicks} 4 as the cap and answers 4-5 ticks late, 46 of 46 such withdrawals counted short,
         * 2,855 items). Replaces the {@code containerAckTicks} cap of {@link #containerAnswerEarly} for shift-click-only
         * transfers while the answer waits are on. Also the budget, from the last cursor click, of a held cursor fix-up
         * and of a refused shift-click's re-sends (see {@link #containerAnswerWaits}). Default 20 (1 s).
         */
        public int containerShiftAnswerTicks = 20;
        /**
         * Settle-fast: a withdrawal or deposit whose answers were in
         * when its menu closed and whose cursor is empty ends on the tick after the close, without the 2 settle ticks
         * (0.1 s of each container use). The next aim or walk never starts in the close tick itself (Grim MultiActionsD),
         * and nothing presses keys or sprints while the menu is open (MultiActionsC). false (default) = the settle
         * ticks, as before.
         */
        public boolean containerSettleFast = false;
        /**
         * One click per tick, with {@link #containerClickTicks} above
         * 0 and ZenithProxy's {@code client.inventory.ncpStrict} off: a withdrawal's or deposit's actions go as one-action
         * requests with {@code actionDelayTicks} 0 (ZenithProxy runs them in the tick they are submitted), one every
         * {@link #containerClickTicks} ticks instead of every {@code containerClickTicks + 1}: never two in a tick, only
         * in a tick whose input pressed no movement key and did not sprint, at most 6 in any 300 ms (Paper's limit is
         * 9). The waits count real ticks then. false (default; stays off until a separate supervised trial) = one
         * request at ZenithProxy's cadence, as before.
         */
        public boolean containerClickEveryTick = false;
        /**
         * Short turns while the plugin walks with Baritone: a Baritone yaw that would
         * make ZenithProxy's Bot turn more than 180° in one tick (its single wrap from a yaw beyond ±180) is sent again
         * as the same heading by the short turn. Grim AimModulo360 flags such jumps (3 per 9 local restock trips).
         * false (default until proven on the local Grim instance) = Baritone's rotations as they come, as before.
         */
        public boolean walkShortTurns = false;
        /**
         * Kill switch of the boxed-in escape: when a walk's path fails and the
         * pathfinder's own moves reach fewer than 100 movements from the feet, the bot walks back along its own recent
         * track (with its own keys) and paths again from outside. false = such a walk just fails, as before.
         */
        public boolean boxedInEscape = true;
        /**
         * Kill switch of the edge stops: a walk whose last cells border a drop of two
         * blocks or more, water or lava, or a no-go zone walks them sneaking (vanilla's sneak edge stop keeps the box on
         * the floor), and stops there sneaking until the bot stands still; a lane stand spot there is centred on
         * sneaking. A path the walk refuses or stops gets no keys in that tick. false = as before.
         */
        public boolean edgeSneak = true;
        /**
         * Kill switch of the edge rule for lane stand spots: a stand spot whose feet
         * cell borders such a hazard is taken only when no other spot of any ring places the cell. false = as before.
         */
        public boolean edgeSpotsLast = true;
        /**
         * Kill switch of the edge descents: a planned path that steps down into a
         * cell with a drop of two blocks or more, water or lava, or a no-go zone straight ahead of the step is planned
         * again around that step (as a no-go corner detour), or the walk fails: a step down at walking speed flies about
         * 0.9 blocks past the upper block's edge, so the bot lands on the far edge of a one-wide edge column (live
         * 2026-10-17, into the soul sand item channel). Not in the boxes of {@code site.edgeSneakOff}. false = as before.
         */
        public boolean edgeDescents = true;
        /**
         * Kill switch of the hole guard: during a walk Baritone drives, its
         * keys of each tick are played forward with the slide after them, and when the feet point would go into a cell
         * with a drop of more than 3 blocks, or water or lava, that the path does not plan to go into, the walk turns,
         * holds back or brakes instead; feet already in such a cell sneak; a planned diagonal move past such a cell's
         * corner is planned around. Live 2026-10-27: after a login east of the sorter's 1x1 item shaft, the walk passed
         * it and the bot fell 4 blocks in, though it sneaked. Not in the boxes of {@code site.edgeSneakOff}.
         * false = as before.
         */
        public boolean edgeHoles = true;
        /**
         * Kill switch of the planned-fall rule: a path one move of
         * which falls more than 3 blocks is never walked; the walk fails at once (a fight's chase then leaves the mob
         * alone as out of reach). The pathfinder falls any height into water when nothing else leads to the goal. Live
         * 2026-11-23: the chase after a trader's llamas on a ledge reachable only from the ground stepped off the
         * walkway and fell 112 blocks into water, twice. Everywhere, the boxes of {@code site.edgeSneakOff} too.
         * false = as before.
         */
        public boolean edgePlannedFalls = true;
        /**
         * Kill switch of the corner detour: a planned path whose only fault is a
         * move between two free cells that cuts a no-go zone's corner is planned again around it, with the move's end
         * made to look far or through the step's free side cell first (at most {@code Walker.MAX_CORNER_DETOURS} times
         * per goal), so Baritone takes the legal way round. false = such a walk fails at once, as before.
         */
        public boolean noGoCornerDetour = true;
        /**
         * Kill switch of the fall rescue: the feet half a block or more under a
         * platform's art layer beside or on its tile after it stood there (10 ticks), or in water for 40 ticks, cancels the
         * task and swims or walks the bot out to the nearest dry cell at the floor's level (30 s at most); if that fails,
         * BLOCKED with one "Bot fell" alert naming the position. Never disconnects. false = as before (walks just fail).
         */
        public boolean fallRescue = true;
        /**
         * Optional age ceiling: at STOCK_CHECK a material chest whose snapshot is older than this and holds an item the
         * job needs is scanned again. 0 (default) = never by age: snapshots are trusted until they turn out wrong. The
         * chests the bot opens refresh themselves, a shortfall at STOCK_CHECK and a withdrawal that comes up short both
         * re-look at the chests that hold or could hold the item, and a waiting job re-looks every
         * {@link #waitingRecheckMinutes}.
         */
        public int stockMaxAgeMinutes = 0;
        /** A job held as WAITING_MATERIALS is re-checked against stock at most this often. */
        public int waitingRecheckMinutes = 10;
        /**
         * Partial build: a tile short of some materials is built as far as the
         * stock allows instead of waiting whole. The cells the inventory and the stock cannot cover (per item, its last
         * cells in build order) are left out of the restock loads, BUILD and VERIFY; once everything else is placed the
         * tile waits on its platform for them ("waiting for materials: …"), looks again at the chests that hold or could
         * hold them every {@link #waitingRecheckMinutes}, and is filled, locked and stored only once complete. false =
         * the whole job waits as WAITING_MATERIALS from the first shortage (the behaviour before 2026-10-12).
         */
        public boolean partialBuild = true;
        /**
         * A tile with nothing placed yet starts a partial build only when the stock covers at least this share of its
         * cells (0.5 = half); below it the job waits as a whole and other jobs go first. A tile with blocks placed builds
         * whatever the stock covers. 0 = any coverable cell starts a partial build.
         */
        public double partialBuildMinShare = 0.5;
        /**
         * Carried items never wait (live 2026-10-13, ellen [3,4]: cyan terracotta
         * put into the bot's inventory waited for the next look and a trip's worth): a started tile short of materials
         * builds the cells the bot's own inventory covers at once, with no trip, and whether a trip is worth it is judged
         * on what the stock adds beyond the inventory ({@link #partialTripMinCells}). false = the carried cells count
         * towards a trip with the stock, as before 2026-10-13.
         */
        public boolean partialBuildCarried = true;
        /**
         * A started tile short of materials makes a trip once the stock covers at least this many of its cells (64 = one
         * stack), besides a full load or all of an item. 0 = only a full load or all of an item (the rule before
         * 2026-10-13).
         */
        public int partialTripMinCells = 64;
        /**
         * While a started tile waits on its platform for materials, bot ticks starting again (a player handing control
         * back, or a reconnect) or a change in the inventory's count of a short item looks again at once, as
         * {@code mapart scan} does, instead of at the next {@link #waitingRecheckMinutes}; bot ticks starting also make
         * every job held as WAITING_MATERIALS due again. false = only the timed look (and {@code mapart scan}).
         */
        public boolean stockWaitWakeOnChange = true;
        /**
         * Stock-aware selection: with no tile started on a platform, SELECT
         * weighs every unfinished map of every queued job against one stock snapshot and builds the first, in queue and
         * map order, the stock covers completely (live 2026-10-11: ellen map 2 idled for hours short of two items). When
         * none is, the one with the most coverable blocks is taken (partial build, {@link #partialBuild}); a started tile
         * always goes on first. false = the next map of the first job, in order, as before 2026-10-12.
         */
        public boolean skipShortTiles = true;
        /**
         * ZenithProxy modules force-disabled while the engine is active (previous state restored). AutoEat is
         * added automatically while {@link #eatFoodLevel} is above 0, and AutoTotem while {@link #placeMode} is
         * AIRPLACE (a saved list keeps its old entries; a new default never reaches it).
         */
        public List<String> disableModules = new ArrayList<>(List.of(
            "AntiAFK", "Spook", "AutoFish", "KillAura", "SpawnPatrol", "AutoDrop", "AutoMend", "AutoArmor",
            "AutoTotem"));
        /**
         * The plugin's keep-alive, since AntiAFK is off while the engine is active:
         * after this many seconds in which the bot neither moved nor acted (a wait: the platform above, map data, the
         * clear, eyeblossoms, materials, an empty queue, paused, BLOCKED), it swings its arm once and turns its head
         * 15 degrees (alternately right and left; it stays where it is), and again every this many seconds while the
         * wait lasts. Never during a task, a back-off or escape, a SAFETY STOP, with a container open, or while a player
         * controls the account. 0 = off.
         */
        public int keepAliveSeconds = 60;
        /**
         * The engine eats, standing still between tasks, when the hunger bar (0-20) is at or below this, or health is
         * at or below 10. ZenithProxy's AutoEat is off while the engine is active, because it clicks the inventory and
         * eats while the bot walks (Grim MultiActionsC/E). 0 = the engine never eats and leaves AutoEat alone.
         */
        public int eatFoodLevel = 14;
        /** Food items carried; topped up from storage.foodChests (GEAR, RESTOCK, or when out of food). */
        public int foodToKeep = 32;
        /**
         * The only foods the engine eats, counts and fetches, in order of preference; food ids listed in
         * {@link #keepItems} count too. Golden apples, enchanted golden apples and other valuables are never eaten, even
         * when listed: out of these foods the engine fetches from the food chests, or alerts "No food". ZenithProxy's
         * own AutoEat (on whenever the engine hands the modules back) follows its own config instead:
         * {@code client.extra.autoEat.mode} / {@code client.extra.autoEat.foods}.
         */
        public List<String> foodItems = new ArrayList<>(List.of(
            "minecraft:golden_carrot", "minecraft:cooked_beef", "minecraft:bread"));
        /**
         * A carried tool with Mending whose durability is at or below this % of its maximum is mended at the next
         * safe point (standing still between tasks): held in the main hand while XP bottles are thrown.
         */
        public int mendBelowPercent = 50;
        /** Mending throws XP until the tool is at or above this % of its maximum durability, or the bottles run out. */
        public int mendTargetPercent = 95;
        /**
         * Tools never break: no block is broken with a tool that has this many uses left or fewer. At the floor the
         * engine mends it (Mending + XP bottles), swaps it for another of the same kind (inventory or toolChests,
         * the worn one goes back), or stops (BLOCKED) with an alert.
         */
        public int toolSafetyFloor = 20;
        /**
         * Mending in the nuke (a 9,879-block map wears a pickaxe more than
         * once): at STORE, by the XP chest, the bot takes the XP bottles the next NUKE's wear needs (estimated from its
         * full blocks and the wear measured so far); in the NUKE a FAST sweep stops at a lane end once a Mending tool is
         * at or below {@link #mendBelowPercent} and the tool is mended there with the carried bottles (fetched then only
         * when none are carried, after room is made). False: mended only at the safety floor, bottles fetched then.
         */
        public boolean nukeMend = true;
        /**
         * Mending during the clear (the
         * part the presence rule allows): in WAIT_CLEAR, standing at the centre while the flying machines sweep and the
         * carts collect, the bot mends its carried Mending tools up to {@link #mendTargetPercent} with the XP bottles it
         * carries (no walk, no fetch). With {@link #nukeMend} the NUKE then skips a lane-end mend while the measured wear
         * says the tool still finishes the nuke well above {@link #toolSafetyFloor}, and STORE takes the bottles for the
         * whole repair. The mend leaves the critical path. False: mends only between tasks as before (not in the clear).
         */
        public boolean mendDuringClear = true;
        /**
         * How blocks are broken (NUKE before a clear, VERIFY repairs).
         * "FAST" (default): instant blocks (destroy progress per tick ≥ 1 with the tool, e.g. terracotta with Efficiency
         * V and Haste II) get up to {@link #nukeStartsPerTick} STARTs per tick, every other block the vanilla client
         * timing (START, STOP when done, then {@link #nukeStopDelayTicks} ticks without a START); a large flat set of
         * targets is swept lane by lane, breaking while walking ({@link #nukeSweep}). The plugin sends the dig packets
         * itself. "DOUBLE" (the 2b2t "doublebreak"): two blocks that take the same tool and lie in reach of the same
         * stand position are mined together. The first is started and at once stopped, which leaves it to the server's
         * delayed destroy (vanilla breaks it by itself once the progress with the held tool reaches 1); the second is
         * mined as usual meanwhile. "SINGLE": one block at a time through ZenithProxy's own left click (the behaviour
         * before 2026-10-11; the kill switch). Unknown values count as FAST.
         */
        public String breakMode = "FAST";
        /**
         * FAST: instant-break STARTs per tick at most (1..8). 1 is what a vanilla client can do with the attack key held
         * (one block per tick, the look on it). Above 1 the look hits only the first block of the tick: Grim's
         * experimental MultiBreak and RotationBreak would flag that (both off in Grim's default config; Lambda, the 2b2t
         * highway client, sends up to 59 per tick without rotating). The sweep is walking-bound from about 2.
         * When blocks other than the looked-at one are refused repeatedly,
         * the run drops to 1 by itself. Default 1: 2b2t refuses them (live 2026-10-11, ellen [2,1]: "6 of the last 20
         * blocks the look was not on were refused" within 8 minutes of 4 per tick).
         */
        public int nukeStartsPerTick = 1;
        /**
         * FAST: dig packets (STARTs, STOPs) per rolling 20 ticks at most. Packet-rate limits kick, they do not slow
         * down: ViaVersion 200 packets/s sustained, Paper 500/s; a tick also sends a movement and a tick-end packet.
         */
        public int nukeMaxDigsPerSecond = 100;
        /**
         * FAST: reach for breaking, from the eye to the nearest point of the face aimed at (−1 = {@link #reach}). The
         * server allows 4.5 + 1 to the block's box; Grim FarBreak (experimental) 4.5 (+0.04) to the box.
         */
        public double nukeReach = -1;
        /**
         * FAST: ticks without any START after a STOP (non-instant blocks), 0..5. 5 is the vanilla client; Grim FastBreak
         * flags a START within 275 ms of a STOP once its balance passes a second (about 4 blocks at 0).
         */
        public int nukeStopDelayTicks = 5;
        /**
         * FAST: sweep a large flat set of targets (one Y level, at least {@link #nukeSweepMinTargets}) in lanes, walking
         * with the bot's own keys and breaking everything in reach on the way (no Baritone stops between blocks).
         * false: walk with Baritone to the nearest target and break everything in reach from where it stops.
         */
        public boolean nukeSweep = true;
        /**
         * FAST, the NUKE only (the art is cleared afterwards): the sweep breaks a block that is no target where it stands
         * in the way and cannot be walked through, if the clear removes it anyway (the platform's clearableBlocks, not
         * protected, a floor under it), instead of walking around it (live 2026-10-15, Galileo [0,0]: decorated pots, 3 %
         * of the art, slowed the sweep; a local sim of such art: 14 % fewer ticks). It also breaks pointed dripstone in the
         * way, which the pocket guard otherwise walks around. false (default): walked around, as before.
         */
        public boolean nukeSweepBreaksWay = false;
        /**
         * Kill switch of the fast nuke's edge guard: before the sweep's own
         * movement keys go out, the tick's movement (the keys pressed for the yaw of that tick, up to 22.5° off the wanted
         * direction) and the slide after it are played forward; if the box would go into a no-go zone, water or lava,
         * or a cell with a drop of two blocks or more, the nearest keys that keep clear are pressed instead, else none,
         * else braking, and Baritone walks around after 5 ticks held back (live 2026-10-18: pressed against decorated
         * pots, the keys slid the bot into the no-go zone over the soul sand item trench). false = as before.
         */
        public boolean nukeEdgeGuard = true;
        /** FAST sweep: lane half-width in blocks across the walking line (lanes are 2w+1 wide), 1..6. */
        public int nukeLaneHalfWidth = 4;
        /** FAST: fewer targets than this (or targets on several Y levels) are not swept. */
        public int nukeSweepMinTargets = 48;
        /**
         * FAST sweep: lane direction, "AUTO" (along the longer side of the targets; with {@code site.hasteZones}, the
         * axis that keeps the most of each lane inside them), "X" (east-west lines) or "Z" (north-south lines).
         */
        public String nukeLaneAxis = "AUTO";
        /**
         * FAST: one arm swing per instant START (true) or one per tick with STARTs, after the first (false, default:
         * Grim NoSwingBreak wants one swing in a tick with dig packets, and fewer packets keep below the rate limits).
         */
        public boolean nukeSwingEachStart = false;
        /**
         * FAST: a block counts as instant through Haste only while the Haste effect has at least this many ticks left
         * (the client's estimate from the last effect update); below, it is mined with the vanilla timing, so no START
         * meets a server whose Haste just ran out.
         */
        public int nukeHasteMarginTicks = 40;
        /**
         * FAST: a slow block (not instant, under 0.7 progress per tick with its tool: redstone and raw iron blocks with
         * Efficiency V and Haste II, 0.33) is started and stopped in one tick, which leaves it in the server's one
         * "delayed destroy" slot; the server breaks it with the held tool by itself 3 ticks later, while the bot walks
         * on. Then 5 ticks without a START, as after any STOP. About 6 ticks a block instead of 10 (START, 4 ticks of
         * mining, 5 without a START) and no stop of the walk. After 3 delayed blocks in a row that never showed (an
         * anti-cheat cancelling the early STOP) slow blocks are mined again for the rest of the task. Grim's FastBreak
         * (on by default upstream) adds the block's break time to its balance for every early STOP: on a Grim server
         * this turns itself off after a few blocks. false: slow blocks are mined.
         */
        public boolean nukePairSlow = true;
        /**
         * FAST: break with Lambda's OldGrim scheme (the 2b2t highway client's default nuker; ported from Lambda,
         * GPL-3.0, commit fa59ab1). A non-instant block (lapis
         * with Efficiency V, Haste or not) gets START plus a fake START 955 blocks above it (out of the world: the server
         * ignores it), its STOP once {@code speed × ticks} reaches 0.7 and 7 ticks have passed, then 6 ticks without a
         * START; at 7 ticks a burst of 22 fake STARTs lets the next non-instant block start at once
         * ({@link #nukeLambdaBurst}). The block just started is turned into the server's delayed destroy by an early
         * STOP and a second one is mined meanwhile ({@link #nukeLambdaDoubleBreak}). A block at 0.7..1 a tick breaks by
         * START + STOP in one tick. Instant blocks: up to {@link #nukeLambdaStartsPerTick} STARTs a tick, the look on the
         * first. Walking, the sweep, the guards, the tool floor and the rate cap ({@link #nukeMaxDigsPerSecond}, which
         * counts the fake STARTs), mining on the move ({@link #nukeMineMoving}, on Lambda's timing), the leftover route
         * and the ack wait ({@link #nukeInstantAckTicks}, also for Lambda's STOPs) stay as they are; {@link #nukePairSlow}
         * is not used. 5 refused of the last 10 such blocks switch it off for the round. Upstream Grim would flag the
         * STARTs right after a STOP and the fake STARTs on air;
         * how 2b2t's anti-cheat takes them only a live run shows. false (default): the vanilla timing above.
         */
        public boolean nukeLambda = false;
        /** FAST with {@link #nukeLambda}: instant-break STARTs per tick at most (1..59; Lambda's own default is 59). */
        public int nukeLambdaStartsPerTick = 8;
        /** FAST with {@link #nukeLambda}: the fake out-of-world START after each non-instant START (false: none at all). */
        public boolean nukeLambdaFakeStarts = true;
        /**
         * FAST with {@link #nukeLambda}: the burst of 22 fake STARTs at 7 ticks of mining, after which the next
         * non-instant block starts in the STOP's tick instead of 6 ticks later (needs {@link #nukeLambdaFakeStarts}).
         */
        public boolean nukeLambdaBurst = true;
        /** FAST with {@link #nukeLambda}: Lambda's double break through the server's delayed-destroy slot. */
        public boolean nukeLambdaDoubleBreak = true;
        /**
         * FAST: keep the bot out of pockets, floor cells walled in by blocks that are no target and cannot be stepped onto
         * (pointed dripstone, pots): it never walks or drops into one, never breaks a target in one from on top, and
         * Baritone's paths may not end in one. When a walk fails with the bot in one anyway, the fewest of those blocks
         * on the way out are broken (only the platform's clearableBlocks, which the clear removes anyway; never a
         * protected block or region; the tool floor holds) and it walks out. false: off (live 2026-10-12, ellen [1,5]:
         * caged in a dripstone pocket, 6 rounds of "no progress", then BLOCKED).
         */
        public boolean nukePocketGuard = true;
        /**
         * Every break mode: a break task that breaks no block and moves the bot less than a block for this many seconds
         * logs one line and re-plans: FAST ends its sweep (the rest is done standing), then leaves the target it is
         * stuck on for later; SINGLE and DOUBLE leave the target they are stuck on; after 4 trips the task fails and the
         * engine runs the step again. 0 = off. (Local 10x run, 2026-10-11: a DOUBLE task swapped its walking goal
         * between two blocks at the same distance every tick and never moved.)
         */
        public int breakWatchdogSeconds = 15;
        /**
         * End of tile, NUKE on a platform whose clear the bot
         * waits out at the centre ({@code stayDuringClear}): when the chunks of the platform are not loaded (the bot
         * comes from the supply chests, 300 blocks away) and the tile's art holds nothing the piston clear cannot
         * remove, the bot walks to the clear button's stand to load them, not to the map station on the floor. The walk
         * to the floor and the second walk up to the button over the long walkway fall away (live 2026-10-13, soul
         * sand: NUKE 71-89 s per map, no block broken in any of 8 maps, two walks of about 39 s each). false = the walk
         * to the map station first, as before. A tile that does need a nuke always walks to the station.
         */
        public boolean nukeLoadFromCentre = true;
        /**
         * Carry-over between tiles: at the end of a stored map, when
         * the tile built next on the same platform is known (the queue) and keeping the cells the two share is estimated faster, the NUKE breaks only the
         * cells that differ (carpets too, by hand), no clear is pressed, and the next tile's build places only what is
         * missing. One log line per decision with both estimates. false (the default until the owner has reviewed it) = the
         * nuke, clear and full build as before.
         */
        public boolean carryOver = false;
        /**
         * Carry-over is taken only when its estimate is at most {@code 1 - carryOverMargin} of the normal end of tile
         * (nuke + clear + the whole next build): 0.1 = at least 10 % faster. 0..0.9.
         */
        public double carryOverMargin = 0.1;
        /**
         * STOCK_CHECK after a restart: the material containers the stock index already knows (the file survives a
         * restart) and that lie in {@code site.storage.materialRegions} are taken as the discovery's result, so the bot
         * does not walk to each region's middle to load it and read every chest block again (live 2026-10-13, the
         * first STOCK_CHECK after the 21:35 restart: 61 s, two walks of 188 blocks). A new chest turns up with
         * {@code !map scan} (a requested scan discovers again). false = discovery after every restart, as before.
         */
        public boolean seedDiscoveryFromStock = true;
        /** Item ids the bot keeps in inventory and never deposits (tools, food, boots). */
        public List<String> keepItems = new ArrayList<>(List.of(
            "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe",
            "minecraft:diamond_axe", "minecraft:netherite_axe",
            "minecraft:diamond_shovel", "minecraft:netherite_shovel",
            "minecraft:golden_carrot", "minecraft:cooked_beef", "minecraft:bread",
            "minecraft:netherite_boots", "minecraft:diamond_boots"));
        /**
         * {@link #dropJunk}: what the NUKE's breaks give back instead of the art block (clay balls of clay, bricks of a
         * decorated pot, glowstone dust, books, melon slices, snowballs, prismarine crystals, a leaf block's saplings and
         * sticks; {@code engine.NukeDrops}) counts as junk whenever that block is a material of this base (a material
         * chest held it, or a job lists it), as if it were in {@link #junkItems}: thrown at the junk spot, never into the
         * sorter, never kept around. Live 2026-10-21 23:42 to 2026-10-23 06:46: the clay of the j0014 nukes came back as
         * clay balls the bot counted as the owner's; they grew to 404 in 7 of the 36 slots and every load since was planned
         * around them (j0015: about 1,440 cells a load instead of about 1,890). Never a valuable, a working item, food or
         * an item a job places. false = they stay in the inventory, as before ("Few free slots" alert).
         */
        public boolean junkNukeDrops = true;
        /**
         * Anticheat-sensitive (a sprint start in mid-walk): needs the owner's go. With {@link #sprintOnce} and
         * {@link #sprintRestartTicks} above 0: a walk whose feet left their first block before its sprint started
         * ("no sprint: the feet left their first block first") may start sprinting once the bot has not sprinted for
         * {@link #sprintRestartTicks} ticks since it left that block, under the same rule as a restart (the same toggle
         * on the wire: a sprint start in mid-walk after that pause). Live 2026-10-19..23: 47-63 % of the restock legs'
         * ticks were such legs (10-22: 1,267 legs, 12,231 s); sprinting 80 % of the rest of them at 7.8 against
         * 6.0 b/s would save about 35-125 s a map. false (default) = such a walk never sprints, as before.
         */
        public boolean sprintLateStart = false;
        /**
         * The pre-lane hotbar layout ({@code LaneHotbar}) never moves a stack
         * into a slot it already filled for this lane, so it ends after at most one move per slot. Without it the plan
         * could swap two items' second stacks with each other for ever (live 2026-10-21 00:55, ABD [9,5]: 40 minutes in
         * APPROACH with no log line, 4 task timeouts, until a reconnect). false = as before.
         */
        public boolean laneLayoutPin = true;
        /**
         * The lane watchdog ({@link #laneWatchdogTicks}) counts hotbar changes alone (no placement, click, phase change
         * or movement) as progress for at most this many ticks; then it trips as for a lane standing still ("only hotbar
         * changes"). 0 = hotbar changes always count, as before.
         */
        public int laneWatchdogHotbarTicks = 1200;
        /**
         * The durability guard (tools, weapons and worn armour never break).
         * Every worn armour piece (head, chest, legs, feet) is looked at every second in every state the bot ticks in:
         * working, between tasks, with a task parked, in waits, IDLE, BLOCKED and paused. A Mending piece below its
         * mend threshold ({@link #bootsMendBelowPercent} for the boots, {@link #armorMendBelowPercent} for the rest) is
         * mended to 95 % with the carried XP bottles, standing still; while working, bottles are fetched from the XP
         * chests when none are carried, and a fetch that brings bottles lets the mend start at once (live 2026-10-21:
         * "boots mend stopped at 53% (no XP bottles left)", the fetch at 49 %, and the mend held 10 min by the retry
         * wait until 27 %). Below {@link #armorParkPercent} the running task stops at its next safe point for it. A
         * piece at {@link #armorOffPercent} that cannot be mended there and then is taken off into the inventory, and
         * worn again only once it can be mended. Damaged Mending armour is topped up before a tool mend (it shares the
         * XP), and a tool mend whose XP the armour took does not count as "Mending does not repair". false: the boots
         * alone are mended between tasks as before.
         */
        public boolean gearGuard = true;
        /** {@link #gearGuard}: worn Mending armour other than the boots below this durability (percent) is mended. */
        public int armorMendBelowPercent = 50;
        /**
         * {@link #gearGuard}: a worn Mending piece below this durability (percent) stops the running task (a lane, a
         * break task, a walk, a chest) at its next safe point for its mend, instead of waiting for the task's end (a
         * NUKE task runs up to about 24 min; Soul Speed boots lost 2.4 % a minute live). 0 = only between tasks.
         */
        public int armorParkPercent = 25;
        /**
         * {@link #gearGuard}: the hard floor (percent). A worn armour piece at or below it that cannot be mended there
         * and then (no bottles, no Mending, Mending not working) is taken off into the inventory; a task that cannot
         * stop for it within 30 s is cut short. With no free inventory slot the engine makes room first; failing that
         * it goes BLOCKED (standing still, so the piece wears no further) with an alert.
         */
        public int armorOffPercent = 6;
        /**
         * Last line at the point of use: while the plugin drives the bot (no
         * player in control), no dig packet (start or finish) and no attack packet goes out while the main hand holds a
         * damageable item at or below {@link #toolSafetyFloor} uses left, whatever sent it (the plugin's tasks, the
         * pathfinder's seal-block breaks, KillAura): a start or attack is dropped, a finish becomes an abort. Logged
         * once per item; the engine mends or swaps the tool as usual. false: off.
         */
        public boolean heldItemGuard = true;
        /**
         * Mend watchdog: a carried Mending tool or worn Mending armour piece below its mend threshold for this many
         * minutes without a successful mend is mended at the next safe point whatever held its mend (a retry wait, the
         * "Mending does not repair" count, a mend deferred to the clear), and a tool is mended on until it reads
         * mended. Standing still counts as a safe point: IDLE and the STOCK_CHECK waits (bottles fetched), BLOCKED and
         * paused (carried bottles only). The reasons the mends were skipped are logged once. 0 = off.
         */
        public int mendWatchdogMinutes = 10;
        /**
         * Kill switch of the fall rescue's climb (needs {@code fallRescue}): a bot
         * that fell where it cannot swim or step out (live 2026-10-15..19: the 1-wide water channels beside the soul
         * sand tile, the floor 2.875 above the channel's) climbs out with blocks of its own: one into the cell beside
         * at its feet's level and a jump onto it, again a level up against the channel's outer wall, then a jump onto
         * the tile; a 1x1 hole is pillared (a jump looking down, the block below the feet). Every block it placed is
         * broken again (from on top of the next one, the last ones from the tile), never with a tool at
         * {@code toolSafetyFloor}. Blocks only go where no protected region, art-layer cell or other no-go zone is.
         * One click per block, real rotations, normal jumps. false = the swim-and-step exits only, as before.
         */
        public boolean fallRescuePillar = true;
        /**
         * Blocks the climb places, the first one carried first (blocks that break fast and drop themselves). None of
         * them carried: {@code fallRescueAnyBlock}.
         */
        public List<String> fallRescueBlocks = new ArrayList<>(List.of(
            "minecraft:netherrack", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt"));
        /**
         * None of {@code fallRescueBlocks} carried: the climb places the carried block of which there is most that
         * suits (a full cube with no block entity that does not fall, melt, slide or power anything and is worth
         * nothing: art materials such as wool or terracotta do). false = only {@code fallRescueBlocks}.
         */
        public boolean fallRescueAnyBlock = true;
        /**
         * The climb's kit: the bot keeps this many of the first {@code fallRescueBlocks} entry it can get (RESTOCK and
         * the leftovers never deposit or drop them) and fetches them from the material chests at GEAR when it carries
         * fewer than half and none of the other entries. 0 = no kit (the climb uses what the bot carries).
         */
        public int fallRescueKit = 8;
        /**
         * WAIT_CLEAR (a clear's flying machines move over the channels, about 80-92 s live): a fall rescue holds still
         * until this many seconds after the clear was pressed before it climbs. 0 = no hold.
         */
        public int fallRescueClearHoldSeconds = 120;
        /**
         * With {@link #seedDiscoveryFromStock}: after a restart with a changed set of {@code site.storage.materialRegions}
         * (a region added, moved or resized), the containers the last discovery found in the regions still the same are
         * taken from the stock index and only the new or changed regions are discovered; their containers, never read,
         * are scanned in the same STOCK_CHECK, the others keep their
         * snapshots. Without it any change discovered every region again (the soul sand site: a walk to the far moss
         * sorter and back, about 61 s live 2026-10-13). false = a changed set of regions discovers everything, as before.
         */
        public boolean seedDiscoveryPartial = true;
        /**
         * Even lanes: the lanes share the tile's 128 columns as
         * evenly as possible instead of leaving the last lane {@code 128 mod (2w+1)} columns, same lane count, none
         * wider than {@code 2w+1} and no cell farther than {@code w} from its walking line. At the airplace half width 4:
         * eight lanes of 9 and seven of 8 instead of fourteen of 9 and a last one of 2 (live 2026-10-23, j0015: lane 14
         * walked 14-16 s with Soul Speed and 38-56 s without it for 256 cells, along the tile's east edge column).
         * Lane indexes stay; a replan mid-map with the switch changed moves the lane borders (cells already placed are
         * skipped as always). false = lanes of 2w+1 from the west edge, as before.
         */
        public boolean laneEvenWidths = false;
        /**
         * With {@link #hotbarMoveFence}: a hotbar move
         * goes out with state id -1, which no server state id is, so the server always answers it with the whole
         * inventory. Before, it carried the cache's state id plus one, which is the server's own when exactly one
         * inventory update is still on the way (the server counts one per update it sends); the server then answers
         * nothing and the move waits out the 40-tick fence (a lookahead move then asks for the full inventory with the
         * same -1 click). Live 2026-10-23 (j0015): 2.7 % of the moves, 8.8 a map, about 31 ticks without a click each;
         * local Paper (one update a tick): 31 %. false = the cache's state id plus one, as before.
         */
        public boolean hotbarMoveForeignStateId = false;
        /**
         * FAST nuke, sweep: a mined block (not instant: cobbled deepslate, raw iron, redstone blocks) is started with
         * the walk going on when it stays in reach until its STOP at the walk's top speed (0.3 blocks to spare), else
         * with the movement keys released as soon as it stays in reach sliding (no wait until the bot stands); while it
         * is mined the walk goes on as long as it stays so and every other target in reach can still be stopped for.
         * The look is aimed from where the eye will be after the tick's movement (the rotation goes out with that
         * position), so the STOP's look hits the block from there. One
         * START per tick and one block mined at a time, the vanilla timing and the delay after each STOP as before; no
         * sprint. Live j0015 (soul sand, 2026-10-23): the walk stood for every cobbled deepslate block, about 9 ticks
         * each. false (default) = a mined block holds the walk and starts once the bot stands, as before.
         */
        public boolean nukeMineMoving = false;
        /**
         * The walk close to a container without a planned stand cell ends on the stand row of the container's storage
         * column: the lowest level of the material regions over its x/z that starts at most 5 levels below it, not the
         * lowest level of the one region holding it. Live 2026-10-23 (soul sand,
         * the upper-rows region y177-179 over the front rows y174-175): every y179 barrel without a stand cell got a
         * walk to "feet at y177 within 4 blocks", which no cell meets, and 4 failed searches first (about 7 s each, 380
         * of the 463 s of one STOCK_CHECK). false = the lowest level of the region holding it, as before.
         */
        public boolean containerColumnFloor = true;
        /**
         * The near-storage look times ({@link #nearScanFirst}: the last full near sweep per platform, the last targeted
         * look per platform and item) are kept in the stock index, so a restart does not make the next far round sweep
         * every near container again within {@link #nearScanFullMaxAgeMinutes} of the last sweep (about 130 s for the
         * 118 soul sand containers, more with the upper rows). The snapshots they rest on are kept there already;
         * containers changed by hand meanwhile are read again by the usual rules. false = the times live only as long
         * as the engine run, as before.
         */
        public boolean nearScanPersist = true;
        /**
         * The next tile's empty map and glass pane come at STORE, one each:
         * the bot stands by their chests then, and the next tile's last load makes no trip to them. Live j0015
         * (2026-10-23, soul sand, the map and pane chests at the station 244 blocks east of the tile): that last load
         * took 74 s of RESTOCK and 32 s to its lane against 35 s + 6 s for the others, about 65 s a map more (j0011
         * about 101 s, j0014 about 122 s). The two items then hold 2 slots through the next tile's loads: with the live
         * kit (pickaxe, axe, shovel, food, XP bottles, anvil) {@link #reservedSlots} is full, so each is a material slot
         * less (RestockModel, the 40 j0015 tiles on the soul sand layout: 30 s a map faster all the same;
         * {@code MapPaneAtStoreModelTest}). STORE keeps one of each instead of putting them all back; the RESTOCK XP
         * tidy does not count them; a chest that is empty or fails: the next one, all tried: the last load fetches it as
         * before. Kept when no tile follows (the next one uses them). false = the last load fetches them, as before.
         */
        public boolean mapPaneAtStore = false;
        /**
         * FAST nuke, NUKE only: instead of the serpentine over
         * whole lanes, the walk can take a tour of lane segments: each lane cut where a lane's width of rows holds no
         * target, the pieces walked in the order a nearest-first walk from the feet gives, improved by 2-opt on the
         * whole walk (to the start, the pieces and the straight joins between them, the end to the platform's
         * {@code nukeEndCorner}); a piece whose targets all broke before the walk got there (from a join or a
         * neighbouring piece) is skipped. Taken only when the targets fill at most {@link #nukeTourMaxDensity} of
         * their box and its walk is at least 5 % shorter than the serpentine's (one log line says which). The same
         * walking, breaking and safety rules as the sweep; no new movement. Live j0015 (soul sand, 2026-10-23, 110-945
         * cobbled deepslate blocks a map): the sweep walked 338-1,564 blocks a map, 748-5,181 ticks; simulated on the
         * targets of j0015's 38 remaining tiles the NUKE takes 48.5 s a tile less (52.9 s with
         * {@link #nukeEntryCorner}), the dense ones 8.7-33.5 s less. false (default) = the serpentine, as before.
         */
        public boolean nukeTour = false;
        /**
         * {@link #nukeTour}: the most the targets may fill of their bounding box (0..1) for a tour; denser art keeps the
         * serpentine (an ellen-like terracotta tile is 0.6, the densest j0015 tiles 0.26-0.34).
         */
        public double nukeTourMaxDensity = 0.5;
        /**
         * NUKE with the platform's chunks not loaded (the bot comes from the station or the storage): the walk that
         * loads them goes to the art layer 3 cells inside the corner the platform's {@code nukeEndCorner} names (the
         * way onto and off the art layer), not to the map station, and the sweep is planned from there; when the chunks
         * are still not all loaded 5 s after it arrived, it walks on to the map station as before. Live j0015 (soul
         * sand, 2026-10-23, replay track): the walk crossed the art layer from the north-east corner to the station at
         * its centre (8-15 s) and then walked back out to the first lane end (3-11 s). A platform without
         * {@code nukeEndCorner}, a colour-suppression tile or a NUKE with nothing but clearable blocks (the walk to the
         * clear stand) walks as before. false (default) = the map station, as before.
         */
        public boolean nukeEntryCorner = false;
        /**
         * FAST nuke, NUKE only: what the sweep left is done as
         * one route instead of a Baritone walk per block. Every leftover whose air already shows counts as broken (an
         * instant block given up as refused while the walk went on, whose late air came once it was out of reach; in the
         * sweep too, for the blocks retried, and before each walk to a target). The rest get stand cells (walkable, no
         * hazard, in no pocket; each within 3.75 blocks of its blocks, as many blocks from one cell as it reaches),
         * ordered nearest first and improved by 2-opt from the feet to the platform's {@code nukeEndCorner}, walked with
         * the sweep's own keys and rules (the edge and pocket guards, holds, detours; a leg along a hazard or across a
         * block that is no target by Baritone), breaking whatever comes in reach on the way. Fewer than 4, more than
         * 2,500 or more than the sweep broke (it did not get through) are done standing, as is what the route could not
         * break. Live j0024 (soul sand, 2026-10-25): "51 left for standing" took 1,173 ticks in 47 Baritone walks. Sim
         * (16 j0024-like rounds): the leftovers in 677 ticks instead of 957 on average, the round 1.4 % shorter, never
         * longer in a round nor in any other nuke scenario (up to 17 % shorter where pockets leave thousands). false =
         * the standing phase, as before.
         */
        public boolean nukeLeftoverRoute = true;
        /**
         * FAST: ticks after the server's ack of an instant START before the block, still showing, counts as refused and
         * is tried again (2..20). 2b2t sends a broken block's air a few ticks after the ack now and then: live j0024
         * counted 95 refused, about 83 of which broke on the retry's START before any STOP, a START on a block the server
         * had already broken (Grim AirLiquidBreak's case). A block whose air shows within the wait is broken and gets no
         * second START; one still showing at its end is retried as before, later by the extra ticks. Mined blocks keep
         * 2. Sim (16 j0024-like rounds, air 1-6 or 1-10 ticks late): 4 cuts the STARTs on broken blocks by 31 % and the
         * refusals by 18 %; the round is 80 ticks shorter on average but longer in 5 of 16 rounds (up to 110 ticks) and
         * 2-4 ticks longer where blocks are really refused. 2 (default) = as before.
         */
        public int nukeInstantAckTicks = 2;
        /**
         * FAST nuke sweep: the walk holds for a target in reach that its next step would take out of reach; this looks
         * that step ahead at the walk's measured top speed (Soul Speed on soul sand: about 0.49 blocks a tick) instead of
         * a fixed 0.25 blocks, so a target passed at speed is broken there instead of being left for the tail. Holds only,
         * no timing changes. Sim (16 j0024-like rounds): 30 % fewer leftovers, the round 146 ticks shorter on average
         * but longer in 4 of 16 rounds (up to 68 ticks); with the route and the 4-tick wait 396 shorter, longer in 1
         * (97 ticks); no other nuke scenario longer. false (default) = 0.25 blocks, as before.
         */
        public boolean nukeHoldAtSpeed = false;
        /**
         * The restock planner may walk a load's lanes the other way: its first
         * lane S→N, the next N→S, and so on, chosen per load by the split DP ({@code restockPlanner} SPLIT, LANES or AUTO
         * on 9-wide lanes; the greedy planner never flips). Every load today starts its first lane N→S: after a load cut
         * a lane walking S→N, the next load runs the lane's north rows from the walkway and then walks back down to the
         * next lane's south end (live j0015, 2026-10-23, 11 maps: those dead walks 68 s a map, the walk to the first
         * lane start 102 s). A flipped load walks in to the cut instead, runs the north rows S→N and changes to the next
         * lane at the north edge. The walk to the first lane start (and the restock sweep's end) is the flipped lane's
         * start; the lanes are replanned N→S after the round as before. RestockModel, soul sand layout, live kit, the 40
         * j0015 tiles and 6 synthetic ones: 600 s → 593 s of restock and lane changes a map, 35 of 46 tiles faster, the
         * worst 25 s slower ({@code LoadDirectionModelTest}). false = every load starts N→S, as before (the kill switch).
         */
        public boolean restockLaneFlip = false;
        /**
         * One anvil at a time: RENAME takes exactly one from
         * {@code site.storage.anvilChests} when the anvil block is gone, and STORE puts any anvils the bot holds back
         * into those chests. Live 2026-10-22 18:57 (soul sand): the replacement came as a whole stack, one was placed and
         * 47 rode along in a kept slot for the next 30+ maps. With {@link #mapPaneAtStore} the kit fills
         * {@link #reservedSlots}, so that slot is a material slot on every load. false = the whole stack, kept, as before.
         */
        public boolean exactAnvil = false;
        /**
         * Stand cells of container visits, sweeps and single clicks ({@code StandSpots}) only on a floor ZenithProxy's
         * pathfinder walks on ({@code MovementHelper.canWalkOnBlockState}): not a decorated pot or a hopper, whose
         * collision reaches the top all the same. Live 2026-10-23 (soul sand): two walkway cells over decorated pots were
         * picked for the chests beside them 3.3 times a map, each a "No path found" and about 6 s before the next spot.
         * false = any floor with a top in [0.8, 1], as before.
         */
        public boolean standSpotWalkable = true;
        /**
         * A load that goes to far storage for an item takes all the item's cells it serves from there, leaving the
         * near chests' stock for the next loads: far first, near only when far
         * storage runs short. Whether the load goes far is decided as before (near storage first: the targeted look,
         * {@link #farTripMinCells}, the sorter lag). Live j0015 (2026-10-23, soul sand): gray carpet loads topped up
         * from the moss sorter every load once the near chests ran dry, 1.5 far trips a map at about 73 s each. false =
         * near chests first, far storage for the rest, as before.
         */
        public boolean farLoadFill = false;
        /**
         * A mob a fight found out of reach (its walks to it failed, "not chased again") is set aside by its entity id:
         * the engine starts no fight for it, and a fight for others
         * does not chase it, until it hits the bot, comes within reach (within 4 blocks on the bot's own level, or
         * within 8 blocks with its feet at the platform floor's height), {@link #fightSetAsideMinutes} pass, or a new
         * entity (a new id) comes. Any listed mob's hit ends the set-aside of every hostile mob (never hiding an
         * attacker); wandering traders and (trader) llamas stay set aside until their own hit. One log line per mob and
         * minute while a set-aside mob is in the fight area. Live 2026-10-24 (j0016 [2,1], soul sand): a wandering
         * trader and its llama on a structure above the floor started 17 fights in one 34-minute map (8.3 min): the old
         * memory matched by position and dropped its record whenever the client stopped tracking them (the bot out of
         * the server's entity range). false = that position memory, as before.
         */
        public boolean fightSetAsideUnreachable = true;
        /**
         * With {@link #fightSetAsideUnreachable}: minutes of game time (20 ticks a second) a mob stays set aside without
         * a hit or coming within reach; values below 1 count as 1.
         */
        public int fightSetAsideMinutes = 10;
    }

    public static class MapCfg {
        /** Ticks to hold the filled map at the platform centre before checking pixels. */
        public int holdTicks = 100;
        /** Attempts at filling + verifying the map before alerting. */
        public int maxFillAttempts = 3;
        public boolean rename = true;
        /** Anvil name for maps of multi-map jobs; {job} {x} {z} {id} are substituted. Truncated to 50 chars. */
        public String nameFormat = "{job} {x},{z}";
        /** Anvil name when the job is a single map (1x1): no tile coordinates needed. Same placeholders. */
        public String nameFormatSingle = "{job}";
        /** XP bottles withdrawn per trip to the XP chest. */
        public int xpBottlesPerTrip = 32;
        /**
         * Upload: an art block with no chest at this base, or an eyeblossom (its map colour follows the time of day),
         * is swapped for a stocked block with the identical 1.21.4 map colour, chosen per tile: the lower build cost
         * (piston-clearable before nuke blocks unless the tile nukes anyway, airplace before LOOK), the block the tile
         * already uses, carpets, then the most stock ({@code plan/SameColourSwap}). Only blocks every enabled platform
         * can clear are swapped in.
         */
        public boolean substituteSameColour = true;
        /** Block/item ids never swapped, in either direction (e.g. "minecraft:mushroom_stem"). */
        public List<String> substituteDeny = new ArrayList<>();
        /**
         * Same-colour substitution (upload, and {@code mapart resubstitute}) also swaps blocks the base stocks, per
         * tile, for a stocked block of the identical map colour that is better there: one the tile already uses more
         * (one item fewer for the tile's loads: a slot and a chest visit less), or one easier to build (no LOOK in
         * AIRPLACE mode, or piston-cleared where it would be the tile's only nuke block), never harder to build than
         * the block it replaces. Off: only blocks without a chest and eyeblossoms are swapped.
         */
        public boolean substituteStocked = false;
        /**
         * FILL_MAP: a tile with eyeblossoms is filled only when they all show the art's state (closed by day, open at
         * night). The bot waits at the map centre at most this long for the world to flip them, then goes BLOCKED.
         */
        public int eyeblossomWaitMinutes = 12;
        /**
         * FILL_MAP / CHECK_MAP: while blocks with a map colour sit above the art (someone using the colour-suppression
         * platform above the tile), the map would show them, so the bot waits at the map centre holding the map and
         * locks it only once it matches. A Discord reminder every this many minutes of waiting (0 = none).
         */
        public int occludedReminderMinutes = 30;
        /** That wait goes BLOCKED after this many minutes (0 = wait as long as it takes). */
        public int occludedMaxWaitMinutes = 0;
        /**
         * Plugin load: raise ZenithProxy's {@code debug maxCachedMaps} to 4096 when it is below 2048. Full, ZenithProxy
         * keeps no data for new map ids (the bot's map included); the plugin's own map capture does not need it, but
         * the fallback and players joining the proxy do. About 16 KB per map the server sends.
         */
        public boolean raiseZenithMapCache = true;
        /**
         * FILL_MAP fills the map from the cell of the tile nearest the bot on its way to the cartography table from which
         * the server draws every pixel (all four corners within 124 blocks; {@code FillStand}), instead of walking to
         * the station ({@code mapStandAt} or the centre) first; the station and its rings stay the fallback when that cell
         * cannot be walked to. Only on the tile's first fill (a refill after a mismatch goes to the station) and only
         * when the server's view distance less a chunk reaches every tile corner from that cell. Not for eyeblossom
         * tiles or while the art is covered (those waits stand at the station), nor for colour-suppression maps.
         * Live 2026-10-21..23: the last lane mostly ended at the east edge, 60-90
         * blocks of walking more through the centre than through the nearest covering cell. Default false: local 10x
         * 2026-10-23 (soul sand) the fill walk was 28 ticks shorter but LOCK's walk 22 ticks longer, its path meeting
         * the east trench's edge stop earlier, which ended the leg's sprint sooner; the coverage itself held (all four
         * corner pixels from 28 blocks off the station). false = the station, as before.
         */
        public boolean fillNearStand = false;
        /**
         * A map fill or hold ends as soon as the held map shows exactly the expected art (the same test as CHECK_MAP),
         * after at least 20 ticks held (vanilla draws one sixteenth of the columns per tick), instead of always
         * {@link #holdTicks}; a map that does not match yet is held the full time as before. Live: the 100-tick hold was
         * 5 s of every FILL_MAP. false = always the full hold.
         */
        public boolean holdUntilMatch = true;
    }

    public static class SiteCfg {
        public String dimension = "minecraft:overworld";
        public List<PlatformCfg> platforms = new ArrayList<>();
        public final StorageCfg storage = new StorageCfg();
        public Pos cartographyTable;
        /** Where the anvil stands; a replacement is placed here (needs a solid block below). */
        public Pos anvil;
        /**
         * Block ids the bot must never break (base infrastructure). Floor blocks and {@link Defaults#MACHINERY_BLOCKS}
         * (redstone parts that never appear in map art) are added implicitly, also to a list saved by an older build.
         */
        public List<String> protectedBlocks = new ArrayList<>(Defaults.PROTECTED_BLOCKS);
        /**
         * Boxes the bot never enters or stands in (its feet or head cell): Baritone goals and paths, stand spots and the
         * lane walk avoid them, and a walk whose path or position enters one fails. For machinery of other platforms the
         * bot has no reason to visit (the colour-suppression layers and the minecart "muncher" above the moss tile).
         * Each box: {@code min}, {@code max} (inclusive, any order) and an optional {@code name}. Empty = none.
         */
        public List<RegionCfg> noGoZones = new ArrayList<>();
        /**
         * Boxes whose blocks the bot never breaks (NUKE, VERIFY, any break), presses, opens or places into, whatever
         * the block: position-based protection for machinery built from block types that art also uses
         * (redstone_block, decorated_pot, concrete, TNT). Each box as in {@link #noGoZones}. Empty = none.
         */
        public List<RegionCfg> protectedRegions = new ArrayList<>();
        /**
         * Run-time only (never saved): boxes where {@link #noGoZones} and {@link #protectedRegions} do not apply right
         * now. The engine sets them while it builds a colour-suppression map (the platform's art volumes and its
         * {@code access} boxes, {@code SiteRules.suppressionOpenings}) and empties them otherwise.
         */
        public transient volatile List<RegionCfg> openings = List.of();
        /**
         * Run-time only (never saved): extra no-go boxes along the lethal edges of a colour-suppression platform's glass
         * floors, one block on each side (docs/COLOR_SUPPRESSION.md), set with {@link #openings} and never opened by them.
         */
        public transient volatile List<RegionCfg> hazards = List.of();
        /**
         * Run-time only (never saved): the art cells of the colour-suppression job being built (glass + 1 and + 2 over
         * its tile and noobline row), where a wrong block of the platform sorter's blocks may be broken in a repair even
         * when its type is protected (moss_block). Set only while such a job is the current one.
         */
        public transient volatile List<RegionCfg> repairable = List.of();
        /**
         * Boxes where the base's beacons give Haste (the bot's feet cell inside: covered). A beacon of level L reaches
         * {@code 10·L + 10} blocks horizontally from its block, from its y − that range up to the sky; its effect lasts
         * {@code 9 + 2·L} s after the bot leaves (13-17 s at level 4: it is renewed every 4 s in range). The fast nuke's
         * sweep waits at the edge of a zone for a fresh renewal when the
         * stretch outside ahead needs more Haste than is left. Each box as in {@link #noGoZones}. Empty = none.
         */
        public List<RegionCfg> hasteZones = new ArrayList<>();
        /**
         * Boxes (feet cell inside) where the edge stop's sneak is off ({@code build.edgeSneak}): a glass stair with
         * open diagonal cells beside its treads reads as an edge the whole way, and
         * a sneaking bot does not step down it (Baritone's "MovementDescend has taken too long", minutes per descent;
         * local CS run 2026-10-14). Only for walkable stairs with a wall or a long climb; never on a platform's
         * edges. Each box as in {@link #noGoZones}. Empty = the sneak applies everywhere (the previous behaviour).
         */
        public List<RegionCfg> edgeSneakOff = new ArrayList<>();
        /**
         * The base's map counter: a button the bot presses once for every map it stores (each map id), right after
         * STORE, while it stands by the supply chests. Only {@code mode} BUTTON; {@code standAt} optional. {@code pos}
         * may be left out when only the button's column is known: then {@link #mapCounterColumn} is searched. null =
         * off. Non-critical: a press that fails is one Discord alert, and the job carries on.
         */
        public ActuatorCfg mapCounter;
        /**
         * Column (x, z) of the map counter button when its y is not known, used while {@code mapCounter.pos} is not set:
         * at run time the bot looks for the one block whose id ends in {@code _button} in that column of the loaded
         * world, from 6 below to 6 above the first output chest's y (the supply walkway). None or several: one alert,
         * nothing is pressed.
         */
        public ColumnCfg mapCounterColumn;
        /**
         * COUNT presses the map counter only once the bot has been in game this long since it (re)connected AND the
         * counter's chunk has been loaded this long (real seconds, the engine clock): a press 0.3 s after a login jammed
         * the owner's counter twice (the pistons answered 26 ticks late and a sticky piston dropped its block). 0 = no wait.
         */
        public int mapCounterLoginDelaySeconds = 10;
        /**
         * The jam check's cube around the button (from the bot's chunk cache). 1 s after each press popped, its blocks
         * must be back to their state before the press, ignoring signal properties (power, lit, ...), with no piston
         * stuck out. Otherwise one "Map counter may be jammed" alert; later maps' counts are kept and pressed by
         * themselves once the counter checks clean again. Default 4: the owner's three counter pistons; 5 at most for the owner's
         * counter (its display's digit pistons, 6 and more blocks away, move with every count). At most 8. 0 = no check.
         */
        public int mapCounterCheckRadius = 4;
        /**
         * 2b2t's scheduled restart (system chat "Server restarting in N minutes..."): from this many minutes before the
         * announced time until the bot is back after the restart (or 2 minutes past the time with no restart), the moss
         * carts are switched off, a NUKE waits and no clear button is pressed (a clear already pressed finishes as
         * usual). 0 = off.
         */
        public int restartHoldMinutes = 5;
        /**
         * 2b2t's restart cycle in minutes: the server restarts this long after its last restart (live 2026-10-18 to
         * 11-14: 3 d 5 h 7 min = 4627, within half a minute each time). The engine keeps the time of the last restart
         * it saw and starts no colour-suppression ride that would still be under way at the next one; the ride starts
         * after that restart (or 30 minutes past its time with none seen). 0 = off (default).
         */
        public int restartEveryMinutes = 0;
    }

    /** A block column: x and z, any y. */
    public static class ColumnCfg {
        public int x;
        public int z;

        public ColumnCfg() { }

        public ColumnCfg(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    public static class PlatformCfg {
        public static final String COLOR_SUPPRESSION = "COLOR_SUPPRESSION";

        public String id = "moss";
        /**
         * MOSS, SOUL_SAND or COLOR_SUPPRESSION (docs/COLOR_SUPPRESSION.md). An enabled COLOR_SUPPRESSION platform makes
         * the upload accept PNG-to-NBT "Suppress (2-Layer)" CrubTech exports; without one they are refused.
         */
        public String kind = "MOSS";
        public boolean enabled = true;
        /** NW corner of the 128x128 map area; must be ≡ -64 (mod 128). */
        public int minX;
        public int minZ;
        /** Y of the art layer (floor Y + 1). */
        public int artY;
        public String floorBlock = "minecraft:moss_block";
        /**
         * The platform's collection system (piston clear, drop point, hopper minecarts) sorts every item back into the
         * near chests within minutes: nothing is lost, a near shortage is the sorter still working (soul sand, the owner
         * 2026-10-14). On such a platform RESTOCK does not fetch a short near item from far storage at first: its last
         * cells wait, the lanes that can be built go first, and when nothing else is left the bot waits by the sorter
         * chests ({@link BuildCfg#sorterRefillPollSeconds}) until the near count rises, falling back to far storage
         * when it has not risen for {@link BuildCfg#sorterRefillMinutes} (or the item was never in the near chests).
         * SELECT and STOCK_CHECK count an item the near chests hold or held as in stock up to the tile's need, so no map
         * is skipped or reordered for sorter lag. false = far trips and stock skips as before.
         */
        public boolean sorterRefills = false;
        /**
         * Soul sand with {@link #sorterRefills}: the cell where the sorter's items fall past the bot (just past the east
         * end of the front rows, an edge) while they are being sorted: standing there picks them up (the owner, 2026-10-14).
         * RESTOCK walks there when a load is short of a lagging item by more than the near chests hold, and stands until
         * {@code build.sorterPickup*} say it is enough. null = off, as before. Feet cell, absolute coordinates (kept in
         * the local config, never in git).
         */
        public Pos sorterPickup;
        /**
         * With {@link #sorterPickup}: the cell the stand goes to first (the north centre of the platform, on the storage
         * stand row north of the tile): the sorter ticks only near the bot (simulation distance 5 chunks), so from the
         * edge its upstream part stands still (the owner, 2026-10-14). The bot walks there and, as soon as it arrives,
         * on to the edge. null = straight to the edge, as before.
         */
        public Pos sorterPickupPrime;
        /**
         * The row to run along in the storage band: absolute z and feet y of a row whose
         * floor is soul sand/soul soil with a clear walking level (soul sand: tile-relative z -3, two rows out from the
         * chests). Walks whose start and goal are both within {@code build.rowRunBand} blocks (in z) of it and at least
         * {@code build.rowRunMinDx} apart in x go to the row, run along it with the plugin's own keys (Baritone does not
         * stay on a row between waypoints) and on to the goal. null = off; replaces the pickup stand's row scan.
         */
        public Pos runRow;
        /**
         * Where items picked up at {@link #sorterPickup} that no load needs are thrown back (the sorter's fall, over the
         * edge); null = the cell one block below the pickup cell. The throw is aimed at this point from where the bot
         * stands ({@code build.pickupThrowBack}).
         */
        public Pos sorterPickupReturn;
        /**
         * Where junk ({@code build.dropJunk}) is thrown: the cell to stand on and a water block to throw at (the ocean, not
         * the sorter's channel; the drop point's aim code). Both set: junk goes there, at a restock when the walk is at most
         * {@code build.junkDropMaxDetour} blocks. Either null: junk is not dropped at all, as it no longer goes into the
         * sorter (the owner, 2026-10-14).
         */
        public Pos junkDropStand;
        /** The water block {@link #junkDropStand} throws at. */
        public Pos junkDropAt;
        /** Stand here to fill the map and (if stayDuringClear) while the clear runs. */
        public Pos center;
        /**
         * Where the map is filled and checked (FILL_MAP, CHECK_MAP), and where the bot goes to load the tile; null =
         * {@link #center}. Soul sand: the floor under the button cross, because the cross is reached only by the long
         * east walkway (the bot is on the art layer after VERIFY anyway). Same x and z as {@code center} works best.
         */
        public Pos mapStandAt;
        /**
         * After the clear, step off the centre structure and drop to this floor cell (soul sand: the cross at feet
         * artY + 6 down onto the empty floor, about 4 HP of fall damage before armour) instead of walking back down.
         * Only while health is at least {@code hopDownMinHealth}; otherwise, and when the drop fails, the next walk
         * takes the stairs or walkway. null = off.
         */
        public Pos hopDownTo;
        /** Lowest health (of 20) at which the bot hops down from the centre structure ({@link #hopDownTo}). */
        public int hopDownMinHealth = 14;
        /**
         * A feet cell off the art layer, outside every clear sweep and more than 4 blocks from any clear button or its
         * stand spot, where a bot without the tile waits: the step-off after a band or an abandon when the centre is a
         * clear stand (soul sand: the button cross). null (default): the centre when it is no clear stand, else none
         * (the step-off stays where it stands).
         */
        public Pos offLayerAt;
        /**
         * Fight hostile mobs here ({@link CombatCfg}); null = the kind's default: on for SOUL_SAND (pillagers spawn
         * there), off for MOSS.
         */
        public Boolean fightMobs;

        /** {@link #fightMobs}, with the kind's default when unset. */
        public boolean fightsMobs() {
            return fightMobs != null ? fightMobs : "SOUL_SAND".equalsIgnoreCase(kind);
        }
        /**
         * Actuators that start the piston clear (normally a middle button). Every actuator whose {@code probe}
         * matches (or that has no probe) is pressed, in order; if none applies the engine goes BLOCKED.
         */
        public List<ActuatorCfg> clear = new ArrayList<>();
        /**
         * Optional item collection (moss: hopper minecarts) switched by two actuators. ON for nuke + clear, OFF while
         * building. Pressing a clear button may also switch it ON (moss), so OFF is re-checked before building.
         */
        public ActuatorCfg collectionOn;
        public ActuatorCfg collectionOff;
        /** Block that shows the collection state (moss: a redstone lamp); null = state unknown, press blindly once. */
        public Pos collectionIndicator;
        /** Collection is ON when the indicator matches this state (only the given properties are compared). */
        public String collectionIndicatorOn = "minecraft:redstone_lamp[lit=true]";
        /**
         * Longest the item collection keeps running after the art layer reads clear (items still in transit); 0 = it
         * goes off at once. The whole time when the dropped items on the platform cannot be counted.
         */
        public int collectionRunAfterClearSeconds = 30;
        /**
         * The item collection goes off earlier, once no dropped-item entity has been seen on the platform (the tile,
         * floor - 2 to art + 3) for this long. The bot counts them from the centre.
         */
        public int collectionQuietSeconds = 3;
        /** Blocks the piston clear removes by itself. */
        public List<String> clearableBlocks = new ArrayList<>(Defaults.PISTON_CLEARABLE);
        /** Full blocks allowed in art; the bot breaks them before triggering the clear. */
        public List<String> nukeBlocks = new ArrayList<>(Defaults.NUKE_BLOCKS);
        public int clearTimeoutSeconds = 600;
        public int clearRetries = 2;
        /** Soul sand: stay at {@link #center} until the clear finishes (chunk loading). */
        public boolean stayDuringClear = true;
        /** Soul sand: wear Soul Speed boots here, mend them with XP after each map. */
        public boolean soulSpeedBoots = false;
        /**
         * COLOR_SUPPRESSION: Y of the plain-glass floors, bottom first (the owner's base: 200 and 214, 14 apart). Each layer's
         * art sits on its glass, at glassY + 1 and + 2. {@link #artY} is not used for this kind.
         */
        public List<Integer> glassY = new ArrayList<>();
        /**
         * COLOR_SUPPRESSION: boxes where the no-go zones and protected regions open while this platform builds a
         * colour-suppression map (the access path, the walkways); the art volumes over the glass open by themselves.
         */
        public List<RegionCfg> access = new ArrayList<>();
        /**
         * COLOR_SUPPRESSION: where the access path reaches the layers (a feet cell on it). A bot below the bottom glass
         * walks here first before it walks to a cell on the layers: the pathfinder's partial paths otherwise head
         * straight for the goal at ground level and end under the platform with no way up. Null = no waypoint.
         */
        public Pos entry;
        /**
         * COLOR_SUPPRESSION: a feet cell at the bottom of the access path (the storage walkway). A bot on the platform
         * walks here before each restock round, so the stand spot search starts from the walkway: from the layers it
         * rates the sorter's roof reachable (the flood only sees the top of the stair), and the walks to the roof spots
         * stall on the stair for minutes (local test 2026-10-14). Null = restock from wherever the bot stands.
         */
        public Pos foot;
        /**
         * The platform's own chests for leftovers (owner, 2026-11-13: the colour-suppression platform has an input chest
         * of its own sorter; its leftovers went into the moss sorter's drop point). A job on this platform deposits its
         * leftovers here and never uses the site's {@code storage.returnChests} or {@code storage.returnDrops}. Empty
         * (default) = the site's, as before.
         */
        public List<Pos> returnChests = new ArrayList<>();
        /**
         * COLOR_SUPPRESSION: the platform's own material chests (docs/COLOR_SUPPRESSION.md). A colour-suppression
         * job restocks only from containers in these boxes, never from the sorters of the other platforms, and its
         * stock checks see only them; every other job leaves them alone. Empty (default) = the site's
         * {@code storage.materialRegions} for all jobs, as before.
         */
        public List<RegionCfg> materialRegions = new ArrayList<>();
        /**
         * COLOR_SUPPRESSION: the row of floor blocks the bot stands on in front of the chest columns, one block per
         * column, each naming the material that column holds (the owner's index row; a box at the floor level). The
         * blocks are the platform's palette, readable from the world before any chest is scanned. Null = none.
         */
        public RegionCfg materialRow;
        /**
         * COLOR_SUPPRESSION: blocks an export may use when the {@link #materialRow} cannot be read (not loaded, none):
         * item ids. A block outside the palette refuses the upload and the job. Empty = no restriction then.
         */
        public List<String> allowedBlocks = new ArrayList<>();
        /**
         * COLOR_SUPPRESSION, for tests: accept exports with transparent pixels (they show what lies below the glass, so
         * a real map would be wrong there). Default false: refused.
         */
        public boolean allowTransparent = false;
        /**
         * COLOR_SUPPRESSION: a new job prints the top layer's passes first, then the bottom layer's (owner 2026-11-14:
         * the muncher is still clearing the bottom layer when the cart reaches the brake, so the next map starts on
         * the layer that is empty already). false = the old order, bottom first. A job is stored with the order of
         * its upload; a queued job that has not started any pass takes the order set here when it is first selected
         * (its stored passes are put in that order), a job with a pass started keeps its own.
         */
        public boolean topLayerFirst = true;
        /**
         * COLOR_SUPPRESSION: the bot rides the muncher itself after the print (fill, ride, lock, store, reset check;
         * docs/COLOR_SUPPRESSION.md). Off by default: the job ends READY_FOR_RIDE.
         */
        public RideCfg ride = new RideCfg();
        /**
         * FAST nuke sweep (the NUKE): the corner of the 128 × 128 map the walk off the art layer leaves from ("NW",
         * "NE", "SW" or "SE"; north is −z, east +x). The sweep starts at the lane end that makes its walk to the first
         * lane plus its last lane's end to that corner shortest, so it ends near the way off instead of anywhere.
         * Live j0015 (soul sand, 2026-10-23): the walk
         * from the sweep's end to the way off at the north-east corner took 15-17 s of a map's NUKE (1 s when the sweep
         * ended there). Empty (default) = the lane end nearest to the bot, as before.
         */
        public String nukeEndCorner = "";

        public PlatformGeometry geometry() {
            return new PlatformGeometry(minX, minZ, artY);
        }

        public boolean isColorSuppression() {
            return kind != null && COLOR_SUPPRESSION.equalsIgnoreCase(kind.strip());
        }
    }

    /**
     * The colour-suppression ride (docs/COLOR_SUPPRESSION.md): where the cart starts, the launch button, the brake
     * in the booth and the booth's table, anvil and chests, the boxes the ride opens, the machine boxes the guard
     * compares and the stasis chunk-loader cells it checks. Real coordinates: live configs only.
     */
    public static class RideCfg {
        public boolean enabled = false;
        /** The start rail the cart is put on, and the cell the bot stands in to put it there and get in. */
        public Pos startRail;
        public Pos startStand;
        /** The launch button, pressed from the seat. */
        public Pos button;
        /** Yaw the bot looks along to push the cart off the start rail (90 = west). */
        public float pushYaw = 90f;
        /** The rail the cart stops on at the end (in the booth). */
        public Pos brake;
        /** Booth: cartography table and anvil for the lock and the name; output chests (empty = site output chests). */
        public Pos cartographyTable;
        public Pos anvil;
        public List<Pos> outputChests = new ArrayList<>();
        /** Chests the minecart comes from. */
        public List<Pos> cartChests = new ArrayList<>();
        /**
         * The cart is the platform's own: it stands on the start rail before every ride and is used again (owner,
         * 2026-11-13: "it is reused and already placed"; after the first live ride: "have it destroy the minecart and
         * replace it at the start when finished with the main ride"). The bot takes no minecart from
         * {@link #cartChests} and gets into the cart on the start rail (one it carries from an earlier ride is put
         * there first; neither is BLOCKED). After the ride it breaks the cart on the brake, picks it up, and after the
         * lock and the name puts it back on the start rail from {@link #startStand} without getting in, before it
         * stores the map. A cart that cannot be put back is an alert, never a reason to hold the map; the item stays
         * in the inventory. Default false: the bot brings its own cart and takes it off the brake.
         */
        public boolean reuseCart = false;
        /**
         * A ride lost after its launch (the bot out of the cart with no cart in reach: the cart went on with the
         * muncher) is recovered instead of BLOCKED: the bot waits for the muncher's run to end, walks to the booth,
         * takes the cart off the brake, puts it back on the start rail and prints the map again from its first pass
         * (the map it made is filled again). false = BLOCKED as before.
         */
        public boolean recoverLostRide = true;
        /**
         * A server restart announced during the ride (2b2t warns from 5 minutes before) pauses the ride at a lane end
         * (owner 2026-11-15, docs/COLOR_SUPPRESSION.md): once the cart stands in a lane-end
         * hold on the start rail's side, the map leaves the hand, the bot gets out, breaks the cart and stands in the
         * hold's own cell with the map in hand until the restart. Back in game it puts the map away, walks the
         * viewpoints, rides the preload lap, goes back to the hold, puts the cart on the next lane's first level rail,
         * gets in with the map in hand and pushes off. false = the ride goes on through the restart as before.
         */
        public boolean restartPause = false;
        /**
         * Boxes where the no-go zones and protected regions open during the ride phases: the way to the start, the
         * start area (the start rail included), the walkway and the booth. The rail field itself stays closed (the bot
         * only crosses it in the cart).
         */
        public List<RegionCfg> access = new ArrayList<>();
        /**
         * Lethal-edge margins along the ride's walks (the walkway's open side): like the layers' lethal edges, the bot
         * never steps into these cells but a diagonal step may cut their corners. They apply with {@link #access}.
         */
        public List<RegionCfg> edges = new ArrayList<>();
        /**
         * Boxes of the machinery the guard compares with its reference before and after every ride (the art volumes
         * are left out); a difference is BLOCKED and the bot does nothing more to the machinery. Empty = no guard.
         */
        public List<RegionCfg> machine = new ArrayList<>();
        /**
         * Boxes inside {@link #machine} the guard leaves out because they change every ride by design (the drop bin's
         * standing fill of art blocks).
         */
        public List<RegionCfg> machineIgnore = new ArrayList<>();
        /**
         * Where the guard takes its readings: every machine chunk must be seen from one of these (in client view) before
         * a ride and after it. The bot walks to each in turn. Empty = the start stand only.
         */
        public List<Pos> viewpoints = new ArrayList<>();
        /** Boxes holding the stasis chunk-loader cells (a trapdoor over a bubble column); each must show a pearl. */
        public List<RegionCfg> stasis = new ArrayList<>();
        /** Cells the stasis boxes must hold (the owner's base: 151); fewer found is BLOCKED. 0 = any number. */
        public int stasisCells = 0;
        /** Cells seen within this many blocks (horizontal) count for the pearl check (the server's tracking range). */
        public int pearlSightRange = 48;
        /** The cart standing still this long fails the ride (BLOCKED). */
        public int stallMinutes = 10;
        /** The whole ride longer than this fails it (BLOCKED). */
        public int maxRideMinutes = 180;
        /**
         * How long the ride takes from the launch to the brake, for the status ("41 of ~75 min") and the ETA only
         * (measured live: 75.5 and 75.0 minutes). The platform's last completed ride replaces it when it lies between
         * half and one and a half times this value.
         */
        public int expectedMinutes = 75;
        /** Health below this in the cart fails the ride (BLOCKED); the plugin never disconnects. */
        public float minHealth = 10f;
        /** Arm swing every this many seconds in the cart (the guide's Anti-AFK swing); 0 = none. */
        public int swingSeconds = 30;
        /** After the ride the layers must be empty and the machines home within this many minutes, else BLOCKED. */
        public int settleMinutes = 30;
        /**
         * The pre-ride machine guard differing from its reference: the bot reads again every 20 s for as long as the
         * difference keeps changing (machines of the last ride on their way home), up to {@link #settleMinutes}. A
         * difference that is the same in consecutive readings (the same cells, the same blocks) for this many minutes
         * is not machines moving: BLOCKED at once (live 2026-11-14: 35 minutes waited on 64 cells of white concrete
         * turned glass panes, identical in every reading). 0 = at the second identical reading.
         */
        public int guardConfirmMinutes = 2;
        /**
         * true: after the map is stored and counted the job waits by the muncher until both layers are empty and the
         * machines home (RIDE_RESET), and only then is DONE. false (default, owner 2026-11-14): the job is DONE once
         * the map is stored and counted and the next job starts; a pass of the next map then waits for its own layer
         * to be empty, and the pre-ride guard waits for machines still on their way home, while its difference keeps
         * changing, up to {@link #settleMinutes} ({@link #guardConfirmMinutes}).
         */
        public boolean waitForReset = false;
        /**
         * The preload lap before the ride (owner, 2026-11-13: "before the main minecart ride it needs to ride the track
         * at y=217 also ... pre loading all the chunks is neccesary"): docs/COLOR_SUPPRESSION.md. Null (or
         * not {@link PreloadCfg#configured}) = no lap, the ride as before.
         */
        public PreloadCfg preload;

        public boolean configured() {
            return enabled && startRail != null && startStand != null && button != null && brake != null
                && cartographyTable != null;
        }

        /** The preload lap is configured ({@link #preload}). */
        public boolean preloads() {
            return preload != null && preload.configured();
        }
    }

    /**
     * The preload lap of a colour-suppression ride ({@code ride.preload}, phase RIDE_PRELOAD): after the pre-ride
     * checks and right before the main cart is boarded, the bot gets into the platform's own cart standing on
     * {@link #startRail} (it never places or breaks one), presses {@link #button} from the seat, rides until the cart
     * stands still at {@link #end} and gets out. The watch rules are the ride's ({@code ride.minHealth},
     * {@code ride.swingSeconds}) with this lap's own {@link #stallMinutes} and {@link #maxMinutes}. Any failure is
     * BLOCKED.
     */
    public static class PreloadCfg {
        /** The rail the lap's cart stands on, and the cell the bot stands in to get in. */
        public Pos startRail;
        public Pos startStand;
        /** The lap's launch button, pressed from the seat. */
        public Pos button;
        /**
         * Yaw the bot looks along with forward held when the cart has not started by itself after the press (the
         * ride's {@code pushYaw}); null = no push: the button alone starts the cart, and a cart that does not start
         * fails the lap.
         */
        public Float pushYaw;
        /** The rail the cart stops on at the end of the lap; null = {@link #startRail} (a loop). */
        public Pos end;
        /** The cart standing still this long on the way fails the lap (BLOCKED). */
        public int stallMinutes = 2;
        /** The whole lap longer than this fails it (BLOCKED). */
        public int maxMinutes = 10;
        /**
         * The main cart must be boarded within this many minutes of the lap's end (and on the same connection), else
         * the pre-ride checks and the lap run again. 0 = no time limit (the same connection only).
         */
        public int validMinutes = 15;
        /** Boxes opened like {@code ride.access} (the way to the lap's cart, its start rail and button). */
        public List<RegionCfg> access = new ArrayList<>();
        /** Lethal-edge margins like {@code ride.edges} (the track's own cells next to the stand). */
        public List<RegionCfg> edges = new ArrayList<>();

        public boolean configured() {
            return startRail != null && startStand != null && button != null;
        }

        /** Where the lap ends: {@link #end}, or the start rail. */
        public Pos endRail() {
            return end != null ? end : startRail;
        }
    }

    public static class ActuatorCfg {
        public Pos pos;
        /** BUTTON (press once) or LEVER (flip on, wait holdTicks, flip back if holdTicks > 0). */
        public String mode = "BUTTON";
        public int holdTicks = 0;
        /** Optional standing position for pressing it; default = pathfind next to it. */
        public Pos standAt;
        /**
         * Optional side probe: the actuator is used only while the world block at {@code probe} matches
         * {@code probeBlock} (e.g. the moss flying machines are parked on this button's side). No probe = always.
         */
        public Pos probe;
        public String probeBlock;
    }

    public static class StorageCfg {
        /** Boxes whose containers are auto-scanned and indexed as material chests (minus role chests below). */
        public List<RegionCfg> materialRegions = new ArrayList<>();
        /** Sorter input / item return chests: leftover materials go here (nothing else is ever deposited). */
        public List<Pos> returnChests = new ArrayList<>();
        /**
         * Used when {@link #returnChests} is empty: the bot stands at {@code standAt} and throws leftovers into
         * {@code target} (moss: the water channel that carries items to the sorter).
         */
        public List<DropPointCfg> returnDrops = new ArrayList<>();
        public List<Pos> mapChests = new ArrayList<>();      // empty maps
        /**
         * Stale filled maps of a stored tile (an unlocked map left over from a cover wait, or one that got no map data):
         * after STORE they go here, one by one, never to the output chest and never dropped. Empty = the
         * {@link #mapChests} (a withdrawal takes empty maps by id, so a filled map there is never taken for one).
         */
        public List<Pos> scrapMapChests = new ArrayList<>();
        public List<Pos> paneChests = new ArrayList<>();     // clear glass panes
        public List<Pos> outputChests = new ArrayList<>();   // locked, renamed maps
        public List<Pos> xpChests = new ArrayList<>();       // bottles o' enchanting
        public List<Pos> anvilChests = new ArrayList<>();
        public List<Pos> toolChests = new ArrayList<>();
        public List<Pos> foodChests = new ArrayList<>();
        public List<Pos> bootChests = new ArrayList<>();     // Soul Speed + Mending boots
        /**
         * The flight kit's chests ({@code build.elytraLegs}): firework rockets, the
         * elytra and its spare. Empty = the chest or barrel a sign saying "rocket" (any case, front or back) is on: a
         * wall sign on one of its faces or a standing sign on top, read from ZenithProxy's chunk cache. Used only with
         * the switch on.
         */
        public List<Pos> rocketChests = new ArrayList<>();
    }

    /**
     * Teleport safeguard: an unexpected server-forced move halts the engine (persisted pause, Discord alert);
     * only a manual resume restarts it. A setback onto the bot's own recent path is not one ({@link
     * SafetyCfg#setbackRecentPath}).
     */
    public static class SafetyCfg {
        /**
         * Low health that still drops while the bot stands still: the bot
         * leaves the cell first (a short walk to open, dry floor a few blocks away, never into water, lava, fire or the
         * like) instead of standing there; only if health still drops after that does it go BLOCKED. A listed mob near
         * a bot that listed mobs hit is fought instead ({@code combat.selfDefense}), or, with no fight possible, the bot
         * retreats from it. Off: the old stand-still-then-BLOCKED.
         */
        public boolean leaveWhenHurt = true;
        /**
         * No totem of undying in the offhand and none in the inventory (one used up): one always-on "No totem" alert,
         * and no task that walks starts or carries on (the running one parks at its next safe point) until a totem is in
         * the offhand again. Fights, eating and leaving harm still run. The offhand repair puts an inventory totem back.
         */
        public boolean requireTotem = true;
        public boolean teleportGuard = true;
        /** Halt on a server teleport moving the bot further than this (blocks). Smaller ones count as setbacks. */
        public double teleportStopDistance = 8.0;
        /** Halt if the bot is further than this (horizontal blocks) from every enabled platform centre. */
        public int maxDistanceFromBase = 256;
        /** Alert (no halt) when this many small setbacks happen within the window. 0 = never. */
        public int setbackAlertCount = 10;
        public int setbackAlertWindowSeconds = 300;
        /**
         * Back off (stop now, stand still, then carry on; not a pause) when this many small setbacks happen within
         * {@link #setbackBurstWindowMillis}. Setbacks while standing still do not count. 0 = never.
         */
        public int setbackBurstCount = 5;
        public int setbackBurstWindowMillis = 2000;
        /** How long a back-off stands still: no walking, clicks or inventory actions. 0 = no back-off. */
        public int setbackBackoffSeconds = 5;
        /**
         * At most this many back-offs within 2 minutes, each followed by an escape (the rungs of the escape ladder); the
         * next burst pauses with a "Stuck" alert (not a SAFETY STOP). 0 = never pause.
         */
        public int setbackBackoffMaxPerWindow = 3;
        /**
         * A teleport further than {@link #teleportStopDistance} onto a position the bot stood at within
         * {@link #setbackRecentSeconds}, at most {@link #setbackRecentMaxDistance} away, is an anti-cheat setback (a
         * lag spike, live 2026-10-11), not a SAFETY STOP: logged, and the bot carries on. false = it halts as before.
         */
        public boolean setbackRecentPath = true;
        public int setbackRecentSeconds = 60;
        public double setbackRecentMaxDistance = 32.0;
        /**
         * After this many setbacks onto the same spot within 2 minutes the bot stops, re-syncs its movement state and
         * re-requests the blocks around the spot from the server (a look and an off-hand click per block, which the
         * server answers with the real blocks there), then carries on. 0 = never.
         */
        public int setbackResyncRepeats = 3;
        /** Ticks after bot ticks start during which teleports are treated as the login position sync. */
        public int startGraceTicks = 100;
        /**
         * Long rollbacks onto the bot's path ({@link #setbackRecentPath}): a teleport onto a position the bot stood at
         * within {@link #setbackRecentSeconds}, further than {@link #setbackRecentMaxDistance} but at most
         * {@link #setbackRecentFarMaxDistance} away, is a setback as well when the bot could have walked that far since
         * it stood there: at most this many blocks per second of that time (the bot sprints about 10). Live 2026-10-20
         * 17:39 and 20:09 (2b2t lag: "container did not open", "eating failed" just before): 66.0 and 118.7 blocks back
         * to where the bot had sprinted past 15 and 12 s earlier were SAFETY STOPs that waited 2 and 21 minutes for a
         * resume. 0 = off: only the {@link #setbackRecentMaxDistance} rule, as before.
         */
        public double setbackRecentSpeed = 12.0;
        /** The farthest rollback onto the bot's path that {@link #setbackRecentSpeed} accepts, in blocks. */
        public double setbackRecentFarMaxDistance = 160.0;
        // No health or starvation disconnect here: disconnecting for health is ZenithProxy's AutoDisconnect, configured
        // by the owner. The former keys disconnectHealth and starvationDisconnect are ignored when an old
        // mapart.json still has them (Gson skips unknown fields).
    }

    /** A place to get rid of leftovers by dropping them (no return chest). */
    public static class DropPointCfg {
        /** Block the bot's feet stand on (in) while dropping. */
        public Pos standAt;
        /** Block the items should land in, e.g. a water source of the channel feeding the sorter. */
        public Pos target;
    }

    /** An inclusive box of blocks; {@code min} and {@code max} may be given in any order. */
    public static class RegionCfg {
        public Pos min;
        public Pos max;
        /** Optional label shown in reports and refusals (no-go zones, protected regions). */
        public String name;

        public boolean contains(Pos p) {
            return contains(p.x(), p.y(), p.z());
        }

        /** False when {@code min} or {@code max} is missing. */
        public boolean contains(int x, int y, int z) {
            if (min == null || max == null) return false;
            return x >= Math.min(min.x(), max.x()) && x <= Math.max(min.x(), max.x())
                && y >= Math.min(min.y(), max.y()) && y <= Math.max(min.y(), max.y())
                && z >= Math.min(min.z(), max.z()) && z <= Math.max(min.z(), max.z());
        }
    }
}
