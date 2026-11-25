package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PickupStandTask#noFloor}: a stand cell over a hole is refused. Live 2026-10-27 (relative to the soul sand tile):
 * the stand (126, 174, -7) was the top of a dry 1x1 shaft whose bottom is stone at y169; the walk there after a login
 * dropped the bot 4 blocks into it, boxed in.
 */
class PickupStandFloorTest {
    private static final Pos STAND = new Pos(126, 174, -7);

    /** {@link EdgeDropTest.World}'s cells, ground at y173 (feet y174) around the shaft's column. */
    private static EdgeDropTest.World shaft() {
        EdgeDropTest.World w = new EdgeDropTest.World();
        for (int x = 120; x <= 130; x++)
            for (int z = -12; z <= -2; z++) {
                for (int y = 169; y <= 173; y++) {
                    boolean hole = x == STAND.x() && z == STAND.z() && y >= 170;
                    if (!hole) w.blocks.put(new Pos(x, y, z), BlockSpec.of("minecraft:stone"));
                }
            }
        return w;
    }

    @Test
    void aStandOverAFourBlockShaftIsRefused() {
        String why = PickupStandTask.noFloor(STAND, shaft());
        assertNotNull(why);
        assertTrue(why.contains("drop of more than 3 blocks"), why);
    }

    @Test
    void aStandWithAFloorOrAShortDropIsKept() {
        EdgeDropTest.World w = shaft();
        w.blocks.put(STAND.below(), BlockSpec.of("minecraft:stone"));
        assertNull(PickupStandTask.noFloor(STAND, w), "a floor right under it");
        w = shaft();
        w.blocks.put(STAND.offset(0, -4, 0), BlockSpec.of("minecraft:stone"));
        assertNull(PickupStandTask.noFloor(STAND, w), "a drop of 3 blocks: no refusal");
        w = shaft();
        w.water.add(STAND.offset(0, -3, 0));
        assertNull(PickupStandTask.noFloor(STAND, w), "water below catches the bot");
    }

    @Test
    void anUnloadedColumnIsNotRefusedYet() {
        EdgeDropTest.World w = shaft();
        w.unloaded.add(STAND.offset(0, -2, 0));
        assertNull(PickupStandTask.noFloor(STAND, w), "looked at again before the walk onto it");
    }
}
