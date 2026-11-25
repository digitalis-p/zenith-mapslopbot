package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.ContainerSim;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.ContainerSim.GLASS;
import static io.github.digitalisp.mapart.testutil.ContainerSim.STONE;

/**
 * Buttons, levers and note blocks ({@link UseRun} on a simulated world): where the bot stands, look first and click on
 * the next tick, the state change it waits for, and the clear sweep it keeps out of. Button mode (the map counter):
 * only a button, never while it is still pressed, and the sweep allowed off the art layer.
 */
class UseRunTest {
    private static final double REACH = 4.3;

    private static ContainerSim floor(long seed) {
        ContainerSim s = new ContainerSim(seed);
        s.fill(-12, 63, -12, 12, 63, 12, STONE);
        return s;
    }

    private static UseRun<ContainerSim.Shot> use(ContainerSim s, Pos pos, List<Pos> standAt, Pos preferred) {
        s.targets = List.of(pos);
        return new UseRun<>(pos, standAt, preferred, REACH, s);
    }

    private static void assertCleanClick(ContainerSim s) {
        assertEquals(0, s.rotateAndClick, "no rotation change in the click tick");
        assertEquals(0, s.clickWhileMoving, "no movement keys in the click tick");
        assertEquals(0, s.clickOffRotation, "clicked with the rotation in effect");
        assertEquals(s.clickTick - 1, s.aimedTick, "looked on the tick before the click");
    }

    private static TaskStatus run(ContainerSim s, UseRun<ContainerSim.Shot> u, int maxTicks) {
        return run(s, u, maxTicks, t -> { });
    }

    /** {@link #run}, with {@code others} changing the world at the start of each tick (someone else, the server). */
    private static TaskStatus run(ContainerSim s, UseRun<ContainerSim.Shot> u, int maxTicks, LongConsumer others) {
        for (long t = 1; t <= maxTicks; t++) {
            s.beginTick(t);
            others.accept(t);
            TaskStatus st = u.tick(t);
            s.endTick();
            if (st != TaskStatus.RUNNING) return st;
        }
        return TaskStatus.FAILED;
    }

    @Test
    void buttonBehindAnObstructionIsPressedFromWhereItsFaceCentreIsInSight() {
        ContainerSim s = floor(31);
        s.fill(0, 64, 0, 0, 66, 0, STONE);
        Pos button = new Pos(1, 65, 0);
        s.set(button, ContainerSim.button("wall", "east"));
        s.fill(3, 64, -4, 3, 66, 4, GLASS); // between the bot and the button
        s.teleport(5.5, 64, 0.5);
        UseRun<ContainerSim.Shot> u = use(s, button, List.of(), null);
        assertFalse(s.visibleFrom(5.5, 64 + Aim.EYE_HEIGHT, 0.5, REACH), "the glass hides it from the start");
        assertEquals(TaskStatus.SUCCESS, run(s, u, 1500), u.failure() + " " + s.log);
        assertEquals("true", s.block(button).props().get("powered"));
        assertTrue(u.standingCell().x() < 3, "walked round the glass: " + u.standingCell());
        // the nearest spot by walking distance round the glass may see the button's east face or its narrow side face
        assertTrue(s.lastClick.face() != Aim.WEST && s.lastClick.face() != Aim.DOWN,
            "a face whose center is in sight: " + s.lastClick.face());
        assertEquals(1, s.clicks);
        assertEquals(1, u.approach().walks());
        assertCleanClick(s);
    }

    @Test
    void noteBlockRightClickOnlyCyclesThePitchAndTheObserverFires() {
        ContainerSim s = floor(32);
        Pos note = new Pos(0, 64, 0);
        s.set(note, ContainerSim.noteBlock(24));
        s.set(0, 64, -1, ContainerSim.observer("south")); // watches the note block
        s.set(1, 64, -1, ContainerSim.observer("south")); // watches something else
        s.teleport(0.5, 64, 2.5);
        UseRun<ContainerSim.Shot> u = use(s, note, List.of(), null);
        assertEquals(TaskStatus.SUCCESS, run(s, u, 100), u.failure());
        assertEquals("0", s.block(note).props().get("note"), "24 wraps to 0: a change all the same");
        assertEquals("harp", s.block(note).props().get("instrument"), "instrument unchanged");
        assertEquals("false", s.block(note).props().get("powered"), "not powered by the click");
        assertEquals(1, s.observerFires, "exactly the observer facing it fires, once");
        assertEquals(1, s.clicks, "pressed like a button: one click");
        assertEquals(0, u.approach().walks(), "in sight from where the bot stands");
        assertCleanClick(s);
    }

