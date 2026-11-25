package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The boxed-in escape: {@link LocalReach} tells a boxed-in spot, {@link TrackBack} walks the bot's track back out. */
class BoxedInTest {
    /** 22 movements tried per cell, as ZenithProxy's A* does; the map gives each cell's ways on. */
    private static LocalReach.Expander graph(Map<Pos, List<Pos>> ways) {
        return (from, to) -> {
            ways.getOrDefault(from, List.of()).forEach(to);
            return 22;
        };
    }

    private static Pos p(int x, int y, int z) {
        return new Pos(x, y, z);
    }

    @Test
    void twoCellPocketIsBoxedInWithFortyFourMovements() {
        // the live case: two cells on a parked machine, every way out refused by the pathfinder
        Pos a = p(10, 76, 38);
        Pos b = p(10, 76, 37);
        LocalReach.Result r = LocalReach.explore(a, graph(Map.of(a, List.of(b), b, List.of(a))),
            LocalReach.BOXED_IN_MOVEMENTS);
        assertTrue(r.exhausted());
        assertEquals(44, r.movements());
        assertEquals(Set.of(a, b), r.cells());
        assertTrue(r.boxedIn());
    }

    @Test
    void aSmallSearchBesideAnUnloadedChunkProvesNothing() {
        // after a login the neighbour chunk may not be there yet: its moves are skipped, so the search stays small
        Pos a = p(15, 64, 3);
        LocalReach.Expander edge = new LocalReach.Expander() {
            @Override
            public int expand(Pos from, java.util.function.Consumer<Pos> to) {
                return 11;
            }

            @Override
            public boolean skippedUnloaded() {
                return true;
            }
        };
        LocalReach.Result r = LocalReach.explore(a, edge, LocalReach.BOXED_IN_MOVEMENTS);
        assertTrue(r.exhausted());
        assertFalse(r.boxedIn());
    }

    @Test
    void openGroundIsNotBoxedIn() {
        // a corridor longer than the cap: the search stops at the cap with cells left
        LocalReach.Expander line = (from, to) -> {
            to.accept(p(from.x() + 1, from.y(), from.z()));
            return 22;
        };
        LocalReach.Result r = LocalReach.explore(p(0, 64, 0), line, LocalReach.BOXED_IN_MOVEMENTS);
        assertFalse(r.exhausted());
        assertFalse(r.boxedIn());
    }

    @Test
    void anEnclosureOfFiveCellsOrMoreIsNotTiny() {
        // all searched, but 5 cells x 22 = 110 movements: not "boxed in" by the 100-movement rule
        LocalReach.Expander five = (from, to) -> {
            if (from.x() < 4) to.accept(p(from.x() + 1, from.y(), from.z()));
            return 22;
        };
        LocalReach.Result r = LocalReach.explore(p(0, 64, 0), five, 1000);
        assertTrue(r.exhausted());
        assertEquals(110, r.movements());
        assertFalse(r.boxedIn());
    }

    @Test
    void trackKeepsCellsStoodOnOnceInARow() {
        TrackBack t = new TrackBack();
        t.note(p(0, 64, 0), true);
        t.note(p(0, 64, 0), true);
        t.note(p(1, 64, 0), false); // in the air: not a cell to step back to
        t.note(p(1, 64, 0), true);
        t.note(p(2, 64, 0), true);
        assertEquals(List.of(p(2, 64, 0), p(1, 64, 0), p(0, 64, 0)), t.newestFirst());
        for (int i = 0; i < TrackBack.MAX_CELLS + 10; i++) t.note(p(10 + i, 64, 0), true);
        assertEquals(TrackBack.MAX_CELLS, t.newestFirst().size());
        t.clear();
        assertTrue(t.newestFirst().isEmpty());
    }

    @Test
    void walkedInIsWalkedBackOutToTheFirstCellOutside() {
        // walked in: open floor (0) -> step up (1, 65) -> diagonal into the pocket (2, 65, 1) -> (2, 65, 2)
        Pos out = p(0, 64, 0);
        Pos ledge = p(1, 65, 0);
        Pos in1 = p(2, 65, 1);
        Pos in2 = p(2, 65, 2);
        TrackBack.Plan plan = TrackBack.plan(List.of(in2, in1, ledge, out), in2, Set.of(in2, in1, ledge));
        assertNull(plan.problem());
        assertEquals(List.of(in1, ledge, out), plan.steps()); // the last step goes down one block: fine
    }

    @Test
    void theWalkBackGoesOnPastACellThePathfinderCannotStartFrom() {
        // the first cell outside the pocket is magma (never a start for the pathfinder): one step further back
        Pos in = p(3, 64, 0);
        Pos magma = p(2, 64, 0);
        Pos stone = p(1, 64, 0);
        TrackBack.Plan plan = TrackBack.plan(List.of(in, magma, stone, p(0, 64, 0)), in,
            c -> !c.equals(in) && !c.equals(magma));
        assertEquals(List.of(magma, stone), plan.steps());
    }

    @Test
    void aDropCannotBeWalkedBack() {
        // the live case: off the bridge end three blocks down onto the machine
        Pos bridge = p(12, 79, 38);
        Pos pocket = p(10, 76, 38);
        TrackBack.Plan plan = TrackBack.plan(List.of(pocket, bridge), pocket, Set.of(pocket, p(10, 76, 37)));
        assertNull(plan.steps());
        assertTrue(plan.problem().contains("drop of 3 blocks"), plan.problem());
        assertEquals(bridge, plan.dropFrom(), "the level the engine's fall rescue climbs back to");
    }

    @Test
    void aDropDownAOneByOneShaftNamesWhereItCameFrom() {
        // live 2026-10-27 (relative to the tile): from the pickup stand 4 blocks down the shaft under it
        Pos stand = p(126, 174, -7);
        Pos shaft = p(126, 170, -7);
        TrackBack.Plan plan = TrackBack.plan(List.of(shaft, stand, p(125, 174, -7)), shaft, Set.of(shaft));
        assertNull(plan.steps());
        assertTrue(plan.problem().startsWith("the way in was a drop of 4 blocks from 126,174,-7"), plan.problem());
        assertEquals(stand, plan.dropFrom());
        // a track that jumps (moved, not walked) is no drop
        assertNull(TrackBack.plan(List.of(shaft, p(130, 170, -7)), shaft, Set.of(shaft)).dropFrom());
    }

    @Test
    void aTrackWithAGapWasNotWalked() {
        // teleported or knocked back: the track jumps
        Pos here = p(5, 64, 5);
        TrackBack.Plan plan = TrackBack.plan(List.of(here, p(9, 64, 5)), here, Set.of(here));
        assertNull(plan.steps());
        assertTrue(plan.problem().contains("jumps 4 blocks"), plan.problem());
    }

    @Test
    void aTrackThatNeverLeavesThePocketHasNoWayBack() {
        Pos a = p(0, 64, 0);
        Pos b = p(1, 64, 0);
        assertNull(TrackBack.plan(List.of(a, b, a), a, Set.of(a, b)).steps());
        assertNull(TrackBack.plan(List.of(), a, Set.of(a)).steps());
    }

    @Test
    void escapeIsOnByDefault() {
        assertTrue(new MapArtConfig().build.boxedInEscape);
    }
}
