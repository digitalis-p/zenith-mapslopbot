package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.WorldView.Mob;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SetAsideMobs}: by entity id, the reach rule, the time limit, hits ({@code build.fightSetAsideUnreachable}). */
class SetAsideMobsTest {
    private static final double ART_Y = 173;
    private static final long LIMIT = 12_000;

    private final List<String> lines = new ArrayList<>();
    private final SetAsideMobs mem = new SetAsideMobs(lines::add);

    /** The bot on the art layer (feet at art + 1) at (0, 0), at {@code tick}. */
    private static SetAsideMobs.Look at(long tick) {
        return new SetAsideMobs.Look(tick, LIMIT, 0.5, ART_Y + 1, 0.5, ART_Y);
    }

    private static Mob mob(String type, int id, double x, double y, double z) {
        return new Mob(type, x, y, z, id);
    }

    @Test
    void theReachRule() {
        SetAsideMobs.Look a = at(0);
        // on the bot's own ground: within 4 blocks horizontally, feet within 1.5
        assertTrue(SetAsideMobs.withinReach(mob("pillager", 1, 3.5, ART_Y + 1, 0.5), a));
        assertTrue(SetAsideMobs.withinReach(mob("pillager", 1, 2.5, ART_Y + 2.4, 2.5), a));
        // at the floor's height (art − 0.5 to art + 1.5) within 8 blocks: soul sand, placed art, carpet
        assertTrue(SetAsideMobs.withinReach(mob("wandering_trader", 1, 7.5, ART_Y - 0.125, 0.5), a));
        assertTrue(SetAsideMobs.withinReach(mob("wandering_trader", 1, 5.5, ART_Y + 1, 5.5), a));
        assertTrue(SetAsideMobs.withinReach(mob("wandering_trader", 1, 0.5, ART_Y + 0.0625, 8.4), a));
        // the live case: a roof 8 above the art layer, also straight above the bot
        assertFalse(SetAsideMobs.withinReach(mob("wandering_trader", 1, 0.5, ART_Y + 8, 0.5), a));
        assertFalse(SetAsideMobs.withinReach(mob("wandering_trader", 1, 2.5, ART_Y + 3, 0.5), a));
        // a channel under the floor, a mob at floor height but far away
        assertFalse(SetAsideMobs.withinReach(mob("pillager", 1, 2.5, ART_Y - 1, 0.5), a));
        assertFalse(SetAsideMobs.withinReach(mob("pillager", 1, 9.0, ART_Y + 1, 0.5), a));
        // off the platform (no art layer): only the bot's own ground
        SetAsideMobs.Look off = new SetAsideMobs.Look(0, LIMIT, 0.5, 64, 0.5, Double.NaN);
        assertTrue(SetAsideMobs.withinReach(mob("pillager", 1, 3.5, 64, 0.5), off));
        assertFalse(SetAsideMobs.withinReach(mob("pillager", 1, 6.5, 64, 0.5), off));
    }

    @Test
    void aCreeperCloseByOrFusingIsNeverHidden() {
        Mob below = new Mob("creeper", 1.5, ART_Y - 4, 1.5, 9, false, 20f); // 5.2 blocks under the bot's feet
        assertTrue(SetAsideMobs.outOfReach(below, at(0)));
        mem.add(below, 0);
        assertTrue(mem.hides(below, at(10)), "under the floor, 5 blocks off: set aside");
        Mob fusing = new Mob("creeper", 1.5, ART_Y - 4, 1.5, 9, true, 20f);
        assertFalse(SetAsideMobs.outOfReach(fusing, at(20)));
        assertFalse(mem.hides(fusing, at(20)), "fusing: counts again at once");
        assertTrue(lines.getLast().contains("is fusing: it counts again"), lines.toString());

        Mob behindFence = new Mob("creeper", 5.5, ART_Y, 0.5, 10, false, 20f); // floor height: within reach
        mem.add(behindFence, 30);
        assertTrue(mem.hides(behindFence, at(40)), "set aside within reach: stays until it leaves and comes back");
        Mob closer = new Mob("creeper", 3.5, ART_Y + 1, 0.5, 10, false, 20f);
        assertFalse(mem.hides(closer, at(50)), "within 4 blocks: never hidden, armed or not");
        assertTrue(lines.getLast().contains("within 4 blocks of the bot: it counts again"), lines.toString());

        // the creeper rule is for creepers only: a zombie 3 blocks off under the floor is out of reach
        assertTrue(SetAsideMobs.outOfReach(mob("zombie", 11, 3.5, ART_Y - 1, 0.5), at(60)));
    }

