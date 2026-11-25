package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The lane placer's decisions, tick by tick, without ZenithProxy.
 * {@link PlaceLaneTask} runs it on ZenithProxy; the tests run it on a simulated platform with collision, jumping and
 * line of sight. Everything that touches the game goes through {@link Io}.
 *
 * <p><b>Walk (RUN).</b> Baritone brings the bot to the lane start; along the lane the bot submits its own movement
 * keys every tick (relative to the yaw it looks with, {@link Aim#movementKeys}), steering back to the lane line and
 * jumping when it walks into a block. Corridor cells (the walking column) are only placed once entirely behind the
 * player's box, other cells when they do not touch the box grown by 0.3 ({@link LaneWalk}). Per tick at most one
 * use-item-on, pipelined ({@link PlacePipeline}); a sent placement is pending until the world shows it or the server
 * answers without it ({@link PlaceTracker}: confirmed / late / rejected / stack end / dropped / unknown; the pacer
 * slows down as its policy says ({@link PlacePacing}), one retry, then skipped; a click the server's click limiter
 * dropped, and one rejected at a used-up stack, is retried without counting, {@link #MAX_DROPS} times per cell). A
 * cell whose stack is used up by clicks still in flight goes from another hotbar stack of its item with items left
 * ({@link PlacePacing#stackSwitch}), else waits for their acks instead of being clicked with an empty hand
 * ({@link #usedUp}). Each lane starts the pacer's clock afresh ({@link AdaptivePacer#newRun}).
 *
 * <p><b>Lane start</b>. Baritone gets two goals, the feet cell within 1 of the start
 * cell, then the start cell itself, each with the feet within 2 blocks of the art layer; Baritone reports a goal reached
 * as soon as the feet cell is in it, and the walk is over only once {@link #nearStart} holds: within 1.2 of the start
 * along the line, 0.9 across it and 1.5 in height. A walk whose last goal is reached without that FAILS ("cannot reach
 * lane start ...: reached ... but the arrival test still fails"), and {@link #unreachedStart} hands the start to the
 * task's fallbacks (break-out, the break walk, stand spots). With {@link Io#startOnFloor} ({@code
 * build.laneStartOnFloor}) the goals take the feet within 1 block, as the arrival test does: live (soul sand, lanes
 * starting on the tile's south row) Baritone walked on top of the glass wall two blocks high south of that row and
 * stopped on its edge above the start cell, feet cell in the goal, the box still on the wall, 2.0 above the art layer.
 *
 * <p><b>Paced walk</b> ({@link PlacePacing#pacedWalk}, {@code build.pacedLaneWalk}, off by default; forward walks on
 * a floor line in AIRPLACE mode). Instead of stopping whenever a cell behind or alongside is unplaced (stop and go),
 * the walk keeps moving as fast as the click queue allows: each queued cell, in the pipeline's order, must be clicked
 * before it leaves reach ({@link #pacedCap}; candidates in deadline order, {@link #deadline}). The bot walks at full
 * speed while the cap allows {@link #PACED_STOP_SPEED} and stops (keys up) below it; no partial speeds, which would
 * take a toggled forward key. It still stops for an inventory move (MultiActionsC), for a LOOK cell behind (as
 * before), and for a cell close to leaving reach that waits for its stack, its ack or its retry. Same packets as the
 * stop-and-go walk: one movement packet per tick, walking keys, no sprint.
 * The lane line separately reports actual keys-up RUN ticks ({@link StandingTicks}), their no-click reasons, and
 * the binding cap conditions with first limiting cells in lane-relative row/column coordinates. A cap hold can
 * still steer or move on after the existing timeout, so it is not itself a standing tick. PREPARE, SPOT and park
 * ticks are outside these standing counters; the walk-tick reason counters still cover all RUN ticks.
 *
 * <p><b>Step back</b> ({@link Params#stepBack}, {@code build.laneStepBack}, on by default). A TODO cell just behind
 * reach gets one short return on a safe open floor line, at most {@link #STEP_BACK_MAX} beyond the reach edge and
 * {@link #STEP_BACK_LIMIT} returns per lane. One return serves the missed cells together, waits for their answers,
 * then carries on forward. A failed correction goes to stand spots. Walls, edges and setback cells forbid a return.
 * With {@link Io#stepBackClearPath} ({@code build.laneStepBackClearPath}, off by default) the return path stays clear
 * of blocks the proxy does not show yet: while the box may still go back over a cell (the return point less the slide
 * after it, or the slide of any backward move) nothing is clicked there, and the walk back waits while a block clicked
 * there before is in flight. Without it the corridor cell 0.05 behind the box goes up as in the forward walk and the
 * box backs into it before its block update arrives: the server's own collision check (isPlayerCollidingWithAnythingNew)
 * teleports the bot back. Live 2026-10-23 (soul sand): 8 of 10 setback bursts began with such a teleport, 5 of them
 * within 1 s of a step back's start.
 * The forward twin, {@link Io#forwardClearPath} ({@code build.laneForwardClearPath}, on by default): riding 0.2-0.25 off
 * the line (the movement keys go in 45° steps, so a small offset is never steered out) the box pokes into the side
 * column. A cell beside the line ({@code |lateral| <= 1}) is clicked only where the box, swept forward by what the walk
 * may still move before the click's answer ({@link #forwardReach}: the latency plus {@link #FWD_EXTRA_TICKS} ticks of
 * held keys, at most {@link #FWD_SWEEP_MAX}), does not reach it ({@link #inForwardPath}); such a cell is deferred and
 * placed once the box has passed it. And the walk holds its keys (brakes, if the slide alone would do it) for a tick
 * whose move would take the box into a side cell whose block the proxy does not show yet ({@link #forwardPathInFlight}).
 * Live 2026-10-27 (soul sand): a carpet clicked just ahead of the box went up 1-56 ms before the move that entered its
 * cell; Paper's teleport, then 5 Grim setbacks in 0.3 s and a 10 s back-off.
 *
 * <p><b>In-reach greedy</b> ({@link Params#greedy}, {@code build.placeInReachGreedy}, off by default; AIRPLACE mode).
 * The engine hands the run the later lanes of its restock load (their cells still to
 * place; it starts every lane without the cells already placed). Airplace cells of those lanes are placed early
 * ("extras"), and so are this lane's own cells while Baritone walks the bot to the lane start (a lane change, the walk
 * from the chests) or back to it after a park; this lane's walking column always waits for the walk. Never early: a
 * full block on or beside its lane's walking line (column 0 or ±1: it would wall that walk in, a pocket or a 1-wide
 * slot where it is still open), a block that hides cells from the eye in a lane with LOOK cells, a LOOK cell, a full
 * block within one cell of the bot's feet or, while Baritone walks, of the path it still walks (none while that path is
 * unknown), an item that is not in the hotbar (no inventory move for it), and an extra whose item the inventory cannot
 * spare for every cell of this lane and the load still to place. In the walk an extra only gets a tick in which this
 * lane's cells have no click (they always come first: the speed cap and the stops count on their clicks only); while
 * Baritone walks, the cells go in order of leaving reach on the way to the goal and the click goes out only while
 * {@link Io#clickWhileWalking} (airplace needs no rotation). Same pacer, stack guard, retries and airplace and offhand
 * guards. An extra placed is reported with {@link Io#placedExtra}; one given up stays its own lane's (never reported as
 * skipped); the run settles only when no extra is in flight.
 *
 * <p><b>Full blocks</b> (collision taller than a step, {@link #TALL}) wall the walking line, and they and tall plants
 * (roots, flowers: outline above {@link #SHADE}, {@link LaneWalk.Cell#shades}) hide the cells beyond them from the eye.
 * So such a block (1) is never placed while an unplaced cell it would hide is left ({@link LaneWalk#shadows}: outer
 * columns first, the rows behind first) and (2) holds the walk while it waits, within reach of itself and of the cells
 * it waits for ({@link LaneWalk#holdLimit}). A full block (3) in an inner column goes up only once the player has
 * passed it, so the line ahead stays open and the walls stand behind, and (4) never closes the last open side of the
 * floor cell the bot stands in: at a lane end that makes the walk turn back, the bot instead climbs onto the full block
 * behind it (a 1-block jump; nothing on the platform is higher) and places the rest from up there.
 *
 * <p><b>Re-entry.</b> A lane entered again after a restock (the split restock planner cuts a lane at a cell count and
 * finishes it on the next load, from the same end or the other one; a load that ran out; a repair pass) has full blocks
 * on its walking line from the first pass: at the cut, or all along it. In AIRPLACE mode with airplace available
 * ({@link Params#walkWalled}, {@code build.walkWalledLanes}) the walk goes on over them when each has room for the box on
 * top and no line cell is next to a setback cell ({@link Io#standAllowed}; else the stand spots below, which keep away
 * from them): Baritone brings the bot exactly to the start cell (on top of it if it is a full block; a narrow start, a
 * pocket or a 1-wide slot, moves on to the next line cell it may rest in), walking into a full block of the line jumps
 * onto it and the walk keeps its keys until it lands, it jumps across a dip of the line (one floor cell between two of
 * its blocks) from the block before it when the jump lands on the middle of the block beyond
 * ({@link LaneWalk#hopLength}; too fast: it brakes first), never rests at the edge of such a block, and never rests in
 * a floor cell made narrow ({@link #AIR_MAX_WALLS} full-block sides) by a block of the line: it walks on to the nearest
 * line cell it may rest in. On top of a block of the line, the line cell ahead goes up as well (its box is clear of the
 * bot's); a walk that neither clicks nor gains a block of headway for {@link #NO_HEADWAY_TICKS} leaves the rest to the
 * stand spots. A cell beside the line still goes up only once the walk is clear of it, as on the floor, and
 * the reach margin covers a jump's vertical move. No packet is added: the jumps are the input's jump key. LOOK lanes
 * (cells hidden behind the line's blocks) and lanes without airplace keep the stand spots below. In every mode the walk
 * no longer climbs back onto the block behind it at a lane end (rule 4) when that block is next to a setback cell: the
 * rest goes to stand spots.
 *
 * <p><b>Line ends</b> ({@link Params#trimEnds}, {@code build.trimLaneEnds}). The planner re-plans a lane cut by the
 * restock planner from the world, its line one row past its cells at each end, in either direction: the line then
 * starts or ends in the first pass's last row, often a full block (live j0002 2026-10-12, airplace off: every walled
 * re-entry of three tiles, 2,232 cells, was walled by that one block and went to stand spots at 4 b/s). A line that
 * would be walled (LOOK lanes, airplace off, or a walk over it refused) is walked between such blocks
 * ({@link #trimEnds}): full blocks on the line at or before the lane's first row of cells, and at or after its last
 * row, end the walk. It starts after them (Baritone brings the bot to that floor cell), never walks back into them,
 * and ends in front of them, where the lane's end rules apply (walk back to place the cells ahead, climb onto the
 * block behind rather than close the last open side). Only full blocks left between those ends wall the line (walked
 * over, or stand spots, as above). With {@link Params#openSegment} ({@code build.laneOpenSegment}), a line still
 * walled walks the open segment serving the most remaining cells, at least 16 and a quarter of them; cells outside
 * its rows go to stand spots. Its start and end follow the same rules as the trim. A line the walk may step over is
 * walked over as before. With {@link Io#wallBrake} ({@code build.laneWallBrake}) the slide after the keys counts at
 * such an end: the keys go down only while the stop after them ({@link LaneWalk#pressedStop}, the acceleration from the
 * walk's top speed) leaves the feet at most {@link LaneWalk#WALL_STOP} past the end cell's centre, and the walk brakes
 * against its direction while the slide alone ({@link LaneWalk#slide}) would take them farther. Live 2026-10-20 (Abaddon [3,4], lane 14, Soul Speed, 0.49 a tick): the keys went off 0.05 before the
 * end, the bot slid 0.6 on into the cell of the pointed dripstone walling the line (a thin column: the box goes into
 * its cell), and no path could start from there ({@code Walker}'s step out, {@link StepOut}).
 *
 * <p><b>Way out</b> ({@link Params#wayOut}, {@code build.laneWayOut}). Pointed dripstone and decorated pots are taller
 * than a step but no full cube ({@link Io#standOnTop} false, {@link Entry#trap}): ZenithProxy's pathfinder neither
 * walks through nor onto them, so they can wall the bot in where full blocks only make it climb. Live 2026-10-12,
 * ellen [0,6] (a dripstone field): a lane's second pass placed dripstone around and behind the bot and ended with
 * dripstone on every side; restarts wanted the bot on top of a placed dripstone on the line. So: such a block goes up
 * only while the bot's area stays open ({@link #closesIn}: a connection to the permanent approach floor
 * after it, counted from its feet; on top of a dripstone, from the cells it can step down into), in the walk, from
 * stand spots and as an extra; one that would close it is left for later (from outside, a stand spot, or VERIFY). The
 * walk never steps over or starts on such a block of the line (the line ends there instead), never jumps up onto
 * one, and never climbs back onto one at a lane end.
 *
 * <p><b>Keep reachable</b> ({@link Params#keepReachable}, {@code build.laneKeepReachable}). Live 2026-10-13, ellen
 * [1,6]: stand-spot placements closed a 2-cell area of a dripstone field around a cell no spot had reached yet, and no
 * spot could afterwards (VERIFY BLOCKED). So such a block also waits while it would close the last way in to an
 * area disconnected from the bot holding a cell of the run still to place, or one skipped
 * as unreachable ({@link #sealsIn}); stand-spot picking takes no cell that waits (unless all do). Stand spots are never
 * on top of such a block ({@link #standY}), such blocks are no walls of a floor stand spot (nobody drops from them into
 * a slot), and such a block may go up from the cell right next to it ({@link #besideOk}; full blocks never).
 *
 * <p><b>Break-in</b> ({@link Params#breakIn}, {@code build.verifyBreakIn}, VERIFY's repair lanes, which get the art:
 * {@link Io#artAt}). A cell no stand spot reaches (picked and skipped as "not reachable") gets a corridor
 * ({@link BreakIn}): the fewest correct art blocks the pathfinder can neither walk through nor onto ({@link
 * Io#breakableWall}, at most {@link #BREAK_IN_WALLS}) to a stand cell from which it can be placed, taken only if each
 * block broken can go up again from the bot's side, the far end first. {@link Io#breakWalls} breaks them, the bot
 * walks to the stand cell (the corridor's cells count as stand spots whatever is beside them) and places the cell; the
 * broken blocks become cells of the run, each after the cell and every block beyond it ({@link Entry#after}), so the
 * corridor closes behind the bot; "Way out" keeps the bot from placing one beyond itself. Every step is logged.
 *
 * <p><b>Stand spots (SPOT).</b> Whatever the walk could not place (hidden, out of reach, stuck, a miss after the bot
 * moved on), and every cell of a lane whose walking line is already walled by full blocks (repair passes; walked over
 * instead in AIRPLACE mode, see Re-entry), is placed
 * from a stand spot: a cell around the target, on the floor or on top of a full block, from which the support's top
 * face is in reach and in line of sight. Baritone walks there (jumping onto full blocks as needed, never placing or
 * breaking), the bot centres itself and places every cell it can see from there, nearest first. On a flat platform
 * with blocks at most one high every cell has such a spot: a floor neighbour sees it at 1 block, and a cell walled in
 * on all four sides is seen from on top of a neighbour (eye 2.62 above the face).
 *
 * <p><b>Start tail</b> ({@link Io#startTail}, {@code build.laneStartTail}, off by default; AIRPLACE mode, a lane's first
 * walk, not a re-entry). A walking line clamped off the tile's edge rows starts up to three rows in; the airplace cells
 * behind its start that the eye at the start cannot reach would be "passed out of reach by the walk" and fetched after
 * it from wherever the lane ended. They go up first instead: stand spots (SPOT) for those cells only, the floor beside
 * the start still open, then the bot walks back to the start (APPROACH) and the walk runs as before. Local 10x runs of
 * 2026-11-14: 1-2 cells on 3 lanes of a colour-suppression bottom-layer pass, 40-115 ticks each against 340-440 for
 * the fetch after the walk; edge-sneak stand spots beside the lethal edge, no fall.
 *
 * <p><b>Raised cells</b> ({@link Io#raisedLevel}, {@code build.suppressionMergeLevels}, off by default;
 * docs/COLOR_SUPPRESSION.md). A colour-suppression layer's level-1 cells, one
 * block above the lane's height over air, go with the level-0 lanes, feet on the glass. From the floor a raised block
 * is a wall at head height: the box can neither pass under it (one block of room) nor climb it (two high), and nobody
 * stands on it. In the walk's plan view that is a full block's footprint, so the walk's rules hold as they are (a cell
 * of the walking column goes up once entirely behind the box, a cell beside it once clear of the box grown by 0.3, both
 * tested at the cell's own height); what differs is what the bot may do with it afterwards, and a raised cell is the
 * class of {@link Entry#trap} (pointed dripstone, pots) whatever {@code build.laneWayOut} and
 * {@code build.laneKeepReachable} say: every world read that asks whether a column is a wall, a floor or a stand cell
 * reads the column, the cell and the one above it ({@link #raisedAt}: walls, pockets, the walking line, the line ends,
 * the way out, stand spots, floor routes). So a raised block is never climbed at a lane end (rule 4 leaves the rest to
 * stand spots), never jumped at, never a stand spot or under one, and a line holding one is never walked over: the line
 * ends before it and starts after it (Line ends), the rest goes to floor stand spots. "Way out" and "keep reachable" hold
 * a raised block back that would close the bot's last connection to the approach floor or seal a cell still to place;
 * unlike pointed dripstone it never goes up from the cell right next to the bot, and it counts as a wall of a floor
 * stand spot (Baritone walks on its top and could drop into the slot beside it). On top of a block at a lane end the
 * box is at a raised cell's height: such a cell is placed only clear of the box, and never as the line cell ahead over
 * a walled line. The walk back at a lane end stops in front of a raised block of the line (what it would serve goes to
 * stand spots at once, not after the stuck wait); over a walled line the way out of a narrow cell never passes one, a
 * jump across a dip counts the line's blocks sent and not yet confirmed (the line cell ahead goes up as the walk comes
 * to it), and a jump wants the collision of a move the same way (on top of a block, the step back from its edge runs
 * into a raised block behind; jumping on that collision forward dropped the bot into the dip). The keys are relative to
 * the look, so the walk drifts 0.2-0.3 across while it aims, the box's edge into the column beside: a raised block
 * there that holds the box ({@link #raisedNear}) sends the walk back across to the line before it goes on, and on top
 * of a block no collision jump is made next to one (local 10x runs: it climbed raised blocks that way on top, and on
 * the floor pressed into them until the stuck wait). No raised cell goes up under the box in a jump
 * ({@link #safeBox}). The lane start's goals
 * take the feet within one block of the floor. No break-in for a raised cell, and
 * one is skipped (left to the level-1 pass) while airplace is not available: it floats, there is nothing to click.
 *
 * <p><b>Placement modes</b> ({@link Params#mode}). In AIRPLACE mode a cell
 * whose {@link TopFaceRules#placeMethod} is AIRPLACE (carpets, plates, full blocks, ...) is placed into the cell itself
 * with no rotation and no line of sight, eye → cell centre within {@link Params#airPlaceRange} (less the last step with
 * {@code reachStepMargin}); the rules above that keep cells visible (1)-(3) only protect LOOK cells then, while (4) and
 * the standing rules of {@link LaneWalk} hold for every cell. While {@link Io#airPlaceReady} is false (the offhand holds
 * a block, or the offhand guard gave up) every cell goes by LOOK. With {@link Params#silentRotation} the looks are sent
 * silently and the real rotation stays, so movement keys are relative to the real yaw.
 *
 * <p><b>Side faces</b> ({@link Params#sideFaces}, {@code build.lookSideFaces}; live 2026-10-12: a pointed dripstone cell
 * walled in by four full blocks was skipped on every repair pass, "not reachable: no stand spot in reach and line of
 * sight"). A LOOK cell whose state comes from the look alone ({@link TopFaceRules#sideFacePlaceable}: pointed
 * dripstone, lanterns, candles) whose support's top face cannot be aimed at is aimed at the side face of a full
 * neighbour that looks at the cell instead ({@link Io#aimSide}): the block goes into the clicked block + that face, the
 * cell, and with the look pitched down ({@link Aim#SIDE_MIN_PITCH}) its state is the top-face click's. The top face
 * is always tried first. Same pipeline as any LOOK click: the rotation goes out on one tick, the click on the next after
 * {@link Io#recheckSide}. Stand spots count such a face as well ({@link Io#sideVisibleFrom}): from the floor two cells
 * away a 1×1 hole's far wall shows its upper part, where the hole's own floor face is hidden from every spot (a
 * pointed dripstone is taller than a step, so in AIRPLACE mode no spot is next to it).
 *
 * <p><b>Stand spots in AIRPLACE mode</b> (mode AIRPLACE and {@link Io#airPlaceReady}). Live 2026-10-11: Baritone walked
 * the bot to stand spots across the top of placed full blocks and dropped it
 * into floor cells between them (a corner walled on two sides next to a 1-wide slot); 2b2t's anti-cheat answered with
 * bursts of setbacks until a safety stop. Airplace needs no line of sight, so in this mode:
 * <ul>
 * <li>stand spots are on the floor first, never in a floor cell with {@link #AIR_MAX_WALLS} or more full-block sides
 * (pockets, 1-wide slots), never next to a full block to place (Chebyshev 1: the bot never puts a wall right beside
 * itself, also not from where it stands);</li>
 * <li>the walk to a floor spot follows a floor route the run found itself ({@link FloorRoutes}), split into legs
 * Baritone cannot shorten by climbing, with {@link Io#walk(List, BooleanSupplier, boolean) noClimb}: a planned path that
 * steps up inside the stand area is refused;</li>
 * <li>a spot on top of a full block (or a floor spot Baritone may climb to) is the last resort, only after
 * {@link #FLOOR_FAILS} walks to floor spots failed for the target or no floor spot exists, and it is logged.</li>
 * </ul>
 * In every mode a stand cell (a spot, the cell the bot places from, a floor route's cell) is one
 * {@link Io#standAllowed} (no-go zones, setback cells); a stand spot picked {@link #SPOT_REPICKS} times without a
 * placement from it is not used again, and after {@link #MAX_FRUITLESS_SPOTS} stand spots in a row without a placement
 * the rest of the lane is skipped (VERIFY re-plans it): a bot that cannot move or whose clicks never land does not loop
 * until the task's timeout.
 *
 * <p><b>Edge stops</b> ({@code build.edgeSpotsLast}, {@code build.edgeSneak}). Live
 * 2026-10-13, soul sand: a last-resort spot on the platform's edge column, next to a one-block gap over a water channel;
 * the bot slid to the edge on arrival and fell in. A spot whose feet cell borders a drop of two blocks or more, water or
 * lava, or a no-go zone ({@link Io#edgeHazard}) costs {@link #EDGE_SPOT_COST} more and the ring search goes on past it:
 * it is taken only when no other spot of any ring places the cell. Such a spot (the walk to it stops sneaking, {@link
 * EdgeApproach}) is centred on with the sneak key held, and placing starts only once the bot stands still.
 *
 * <p><b>Park.</b> The engine checks hunger and health every tick; to eat mid-lane it asks the run to stop
 * ({@link #requestPark}). The run clicks no more, stops Baritone and holds still until nothing waits for the server and
 * the bot stands on the ground, then stays parked until {@link #resume}: same cells, same walk, a fresh aim.
 */
public final class LaneRun<S extends PlacePipeline.Rotation> {
    /** Collision taller than this is a wall at feet height (vanilla step height 0.6): a full block. */
    static final double TALL = 0.6;
    /** An outline taller than this hides the cells beyond it from the eye on the line (roots 13/16, flowers 10/16). */
    static final double SHADE = 0.5;
    /**
     * In AIRPLACE mode an outline taller than a carpet (1/16) hides a LOOK cell at the reach edge of a 9-wide lane
     * (simulated: pink petals two and three columns out left resin four out to stand spots).
     */
    static final double AIR_SHADE = 1.0 / 16 + 1e-6;
    static final double EDGE = LaneWalk.EDGE;
    static final int MAX_SENDS = 2;
    /**
     * Dropped clicks ({@link PlaceTracker.Outcome#DROPPED}: the server's click limiter never handled them) a cell may
     * have on top of its {@link #MAX_SENDS}; further drops count as sends. A drop says nothing about the cell, so it
     * does not use up a send; the cap keeps a click the server handles without answering it (out of its interaction
     * range, a hit point off the block, not the last of its server tick) from being retried forever.
     */
    static final int MAX_DROPS = 4;
    /**
     * Grim's block interaction range. Grim 2.3.73's source checks a placement from the click-time position; by
     * default ({@code build.reachStepMargin}) the last walking step is still kept off the range, as for a check from
     * the position after the next move, until a live run settles it.
     */
    static final double SERVER_RANGE = 4.5;
    /**
     * The walk over a walled line goes on while it clicks or gains a block of headway at least this often; else what
     * is left goes to stand spots.
     */
    static final int NO_HEADWAY_TICKS = 300;
    /** Line cells each way searched for one to rest in when the walk over a walled line stands in a narrow cell. */
    static final int ESCAPE_CELLS = 3;
    /** Ticks without progress before the walk gives up on what is left (to stand spots). */
    static final int IDLE_TICKS = 100;
    /**
     * Ticks the walk may be held for a waiting block beyond a miss and its retry ({@link #MAX_SENDS} ×
     * {@code placeConfirmTicks}) before it lets the block go (to stand spots).
     */
    static final int HOLD_EXTRA_TICKS = 30;
    /** Stand spots tried per cell. */
    static final int MAX_SPOTS = 4;
    /** Ticks to centre on a stand spot after Baritone arrived. */
    static final int CENTER_TICKS = 20;
    /** Ticks at a stand spot with nothing to aim at before the next spot. */
    static final int SPOT_IDLE_TICKS = 8;
    /** Ticks at a stand spot without a click before the next spot. */
    static final int SPOT_MAX_TICKS = 200;
    /** Other cells counted when a stand spot is chosen by how many it sees. */
    static final int SPOT_SEEN_MAX = 8;
    /** Rings of stand spots around a target: its 8 neighbours, then the ring 2 out (the first ring with a spot wins). */
    static final int SPOT_RINGS = 2;
    /** AIRPLACE mode: a floor spot with this many full-block sides (or more) is a pocket or a slot, never a stand spot. */
    static final int AIR_MAX_WALLS = 2;
    /** Break-in: blocks broken for one cell at most (each is placed again; a longer corridor is not opened). */
    static final int BREAK_IN_WALLS = 4;
    /** Break-in: corridors opened per run at most. */
    static final int MAX_BREAK_INS = 3;
    /** Break-in: cells around the bot and the target the plan may use. */
    static final int BREAK_IN_MARGIN = 32;
    /** AIRPLACE mode: failed walks to floor spots of a target before a spot on top of a full block may be used. */
    static final int FLOOR_FAILS = 2;
    /** Edge stops: added to the cost of a stand spot beside a drop, water or a no-go zone (ranks after every other). */
    static final double EDGE_SPOT_COST = 1000;
    /** Edge stops: ticks a stand spot beside such a hazard waits at most for the bot to stand still after centring. */
    static final int EDGE_STILL_TICKS = 20;
    /** A stand spot picked this many times without a placement from it is not used again (for any target). */
    static final int SPOT_REPICKS = 3;
    /** Stand spots in a row without a placement after which the rest of the lane is skipped (VERIFY re-plans it). */
    static final int MAX_FRUITLESS_SPOTS = 12;
    /** Skipped cells per lane run logged one by one with their reason; the rest only in the lane's last line. */
    static final int SKIP_LOG_MAX = 32;
    /** Floor routes ({@link FloorRoutes}) are searched this many cells around the bot and the target. */
    static final int ROUTE_MARGIN = 12;
    /** Step back: blocks beyond the reach edge at most (a short correction, not a second lane walk). */
    static final double STEP_BACK_MAX = 3.0;
    /**
     * Step back: corrections per lane at most; each cell gets only one correction. Live 2026-10-13 (soul sand, sub-tick
     * dispatch): 8 per lane were used up in the first half of most lanes, each 0.4-0.8 blocks for 1-3 cells (one per
     * dropped or refused click), and the rest of the lane's misses went to stand spots again.
     */
    static final int STEP_BACK_LIMIT = 32;
    /**
     * Paced walk: blocks kept between a queued cell and the edge of reach at the tick it is due to be clicked. With step
     * back on, {@link #PACED_MARGIN_STEP_BACK}: a dropped click is known from a later click's ack 2-4 ticks after it
     * went out (0.3-0.6 blocks of walk at the window's rate), so a cell clicked 0.35 inside reach had left it by then.
     */
    static final double PACED_MARGIN = 0.35;
    static final double PACED_MARGIN_STEP_BACK = 0.6;
    /** Paced walk: a cell waiting for its stack (used up, or a move) stops the walk this close to leaving reach. */
    static final double PACED_STOP_STACK = 1.2;
    /** Paced walk: a cell in flight or waiting to retry stops the walk this close to leaving reach. */
    static final double PACED_STOP_WAITING = 1.5;
    /**
     * Paced walk: a speed cap below this (blocks per tick, walking is 0.216) stops the walk; above it the bot walks at
     * full speed. No partial speeds: holding one with the keys alone means toggling the forward key, and every key-down
     * after a key-up is a walk start (LaneSim, 3 lanes of 4 colours: 183 such ticks, 165 starts against 91 this way;
     * the routing study fitted about 0.85 lost ticks per walk start on 2b2t). LaneSim sweep: 0.05-0.11 all keep every
     * cell; 0.08 in the middle.
     */
    static final double PACED_STOP_SPEED = 0.08;
    /** Paced walk: only with the eye within this of the lane line (the stop-and-go walk steers back first). */
    static final double PACED_MAX_OFF_LINE = 0.35;
    /** {@link Io#forwardClearPath}: the most the box is swept forward over a click's answer time ({@link #forwardReach}). */
    static final double FWD_SWEEP_MAX = 1.5;
    /** {@link Io#forwardClearPath}: ticks of walking counted beyond the click latency (the click's tick, the move after). */
    static final int FWD_EXTRA_TICKS = 2;
    /** {@link Io#forwardClearPath}: the swept box is the player's grown by this (steering drift over the answer time). */
    static final double FWD_INFLATE = 0.1;
    /** {@link Io#forwardClearPath}: the predicted box after this tick's move is grown by this. */
    static final double FWD_GUARD_MARGIN = 0.02;
    /** Off the line by more than this the walk steers back even while standing (the movement keys' deadband). */
    static final double STEER_DEADBAND = 0.2;
    /** Paced walk: the walking step kept off the ranges for deadlines (as {@code reachStepMargin} does while walking). */
    static final double PACED_STEP = 0.22;

    /**
     * Run parameters (from {@code build.*} config) and the cells a stand spot may be on (null: no stand spots).
     * {@code mode}: AIRPLACE or LOOK ({@code build.placeMode}); {@code airPlaceRange}: eye → cell centre for airplace;
     * {@code silentRotation}: looks are sent silently ({@code rotatePlayer} false, {@code grimRotate} true);
     * {@code walkWalled}: a partly built AIRPLACE lane is walked ({@code build.walkWalledLanes}, see "Re-entry" above).
     * {@code pacing}: the pacer policy, the stack settle ticks and the stack switch ({@link PlacePacing}).
     * {@code greedy}: the in-reach greedy ({@code build.placeInReachGreedy}, class javadoc "In-reach greedy").
     * {@code sideFaces}: LOOK cells may be clicked on a neighbour's side face ({@code build.lookSideFaces}, class
     * javadoc "Side faces").
     * {@code trimEnds}: full blocks at the ends of the walking line, outside the lane's rows, end the walk instead of
     * walling the lane ({@code build.trimLaneEnds}, class javadoc "Line ends").
     * {@code openSegment}: a line still walled walks its most useful open segment ({@code build.laneOpenSegment},
     * class javadoc "Line ends").
     * {@code wayOut}: no block the pathfinder can neither walk through nor onto goes up where it would wall the bot in
     * ({@code build.laneWayOut}, class javadoc "Way out").
     * {@code keepReachable}: no such block goes up where it would shut a cell still to place into a closed area,
     * stand spots are never on top of one, and they do not count as the walls of a floor stand spot
     * ({@code build.laneKeepReachable}, class javadoc "Keep reachable").
     * {@code stepBack}: bounded returns for cells just passed out of reach ({@code build.laneStepBack}).
     * {@code breakIn}: a cell no stand spot of which the pathfinder can reach opens a corridor through such blocks
     * (VERIFY's repair lanes only, {@code build.verifyBreakIn}, class javadoc "Break-in").
     */
    public record Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                         int intervalMin, int intervalMax, @Nullable Area standArea, PlaceMode mode,
                         double airPlaceRange, boolean silentRotation, boolean walkWalled, PlacePacing pacing,
                         boolean greedy, boolean sideFaces, boolean trimEnds, boolean openSegment, boolean wayOut,
                         boolean keepReachable, boolean breakIn, boolean sealOut, boolean stepBack) {
        /** Step back on (the default of {@code build.laneStepBack}). */
        public Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                      int intervalMin, int intervalMax, @Nullable Area standArea, PlaceMode mode,
                      double airPlaceRange, boolean silentRotation, boolean walkWalled, PlacePacing pacing,
                      boolean greedy, boolean sideFaces, boolean trimEnds, boolean openSegment, boolean wayOut,
                      boolean keepReachable, boolean breakIn, boolean sealOut) {
            this(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax, standArea, mode,
                airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment, wayOut,
                keepReachable, breakIn, sealOut, true);
        }

        /** The same without VERIFY seal-out (ordinary lanes). */
        public Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                      int intervalMin, int intervalMax, @Nullable Area standArea, PlaceMode mode,
                      double airPlaceRange, boolean silentRotation, boolean walkWalled, PlacePacing pacing,
                      boolean greedy, boolean sideFaces, boolean trimEnds, boolean openSegment, boolean wayOut,
                      boolean keepReachable, boolean breakIn) {
            this(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax, standArea, mode,
                airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment, wayOut,
                keepReachable, breakIn, false);
        }

        /** {@code openSegment} on (the default of {@code build.laneOpenSegment}). */
        public Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                      int intervalMin, int intervalMax, @Nullable Area standArea, PlaceMode mode,
                      double airPlaceRange, boolean silentRotation, boolean walkWalled, PlacePacing pacing,
                      boolean greedy, boolean sideFaces, boolean trimEnds, boolean wayOut, boolean keepReachable,
                      boolean breakIn) {
            this(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax, standArea, mode,
                airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, true, wayOut,
                keepReachable, breakIn);
        }

        /** {@code keepReachable} on and {@code breakIn} off (the BUILD default). */
        public Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                      int intervalMin, int intervalMax, @Nullable Area standArea, PlaceMode mode,
                      double airPlaceRange, boolean silentRotation, boolean walkWalled, PlacePacing pacing,
                      boolean greedy, boolean sideFaces, boolean trimEnds, boolean wayOut) {
            this(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax, standArea, mode,
                airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, wayOut, true, false);
        }

        /** {@code wayOut} on (the default of {@code build.laneWayOut}). */
        public Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                      int intervalMin, int intervalMax, @Nullable Area standArea, PlaceMode mode,
                      double airPlaceRange, boolean silentRotation, boolean walkWalled, PlacePacing pacing,
                      boolean greedy, boolean sideFaces, boolean trimEnds) {
            this(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax, standArea, mode,
                airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, true);
        }

        /** {@code trimEnds} on (the default of {@code build.trimLaneEnds}). */
        public Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                      int intervalMin, int intervalMax, @Nullable Area standArea, PlaceMode mode,
                      double airPlaceRange, boolean silentRotation, boolean walkWalled, PlacePacing pacing,
                      boolean greedy, boolean sideFaces) {
            this(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax, standArea, mode,
                airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, true);
        }

        /** {@code sideFaces} on (the default of {@code build.lookSideFaces}). */
        public Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                      int intervalMin, int intervalMax, @Nullable Area standArea, PlaceMode mode,
                      double airPlaceRange, boolean silentRotation, boolean walkWalled, PlacePacing pacing,
                      boolean greedy) {
            this(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax, standArea, mode,
                airPlaceRange, silentRotation, walkWalled, pacing, greedy, true);
        }

        /** {@code greedy} off ({@code build.placeInReachGreedy}, class javadoc "In-reach greedy"). */
        public Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                      int intervalMin, int intervalMax, @Nullable Area standArea, PlaceMode mode,
                      double airPlaceRange, boolean silentRotation, boolean walkWalled, PlacePacing pacing) {
            this(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax, standArea, mode,
                airPlaceRange, silentRotation, walkWalled, pacing, false);
        }

        /** {@code pacing} the defaults ({@link PlacePacing#DEFAULT}). */
        public Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                      int intervalMin, int intervalMax, @Nullable Area standArea, PlaceMode mode,
                      double airPlaceRange, boolean silentRotation, boolean walkWalled) {
            this(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax, standArea, mode,
                airPlaceRange, silentRotation, walkWalled, PlacePacing.DEFAULT);
        }

        /** The same with bounded steps back on or off ({@code build.laneStepBack}). */
        public Params withStepBack(boolean stepBack) {
            return new Params(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax,
                standArea, mode, airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment,
                wayOut, keepReachable, breakIn, sealOut, stepBack);
        }

        /** The same with {@code pacing}. */
        public Params withPacing(PlacePacing pacing) {
            return new Params(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax,
                standArea, mode, airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment,
                wayOut, keepReachable, breakIn, sealOut, stepBack);
        }

        /** The same with the in-reach greedy on or off ({@code build.placeInReachGreedy}). */
        public Params withGreedy(boolean greedy) {
            return new Params(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax,
                standArea, mode, airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment,
                wayOut, keepReachable, breakIn, sealOut, stepBack);
        }

        /** The same with side-face LOOK clicks on or off ({@code build.lookSideFaces}). */
        public Params withSideFaces(boolean sideFaces) {
            return new Params(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax,
                standArea, mode, airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment,
                wayOut, keepReachable, breakIn, sealOut, stepBack);
        }

        /** The same with the line-end trim on or off ({@code build.trimLaneEnds}). */
        public Params withTrimEnds(boolean trimEnds) {
            return new Params(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax,
                standArea, mode, airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment,
                wayOut, keepReachable, breakIn, sealOut, stepBack);
        }

        /** The same with the open segment on or off ({@code build.laneOpenSegment}). */
        public Params withOpenSegment(boolean openSegment) {
            return new Params(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax,
                standArea, mode, airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment,
                wayOut, keepReachable, breakIn, sealOut, stepBack);
        }

        /** LOOK mode with the real rotation: the placement before placement modes. */
        public Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                      int intervalMin, int intervalMax, @Nullable Area standArea) {
            this(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax, standArea,
                PlaceMode.LOOK, 5.0, false);
        }

        /** {@code walkWalled} on (the default of {@code build.walkWalledLanes}). */
        public Params(double reach, boolean reachStepMargin, int placeConfirmTicks, boolean pipelined,
                      int intervalMin, int intervalMax, @Nullable Area standArea, PlaceMode mode,
                      double airPlaceRange, boolean silentRotation) {
            this(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax, standArea, mode,
                airPlaceRange, silentRotation, true);
        }

        /** From the build config. */
        public static Params of(MapArtConfig.BuildCfg b, @Nullable Area standArea) {
            return new Params(b.reach, b.reachStepMargin, b.placeConfirmTicks, b.pipelinePlacement,
                b.placeIntervalMinTicks, b.placeIntervalMaxTicks, standArea, PlaceMode.of(b), b.airPlaceRange,
                PlaceMode.silentRotation(b), b.walkWalledLanes, PlacePacing.of(b), b.placeInReachGreedy,
                b.lookSideFaces, b.trimLaneEnds, b.laneOpenSegment, b.laneWayOut, b.laneKeepReachable, false, false, b.laneStepBack);
        }

        /** The same with keep-reachable on or off ({@code build.laneKeepReachable}). */
        public Params withKeepReachable(boolean keepReachable) {
            return new Params(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax,
                standArea, mode, airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment,
                wayOut, keepReachable, breakIn, sealOut, stepBack);
        }

        /** The same with the break-in on or off (VERIFY's repair lanes, {@code build.verifyBreakIn}). */
        public Params withBreakIn(boolean breakIn) {
            return new Params(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax,
                standArea, mode, airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment,
                wayOut, keepReachable, breakIn, sealOut, stepBack);
        }

        /** VERIFY seal-out: only the repair task enables it, independently of break-in. */
        public Params withSealOut(boolean sealOut) {
            return new Params(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax,
                standArea, mode, airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment,
                wayOut, keepReachable, breakIn, sealOut, stepBack);
        }

        /** The same with the way-out rule on or off ({@code build.laneWayOut}). */
        public Params withWayOut(boolean wayOut) {
            return new Params(reach, reachStepMargin, placeConfirmTicks, pipelined, intervalMin, intervalMax,
                standArea, mode, airPlaceRange, silentRotation, walkWalled, pacing, greedy, sideFaces, trimEnds, openSegment,
                wayOut, keepReachable, breakIn, sealOut, stepBack);
        }
    }

    /** Inclusive rectangle of cells. */
    public record Area(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /** The game for one lane. Positions are feet positions; the eye is {@link #eyeY()}. Client tick thread. */
    public interface Io<S> {
        double x();

        double y();

        double z();

        double eyeY();

        /** Yaw in effect now (the rotation of the last movement packet). */
        float yaw();

        boolean onGround();

        /** The last movement was stopped by a block. */
        boolean horizontalCollision();

        boolean isLoaded(Pos pos);

        BlockSpec block(Pos pos);

        /** Not air and not replaceable: a placement there fails, and it supports a block on top. */
        boolean blocksPlacement(Pos pos);

        boolean hasFluid(Pos pos);

        /** Highest collision box top of the state: 0 none, 1 full height. */
        double collisionTop(BlockSpec spec);

        /**
         * The bot, and Baritone, can stand on top of this state: a full cube. Pointed dripstone (a thin column) and
         * decorated pots are as high but neither: ZenithProxy's pathfinder neither walks through nor onto them, so they
         * can wall the bot in (class javadoc "Way out"). Default: a collision box a block high.
         */
        default boolean standOnTop(BlockSpec spec) {
            return spec != null && !spec.isAir() && collisionTop(spec) >= 1.0;
        }

        /** Highest outline (interaction / raycast) box top of the state: what hides the cells behind it from the eye. */
        double outlineTop(BlockSpec spec);

        /** World state counts as the planned target ({@code BlockCatalog.matches}). */
        boolean matches(BlockSpec target, BlockSpec actual);

        /** The unit block {@code support} is within {@code reach} of the eye (nearest point). */
        boolean inReach(Pos support, double reach);

        /** A fresh shot at the top face of {@code support} from the eye now, in reach and line of sight, or null. */
        @Nullable S aim(Pos support, double reach);

        /** The rotation in effect still hits the top face of {@code support} from the eye now, within reach. */
        @Nullable S recheck(Pos support, double reach);

        /** The top face of {@code support} could be hit in reach from an eye at (ex, ey, ez) (a stand spot). */
        boolean visibleFrom(double ex, double ey, double ez, Pos support, double reach);

        /**
         * A fresh shot from the eye now at the side face of a horizontal neighbour of {@code cell} that looks at the
         * cell (a full block that places rather than acts on a click, {@link TopFaceRules#sideClickable}), pitched down
         * at least {@link Aim#SIDE_MIN_PITCH}, in reach and line of sight, or null (class javadoc "Side faces").
         * Default: none.
         */
        default @Nullable S aimSide(Pos cell, double reach) {
            return null;
        }

        /** The rotation in effect still hits such a side face for {@code cell} ({@link #aimSide}). Default: no. */
        default @Nullable S recheckSide(Pos cell, double reach) {
            return null;
        }

        /** Such a side face for {@code cell} could be hit in reach from an eye at (ex, ey, ez). Default: no. */
        default boolean sideVisibleFrom(double ex, double ey, double ez, Pos cell, double reach) {
            return false;
        }

        /**
         * The bot may stand with its feet in this cell (a stand spot; a cell of a floor route): false in a no-go zone
         * and next to a cell where the server set the bot back (the engine's setback cells). Default: anywhere.
         */
        default boolean standAllowed(Pos feet) {
            return true;
        }

        /**
         * Edge stops (class javadoc "Edge stops"): why a bot with its feet in {@code feet} could slide into danger (a
         * neighbour cell with a drop of two blocks or more, water or lava, or a no-go zone: {@link EdgeDrop}), or null.
         * Default: never.
         */
        default @Nullable String edgeHazard(Pos feet) {
            return null;
        }

        /** {@code build.edgeSpotsLast}: a stand spot with an {@link #edgeHazard} ranks after every other. Default: no. */
        default boolean edgeSpotsLast() {
            return false;
        }

        /** {@code build.edgeSneak}: a stand spot with an {@link #edgeHazard} is centred on sneaking. Default: no. */
        default boolean edgeSneak() {
            return false;
        }

        /**
         * Break-in (class javadoc): the tile's placement for {@code cell} (its art target and item), or null (no target
         * there, or no art known: a BUILD lane). Default: none.
         */
        default @Nullable Placement artAt(Pos cell) {
            return null;
        }

        /** The general walk recovery kill switch. */
        default boolean dripstoneBreakThrough() { return false; }

        /**
         * {@code build.laneWallBrake}: the walk lets go of its keys early enough, and brakes, that its slide never takes
         * the box past the end cell into a wall right after it (class javadoc "Line ends"). Default: off.
         */
        default boolean wallBrake() { return false; }

        /**
         * {@code build.laneStepBackClearPath}: a step back neither clicks into nor walks into a cell its box may still go
         * back over before the proxy shows that cell's block (class javadoc "Step back"). Default: off.
         */
        default boolean stepBackClearPath() { return false; }

        /**
         * {@code build.laneForwardClearPath}: the forward walk neither clicks a side cell its box may reach before the
         * cell's answer, nor walks into a side cell whose block the proxy does not show yet (class javadoc "Step
         * back", forward clear path).
         */
        default boolean forwardClearPath() { return false; }

        /**
         * {@code build.laneStartOnFloor}: the goals of the walk to the lane start take the feet at most one block above
         * the art layer (the arrival test's 1.5), not two (class javadoc "Lane start"). Default: off.
         */
        default boolean startOnFloor() { return false; }

        /**
         * {@code build.laneStartTail}: the airplace cells behind the walk's start that the start does not reach (a
         * line clamped off the tile's edge rows leaves the edge row's outer columns 3 rows back and 4 across) go up
         * from stand spots before the walk, the bot beside them and the floor still open, not after it, from the
         * lane's other end (class javadoc "Start tail"). Default: off.
         */
        default boolean startTail() { return false; }

        /**
         * {@code build.suppressionMergeLevels}: the lane may hold raised cells, one block above its height, and the world
         * there may hold raised blocks (class javadoc "Raised cells"). Default: off (one level, as before).
         */
        default boolean raisedLevel() { return false; }

        /** Previously broken walk corridors waiting for a safe placement run, far end first. */
        default List<Pos> walkRepairs() { return List.of(); }

        /**
         * Break-in: {@code cell} holds a block the bot may break to open a corridor: one the pathfinder can neither walk
         * through nor onto, among the platform's clearable blocks, not protected, in no protected region or no-go zone,
         * on a cell of the tile's art ({@link #artAt}), so it can be placed again. Default: none.
         */
        default boolean breakableWall(Pos cell) {
            return false;
        }

        /**
         * Break-in: breaks {@code walls} in order, walking along {@code path} (the bot's feet cell first) to bring the
         * next one into reach (the usual tool rules); RUNNING until all are gone (SUCCESS) or it gives up (FAILED,
         * {@link #breakError}). Default: FAILED.
         */
        default TaskStatus breakWalls(List<Pos> walls, List<Pos> path) {
            return TaskStatus.FAILED;
        }

        default void cancelBreakWalls() { }

        /** Why the last {@link #breakWalls} failed. */
        default String breakError() {
            return "breaking is not supported here";
        }

        /** Items of {@code item} (namespaced) in the inventory. */
        int count(String item);

        @Nullable String heldItem();

        int heldSlot();

        /** Hotbar index 0-8 holding {@code item}, or -1. */
        int hotbarSlot(String item);

        /** An inventory request (ours or another module's) is queued or executing. */
        boolean inventoryBusy();

        /**
         * What a park waits out ({@link #requestPark}): as {@link #inventoryBusy}, except the airplace guard's hold while it
         * wants the server's full inventory (the park is how that check gets its still tick; drill OD2: the lane waited
         * 20 s for a hold only the check could end).
         */
        default boolean parkBusy() { return inventoryBusy(); }

        /** Request execution blocks all clicks; optionally allow safe slots during only a fence wait. */
        default boolean clickInventoryBusy(boolean fenceClicks) {
            return inventoryBusy();
        }

        /** Hotbar slot whose item/count may be stale until the move is reported, or -1. */
        default int fencedSlot() {
            return -1;
        }

        /** Prefetch through the usual move rules, only replacing an unused slot with no clicks in flight. */
        default boolean prefetchHeld(String item, java.util.function.ToIntFunction<String> nextUse,
                                     java.util.function.IntPredicate replaceable) {
            return false;
        }

        /** Opt-in standing lookahead, including early refill; old adapters retain retired-slot prefetch. */
        default boolean lookaheadPrefetch() { return false; }
        /** The click window also bounds every rolling window ({@code build.placeRollingClickWindow}). */
        default boolean rollingClickWindow() { return lookaheadPrefetch(); }
        /** An abandoned speculative slot needs a server report before placement can use it. */
        default boolean slotKnown(int slot) { return true; }
        /**
         * Items of {@code item} in hotbar slots that are not {@link #slotKnown}: {@link #count} leaves them out, but they
         * are not missing either, only unconfirmed until the server's full inventory answer
         * ({@code build.unknownSlotResync}). Default: none.
         */
        default int unknownCount(String item) { return 0; }
        /** A hotbar move could bring {@code item} in: a stack of it in the main inventory. Default: always. */
        default boolean moveSource(String item) { return true; }
        /** Where {@code item} is (hotbar slots with counts, unknown or fenced; the main inventory), for log lines. */
        default String itemState(String item) { return ""; }
        /** The input and inventory state that can hold a lane (for the watchdog's line). Default: none. */
        default String holdState() { return ""; }
        /**
         * The lane watchdog ({@code build.laneWatchdogTicks}): ticks of the run without a placement, a click, a hotbar
         * change or movement after which it ends, its cells left for the next round. 0 = off (the default here).
         */
        default int watchdogTicks() { return 0; }
        /**
         * {@code build.laneWatchdogHotbarTicks}: hotbar changes alone (no placement, click, phase change or movement)
         * keep the lane watchdog from tripping for at most this many ticks. 0 = no bound (the default here).
         */
        default int watchdogHotbarTicks() { return 0; }
        /** Hunger/offhand/health parking must release speculative inventory holds before the new task exists. */
        default void inventoryNeeded() { }
        default void prefetchAhead(List<String> cells, java.util.function.IntUnaryOperator available,
                                   java.util.function.IntPredicate replaceable) { }

        /** Prepare the remaining lane's materials while still; false means a move or its fence is pending. */
        default boolean layoutHotbar(List<String> cells) { return true; }

        /** Lazy tick batch: no swap until the first airplace, always closed in the tick's finally block. */
        default void beginAirPlaces(boolean batch) { }
        default void endAirPlaces() { }

        default long nanoTime() { return System.nanoTime(); }
        default long clickNanoTime() { return nanoTime(); }
        default int airPlacesSent() { return 0; }
        default boolean subTickAvailable() { return false; }
        default boolean subTickReady() { return false; }
        default void beginSubTick() { }
        default void endSubTick() { }
        /** Schedule on the same loop as ticks; the returned action cancels the callback. */
        default Runnable scheduleClick(long at, Runnable send) { throw new UnsupportedOperationException(); }

        /** The summary line's part on hotbar moves waiting for the server ({@link MoveFence#summary}). Default: none. */
        default String moveFence() {
            return "";
        }

        /** Moves {@code item} into the hand (an inventory click, only after a still tick). */
        void ensureHeld(String item);

        /**
         * {@link #ensureHeld(String)}; a move from the main inventory replaces the lane's hotbar stack whose item
         * {@code nextUse} ranks last (material id → order of its next cell, larger = later, {@link Integer#MAX_VALUE}
         * = not needed again: Belady's rule, {@link PlacePacing#belady}). Default: the plain move.
         */
        default void ensureHeld(String item, java.util.function.ToIntFunction<String> nextUse) {
            ensureHeld(item);
        }

        /** This tick's rotation (null = keep) and movement keys: forward 1 = W / -1 = S, strafe 1 = A / -1 = D. */
        void submit(@Nullable Float yaw, @Nullable Float pitch, int forward, int strafe, boolean jump);

        /**
         * {@link #submit(Float, Float, int, int, boolean)} with the sneak key held as well when {@code sneak} (vanilla's
         * sneak edge stop: the box stays over the floor). Default: without it.
         */
        default void submit(@Nullable Float yaw, @Nullable Float pitch, int forward, int strafe, boolean jump,
                            boolean sneak) {
            submit(yaw, pitch, forward, strafe, jump);
        }

        /** This tick's input: no keys, rotation unchanged. */
        void holdStill();

        /** Ticks in a row our input request was rejected (another module owns the input). */
        int lostTicks();

        /** The previous tick's input request was executed and no container is open. */
        boolean ownedLastTick();

        /** After this tick's submit: accepted, and the previous one executed. */
        boolean canClick();

        /** Selects hotbar slot {@code slot} now (before a click). True if selected afterwards. */
        boolean select(int slot);

        /** Sends the use-item-on for the shot. True if sent. */
        boolean click(S shot);

        /** Block prediction sequence of the use-item-on just sent by {@link #click} or {@link #airPlace}, -1 if unknown. */
        default int clickSequence() {
            return -1;
        }

        /** Highest block-changed-ack sequence the server sent on this connection, -1 if none. */
        default int ackedSequence() {
            return -1;
        }

        /** An ack carrying exactly {@code sequence} arrived on this connection (not only a higher one). */
        default boolean ackedExactly(int sequence) {
            return false;
        }

        /**
         * A mark in the stream of block updates from the server, taken just before a click: a block update for the
         * click's cell after it answers the click ({@link #echoedSince}). -1: block updates are not watched; an acked
         * click without its block then always counts as rejected.
         */
        default long echoMark() {
            return -1;
        }

        /** A block update for {@code pos} arrived after {@code mark} ({@link #echoMark}). */
        default boolean echoedSince(Pos pos, long mark) {
            return false;
        }

        /** Items in hotbar slot {@code slot} (0-8) as the cache shows them, 0 if empty. */
        default int hotbarCount(int slot) {
            return Integer.MAX_VALUE;
        }

        /**
         * Every hotbar index (0-8) holding {@code item}, lowest first, as the cache shows them (the stack switch,
         * {@link PlacePacing#stackSwitch}). Default: {@link #hotbarSlot}'s one slot.
         */
        default int[] hotbarSlots(String item) {
            int s = hotbarSlot(item);
            return s < 0 ? new int[0] : new int[]{s};
        }

        /**
         * AIRPLACE is the method for AIRPLACE cells ({@link AirPlaceGuard#airPlaceAvailable}: the guard has not given up
         * and the offhand's own item places no block). While false every cell goes by LOOK. It stays true while the
         * guard settles an airplace echo; the clicks wait then ({@link #inventoryBusy}).
         */
        boolean airPlaceReady();

        /** Airplaces the selected hotbar item into {@code cell} ({@link AirPlace}). True if sent. */
        boolean airPlace(Pos cell);

        /** Walks with Baritone towards the goals (tighter ones after a looser one is reached) until {@code arrived}. */
        TaskStatus walk(List<GoalNearXZ> goals, BooleanSupplier arrived);

        /**
         * A walk to a stand spot ({@link #walk(List, BooleanSupplier)}; the goals are legs walked in order). With
         * {@code noClimb} a planned path that steps up inside the stand area is refused and the walk FAILS
         * ({@link #pathRefusal}); setback cells are never walked through. Default: the plain walk.
         */
        default TaskStatus walk(List<GoalNearXZ> goals, BooleanSupplier arrived, boolean noClimb) {
            return walk(goals, arrived);
        }

        /**
         * {@link #walk(List, BooleanSupplier, boolean)} that gives up after {@code retries} failed path requests
         * (Baritone's own walk retries a few times otherwise). Default: the stand-spot walk.
         */
        default TaskStatus walk(List<GoalNearXZ> goals, BooleanSupplier arrived, boolean noClimb, int retries) {
            return walk(goals, arrived, noClimb);
        }

        String walkError();

        void stopWalking();

        /**
         * Baritone walks for this run right now (its input, not ours, this tick), no container is open and the bot has
         * not sprinted for {@code build.sprintQuietTicks} ticks (a lane-start walk may sprint): an airplace may go out
         * this tick (it needs no rotation; the in-reach greedy, class
         * javadoc). Default: never.
         */
        default boolean clickWhileWalking() {
            return false;
        }

        /**
         * Feet cells Baritone will still walk for this run's walk, from where it is now to the goal (the current path
         * segment from its position, then the next); null while it has no path yet or its planned segments do not reach
         * the goal. Default: unknown.
         */
        default @Nullable List<Pos> walkPath() {
            return null;
        }

        AdaptivePacer pacer();

        void placed(Placement p);

        /**
         * A cell of a later lane of the load placed by this run (the in-reach greedy, class javadoc). Default:
         * {@link #placed}.
         */
        default void placedExtra(Placement p) {
            placed(p);
        }

        void skipped(Placement p, String reason);

        void log(String line);
    }

    enum Phase { APPROACH, RUN, SETTLE, SPOT }

    enum State { TODO, PENDING, DONE, SKIPPED }

    private enum SpotStep { PICK, WALK, CENTER, PLACE, BREAK }

    /**
     * A stand spot; {@code floor}: found by the floor-first search of AIRPLACE mode, walked along its floor route with
     * no climbing; {@code legs}: the walk's goals in order (empty: straight to the spot).
     */
    private record Spot(int x, double y, int z, double cost, boolean floor, List<GoalNearXZ> legs) {
        int feetY() {
            return (int) Math.floor(y + 1e-6);
        }

        Pos feet() {
            return new Pos(x, feetY(), z);
        }

        /** The walk's goals: the legs, or the spot itself. */
        @Override
        public List<GoalNearXZ> legs() {
            return legs.isEmpty() ? List.of(new GoalNearXZ(x, feetY(), z, 0, 0)) : legs;
        }

        Spot routed(List<GoalNearXZ> route) {
            return new Spot(x, y, z, cost, floor, route);
        }
    }

    static final class Entry {
        final Placement p;
        final Pos support;
        final double s;
        final boolean corridor;
        final boolean solid;
        final boolean shades;
        final int row;
        final LaneWalk.Cell cell;
        /** How this cell is placed in this lane's mode ({@link TopFaceRules#placeMethod}). */
        final PlaceMethod method;
        State st = State.TODO;
        long sentAt;
        /** Clicks that count against {@link #MAX_SENDS} (drops do not, up to {@link #MAX_DROPS}). */
        int sends;
        /** Dropped clicks not counted as sends. */
        int drops;
        /** Sequence of the last click (-1 unknown) and the block update mark taken before it (-1 unwatched). */
        int seq = -1;
        long echoMark = -1;
        /** A rejected cell is not clicked again before this tick ({@link PlaceTracker#retryDelay}). */
        long retryAt;
        int losMisses;
        double losMissS;
        /** Aims that failed with the face centre in reach (hidden from the line), since the last good aim. */
        int hiddenMisses;
        /** Left by the walk: placed from a stand spot. */
        boolean left;
        /** A step back already served this cell; a failed correction waits for stand spots. */
        boolean stepBackTried;
        boolean stepBackSent;
        /** The tick its block showed (DONE); -1 before. */
        long doneAt = -1;
        /** No longer holds the walk (it waited too long). */
        boolean released;
        int spotTries;
        int spotMisses;
        /** AIRPLACE mode: walks to floor stand spots for this cell that failed (refused or not arrived). */
        int floorFails;
        final Set<Long> failedSpots = new HashSet<>();
        /** In-reach greedy: a cell of a later lane of the load (s and lateral in this lane's coordinates). */
        boolean extra;
        /** In-reach greedy: may be placed before its lane's walk reaches it ({@link LaneRun#early}). */
        boolean early;
        /** In-reach greedy: its last click went out while Baritone walked. */
        boolean walkSent;
        /** Fast placement: the click in flight went out as the second or later click of its tick. */
        boolean shared;
        /** May be clicked on a neighbour's side face (class javadoc "Side faces"). */
        boolean side;
        /** The shot of its next click (the last recheck) is on a side face. */
        boolean sideShot;
        /** Taller than a step and no block to stand on (pointed dripstone, pots): it can wall the bot in (Way out). */
        boolean trap;
        /** Goes up only once none of these is still to place (a break-in's corridor: the far end first). */
        List<Entry> after = List.of();
        /** A break-in was planned (or found impossible) for this cell: not again in this run. */
        boolean breakInTried;
        /** {@link #bestSpot} said once that this cell would close the bot's way out and took a spot outside. */
        boolean shutLogged;
        /** Goes first of cells that shut in each other ({@link #spotPick}): {@link #sealsIn} no longer holds it back. */
        boolean tieBreak;
        boolean tieLogged;
        /**
         * Skipped as out of reach of every stand spot (not for its item or the server): keep-reachable still keeps its
         * way in open, so VERIFY finds it reachable.
         */
        boolean unreachable;
        /**
         * The target of a break-in or a block of its corridor: it may go up from a cell right next to it (the corridor
         * is 1 wide), and a stand spot for it ignores the walls beside the spot that nobody stands on.
         */
        boolean breakIn;
        /** A raised cell, one block above the lane's height (class javadoc "Raised cells"): a {@link #trap} always. */
        boolean raised;

        Entry(Placement p, double s, double lateral, boolean corridor, boolean solid, boolean shades) {
            this(p, s, lateral, corridor, solid, shades, PlaceMethod.LOOK);
        }

        Entry(Placement p, double s, double lateral, boolean corridor, boolean solid, boolean shades, PlaceMethod method) {
            this.p = p;
            this.method = method;
            this.support = p.pos().below();
            this.s = s;
            this.corridor = corridor;
            this.solid = solid;
            this.shades = shades;
            this.row = (int) Math.round(s);
            this.cell = new LaneWalk.Cell(s, lateral, corridor, shades);
        }
    }

    private final Lane lane;
    private final Io<S> io;
    private final Params params;
    private final int artY;
    /** {@link Io#raisedLevel}: raised cells and blocks one above {@link #artY} (class javadoc "Raised cells"). */
    private final boolean levels;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Integer, List<Entry>> byRow = new HashMap<>();
    private final Map<Long, Entry> byCell = new HashMap<>();
    private @Nullable LaneGeometry geo;
    private @Nullable String setupError;
    private @Nullable String failure;
    private final PlacePipeline<Entry, S> pipeline;
    /**
     * Outcome of every click (confirmed / late / rejected / dropped / unknown), latencies, clicks in flight per hotbar
     * slot.
     */
    private final PlaceTracker<Entry> tracker;
    /** Ticks a cell waited because its stack was used up by clicks in flight ({@link #usedUp}). */
    private int stockWaits;
    private final Env env = new Env();
    private final MissingItemDebounce missingItems = new MissingItemDebounce();
    private Phase phase = Phase.APPROACH;
    private boolean started;
    private boolean checkedWalkable;
    private long now;
    private long lastClick = Long.MIN_VALUE / 4;
    private long lastActivity;
    private long movingSince = -1;
    private double movingFromS;
    private long stayingSince = -1;
    private long holdSince = -1;
    private double prevX = Double.NaN;
    private double prevZ = Double.NaN;
    private boolean reverse;
    /** Way out: the tick the start cells and the openness were taken, and the cells found to close the area then. */
    private long wayOutTick = Long.MIN_VALUE;
    private List<Pos> wayOutStart = List.of();
    private boolean wayOutOpen;
    private final Map<Long, Boolean> wayOutClosing = new HashMap<>();
    /** Keep reachable: cells found (this tick) to close the last way in to a cell still to place. */
    private final Map<Long, Boolean> wayOutSealing = new HashMap<>();
    /** A lane movement stalled: use the shared recovery before handing its cells to stand spots. */
    private @Nullable GoalNearXZ laneRecoveryGoal;
    private boolean laneRecoveryFirst;
    private boolean laneRecoveryTried;
    private boolean walkRepairsAdded;
    private @Nullable DripstoneBreakThrough walkRecovery;
    private final List<Pos> walkCorridor = new ArrayList<>();
    /** VERIFY seal-out: the repair load and its one refill walk. */
    private final List<Lane> repairLoad;
    private SealOut.@Nullable Plan sealOut;
    private int sealRoute;
    private int sealStep;
    private boolean sealWalking;

    /** Break-in (class javadoc): the plan whose walls are being broken, and the cell it is for. */
    private BreakIn.@Nullable Plan breakIn;
    private @Nullable Entry breakInTarget;
    private int breakIns;
    /** Cells of break-in corridors: stand spots there ignore the walls beside them (the corridor is 1 wide). */
    private final Set<Long> corridorCells = new HashSet<>();
    /** Walking back onto the full block behind (the bot's floor cell has one open side left), to {@link #climbTo}. */
    private boolean climbBack;
    /**
     * The walk's direction sign last tick. With the raised level a jump wants the collision from a move the same way: on
     * top of a block the step back from its edge runs into a raised block behind, and the next tick forward would
     * otherwise jump on that collision, off the block into the dip ahead (local sim 2026-11-14).
     */
    private int lastWalkDir;
    /** The lane start the walk could not reach (the run failed for it). */
    private @Nullable Pos unreachedStart;
    /** Every cell from stand spots, no walk along the line ({@link #standSpotsOnly}). */
    private boolean spotsOnly;
    private double climbTo;
    private double endCap;
    private int outerColumn;
    /** AIRPLACE cells may be airplaced this tick ({@link #methodNow}). */
    private boolean airOk;
    private int placed;
    private int skipped;
    /** Skipped cells logged one by one ({@link #SKIP_LOG_MAX}), and the reasons of all of them (for the last line). */
    private int skipLogs;
    private final Map<String, Integer> skipReasons = new java.util.TreeMap<>();
    /** LOOK clicks sent on a neighbour's side face (class javadoc "Side faces"). */
    private int sideClicks;
    /** Cells the walk passed out of reach without placing them (to stand spots). */
    private int leftBehind;
    /** Start tail ({@link Io#startTail}): looked for once; its cells while their stand spots run before the walk. */
    private boolean tailChecked;
    private @Nullable Set<Entry> tail;
    /** One correction at a time, batched across cells behind the forward walk. */
    private final Set<Entry> stepBackCells = new HashSet<>();
    private double stepBackTo;
    private double stepBackFrom;
    private long stepBackAt;
    private int stepBacks;
    /**
     * {@link Io#stepBackClearPath}: how far back (along the lane) the box may still go, computed at tick
     * {@link #backAt}: the return point less the slide after it, or the slide of a backward move; NaN: not moving back.
     */
    private double backTo = Double.NaN;
    private long backAt = -1;
    /** Ticks a step back waited for a block in flight on its return path ({@link Io#stepBackClearPath}). */
    private int backWaits;
    /**
     * {@link Io#forwardClearPath}: how far forward (along the lane, in {@link #fwdDir}) the box may still go before a
     * click sent now is answered ({@link #forwardReach}), computed at tick {@link #fwdAt}; NaN: off.
     */
    private double fwdSweep = Double.NaN;
    /** The same across the lane: the last tick's lateral move kept up over the answer time. */
    private double fwdLat;
    private int fwdDir = 1;
    private long fwdAt = -1;
    /** Ticks the forward walk held its keys for a side block in flight ({@link #forwardPathInFlight}). */
    private int fwdWaits;
    // re-entry of a partly built lane (class javadoc "Re-entry")
    /** The walking line holds full blocks the walk steps up onto ({@link #walkable}). */
    private boolean walkOver;
    /** Paced walk: ticks the walk stood because the speed cap fell below {@link #PACED_STOP_SPEED} (for the log). */
    private int pacedHolds;
    private final StandingTicks standing = new StandingTicks();
    private StandingTicks.Hold capReason = StandingTicks.Hold.OTHER;
    private @Nullable Entry capCell;
    // fast placement (moreClicks)
    /** Ticks of this run's clicks within the click window ({@link #windowAllows}), oldest first. */
    private final ArrayDeque<Long> clickTicks = new ArrayDeque<>();
    private final List<Entry> prepared = new ArrayList<>();
    private int preparedSlot = -1;
    private long tickStartedNanos;
    private long dispatchGeneration;
    private @Nullable Runnable cancelDispatch;
    private boolean ended;
    private int afterMovementSlot = -1;
    private int tickSlotFence = -1;
    private boolean dispatchAtTickStart;
    /** Clicks after the first of a tick, ticks with more than one click, the most in one tick (for the log). */
    private int moreClicks;
    private int multiTicks;
    private int maxPerTick = 1;
    /** Ticks in which the click window held a click back (a first click of a tick, or one more). */
    private int windowHolds;
    private long windowHeldAt = Long.MIN_VALUE;
    // where the walk's ticks go (idleTick)
    private static final int IDLE_NONE = 0;
    private static final int IDLE_WAITING = 1;
    private static final int IDLE_MOVE = 2;
    private static final int IDLE_BUSY = 3;
    private static final int IDLE_PACER = 4;
    private static final int IDLE_INPUT = 5;
    private static final int IDLE_OTHER = 6;
    private final int[] idle = new int[7];
    private int idleWalking;
    private int walkTicks;
    /** Jumped onto a full block of the line: keeps walking until it lands (stopping mid-jump drops it back). */
    private boolean stepping;
    /** The way the bot walked when it jumped ({@link #stepping}): back onto the block behind as well. */
    private int stepDir;
    /** Feet height of the last walk tick (the reach margin covers a jump's vertical move too). */
    private double prevY = Double.NaN;
    /** Where the walk over a walled line starts ({@link #walkStart}), once known. */
    private @Nullable Pos startCell;
    /** The walk to {@link #startCell} failed: the usual approach to the lane start instead. */
    private boolean plainStart;
    /**
     * Line cells the walk covers ({@link #trimEnds}, class javadoc "Line ends"): from {@link #walkFrom} to
     * {@link #walkTo} (s of the cell centres); 0 and the lane's length unless full blocks end the line.
     */
    private int walkFrom;
    private int walkTo = Integer.MAX_VALUE;
    /** The fastest the bot moved along the lane in one tick on the ground (the wall brake's acceleration). */
    private double topSpeed;
    /** Ticks the walk braked before a wall at its end (class javadoc "Line ends"). */
    private int wallBrakes;
    /** Jumps onto full blocks of the line and across pockets in it (for the log). */
    private int stepUps;
    /**
     * Ticks next to a raised block ({@link #raisedNear}) that steered back to the line across only, or whose collision
     * jump on top of a block was not made (for the log).
     */
    private int raisedHolds;
    /** Consecutive {@link #raisedHolds} ticks without a move across the lane (the hold's bound). */
    private int raisedHoldStill;
    /** Where along the lane a hold that did not move the box gave up; NaN: holds apply. */
    private double raisedHoldOffS = Double.NaN;
    /** Walking out of a narrow floor cell this way (along the lane), until it stands somewhere else; 0 = not. */
    private int escapeDir;
    /** The walk over a walled line: furthest point along {@link #headwayDir} since the last click, and when. */
    private double furthest = Double.NaN;
    private int headwayDir;
    private long headwayAt;
    // stand spots
    private SpotStep spotStep = SpotStep.PICK;
    private @Nullable Entry spotTarget;
    private @Nullable Spot spot;
    private long spotSince;
    /** Edge stops: the hazard beside the stand spot being centred on (it is centred on sneaking), or null. */
    private @Nullable String centerHazard;
    /** Edge stops: the bot's x and z on the last centring tick (NaN: none yet), to see it stand still. */
    private double centerLastX = Double.NaN;
    private double centerLastZ;
    private long lastSpotClick;
    private long spotIdleSince = -1;
    private @Nullable Pos triedHere;
    private int spotWalks;
    private int spotPlaced;
    /** Picks of a stand spot since the last placement from it ({@link #SPOT_REPICKS}); spots used up for every cell. */
    private final Map<Long, Integer> spotPicks = new HashMap<>();
    private final Set<Long> deadSpots = new HashSet<>();
    /** The last pick (its spot, {@link #spotPlaced} then), judged at the next pick; picks in a row without placing. */
    private boolean picked;
    private long pickedKey;
    private int placedAtPick;
    private int fruitless;
    /** AIRPLACE mode: walks to floor spots that failed, and last-resort spots (on top of a block, or climbing). */
    private int floorWalkFails;
    private int lastResorts;
    /** The spot being walked to is a last resort ({@link #bestSpot}): one path request, no retries. */
    private boolean spotLastResort;
    // park (the engine eats or stands still mid-lane): see requestPark
    private boolean parkRequested;
    private boolean parked;
    /** The walk back after a stop failed once: the second try takes the park point within {@link #PARK_LOOSE}. */
    private boolean walkBackLoose;
    private double parkX;
    private double parkY;
    private double parkZ;
    /** Moved away while parked in the walk (RUN): Baritone walks back to the park position first. */
    private boolean returning;
    private int parks;
    // in-reach greedy (class javadoc "In-reach greedy")
    /** Cells of the load's later lanes this run may place, and by cell. */
    private final List<Entry> extras = new ArrayList<>();
    private final Map<Long, Entry> extraByCell = new HashMap<>();
    /** Extras sent and not settled yet. */
    private final Set<Entry> extrasInFlight = new java.util.LinkedHashSet<>();
    /** Cells still to place per item (namespaced): this lane's TODO and PENDING cells and the extras'. */
    private final Map<String, Integer> owed = new HashMap<>();
    /** Extras placed; cells of this lane placed while Baritone walked to it; clicks sent while Baritone walked. */
    private int extrasPlaced;
    private int earlyPlaced;
    private int walkClicks;
    /** While Baritone walked: cells in reach held back by a missing hotbar item, and full blocks by the path (log). */
    private final Set<Entry> walkNoHotbar = new HashSet<>();
    private final Set<Entry> walkNearPath = new HashSet<>();
    /** The last click of a cell of this lane (an extra's click does not count: the walk's own timers). */
    private long lastOwnClick = Long.MIN_VALUE / 4;
    /** Position at the last tick that placed while Baritone walked (its step, for the reach margin). */
    private double walkPrevX = Double.NaN;
    private double walkPrevY = Double.NaN;
    private double walkPrevZ = Double.NaN;

    public LaneRun(Lane lane, Io<S> io, Params params) {
        this(lane, io, params, List.of());
    }

    /**
     * {@code load}: the lanes the restock load covers that run after this one (their cells still to place), for the
     * in-reach greedy ({@link Params#greedy}, class javadoc); ignored when it is off.
     */
    public LaneRun(Lane lane, Io<S> io, Params params, List<Lane> load) {
        this.lane = lane;
        this.io = io;
        this.params = params;
        this.repairLoad = params.sealOut() ? List.copyOf(load) : List.of();
        this.artY = lane.from().y();
        this.levels = io.raisedLevel();
        this.pipeline = new PlacePipeline<>(params.pipelined());
        this.tracker = new PlaceTracker<>(io.pacer(), params.placeConfirmTicks(), params.pacing().settleTicks(),
            params.pacing().classifyStackEnds());
        try {
            geo = new LaneGeometry(lane.from().x(), lane.from().z(), lane.to().x(), lane.to().z());
            for (Placement p : lane.placements()) {
                Entry e = entryFor(p);
                entries.add(e);
                byRow.computeIfAbsent(e.row, k -> new ArrayList<>()).add(e);
                byCell.put(key(p.pos().x(), p.pos().z()), e);
                outerColumn = Math.max(outerColumn, Math.abs(e.cell.column()));
            }
        } catch (IllegalArgumentException e) {
            setupError = e.getMessage();
        }
        if (greedy() && setupError == null) {
            boolean look = false;
            for (Entry e : entries) if (e.method == PlaceMethod.LOOK) look = true;
            for (Entry e : entries) {
                // this lane's own walking column waits for the walk (its cells go up behind the player)
                e.early = !e.corridor && early(e.method, e.cell.column(), e.solid, e.shades, look);
                owe(e, 1);
            }
            for (Lane l : load) addExtras(l);
        }
    }

    /** A cell of this lane (or of a break-in corridor) in this lane's coordinates. */
    private Entry entryFor(Placement p) {
        int x = p.pos().x();
        int z = p.pos().z();
        BlockSpec b = p.block();
        boolean up = raisedCell(p.pos());
        boolean solid = up ? raisedWall(b) : b != null && !b.isAir() && io.collisionTop(b) > TALL;
        // AIRPLACE: only the few LOOK cells need sight, and in 9-wide lanes their outer ones see the floor face
        // at the reach edge, so anything above a carpet (petals, plates' rims, roots) waits for them as well
        double shade = params.mode() == PlaceMode.AIRPLACE ? AIR_SHADE : SHADE;
        boolean shades = solid || (b != null && !b.isAir() && io.outlineTop(b) > shade);
        PlaceMethod m = b == null ? PlaceMethod.LOOK : TopFaceRules.placeMethod(b, params.mode());
        Entry e = new Entry(p, geo.cellS(x, z), geo.cellLateral(x, z), geo.inCorridor(x, z), solid, shades, m);
        e.side = !up && params.sideFaces() && b != null && TopFaceRules.sideFacePlaceable(b);
        e.trap = up ? solid : solid && !io.standOnTop(b);
        e.raised = up;
        return e;
    }

    /** {@code pos} is a raised cell: one above the lane's height, with the raised level on ({@link Io#raisedLevel}). */
    private boolean raisedCell(Pos pos) {
        return levels && pos.y() == artY + 1;
    }

    /**
     * A raised block with any collision is a wall at head height for a box on the floor, taller than a step from it
     * and nothing to stand on (class javadoc "Raised cells").
     */
    private boolean raisedWall(@Nullable BlockSpec b) {
        return b != null && !b.isAir() && io.collisionTop(b) > 0;
    }

    /** The in-reach greedy is on and can act: AIRPLACE mode (its cells are airplaced). */
    private boolean greedy() {
        return params.greedy() && params.mode() == PlaceMode.AIRPLACE;
    }

    /**
     * The in-reach greedy may place a cell before its lane's walk reaches it: an airplace cell; never a full block on
     * its lane's walking line or beside it ({@code column} 0 or ±1: it would wall the walk in, a pocket or a 1-wide slot
     * where it is still open), and never a block that hides cells from the eye ({@code shades}) in a lane with LOOK
     * cells (they need line of sight from the line).
     */
    static boolean early(PlaceMethod method, int column, boolean solid, boolean shades, boolean lookLane) {
        return method == PlaceMethod.AIRPLACE && !(solid && Math.abs(column) <= 1) && !(shades && lookLane);
    }

    /** The cells of {@code l} (a later lane of the load) the in-reach greedy may place: {@link #extras}. */
    private void addExtras(Lane l) {
        LaneGeometry g;
        try {
            g = new LaneGeometry(l.from().x(), l.from().z(), l.to().x(), l.to().z());
        } catch (IllegalArgumentException ex) {
            return;
        }
        double shade = AIR_SHADE;
        boolean look = false;
        for (Placement p : l.placements()) {
            BlockSpec b = p.block();
            if (b == null || TopFaceRules.placeMethod(b, params.mode()) == PlaceMethod.LOOK) look = true;
        }
        for (Placement p : l.placements()) {
            BlockSpec b = p.block();
            Pos pos = p.pos();
            long k = key(pos.x(), pos.z());
            boolean up = raisedCell(pos);
            if (b == null || b.isAir() || p.itemId() == null || (pos.y() != artY && !up) || byCell.containsKey(k)
                || extraByCell.containsKey(k)) {
                continue;
            }
            boolean solid = up ? raisedWall(b) : io.collisionTop(b) > TALL;
            boolean shades = solid || io.outlineTop(b) > shade;
            PlaceMethod m = TopFaceRules.placeMethod(b, params.mode());
            int column = (int) Math.round(g.cellLateral(pos.x(), pos.z()));
            Entry e = new Entry(p, geo.cellS(pos.x(), pos.z()), geo.cellLateral(pos.x(), pos.z()), false, solid, shades, m);
            e.trap = up ? solid : solid && !io.standOnTop(b);
            e.raised = up;
            owe(e, 1); // every cell of the load still to place needs its item, whether this run may place it or not
            if (!early(m, column, solid, shades, look)) continue;
            e.extra = true;
            e.early = true;
            extras.add(e);
            extraByCell.put(k, e);
        }
    }

    /** {@link #owed} of {@code e}'s item changes by {@code d}. */
    private void owe(Entry e, int d) {
        owed.merge(ItemIds.ns(e.p.itemId()), d, Integer::sum);
    }

    /** The lane start the walk could not reach, after {@link #tick} failed for that; null otherwise. */
    public @Nullable Pos unreachedStart() {
        return unreachedStart;
    }

    /**
     * Before the first tick: no walk along the line, every cell from stand spots ({@code build.laneStartSpots}: a lane
     * whose start stayed out of reach after the break walk).
     */
    public void standSpotsOnly() {
        spotsOnly = true;
    }

    /** Failure reason after {@link #tick} returned FAILED. */
    public @Nullable String failure() {
        return failure;
    }

    public int placed() {
        return placed;
    }

    public int skipped() {
        return skipped;
    }

    /** Cells placed from stand spots. */
    public int spotPlaced() {
        return spotPlaced;
    }

    /** Baritone walks to stand spots. */
    public int spotWalks() {
        return spotWalks;
    }

    /** AIRPLACE mode: walks to floor stand spots that failed (a path that climbs refused, stalled, not arrived). */
    public int floorWalkFails() {
        return floorWalkFails;
    }

    /** AIRPLACE mode: stand spots taken as the last resort (on top of a full block, or a walk that may climb). */
    public int lastResorts() {
        return lastResorts;
    }

    public String progress() {
        return " (" + placed + " placed, " + skipped + " skipped of " + entries.size() + ", phase " + phase + ")";
    }

    Phase phase() {
        return phase;
    }

    /** Bounded steps back taken during this lane's walk. */
    public int stepBacks() {
        return stepBacks;
    }

    /** A step back is under way (its cells not yet served). */
    boolean steppingBack() {
        return !stepBackCells.isEmpty();
    }

    /** Times the run parked. */
    public int parks() {
        return parks;
    }

    /** Stopped at a safe point after {@link #requestPark()}, until {@link #resume()}. */
    public boolean parked() {
        return parked;
    }

    /**
     * Asks the run to stop at its next safe point (the engine eats or stands still): no new clicks from now on, Baritone
     * stopped, the bot held still; parked once no placement waits for the server, no inventory move is in flight and
     * the bot stands on the ground. Nothing changes if every cell is done: the run finishes instead.
     */
    public void requestPark() {
        io.inventoryNeeded();
        cancelPrepared();
        if (!parked) parkRequested = true;
    }

    /**
     * Continues after a park, with a fresh aim (the rotation may have changed while parked). Moved away meanwhile (food
     * fetched from a chest): a walk in progress goes back to the park position with Baritone first; at stand spots the
     * next spot is picked from where the bot is.
     */
    public void resume() {
        parkRequested = false;
        if (!parked) return;
        parked = false;
        pipeline.reset();
        prevX = Double.NaN;
        prevZ = Double.NaN;
        movingSince = -1;
        stayingSince = -1;
        holdSince = -1;
        spotIdleSince = -1;
        lastActivity = now;
        walkBackLoose = false;
        boolean moved = Math.hypot(io.x() - parkX, io.z() - parkZ) > 0.75 || Math.abs(io.y() - parkY) > 0.6;
        if (phase == Phase.SPOT && spotStep != SpotStep.PICK && spotStep != SpotStep.BREAK) {
            spotStep = SpotStep.PICK; // (a break-in's walls are broken on: its plan still holds)
            triedHere = null;
        }
        if (moved && phase == Phase.RUN) returning = true;
        io.log("Lane " + lane.index() + ": carrying on after a stop" + (moved ? " (moved; walking back)" : ""));
    }

    /** One tick (tick counter {@code now}, increasing by one); the lane watchdog ({@link #watchdog}) after it. */
    public TaskStatus tick(long now) {
        TaskStatus s = tickRun(now);
        if (s != TaskStatus.RUNNING || ended) return s;
        return watchdog(now);
    }

    /**
     * The lane watchdog ({@link Io#watchdogTicks}, {@code build.laneWatchdogTicks}): with no cell settled, no click
     * sent, no change of the hotbar the run sees (counts,
     * held slot, a slot that becomes known) and the bot not {@link #WATCH_MOVE} from where it stood for that many ticks,
     * the run logs one line with what holds it and ends with SUCCESS, its cells left for the next round: the engine plans
     * again (RESTOCK when the load ran out, as after any lane) and never counts it as a failed task. A park, a seal-out
     * and breaking walls have bounds of their own and do not count. Also called for a task tick that did not tick the
     * run (PlaceLaneTask's inventory recovery).
     * Live 2026-10-15: a lane stood still from its first tick for 11 minutes, through a pause and a stop, without a log
     * line; only a reconnect ended it. A hotbar change alone counts as progress for at most
     * {@link Io#watchdogHotbarTicks} ticks after the last placement, click, phase change or movement (live 2026-10-21,
     * ABD [9,5]: a pre-lane layout that swapped stacks back and forth for 40 minutes kept the watchdog from tripping).
     * RUNNING while the run goes on.
     */
    TaskStatus watchdog(long now) {
        int limit = io.watchdogTicks();
        if (limit <= 0 || ended) return TaskStatus.RUNNING;
        double x = io.x();
        double y = io.y();
        double z = io.z();
        long sig = progressSignature();
        long work = workSignature();
        // clicks waiting for the server's answer have the tracker's confirm window (a lag spike is the server's pause)
        boolean ownBound = parked || parkRequested || sealOut != null || laneRecoveryGoal != null || anyPending()
            || (phase == Phase.SPOT && spotStep == SpotStep.BREAK) || (walkRecovery != null && walkRecovery.active());
        if (ownBound || watchAt == Long.MIN_VALUE || work != watchWork || Double.isNaN(watchX)
            || Aim.distSq(x, y, z, watchX, watchY, watchZ) > WATCH_MOVE * WATCH_MOVE) {
            watchAt = now;
            workAt = now;
            watchSig = sig;
            watchWork = work;
            watchX = x;
            watchY = y;
            watchZ = z;
            return TaskStatus.RUNNING;
        }
        int churn = io.watchdogHotbarTicks();
        boolean hotbarOnly = false;
        if (sig != watchSig) {
            // only the hotbar changed: progress, unless that is all that happened for too long
            watchSig = sig;
            if (churn <= 0 || now - workAt < churn) {
                watchAt = now;
                return TaskStatus.RUNNING;
            }
            hotbarOnly = true;
        } else if (now - watchAt < limit) return TaskStatus.RUNNING;
        int left = 0;
        for (Entry e : entries) if (e.st == State.TODO || e.st == State.PENDING) left++;
        String item = null; // the next cell's item: the walk's next, else the first of those left to stand spots
        for (Entry e : remainingOrder()) {
            if (e.p.itemId() == null || (item != null && e.left)) continue;
            item = ItemIds.ns(e.p.itemId());
            if (!e.left) break;
        }
        String held = io.holdState();
        io.log("Lane " + lane.index() + ": watchdog: " + (hotbarOnly
            ? "no placement, click or movement for " + (now - workAt) + " ticks, only hotbar changes"
            : "no placement, click, hotbar change or movement for " + (now - watchAt) + " ticks")
            + " (phase " + phase + (!layoutDone && params.pacing().hotbarStacks() ? ", hotbar layout" : "")
            + (phase == Phase.SPOT ? ", stand spot " + spotStep : "")
            + (phase == Phase.RUN && !Double.isNaN(lastSp) ? "; walk at " + fmt(lastSp) + " ("
            + String.format(java.util.Locale.ROOT, "%.2f across, feet +%.2f", lastLat, lastFeet) + "), target "
            + fmt(lastTarget) + ", hold limit " + fmt(lastLimit) + ", keys " + lastDir + (lastStay ? ", staying" : "") + (lastCollision ? ", collision" : "")
            + (walkOver ? ", over a walled line" : "") + (stepping ? ", stepping" : "") + ", " + lastCands
            + " candidates, " + lastAimable + " clickable" + (lastMove == null ? "" : ", move " + lastMove) : "")
            + (item == null ? "" : "; next item " + item + ": usable slot " + usableSlot(item) + ", "
            + io.itemState(item)) + (held.isEmpty() ? "" : "; " + held) + "); ending the lane, " + left
            + " cells left for the next round");
        close();
        return summary();
    }

    /** What the watchdog counts as progress: cells settled, clicks sent, the hotbar the run sees, the phase. */
    private long progressSignature() {
        long h = workSignature();
        h = h * 31 + io.heldSlot();
        for (int i = 0; i < 9; i++) h = h * 31 + io.hotbarCount(i);
        return h;
    }

    /** {@link #progressSignature} without the hotbar: cells settled, clicks sent, the phase, the layout done. */
    private long workSignature() {
        long h = placed;
        h = h * 31 + skipped;
        h = h * 31 + extrasPlaced;
        h = h * 31 + sentClicks;
        h = h * 31 + phase.ordinal();
        h = h * 31 + (layoutDone ? 1 : 0);
        return h;
    }

    private TaskStatus tickRun(long now) {
        if (ended) return TaskStatus.FAILED;
        this.now = now;
        tickStartedNanos = io.nanoTime();
        tickSlotFence = afterMovementSlot;
        afterMovementSlot = -1;
        if (!prepared.isEmpty()) {
            cancelTimer();
            if (wallAllows()) {
                dispatchAtTickStart = true;
                try {
                    dispatchPrepared();
                } finally {
                    dispatchAtTickStart = false;
                }
                if (ended) return TaskStatus.FAILED;
                if (lastClick == now) {
                    updateEntries();
                    io.holdStill(); // this tick already clicked; no new select, inventory move or aim follows it
                    return TaskStatus.RUNNING;
                }
            } else cancelPrepared(); // an early tick invalidates the old preparation; choose again from its state
        }
        io.beginAirPlaces(params.pacing().multiClick() && params.pacing().airplaceBatch());
        try {
            TaskStatus status = tickBody(now);
            if (status != TaskStatus.RUNNING) close();
            return status;
        } catch (RuntimeException e) {
            close();
            throw e;
        } finally {
            io.endAirPlaces();
        }
    }

    private boolean layoutDone;
    // lane watchdog (#watchdog)
    /** Movement (blocks) from where the bot stood at the last progress that counts as progress itself. */
    static final double WATCH_MOVE = 0.25;
    /** The last progress: its tick, its {@link #progressSignature} and where the bot stood. */
    private long watchAt = Long.MIN_VALUE;
    private long watchSig;
    /** The last progress other than a hotbar change ({@link #workSignature}, movement, a bound of its own): tick, sig. */
    private long workAt;
    private long watchWork;
    private double watchX = Double.NaN;
    private double watchY;
    private double watchZ;
    /** Clicks this run sent (its cells and extras), for the watchdog. */
    private int sentClicks;
    /**
     * The walk's last RUN tick, for the watchdog's line (local 10x run M3: a lane stood still 200 ticks over a walled
     * line, its next item only in the main inventory, and the line did not say why): along, across, feet above the
     * lane's height, target, hold limit (NaN: none), keys along the lane, stay, candidates and clickable, the planned
     * inventory move.
     */
    private double lastSp = Double.NaN;
    private double lastLat;
    private double lastFeet;
    private double lastTarget;
    private double lastLimit;
    private int lastDir;
    private boolean lastStay;
    private boolean lastCollision;
    private int lastCands;
    private int lastAimable;
    private String lastMove;
    /** Items the run said it holds for with no stack to move in ({@link #hold}): once each. */
    private final Set<String> heldFor = new HashSet<>();

    private TaskStatus tickBody(long now) {
        this.now = now;
        if (!walkRepairsAdded) {
            walkRepairsAdded = true;
            List<Pos> repairs = io.walkRepairs();
            if (!repairs.isEmpty()) addCorridor(repairs, null);
        }
        if (setupError != null) return fail(setupError);
        if (parkRequested && !parked && toPark()) return TaskStatus.RUNNING;
        if (parked) {
            io.holdStill();
            return TaskStatus.RUNNING;
        }
        if (!layoutDone && params.pacing().hotbarStacks()) {
            io.holdStill();
            if (io.inventoryBusy()) return TaskStatus.RUNNING;
            List<String> cells = remainingOrder().stream()
                .filter(e -> e.p.itemId() != null && !io.matches(e.p.block(), io.block(e.p.pos())))
                .map(e -> ItemIds.material(e.p.itemId())).toList();
            if (!io.layoutHotbar(cells)) return TaskStatus.RUNNING;
            layoutDone = true;
        }
        if (returning) return walkBack();
        if (sealOut != null) return sealOutTick();
        return switch (phase) {
            case APPROACH -> approach();
            case RUN -> run();
            case SETTLE -> settle();
            case SPOT -> spot();
        };
    }

    // =====================================================================================
    // PARK: a safe stop mid-lane (the engine eats or stands still)
    // =====================================================================================

    /** One tick towards a park; false when the lane has nothing left to place (it finishes instead). */
    private boolean toPark() {
        updateEntries();
        boolean left = false;
        for (Entry e : entries) if (e.st == State.TODO || e.st == State.PENDING) left = true;
        if (!left) {
            parkRequested = false;
            return false;
        }
        io.stopWalking();
        io.holdStill(); // no click from here on: the pipeline only settles
        if (anyPending() || io.parkBusy() || !io.onGround()) return true;
        parked = true;
        parkRequested = false;
        parks++;
        parkX = io.x();
        parkY = io.y();
        parkZ = io.z();
        io.log("Lane " + lane.index() + ": stopped at a safe point (" + phase + ", " + placed + " placed, " + skipped
            + " skipped of " + entries.size() + ")");
        return true;
    }

    /**
     * Moved away while parked in the walk: Baritone back to where the walk stopped, then the walk goes on. A walk back
     * that fails tries once more, taking the park point within {@link #PARK_LOOSE} (live 2026-10-13, remu [1,2] after a
     * fight: Baritone reached the park cell, the strict arrival test failed, and all 937 cells left went to stand spots:
     * 214 s instead of about 50 s walked).
     */
    private TaskStatus walkBack() {
        int bx = (int) Math.floor(parkX);
        int bz = (int) Math.floor(parkZ);
        int by = (int) Math.floor(parkY + 0.1251);
        TaskStatus s = walkBackLoose
            ? recoverWalk(List.of(new GoalNearXZ(bx, by, bz, 1, 2)), this::nearParkLoose)
            : recoverWalk(List.of(new GoalNearXZ(bx, by, bz, 1, 2), new GoalNearXZ(bx, by, bz, 0, 1)), this::nearPark);
        if (s == TaskStatus.RUNNING) {
            placeWhileWalking(parkX, parkZ);
            return s;
        }
        returning = false;
        lastActivity = now;
        movingSince = -1;
        prevX = Double.NaN;
        prevZ = Double.NaN;
        if (s == TaskStatus.FAILED && !walkBackLoose) {
            io.log("Lane " + lane.index() + ": walking back after a stop: " + io.walkError() + "; once more, to within "
                + PARK_LOOSE + " blocks of the stop");
            walkBackLoose = true;
            returning = true;
            return TaskStatus.RUNNING;
        }
        walkBackLoose = false;
        if (s == TaskStatus.FAILED) {
            io.log("Lane " + lane.index() + ": cannot walk back after a stop: " + io.walkError());
            leaveRemaining("left to stand spots after a stop");
        }
        return TaskStatus.RUNNING;
    }

    private boolean nearPark() {
        return Math.hypot(io.x() - parkX, io.z() - parkZ) <= 0.9 && Math.abs(io.y() - parkY) <= 0.6;
    }

    /** Blocks from the park point the second walk back accepts (horizontally; a block up or down). */
    static final double PARK_LOOSE = 1.5;

    private boolean nearParkLoose() {
        return Math.hypot(io.x() - parkX, io.z() - parkZ) <= PARK_LOOSE && Math.abs(io.y() - parkY) <= 1.0;
    }

    // =====================================================================================
    // APPROACH
    // =====================================================================================

    private TaskStatus approach() {
        if (entries.isEmpty()) return TaskStatus.SUCCESS;
        if (!started) {
            started = true;
            io.pacer().setBounds(params.intervalMin(), params.intervalMax());
            io.pacer().configure(params.pacing());
            io.pacer().newRun(); // this lane counts its own ticks: a tick of the last lane means nothing here
            if (startSealOut()) return TaskStatus.RUNNING;
            if (spotsOnly) {
                io.log("Lane " + lane.index() + ": the lane start is out of reach; all " + entries.size()
                    + " cells from stand spots");
                return startSpots();
            }
            if (io.isLoaded(lane.from()) && checkWalkable()) return startSpots();
        }
        Pos from = walkFrom > 0 ? lineCell(walkFrom) : lane.from();
        // feet within 2 blocks of the art layer, or 1 with build.laneStartOnFloor (class javadoc "Lane start")
        // (raised level: within 1, so a goal is never met on top of a raised block, two above the floor)
        int yTolerance = io.startOnFloor() || levels ? 1 : 2;
        if (walkOver && !plainStart) {
            // over a walled line: exactly the start cell, on top of it if it is a full block (a cell next to it may be
            // a pocket); a walk there that fails (a path rule against climbing) falls back to the usual approach
            Pos start = walkStart();
            TaskStatus s = recoverWalk(List.of(new GoalNearXZ(start.x(), start.y(), start.z(), 0, yTolerance)),
                this::onWalkStart);
            if (s == TaskStatus.RUNNING) {
                placeWhileWalking(start.x() + 0.5, start.z() + 0.5);
                return s;
            }
            if (s == TaskStatus.FAILED) {
                io.log("Lane " + lane.index() + ": cannot reach the walk's start " + start + " (" + io.walkError()
                    + "); approaching the lane start as usual");
                plainStart = true;
                return TaskStatus.RUNNING;
            }
        } else {
            TaskStatus s = recoverWalk(List.of(
                new GoalNearXZ(from.x(), from.y(), from.z(), 1, yTolerance),
                new GoalNearXZ(from.x(), from.y(), from.z(), 0, yTolerance)), this::nearStart);
            if (s == TaskStatus.FAILED) {
                unreachedStart = from;
                return fail("cannot reach lane start " + from + ": " + io.walkError());
            }
            if (s == TaskStatus.RUNNING) {
                placeWhileWalking(from.x() + 0.5, from.z() + 0.5);
                return s;
            }
        }
        if (!checkedWalkable && checkWalkable()) return startSpots();
        if (!tailChecked) {
            tailChecked = true;
            if (startTail()) return startSpots();
        }
        // a full block ends the line before its last cell (class javadoc "Line ends"): the walk ends in front of it
        endCap = walkTo < Math.round(geo.length()) ? walkTo
            : extensionWalkable() ? geo.length() + 0.95 : geo.length();
        phase = Phase.RUN;
        lastActivity = now;
        return run();
    }

    /** Checks the walking line once; true if nothing is left for the walk (all from stand spots). */
    private boolean checkWalkable() {
        checkedWalkable = true;
        if (!walkable()) {
            io.log("Lane " + lane.index() + ": walking line walled by full blocks; " + entries.size()
                + " cells from stand spots");
            for (Entry e : entries) e.left = true;
        }
        if (params.standArea() == null) return false;
        for (Entry e : entries) if (!e.left) return false;
        return true;
    }

    /**
     * Start tail ({@link Io#startTail}): the cells of {@link #tail}, or false. A cell counts when it lies behind the
     * walk's start, goes by airplace, and its centre is out of airplace range of the eye at the start cell (the walk
     * only moves away from it). Not over a walled line (a lane entered again: its start rows were built before).
     */
    private boolean startTail() {
        if (!io.startTail() || params.standArea() == null || walkOver || reverse
            || params.mode() != PlaceMode.AIRPLACE || !io.airPlaceReady()) return false;
        Pos start = walkFrom > 0 ? lineCell(walkFrom) : lane.from();
        double ex = start.x() + 0.5, ey = artY + Aim.EYE_HEIGHT, ez = start.z() + 0.5;
        double r = params.airPlaceRange() - (params.reachStepMargin() ? 0.3 : 0) - 0.05;
        airOk = true; // as the walk and the stand spots set it (methodNow)
        Set<Entry> t = new HashSet<>();
        for (Entry e : entries) {
            if (e.st != State.TODO || e.left || e.extra || e.s >= walkFrom || methodNow(e) != PlaceMethod.AIRPLACE) continue;
            Pos c = e.p.pos();
            if (Aim.distSq(ex, ey, ez, c.x() + 0.5, c.y() + 0.5, c.z() + 0.5) > r * r) t.add(e);
        }
        if (t.isEmpty()) return false;
        tail = t;
        io.log("Lane " + lane.index() + ": " + t.size() + " cells behind the start are out of its reach; from stand spots"
            + " before the walk");
        return true;
    }

    private TaskStatus startSpots() {
        phase = Phase.SPOT;
        spotStep = SpotStep.PICK;
        triedHere = null;
        lastActivity = now;
        return spot();
    }

    private boolean nearStart() {
        double x = io.x();
        double z = io.z();
        // a start after full blocks at the line's start (class javadoc "Line ends"): on the floor, not on top of them
        return Math.abs(geo.s(x, z) - walkFrom) <= 1.2 && Math.abs(geo.lateral(x, z)) <= 0.9
            && Math.abs(io.y() - lane.from().y()) <= 1.5 && (walkFrom == 0 || io.y() < artY + 0.5);
    }

    /**
     * Where the walk over a walled line starts: the lane start, or, when that is a narrow floor cell (a pocket or a
     * 1-wide slot: {@link #AIR_MAX_WALLS} or more full-block sides, where the bot never stands), the next line cell
     * that is a full block (the bot stands on top) or an open floor cell, up to three cells on.
     */
    private Pos walkStart() {
        if (startCell == null) {
            startCell = lineCell(walkFrom);
            int n = Math.min((int) Math.round(geo.length()), walkTo);
            for (int i = walkFrom; i <= Math.min(walkFrom + 3, n); i++) {
                Pos c = lineCell(i);
                if (standable(c)) {
                    startCell = c;
                    break;
                }
            }
        }
        return startCell;
    }

    /**
     * In {@link #walkStart}'s cell, above the art layer if it is a full block. Not necessarily on the ground: Baritone may
     * end its path half-way through the last jump (live local run 2026-10-11: "reached ... but the arrival test still
     * fails"); the walk lands it.
     */
    private boolean onWalkStart() {
        Pos c = startCell;
        return c != null && (int) Math.floor(io.x()) == c.x() && (int) Math.floor(io.z()) == c.z()
            && (!io.isLoaded(c) || !tall(c) || io.y() > artY + 0.5);
    }

    /**
     * The walking line can be walked: no full block on a loaded corridor cell between the lane ends (a repair pass, a
     * lane cut by the restock planner or left by a load that ran out, after full blocks were placed), or, in AIRPLACE
     * mode ({@link #walkOverAllowed}), only full blocks at most one high with room for the box on top: the walk steps
     * over them ({@link #walkOver}, class javadoc "Re-entry"). A line that would be walled otherwise is walked between
     * the full blocks at its ends ({@link #trimEnds}, class javadoc "Line ends"). Cells hidden from the line by a full
     * block already in the world in their own row are left to stand spots from the start.
     */
    private boolean walkable() {
        int n = (int) Math.round(geo.length());
        walkTo = n;
        boolean open = lineOpen(0, n);
        if (!open && params.trimEnds() && trimEnds(n)) {
            open = lineOpen(walkFrom, walkTo);
            if (open) {
                io.log("Lane " + lane.index() + ": walking line ends in full blocks; walking line cells " + walkFrom
                    + ".." + walkTo + " of 0.." + n);
            } else {
                walkFrom = 0;
                walkTo = n;
            }
        }
        if (!open && params.openSegment()) open = openSegment(n);
        if (!open) return false;
        if (params.standArea() == null) return true;
        boolean alongZ = geo.alongZ();
        for (Entry e : entries) {
            if (e.method == PlaceMethod.AIRPLACE) continue; // no line of sight needed
            int col = e.cell.column();
            Pos pos = e.p.pos();
            for (int c = Math.abs(col) - 1; c >= 1; c--) {
                int d = Integer.signum(col) * (Math.abs(col) - c);
                Pos between = alongZ ? pos.offset(-d, 0, 0) : pos.offset(0, 0, -d);
                if (io.isLoaded(between) && tall(between)) {
                    e.left = true;
                    break;
                }
            }
        }
        return true;
    }

    /**
     * Line cells {@code lo..hi} can be walked: no full block on a loaded one, or only full blocks the walk may step over
     * ({@link #walkOverAllowed}: at most one high, room for the box on top, no line cell next to a setback cell), which
     * sets {@link #walkOver}.
     */
    private boolean lineOpen(int lo, int hi) {
        int walls = 0;
        boolean steppable = true;
        Pos setback = null;
        for (int i = lo; i <= hi; i++) {
            Pos c = lineCell(i);
            boolean up = io.isLoaded(c) && tall(c);
            if (up) {
                walls++;
                // one block up, with room for the box on top (nothing on a flat platform is higher); a block to stand on:
                // never onto pointed dripstone or a pot (class javadoc "Way out")
                if (io.collisionTop(io.block(c)) > 1.0 + 1e-9 || !passable(c.above()) || !passable(c.above().above())
                    || (params.wayOut() && !io.standOnTop(io.block(c)))) {
                    steppable = false;
                }
            }
            if (setback == null && !io.standAllowed(up ? c.above() : c)) setback = c; // a setback cell (or no-go zone)
        }
        if (walls == 0) return true;
        if (!steppable || !walkOverAllowed()) return false;
        if (setback != null) {
            io.log("Lane " + lane.index() + ": walking line holds " + walls + " full blocks and passes " + setback
                + " next to a setback cell; not walking over them");
            return false;
        }
        walkOver = true;
        io.log("Lane " + lane.index() + ": walking line holds " + walls + " full blocks; walking over them");
        return true;
    }

    /**
     * The walk's ends ({@link #walkFrom}, {@link #walkTo}; class javadoc "Line ends"): full blocks on the line at or
     * before the lane's first row of cells, and at or after its last row, come off the walk. The walk then starts after
     * the last of the first kind and ends before the first of the second kind; the cells of those rows are placed from
     * the row next to them, or from stand spots. False (nothing changed) when no such block is there or nothing would be
     * left to walk.
     */
    private boolean trimEnds(int n) {
        if (entries.isEmpty()) return false;
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (Entry e : entries) {
            first = Math.min(first, e.row);
            last = Math.max(last, e.row);
        }
        int lo = 0;
        int hi = n;
        for (int i = 0; i <= Math.min(first, n); i++) if (lineWall(i)) lo = i + 1;
        for (int i = n; i >= Math.max(last, 0); i--) if (lineWall(i)) hi = i - 1;
        if (lo > hi || (lo == 0 && hi == n)) return false;
        walkFrom = lo;
        walkTo = hi;
        return true;
    }

    /** The open segment serving the most remaining cells; the rest goes to stand spots, as for a walled lane. */
    private boolean openSegment(int n) {
        int[] rows = new int[n + 2];
        for (Entry e : entries) if (e.row >= 0 && e.row <= n) rows[e.row + 1]++;
        for (int i = 1; i < rows.length; i++) rows[i] += rows[i - 1];
        int lo = 0;
        int bestFrom = 0;
        int bestTo = -1;
        int best = 0;
        for (int i = 0; i <= n + 1; i++) {
            if (i <= n && !lineWall(i)) continue;
            int count = rows[i] - rows[lo];
            if (count > best) {
                best = count;
                bestFrom = lo;
                bestTo = i - 1;
            }
            lo = i + 1;
        }
        // A short walk costs an approach and a turn: save at least 16 cells and a quarter of the stand-spot work.
        if (best < Math.max(16, (entries.size() + 3) / 4) || !lineOpen(bestFrom, bestTo)) return false;
        walkFrom = bestFrom;
        walkTo = bestTo;
        for (Entry e : entries) if (e.row < walkFrom || e.row > walkTo) e.left = true;
        io.log("Lane " + lane.index() + ": walking line walled by full blocks; walking line cells " + walkFrom
            + ".." + walkTo + " of 0.." + n + " (" + best + " of " + entries.size() + " cells), the other "
            + (entries.size() - best) + " from stand spots");
        return true;
    }

    /** Line cell {@code i} is loaded and holds a full block. */
    private boolean lineWall(long i) {
        Pos c = lineCell(i);
        return io.isLoaded(c) && tall(c);
    }

    /**
     * A wall at feet or head height for a box on the floor: a block taller than a step in {@code p}, or, with the raised
     * level, a raised block over it ({@link #raisedAt}: the column is read, not the cell).
     */
    private boolean tall(Pos p) {
        BlockSpec b = io.block(p);
        if (!b.isAir() && io.collisionTop(b) > TALL) return true;
        return raisedAt(p);
    }

    /**
     * Raised level ({@link Io#raisedLevel}): the cell above {@code p} (a cell at the lane's height) holds a block with
     * collision. Such a column is a wall a box on the floor can neither pass under (one block of room) nor climb (two
     * high) and nobody stands on (class javadoc "Raised cells"). False without the raised level.
     */
    private boolean raisedAt(Pos p) {
        if (!levels || p.y() != artY) return false;
        Pos up = p.above();
        return io.isLoaded(up) && raisedWall(io.block(up));
    }

    /**
     * A walking line with full blocks on it is walked, the bot stepping up onto them, rather than left to stand spots:
     * AIRPLACE lanes with airplace available ({@code build.walkWalledLanes}). Airplace needs no line of sight and
     * reaches every column of the lane from on top of a block (eye 2.12 above the cell centres: the outer column of a
     * 9-wide lane stays within the walking range for about ±1.4 blocks along the lane).
     */
    private boolean walkOverAllowed() {
        return params.walkWalled() && params.mode() == PlaceMode.AIRPLACE && io.airPlaceReady();
    }

    /** True if the cell one past {@code to} can be stood on (floor below, nothing solid at feet/head height). */
    private boolean extensionWalkable() {
        double[] f = geo.forward();
        Pos to = lane.to();
        Pos beyond = to.offset((int) f[0], 0, (int) f[1]);
        if (!io.isLoaded(beyond)) return false;
        if (!io.blocksPlacement(beyond.below())) return false;
        BlockSpec feet = io.block(beyond);
        BlockSpec head = io.block(beyond.above());
        return (feet.isAir() || io.collisionTop(feet) <= TALL)
            && (head.isAir() || io.collisionTop(head) == 0.0)
            && !io.hasFluid(beyond);
    }

    // =====================================================================================
    // RUN: the walk along the lane
    // =====================================================================================

    private TaskStatus run() {
        if (laneRecoveryGoal != null) {
            GoalNearXZ goal = laneRecoveryGoal;
            boolean failed = laneRecoveryFirst;
            laneRecoveryFirst = false;
            TaskStatus status = recoverWalk(List.of(goal), () -> feet().equals(new Pos(goal.x(), goal.y(), goal.z())),
                false, false, "lane walk", failed);
            lastActivity = now;
            if (status == TaskStatus.RUNNING) return status;
            laneRecoveryGoal = null;
            movingSince = -1;
            headwayAt = now;
            if (status == TaskStatus.FAILED) leaveRemaining("lane walk break-through failed: " + io.walkError());
            return TaskStatus.RUNNING;
        }
        double px = io.x();
        double py = io.y();
        double pz = io.z();
        double sp = geo.s(px, pz);
        updateEntries();

        List<LaneWalk.Cell> todo = new ArrayList<>();
        for (Entry e : entries) if (e.st == State.TODO && !e.left) todo.add(e.cell);
        if (todo.isEmpty() && stepBackCells.isEmpty()) {
            phase = Phase.SETTLE;
            return settle();
        }
        int dir = reverse ? -1 : 1;

        // over a walled line the box counts from the floor up even on top of a block: a cell beside the line goes up
        // only once the walk is clear of it, as on the floor (the bot may step down next to it)
        double[] safe = safeBox(px, py, pz);
        // the last tick's move, a jump's rise or fall included (walking over a walled line)
        double rise = Double.isNaN(prevY) ? 0 : py - prevY;
        double step = Double.isNaN(prevX) ? 0.3
            : Math.min(0.5, Math.sqrt((px - prevX) * (px - prevX) + rise * rise + (pz - prevZ) * (pz - prevZ)));
        prevY = py;
        double ds = Double.isNaN(prevX) ? 0 : sp - geo.s(prevX, prevZ); // last tick's move along the lane
        double mvx = Double.isNaN(prevX) ? 0 : px - prevX; // the last tick's move
        double mvz = Double.isNaN(prevX) ? 0 : pz - prevZ;
        double dLat = Double.isNaN(prevX) ? 0 : geo.lateral(px, pz) - geo.lateral(prevX, prevZ); // across the lane
        prevX = px;
        prevZ = pz;
        double reach = Math.min(params.reach(), SERVER_RANGE - (params.reachStepMargin() ? step : 0) - 0.05);
        double reachH = LaneWalk.horizontalReach(reach, Aim.EYE_HEIGHT);
        double airReach = params.airPlaceRange() - (params.reachStepMargin() ? step : 0) - 0.05;
        airOk = params.mode() == PlaceMode.AIRPLACE && io.airPlaceReady();

        if (params.stepBack()) stepBack(sp, safe, reach, airReach);
        boolean backing = !stepBackCells.isEmpty();
        backTo = io.stepBackClearPath() && !reverse ? backReachTo(sp, ds, backing) : Double.NaN;
        backAt = now;
        fwdSweep = io.forwardClearPath() ? forwardReach(ds * dir) : Double.NaN;
        fwdLat = Math.max(-FWD_SWEEP_MAX, Math.min(FWD_SWEEP_MAX, dLat * fwdTicks()));
        fwdDir = dir;
        fwdAt = now;

        // out of reach behind the walk: left to stand spots, so it no longer holds back the blocks that wait for it
        for (Entry e : entries) {
            double behind = methodNow(e) == PlaceMethod.AIRPLACE ? airReach : LaneWalk.horizontalReach(reach, 0);
            if (e.st == State.TODO && !e.left && !stepBackCells.contains(e) && (sp - e.s) * dir > behind + 1.0) {
                e.left = true;
                leftBehind++;
            }
        }
        boolean sight = lookLeft();
        List<Entry> cands = new ArrayList<>();
        Pos feet = feet();
        // standing on a block: cannot be walled in (on a pointed dripstone it can: it falls off; class javadoc "Way out")
        boolean raised = io.onGround() && io.y() > artY + TALL
            && (!params.wayOut() || !io.isLoaded(feet.below()) || io.standOnTop(io.block(feetCell())));
        boolean onTop = raised && io.y() >= artY + 1 - 1e-6;      // and the box is clear of the art layer
        if (raised) climbBack = false;
        boolean lastWayOut = !raised && walledSides(feet.x(), feet.z()) >= 3; // airborne counts as on the floor
        boolean wayOutBlocked = false;
        double[] hold = {Double.NaN};
        java.util.function.Consumer<Entry> holdFor = y -> {
            double l = LaneWalk.holdLimit(y.cell, reachH, dir);
            if (Double.isNaN(hold[0]) || (l - hold[0]) * dir < 0) hold[0] = l;
        };
        for (Entry e : entries) {
            if (e.st != State.TODO || e.left) continue;
            // a waiting block holds the walk within reach of itself and of every cell it waits for
            if (e.shades && (e.released ? waiting(e, dir) : waitingOn(e, dir, holdFor))) {
                if (!e.released) holdFor.accept(e);
                continue;
            }
            if (!placeableNow(e, sp, safe, dir, onTop, sight)) continue;
            if (e.solid && lastWayOut && adjacent(e.p.pos(), feet)) {
                wayOutBlocked = true; // it would wall the bot in on the floor: place it from on top of a block
                continue;
            }
            // A correction must keep its return path open, including the cells beside the line.
            if (blocksStepBack(e) || inBackPath(e) || inForwardPath(e)) continue;
            if (heldBack(e)) continue; // it would close the bot's way out or a cell's way in: placed later
            if (!inReachNow(e, reach, airReach)) continue;
            cands.add(e);
        }
        double limit = hold[0]; // how far the walk may go while blocks wait
        // paced walk (class javadoc "Paced walk"): forward on a floor line with airplace, the queue in deadline order
        double latEye = geo.lateral(px, pz);
        // (off the line, as at a lane start after Baritone, the walk stops and goes as before while it steers back:
        // the lane's width assumes the eye on the line)
        boolean paced = params.pacing().pacedWalk() && airOk && !walkOver && !reverse
            && Math.abs(latEye) <= PACED_MAX_OFF_LINE;
        double eyeY = io.eyeY();
        if (paced) {
            cands.sort(Comparator.comparingDouble(e -> deadline(e, sp, eyeY, latEye, dir)));
        } else {
            cands.sort(Comparator.<Entry>comparingDouble(e -> reverse ? -e.s : e.s)
                .thenComparingDouble(e -> Math.abs(e.cell.lateral())));
        }
        List<Entry> aimable = new ArrayList<>(cands.size());
        for (Entry e : cands) if (!losBlocked(e, sp)) aimable.add(e); // never set for airplace cells
        aimable = clickable(aimable);

        env.begin(reach, sp);
        PlacePipeline.Plan<Entry, S> plan = pipeline.plan(now, aimable, env);
        // in-reach greedy: a tick with no click for this lane's cells clicks a cell of a later lane instead
        if (greedy() && airOk && plan.click() == null && plan.inventoryMove() == null) {
            plan = withExtra(plan, px, pz, safe, feet, airReach, sp, eyeY, latEye, dir);
        }
        // an item that is only in the main inventory needs an inventory click: stand still for it (MultiActionsC)
        String move = plan.inventoryMove();

        // stay while something reachable behind/alongside is still to be placed (paced: only a LOOK cell; the
        // airplace cells set the speed cap instead)
        boolean stay = false;
        for (Entry e : cands) {
            boolean behind = reverse ? e.s > sp - 0.5 : e.s < sp + 0.5;
            if (behind && !losBlocked(e, sp) && (!paced || methodNow(e) == PlaceMethod.LOOK)) {
                stay = true;
                break;
            }
        }
        double vCap = paced ? pacedCap(cands, aimable, sp, eyeY, latEye, dir) : Double.POSITIVE_INFINITY;
        if (vCap < PACED_STOP_SPEED) { // the queue no longer keeps up with walking: stop until it has
            stay = true;
            pacedHolds++;
            standing.cap(capReason, capCell == null ? null : "row " + Math.round(capCell.s)
                + " column " + Math.round(capCell.cell.lateral()) + " " + env.item(capCell));
        }
        if (backing) stay = sp <= stepBackTo + 0.05;
        if (stay) {
            if (stayingSince < 0) stayingSince = now;
            if (now - stayingSince > 60 && now - lastOwnClick > 60) stay = false; // cannot place it from here: move on
        } else {
            stayingSince = -1;
        }

        // the rotation requested now is the one this tick's movement uses: keys are relative to it (a silent look
        // leaves the real rotation as it is)
        S rot = plan.nextShot();
        float effYaw = rot != null && !params.silentRotation() ? rot.yaw() : io.yaw();
        double target = reverse ? LaneWalk.reverseTarget(todo, sp) : LaneWalk.forwardTarget(todo, sp, endCap);
        // walking back: never into the full blocks before the walk's start (class javadoc "Line ends")
        if (reverse && walkFrom > 0) target = Math.max(target, walkFrom + LaneWalk.MIN_BACK_S);
        // walking back: never into a raised block of the line (class javadoc "Raised cells"); what is not placeable
        // from in front of it goes to stand spots at once, not after the stuck wait
        boolean raisedStop = false;
        if (reverse && !onTop && !backing && !climbBack) {
            double stop = raisedStopBehind(sp);
            if (!Double.isNaN(stop) && target < stop) {
                target = stop + 0.02;
                raisedStop = true;
            }
        }
        // walking back with only one open side left: back up onto the full block right behind, place from on top
        if (reverse && wayOutBlocked && !climbBack) {
            Pos behind = lineCell(Math.round(sp) - 1);
            if ((params.wayOut() || levels) && io.isLoaded(behind)
                && (raisedAt(behind) || !io.standOnTop(io.block(behind)))) {
                // no block to stand on (pointed dripstone, a pot): the rest from stand spots (class javadoc "Way out")
                io.holdStill();
                leaveRemaining("left to stand spots: the block behind " + behind + " is no block to stand on");
                return TaskStatus.RUNNING;
            }
            if (!io.standAllowed(behind.above())) {
                // never up next to a setback cell (or into a no-go zone): the rest from stand spots, which keep away
                io.holdStill();
                leaveRemaining("left to stand spots: the block behind " + behind + " is next to a setback cell");
                return TaskStatus.RUNNING;
            }
            climbBack = true;
            climbTo = Math.round(sp) - 1.0; // centre of the cell behind (cell centres lie at whole s)
        }
        if (reverse && climbBack) {
            target = Math.min(target, climbTo);
            stay = false; // half-way up a jump: stopping would drop the bot back into the cell
        } else if (reverse && onTop) {
            target = sp; // on a block at the lane end: everything left is placed from up here (or from stand spots)
        }
        boolean held = false;
        if (!Double.isNaN(limit) && (target - limit) * dir > 0) {
            target = limit;
            held = true;
        }
        if (backing) target = stepBackTo;
        int dirSign = 0;
        if (stepping && io.onGround()) stepping = false;
        if (stepping) {
            dirSign = stepDir; // half-way up onto a full block: stopping or turning would drop it back in front of it
        } else if (!stay && move == null) {
            if (backing && sp > target + 0.05) dirSign = -1;
            else if (!backing && !reverse && sp < target - 0.05) dirSign = 1;
            else if (reverse && sp > target + 0.05) dirSign = -1;
        }
        if (backing && dirSign == -1 && !Double.isNaN(backTo) && backPathInFlight()) {
            dirSign = 0; // the box would go into a block the proxy does not show yet: wait for its block update
            backWaits++;
        }
        boolean braking = false;
        if (io.onGround() && Math.abs(ds) <= LaneWalk.TOP_SPEED_MAX) topSpeed = Math.max(topSpeed, Math.abs(ds));
        if (io.wallBrake() && !stepping && !walkOver && move == null) {
            // a wall right after the walk's end (class javadoc "Line ends"): keys only while the stop after them stays
            // in the end cell, braking while the slide alone would leave it
            double accel = LaneWalk.accel(topSpeed);
            if (!reverse && !backing && walkTo < Math.round(geo.length()) && target >= endCap - 0.05) {
                double wall = endCap + LaneWalk.WALL_STOP;
                if (dirSign == 1 && sp + LaneWalk.pressedStop(ds, accel) > wall) dirSign = 0;
                if (dirSign == 0 && sp + LaneWalk.slide(ds) > wall) braking = true;
            } else if (reverse && !backing && !climbBack && !onTop && walkFrom > 0
                && target <= walkFrom + LaneWalk.MIN_BACK_S + 0.05) {
                double wall = walkFrom - LaneWalk.WALL_STOP;
                if (dirSign == -1 && sp - LaneWalk.pressedStop(-ds, accel) < wall) dirSign = 0;
                if (dirSign == 0 && sp + LaneWalk.slide(ds) < wall) braking = true;
            }
            if (braking) {
                dirSign = -dir;
                wallBrakes++;
            }
        }
        // walking over a walled line: across a dip in one jump, never resting at its edge, never standing in a pocket
        // or a 1-wide slot
        double past = walkOver && raised && !stepping ? pastMiddleBeforeDip(sp, dir) : Double.NaN;
        boolean hop = false;
        if (!Double.isNaN(past)) {
            double block = sp - past * dir; // middle of the full block the bot stands on
            if (dirSign == dir && (target - block) * dir < 1.5) dirSign = 0; // nothing beyond the pocket: stay on top
            if (dirSign == dir) {
                // jump when it lands on the middle of the block beyond the pocket; too fast for that: brake first
                double land = past + LaneWalk.hopLength(Math.max(0, ds * dir));
                if (land > LaneWalk.HOP_LAND_MAX) dirSign = -dir;
                else hop = land >= LaneWalk.HOP_LAND_MIN || past >= LaneWalk.HOP_LAST;
            } else if (past > 0.15) {
                dirSign = -dir; // back from the edge (a slide after a landing)
            }
        } else if (walkOver && !stepping && !raised && io.onGround() && narrowByTheLine(sp, feet)) {
            // never rests in a pocket or a slot of the line's own blocks: to the nearest line cell it may rest in,
            // the same way until out
            if (dirSign == 0 || escapeDir != 0) {
                if (escapeDir == 0) escapeDir = outOfNarrowCell(sp, dir);
                dirSign = escapeDir;
            }
        } else if (io.onGround()) {
            escapeDir = 0;
        }
        // raised level: a raised block beside holds the box off the line (on the floor at head height, where no jump
        // gets past it; on top of a block at feet height, where the collision's jump climbs it): back across to the
        // line first, then on (class javadoc "Raised cells")
        // (bounded: a hold that does not move the box across for RAISED_HOLD_STILL ticks lets the walk go on as before
        // until it has moved a block along; local 10x run M2: one lane stood 200 ticks and the watchdog ended it)
        if (!Double.isNaN(raisedHoldOffS) && Math.abs(sp - raisedHoldOffS) > 1.0) raisedHoldOffS = Double.NaN;
        boolean raisedHold = levels && dirSign != 0 && !braking && !stepping && io.onGround()
            && io.horizontalCollision() && Double.isNaN(raisedHoldOffS)
            && Math.abs(geo.lateral(px, pz)) > RAISED_HOLD_LAT && raisedNear(px, pz);
        if (raisedHold) {
            raisedHoldStill = Math.abs(dLat) < 0.005 ? raisedHoldStill + 1 : 0;
            if (raisedHoldStill > RAISED_HOLD_STILL) {
                io.log("Lane " + lane.index() + ": held by a raised block beside at " + fmt(px) + "," + fmt(py) + ","
                    + fmt(pz) + " (" + fmt(geo.lateral(px, pz)) + " off the line) without moving across for "
                    + RAISED_HOLD_STILL + " ticks: walking on");
                raisedHoldOffS = sp;
                raisedHoldStill = 0;
                raisedHold = false;
            } else {
                dirSign = 0;
                raisedHolds++;
            }
        } else {
            raisedHoldStill = 0;
        }
        double[] fwd = geo.forward();
        double[] steer = geo.steer(px, pz, 2.0);
        double lat = geo.lateral(px, pz);
        double dx = fwd[0] * dirSign;
        double dz = fwd[1] * dirSign;
        if (move == null && (dirSign != 0 || raisedHold || Math.abs(lat) > STEER_DEADBAND)) {
            dx += steer[0] * 0.6;
            dz += steer[1] * 0.6;
        }
        int[] keys = Aim.movementKeys(effYaw, dx, dz);
        if (!Double.isNaN(fwdSweep) && !stepping && !walkOver && io.onGround() && keyedInto(effYaw, keys, mvx, mvz)) {
            // the forward twin of backPathInFlight: never a move into a side block the proxy does not show yet (or
            // showed only this tick): without the steering, else keys up, else (the slide alone) braking; not over a
            // walled line (its hops and pocket escapes must not stop half-way)
            int[] straight = Aim.movementKeys(effYaw, fwd[0] * dirSign, fwd[1] * dirSign);
            if (dirSign != 0 && !keyedInto(effYaw, straight, mvx, mvz)) {
                keys = straight;
            } else {
                double f = LaneWalk.FRICTION;
                keys = forwardPathInFlight(mvx * f, mvz * f) ? Aim.movementKeys(effYaw, -mvx, -mvz) : new int[]{0, 0};
                dirSign = 0;
            }
            fwdWaits++;
        }
        boolean moving = keys[0] != 0 || keys[1] != 0;
        boolean jump = moving && dirSign != 0 && !braking && io.horizontalCollision() && io.onGround()
            && !trapAhead(px, pz, dx, dz) // never up onto a pointed dripstone (class javadoc "Way out")
            && (!levels || lastWalkDir == dirSign);
        if (jump && py >= artY + 1 - 1e-6 && raisedNear(px, pz)) {
            jump = false; // on top of a block a raised block is a wall at feet height: walked along, never climbed
            raisedHolds++;
        }
        lastWalkDir = dirSign;
        if (hop && moving && io.onGround()) jump = true;
        if (jump && walkOver) {
            stepping = true;
            stepDir = dirSign;
            stepUps++;
        }
        lastSp = sp;
        lastLat = lat;
        lastFeet = py - artY;
        lastTarget = target;
        lastLimit = limit;
        lastDir = dirSign;
        lastStay = stay;
        lastCollision = io.horizontalCollision();
        lastCands = cands.size();
        lastAimable = aimable.size();
        lastMove = move;
        io.submit(rot == null ? null : rot.yaw(), rot == null ? null : rot.pitch(), keys[0], keys[1], jump);
        // Diagnose before execution changes cell/stack state; count only the ticks that actually have keys up.
        boolean standingTick = paced && !moving && !jump;
        StandingTicks.Idle standingReason = standingTick ? standingReason(cands, aimable, plan, reach, airReach)
            : StandingTicks.Idle.OTHER;
        boolean anyClick = pipeline.execute(now, plan, env);
        if (anyClick && plan.air() && plan.click() != null && !plan.click().extra && params.pacing().multiClick()) {
            moreClicks(aimable, sp, safe, dir, onTop, sight, reach, airReach);
        }
        walkTicks++;
        if (standingTick) standing.tick(anyClick, standingReason);
        if (!anyClick) idleTick(cands, aimable, plan, moving);
        if (!anyClick && move == null && paced && vCap < PACED_STOP_SPEED && !moving && !jump
            && plan.click() == null && io.canClick() && !io.inventoryBusy()
            && (params.pacing().prefetch() || io.lookaheadPrefetch())) {
            prefetch(cands);
        }
        if (move != null && !anyClick) {
            // inventory click only in a tick without a use-item-on, after a still tick (Hotbar.ensureHeld)
            hold(move);
        }
        // a click of a later lane's cell (in-reach greedy) does not count for the walk's own timers
        boolean clicked = anyClick && plan.click() != null && !plan.click().extra;

        // held with nothing placed for longer than a miss and its retry: let the walk go on (stand spots later)
        if (held && dirSign == 0 && !clicked) {
            if (holdSince < 0) holdSince = now;
            if (now - holdSince > (long) MAX_SENDS * Math.max(1, params.placeConfirmTicks()) + HOLD_EXTRA_TICKS) {
                for (Entry e : entries) if (e.st == State.TODO && e.shades && !e.left && waiting(e, dir)) e.released = true;
                holdSince = -1;
                lastActivity = now;
            }
        } else {
            holdSince = -1;
        }

        // over a walled line: a click, or a block more of headway, at least every NO_HEADWAY_TICKS; the walk can go
        // back and forth over the line's blocks without either (live local run: 3 task timeouts on one cell)
        if (walkOver) {
            if (clicked || Double.isNaN(furthest) || headwayDir != dir || (sp - furthest) * dir > 1.0) {
                furthest = sp;
                headwayDir = dir;
                headwayAt = now;
            } else if (now - headwayAt > NO_HEADWAY_TICKS) {
                if (startLaneRecovery(target)) return TaskStatus.RUNNING;
                leaveRemaining("without a placement or headway for " + NO_HEADWAY_TICKS + " ticks at " + fmt(px) + ","
                    + fmt(pz));
                return TaskStatus.RUNNING;
            }
        }
        // stuck / no-progress handling
        if (dirSign != 0) {
            if (movingSince < 0 || Math.abs(sp - movingFromS) > 0.3) {
                movingSince = now;
                movingFromS = sp;
                lastActivity = now;
            } else if (now - movingSince > IDLE_TICKS) {
                if (startLaneRecovery(target)) return TaskStatus.RUNNING;
                leaveRemaining("stuck at " + fmt(px) + "," + fmt(pz));
                return TaskStatus.RUNNING;
            }
        } else {
            movingSince = -1;
            boolean atEnd = reverse ? sp <= target + 0.1 : sp >= target - 0.1;
            if (raisedStop && atEnd && cands.isEmpty() && !clicked) {
                leaveRemaining("left to stand spots: a raised block of the line behind");
            } else if (backing) {
                lastActivity = now; // the correction has its own bounded wait
            } else if (!reverse && atEnd && sp >= endCap - 0.1 && LaneWalk.blockedAtEnd(todo, sp)) {
                reverse = true;
                lastActivity = now;
            } else if (io.lostTicks() > 0 || env.inventoryBusy() || (move != null && io.unknownCount(move) > 0)) {
                // not our fault: input owned by another module, an inventory request in flight, or the item only in
                // unknown slots that wait for the full inventory answer (bounded: build.unknownSlotTrustTicks, the
                // lane watchdog)
                lastActivity = now;
            } else if (held) {
                lastActivity = now; // a block waits for a neighbour (a miss and its retry): the hold has its own limit
            } else if (now - lastActivity > IDLE_TICKS) {
                leaveRemaining("not placeable from the lane");
            }
        }
        return TaskStatus.RUNNING;
    }

    /** A short return on the open floor line, then the same forward walk; no new path or click machinery. */
    private void stepBack(double sp, double[] safe, double reach, double airReach) {
        if (!stepBackCells.isEmpty()) {
            stepBackCells.removeIf(e -> e.st == State.DONE || e.st == State.SKIPPED || e.left);
            if (!stepBackCells.isEmpty() && (!stepBackSafe(Math.max(sp, stepBackFrom), stepBackTo)
                || now - stepBackAt > (long) MAX_SENDS * Math.max(1, params.placeConfirmTicks()) + HOLD_EXTRA_TICKS)) {
                for (Entry e : stepBackCells) if (!e.left) { e.left = true; leftBehind++; }
                stepBackCells.clear();
            }
            if (!stepBackCells.isEmpty()) return;
            stayingSince = -1;
            lastActivity = now;
        }
        if (reverse || stepping || !io.onGround() || io.y() > artY + TALL || stepBacks >= STEP_BACK_LIMIT
            || Math.abs(geo.lateral(io.x(), io.z())) > PACED_MAX_OFF_LINE) return;
        double eyeY = io.eyeY();
        double lat = geo.lateral(io.x(), io.z());
        double to = sp;
        boolean sight = lookLeft();
        double[] f = geo.forward();
        List<Entry> missed = new ArrayList<>();
        for (Entry e : entries) {
            if (e.st != State.TODO || e.left || e.stepBackTried || e.s >= sp || inReachNow(e, reach, airReach)) continue;
            double dl = deadline(e, sp, eyeY, lat, 1);
            if (dl >= -0.05 || dl < -STEP_BACK_MAX || io.count(ItemIds.ns(e.p.itemId())) <= 0
                || (e.shades && waiting(e, 1)) || heldBack(e)
                || !placeableNow(e, sp, safe, 1, false, sight)) continue;
            double want = sp + dl - pacedMargin();
            if (want < walkFrom || want > walkTo || !stepBackSafe(sp, want)) continue;
            double ex = io.x() + (want - sp) * f[0];
            double ez = io.z() + (want - sp) * f[1];
            // The usual box, order and sight rules must still allow a click at the return point.
            double[] box = Aim.playerBox(ex, io.y(), ez, LaneWalk.SAFE_INFLATE);
            if (!placeableNow(e, want, box, 1, false, sight)) continue;
            if (methodNow(e) == PlaceMethod.LOOK && !io.visibleFrom(ex, eyeY, ez, e.support, reach)) continue;
            missed.add(e);
            to = Math.min(to, want);
        }
        if (missed.isEmpty()) return;
        stepBackFrom = sp;
        stepBackTo = to;
        stepBackAt = now;
        stepBacks++;
        for (Entry e : missed) { e.stepBackTried = true; stepBackCells.add(e); }
        stayingSince = -1;
        io.log("Lane " + lane.index() + ": step back " + stepBacks + " for " + missed.size() + " cells ("
            + fmt(sp - to) + " blocks)");
    }

    /** {@link #PACED_MARGIN}, or {@link #PACED_MARGIN_STEP_BACK} with step back on. */
    private double pacedMargin() {
        return params.stepBack() ? PACED_MARGIN_STEP_BACK : PACED_MARGIN;
    }

    /** Never back beyond the open segment, into a wall, over an edge, or next to a setback cell. */
    private boolean stepBackSafe(double sp, double to) {
        if (to < walkFrom || sp > walkTo || !io.onGround() || io.y() > artY + TALL) return false;
        for (Entry e : extrasInFlight) if (e.solid && Math.abs(e.cell.lateral()) < 1.5
            && e.s >= to - 1 && e.s <= sp + 1) return false;
        // Include the braking step and the box at both ends; the floor must be loaded throughout.
        for (int row = (int) Math.floor(to - 0.5); row <= (int) Math.ceil(sp + 0.5); row++) {
            Pos p = lineCell(row);
            Entry e = byCell.get(key(p.x(), p.z()));
            if (e != null && e.solid && e.st == State.PENDING) return false;
            if (walledSides(p.x(), p.z()) >= AIR_MAX_WALLS) return false;
            if (!io.isLoaded(p) || !io.isLoaded(p.below()) || !io.isLoaded(p.above())
                || !io.blocksPlacement(p.below()) || io.hasFluid(p) || !io.standAllowed(p)
                || io.edgeHazard(p) != null || io.collisionTop(io.block(p)) > TALL
                || io.collisionTop(io.block(p.above())) > 0) return false;
        }
        return true;
    }

    /** A solid cell of this lane or an extra would block the correction's return path. */
    private boolean blocksStepBack(Entry e) {
        return !stepBackCells.isEmpty() && e.solid && Math.abs(e.cell.lateral()) < 1.5
            && e.s >= stepBackTo - 1 && e.s <= stepBackFrom + 1;
    }

    /**
     * {@link Io#stepBackClearPath}: the farthest back (along the lane) the box may still go from {@code sp}. A step back
     * keys back to {@link #stepBackTo} and then slides at most what its top speed slides ({@link LaneWalk#accel}: the
     * speed a held key settles at); any other backward move ({@code ds} the last tick's move) slides on by
     * {@link LaneWalk#slide}. NaN: not moving back.
     */
    private double backReachTo(double sp, double ds, boolean backing) {
        if (backing) {
            double top = LaneWalk.accel(topSpeed) / (1 - LaneWalk.FRICTION);
            return Math.min(sp, stepBackTo) - LaneWalk.slide(top);
        }
        return ds < 0 ? sp - LaneWalk.slide(-ds) : Double.NaN;
    }

    /**
     * {@link Io#stepBackClearPath}: {@code e}'s cell lies where the box may still go back to this tick ({@link #backTo}).
     * A corridor cell counts while its near edge is within {@link #EDGE} of the box anywhere on the way back (the
     * forward walk's rule at the farthest point back); any other cell while it touches the box grown by
     * {@link LaneWalk#SAFE_INFLATE} anywhere on the way back (the side-cell rule).
     */
    private boolean inBackPath(Entry e) {
        if (Double.isNaN(backTo) || backAt != now) return false;
        double sp = geo.s(io.x(), io.z());
        if (e.corridor && !e.extra) return e.s + 0.5 > backTo - EDGE && e.s - 0.5 < sp + EDGE;
        double back = Math.max(0, sp - backTo);
        double[] f = geo.forward();
        double[] box = Aim.playerBox(io.x(), io.y(), io.z(), LaneWalk.SAFE_INFLATE);
        double bx = -f[0] * back;
        double bz = -f[1] * back;
        double[] swept = {box[0] + Math.min(0, bx), box[1], box[2] + Math.min(0, bz), box[3] + Math.max(0, bx), box[4],
            box[5] + Math.max(0, bz)};
        Pos p = e.p.pos();
        return Aim.cellOverlaps(p.x(), p.y(), p.z(), swept);
    }

    /** {@link Io#stepBackClearPath}: a block clicked on the way back is not shown yet ({@link #inBackPath}). */
    private boolean backPathInFlight() {
        for (Entry e : entries) if (e.st == State.PENDING && notShown(e) && inBackPath(e)) return true;
        for (Entry e : extrasInFlight) if (notShown(e) && inBackPath(e)) return true;
        return false;
    }

    /**
     * {@link Io#forwardClearPath}: how far forward the box may still go before a click sent now is answered: from the
     * last tick's forward move {@code v}, the keys held for the click latency (the pacer's highest recent one, else
     * {@code placeConfirmTicks}) plus {@link #FWD_EXTRA_TICKS} ticks (each move the last one × friction + the keys'
     * {@link LaneWalk#accel}), at most {@link #FWD_SWEEP_MAX}. Standing still counts too: the walk may start at once.
     */
    private double forwardReach(double v) {
        int ticks = fwdTicks();
        double a = LaneWalk.accel(topSpeed);
        double u = Math.max(0, v);
        double d = 0;
        for (int k = 0; k < ticks && d < FWD_SWEEP_MAX; k++) {
            u = u * LaneWalk.FRICTION + a;
            d += u;
        }
        return Math.min(FWD_SWEEP_MAX, d);
    }

    /** {@link #forwardReach}: the click latency in ticks (the pacer's highest recent one, else placeConfirmTicks) + 2. */
    private int fwdTicks() {
        int latency = io.pacer().maxLatency();
        return (latency > 0 ? latency : Math.max(1, params.placeConfirmTicks())) + FWD_EXTRA_TICKS;
    }

    /**
     * The player's box (grown by {@code inflate}) moved by the world vector ({@code wx}, {@code wz}), swept (the box
     * over the whole move) or not (the box at its end).
     */
    private double[] boxMoved(double wx, double wz, double inflate, boolean swept) {
        double y = walkOver ? Math.min(io.y(), artY) : io.y();
        double[] box = Aim.playerBox(io.x(), y, io.z(), inflate);
        if (!swept) return new double[]{box[0] + wx, box[1], box[2] + wz, box[3] + wx, box[4], box[5] + wz};
        return new double[]{box[0] + Math.min(0, wx), box[1], box[2] + Math.min(0, wz), box[3] + Math.max(0, wx), box[4],
            box[5] + Math.max(0, wz)};
    }

    /** A cell beside the walking line (not the corridor, {@code |lateral| <= 1}) of this lane. */
    private static boolean sideCell(Entry e) {
        return !e.corridor && !e.extra && Math.abs(e.cell.lateral()) < 1.5;
    }

    /**
     * {@link Io#forwardClearPath}: {@code e} is a side cell ({@link #sideCell}) the box, grown by {@link #FWD_INFLATE}
     * and swept by {@link #fwdSweep} along the walk and {@link #fwdLat} across it, touches: a click now could be
     * answered after the box has gone into it. Within {@link #FWD_INFLATE} of the steering deadband
     * ({@link #STEER_DEADBAND}) or beyond it the sweep also reaches one steering tap towards the line and past it: with
     * the walk's keys up, steering back is full strafe (Soul Speed: 0.49 from standing), which overshoots to the
     * other side column (LaneForwardRaceTest: the side-to-side ride between the stops).
     */
    private boolean inForwardPath(Entry e) {
        if (Double.isNaN(fwdSweep) || now - fwdAt > 1 || !sideCell(e)) return false;
        double[] f = geo.forward();
        boolean z = geo.alongZ();
        double ahead = fwdSweep * fwdDir;
        double[] box = boxMoved(f[0] * ahead + (z ? fwdLat : 0), f[1] * ahead + (z ? 0 : fwdLat), FWD_INFLATE, true);
        double lat = geo.lateral(io.x(), io.z());
        if (Math.abs(lat) > STEER_DEADBAND - FWD_INFLATE) {
            double tap = LaneWalk.pressedStop(0, LaneWalk.accel(topSpeed));
            int i = z ? 0 : 2; // the lateral world axis
            if (lat > 0) box[i] = Math.min(box[i], box[i] - tap);
            else box[i + 3] = Math.max(box[i + 3], box[i + 3] + tap);
        }
        Pos p = e.p.pos();
        return Aim.cellOverlaps(p.x(), p.y(), p.z(), box);
    }

    /**
     * {@link Io#forwardClearPath}: this tick's {@code keys} (at {@code yaw}) after the last tick's move ({@code mvx},
     * {@code mvz}) would take the box into a side block in flight ({@link #forwardPathInFlight}): the move is the last
     * one × friction plus the keys' {@link LaneWalk#accel} in their direction.
     */
    private boolean keyedInto(float yaw, int[] keys, double mvx, double mvz) {
        double wx = mvx * LaneWalk.FRICTION;
        double wz = mvz * LaneWalk.FRICTION;
        if (keys[0] != 0 || keys[1] != 0) {
            double[] w = Aim.keysToWorld(yaw, keys[0], keys[1]);
            double l = Math.hypot(w[0], w[1]);
            double a = LaneWalk.accel(topSpeed);
            wx += w[0] / l * a;
            wz += w[1] / l * a;
        }
        return forwardPathInFlight(wx, wz);
    }

    /**
     * {@link Io#forwardClearPath}: a move by the world vector ({@code wx}, {@code wz}) would take the box (grown by
     * {@link #FWD_GUARD_MARGIN} at its end: the move is a prediction) into a cell of this lane (or an extra) whose
     * block is clicked and not shown yet, or showed only this tick (its update 1-56 ms ahead of the move: the server
     * may still take the move as one into it), and the box is not in it now.
     */
    private boolean forwardPathInFlight(double wx, double wz) {
        double[] next = boxMoved(wx, wz, FWD_GUARD_MARGIN, false);
        double[] box = boxMoved(0, 0, 0, false);
        for (Entry e : entries) {
            boolean race = (e.st == State.PENDING && notShown(e)) || (e.st == State.DONE && e.doneAt == now);
            if (race && entering(e, box, next)) return true;
        }
        for (Entry e : extrasInFlight) if (notShown(e) && entering(e, box, next)) return true;
        return false;
    }

    private static boolean entering(Entry e, double[] box, double[] next) {
        Pos p = e.p.pos();
        return Aim.cellOverlaps(p.x(), p.y(), p.z(), next) && !Aim.cellOverlaps(p.x(), p.y(), p.z(), box);
    }

    /** A block with collision is planned in {@code e}'s cell and the proxy shows none there yet. */
    private boolean notShown(Entry e) {
        Pos p = e.p.pos();
        return io.collisionTop(e.p.block()) > 0 && io.collisionTop(io.block(p)) <= 0;
    }

    /**
     * Paced walk: how far the walk may still go (along the lane, in direction {@code dir}, from {@code sp}) before
     * {@code e} leaves reach from an eye at height {@code eyeY} and lateral offset {@code latEye}: airplace, the cell
     * centre within {@code airPlaceRange}; LOOK, the nearest point of its support within the LOOK reach; both less a
     * walking step ({@link #PACED_STEP}, with {@code reachStepMargin}) and 0.05. Negative: already out of reach.
     */
    private double deadline(Entry e, double sp, double eyeY, double latEye, int dir) {
        double margin = (params.reachStepMargin() ? PACED_STEP : 0) + 0.05;
        double dLat = Math.abs(e.cell.lateral() - latEye);
        double w;
        if (methodNow(e) == PlaceMethod.AIRPLACE) {
            double r = params.airPlaceRange() - margin;
            double h = eyeY - (e.p.pos().y() + 0.5);
            double w2 = r * r - h * h - dLat * dLat;
            w = w2 > 0 ? Math.sqrt(w2) : 0;
        } else {
            double r = Math.min(params.reach(), SERVER_RANGE - margin);
            double h = eyeY - e.support.y() - 1.0; // the support's top face
            double side = Math.max(0, dLat - 0.5);
            double w2 = r * r - h * h - side * side;
            w = w2 > 0 ? Math.sqrt(w2) + 0.5 : 0;
        }
        return (e.s - sp) * dir + w;
    }

    /**
     * Paced walk: the highest speed (blocks per tick along the lane) at which every queued cell is still clicked
     * before it leaves reach, one click per pacer interval in the pipeline's order (held item first, then the hotbar,
     * then the main inventory; deadline order within): min over the queue of (deadline − {@link #PACED_MARGIN}) /
     * ((k + 1) · interval + 1). 0 (stop) when a candidate that cannot be clicked now (its stack used up, a retry not due)
     * is within {@link #PACED_STOP_STACK} of leaving reach, or a cell in flight or waiting to retry is within
     * {@link #PACED_STOP_WAITING} of it. Infinite when nothing binds.
     */
    private double pacedCap(List<Entry> cands, List<Entry> aimable, double sp, double eyeY, double latEye, int dir) {
        double cap = Double.POSITIVE_INFINITY;
        capReason = StandingTicks.Hold.OTHER;
        capCell = null;
        List<Entry> queue = new ArrayList<>(aimable);
        String held = env.heldItem();
        queue.sort(Comparator.comparingInt(e -> {
            String item = env.item(e);
            return item.equals(held) ? 0 : env.hotbarSlot(item) >= 0 ? 1 : 2;
        }));
        int interval = Math.max(1, io.pacer().interval());
        // fast placement: cells of the held item that may share a tick go at the window's rate (moreClicks)
        double rate = interval == 1 && airOk && params.pacing().multiClick() && io.pacer().fastPlacementOn() ? Math.min(params.pacing().clicksPerTick(),
            subTickOn() ? params.pacing().windowMax() * 50.0 / params.pacing().windowMillis()
                : (double) params.pacing().windowMax() / io.pacer().clickWindow().ticks()) : 1.0;
        double due = 0; // ticks until queue cell k is clicked
        for (int k = 0; k < queue.size(); k++) {
            Entry q = queue.get(k);
            boolean fast = rate > 1 && multiClickable(q) && methodNow(q) == PlaceMethod.AIRPLACE
                && env.item(q).equals(held);
            due += fast ? 1.0 / rate : interval;
            double dl = deadline(q, sp, eyeY, latEye, dir);
            double cellCap = Math.max(0, (dl - pacedMargin()) / (due + 1));
            if (cellCap < cap) {
                capCell = q;
                capReason = methodNow(q) == PlaceMethod.LOOK ? StandingTicks.Hold.LOOK
                    : env.hotbarSlot(env.item(q)) < 0 ? StandingTicks.Hold.MOVE
                    : env.inventoryBusy() ? StandingTicks.Hold.BUSY : StandingTicks.Hold.QUEUE;
            }
            cap = Math.min(cap, cellCap);
        }
        Set<Entry> queued = new HashSet<>(aimable);
        for (Entry e : cands) {
            if (!queued.contains(e) && deadline(e, sp, eyeY, latEye, dir) < PACED_STOP_STACK) {
                capCell = e;
                capReason = now < e.retryAt ? StandingTicks.Hold.RETRY
                    : io.fencedSlot() >= 0 && env.hotbarSlot(env.item(e)) < 0 ? StandingTicks.Hold.MOVE
                    : usedUp(e) ? StandingTicks.Hold.STACK
                    : methodNow(e) == PlaceMethod.LOOK ? StandingTicks.Hold.LOOK : StandingTicks.Hold.QUEUE;
                return 0;
            }
        }
        for (Entry e : entries) {
            boolean waits = e.st == State.PENDING || (e.st == State.TODO && !e.left && now < e.retryAt);
            if (!waits) continue;
            double dl = deadline(e, sp, eyeY, latEye, dir);
            // with step back on, a cell already out of reach is served by a step back: standing still cannot bring it
            // back into reach (live: a refused cell held the walk for its 20-tick retry wait, then needed a step back)
            if (dl < PACED_STOP_WAITING && dl > (params.stepBack() ? 0.0 : -1.0)) {
                capCell = e;
                capReason = e.st == State.PENDING ? StandingTicks.Hold.ACK : StandingTicks.Hold.RETRY;
                return 0; // well out of reach already: standing does not help
            }
        }
        return cap;
    }

    /**
     * Walking over a walled line on top of one of its full blocks, and the next line cell (walking {@code dir}) is a
     * dip: a floor cell with the line's next cell a full block again (a 1-wide slot between the two, a pocket if its
     * sides are full blocks too). Returns how far the bot's centre is past the middle of the block it stands on along
     * the walk (beyond 0.5 it overhangs the dip, held up by the block's edge up to 0.8); NaN otherwise. The walk jumps
     * across the dip, or stays on the block away from its edge, rather than drop in and jump out again.
     */
    private double pastMiddleBeforeDip(double sp, int dir) {
        long i = Math.round(sp);
        Pos c = lineCell(i);
        if (!io.isLoaded(c)) return Double.NaN;
        if (!tall(c)) { // the centre overhangs the cell ahead of the block (or behind it, walking on)
            i -= dir;
            c = lineCell(i);
            if (!io.isLoaded(c) || !tall(c)) return Double.NaN;
        }
        Pos next = lineCell(i + dir);
        Pos after = lineCell(i + 2L * dir);
        if (!io.isLoaded(next) || tall(next) || blockToLandOn(next) || !blockToLandOn(after)) return Double.NaN;
        return (sp - i) * dir;
    }

    /**
     * The floor cell the bot stands in on a walled line is narrow ({@link #AIR_MAX_WALLS} or more full-block sides: a
     * pocket or a 1-wide slot) because of the line itself: a full block of the line right before or after it (the walls
     * the walk over the line meets). A cell narrow only by blocks beside the line is the floor walk's as on any lane.
     */
    private boolean narrowByTheLine(double sp, Pos feet) {
        if (walledSides(feet.x(), feet.z()) < AIR_MAX_WALLS) return false;
        long i = Math.round(sp);
        for (long j : new long[]{i - 1, i + 1}) {
            Pos c = lineCell(j);
            if (io.isLoaded(c) && tall(c)) return true;
        }
        return false;
    }

    /**
     * Standing in a narrow cell of a walled line ({@link #narrowByTheLine}) where the walk would rest: the way along the
     * line to the nearest line cell it may rest in (on top of a full block: it walks into it and jumps on; an open
     * floor cell), within {@link #ESCAPE_CELLS} cells inside the lane, ahead first at the same distance; ahead if none;
     * 0 at the lane's end.
     */
    private int outOfNarrowCell(double sp, int dir) {
        long i = Math.round(sp);
        int n = Math.min((int) Math.round(geo.length()), walkTo);
        // a raised column on the way is a wall the box neither passes under nor climbs: nothing beyond it is reached
        boolean[] walled = new boolean[2];
        for (int k = 1; k <= ESCAPE_CELLS; k++) {
            for (int d : new int[]{dir, -dir}) {
                long j = i + (long) k * d;
                int w = d == dir ? 0 : 1;
                if (walled[w] || j < walkFrom || j > n) continue;
                Pos c = lineCell(j);
                if (io.isLoaded(c) && raisedAt(c)) {
                    walled[w] = true;
                    continue;
                }
                if (standable(c)) return d;
            }
        }
        if (walled[0]) return walled[1] ? 0 : -dir;
        return i + dir < walkFrom || i + dir > n ? 0 : dir;
    }

    /**
     * A line cell the walk over a walled line may rest in: on top of a full block, or an open floor cell; never a raised
     * column (a wall at head height nobody stands on, class javadoc "Raised cells").
     */
    private boolean standable(Pos c) {
        return io.isLoaded(c) && !raisedAt(c) && (tall(c) || walledSides(c.x(), c.z()) < AIR_MAX_WALLS);
    }

    /**
     * Over a walled line: line cell {@code c} holds a full block to land on, in the world or, with the raised level, of
     * this lane sent and not confirmed yet (the line cell ahead goes up as the walk comes to it; a jump decided on the
     * world alone walked off the edge into the dip, local sim 2026-11-14); never a raised column.
     */
    private boolean blockToLandOn(Pos c) {
        if (!io.isLoaded(c)) return false;
        if (levels) {
            if (raisedAt(c)) return false;
            Entry e = byCell.get(key(c.x(), c.z()));
            if (e != null && e.solid && !e.raised && !e.trap && e.st == State.PENDING) return true;
        }
        return tall(c);
    }

    /**
     * Raised level, walking back: the nearest line cell behind {@code sp} (within the walk back of a lane end) whose
     * column holds a raised block, as the farthest s the box can go back to; NaN if none (class javadoc "Raised cells").
     */
    private double raisedStopBehind(double sp) {
        if (!levels) return Double.NaN;
        long i = Math.round(sp);
        for (long j = i - 1; j >= Math.max(0, i - 3); j--) {
            Pos c = lineCell(j);
            if (io.isLoaded(c) && raisedAt(c)) return j + 0.5 + Aim.PLAYER_HALF_WIDTH;
        }
        return Double.NaN;
    }

    /** The walking line's cell {@code i} cells from the lane start (s = i). */
    private Pos lineCell(long i) {
        double[] f = geo.forward();
        return lane.from().offset((int) (f[0] * i), 0, (int) (f[1] * i));
    }

    /**
     * True while an unplaced cell that full block {@code e} would hide from the line is still to be placed by the walk
     * (walking {@code dir}); cells already left to stand spots do not count.
     */
    private boolean waiting(Entry e, int dir) {
        return waitingOn(e, dir, null);
    }

    /** {@link #waiting}; with {@code each} non-null, every cell {@code e} waits for is passed to it. */
    private boolean waitingOn(Entry e, int dir, java.util.function.@Nullable Consumer<Entry> each) {
        boolean any = false;
        for (int r = e.row - 3; r <= e.row + 3; r++) {
            List<Entry> row = byRow.get(r);
            if (row == null) continue;
            for (Entry y : row) {
                if (y == e || (y.st != State.TODO && y.st != State.PENDING)) continue;
                // left to stand spots, or hidden from two places on the line: it does not hold the others back
                if (y.st == State.TODO && (y.left || y.hiddenMisses >= 3)) continue;
                if (methodNow(y) == PlaceMethod.AIRPLACE) continue; // airplace needs no line of sight
                if (!LaneWalk.shadows(e.cell, y.cell, dir)) continue;
                if (each == null) return true;
                any = true;
                each.accept(y);
            }
        }
        return any;
    }

    /**
     * The box the walk's placements keep clear of, grown by {@link LaneWalk#SAFE_INFLATE}: over a walled line from the
     * floor up even on top of a block; with the raised level from at most one block above the lane's height, so a
     * raised cell never goes up under the box in a jump either (it would land on the raised top).
     */
    private double[] safeBox(double px, double py, double pz) {
        double y = walkOver ? Math.min(py, artY) : levels ? Math.min(py, artY + 1) : py;
        return Aim.playerBox(px, y, pz, LaneWalk.SAFE_INFLATE);
    }

    private boolean placeableNow(Entry e, double sp, double[] safe, int dir, boolean onTop, boolean sight) {
        // an inner full block goes up once passed: the walls stand behind the player, the line ahead stays open
        // (only while a cell that needs line of sight is left)
        boolean order = !e.solid || e.corridor || Math.abs(e.cell.column()) >= outerColumn || (e.s - sp) * dir < 0
            || !sight;
        // standing on a block at the lane end: its box is above the art layer (a raised cell is at its height)
        if (onTop && reverse) {
            return order && !(e.raised && Aim.cellOverlaps(e.p.pos().x(), e.p.pos().y(), e.p.pos().z(), safe));
        }
        if (e.corridor) {
            // over a walled line, on top of one of its blocks: a line cell ahead goes up too, its box clear of the bot's
            // (live local run: a line cell walled in on three sides was never passed, so never placed); never a raised
            // cell: it would wall the walk at head height
            if (walkOver && onTop && !e.raised && !Aim.cellOverlaps(e.p.pos().x(), e.p.pos().y(), e.p.pos().z(),
                Aim.playerBox(io.x(), io.y(), io.z(), 0))) {
                return true;
            }
            return reverse ? e.s - 0.5 >= sp + EDGE : e.s + 0.5 <= sp - EDGE;
        }
        Pos pos = e.p.pos();
        if (Aim.cellOverlaps(pos.x(), pos.y(), pos.z(), safe)) return false;
        return order;
    }

    /** How {@code e} is placed this tick: its method, or LOOK while airplace is not available. */
    private PlaceMethod methodNow(Entry e) {
        return e.method == PlaceMethod.AIRPLACE && airOk ? PlaceMethod.AIRPLACE : PlaceMethod.LOOK;
    }

    /** A cell placed by LOOK is still left for the walk (it needs line of sight). */
    private boolean lookLeft() {
        for (Entry e : entries) {
            if (e.st == State.TODO && !e.left && methodNow(e) == PlaceMethod.LOOK) return true;
        }
        return false;
    }

    /** In reach from the eye now: airplace eye → cell centre within {@code airReach}, LOOK the support within {@code reach}. */
    private boolean inReachNow(Entry e, double reach, double airReach) {
        if (methodNow(e) == PlaceMethod.AIRPLACE) return centreWithin(io.x(), io.eyeY(), io.z(), e, airReach);
        return io.inReach(e.support, reach);
    }

    /** The centre of {@code e}'s cell is within {@code range} of an eye at (ex, ey, ez). */
    private static boolean centreWithin(double ex, double ey, double ez, Entry e, double range) {
        Pos c = e.p.pos();
        return Aim.distSq(ex, ey, ez, c.x() + 0.5, c.y() + 0.5, c.z() + 0.5) <= range * range;
    }

    /** {@link #stillReach} for airplace. */
    private double stillAirReach() {
        return params.airPlaceRange() - 0.05;
    }

    /** Two line-of-sight misses close to here: not aimed at again until the bot has moved on. */
    private static boolean losBlocked(Entry e, double sp) {
        return e.losMisses >= 2 && Math.abs(sp - e.losMissS) < 0.5;
    }

    /** The walk ends: what it has not placed goes to stand spots (or is skipped without a stand area). */
    private void leaveRemaining(String why) {
        stepBackCells.clear();
        int n = 0;
        for (Entry e : entries) {
            if (e.st == State.TODO && !e.left) {
                e.left = true;
                n++;
            }
        }
        if (n > 0) io.log("Lane " + lane.index() + ": " + n + " cells " + why);
        phase = Phase.SETTLE;
    }

    // =====================================================================================
    // In-reach greedy (class javadoc): cells placed before their lane's walk reaches them
    // =====================================================================================

    /**
     * In the walk: {@code plan} has no click for this lane's cells this tick (none clickable, none in reach), so the
     * extras that can go now ({@link #extrasInReach}) get it, in deadline order; the plan's aim for the next tick stays.
     * This lane's cells always come first: the walk's speed cap and stops count on their clicks only.
     */
    private PlacePipeline.Plan<Entry, S> withExtra(PlacePipeline.Plan<Entry, S> plan, double px, double pz,
                                                   double[] safe, Pos feet, double airReach, double sp, double eyeY,
                                                   double latEye, int dir) {
        List<Entry> ex = extrasInReach(px, eyeY, pz, airReach, safe, feet, null, false);
        if (ex.isEmpty()) return plan;
        ex.sort(Comparator.comparingDouble(e -> deadline(e, sp, eyeY, latEye, dir)));
        ex = clickable(ex);
        if (ex.isEmpty()) return plan;
        PlacePipeline.Plan<Entry, S> p = pipeline.plan(now, ex, env);
        if (p.click() == null) return plan;
        return new PlacePipeline.Plan<>(p.click(), null, p.select(), plan.next(), plan.nextShot(), null, true);
    }

    /**
     * While Baritone walks for this run (to the lane start, back to where a park stopped the walk): this lane's cells
     * that may go early ({@link Entry#early}) and the extras ({@link #extrasInReach}), airplaced in reach of the eye
     * (less the last step, as in the walk), clear of the bot's box grown by 0.3, from the hotbar (no inventory move while
     * walking), a full block never within one cell of the feet or of the path Baritone still walks (none while that path
     * is unknown); the cell closest to leaving reach on the way to the goal ({@code gx, gz}) first. Same pacer, stack
     * guard and airplace guard as the walk; the click goes out only while {@link Io#clickWhileWalking}.
     */
    private void placeWhileWalking(double gx, double gz) {
        if (walkRecovery != null && walkRecovery.active()) return;
        if (!greedy()) return;
        double px = io.x();
        double py = io.y();
        double pz = io.z();
        double step = Double.isNaN(walkPrevX) ? 0.3 : Math.min(0.5, Math.sqrt((px - walkPrevX) * (px - walkPrevX)
            + (py - walkPrevY) * (py - walkPrevY) + (pz - walkPrevZ) * (pz - walkPrevZ)));
        walkPrevX = px;
        walkPrevY = py;
        walkPrevZ = pz;
        updateEntries();
        airOk = io.airPlaceReady();
        if (!airOk || !io.clickWhileWalking()) return;
        double airReach = params.airPlaceRange() - (params.reachStepMargin() ? step : 0) - 0.05;
        double ey = io.eyeY();
        double[] safe = Aim.playerBox(px, levels ? Math.min(py, artY + 1) : py, pz, LaneWalk.SAFE_INFLATE);
        Pos feet = feet();
        List<Pos> path = io.walkPath();
        List<Entry> cands = new ArrayList<>();
        for (Entry e : entries) {
            if (e.st != State.TODO || !e.early || e.left || !centreWithin(px, ey, pz, e, airReach)) continue;
            Pos pos = e.p.pos();
            if (Aim.cellOverlaps(pos.x(), pos.y(), pos.z(), safe)) continue;
            if (e.solid && !clearOfWalk(pos, feet, path, true)) {
                walkNearPath.add(e);
                continue;
            }
            if (!inHotbar(ItemIds.ns(e.p.itemId()))) {
                walkNoHotbar.add(e);
                continue;
            }
            cands.add(e);
        }
        cands.addAll(extrasInReach(px, ey, pz, airReach, safe, feet, path, true));
        if (cands.isEmpty()) return;
        double gd = Math.hypot(gx - px, gz - pz);
        double ux = gd < 0.5 ? 0 : (gx - px) / gd;
        double uz = gd < 0.5 ? 0 : (gz - pz) / gd;
        cands.sort(Comparator.comparingDouble(e -> walkDeadline(e, px, ey, pz, ux, uz, airReach)));
        cands = clickable(cands);
        if (cands.isEmpty()) return;
        env.beginWalking(stillReach());
        PlacePipeline.Plan<Entry, S> plan = pipeline.plan(now, cands, env);
        if (plan.click() != null) pipeline.execute(now, plan, env);
    }

    /**
     * Extras around the eye (ex, ey, ez) that can go now: TODO, the cell centre within {@code airReach}, clear of
     * {@code safe}, the item in the hotbar (no inventory move for them) and enough of it carried for every cell still
     * owed ({@link #owed}: a later lane never takes the items of this one or of an earlier one). A full block also keeps
     * the way open ({@link #clearOfWalk}; {@code walking}: Baritone walks {@code path}). An extra the world shows placed
     * or taken meanwhile is settled here.
     */
    private List<Entry> extrasInReach(double ex, double ey, double ez, double airReach, double[] safe, Pos feet,
                                      @Nullable List<Pos> path, boolean walking) {
        List<Entry> out = new ArrayList<>();
        if (extras.isEmpty()) return out;
        int r = (int) Math.ceil(airReach) + 1;
        int fx = (int) Math.floor(ex);
        int fz = (int) Math.floor(ez);
        Map<String, Boolean> covered = new HashMap<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Entry e = extraByCell.get(key(fx + dx, fz + dz));
                if (e == null || e.st != State.TODO || !centreWithin(ex, ey, ez, e, airReach)) continue;
                Pos pos = e.p.pos();
                if (!io.isLoaded(pos)) continue;
                if (io.matches(e.p.block(), io.block(pos))) {
                    tracker.placed(e, now); // a click of ours given up earlier may have landed late
                    extraDone(e);
                    continue;
                }
                // (a raised cell floats: no support below it, airplace needs none)
                if (io.blocksPlacement(pos) || (!e.raised && !io.blocksPlacement(pos.below()))) {
                    tracker.forget(e);
                    dropExtra(e);
                    continue;
                }
                if (Aim.cellOverlaps(pos.x(), pos.y(), pos.z(), safe)) continue;
                if (e.solid && !clearOfWalk(pos, feet, path, walking)) {
                    if (walking) walkNearPath.add(e);
                    continue;
                }
                if (blocksStepBack(e) || inBackPath(e) || heldBack(e)) continue;
                String item = ItemIds.ns(e.p.itemId());
                if (!inHotbar(item)) {
                    if (walking) walkNoHotbar.add(e);
                    continue;
                }
                if (!covered.computeIfAbsent(item, k -> io.count(k) >= owed.getOrDefault(k, 0))) continue;
                out.add(e);
            }
        }
        return out;
    }

    /**
     * A full block at {@code pos} leaves the bot's way open: not within one cell (x, z) of the feet, and while Baritone
     * walks ({@code walking}) not within one cell of a cell of the path it still walks ({@code path}; unknown: never).
     */
    static boolean clearOfWalk(Pos pos, Pos feet, @Nullable List<Pos> path, boolean walking) {
        if (chebyshev(pos, feet) <= 1) return false;
        if (!walking) return true;
        if (path == null) return false;
        for (Pos c : path) if (chebyshev(pos, c) <= 1) return false;
        return true;
    }

    /** {@code item} (namespaced) is held or elsewhere in the hotbar. */
    private boolean inHotbar(String item) {
        return item.equals(io.heldItem()) || io.hotbarSlot(item) >= 0;
    }

    /**
     * While Baritone walks: how far the bot may still go towards its goal (unit {@code ux, uz}) before {@code e} leaves
     * the airplace reach {@code r} of the eye (ex, ey, ez); with no direction (at the goal), what is left of the reach.
     */
    static double walkDeadline(Entry e, double ex, double ey, double ez, double ux, double uz, double r) {
        Pos c = e.p.pos();
        double dx = c.x() + 0.5 - ex;
        double dz = c.z() + 0.5 - ez;
        double h = ey - (c.y() + 0.5);
        if (ux == 0 && uz == 0) return r - Math.sqrt(dx * dx + dz * dz + h * h);
        double along = dx * ux + dz * uz;
        double lat = dx * uz - dz * ux;
        double w2 = r * r - h * h - lat * lat;
        return along + (w2 > 0 ? Math.sqrt(w2) : 0);
    }

    // =====================================================================================
    // SETTLE: wait for pending placements, then stand spots for the rest
    // =====================================================================================

    private TaskStatus settle() {
        updateEntries();
        if (anyPending()) {
            io.holdStill();
            return TaskStatus.RUNNING;
        }
        boolean todo = false;
        for (Entry e : entries) if (e.st == State.TODO) todo = true;
        if (!todo) return finish();
        if (params.standArea() == null) {
            for (Entry e : entries) if (e.st == State.TODO) skip(e, "not reachable from the lane");
            return finish();
        }
        int n = 0;
        for (Entry e : entries) if (e.st == State.TODO) n++;
        io.log("Lane " + lane.index() + ": " + n + " cells to place from stand spots"
            + (leftBehind > 0 ? " (" + leftBehind + " passed out of reach by the walk)" : ""));
        return startSpots();
    }

    // =====================================================================================
    // SPOT: place from stand spots
    // =====================================================================================

    private TaskStatus spot() {
        updateEntries();
        airOk = params.mode() == PlaceMode.AIRPLACE && io.airPlaceReady();
        List<Entry> todo = new ArrayList<>();
        for (Entry e : entries) if (e.st == State.TODO && (tail == null || tail.contains(e))) todo.add(e);
        if (todo.isEmpty() && spotStep != SpotStep.BREAK) {
            if (spotStep == SpotStep.WALK) io.stopWalking();
            spotStep = SpotStep.PICK;
            if (anyPending()) {
                io.holdStill();
                return TaskStatus.RUNNING;
            }
            if (tail != null) {
                // the start tail is up (or given up, to VERIFY): back to the lane start, then the walk
                tail = null;
                phase = Phase.APPROACH;
                lastActivity = now;
                return TaskStatus.RUNNING;
            }
            return finish();
        }
        return switch (spotStep) {
            case PICK -> spotPick(todo);
            case WALK -> spotWalk();
            case CENTER -> spotCenter();
            case PLACE -> spotPlace(todo);
            case BREAK -> spotBreak();
        };
    }

    private TaskStatus spotPick(List<Entry> todo) {
        io.holdStill();
        Pos here = feet();
        // try from where the bot stands first (unless it may not stand there: a no-go zone, next to a setback cell)
        if (!here.equals(triedHere) && io.standAllowed(here) && !spotCandidates(todo).isEmpty()) {
            startPlacing(null, null);
            return TaskStatus.RUNNING;
        }
        if (anyPending()) return TaskStatus.RUNNING; // a miss may come back to be placed from here
        judgeLastPick();
        if (fruitless >= MAX_FRUITLESS_SPOTS && io.dripstoneBreakThrough()) {
            for (Entry e : todo) if (startBreakIn(e, "stand spots made no progress")) {
                fruitless = 0;
                return TaskStatus.RUNNING;
            }
        }
        if (fruitless >= MAX_FRUITLESS_SPOTS) {
            io.log("Lane " + lane.index() + ": " + fruitless + " stand spots in a row placed nothing (the bot at " + here
                + "); skipping the " + todo.size() + " cells left (VERIFY re-plans them)");
            for (Entry e : todo) skip(e, "no progress from " + fruitless + " stand spots in a row");
            fruitless = 0;
            return TaskStatus.RUNNING;
        }
        double px = io.x();
        double pz = io.z();
        // a cell that waits for another (a corridor's far end first, a cell it would shut in) is no target yet, unless
        // every cell left waits
        List<Entry> pickable = new ArrayList<>(todo.size());
        for (Entry e : todo) if (!waitsFor(e) && !sealsIn(e)) pickable.add(e);
        if (pickable.isEmpty()) {
            // cells that only shut in each other (a pocket of dripstone cells, each the other's way in): the one
            // farthest from the bot goes first, so the nearer ones keep their way in from the bot's side. Live
            // 2026-11-23, j0039 [1,15] and [10,16]: 2 and 11 such cells, 0 clicks in 4 repair passes, BLOCKED.
            Entry far = null;
            double farD = -1;
            for (Entry e : todo) {
                if (waitsFor(e)) continue;
                double d = Math.hypot(e.p.pos().x() + 0.5 - px, e.p.pos().z() + 0.5 - pz);
                if (d > farD) {
                    farD = d;
                    far = e;
                }
            }
            if (far != null) {
                if (!far.tieLogged) {
                    far.tieLogged = true;
                    io.log("Lane " + lane.index() + ": every cell left waits for another it would shut in ("
                        + todo.size() + " cells); " + far.p.pos() + ", the farthest from the bot, goes first");
                }
                far.tieBreak = true;
                pickable.add(far);
            }
        }
        if (pickable.isEmpty()) {
            // every cell left would shut in one no stand spot reached (nothing is in flight here): VERIFY places that
            // one first, then these; walking to spots for them would place nothing
            for (Entry e : todo) skip(e, "waits for a cell it would shut in");
            return TaskStatus.RUNNING;
        }
        Entry t = null;
        double best = Double.POSITIVE_INFINITY;
        for (Entry e : pickable) {
            double d = Math.hypot(e.p.pos().x() + 0.5 - px, e.p.pos().z() + 0.5 - pz);
            if (d < best) {
                best = d;
                t = e;
            }
        }
        if (t.spotTries >= MAX_SPOTS) {
            String why = "not reachable from the lane or " + MAX_SPOTS + " stand spots";
            if (startBreakIn(t, why)) return TaskStatus.RUNNING;
            skip(t, why);
            return TaskStatus.RUNNING;
        }
        t.spotTries++;
        Spot s = bestSpot(t);
        if (s == null) {
            String why = "not reachable: no stand spot in reach and line of sight";
            if (startBreakIn(t, why)) return TaskStatus.RUNNING;
            skip(t, why);
            return TaskStatus.RUNNING;
        }
        spotTarget = t;
        spot = s;
        spotStep = SpotStep.WALK;
        spotSince = now;
        spotWalks++;
        picked = true;
        pickedKey = key(s.x(), s.z());
        placedAtPick = spotPlaced;
        spotPicks.merge(pickedKey, 1, Integer::sum);
        return TaskStatus.RUNNING;
    }

    /**
     * Judges the previous pick at the next one: a cell placed since then makes its spot good again and ends a fruitless
     * streak; none adds to the streak ({@link #MAX_FRUITLESS_SPOTS}), and a spot picked {@link #SPOT_REPICKS} times
     * without a placement is not used again for any cell (the same spot re-picked for one cell after another while the
     * bot cannot move or its clicks never land).
     */
    private void judgeLastPick() {
        if (!picked) return;
        picked = false;
        if (spotPlaced > placedAtPick) {
            fruitless = 0;
            spotPicks.remove(pickedKey);
            return;
        }
        fruitless++;
        int n = spotPicks.getOrDefault(pickedKey, 0);
        if (n >= SPOT_REPICKS && deadSpots.add(pickedKey)) {
            io.log("Lane " + lane.index() + ": stand spot " + (int) (pickedKey >> 32) + "," + (int) pickedKey + " picked "
                + n + " times without a placement from it; not used again");
        }
    }

    /** One plan for the repair load; ordinary stand spots retain holes placeable from the permanent approach. */
    private boolean startSealOut() {
        if (!params.sealOut() || params.standArea() == null) return false;
        airOk = params.mode() == PlaceMode.AIRPLACE && io.airPlaceReady();
        updateEntries();
        Map<Pos, Placement> missing = new HashMap<>();
        for (Entry e : entries) if (e.st == State.TODO) missing.put(e.p.pos(), e.p);
        for (Lane later : repairLoad) for (Placement p : later.placements()) {
            if (io.isLoaded(p.pos()) && !io.blocksPlacement(p.pos())) missing.put(p.pos(), p);
        }
        BreakIn.Grid grid = breakInGrid();
        Pos start = breakInStart(grid);
        if (start == null) return false;
        List<BreakIn.Target> targets = missing.values().stream().sorted(Comparator.comparing(Placement::pos,
            Comparator.comparingInt(Pos::x).thenComparingInt(Pos::z)))
            .map(p -> breakInTarget(p.pos(), p.block())).toList();
        SealOut.Plan plan = SealOut.plan(grid, params.standArea(), start, targets,
            new BreakIn.Reach(stillReach() - 0.2, stillAirReach() - 0.2), c -> {
                Placement p = io.artAt(c);
                return p == null || p.block() == null ? null : breakInTarget(c, p.block());
            });
        if (plan == null) return false;
        sealOut = plan;
        phase = Phase.SPOT;
        spotStep = SpotStep.PICK;
        for (SealOut.Step step : plan.steps()) addCorridor(List.of(step.hole()), null);
        int walls = plan.routes().stream().mapToInt(route -> route.walls().size()).sum();
        io.log("VERIFY seal-out: " + plan.steps().size() + " holes in " + plan.groups()
            + " groups, way out through " + walls + " blocks; placing farthest first");
        return true;
    }

    private TaskStatus sealOutTick() {
        SealOut.Plan plan = sealOut;
        updateEntries();
        lastActivity = now;
        if (sealRoute < plan.routes().size()) {
            WallRoute.Route route = plan.routes().get(sealRoute);
            TaskStatus status = route.walls().isEmpty() ? TaskStatus.SUCCESS : io.breakWalls(route.walls(), route.path());
            if (status == TaskStatus.RUNNING) return status;
            addCorridor(route.walls(), null);
            if (status == TaskStatus.FAILED) return sealOutEnd("failed opening the way out: " + io.breakError());
            sealRoute++;
            return TaskStatus.RUNNING;
        }
        if (sealStep >= plan.steps().size()) {
            if (anyPending()) return TaskStatus.RUNNING;
            if (!plan.outside().contains(feetCell())) {
                Pos end = plan.outside().stream().min(Comparator.comparingDouble(c ->
                    Math.hypot(c.x() + 0.5 - io.x(), c.z() + 0.5 - io.z()))).orElseThrow();
                FloorRoutes routes = new FloorRoutes(feet(), end);
                if (!routes.reached(end.x(), end.z())) return sealOutEnd("incomplete: outside became unreachable");
                TaskStatus status = io.walk(routes.legs(end.x(), end.z(), artY),
                    () -> plan.outside().contains(feetCell()), true);
                if (status == TaskStatus.RUNNING) return status;
                if (status == TaskStatus.FAILED) return sealOutEnd("incomplete: cannot finish retreat to outside");
            }
            return sealOutEnd("complete; " + plan.steps().size() + " holes refilled; bot in outside=true");
        }
        SealOut.Step step = plan.steps().get(sealStep);
        Entry target = byCell.get(key(step.hole().x(), step.hole().z()));
        if (target == null || target.st == State.SKIPPED) return sealOutEnd("incomplete: a refill cell is unavailable");
        if (target.st == State.DONE) {
            sealStep++;
            sealWalking = false;
            spotStep = SpotStep.PICK;
            pipeline.reset();
            return TaskStatus.RUNNING;
        }
        if (!sealWalking) {
            Pos st = step.stand();
            FloorRoutes routes = new FloorRoutes(feet(), st);
            if (!routes.reached(st.x(), st.z())) return sealOutEnd("incomplete: retreat stand cell is unreachable");
            double y = standY(st.x(), st.z());
            spot = new Spot(st.x(), y, st.z(), 0, true, routes.legs(st.x(), st.z(), (int) Math.floor(y)));
            spotTarget = target;
            spotStep = SpotStep.WALK;
            spotSince = now;
            sealWalking = true;
        }
        if (spotStep == SpotStep.WALK) return spotWalk();
        if (spotStep == SpotStep.CENTER) return spotCenter();
        if (spotStep == SpotStep.PLACE) return spotPlace(List.of(target));
        return sealOutEnd("incomplete: stand walk or placement made no progress");
    }

    private TaskStatus sealOutEnd(String reason) {
        io.stopWalking();
        io.holdStill();
        io.log("VERIFY seal-out: " + reason);
        sealOut = null;
        sealWalking = false;
        spotStep = SpotStep.PICK;
        triedHere = null;
        return TaskStatus.RUNNING;
    }

    // =====================================================================================
    // BREAK-IN: a cell walled in by blocks the pathfinder can neither walk through nor onto (class javadoc)
    // =====================================================================================

    /**
     * Break-in for {@code t} ({@link Params#breakIn}, VERIFY): plans the corridor ({@link BreakIn}) and starts it; true
     * if it did. Once per cell, at most {@link #MAX_BREAK_INS} per run.
     */
    private boolean startBreakIn(Entry t, String why) {
        if ((!params.breakIn() && !io.dripstoneBreakThrough()) || t.breakInTried || t.extra || t.raised
            || breakIns >= MAX_BREAK_INS || params.standArea() == null) {
            return false;
        }
        t.breakInTried = true;
        BreakIn.Grid grid = breakInGrid();
        Pos start = breakInStart(grid);
        if (start == null) {
            io.log("Lane " + lane.index() + ": no break-in for " + t.p.pos() + ": no floor cell of the platform near the"
                + " bot at " + feet() + " to start from");
            return false;
        }
        int maxWalls = io.dripstoneBreakThrough() ? DripstoneBreakThrough.MAX_WALLS : BREAK_IN_WALLS;
        BreakIn.Plan plan = BreakIn.plan(grid, start, breakInTarget(t.p.pos(), t.p.block()),
            new BreakIn.Reach(stillReach() - 0.2, stillAirReach() - 0.2), maxWalls, BREAK_IN_MARGIN,
            c -> {
                Placement p = io.artAt(c);
                return p == null || p.block() == null ? null : breakInTarget(c, p.block());
            });
        String what = t.p.block() == null ? t.p.itemId() : ItemIds.path(t.p.block().id());
        if (plan == null) {
            io.log("Lane " + lane.index() + ": no break-in for " + t.p.pos() + " (" + what + "; " + why + "): no corridor"
                + " of at most " + maxWalls + " art blocks to a stand cell in reach whose blocks can all be placed"
                + " again from the bot's side");
            return false;
        }
        breakIns++;
        breakIn = plan;
        breakInTarget = t;
        t.breakIn = true;
        spotStep = SpotStep.BREAK;
        spotSince = now;
        lastActivity = now;
        List<String> named = new ArrayList<>();
        for (Pos w : plan.walls()) named.add(ItemIds.path(io.block(w).id()) + " at " + w);
        if (io.dripstoneBreakThrough()) {
            Area a = params.standArea();
            io.log("dripstone break-through for stand spot: breaking " + plan.walls().size() + " block(s) "
                + plan.walls().stream().map(c -> "[X" + (c.x() - a.minX()) + "," + c.y() + ",Z"
                    + (c.z() - a.minZ()) + "]").toList() + "; they are placed again after");
        } else io.log("Lane " + lane.index() + ": break-in for " + t.p.pos() + " (" + what + "; " + why + "): "
            + (named.isEmpty() ? "no block to break" : "breaking " + named.size() + " correct art block(s) "
            + String.join(", ", named)) + " to stand at " + plan.stand() + "; placing it from there, then "
            + (named.isEmpty() ? "nothing else" : "the broken block(s) again from the bot's side, the far end first ("
            + plan.replace() + ")") + "; the bot at " + feet());
        return true;
    }

    /**
     * Where the break-in's way starts: the bot's cell on the platform; off it (on the walkway, at the chests) the
     * platform's floor cell nearest to the bot (Baritone brings it there; the plan's path begins with it); null if none
     * within 8 cells of the platform's edge point nearest the bot.
     */
    private @Nullable Pos breakInStart(BreakIn.Grid grid) {
        Area area = params.standArea();
        Pos f = feetCell();
        if (area.contains(f.x(), f.z()) && grid.passable(f)) return f;
        int cx = Math.max(area.minX(), Math.min(area.maxX(), f.x()));
        int cz = Math.max(area.minZ(), Math.min(area.maxZ(), f.z()));
        for (int r = 0; r <= 8; r++) {
            Pos best = null;
            double bd = Double.POSITIVE_INFINITY;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    Pos c = new Pos(cx + dx, artY, cz + dz);
                    if (!area.contains(c.x(), c.z()) || !grid.passable(c)) continue;
                    double d = Math.hypot(c.x() + 0.5 - io.x(), c.z() + 0.5 - io.z());
                    if (d < bd) {
                        bd = d;
                        best = c;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** How a cell is placed, for {@link BreakIn}: by airplace now, or by LOOK; taller than a step or not. */
    private BreakIn.Target breakInTarget(Pos cell, @Nullable BlockSpec b) {
        boolean solid = b != null && !b.isAir() && io.collisionTop(b) > TALL;
        boolean air = b != null && airOk && TopFaceRules.placeMethod(b, params.mode()) == PlaceMethod.AIRPLACE;
        return new BreakIn.Target(cell, air, solid);
    }

    /** The world at the art level of the stand area, for {@link BreakIn}. */
    private BreakIn.Grid breakInGrid() {
        Area area = params.standArea();
        return new BreakIn.Grid() {
            @Override
            public boolean passable(Pos c) {
                return area.contains(c.x(), c.z()) && wayOutCell(c.x(), c.z(), null);
            }

            @Override
            public boolean floor(Pos c) {
                if (!area.contains(c.x(), c.z())) return false;
                double y = standY(c.x(), c.z());
                return !Double.isNaN(y) && y <= artY + TALL;
            }

            @Override
            public boolean breakable(Pos c) {
                return area.contains(c.x(), c.z()) && io.isLoaded(c) && io.breakableWall(c);
            }

            @Override
            public double outlineTop(Pos c) {
                if (!io.isLoaded(c) || raisedAt(c)) return 1.0;
                BlockSpec b = io.block(c);
                return b.isAir() ? 0 : io.outlineTop(b);
            }

            @Override
            public boolean standAllowed(Pos c) {
                return io.standAllowed(c);
            }
        };
    }

    /**
     * The break-in's walls: broken (or given up), the broken ones become cells of this run to place again, each after
     * the target and every one beyond it ({@link Entry#after}: the far end first); then the bot walks to the planned
     * stand cell for the target.
     */
    private TaskStatus spotBreak() {
        BreakIn.Plan plan = breakIn;
        Entry t = breakInTarget;
        if (plan == null || t == null) {
            spotStep = SpotStep.PICK;
            return TaskStatus.RUNNING;
        }
        TaskStatus s = plan.walls().isEmpty() ? TaskStatus.SUCCESS : io.breakWalls(plan.walls(), plan.path());
        lastActivity = now;
        if (s == TaskStatus.RUNNING) return s;
        breakIn = null;
        breakInTarget = null;
        int again = addCorridor(plan.replace(), t);
        if (s == TaskStatus.FAILED) {
            io.log("Lane " + lane.index() + ": break-in for " + t.p.pos() + " failed: " + io.breakError()
                + (again > 0 ? "; the " + again + " block(s) broken go up again" : ""));
            if (t.st == State.TODO) skip(t, "not reachable: the break-in failed");
            spotStep = SpotStep.PICK;
            return TaskStatus.RUNNING;
        }
        if (t.st != State.TODO) {
            spotStep = SpotStep.PICK;
            return TaskStatus.RUNNING;
        }
        Pos st = plan.stand();
        corridorCells.add(key(st.x(), st.z()));
        double y = standY(st.x(), st.z());
        Spot sp = new Spot(st.x(), Double.isNaN(y) ? artY : y, st.z(), 0, false, List.of());
        spotTarget = t;
        spot = sp;
        spotStep = SpotStep.WALK;
        spotSince = now;
        spotWalks++;
        picked = true;
        pickedKey = key(st.x(), st.z());
        placedAtPick = spotPlaced;
        spotPicks.merge(pickedKey, 1, Integer::sum);
        return TaskStatus.RUNNING;
    }

    /** The plan's broken cells as cells of this run to place again, the far end first; how many. */
    private int addCorridor(List<Pos> replace, @Nullable Entry t) {
        List<Entry> before = new ArrayList<>();
        if (t != null) before.add(t);
        int n = 0;
        for (Pos w : replace) {
            long k = key(w.x(), w.z());
            corridorCells.add(k);
            Placement p = io.artAt(w);
            if (p == null || p.block() == null || !io.isLoaded(w) || io.matches(p.block(), io.block(w))) continue;
            Entry e = byCell.get(k);
            if (e == null) {
                Entry extra = extraByCell.remove(k);
                if (extra != null) {
                    extras.remove(extra);
                    if (greedy() && (extra.st == State.TODO || extra.st == State.PENDING)) owe(extra, -1);
                }
                e = entryFor(p);
                entries.add(e);
                byRow.computeIfAbsent(e.row, r -> new ArrayList<>()).add(e);
                byCell.put(k, e);
                if (greedy()) owe(e, 1);
            } else if (e.st != State.TODO) {
                if (greedy() && e.st != State.PENDING) owe(e, 1);
                e.st = State.TODO;
                e.sends = 0;
                e.drops = 0;
                e.spotTries = 0;
                e.spotMisses = 0;
                e.floorFails = 0;
                e.failedSpots.clear();
                e.retryAt = 0;
            }
            e.left = true;
            e.breakIn = true;
            e.after = List.copyOf(before);
            before.add(e);
            n++;
        }
        return n;
    }

    private boolean startLaneRecovery(double target) {
        if (!io.dripstoneBreakThrough() || laneRecoveryTried) return false;
        laneRecoveryTried = true;
        Pos goal = lineCell(Math.round(target));
        io.log("Lane " + lane.index() + ": no headway at " + fmt(io.x()) + "," + fmt(io.y()) + "," + fmt(io.z())
            + " (" + fmt(geo.lateral(io.x(), io.z())) + " off the line" + (raisedNear(io.x(), io.z()) ? ", a raised block beside"
            : "") + "): back to the line at " + goal + " by the pathfinder");
        laneRecoveryGoal = new GoalNearXZ(goal.x(), artY, goal.z(), 0, 2);
        laneRecoveryFirst = true;
        io.stopWalking();
        return true;
    }

    private TaskStatus recoverWalk(List<GoalNearXZ> goals, BooleanSupplier arrived) {
        return recoverWalk(goals, arrived, false, false);
    }

    private TaskStatus recoverWalk(List<GoalNearXZ> goals, BooleanSupplier arrived, boolean floor) {
        return recoverWalk(goals, arrived, floor, true);
    }

    private TaskStatus recoverWalk(List<GoalNearXZ> goals, BooleanSupplier arrived, boolean floor, boolean stand) {
        return recoverWalk(goals, arrived, floor, stand, stand ? "stand spot" : "lane start", false);
    }

    private TaskStatus recoverWalk(List<GoalNearXZ> goals, BooleanSupplier arrived, boolean floor, boolean stand,
                                   String kind, boolean failed) {
        if (sealOut != null) return io.walk(goals, arrived, true);
        if (walkRecovery == null) walkRecovery = new DripstoneBreakThrough(new DripstoneBreakThrough.Io() {
            @Override public boolean enabled() { return io.dripstoneBreakThrough() && params.standArea() != null; }
            @Override public Pos feet() { return feetCell(); }
            @Override public BreakIn.Grid grid() { return breakInGrid(); }
            @Override public BreakIn.Target target(Pos c) {
                Placement p = io.artAt(c);
                return p == null ? null : breakInTarget(c, p.block());
            }
            @Override public BreakIn.Reach reach() { return new BreakIn.Reach(stillReach() - 0.2, stillAirReach() - 0.2); }
            @Override public TaskStatus breakWalls(List<Pos> walls, List<Pos> path) { return io.breakWalls(walls, path); }
            @Override public void stop() { io.stopWalking(); }
            @Override public void cancelBreaks() { io.cancelBreakWalls(); }
            @Override public void restore(List<Pos> cells) { walkCorridor.addAll(cells); }
            @Override public void log(String line) { io.log(line); }
            @Override public String relative(Pos c) {
                Area a = params.standArea();
                return "[X" + (c.x() - a.minX()) + "," + c.y() + ",Z" + (c.z() - a.minZ()) + "]";
            }
        });
        // a last-resort spot (a walk that may climb, past a field of dripstone) the pathfinder finds no path to the first
        // time finds none the next times either: live 2026-10-13, 12 such spots of one lane took 75 s, 4 tries each
        TaskStatus status = walkRecovery.tick(goals, kind, () -> failed ? TaskStatus.FAILED : stand
            ? (spotLastResort ? io.walk(goals, arrived, floor, 0) : io.walk(goals, arrived, floor)) : io.walk(goals, arrived));
        // No placement can close the corridor while this walk still needs it.
        if (status != TaskStatus.RUNNING && !walkCorridor.isEmpty()) {
            Entry target = stand ? spotTarget : null;
            addCorridor(List.copyOf(walkCorridor), target);
            walkCorridor.clear();
        }
        return status;
    }

    private TaskStatus spotWalk() {
        Spot s = spot;
        TaskStatus st = recoverWalk(s.legs(), this::atSpot, s.floor());
        if (st == TaskStatus.RUNNING) return st;
        if (st == TaskStatus.FAILED) {
            io.log("Lane " + lane.index() + ": cannot reach stand spot " + s.feet() + (s.floor() ? " on the floor" : "")
                + ": " + io.walkError());
            if (spotTarget != null) spotTarget.failedSpots.add(key(s.x(), s.z()));
            if (s.floor()) {
                floorWalkFails++;
                if (spotTarget != null) spotTarget.floorFails++;
            }
            spotStep = SpotStep.PICK;
            return TaskStatus.RUNNING;
        }
        spotStep = SpotStep.CENTER;
        spotSince = now;
        centerHazard = io.edgeSneak() ? io.edgeHazard(s.feet()) : null;
        centerLastX = Double.NaN;
        if (centerHazard != null) {
            io.log("Lane " + lane.index() + ": stand spot " + s.feet() + " is beside " + centerHazard
                + ": centering on it sneaking, then standing still before placing");
        }
        return spotCenter();
    }

    private boolean atSpot() {
        Spot s = spot;
        return s != null && feet().equals(new Pos(s.x(), s.feetY(), s.z()));
    }

    private TaskStatus spotCenter() {
        Spot s = spot;
        double dx = s.x() + 0.5 - io.x();
        double dz = s.z() + 0.5 - io.z();
        boolean centred = Math.abs(dx) <= 0.15 && Math.abs(dz) <= 0.15 && io.onGround();
        if (centerHazard != null) return edgeCenter(s, dx, dz, centred);
        if (centred || now - spotSince > CENTER_TICKS) {
            io.holdStill();
            startPlacing(spotTarget, s);
            return TaskStatus.RUNNING;
        }
        int[] keys = Aim.movementKeys(io.yaw(), dx, dz);
        io.submit(null, null, keys[0], keys[1], false);
        return TaskStatus.RUNNING;
    }

    /**
     * Centring on a stand spot beside a drop, water or a no-go zone (class javadoc "Edge stops"): every input sneaks, so
     * the box never leaves the floor however the bot moves; placing starts once it is centred (or the centring timed
     * out) and it stands still, so letting go of the sneak key moves nothing.
     */
    private TaskStatus edgeCenter(Spot s, double dx, double dz, boolean centred) {
        boolean still = !Double.isNaN(centerLastX) && io.onGround()
            && Math.hypot(io.x() - centerLastX, io.z() - centerLastZ) < 0.003;
        centerLastX = io.x();
        centerLastZ = io.z();
        long waited = now - spotSince;
        if (((centred || waited > CENTER_TICKS) && still) || waited > CENTER_TICKS + EDGE_STILL_TICKS) {
            io.holdStill();
            startPlacing(spotTarget, s);
            return TaskStatus.RUNNING;
        }
        if (centred || waited > CENTER_TICKS) {
            io.submit(null, null, 0, 0, false, true);
            return TaskStatus.RUNNING;
        }
        int[] keys = Aim.movementKeys(io.yaw(), dx, dz);
        io.submit(null, null, keys[0], keys[1], false, true);
        return TaskStatus.RUNNING;
    }

    private void startPlacing(@Nullable Entry target, @Nullable Spot s) {
        spotTarget = target;
        spot = s;
        spotStep = SpotStep.PLACE;
        spotSince = now;
        lastSpotClick = now;
        spotIdleSince = -1;
        triedHere = feet();
        for (Entry e : entries) e.spotMisses = 0;
        pipeline.reset();
    }

    private TaskStatus spotPlace(List<Entry> todo) {
        double reach = stillReach();
        List<Entry> cands = spotCandidates(todo);
        double ex = io.x();
        double ey = io.eyeY();
        double ez = io.z();
        // low blocks nearest first, then shading blocks farthest first: none hides the next target
        cands.sort(Comparator.<Entry>comparingInt(e -> e.shades ? 1 : 0).thenComparingDouble(e -> {
            double d = Aim.distSq(ex, ey, ez, e.p.pos().x() + 0.5, artY, e.p.pos().z() + 0.5);
            return e.shades ? -d : d;
        }));
        env.begin(reach, Double.NaN);
        List<Entry> clickable = clickable(cands);
        PlacePipeline.Plan<Entry, S> plan = pipeline.plan(now, clickable, env);
        String move = plan.inventoryMove();
        S rot = plan.nextShot();
        io.submit(rot == null ? null : rot.yaw(), rot == null ? null : rot.pitch(), 0, 0, false);
        boolean clicked = pipeline.execute(now, plan, env);
        if (move != null && !clicked) hold(move);
        if (clicked) {
            lastSpotClick = now;
            lastActivity = now;
        }
        boolean busy = clicked || plan.next() != null || move != null || env.inventoryBusy() || io.lostTicks() > 0
            || anyPending() || clickable.size() < cands.size();
        if (busy) spotIdleSince = -1;
        else if (spotIdleSince < 0) spotIdleSince = now;
        boolean idle = spotIdleSince >= 0 && now - spotIdleSince >= SPOT_IDLE_TICKS;
        if (idle || now - lastSpotClick > SPOT_MAX_TICKS) {
            Spot s = spot;
            if (spotTarget != null && spotTarget.st == State.TODO && s != null) spotTarget.failedSpots.add(key(s.x(), s.z()));
            spotStep = SpotStep.PICK;
        }
        return TaskStatus.RUNNING;
    }

    /**
     * TODO cells placeable from where the bot stands: clear of its box, in reach, not missed twice from here, and
     * not the full block that would close the last open side of the bot's own cell (it keeps a way out on the floor).
     * AIRPLACE mode, the bot on the floor: no full block in the 8 cells around its feet (never a wall right next to
     * itself; they go from a spot further away).
     */
    private List<Entry> spotCandidates(List<Entry> todo) {
        double reach = stillReach();
        double[] box = Aim.playerBox(io.x(), io.y(), io.z(), 0.05);
        Pos feet = feet();
        boolean onFloor = feet.y() <= artY;
        int open = onFloor ? 4 - walledSides(feet.x(), feet.z()) : 4;
        boolean noWallsBeside = airOk && onFloor;
        List<Entry> out = new ArrayList<>();
        for (Entry e : todo) {
            Pos pos = e.p.pos();
            if (e.spotMisses >= 2 || Aim.cellOverlaps(pos.x(), pos.y(), pos.z(), box)) continue;
            if (e.solid && open <= 1 && Math.abs(pos.x() - feet.x()) + Math.abs(pos.z() - feet.z()) == 1) continue;
            if (e.solid && noWallsBeside && chebyshev(pos, feet) <= 1 && !besideOk(e)) continue;
            if (!inReachNow(e, reach, stillAirReach())) continue;
            if (heldBack(e)) continue;
            out.add(e);
        }
        return out;
    }

    /**
     * Stand spot for {@code t}. LOOK mode (or airplace not available): {@link #spotAround}, floor or on top of a full
     * block alike. AIRPLACE mode (class javadoc): a floor spot the bot walks to without climbing ({@link FloorRoutes});
     * only once {@link #FLOOR_FAILS} walks to floor spots failed for {@code t}, or no floor spot exists, any spot (on
     * top of a full block, or a floor spot Baritone may climb to), logged as the last resort.
     *
     * <p>Pocket exits: {@code t} would close the bot's way out from where it stands ({@link #closesIn}), so the guard
     * holds it back from every spot of the area it would shut ({@link #shutInBy}). Those spots are left out (the floor
     * routes never climb, so before this every spot they offered lay in that area: live 2026-11-05, Edgerunners [3,6]
     * lane 14, a south-east corner pocket, 0 clicks in 4 repair passes, BLOCKED twice); a spot outside it places
     * {@code t} with the way out kept. With no spot outside, the search is the one before (the guard still holds).
     */
    private @Nullable Spot bestSpot(Entry t) {
        Set<Long> shut = shutInBy(t);
        if (!shut.isEmpty()) {
            Spot s = bestSpot(t, shut);
            if (s != null) {
                if (!t.shutLogged) {
                    t.shutLogged = true;
                    io.log("Lane " + lane.index() + ": " + t.p.pos() + " would close the bot's way out from the "
                        + shut.size() + " cells it stands among; stand spot " + s.feet() + " outside them (the bot at "
                        + feet() + ")");
                }
                return s;
            }
        }
        return bestSpot(t, Set.of());
    }

    private @Nullable Spot bestSpot(Entry t, Set<Long> shut) {
        spotLastResort = false;
        if (!airOk) return spotAround(t, null, false, shut);
        String why;
        if (t.floorFails < FLOOR_FAILS) {
            FloorRoutes routes = new FloorRoutes(feet(), t.p.pos());
            Spot s = spotAround(t, routes, true, shut);
            if (s != null) return s.routed(routes.legs(s.x(), s.z(), s.feetY()));
            why = "no floor spot in reach the bot can walk to without climbing";
        } else {
            why = t.floorFails + " walks to floor spots failed";
        }
        Spot s = spotAround(t, null, true, shut);
        if (s != null) {
            lastResorts++;
            spotLastResort = true;
            String edge = io.edgeSneak() || io.edgeSpotsLast() ? io.edgeHazard(s.feet()) : null;
            io.log("Lane " + lane.index() + ": last resort for " + t.p.pos() + " (" + why + "): stand spot " + s.feet()
                + (s.y() > artY + TALL ? " on top of a full block" : " on the floor, the walk may climb")
                + (edge != null ? ", beside " + edge + " (edge stop)" : "") + "; the bot at " + feet());
        }
        return s;
    }

    /**
     * The cells of the bot's area (its {@link #wayOutStart} cells and every {@link #wayOutCell} joined to them without
     * {@code t}'s cell) when {@code t} would close its way out ({@link #closesIn}); empty otherwise, and empty when that
     * area is too large to flood on the tick thread (it then counts as open, as in {@link #wayOutConnected}).
     */
    private Set<Long> shutInBy(Entry t) {
        if (!closesIn(t)) return Set.of();
        Pos wall = t.p.pos();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        for (Pos c : wayOutStart) if (seen.add(key(c.x(), c.z()))) queue.add(c);
        while (!queue.isEmpty()) {
            if (seen.size() > WAY_OUT_FLOOD_MAX) return Set.of();
            Pos c = queue.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = c.x() + d[0];
                int nz = c.z() + d[1];
                if (!wayOutCell(nx, nz, wall) || !seen.add(key(nx, nz))) continue;
                queue.add(new Pos(nx, artY, nz));
            }
        }
        return seen;
    }

    /**
     * Cheapest stand spot for {@code t}: a cell around it (the 8 neighbours, then the ring 2 out: the first ring with a
     * spot) inside the stand area and {@link Io#standAllowed}, from which {@code t} can be placed from an eye over its
     * centre ({@link #placeableFrom}). Cost: the walk there, a climb, standing on another cell still to place, a pocket,
     * less the other cells it can place.
     *
     * <p>LOOK search ({@code air} false): on the floor or on top of a full block; never down into a cell walled in on
     * all sides, and a full block must not close the last open side of a floor spot. AIRPLACE search: never a floor
     * cell with {@link #AIR_MAX_WALLS} full-block sides, never next to a full block to place; with {@code routes} only
     * floor cells it reaches (the walk: its route's length). Never a cell of {@code shut} ({@link #shutInBy}).
     */
    private @Nullable Spot spotAround(Entry t, @Nullable FloorRoutes routes, boolean air, Set<Long> shut) {
        Area area = params.standArea();
        int tx = t.p.pos().x();
        int tz = t.p.pos().z();
        double px = io.x();
        double pz = io.z();
        double reach = stillReach() - 0.2; // the bot stands within 0.15 of the centre
        Spot best = null;
        boolean bestEdge = false;
        // edge stops: a spot beside a drop, water or a no-go zone only when no other spot of any ring places t
        for (int r = 1; r <= SPOT_RINGS && (best == null || bestEdge); r++) {
            if (air && t.solid && r <= 1 && !besideOk(t)) continue; // never a full block right next to the bot
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = tx + dx;
                    int z = tz + dz;
                    long k = key(x, z);
                    if (area == null || !area.contains(x, z) || t.failedSpots.contains(k) || deadSpots.contains(k)
                        || shut.contains(k)) {
                        continue;
                    }
                    double y = standY(x, z);
                    if (Double.isNaN(y)) continue;
                    if (!io.standAllowed(new Pos(x, (int) Math.floor(y + 1e-6), z))) continue;
                    double ex = x + 0.5;
                    double ez = z + 0.5;
                    if (Aim.cellOverlaps(tx, t.p.pos().y(), tz, Aim.playerBox(ex, y, ez, 0.05))) continue;
                    boolean onTop = y > artY + TALL;
                    int walls = onTop ? 0 : walledSides(x, z);
                    if (air) {
                        // a pocket or a 1-wide slot between full blocks: where 2b2t set the bot back (2026-10-11); blocks
                        // nobody stands on do not count with keep-reachable, nor anything beside a break-in's corridor
                        int airWalls = onTop || corridorCells.contains(k) ? 0
                            : params.keepReachable() || t.breakIn ? fullSides(x, z) : walls;
                        if (!onTop && airWalls >= AIR_MAX_WALLS) continue;
                        if (routes != null && (onTop || !routes.reached(x, z))) continue;
                    } else if (walls >= 4 || (t.solid && Math.abs(dx) + Math.abs(dz) == 1 && walls >= 3)) {
                        // never down into a cell walled in on all sides, and a full block must not close the last open
                        // side of a floor spot: the bot keeps a way out on the floor
                        continue;
                    }
                    if (!placeableFrom(ex, y + Aim.EYE_HEIGHT, ez, t, reach)) continue;
                    double walk = routes != null ? routes.distance(x, z) : Math.hypot(ex - px, ez - pz);
                    double cost = walk + (onTop ? 1.5 : 0) + 0.5 * (r - 1);
                    Entry there = byCell.get(k);
                    if (there != null && there.st == State.TODO) cost += 3;
                    if (!onTop && walls >= 3) cost += 2;
                    cost -= 1.0 * othersSeen(t, ex, y + Aim.EYE_HEIGHT, ez, y, reach);
                    boolean edge = io.edgeSpotsLast()
                        && io.edgeHazard(new Pos(x, (int) Math.floor(y + 1e-6), z)) != null;
                    if (edge) cost += EDGE_SPOT_COST;
                    if (best == null || cost < best.cost()) {
                        best = new Spot(x, y, z, cost, routes != null, List.of());
                        bestEdge = edge;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Floor routes from the bot's feet (AIRPLACE mode): a breadth-first flood over the floor cells of the stand area
     * within {@link #ROUTE_MARGIN} of the bot and the target: nothing taller than a step at the feet, a support below,
     * head room, not one of this lane's full blocks waiting for the server, and {@link Io#standAllowed} (a cell within 2
     * of the bot may be walked out of). Straight on is tried first, so routes turn rarely.
     *
     * <p>Baritone does not know the rule: across a wall of full blocks its cheapest path climbs over (a jump costs less
     * than a detour), and the walk's path check refuses it. So the walk follows the route in legs ({@link #legs}): each
     * leg ends where the rectangle between its ends would hold a cell that is not open floor. Inside such a rectangle
     * every shortest path stays on the open floor, and a path that climbs is longer, so Baritone keeps to the floor.
     */
    private final class FloorRoutes {
        private final int sx;
        private final int sz;
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        /** Cell → the cell before it on its route; route length of each cell reached. */
        private final Map<Long, Long> from = new HashMap<>();
        private final Map<Long, Integer> dist = new HashMap<>();
        private final Map<Long, Boolean> open = new HashMap<>();

        FloorRoutes(Pos start, Pos target) {
            Area a = params.standArea();
            sx = start.x();
            sz = start.z();
            minX = Math.max(a.minX(), Math.min(sx, target.x()) - ROUTE_MARGIN);
            minZ = Math.max(a.minZ(), Math.min(sz, target.z()) - ROUTE_MARGIN);
            maxX = Math.min(a.maxX(), Math.max(sx, target.x()) + ROUTE_MARGIN);
            maxZ = Math.min(a.maxZ(), Math.max(sz, target.z()) + ROUTE_MARGIN);
            dist.put(key(sx, sz), 0);
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            queue.add(new int[]{sx, sz, 0, 0}); // cell, and the step that led there
            int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                int d = dist.get(key(c[0], c[1]));
                for (int i = -1; i < steps.length; i++) {
                    int[] st = i < 0 ? new int[]{c[2], c[3]} : steps[i];
                    if (st[0] == 0 && st[1] == 0) continue;
                    int nx = c[0] + st[0];
                    int nz = c[1] + st[1];
                    if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                    long k = key(nx, nz);
                    if (dist.containsKey(k) || !open(nx, nz)) continue;
                    dist.put(k, d + 1);
                    from.put(k, key(c[0], c[1]));
                    queue.add(new int[]{nx, nz, st[0], st[1]});
                }
            }
        }

        boolean reached(int x, int z) {
            return dist.containsKey(key(x, z));
        }

        int distance(int x, int z) {
            return dist.get(key(x, z));
        }

        /** Open floor for a route (the class javadoc); the bot's own cell always is. */
        private boolean open(int x, int z) {
            if (x == sx && z == sz) return true;
            return open.computeIfAbsent(key(x, z), k -> {
                double y = standY(x, z);
                if (Double.isNaN(y) || y > artY + TALL) return false;
                Entry e = byCell.get(k);
                if (e != null && e.solid && e.st == State.PENDING) return false;
                return io.standAllowed(new Pos(x, artY, z)) || Math.max(Math.abs(x - sx), Math.abs(z - sz)) <= 2;
            });
        }

        /** Every cell of the rectangle with corners (ax, az) and (bx, bz) is open floor. */
        private boolean clear(int ax, int az, int bx, int bz) {
            for (int x = Math.min(ax, bx); x <= Math.max(ax, bx); x++) {
                for (int z = Math.min(az, bz); z <= Math.max(az, bz); z++) if (!open(x, z)) return false;
            }
            return true;
        }

        /** The walk to floor cell (x, z) (reached), feet at {@code fy}: legs along its route (class javadoc). */
        List<GoalNearXZ> legs(int x, int z, int fy) {
            List<long[]> path = new ArrayList<>();
            long k = key(x, z);
            path.add(new long[]{x, z});
            while (from.containsKey(k)) {
                k = from.get(k);
                path.add(new long[]{(int) (k >> 32), (int) k});
            }
            Collections.reverse(path);
            List<GoalNearXZ> legs = new ArrayList<>();
            int a = 0;
            for (int i = 1; i < path.size(); i++) {
                long[] s = path.get(a);
                long[] c = path.get(i);
                if (clear((int) s[0], (int) s[1], (int) c[0], (int) c[1])) continue;
                long[] end = path.get(i - 1);
                legs.add(new GoalNearXZ((int) end[0], artY, (int) end[1], 0, 0));
                a = i - 1;
            }
            legs.add(new GoalNearXZ(x, fy, z, 0, 0));
            return legs;
        }
    }

    /**
     * Why a path Baritone planned (feet cells in order) must not be walked, or null: it passes within one cell (x, z;
     * feet y within 1) of one of the {@code avoid} cells, where the server set the bot back (cells next to one at the
     * start may be walked out of); with {@code noClimb}, a step goes up onto a block inside {@code area}. For
     * {@link Io#walk(List, BooleanSupplier, boolean)} (PlaceLaneTask's path check, the simulator's Baritone).
     */
    public static @Nullable String pathRefusal(List<Pos> cells, @Nullable Area area, Collection<Pos> avoid,
                                               boolean noClimb) {
        boolean leaving = true;
        for (int i = 0; i < cells.size(); i++) {
            Pos c = cells.get(i);
            Pos near = nearAvoid(c, avoid);
            if (near == null) {
                leaving = false;
            } else if (!leaving) {
                return "passes the setback cell " + near.x() + "," + near.y() + "," + near.z();
            }
            if (noClimb && i > 0 && c.y() > cells.get(i - 1).y() && (area == null || area.contains(c.x(), c.z()))) {
                return "climbs onto a block at " + c.x() + "," + (c.y() - 1) + "," + c.z();
            }
        }
        return null;
    }

    /** An {@code avoid} cell within one cell of {@code feet} (x, z; y within 1), or null. */
    public static @Nullable Pos nearAvoid(Pos feet, Collection<Pos> avoid) {
        for (Pos a : avoid) {
            if (Math.abs(a.x() - feet.x()) <= 1 && Math.abs(a.z() - feet.z()) <= 1 && Math.abs(a.y() - feet.y()) <= 1) {
                return a;
            }
        }
        return null;
    }

    private static int chebyshev(Pos a, Pos b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()));
    }

    /** Feet height on cell (x, z) of the art layer: the floor (low block) or on top of a full block; NaN = cannot stand. */
    private double standY(int x, int z) {
        Pos feet = new Pos(x, artY, z);
        if (!io.isLoaded(feet) || io.hasFluid(feet)) return Double.NaN;
        BlockSpec b = io.block(feet);
        double top = b.isAir() ? 0 : io.collisionTop(b);
        if (top > TALL) { // on top of it (a full block, or soul sand / mud a little lower)
            // never on a pointed dripstone or a pot: the pathfinder walks onto neither (class javadoc "Keep reachable")
            if (params.keepReachable() && !io.standOnTop(b)) return Double.NaN;
            return passable(feet.above()) && passable(feet.above().above()) ? artY + top : Double.NaN;
        }
        if (!io.blocksPlacement(feet.below())) return Double.NaN;
        return passable(feet.above()) && passable(feet.above().above()) ? artY + top : Double.NaN;
    }

    private boolean passable(Pos p) {
        BlockSpec b = io.block(p);
        return (b.isAir() || io.collisionTop(b) == 0.0) && !io.hasFluid(p);
    }

    /**
     * Way out (class javadoc): {@code e} is a block the pathfinder can neither walk through nor onto ({@link Entry#trap})
     * and, placed now, it would disconnect the floor area the bot stands in from the permanent approach
     * ({@link #wayOutCell}), whatever its size. False when the area is closed already (nothing left
     * to keep open) or {@code build.laneWayOut} is off.
     */
    private boolean closesIn(Entry e) {
        if ((!params.wayOut() && !e.breakIn && !e.raised) || !e.trap) return false;
        wayOutTick();
        if (!wayOutOpen) return false;
        Pos c = wallCell(e);
        return wayOutClosing.computeIfAbsent(key(c.x(), c.z()), k -> narrowAt(c) && !wayOutConnected(wayOutStart, c));
    }

    /** The bot's area for this tick ({@link #closesIn}, {@link #sealsIn}): its start cells and whether it is open. */
    private void wayOutTick() {
        if (wayOutTick == now) return;
        wayOutTick = now;
        wayOutStart = wayOutStart();
        wayOutOpen = !wayOutStart.isEmpty() && wayOutConnected(wayOutStart, null);
        wayOutClosing.clear();
        wayOutSealing.clear();
    }

    /**
     * {@code e} may go up from a cell right next to it in AIRPLACE mode: a break-in's cell (its corridor is 1 wide), or,
     * with keep-reachable, a block nobody stands on (pointed dripstone, a pot): it cannot become a wall the bot drops
     * against, and {@link #closesIn} keeps the bot's way out. Full blocks never ("Stand-spot safety").
     */
    private boolean besideOk(Entry e) {
        // a raised block is a wall at head height beside the bot as a full block is (class javadoc "Raised cells")
        return e.breakIn || (params.keepReachable() && e.trap && !e.raised);
    }

    /** The cell at the lane's height of {@code e}'s column (a raised cell's column; any other cell: its own). */
    private Pos wallCell(Entry e) {
        Pos p = e.p.pos();
        return e.raised ? new Pos(p.x(), artY, p.z()) : p;
    }

    /** {@code e} waits: it would close the bot's way out or a cell's way in, or a cell it goes up after is still to place. */
    private boolean heldBack(Entry e) {
        return closesIn(e) || sealsIn(e) || waitsFor(e);
    }

    /** A cell {@code e} goes up after ({@link Entry#after}: a break-in's corridor, the far end first) is still to place. */
    private static boolean waitsFor(Entry e) {
        for (Entry a : e.after) if (a.st == State.TODO || a.st == State.PENDING) return true;
        return false;
    }

    /**
     * Keep reachable (class javadoc): {@code e} is a block the pathfinder can neither walk through nor onto and, placed
     * now, it would close the last way in to an area, whatever its size, that
     * holds a cell of this run still to place, or one skipped because no stand spot reached it (VERIFY tries it
     * again): an area next to it reached from the bot's area only through its cell. False when
     * {@code build.laneKeepReachable} is off.
     */
    private boolean sealsIn(Entry e) {
        if ((!params.keepReachable() && !e.breakIn && !e.raised) || !e.trap || e.tieBreak) return false;
        wayOutTick();
        if (wayOutStart.isEmpty()) return false;
        Pos c = wallCell(e);
        return wayOutSealing.computeIfAbsent(key(c.x(), c.z()), k -> seals(e, c));
    }

    private boolean seals(Entry e, Pos wall) {
        if (!narrowAt(wall)) return false;
        Set<Long> start = new HashSet<>();
        for (Pos s : wayOutStart) start.add(key(s.x(), s.z()));
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int nx = wall.x() + d[0];
            int nz = wall.z() + d[1];
            if (start.contains(key(nx, nz)) || !wayOutCell(nx, nz, null)) continue;
            // the area beyond the wall: closed off by it (without the bot) yet open through its cell now
            Set<Long> area = sealedArea(nx, nz, wall, start);
            if (area == null) continue;
            for (long k : area) {
                Entry t = byCell.get(k);
                if (t == null) t = extraByCell.get(k);
                if (t != null && t != e && (t.st == State.TODO || t.st == State.PENDING
                    || (t.st == State.SKIPPED && t.unreachable))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The open sides of {@code c} ({@link #wayOutCell}) are not all joined around it through the 8 cells of the ring
     * about it: a block there may split the floor (most cells of an open floor are not, and need no flood).
     */
    private boolean narrowAt(Pos c) {
        int[][] ring = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
        boolean[] open = new boolean[8];
        int sides = 0;
        for (int i = 0; i < 8; i++) {
            open[i] = wayOutCell(c.x() + ring[i][0], c.z() + ring[i][1], null);
            if (open[i] && i % 2 == 1) sides++;
        }
        if (sides <= 1) return false; // a dead end (or shut): nothing beyond it
        // runs of open cells around the ring (4-connected along it: neighbours on the ring touch at an edge)
        int runsWithSide = 0;
        int startAt = -1;
        for (int i = 0; i < 8; i++) if (!open[i]) startAt = i;
        if (startAt < 0) return false; // the whole ring open
        boolean inRun = false;
        boolean runHasSide = false;
        for (int j = 1; j <= 8; j++) {
            int i = (startAt + j) % 8;
            if (open[i]) {
                if (!inRun) {
                    inRun = true;
                    runHasSide = false;
                }
                if (i % 2 == 1) runHasSide = true;
            } else if (inRun) {
                inRun = false;
                if (runHasSide) runsWithSide++;
            }
        }
        if (inRun && runHasSide) runsWithSide++;
        return runsWithSide >= 2;
    }

    /**
     * The cells reached from (x, z) without passing {@code wall}, if they hold none of the bot's {@code start} cells
     * while the same flood through the wall's cell reaches the bot; null otherwise (open either way or closed already).
     */
    private @Nullable Set<Long> sealedArea(int x, int z, Pos wall, Set<Long> start) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        seen.add(key(x, z));
        queue.add(new long[]{x, z});
        while (!queue.isEmpty()) {
            if (seen.size() > WAY_OUT_FLOOD_MAX) return null; // tick-thread budget: that large an area counts as open
            long[] c = queue.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = (int) c[0] + d[0];
                int nz = (int) c[1] + d[1];
                long k = key(nx, nz);
                if (seen.contains(k) || !wayOutCell(nx, nz, wall)) continue;
                if (start.contains(k)) return null; // the bot's area: not closed by this block
                seen.add(k);
                queue.add(new long[]{nx, nz});
            }
        }
        // closed off without the wall's cell: was it open through it (the bot reached over that cell)?
        Set<Long> through = new HashSet<>(seen);
        ArrayDeque<long[]> q2 = new ArrayDeque<>();
        through.add(key(wall.x(), wall.z()));
        q2.add(new long[]{wall.x(), wall.z()});
        while (!q2.isEmpty()) {
            if (through.size() > seen.size() + WAY_OUT_FLOOD_MAX) return null; // budget: counts as open
            long[] c = q2.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = (int) c[0] + d[0];
                int nz = (int) c[1] + d[1];
                long k = key(nx, nz);
                if (through.contains(k) || !wayOutCell(nx, nz, null)) continue;
                if (start.contains(k)) return seen; // the bot reaches it through the wall's cell now
                through.add(k);
                q2.add(new long[]{nx, nz});
            }
        }
        return null; // closed already, with or without this block
    }

    /**
     * The cells the bot's area is counted from: its feet cell, else (in or on a block it cannot stand on: a pointed
     * dripstone) the cells under its box and next to its feet it can step into.
     */
    private List<Pos> wayOutStart() {
        Pos f = feetCell();
        if (wayOutCell(f.x(), f.z(), null)) return List.of(f);
        List<Pos> out = new ArrayList<>();
        double h = Aim.PLAYER_HALF_WIDTH - 1e-3;
        for (int x = (int) Math.floor(io.x() - h); x <= (int) Math.floor(io.x() + h); x++) {
            for (int z = (int) Math.floor(io.z() - h); z <= (int) Math.floor(io.z() + h); z++) {
                if (wayOutCell(x, z, null)) out.add(new Pos(x, artY, z));
            }
        }
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Pos n = new Pos(f.x() + d[0], artY, f.z() + d[1]);
            if (!out.contains(n) && wayOutCell(n.x(), n.z(), null)) out.add(n);
        }
        return out;
    }

    private Set<Pos> wayOutOutside = Set.of();
    private boolean wayOutOutsidePicked;
    /**
     * Way out: an approach cell counts only this far (Chebyshev) from the bot's start cells. The approach is picked once
     * per run, so the cells the bot stands in are approach cells too; dripstone placed around it later must still
     * count as closing it in (live 2026-10-12, ellen [0,6]).
     */
    static final int WAY_OUT_ANCHOR = 16;
    /** Way-out floods stop past this many cells (tick-thread budget): an area that large counts as open. */
    static final int WAY_OUT_FLOOD_MAX = 4096;

    /**
     * The bot's area (its {@code start} cells, {@code wall} counting as a block) still reaches the approach picked at
     * the run's start, at least {@link #WAY_OUT_ANCHOR} cells away over cells it can stand in now; component size
     * alone never makes a trap open (live 2026-10-13, remu [0,2]: a 40-cell pocket counted as open sealed the bot in).
     */
    private boolean wayOutConnected(List<Pos> start, @Nullable Pos wall) {
        Area area = params.standArea();
        if (area == null) return false;
        if (!wayOutOutsidePicked) {
            wayOutOutsidePicked = true;
            Set<Pos> outside = SealOut.outside(area, artY, c -> {
                Entry e = byCell.get(key(c.x(), c.z()));
                return wayOutCell(c.x(), c.z(), null) && (e == null || !e.trap);
            });
            wayOutOutside = outside;
        }
        if (wayOutOutside.isEmpty()) return false;
        ArrayDeque<Pos> queue = new ArrayDeque<>(start);
        Set<Pos> seen = new HashSet<>(start);
        while (!queue.isEmpty()) {
            if (seen.size() > WAY_OUT_FLOOD_MAX) return true;
            Pos c = queue.poll();
            if (c.equals(wall)) continue;
            if (wayOutOutside.contains(c) && farFrom(start, c)) return true;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Pos n = new Pos(c.x() + d[0], artY, c.z() + d[1]);
                if (seen.contains(n) || !wayOutCell(n.x(), n.z(), wall)) continue;
                seen.add(n);
                queue.add(n);
            }
        }
        return false;
    }

    private static boolean farFrom(List<Pos> start, Pos c) {
        for (Pos s : start) if (Math.max(Math.abs(s.x() - c.x()), Math.abs(s.z() - c.z())) < WAY_OUT_ANCHOR) return false;
        return true;
    }

    /**
     * The bot can be in or on cell (x, z) and walk on from it: a floor cell (nothing taller than a step, a support
     * below), or a block to stand on (Baritone climbs it). Not {@code wall}, and not a block of this lane sent but not
     * confirmed that is no block to stand on.
     */
    private boolean wayOutCell(int x, int z, @Nullable Pos wall) {
        if (wall != null && x == wall.x() && z == wall.z()) return false;
        Pos p = new Pos(x, artY, z);
        if ((params.standArea() != null && !params.standArea().contains(x, z)) || !io.standAllowed(p)) return false;
        Entry e = byCell.get(key(x, z));
        if (e != null && e.solid && e.st == State.PENDING) return !e.trap;
        if (!io.isLoaded(p) || io.hasFluid(p)) return false;
        BlockSpec b = io.block(p);
        if (!passable(p.above()) || !passable(p.above().above())) return false;
        if (!b.isAir() && io.collisionTop(b) > TALL) return io.standOnTop(b);
        return io.blocksPlacement(p.below());
    }

    /**
     * {@code build.laneWayOut}: the cell the box walks into (half a block ahead along (dx, dz)) holds a block taller than
     * a step that is no block to stand on (pointed dripstone, a pot): the walk does not jump up onto it.
     */
    private boolean trapAhead(double px, double pz, double dx, double dz) {
        if (!params.wayOut() && !levels) return false;
        double l = Math.hypot(dx, dz);
        if (l < 1e-9) return false;
        Pos c = new Pos((int) Math.floor(px + dx / l * 0.5), artY, (int) Math.floor(pz + dz / l * 0.5));
        if (raisedAt(c)) return true; // two high from the floor: no jump gets up there
        if (!params.wayOut() || !io.isLoaded(c)) return false;
        BlockSpec b = io.block(c);
        return !b.isAir() && io.collisionTop(b) > TALL && !io.standOnTop(b);
    }

    /**
     * How far around the box {@link #raisedNear} looks for a raised block: more than a tick's move (0.22-0.28), as the
     * collision may stop the box short of the block's face.
     */
    static final double RAISED_NEAR_MARGIN = 0.35;
    /** Off the line by more than this, a raised block that holds the box sends the walk back across first. */
    static final double RAISED_HOLD_LAT = 0.05;
    /** Ticks a hold may go without moving the box across before the walk goes on as before (for a block along). */
    static final int RAISED_HOLD_STILL = 10;

    /**
     * Raised level ({@link Io#raisedLevel}): a raised block within {@link #RAISED_NEAR_MARGIN} of the box. With the box
     * off the line (the keys are relative to the look, so the walk drifts 0.2-0.3 across while it aims), its edge goes
     * 0.01-0.1 into the column beside, and a raised block there holds it. Local 10x runs 2026-11-14: on top of the line's
     * blocks (feet height) the collision's jump climbed it, eight lane walks a print, 1-9 ticks on its top; on the floor
     * (head height, no jump gets past) the walk pressed into it until the stuck wait, the way back to the line failed and
     * 223 and 312 cells of two lanes went to stand spots. {@link #trapAhead} reads only the cell ahead of the box's centre.
     */
    private boolean raisedNear(double px, double pz) {
        if (!levels) return false;
        double m = Aim.PLAYER_HALF_WIDTH + RAISED_NEAR_MARGIN;
        for (int x = (int) Math.floor(px - m); x <= (int) Math.floor(px + m); x++) {
            for (int z = (int) Math.floor(pz - m); z <= (int) Math.floor(pz + m); z++) {
                if (raisedAt(new Pos(x, artY, z))) return true;
            }
        }
        return false;
    }

    /** The bot's cell at art level (the block it stands on, raised). */
    private Pos feetCell() {
        return new Pos((int) Math.floor(io.x()), artY, (int) Math.floor(io.z()));
    }

    /**
     * Full blocks among the four neighbours (a pocket the bot would have to jump out of), counting full blocks of this
     * lane that are sent but not confirmed yet.
     */
    private int walledSides(int x, int z) {
        int n = 0;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Pos p = new Pos(x + d[0], artY, z + d[1]);
            Entry e = byCell.get(key(p.x(), p.z()));
            if ((e != null && e.solid && e.st == State.PENDING) || (io.isLoaded(p) && tall(p))) n++;
        }
        return n;
    }

    /**
     * Full blocks the bot could stand on among the four neighbours (a pocket or a slot it could drop into from above),
     * counting full blocks of this lane sent but not confirmed: {@link #walledSides} without pointed dripstone and pots.
     */
    private int fullSides(int x, int z) {
        int n = 0;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Pos p = new Pos(x + d[0], artY, z + d[1]);
            Entry e = byCell.get(key(p.x(), p.z()));
            // a raised block counts: Baritone walks on its top and drops into the cell beside it
            if (e != null && e.solid && e.st == State.PENDING) {
                if (!e.trap || e.raised) n++;
            } else if (io.isLoaded(p) && tall(p) && (io.standOnTop(io.block(p)) || raisedAt(p))) {
                n++;
            }
        }
        return n;
    }

    private static boolean adjacent(Pos a, Pos b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) == 1;
    }

    /**
     * Other TODO cells (up to {@link #SPOT_SEEN_MAX}) a bot on a stand spot (eye at ex, ey, ez; feet at {@code feetY})
     * could place: within 3 blocks, clear of its box, the support's top face in reach and line of sight.
     */
    private int othersSeen(Entry t, double ex, double ey, double ez, double feetY, double reach) {
        int n = 0;
        double[] box = Aim.playerBox(ex, feetY, ez, 0.05);
        for (int r = t.row - 4; r <= t.row + 4 && n < SPOT_SEEN_MAX; r++) {
            List<Entry> row = byRow.get(r);
            if (row == null) continue;
            for (Entry e : row) {
                if (e == t || e.st != State.TODO) continue;
                Pos pos = e.p.pos();
                double dx = pos.x() + 0.5 - ex;
                double dz = pos.z() + 0.5 - ez;
                if (dx * dx + dz * dz > 9 || Aim.cellOverlaps(pos.x(), pos.y(), pos.z(), box)) continue;
                if (placeableFrom(ex, ey, ez, e, reach) && ++n >= SPOT_SEEN_MAX) break;
            }
        }
        return n;
    }

    private double stillReach() {
        return Math.min(params.reach(), SERVER_RANGE - 0.05);
    }

    /**
     * {@code e} can be placed from an eye at (ex, ey, ez) (a stand spot; {@code reach} is the LOOK reach there):
     * airplace with the cell centre within the airplace range less the same 0.2 centring margin, LOOK with the
     * support's top face in reach and line of sight.
     */
    private boolean placeableFrom(double ex, double ey, double ez, Entry e, double reach) {
        if (methodNow(e) == PlaceMethod.AIRPLACE) return centreWithin(ex, ey, ez, e, stillAirReach() - 0.2);
        return io.visibleFrom(ex, ey, ez, e.support, reach)
            || (e.side && io.sideVisibleFrom(ex, ey, ez, e.p.pos(), reach));
    }

    private Pos feet() {
        return new Pos((int) Math.floor(io.x()), (int) Math.floor(io.y() + 0.1251), (int) Math.floor(io.z()));
    }

    // =====================================================================================
    // shared
    // =====================================================================================

    private TaskStatus finish() {
        io.holdStill();
        return summary();
    }

    /** The lane's last line ({@link #finish}; the watchdog's end, whose tick already has its input). SUCCESS. */
    private TaskStatus summary() {
        io.log("Lane " + lane.index() + ": " + placed + " placed, " + skipped + " skipped of " + entries.size() + " in "
            + now + " ticks; " + tracker.summary() + (stockWaits > 0 ? "; " + stockWaits + " t waiting for used-up stacks"
            : "") + "; " + io.pacer()
            + (spotWalks > 0 || spotPlaced > 0 ? "; " + spotPlaced + " from " + spotWalks + " stand spots" : "")
            + (stepUps > 0 ? "; " + stepUps + " jumps over the line's full blocks" : "")
            + (raisedHolds > 0 ? "; " + raisedHolds + " ticks held by a raised block beside (back to the line, no climb)" : "")
            + (sideClicks > 0 ? "; " + sideClicks + " LOOK clicks on a neighbor's side face" : "")
            + io.moveFence()
            + (skipped > 0 ? "; skipped: " + skipReasons : "")
            + (params.pacing().pacedWalk() ? "; paced walk, " + pacedHolds + " ticks held by the cap" : "")
            + (walkTicks > 0 ? idleSummary() : "")
            + (params.pacing().pacedWalk() ? standing.summary() : "")
            + (params.pacing().multiClick() ? "; fast placement: " + moreClicks + " more clicks in " + multiTicks
            + " ticks (at most " + maxPerTick + " in one), window " + params.pacing().windowMax() + " per "
            + (subTickOn() ? params.pacing().windowMillis() + " ms" : io.pacer().clickWindow().ticks() + " t")
            + " held clicks back in " + windowHolds + " ticks" : "")
            + (greedy() ? "; in-reach greedy: " + extrasPlaced + " cells of later lanes, " + earlyPlaced
            + " of this lane while walking to it, " + walkClicks + " clicks while Baritone walked (held back: "
            + walkNoHotbar.size() + " cells without their item in the hotbar, " + walkNearPath.size()
            + " full blocks near the path)" : ""));
        return TaskStatus.SUCCESS;
    }

    private TaskStatus fail(String reason) {
        failure = reason;
        return TaskStatus.FAILED;
    }

    /**
     * Fast placement ({@link PlacePacing#clicksPerTick}, {@code build.placeClicksPerTick}):
     * after this tick's first click, an airplace of this lane, up to {@code clicksPerTick − 1} more airplaces in the
     * same tick, all before the movement packet: candidates in the pipeline's order ({@code aimable}) that take the
     * held item from the held slot (no slot change after a click in a tick: Grim PacketOrderE), whose stack has items
     * left by the clicks in flight, that {@link #multiClickable} (they neither wall nor hide anything, so no rule
     * that weighs one block against the others of the tick applies), and that still pass the walk's own tests
     * ({@link #placeableNow}, {@link #heldBack}, {@link #inReachNow}) with the clicks of this tick in flight. Only
     * while the pacer runs at one tick (a slow-down turns it off by itself) and the click window allows
     * ({@link #windowAllows}). Each keeps its normal use-item-on and swing; the tick may share one swap pair.
     */
    private void moreClicks(List<Entry> aimable, double sp, double[] safe, int dir, boolean onTop, boolean sight,
                            double reach, double airReach) {
        PlacePacing pacing = params.pacing();
        if (!airOk || io.pacer().interval() > 1 || !io.pacer().fastPlacementOn()) return;
        int sent = 1;
        for (Entry e : aimable) {
            if (sent >= pacing.clicksPerTick()) break;
            if (e.st != State.TODO || e.left || e.extra || now < e.retryAt || !multiClickable(e)) continue;
            if (methodNow(e) != PlaceMethod.AIRPLACE) continue;
            String held = io.heldItem();
            if (held == null || !held.equals(env.item(e)) || left(io.heldSlot()) <= 0) continue;
            if (io.inventoryBusy() || !io.airPlaceReady()) break;
            if (!placeableNow(e, sp, safe, dir, onTop, sight) || heldBack(e) || inForwardPath(e)
                || !inReachNow(e, reach, airReach)) continue;
            if (!windowAllows()) {
                windowHeld();
                break;
            }
            if (!env.airPlace(e)) break;
            e.shared = true;
            sent++;
            if (!prepared.contains(e)) moreClicks++;
        }
        if (sent > 1 && prepared.isEmpty()) {
            multiTicks++;
            maxPerTick = Math.max(maxPerTick, sent);
        }
    }

    /**
     * Why a tick of the walk sent no click (the lane's last line):
     * nothing placeable in reach, the cells in reach waiting (stack used up by the clicks in flight, a retry not due),
     * an item to move in from the main inventory, an inventory request or the hotbar move fence in flight, the pacer's
     * gap or the click window, the input not ours, anything else (a LOOK aim, the airplace guard, a failed select).
     */
    private void idleTick(List<Entry> cands, List<Entry> aimable, PlacePipeline.Plan<Entry, S> plan, boolean moving) {
        int why;
        if (cands.isEmpty()) why = IDLE_NONE;
        else if (aimable.isEmpty()) why = IDLE_WAITING;
        else if (io.inventoryBusy()) why = IDLE_BUSY;
        else if (plan.inventoryMove() != null) why = IDLE_MOVE;
        else if (!io.pacer().ready(now, lastClick) || !windowAllows()) why = IDLE_PACER;
        else if (!io.canClick()) why = IDLE_INPUT;
        else why = IDLE_OTHER;
        idle[why]++;
        if (moving) idleWalking++;
    }

    /** The lane's last line: the walk's ticks without a click by reason ({@link #idleTick}). */
    private String idleSummary() {
        int n = 0;
        for (int v : idle) n += v;
        return "; walk ticks " + walkTicks + ", " + n + " without a click (" + idleWalking + " walking): "
            + idle[IDLE_NONE] + " none in reach, " + idle[IDLE_WAITING] + " used-up stack or retry, " + idle[IDLE_MOVE]
            + " item move, " + idle[IDLE_BUSY] + " inventory busy, " + idle[IDLE_PACER] + " pacer or window, "
            + idle[IDLE_INPUT] + " input, " + idle[IDLE_OTHER] + " other"
            + (wallBrakes > 0 ? "; braked " + wallBrakes + " ticks before the wall at the walk's end" : "")
            + (backWaits > 0 ? "; step backs waited " + backWaits + " ticks for blocks in flight" : "")
            + (fwdWaits > 0 ? "; the forward walk held " + fwdWaits + " ticks for side blocks in flight" : "");
    }

    /** First active click gate; cell waits only explain idle when there is no other clickable candidate. */
    private StandingTicks.Idle standingReason(List<Entry> cands, List<Entry> aimable,
                                              PlacePipeline.Plan<Entry, S> plan, double reach, double airReach) {
        if (io.clickInventoryBusy(true)) return StandingTicks.Idle.BUSY;
        if (env.inventoryBusy() || plan.inventoryMove() != null) return StandingTicks.Idle.MOVE;
        if (!aimable.isEmpty()) {
            if (!io.pacer().ready(now, lastClick)) return StandingTicks.Idle.PACER;
            if (!windowAllows()) return StandingTicks.Idle.WINDOW;
            return StandingTicks.Idle.OTHER; // input, LOOK aim, failed select or airplace guard
        }
        for (Entry e : cands) {
            if (now < e.retryAt) return StandingTicks.Idle.RETRY;
            if (io.fencedSlot() >= 0 && env.hotbarSlot(env.item(e)) < 0) return StandingTicks.Idle.MOVE;
            if (usedUp(e)) return StandingTicks.Idle.STACK;
        }
        for (Entry e : entries) {
            if (e.st == State.PENDING && inReachNow(e, reach, airReach)) return StandingTicks.Idle.ACK;
        }
        return StandingTicks.Idle.NONE;
    }

    /** Every resolved click measures drops; only shared exact-ack refusals feed the latch. */
    private void clickOutcome(Entry e, PlaceTracker.Outcome outcome) {
        String change = io.pacer().clickWindow().outcome(outcome, e.shared);
        if (change != null) io.log("Lane " + lane.index() + ": " + change);
        boolean refused = outcome == PlaceTracker.Outcome.REJECTED && io.ackedExactly(e.seq)
            && !io.echoedSince(e.p.pos(), e.echoMark);
        boolean shared = e.shared;
        e.shared = false;
        String restored = io.pacer().takeRestored();
        if (restored != null) io.log("Lane " + lane.index() + ": " + restored);
        if (shared && io.pacer().sharedOutcome(refused, io.nanoTime())) {
            io.log("Lane " + lane.index() + ": fast placement off: " + io.pacer().fastPlacementOff()
                + "; one click per tick from now on (build.placeClicksPerTick)");
        }
    }

    /**
     * Fast placement: a cell that may share its tick with other clicks: no full block, nothing taller than a carpet's
     * outline (it hides no cell) and no block the bot cannot stand on (carpets, pressure plates). Such a block changes
     * neither the walk's way, nor what can be seen or reached, nor the openness of any area.
     */
    private static boolean multiClickable(Entry e) {
        return !e.solid && !e.shades && !e.trap;
    }

    /**
     * Fast placement: another click fits the click window now: fewer than {@link PlacePacing#windowMax} lane clicks in
     * the last {@link PlacePacing#windowTicks} ticks, this one included. Always true with fast placement off.
     */
    private boolean windowAllows() {
        PlacePacing pacing = params.pacing();
        if (!pacing.multiClick()) return true;
        if (subTickOn()) return wallAllows() || canPrepare();
        // Retain the widest possible history so a widening cannot forget clicks still inside its new window.
        while (!clickTicks.isEmpty() && clickTicks.peekFirst() <= now - (pacing.windowTicks() + 1)) clickTicks.pollFirst();
        int count = 0;
        for (long tick : clickTicks) if (tick > now - io.pacer().clickWindow().ticks()) count++;
        return count < pacing.windowMax();
    }

    private boolean subTickOn() {
        return params.pacing().multiClick() && params.pacing().subTickDispatch() && io.subTickAvailable();
    }

    private boolean wallAllows() {
        PlacePacing p = params.pacing();
        return io.pacer().wallWindow().allows(io.nanoTime(), p.windowMillis(), p.windowMax())
            && (!io.rollingClickWindow() || rollingOpensAt() <= io.nanoTime());
    }

    /** Speculative gaps can shift Paper's anchor ahead of ours. Bound every rolling window as well. */
    private long rollingOpensAt() {
        return io.pacer().wallWindow().rollingOpensAt(io.nanoTime(), params.pacing().windowMillis(),
            params.pacing().windowMax());
    }

    private long windowOpensAt() {
        long at = io.pacer().wallWindow().opensAt(params.pacing().windowMillis());
        return io.rollingClickWindow() ? Math.max(at, rollingOpensAt()) : at;
    }

    private boolean canPrepare() {
        return subTickOn() && !ended && !parkRequested && !parked && phase == Phase.RUN
            && io.subTickAvailable() && io.pacer().interval() == 1 && io.pacer().fastPlacementOn()
            && prepared.size() < Math.min(params.pacing().clicksPerTick(), params.pacing().windowMax())
            && windowOpensAt() - tickStartedNanos < 50_000_000L;
    }

    private void cancelTimer() {
        dispatchGeneration++;
        if (cancelDispatch != null) cancelDispatch.run();
        cancelDispatch = null;
    }

    private void cancelPrepared() {
        cancelTimer();
        for (Entry e : prepared) if (e.st == State.PENDING) e.st = State.TODO;
        prepared.clear();
        preparedSlot = -1;
    }

    /** Inventory recovery must not leave a prepared between-tick click armed. */
    void suspendClicks() { cancelPrepared(); }

    /** Release/park never leaves clicks queued. Sent attempts remain with the normal tracker. */
    public void close() {
        ended = true;
        cancelPrepared();
    }

    /** Runs between complete ticks, or at the next tick's start before any new input or movement. */
    private void dispatchPrepared() {
        io.beginSubTick();
        try {
            dispatchPreparedBody();
        } catch (RuntimeException e) {
            fail("sub-tick dispatch failed: " + e);
            close();
            io.log("Lane " + lane.index() + ": " + failure);
        } finally {
            io.endSubTick();
        }
    }

    private void dispatchPreparedBody() {
        cancelTimer();
        if (!subTickOn() || ended || parkRequested || parked || !io.subTickReady() || !wallAllows()
            || io.pacer().interval() != 1 || !io.pacer().fastPlacementOn()
            || io.clickInventoryBusy(params.pacing().fenceClicks()) || !io.airPlaceReady()
            || io.heldSlot() != preparedSlot || preparedSlot == io.fencedSlot() || !io.slotKnown(preparedSlot)) {
            cancelPrepared();
            return;
        }
        int slot = preparedSlot;
        List<Entry> clicks = List.copyOf(prepared);
        prepared.clear();
        preparedSlot = -1;
        for (Entry e : clicks) e.st = State.TODO;
        int sent = 0;
        io.beginAirPlaces(params.pacing().airplaceBatch());
        try {
            // Airplaces have no aimed rotation. Recompute reach and box against the last completed movement.
            double sp = geo.s(io.x(), io.z());
            double step = Double.isNaN(prevX) ? 0 : Math.min(0.5,
                Math.sqrt(Math.pow(io.x() - prevX, 2) + Math.pow(io.y() - prevY, 2) + Math.pow(io.z() - prevZ, 2)));
            double range = params.airPlaceRange() - (params.reachStepMargin() ? step : 0) - 0.05;
            double[] safe = levels ? safeBox(io.x(), io.y(), io.z())
                : Aim.playerBox(io.x(), io.y(), io.z(), LaneWalk.SAFE_INFLATE);
            airOk = io.airPlaceReady();
            for (Entry e : clicks) {
                if (!wallAllows() || io.heldSlot() != slot || left(slot) <= 0
                    || io.clickInventoryBusy(params.pacing().fenceClicks())) break;
                if (!io.isLoaded(e.p.pos()) || io.blocksPlacement(e.p.pos()) || !multiClickable(e)
                    || !env.item(e).equals(io.heldItem()) || heldBack(e) || inBackPath(e) || inForwardPath(e)
                    || !placeableNow(e, sp, safe, reverse ? -1 : 1, false, lookLeft())
                    || !centreWithin(io.x(), io.eyeY(), io.z(), e, range)) continue;
                if (!sendAirPlace(e)) break;
                sent++;
                e.shared = true; // refusals of between-tick clicks also retain the fast-placement kill switch
                if (!dispatchAtTickStart) afterMovementSlot = slot;
            }
        } finally {
            io.endAirPlaces();
            if (sent > 1) {
                moreClicks += sent - 1;
                multiTicks++;
                maxPerTick = Math.max(maxPerTick, sent);
            }
        }
    }

    /** Account for a sent use even if its swing or closing swap then fails. */
    private boolean sendAirPlace(Entry e) {
        if (!io.slotKnown(io.heldSlot()) || io.heldSlot() == io.fencedSlot()) return false;
        long mark = io.echoMark();
        int before = io.airPlacesSent();
        boolean sent = false;
        try {
            sent = io.airPlace(e.p.pos());
            return sent;
        } finally {
            if (sent || io.airPlacesSent() > before) env.sent(e, mark);
        }
    }

    /** Fast placement: the click window held a click back this tick (counted once per tick, for the log). */
    private void windowHeld() {
        if (windowHeldAt == now) return;
        windowHeldAt = now;
        windowHolds++;
    }

    /** A click of this lane's or an extra waits for the server. */
    private boolean anyPending() {
        for (Entry e : entries) if (e.st == State.PENDING) return true;
        return !extrasInFlight.isEmpty();
    }

    /** Confirms, classifies ({@link PlaceTracker}) and pre-checks entries against the world (extras: those in flight). */
    private void updateEntries() {
        tracker.observeAck(io.ackedSequence(), now, slot -> io.hotbarCount(slot) <= 0);
        Map<String, Boolean> missing = new HashMap<>();
        for (Entry e : entries) update(e, missing);
        if (!extrasInFlight.isEmpty()) for (Entry e : List.copyOf(extrasInFlight)) update(e, missing);
    }

    /**
     * {@link #updateEntries} for one entry. An extra (in flight) is never skipped: placed, it is reported
     * ({@link Io#placedExtra}); given up or taken by another block, it is dropped silently and stays its own lane's.
     */
    private void update(Entry e, Map<String, Boolean> missing) {
        if (e.st == State.DONE || e.st == State.SKIPPED || prepared.contains(e)) return;
        Pos pos = e.p.pos();
        if (e.st == State.PENDING && e.echoMark >= 0
            && (io.echoedSince(pos, e.echoMark) || (e.seq >= 0 && io.ackedExactly(e.seq)))) {
            tracker.answered(e, now); // handled by the server (or refused by its anti-cheat): not dropped
        }
        if (!io.isLoaded(pos)) return; // far end of a long lane may load later; leftovers skipped at the end
        BlockSpec actual = io.block(pos);
        if (io.matches(e.p.block(), actual)) {
            PlaceTracker.Outcome outcome = tracker.placed(e, now);
            if (outcome != null && e.st == State.PENDING) clickOutcome(e, outcome);
            if (e.extra) {
                extraDone(e);
                return;
            }
            if (phase == Phase.SPOT) spotPlaced++;
            if (e.walkSent) earlyPlaced++;
            e.st = State.DONE;
            e.doneAt = now;
            placed++;
            if (greedy()) owe(e, -1);
            io.placed(e.p);
            if (e.breakIn) {
                int open = 0;
                for (Entry c : entries) if (c.breakIn && c != e && c.st != State.DONE && c.st != State.SKIPPED) open++;
                io.log("Lane " + lane.index() + ": break-in: " + pos + " placed" + (e.after.isEmpty() ? "" : " again")
                    + " (the bot at " + feet() + "); " + (open == 0 ? "the corridor is closed" : open
                    + " of the corridor still to place"));
            }
            return;
        }
        if (io.blocksPlacement(pos)) {
            tracker.forget(e);
            if (e.extra) dropExtra(e);
            else skip(e, e.st == State.PENDING ? "placed as " + actual : "occupied by " + actual);
            return;
        }
        if (e.st == State.PENDING) {
            PlaceTracker.Outcome failed = tracker.check(e, now);
            if (failed != null) { // rejected (old state kept), dropped (not handled), unknown (no answer): retry
                boolean dropped = failed == PlaceTracker.Outcome.DROPPED; // never handled: retried, not counted
                // an empty hand: says nothing about the cell, retried at once (it waits for a usable stack)
                boolean stackEnd = failed == PlaceTracker.Outcome.STACK_END;
                if ((dropped || stackEnd) && e.drops < MAX_DROPS) {
                    e.drops++;
                    e.sends--;
                }
                if (failed == PlaceTracker.Outcome.REJECTED) e.retryAt = now + tracker.retryDelay();
                else if (dropped) e.retryAt = now;
                clickOutcome(e, failed);
                if (e.extra) {
                    extrasInFlight.remove(e);
                    if (e.sends < MAX_SENDS) e.st = State.TODO;
                    else dropExtra(e);
                    return;
                }
                if (phase == Phase.RUN && e.stepBackSent) {
                    e.st = State.TODO;
                    if (!e.left) { e.left = true; leftBehind++; }
                } else if (e.sends < MAX_SENDS) e.st = State.TODO;
                else if (failed == PlaceTracker.Outcome.REJECTED || stackEnd) {
                    skip(e, "rejected by the server " + e.sends + " times");
                }
                else if (dropped) {
                    skip(e, "dropped by the server (acked without an answer) " + (e.sends + e.drops) + " times");
                }
                else skip(e, "not confirmed after " + e.sends + " attempts (no answer)");
            }
            return;
        }
        // TODO
        if (e.raised && !(params.mode() == PlaceMode.AIRPLACE && io.airPlaceReady())) {
            // a raised cell floats: nothing to click on without airplace; the raised level's own pass builds it
            skip(e, "a raised cell needs airplace");
            return;
        }
        // an airplace needs no support: a colour-suppression map's raised fillers float over air (docs/COLOR_SUPPRESSION.md)
        if (!io.blocksPlacement(pos.below()) && !(e.method == PlaceMethod.AIRPLACE && io.isLoaded(pos.below()))) {
            skip(e, "no support block below");
            return;
        }
        String id = ItemIds.ns(e.p.itemId());
        // not on the first zero: 2b2t follows a slot update with a full inventory update (MissingItemDebounce); items
        // in unknown hotbar slots are not missing, only unconfirmed until the full inventory answer (Io#unknownCount)
        if (missing.computeIfAbsent(id, k -> missingItems.missing(k, io.count(k) + io.unknownCount(k), now))) {
            skip(e, "missing item " + id);
        }
    }

    /**
     * An extra shows placed: reported if a click of this run went out for it (else another click did it, and its own
     * lane, which the engine starts with only the cells still to place, never sees it).
     */
    private void extraDone(Entry e) {
        boolean ours = e.sends > 0 || e.drops > 0;
        e.st = State.DONE;
        extrasInFlight.remove(e);
        owe(e, -1);
        if (!ours) return;
        extrasPlaced++;
        io.placedExtra(e.p);
    }

    /** An extra is given up (refused, taken by another block): its own lane places it. Never reported as skipped. */
    private void dropExtra(Entry e) {
        e.st = State.SKIPPED;
        extrasInFlight.remove(e);
        owe(e, -1);
    }

    /**
     * The stack {@code e} would be placed from is used up by clicks the server has not acknowledged yet: the cache shows
     * a hotbar stack's count one round trip late, and a click with an empty hand is rejected (on 2b2t nearly every
     * rejected click). The cell waits for the acks; once the cache shows the stack gone, the pipeline moves in a new one.
     */
    private boolean usedUp(Entry e) {
        String item = ItemIds.ns(e.p.itemId());
        if (params.pacing().stackSwitch()) return usableSlot(item) < 0 && io.hotbarSlots(item).length > 0;
        int slot = item.equals(io.heldItem()) ? io.heldSlot() : io.hotbarSlot(item);
        return slot >= 0 && left(slot) <= 0;
    }

    /**
     * Moves {@code item} into the hand ({@link Io#ensureHeld}); with {@link PlacePacing#belady} the move replaces the
     * hotbar stack whose item the cells left need last ({@link #nextUse}).
     */
    private void hold(String item) {
        if (tickSlotFence >= 0) { io.holdStill(); return; }
        // no stack to move in either (only in unknown or fenced slots, or used up by clicks in flight): the lane holds;
        // said once per item, so a hold that lasts is never silent (live 2026-10-15)
        if (!io.moveSource(item) && heldFor.add(item)) {
            io.log("Lane " + lane.index() + ": holding for " + item + ": no usable stack (" + io.itemState(item) + ")");
        }
        if (params.pacing().belady()) io.ensureHeld(item, nextUse());
        else io.ensureHeld(item);
    }

    /** Keep the prefetched material behind reachable hotbar cells so early loading does not reorder the queue. */
    private @Nullable String prefetchedItem;

    /** Earliest missing material in Belady order; never an extra or a left cell. */
    private void prefetch(List<Entry> cands) {
        if (tickSlotFence >= 0) return;
        if (io.lookaheadPrefetch()) {
            boolean[] protectedSlots = new boolean[9];
            for (Entry e : cands) for (int slot : io.hotbarSlots(ItemIds.ns(e.p.itemId())))
                protectedSlots[slot] = true;
            io.prefetchAhead(remainingOrder().stream().filter(e -> !e.left)
                .map(e -> ItemIds.ns(e.p.itemId())).toList(), this::left,
                slot -> io.slotKnown(slot) && !protectedSlots[slot] && tracker.inFlight(slot) == 0);
            return;
        }
        var order = nextUse();
        if (prefetchedItem != null && order.applyAsInt(prefetchedItem) == Integer.MAX_VALUE) prefetchedItem = null;
        if (prefetchedItem != null) return;
        String item = null;
        int first = Integer.MAX_VALUE;
        for (Entry e : entries) {
            if (e.st != State.TODO || e.left || now < e.retryAt) continue;
            String id = ItemIds.ns(e.p.itemId());
            int rank = order.applyAsInt(id);
            if (rank >= first || io.hotbarSlot(id) >= 0 || io.count(id) <= 0) continue;
            item = id;
            first = rank;
        }
        if (item != null) {
            boolean[] protectedSlots = new boolean[9];
            for (Entry e : cands) {
                for (int slot : io.hotbarSlots(ItemIds.ns(e.p.itemId()))) protectedSlots[slot] = true;
            }
            if (io.prefetchHeld(item, order, slot -> !protectedSlots[slot] && tracker.inFlight(slot) == 0)) {
                prefetchedItem = item;
            }
        }
    }

    /** Remaining cells along the walking direction, nearest the line first, stand-spot cells last. */
    private List<Entry> remainingOrder() {
        List<Entry> left = new ArrayList<>();
        for (Entry e : entries) if (e.st == State.TODO) left.add(e);
        left.sort(Comparator.<Entry>comparingInt(e -> e.left ? 1 : 0).thenComparingDouble(e -> reverse ? -e.s : e.s)
            .thenComparingDouble(e -> Math.abs(e.cell.lateral())));
        return left;
    }

    /** First remaining use per material; MAX_VALUE means no remaining use (Belady eviction). */
    private java.util.function.ToIntFunction<String> nextUse() {
        List<Entry> left = remainingOrder();
        Map<String, Integer> first = new HashMap<>();
        for (int i = 0; i < left.size(); i++) first.putIfAbsent(ItemIds.material(left.get(i).p.itemId()), i);
        return id -> first.getOrDefault(ItemIds.material(id), Integer.MAX_VALUE);
    }

    /** Items the stack in hotbar slot {@code slot} has left for new clicks: the cache's count less the clicks in flight. */
    private int left(int slot) {
        return io.hotbarCount(slot) - tracker.inFlight(slot) - (slot == preparedSlot ? prepared.size() : 0);
    }

    /**
     * The stack switch ({@link PlacePacing#stackSwitch}): the hotbar slot to click {@code item} from, the held slot if
     * its stack has items left ({@link #left}), else the first other slot with {@code item} that has; -1 if none has
     * (all used up by clicks in flight, or {@code item} is not in the hotbar).
     */
    private int usableSlot(String item) {
        int held = io.heldSlot();
        int[] slots = io.hotbarSlots(item); // by material: a plan id and the cache's id may differ (eyeblossoms)
        for (int s : slots) if (s == held && s != io.fencedSlot() && io.slotKnown(s) && left(s) > 0) return s;
        for (int s : slots) if (s != held && s != io.fencedSlot() && io.slotKnown(s) && left(s) > 0) return s;
        return -1;
    }

    /**
     * {@code cells} without the ones whose stack is used up by clicks in flight ({@link #usedUp}) and the rejected ones
     * still waiting for their retry.
     */
    private List<Entry> clickable(List<Entry> cells) {
        List<Entry> out = new ArrayList<>(cells.size());
        Map<String, Boolean> usedUp = new HashMap<>();
        boolean stock = false;
        for (Entry e : cells) {
            if (now < e.retryAt) continue;
            // An aimed stale slot must not hold the pipeline ahead of safe cells (LOOK and AIRPLACE).
            if (io.fencedSlot() >= 0 && env.hotbarSlot(ItemIds.ns(e.p.itemId())) < 0) continue;
            if (usedUp.computeIfAbsent(ItemIds.ns(e.p.itemId()), k -> usedUp(e))) stock = true;
            else out.add(e);
        }
        if (stock) stockWaits++;
        // Keep the existing hotbar-first order: loading a later material early must not promote it.
        // Drain other reachable hotbar cells before consuming the prefetched material.
        if (prefetchedItem != null && out.stream().anyMatch(e -> !prefetchedItem.equals(ItemIds.ns(e.p.itemId()))
            && env.hotbarSlot(ItemIds.ns(e.p.itemId())) >= 0)) {
            out.removeIf(e -> prefetchedItem.equals(ItemIds.ns(e.p.itemId())));
        }
        return out;
    }

    /**
     * {@code e} is given up for this run (VERIFY re-plans it). Logged once per cell with its reason, the first
     * {@link #SKIP_LOG_MAX} of a run one by one; the lane's last line counts the reasons of all of them.
     */
    private void skip(Entry e, String reason) {
        e.st = State.SKIPPED;
        e.unreachable = reason.startsWith("not reachable") || reason.startsWith("no progress");
        skipped++;
        if (greedy()) owe(e, -1);
        skipReasons.merge(reason, 1, Integer::sum);
        if (skipLogs++ < SKIP_LOG_MAX) {
            String what = e.p.block() == null ? e.p.itemId() : ItemIds.path(e.p.block().id());
            io.log("Lane " + lane.index() + ": skipped " + e.p.pos() + " (" + what + "): " + reason);
        }
        io.skipped(e.p, reason);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    /** {@link PlacePipeline.Env} for the current tick ({@link #begin} first). */
    private final class Env implements PlacePipeline.Env<Entry, S> {
        private double reach;
        private double sp;
        /** Baritone walks: airplaces only, gated by {@link Io#clickWhileWalking} (in-reach greedy). */
        private boolean walking;

        void begin(double reach, double sp) {
            this.reach = reach;
            this.sp = sp;
            this.walking = false;
        }

        /** This tick places while Baritone walks ({@link #placeWhileWalking}). */
        void beginWalking(double reach) {
            this.reach = reach;
            this.sp = Double.NaN;
            this.walking = true;
        }

        @Override
        public @Nullable S aim(Entry e) {
            S s = io.aim(e.support, reach);
            if (s == null && e.side) s = io.aimSide(e.p.pos(), reach); // the top face hidden (class javadoc "Side faces")
            if (Double.isNaN(sp)) {
                if (s == null) e.spotMisses++;
                return s;
            }
            if (s == null) {
                e.losMisses++;
                e.losMissS = sp;
                // hidden, not just at the reach edge: the face centre is in reach
                Pos c = e.support;
                if (Aim.distSq(io.x(), io.eyeY(), io.z(), c.x() + 0.5, c.y() + 1.0, c.z() + 0.5) <= reach * reach) {
                    e.hiddenMisses++;
                }
            } else {
                e.losMisses = 0;
                e.hiddenMisses = 0;
            }
            return s;
        }

        @Override
        public @Nullable S recheck(Entry e) {
            S s = io.recheck(e.support, reach);
            e.sideShot = false;
            if (s == null && e.side) {
                s = io.recheckSide(e.p.pos(), reach);
                e.sideShot = s != null;
            }
            return s;
        }

        @Override
        public String item(Entry e) {
            return ItemIds.ns(e.p.itemId());
        }

        /**
         * The held item; with the stack switch none while the held stack is used up by clicks in flight and another
         * hotbar stack of the item has items left (the pipeline then selects that one, {@link #hotbarSlot}).
         */
        @Override
        public @Nullable String heldItem() {
            String held = io.heldItem();
            if (io.heldSlot() == io.fencedSlot() || !io.slotKnown(io.heldSlot())) return null;
            if (held != null && params.pacing().stackSwitch() && left(io.heldSlot()) <= 0 && usableSlot(held) >= 0) {
                return null;
            }
            return held;
        }

        @Override
        public int heldSlot() {
            return io.heldSlot();
        }

        /** With the stack switch the slot with items left ({@link #usableSlot}) if there is one. */
        @Override
        public int hotbarSlot(String item) {
            if (params.pacing().stackSwitch()) {
                int s = usableSlot(item);
                if (s >= 0) return s;
            }
            for (int s : io.hotbarSlots(item)) if (s != io.fencedSlot() && io.slotKnown(s)) return s;
            return -1;
        }

        @Override
        public boolean ownedLastTick() {
            return io.ownedLastTick();
        }

        @Override
        public boolean inventoryBusy() {
            return io.clickInventoryBusy(params.pacing().fenceClicks());
        }

        /** The pacer allows a click now, and with fast placement the click window ({@link #windowAllows}) too. */
        @Override
        public boolean pacerReady() {
            if (!io.pacer().ready(now, lastClick)) return false;
            if (windowAllows()) return true;
            windowHeld();
            return false;
        }

        @Override
        public boolean canClick() {
            return walking ? io.clickWhileWalking() : io.canClick();
        }

        @Override
        public boolean select(int slot) {
            if (!io.slotKnown(slot) || (tickSlotFence >= 0 && slot != tickSlotFence)) return false;
            return io.select(slot);
        }

        @Override
        public PlaceMethod method(Entry e) {
            return methodNow(e);
        }

        @Override
        public boolean airPlace(Entry e) {
            if (subTickOn() && (!wallAllows() || !prepared.isEmpty())) {
                if (!canPrepare() || walking || e.extra || !multiClickable(e)) return false;
                if (preparedSlot >= 0 && preparedSlot != io.heldSlot()) return false;
                preparedSlot = io.heldSlot();
                e.st = State.PENDING;
                prepared.add(e);
                if (cancelDispatch == null) {
                    long generation = ++dispatchGeneration;
                    cancelDispatch = io.scheduleClick(windowOpensAt(),
                        () -> { if (generation == dispatchGeneration) dispatchPrepared(); });
                }
                return true;
            }
            return sendAirPlace(e);
        }

        @Override
        public boolean click(Entry e, S shot) {
            if (!io.slotKnown(io.heldSlot()) || io.heldSlot() == io.fencedSlot()) return false;
            if (subTickOn() && !wallAllows()) return false;
            long mark = io.echoMark();
            if (!io.click(shot)) return false;
            if (e.sideShot) sideClicks++;
            sent(e, mark);
            return true;
        }

        /**
         * {@code e}'s use-item-on went out; {@code mark}: the block update mark taken before it. Either way the server
         * echoes the cell itself (next to a clicked support's top face, or the clicked cell of an airplace).
         */
        private void sent(Entry e, long mark) {
            int seq = io.clickSequence();
            int slot = io.heldSlot();
            tracker.sent(e, now, seq, slot, mark >= 0, left(slot)); // left: before this click is in flight
            e.seq = seq;
            e.echoMark = mark;
            e.st = State.PENDING;
            e.sentAt = now;
            e.sends++;
            if (!e.extra && !stepBackCells.isEmpty() && e.s < stepBackFrom) {
                e.stepBackTried = true;
                e.stepBackSent = true;
                stepBackCells.add(e);
            }
            e.walkSent = walking;
            e.shared = false; // moreClicks marks the second and later clicks of a tick
            sentClicks++;
            if (walking) walkClicks++;
            if (ItemIds.ns(e.p.itemId()).equals(prefetchedItem)) prefetchedItem = null;
            lastClick = now; // the pacer spaces every click
            if (params.pacing().multiClick()) {
                io.pacer().wallWindow().sent(io.clickNanoTime(), params.pacing().windowMillis());
                clickTicks.addLast(now);
                while (!clickTicks.isEmpty() && clickTicks.peekFirst() <= now - (params.pacing().windowTicks() + 1))
                    clickTicks.pollFirst();
            }
            if (e.extra) {
                extrasInFlight.add(e);
            } else {
                lastOwnClick = now;
                lastActivity = now;
            }
        }
    }
}
