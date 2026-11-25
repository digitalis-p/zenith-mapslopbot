package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.FightArea;
import io.github.digitalisp.mapart.bot.Hit;
import io.github.digitalisp.mapart.bot.WorldView.Mob;
import io.github.digitalisp.mapart.bot.WorldView.Thing;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code build.fightSetAsideUnreachable}. Live 2026-10-24 (j0016
 * [2,1], soul sand): a wandering trader and its trader llama on a structure above the platform started 17 fights in one
 * 34-minute map (8.3 min), every one ending "only 2 mobs out of reach left ... ignored until a mob hits the bot again",
 * the next starting 1-2 minutes later. The position memory dropped its records after three looks without the mobs,
 * and the client stops tracking them whenever the bot walks out of the server's entity range (restocks, far trips):
 * here a mob "leaves tracking" by leaving the fake world's mob list and comes back with the same entity id.
 */
class SetAsideUnreachableTest {
    private static final int LONG = 80_000;
    private static int mobIds = 5_000;
    private static int spitIds = 95_000;

    private static Sim sim(RecordingLogger log, boolean on) {
        Sim sim = new Sim();
        if (log != null) sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.bot.placementsPerTick = 1; // long lanes: the work is still under way while the mobs come and go
        sim.modules.state.put("KillAura", false);
        sim.bot.killAuraOn = () -> sim.modules.isEnabled("KillAura");
        sim.moss.fightMobs = true;
        sim.config.combat.mobs.addAll(List.of("wandering_trader", "trader_llama", "llama"));
        sim.config.build.fightSetAsideUnreachable = on;
        return sim;
    }

