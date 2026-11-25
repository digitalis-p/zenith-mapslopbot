package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.WorldView.Mob;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CombatTask#priorityCreeper}: which creeper the hit and run deals with. Live 2026-10-30 (soul sand): a creeper 5
 * blocks under the art floor, which the chase walk could not reach, took the hit and run every tick (within 7 blocks)
 * and the fight never reached its out-of-reach end: 120 s of "cannot walk to the creeper", 60 s of work, round after
 * round. An unreachable creeper that is not close and not fusing is now out of reach like any other mob.
 */
class CombatTaskCreeperTest {
    /** The bot's feet on the art floor at (0.5, 175, 0.5). */
    private static final double BX = 0.5, BY = 175, BZ = 0.5;

    private static Mob creeper(int id, double x, double y, double z, boolean fusing) {
        return new Mob("creeper", x, y, z, id, fusing, 20f);
    }

    private static Mob pick(List<Mob> mobs, List<Mob> notUnreachable) {
        return CombatTask.priorityCreeper(mobs, notUnreachable, BX, BY, BZ);
    }

    @Test
    void anUnreachableCreeperUnderTheFloorIsNoPriority() {
        Mob below = creeper(1, 1.5, BY - 5, 1.5, false); // 5.2 blocks, within 7
        assertEquals(below, pick(List.of(below), List.of(below)), "before a walk failed: the hit and run, as before");
        assertNull(pick(List.of(below), List.of()), "the walk failed: out of reach, the fight's out-of-reach end");
    }

    @Test
    void aCloseOrFusingCreeperIsAlwaysHandled() {
        Mob close = creeper(1, 0.5, BY - 3.5, 1.5, false); // 3.6 blocks: within CREEPER_CLOSE
        assertEquals(close, pick(List.of(close), List.of()), "close: handled though the walk cannot reach it");
        Mob fusing = creeper(2, 1.5, BY - 5, 1.5, true);
        assertEquals(fusing, pick(List.of(fusing), List.of()), "fusing: handled (backed off from)");
        Mob edge = creeper(3, BX + CombatTask.CREEPER_CLOSE, BY, BZ, false);
        assertEquals(edge, pick(List.of(edge), List.of()), "exactly 4 blocks still counts");
    }

    @Test
    void aReachableCreeperIsFoughtAsBefore() {
        Mob near = creeper(1, 5.5, BY, 0.5, false);
        assertEquals(near, pick(List.of(near), List.of(near)));
        Mob far = creeper(2, 8.5, BY, 0.5, false);
        assertNull(pick(List.of(far), List.of(far)), "beyond 7: no priority (the far-creeper rule handles it)");
    }

    @Test
    void theNearestThatCountsWins() {
        Mob below = creeper(1, 0.5, BY - 4.5, 0.5, false); // nearer, unreachable, not close
        Mob floor = creeper(2, 6.0, BY, 0.5, false);       // farther, reachable
        assertEquals(floor, pick(List.of(below, floor), List.of(floor)));
        Mob next = creeper(3, 2.5, BY, 0.5, false);
        assertEquals(next, pick(List.of(below, floor, next), List.of(floor, next)));
        Mob zombie = new Mob("zombie", 1.5, BY, 0.5, 4);
        assertNull(pick(List.of(zombie, below), List.of(zombie)), "never a zombie");
    }
}