    @Test
    void leverIsFlippedOnHeldAndFlippedBackFromTheSameSpot() {
        ContainerSim s = floor(33);
        Pos lever = new Pos(0, 64, 0);
        s.set(lever, ContainerSim.lever("floor", "north"));
        Pos standAt = new Pos(2, 64, 0);
        s.teleport(8.5, 64, 5.5);
        // the engine: flip on, wait holdTicks, flip back (two presses with a wait between)
        UseRun<ContainerSim.Shot> on = use(s, lever, List.of(standAt), null);
        assertEquals(TaskStatus.SUCCESS, run(s, on, 1000), on.failure());
        assertEquals("true", s.block(lever).props().get("powered"));
        assertEquals(standAt, on.standingCell());
        for (long t = 1; t <= 40; t++) { // held
            s.beginTick(t);
            s.holdStill();
            s.endTick();
        }
        assertEquals("true", s.block(lever).props().get("powered"), "still on during the hold");
        UseRun<ContainerSim.Shot> off = use(s, lever, List.of(standAt), on.standingCell());
        assertEquals(TaskStatus.SUCCESS, run(s, off, 100), off.failure());
        assertEquals("false", s.block(lever).props().get("powered"));
        assertEquals(0, off.approach().walks(), "flipped back from where it stands");
        assertEquals(2, s.clicks);
        assertCleanClick(s);
    }

    @Test
    void standAtIsPreferredOverWhereTheBotAlreadySeesTheButton() {
        ContainerSim s = floor(34);
        Pos button = new Pos(0, 64, 0);
        s.set(button, ContainerSim.button("floor", "north"));
        s.teleport(0.5, 64, 2.5);
        s.targets = List.of(button);
        assertTrue(s.visibleFrom(0.5, 64 + Aim.EYE_HEIGHT, 2.5, REACH), s.hiddenFrom(0.5, 64 + Aim.EYE_HEIGHT, 2.5, REACH));
        // standAt naming the block stood on also works (the cell above it)
        Pos standAt = new Pos(3, 63, 0);
        UseRun<ContainerSim.Shot> u = use(s, button, List.of(standAt), null);
        assertEquals(TaskStatus.SUCCESS, run(s, u, 600), u.failure());
        assertEquals(new Pos(3, 64, 0), u.standingCell(), "pressed from standAt");
        assertEquals(1, u.approach().walks());
        assertCleanClick(s);
    }

    @Test
    void anUnusableStandAtIsLoggedAndAnotherSpotIsUsed() {
        ContainerSim s = floor(35);
        Pos button = new Pos(0, 64, 0);
        s.set(button, ContainerSim.button("floor", "north"));
        Pos standAt = new Pos(3, 64, 0);
        s.set(3, 65, 0, STONE); // no head room there
        s.teleport(6.5, 64, 6.5);
        UseRun<ContainerSim.Shot> u = use(s, button, List.of(standAt), null);
        assertEquals(TaskStatus.SUCCESS, run(s, u, 600), u.failure());
        assertNotEquals(standAt, u.standingCell());
        assertTrue(s.log.stream().anyMatch(l -> l.contains("configured stand cell " + standAt) && l.contains("cannot stand there")),
            s.log.toString());
    }

    @Test
    void aLostClickIsRepeatedOnceThenItFails() {
        ContainerSim s = floor(36);
        Pos button = new Pos(0, 64, 0);
        s.set(button, ContainerSim.button("floor", "north"));
        s.teleport(0.5, 64, 2.5);
        s.dropClicks = 1;
        UseRun<ContainerSim.Shot> u = use(s, button, List.of(), null);
        assertEquals(TaskStatus.SUCCESS, run(s, u, 200), u.failure());
        assertEquals(2, s.clicks);
        assertCleanClick(s);

        ContainerSim s2 = floor(37);
        s2.set(button, ContainerSim.button("floor", "north"));
        s2.teleport(0.5, 64, 2.5);
        s2.dropClicks = 5;
        UseRun<ContainerSim.Shot> u2 = use(s2, button, List.of(), null);
        assertEquals(TaskStatus.FAILED, run(s2, u2, 200));
        assertTrue(u2.failure().endsWith("did not react"), u2.failure());
        assertEquals(UseRun.MAX_ATTEMPTS, s2.clicks);
    }