    @Test
    void keptByIdWhileUnseenAndWhereverItWanders() {
        Mob t = mob("wandering_trader", 7, 20.5, ART_Y + 8, 20.5);
        mem.add(t, 0);
        assertTrue(mem.without(List.of(t), at(10)).isEmpty());
        for (long tick = 20; tick < 2_000; tick += 10) assertTrue(mem.without(List.of(), at(tick)).isEmpty());
        Mob back = mob("wandering_trader", 7, 40.5, ART_Y + 8, 3.5); // 20 blocks on, after 100 looks without it
        assertTrue(mem.without(List.of(back), at(2_000)).isEmpty(), "the same entity: still set aside");
        Mob other = mob("wandering_trader", 8, 40.5, ART_Y + 8, 3.5);
        assertEquals(List.of(other), mem.without(List.of(back, other), at(2_010)), "a new entity at its spot is not");
        assertTrue(lines.isEmpty(), lines.toString());
    }

    @Test
    void comingWithinReachEndsItOnlyAfterItWasSeenOutOfReach() {
        Mob near = mob("pillager", 3, 2.5, ART_Y + 1, 0.5); // set aside while within reach (behind a fence)
        mem.add(near, 0);
        assertTrue(mem.without(List.of(near), at(10)).isEmpty(), "not on every look: it has to leave first");
        Mob away = mob("pillager", 3, 2.5, ART_Y - 5, 0.5);
        assertTrue(mem.without(List.of(away), at(20)).isEmpty());
        assertEquals(List.of(near), mem.without(List.of(near), at(30)), "back within reach: it counts again");
        assertTrue(mem.isEmpty());
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("came within reach"), lines.toString());
    }

    @Test
    void theTimeLimit() {
        Mob t = mob("wandering_trader", 9, 20.5, ART_Y + 8, 20.5);
        mem.add(t, 100);
        assertTrue(mem.without(List.of(t), at(100 + LIMIT - 1)).isEmpty());
        assertEquals(List.of(t), mem.without(List.of(t), at(100 + LIMIT)));
        assertTrue(lines.getFirst().contains("time limit"), lines.toString());
        mem.add(t, 0);
        mem.without(List.of(), at(LIMIT)); // unseen: goes without a line
        assertTrue(mem.isEmpty());
        assertEquals(1, lines.size());
    }

    @Test
    void aHitEndsTheAttackerAndEveryHostileOneButNotThePassiveOthers() {
        Mob trader = mob("wandering_trader", 1, 20.5, ART_Y + 8, 20.5);
        Mob llama = mob("trader_llama", 2, 22.5, ART_Y + 8, 20.5);
        Mob pillager = mob("pillager", 3, 2.5, ART_Y - 5, 0.5);
        mem.addAll(List.of(trader, llama, pillager), 0);
        assertEquals("2 mobs set aside as out of reach count again (the attacker and 1 hostile); 1 passive one that"
            + " did not hit stays set aside", mem.hit(2));
        assertTrue(mem.contains(trader));
        assertFalse(mem.contains(llama));
        assertFalse(mem.contains(pillager));
        assertEquals("", mem.hit(99), "a hit by a zombie with only the trader left: nothing ends");
        assertTrue(mem.contains(trader));
        mem.add(pillager, 10);
        assertEquals("1 mob set aside as out of reach count again (1 hostile); 1 passive one that did not hit stays set"
            + " aside", mem.hit(99));
    }

    @Test
    void noIdNoRecordAndTheSkipLineAtMostOncePerMinute() {
        mem.add(new Mob("wandering_trader", 1, 2, 3), 0); // id -1
        assertTrue(mem.isEmpty());
        Mob t = mob("wandering_trader", 4, 20.5, ART_Y + 8, 20.5);
        mem.add(t, 0);
        assertTrue(mem.skipLogDue(t, 0));
        assertFalse(mem.skipLogDue(t, SetAsideMobs.LOG_TICKS - 1));
        assertTrue(mem.skipLogDue(t, SetAsideMobs.LOG_TICKS));
        assertEquals(SetAsideMobs.LOG_TICKS + 5, mem.age(t, SetAsideMobs.LOG_TICKS + 5));
    }
}