    private static Job mossJob(Sim sim) {
        TileArt art = Sim.stripes(64, 4);
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(Sim.BLACK, 1728));
        return sim.addJob("j0601", "Moss", 1, 1, List.of("moss"), (x, z) -> art);
    }

    private static boolean laneRunning(Sim sim) {
        BotTask t = sim.bot.lastTask;
        return t != null && t.name().startsWith("lane") && !t.isFinished();
    }

    private static Sim working(RecordingLogger log, boolean on) {
        Sim sim = sim(log, on);
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        return sim;
    }

    /** A mob on a roof 9 blocks above the art layer, inside the fight area: no fight can walk to it. */
    private static Mob onTheRoof(Sim sim, String type, double dx) {
        Mob m = new Mob(type, sim.world.x + dx, sim.moss.artY + 9, sim.world.z + 1, mobIds++);
        sim.bot.unreachableIds.add(m.id());
        sim.world.mobList.add(m);
        return m;
    }

    /** A pillager in a water channel 6 blocks under the floor: no fight can walk to it. */
    private static Mob inTheChannel(Sim sim, double dx) {
        Mob m = new Mob("pillager", sim.world.x + dx, sim.moss.artY - 5, sim.world.z + 1, mobIds++);
        sim.bot.unreachableIds.add(m.id());
        sim.world.mobList.add(m);
        return m;
    }

    /** {@code m} (the same entity) now stands at (x, y, z); returns the moved mob. */
    private static Mob move(Sim sim, Mob m, double x, double y, double z) {
        Mob to = new Mob(m.type(), x, y, z, m.id());
        int i = sim.world.mobList.indexOf(m);
        if (i >= 0) sim.world.mobList.set(i, to);
        else sim.world.mobList.add(to);
        return to;
    }

    /** The trader llama spat at the bot (cause the llama, direct its spit). */
    private static void spitFrom(Sim sim, Mob llama) {
        sim.engine.onHit(new Hit("minecraft:mob_projectile", Sim.thing(llama),
            new Thing(spitIds++, "llama_spit", sim.world.x, sim.world.y + 1, sim.world.z)));
    }

    /** The trader and its llama on the roof, one fight that finds them out of reach and sets them aside. */
    private static Mob[] traderSetAside(Sim sim) {
        Mob trader = onTheRoof(sim, "wandering_trader", 6);
        Mob llama = onTheRoof(sim, "trader_llama", 8);
        assertTrue(sim.runUntil(() -> sim.bot.reportedUnreachable.size() == 1, 400), sim.state());
        assertEquals(1, sim.bot.fights.size(), sim.bot.history.toString());
        sim.run(20); // a look or two at them out of reach
        return new Mob[]{trader, llama};
    }

    /**
     * The live pattern: the mobs wander on the roof and the bot's walks take it out of the server's entity range and
     * back (the client's cache loses them and gets them again, same entity ids).
     */
    private static Mob[] wanderAndLeaveTracking(Sim sim, Mob trader, Mob llama, int rounds) {
        for (int r = 0; r < rounds; r++) {
            for (int i = 0; i < 6; i++) { // wander: a block every 50 ticks
                trader = move(sim, trader, trader.x() + 1, trader.y(), trader.z());
                llama = move(sim, llama, llama.x() + 1, llama.y(), llama.z() + 0.5);
                sim.run(50);
            }
            sim.world.mobList.remove(trader); // out of tracking range: gone from the entity cache
            sim.world.mobList.remove(llama);
            sim.run(200); // twenty looks without them
            trader = move(sim, trader, trader.x() + 2, trader.y(), trader.z() - 1); // back, a little further on
            llama = move(sim, llama, llama.x() + 2, llama.y(), llama.z() - 1);
            sim.run(300);
        }
        return new Mob[]{trader, llama};
    }

    private static long lines(RecordingLogger log, String part) {
        return log.lines.stream().filter(l -> l.contains(part)).count();
    }

    // ---------------------------------------------------------------------------------------------- the live case

    @Test
    void anUnreachableTraderSeenAgainStartsNoNewFight() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = working(log, true);
        Mob[] m = traderSetAside(sim);
        assertTrue(lines(log, "set aside as out of reach (build.fightSetAsideUnreachable)") == 1, log.lines.toString());
        int placed = sim.bot.placed;
        long start = sim.ticks;
        wanderAndLeaveTracking(sim, m[0], m[1], 3);
        assertEquals(1, sim.bot.fights.size(), "no new fight for the same trader and llama: " + sim.bot.history);
        assertTrue(sim.bot.placed > placed, "the work goes on");
        // one "no fight for it" line per mob and minute (1,200 ticks) at most
        long minutes = (sim.ticks - start) / SetAsideMobs.LOG_TICKS + 1;
        long traderLines = log.at(Level.INFO).stream()
            .filter(l -> l.startsWith("wandering_trader #" + m[0].id()) && l.contains("no fight for it")).count();
        assertTrue(traderLines >= 1 && traderLines <= minutes, traderLines + " lines in " + minutes + " min: " + log.lines);
        assertTrue(sim.runUntil(() -> sim.output().maps.size() == 1, LONG), sim.state());
    }

    @Test
    void itHitsTheBotThenItIsFoughtAndThePassiveOneThatDidNotStaysSetAside() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = working(log, true);
        Mob[] m = traderSetAside(sim);
        Mob[] w = wanderAndLeaveTracking(sim, m[0], m[1], 1);
        assertEquals(1, sim.bot.fights.size(), sim.bot.history.toString());
        sim.inv.health = 18f;
        spitFrom(sim, w[1]); // the llama spits at the bot
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == 2, 60), "fought again at once: " + sim.state());
        FightArea a = sim.bot.fights.getLast();
        assertTrue(a.contains(w[1]), "the llama that spat counts again");
        assertFalse(a.contains(w[0]), "the trader did not hit the bot: still set aside, not chased");
        assertTrue(lines(log, "a mob hit the bot (trader_llama #" + w[1].id()) == 1, log.lines.toString());
    }

    @Test
    void itComesWithinReachThenItIsFought() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = working(log, true);
        Mob[] m = traderSetAside(sim);
        Mob trader = wanderAndLeaveTracking(sim, m[0], m[1], 1)[0];
        assertEquals(1, sim.bot.fights.size(), sim.bot.history.toString());
        // it came down from the roof and walks next to the bot, on the bot's own level
        sim.bot.unreachableIds.remove(trader.id());
        for (int i = 0; i < 40 && sim.bot.fights.size() == 1; i++) {
            trader = move(sim, trader, sim.world.x + 1.5, sim.world.y, sim.world.z + 0.5);
            sim.tick();
        }
        assertEquals(2, sim.bot.fights.size(), "fought once it is within reach: " + sim.state());
        assertTrue(lines(log, "came within reach of the bot: it counts again") == 1, log.lines.toString());
        int id = trader.id();
        assertTrue(sim.runUntil(() -> sim.world.mobList.stream().noneMatch(x -> x.id() == id), 200), "killed");
    }

    @Test
    void theTimeLimitRunsOutThenItIsFought() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = working(log, true);
        sim.config.build.fightSetAsideMinutes = 1; // 1,200 ticks
        traderSetAside(sim);
        sim.run(1_000);
        assertEquals(1, sim.bot.fights.size(), "within the time limit: " + sim.bot.history);
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == 2, 400), "after it: " + sim.state());
        assertTrue(lines(log, "the time limit is up (build.fightSetAsideMinutes)") >= 1, log.lines.toString());
    }

    @Test
    void aNewEntityIsFought() {
        Sim sim = working(null, true);
        Mob[] m = traderSetAside(sim);
        sim.world.mobList.remove(m[0]); // killed by a player, or reloaded with its chunk: a new entity id
        sim.world.mobList.remove(m[1]);
        sim.run(40);
        Mob again = new Mob("wandering_trader", m[0].x(), m[0].y(), m[0].z(), mobIds++);
        sim.bot.unreachableIds.add(again.id());
        sim.world.mobList.add(again);
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == 2, 40), "a new entity at the same spot: " + sim.state());
    }

    @Test
    void aMobWithoutAnIdIsNeverSetAside() {
        Sim sim = working(null, true);
        Mob unknown = new Mob("wandering_trader", sim.world.x + 6, sim.moss.artY + 9, sim.world.z + 1); // id -1
        sim.bot.unreachableMobs.add(unknown);
        sim.world.mobList.add(unknown);
        assertTrue(sim.runUntil(() -> sim.bot.reportedUnreachable.size() == 1, 400), sim.state());
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == 2, 40), "no identity, no set-aside: " + sim.state());
    }

    // ------------------------------------------------------------------------------------------- hostile mobs

    @Test
    void anyMobsHitEndsAHostileSetAsideButNotAPassiveOne() {
        Sim sim = working(null, true);
        Mob pillager = inTheChannel(sim, 4);
        Mob trader = onTheRoof(sim, "wandering_trader", 6);
        assertTrue(sim.runUntil(() -> sim.bot.reportedUnreachable.size() == 1, 400), sim.state());
        assertEquals(2, sim.bot.reportedUnreachable.getFirst().size());
        sim.run(200);
        assertEquals(1, sim.bot.fights.size());

        Mob zombie = new Mob("zombie", sim.world.x + 1.5, sim.world.y, sim.world.z, mobIds++);
        sim.world.mobList.add(zombie);
        sim.inv.health = 18f;
        sim.blowFrom(zombie);
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == 2, 60), sim.state());
        FightArea a = sim.bot.fights.getLast();
        assertTrue(a.contains(zombie));
        assertTrue(a.contains(pillager), "a hostile mob set aside counts again on any mob's hit (it may be shooting)");
        assertFalse(a.contains(trader), "the trader that did not hit stays set aside");
    }

    @Test
    void aHostileSetAsideIsFoughtAtOnceWithinReach() {
        Sim sim = working(null, true);
        Mob pillager = inTheChannel(sim, 4);
        assertTrue(sim.runUntil(() -> sim.bot.reportedUnreachable.size() == 1, 400), sim.state());
        sim.run(200);
        assertEquals(1, sim.bot.fights.size());
        sim.bot.unreachableIds.remove(pillager.id());
        for (int i = 0; i < 20 && sim.bot.fights.size() == 1; i++) {
            pillager = move(sim, pillager, sim.world.x + 2, sim.world.y, sim.world.z);
            sim.tick();
        }
        assertEquals(2, sim.bot.fights.size(), "within reach: fought at the next look: " + sim.state());
    }

    @Test
    void aSetAsideMobIsNotChasedInAFightForAnother() {
        Sim sim = working(null, true);
        Mob trader = traderSetAside(sim)[0];
        Mob pillager = new Mob("pillager", sim.world.x + 5, sim.world.y, sim.world.z + 1, mobIds++);
        sim.world.mobList.add(pillager);
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == 2, 60), sim.state());
        int reports = sim.bot.reportedUnreachable.size();
        assertTrue(sim.runUntil(() -> !sim.world.mobList.contains(pillager), 200), sim.state());
        sim.run(200);
        assertEquals(2, sim.bot.fights.size(), sim.bot.history.toString());
        assertEquals(reports, sim.bot.reportedUnreachable.size(),
            "the fight ended with the pillager; the set-aside trader was neither chased nor reported again");
        assertTrue(sim.world.mobList.stream().anyMatch(x -> x.id() == trader.id()));
    }

    // ---------------------------------------------------------------------------------------------- creepers

    /**
     * A creeper 5 blocks under the art floor, inside the fight area (live 2026-10-30: the hit and run could not walk to
     * it, the fight ran out of time and began again, round after round).
     */
    private static Mob creeperUnderTheFloor(Sim sim, boolean unreachable) {
        Mob c = new Mob("creeper", sim.world.x + 1, sim.moss.artY - 5, sim.world.z + 1, mobIds++);
        if (unreachable) sim.bot.unreachableIds.add(c.id());
        sim.world.mobList.add(c);
        return c;
    }

    @Test
    void anUnreachableCreeperUnderTheFloorIsSetAsideAndTheWorkGoesOn() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = working(log, true);
        Mob c = creeperUnderTheFloor(sim, true);
        assertTrue(sim.runUntil(() -> sim.bot.reportedUnreachable.size() == 1, 200), "within 10 s: " + sim.state());
        assertTrue(lines(log, "set aside as out of reach (build.fightSetAsideUnreachable): creeper #" + c.id()) == 1,
            log.lines.toString());
        int placed = sim.bot.placed;
        sim.run(3_000);
        assertEquals(1, sim.bot.fights.size(), "no new fight for the creeper under the floor: " + sim.bot.history);
        assertTrue(sim.bot.placed > placed, "the work goes on");
        assertTrue(sim.world.mobList.contains(c));
    }

    @Test
    void aSetAsideCreeperThatComesCloseIsFought() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = working(log, true);
        Mob c = creeperUnderTheFloor(sim, true);
        assertTrue(sim.runUntil(() -> sim.bot.reportedUnreachable.size() == 1, 200), sim.state());
        sim.run(100);
        assertEquals(1, sim.bot.fights.size());
        // it found a way up: 3 blocks from the bot, still a block under its feet (never at floor height)
        sim.bot.unreachableIds.remove(c.id());
        for (int i = 0; i < 20 && sim.bot.fights.size() == 1; i++) {
            c = move(sim, c, sim.world.x + 2.5, sim.world.y - 2, sim.world.z);
            sim.tick();
        }
        assertEquals(2, sim.bot.fights.size(), "a creeper close by counts again at the next look: " + sim.state());
        assertTrue(sim.bot.fights.getLast().contains(c));
        assertTrue(lines(log, "within 4 blocks of the bot: it counts again") == 1, log.lines.toString());
        int id = c.id();
        assertTrue(sim.runUntil(() -> sim.world.mobList.stream().noneMatch(x -> x.id() == id), 200), "killed");
    }

    @Test
    void aReachableCreeperIsFoughtAsBefore() {
        Sim sim = working(null, true);
        Mob c = new Mob("creeper", sim.world.x + 3, sim.world.y, sim.world.z + 1, mobIds++);
        sim.world.mobList.add(c);
        assertTrue(sim.runUntil(() -> !sim.world.mobList.contains(c), 200), sim.state());
        assertEquals(1, sim.bot.fights.size());
        assertTrue(sim.bot.reportedUnreachable.isEmpty(), "nothing set aside");
    }

    @Test
    void aFightThatRunsOutOfTimeSetsAsideTheMobsStillOutOfReach() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = working(log, true);
        sim.config.combat.maxSeconds = 5;
        sim.config.combat.cooldownSeconds = 10;
        sim.bot.killTicks = 1_000_000; // the old creeper loop: the fight never ends by itself, nor reports anything
        Mob c = creeperUnderTheFloor(sim, false);
        Mob zombie = new Mob("zombie", sim.world.x + 6, sim.world.y, sim.world.z + 1, mobIds++); // on the floor
        sim.world.mobList.add(zombie);
        assertTrue(sim.runUntil(() -> sim.notifier.has("alert", "Fight not over"), 400), sim.state());
        assertTrue(lines(log, "set aside as out of reach (build.fightSetAsideUnreachable): creeper #" + c.id()) == 1,
            "the time-out sets the creeper under the floor aside: " + log.lines);
        assertEquals(0, lines(log, "zombie #" + zombie.id() + " at"), "never the zombie on the floor: " + log.lines);
        sim.bot.killTicks = 20;
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == 2, 600), "after the cooldown: " + sim.state());
        FightArea a = sim.bot.fights.getLast();
        assertTrue(a.contains(zombie), "the zombie within reach is fought again");
        assertFalse(a.contains(c), "the creeper under the floor stays set aside");
        assertTrue(sim.runUntil(() -> !sim.world.mobList.contains(zombie), 200), sim.state());
        sim.run(2_000);
        assertEquals(2, sim.bot.fights.size(), "no fight loop on the creeper: " + sim.bot.history);
    }

    // ---------------------------------------------------------------------------------- switch off: as before

    @Test
    void switchOffTheTraderLeavingTrackingIsFoughtAgainAsBefore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = working(log, false);
        Mob[] m = traderSetAside(sim);
        sim.world.mobList.remove(m[0]);
        sim.world.mobList.remove(m[1]);
        sim.run(200); // out of tracking: the position memory drops its records after three looks
        move(sim, m[0], m[0].x() + 1, m[0].y(), m[0].z());
        move(sim, m[1], m[1].x() + 1, m[1].y(), m[1].z());
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == 2, 40), "today's repeated fight: " + sim.state());
        assertEquals(0, lines(log, "build.fightSetAsideUnreachable"), "no line of the new memory: " + log.lines);
        assertEquals(0, lines(log, "no fight for it"), log.lines.toString());
    }

    @Test
    void switchOffATrackedTraderStaysSetAsideUntilAnyMobsHitAsBefore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = working(log, false);
        Mob[] m = traderSetAside(sim);
        sim.run(1_000);
        assertEquals(1, sim.bot.fights.size(), "matched by position while tracked: " + sim.bot.history);
        Mob zombie = new Mob("zombie", sim.world.x + 1.5, sim.world.y, sim.world.z, mobIds++);
        sim.world.mobList.add(zombie);
        sim.inv.health = 18f;
        sim.blowFrom(zombie);
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == 2, 60), sim.state());
        assertTrue(sim.bot.fights.getLast().contains(m[0]), "any mob's hit brings every set-aside mob back");
        // (the position memory keeps one record for both: the llama is within 3 blocks of the trader)
        assertTrue(lines(log, "mob(s) left alone as out of reach count again") == 1, log.lines.toString());
    }

    @Test
    void switchOffTheTimeLimitDoesNotApply() {
        Sim sim = working(null, false);
        sim.config.build.fightSetAsideMinutes = 1;
        traderSetAside(sim);
        sim.run(3_000);
        assertEquals(1, sim.bot.fights.size(), sim.bot.history.toString());
    }

    @Test
    void theMapFinishesWithTheTraderOnTheRoof() {
        Sim sim = working(null, true);
        traderSetAside(sim);
        assertTrue(sim.runUntil(() -> sim.output().maps.size() == 1, LONG), sim.state());
        assertEquals(1, sim.bot.fights.size(), sim.bot.history.toString());
    }
}