    @Test
    void itMayParkWhileWalkingButNotOnceItAims() {
        ContainerSim s = floor(38);
        Pos button = new Pos(0, 64, 0);
        s.set(button, ContainerSim.button("floor", "north"));
        s.teleport(9.5, 64, 9.5);
        UseRun<ContainerSim.Shot> u = use(s, button, List.of(), null);
        assertFalse(u.canPark(), "not before it walks");
        long t = 0;
        int parkable = 0;
        TaskStatus st = TaskStatus.RUNNING;
        while (st == TaskStatus.RUNNING && t < 1000) {
            s.beginTick(++t);
            if (u.canPark()) {
                parkable++;
                assertEquals(0, s.clicks, "parkable only before the click");
            }
            st = u.tick(t);
            s.endTick();
        }
        assertEquals(TaskStatus.SUCCESS, st, u.failure());
        assertTrue(parkable > 0, "parkable during the walk");
        assertFalse(u.canPark());

        // a flip back from where the bot stands is never parkable
        ContainerSim s2 = floor(39);
        Pos lever = new Pos(0, 64, 0);
        s2.set(lever, ContainerSim.lever("floor", "north"));
        s2.teleport(2.5, 64, 0.5);
        UseRun<ContainerSim.Shot> back = use(s2, lever, List.of(new Pos(2, 64, 0)), null);
        for (long k = 1; k <= 30; k++) {
            s2.beginTick(k);
            assertFalse(back.canPark(), "tick " + k);
            if (back.tick(k) != TaskStatus.RUNNING) break;
            s2.endTick();
        }
        assertEquals("true", s2.block(lever).props().get("powered"));
    }

    // ------------------------------------------------------------------------------------------ the clear sweep

    /** A tile whose art layer is y 64 (floor 63) around the origin. */
    private static final PlatformGeometry TILE = new PlatformGeometry(-64, -64, 64);

    @Test
    void theClearSweepCoversTheArtLayerAndWhereTheMachinesParkNotTheCornersBesideIt() {
        Predicate<Pos> ok = StandSpots.awayFromClearSweeps(List.of(TILE));
        assertFalse(ok.test(new Pos(0, 64, 0)), "art layer");
        assertFalse(ok.test(new Pos(0, 65, 0)), "on a full block of the art layer");
        assertFalse(ok.test(new Pos(0, 68, 0)), "4 above the art layer: machine height");
        assertTrue(ok.test(new Pos(0, 69, 0)), "5 above: over the machines (the moss center structure is +6)");
        assertTrue(ok.test(new Pos(0, 62, 0)), "under the floor");
        assertFalse(ok.test(new Pos(63 + 10, 65, 0)), "east of the tile, in its rows: machine parking");
        assertFalse(ok.test(new Pos(0, 65, -64 - 5)), "north of the tile, in its columns");
        assertTrue(ok.test(new Pos(63 + 3, 65, -64 - 4)), "the corner beside it (the moss collection switches)");
        assertTrue(ok.test(new Pos(63 + 17, 65, 0)), "beyond the margin");
    }

    /**
     * A button on a pillar that is in reach both from the art layer and from a structure above the machines, reached
     * by stairs from the art layer.
     */
    private static Pos pillarWithStructure(ContainerSim s) {
        s.fill(-12, 63, -12, 12, 63, 12, BlockSpecs.MOSS);
        s.fill(0, 64, 0, 0, 67, 0, GLASS);
        Pos button = new Pos(1, 67, 0);
        s.set(button, ContainerSim.button("wall", "east"));
        s.fill(2, 68, -1, 4, 68, 1, GLASS); // the structure: feet 69, 5 above the art layer
        for (int i = 0; i < 4; i++) s.fill(8 - i, 64, 0, 8 - i, 64 + i, 0, STONE); // stairs up from the east
        s.targets = List.of(button);
        return button;
    }

    @Test
    void withoutTheSweepRuleTheArtLayerWouldBeChosen() {
        ContainerSim s = new ContainerSim(39);
        pillarWithStructure(s);
        StandSpots.Search open = StandSpots.search(s, s, s.targets, REACH, 10.5, 64, 0.5, null, Set.of(), 4);
        assertEquals(64, open.spots().getFirst().y(), "nearest: the art layer");
        s.allowedCells = StandSpots.awayFromClearSweeps(List.of(TILE));
        StandSpots.Search safe = StandSpots.search(s, s, s.targets, REACH, 10.5, 64, 0.5, null, Set.of(), 4);
        assertFalse(safe.spots().isEmpty(), safe.describe(REACH));
        for (StandSpots.Spot sp : safe.spots()) assertEquals(69, sp.y(), "on the structure: " + sp);
    }

