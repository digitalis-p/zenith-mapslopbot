package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FallExit} from the live 2026-10-13 fall in base-relative coordinates ({@link EdgeDropTest.World}: the soul sand
 * tile's west edge column x -320, the water channel at x -321 with its bottom at y169, the clear machine's no-go zone
 * x -328..-321, y173..178): the bot at feet (-321, 170, -26) in the water.
 */
class FallExitTest {
    private static final int E = EdgeDropTest.E;

    @Test
    void theNearestExitIsTheEdgeColumnBesideTheChannel() {
        EdgeDropTest.World w = new EdgeDropTest.World();
        w.zone(E - 8, 173, -10, E - 1, 178, 10);
        Pos feet = new Pos(E - 1, 170, 2);
        List<Pos> exits = FallExit.exits(feet, 173, 174, w, 4);
        assertEquals(new Pos(E, 173, 2), exits.getFirst(), "east, onto the soul sand floor: " + exits);
        for (Pos c : exits) assertTrue(c.x() >= E, "never west into the clear machine's zone: " + c);
        assertTrue(FallExit.beside(feet, exits.getFirst()), "next to the bot's column: its own keys");
    }

    @Test
    void waterTheChannelAndWallsAreNoExit() {
        EdgeDropTest.World w = new EdgeDropTest.World();
        assertFalse(FallExit.standable(new Pos(E - 1, 170, 2), w), "the channel bottom is under water");
        assertTrue(FallExit.standable(new Pos(E, 173, 2), w), "the soul sand floor");
        w.blocks.put(new Pos(E, 173, 2), BlockSpec.of("minecraft:stone"));
        assertFalse(FallExit.standable(new Pos(E, 173, 2), w), "a full block in the feet cell");
        w.blocks.put(new Pos(E, 173, 2), BlockSpec.of("minecraft:white_carpet"));
        assertTrue(FallExit.standable(new Pos(E, 173, 2), w), "a carpet of the art");
        w.blocks.put(new Pos(E, 174, 2), BlockSpec.of("minecraft:stone"));
        assertFalse(FallExit.standable(new Pos(E, 173, 2), w), "no head room");
    }

    @Test
    void nothingAtTheFloorsLevelNearbyIsNoExit() {
        EdgeDropTest.World w = new EdgeDropTest.World();
        // a pit 20 blocks deep, far from any floor at y173
        Pos feet = new Pos(E + 10, 150, 0);
        assertTrue(FallExit.exits(feet, 151, 152, w, 4).isEmpty());
        assertEquals(4, FallExit.exits(new Pos(E - 1, 170, 0), 173, 174, w, 4).size());
    }
}