    @Test
    void aButtonIsPressedFromTheStructureNotFromTheArtLayer() {
        ContainerSim s = new ContainerSim(40);
        Pos button = pillarWithStructure(s);
        s.allowedCells = StandSpots.awayFromClearSweeps(List.of(TILE));
        s.teleport(3.5, 64, 2.5); // on the art layer, the button in sight and in reach
        assertTrue(s.visibleFrom(3.5, 64 + Aim.EYE_HEIGHT, 2.5, REACH));
        UseRun<ContainerSim.Shot> u = new UseRun<>(button, List.of(), null, REACH, s);
        assertEquals(TaskStatus.SUCCESS, run(s, u, 2000), u.failure() + " " + s.log);
        assertEquals("true", s.block(button).props().get("powered"));
        assertEquals(69, u.standingCell().y(), "pressed from above the machines: " + u.standingCell());
        assertCleanClick(s);
    }

    // ------------------------------------------------------------------------------------- button mode (map counter)

    private static UseRun<ContainerSim.Shot> press(ContainerSim s, Pos pos, List<Pos> standAt, Pos preferred) {
        s.targets = List.of(pos);
        return UseRun.pressButton(pos, standAt, preferred, REACH, s);
    }

    /** An oak button on the floor facing north (a wooden button pops after 30 ticks). */
    private static io.github.digitalisp.mapart.model.BlockSpec oakButton(boolean powered) {
        return io.github.digitalisp.mapart.model.BlockSpec.of("minecraft:oak_button",
            Map.of("face", "floor", "facing", "north", "powered", String.valueOf(powered)));
    }

    private static boolean misses(ContainerSim s) {
        return s.log.stream().anyMatch(l -> l.startsWith("Cannot use"));
    }

    @Test
    void anAlreadyPressedButtonIsWaitedForAndThenPressedOnce() {
        ContainerSim s = floor(41);
        Pos button = new Pos(0, 64, 0);
        s.set(button, oakButton(true)); // pressed by someone else; it pops on tick 30
        s.teleport(0.5, 64, 2.5);
        UseRun<ContainerSim.Shot> u = press(s, button, List.of(), null);
        boolean[] waited = {false};
        TaskStatus st = run(s, u, 300, t -> {
            if (t == 30) s.set(button, oakButton(false));
            if (t == 20) waited[0] = u.progress().contains("waiting for it to pop");
        });
        assertEquals(TaskStatus.SUCCESS, st, u.failure() + " " + s.log);
        assertTrue(waited[0], "aimed and waiting while pressed");
        assertEquals(1, s.clicks, "exactly one click");
        assertTrue(s.clickTick >= 30 && s.clickTick <= 31, "sent once it popped, not before: tick " + s.clickTick);
        assertEquals(1, s.uses.size(), s.uses.toString());
        assertEquals("true", s.block(button).props().get("powered"));
        assertEquals(0, u.approach().walks(), "waited where it stood");
        assertFalse(misses(s), "the wait is no miss: " + s.log);
        assertEquals(0, s.rotateAndClick);
        assertEquals(0, s.clickWhileMoving);
        assertEquals(0, s.clickOffRotation);
    }

    @Test
    void aButtonThatStaysPressedFailsWithoutAClick() {
        ContainerSim s = floor(42);
        Pos button = new Pos(0, 64, 0);
        s.set(button, oakButton(true)); // held: an arrow in a wooden button, or a stale state
        s.teleport(0.5, 64, 2.5);
        UseRun<ContainerSim.Shot> u = press(s, button, List.of(), null);
        long[] last = {0};
        assertEquals(TaskStatus.FAILED, run(s, u, 400, t -> last[0] = t));
        assertEquals("the button at " + button + " stayed pressed for 5 s", u.failure());
        assertEquals(UseRun.PRESSED_TICKS, last[0], "failed after 5 s");
        assertEquals(0, s.clicks);
        assertEquals(0, u.approach().walks(), "kept its spot while waiting");
        assertFalse(misses(s), s.log.toString());
    }

    @Test
    void inButtonModeAnythingButAButtonFailsAtOnce() {
        Pos pos = new Pos(0, 64, 0);
        for (var b : List.of(ContainerSim.lever("floor", "north"), ContainerSim.noteBlock(3), STONE)) {
            ContainerSim s = floor(43);
            s.set(pos, b);
            s.teleport(0.5, 64, 2.5);
            UseRun<ContainerSim.Shot> u = press(s, pos, List.of(), null);
            s.beginTick(1);
            assertEquals(TaskStatus.FAILED, u.tick(1), b.id());
            s.endTick();
            assertEquals(b.id() + " at " + pos + " is not a button", u.failure());
            assertEquals(0, s.clicks, b.id());
        }
    }

    @Test
    void aLateAnswerToTheFirstClickIsNotPressedAgain() {
        ContainerSim s = floor(44);
        Pos button = new Pos(0, 64, 0);
        s.set(button, ContainerSim.button("floor", "north"));
        s.teleport(0.5, 64, 2.5);
        s.dropClicks = 1; // the server answers the first click after the bot gave up waiting (lag)
        UseRun<ContainerSim.Shot> u = press(s, button, List.of(), null);
        TaskStatus st = run(s, u, 300, t -> {
            if (s.clicks == 1 && t == s.clickTick + UseRun.REACT_TICKS + 1) {
                s.set(button, io.github.digitalisp.mapart.model.BlockSpec.of("minecraft:stone_button",
                    Map.of("face", "floor", "facing", "north", "powered", "true")));
            }
        });
        assertEquals(TaskStatus.SUCCESS, st, u.failure());
        assertEquals(1, s.clicks, "no second press once it shows pressed");
    }

    /**
     * The map counter on the supply wall north of the tile: the walkway in front of it lies in the clear sweep, and
     * the art layer is in reach too. The bot starts on the art layer with the button in sight.
     */
    private static Pos counterBesideTheWalkway(ContainerSim s) {
        s.fill(-8, 63, -72, 8, 63, -56, STONE); // the walkway (z -67..-65) and the tile's floor (from z -64)
        s.fill(-8, 64, -68, 8, 66, -68, STONE); // the supply wall
        Pos button = new Pos(0, 65, -67);
        s.set(button, ContainerSim.button("wall", "south"));
        s.teleport(0.5, 64, -63.5);
        s.targets = List.of(button);
        return button;
    }

    @Test
    void theCounterInTheSweepIsPressedFromTheWalkwayNeverFromTheArtLayer() {
        StandSpots.CellRule rule = UseRun.buttonStandCells(List.of(TILE));
        assertEquals("on the art layer", rule.refused());

        ContainerSim s = new ContainerSim(45);
        Pos button = counterBesideTheWalkway(s);
        assertTrue(StandSpots.onArtLayer(TILE, StandSpots.feetCell(s.x, s.y, s.z)), "starts on the art layer");
        assertTrue(s.visibleFrom(s.x, s.y + Aim.EYE_HEIGHT, s.z, REACH), "in reach and in sight from there");
        s.allowedCells = rule;
        UseRun<ContainerSim.Shot> u = press(s, button, List.of(), null);
        assertEquals(TaskStatus.SUCCESS, run(s, u, 1500), u.failure() + " " + s.log);
        assertEquals("true", s.block(button).props().get("powered"));
        assertEquals(1, s.clicks);
        Pos stood = u.standingCell();
        assertFalse(StandSpots.onArtLayer(TILE, stood), "not from the art layer: " + stood);
        assertTrue(stood.z() < TILE.minZ() && stood.y() == 64, "on the walkway: " + stood);
        assertFalse(StandSpots.awayFromClearSweeps(List.of(TILE)).test(stood), "the spot lies in the clear sweep");
        assertEquals(1, u.approach().walks());
        assertCleanClick(s);

        // the presses' sweep rule refuses every cell there
        ContainerSim s2 = new ContainerSim(46);
        counterBesideTheWalkway(s2);
        s2.allowedCells = StandSpots.awayFromClearSweeps(List.of(TILE));
        UseRun<ContainerSim.Shot> u2 = press(s2, button, List.of(), null);
        assertEquals(TaskStatus.FAILED, run(s2, u2, 300));
        assertTrue(u2.failure().startsWith("cannot reach " + button + ": no stand spot: "), u2.failure());
        assertTrue(u2.failure().endsWith(" floor cells within reach excluded: inside the clear sweep"), u2.failure());
        assertEquals(0, s2.clicks);
    }

    /** Block states used here. */
    private static final class BlockSpecs {
        static final io.github.digitalisp.mapart.model.BlockSpec MOSS = io.github.digitalisp.mapart.model.BlockSpec.of("minecraft:moss_block");
    }
}
