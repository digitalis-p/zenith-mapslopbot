package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.FightArea;
import io.github.digitalisp.mapart.bot.Hit;
import io.github.digitalisp.mapart.bot.WorldView.Mob;
import io.github.digitalisp.mapart.bot.WorldView.Thing;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who hurt the bot: only the server's damage events with a listed mob behind them (its
 * blow, or its arrow) count as "hurt by mobs" for self-defence, the health hold's retreat and the out-of-reach memory.
 * Live 2026-10-13 01:45-01:48: the owner took control and flew the account (a fall, two elytra wall hits; health back to 19.2
 * by 01:47:31); 9 s after bot ticks started again the engine took the health below what it last saw for a hit and
 * fought two pillagers in a water channel under the walkway for 29 s. No mob had hurt the bot (no damage event from
 * any): they could not.
 */
class SelfDefenseHitsTest {
    private static final int LONG = 80_000;
    private static int mobIds = 700;

    private static Sim sim() {
        Sim sim = new Sim();
        sim.bot.placementsPerTick = 1; // long lanes: the work is still under way when the damage comes
        sim.modules.state.put("KillAura", false);
        sim.bot.killAuraOn = () -> sim.modules.isEnabled("KillAura");
        return sim;
    }

    private static Job mossJob(Sim sim) {
        TileArt art = Sim.stripes(64, 4);
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(Sim.BLACK, 1728));
        return sim.addJob("j0201", "Moss", 1, 1, List.of("moss"), (x, z) -> art);
    }

    private static boolean laneRunning(Sim sim) {
        BotTask t = sim.bot.lastTask;
        return t != null && t.name().startsWith("lane") && !t.isFinished();
    }

    private static Sim working() {
        Sim sim = sim();
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        return sim;
    }

    /** A pillager in the water channel under the walkway: the fight cannot walk to it. */
    private static Mob inTheChannel(Sim sim, double dx) {
        Mob m = new Mob("pillager", sim.world.x + dx, sim.world.y - 6, sim.world.z + 1, mobIds++);
        sim.bot.unreachableMobs.add(m);
        sim.world.mobList.add(m);
        return m;
    }

    private static Mob nextToTheBot(Sim sim, double dx) {
        Mob m = new Mob("pillager", sim.world.x + dx, sim.world.y, sim.world.z + 1, mobIds++);
        sim.world.mobList.add(m);
        return m;
    }

    // ------------------------------------------------------------------------------- (a) a player had control

    @Test
    void aHealthDropAcrossAPlayersControlStartsNoFight() {
        // the live sequence: two pillagers in the channel, the owner flies the account, the bot comes back below 20
        Sim sim = working();
        Mob a = inTheChannel(sim, 3);
        inTheChannel(sim, -4);
        sim.run(40);
        assertTrue(sim.bot.fights.isEmpty(), "not hurt: left alone");
        assertEquals(20f, sim.inv.health);

        sim.engine.onBotTickStopped(); // "bot ticks stopped": the owner took control
        sim.damage("minecraft:fall");
        sim.inv.health = 19.2f;
        sim.damage("minecraft:fly_into_wall");
        sim.inv.health = 17.7f;
        sim.damage("minecraft:fly_into_wall");
        sim.inv.health = 16.9f;
        sim.arrowFrom(a); // even a mob's hit while a player flies the account is the player's to handle
        sim.inv.health = 19.2f; // regenerated, still below the 20 the engine last saw
        sim.engine.onBotTickStarting(); // "bot ticks starting"
        int placed = sim.bot.placed;
        sim.run(600); // the live fight began 9 s after the start
        assertTrue(sim.bot.fights.isEmpty(), "no fight: " + sim.bot.history);
        assertTrue(sim.bot.retreats.isEmpty(), sim.bot.history.toString());
        assertTrue(sim.bot.placed > placed, "back to work");
    }

    @Test
    void aHitQueuedJustBeforeAPlayerTookControlDoesNotCountAfterwards() {
        Sim sim = working();
        Mob p = nextToTheBot(sim, 6);
        sim.arrowFrom(p); // queued for the next tick, which never comes: the owner takes control first
        sim.engine.onBotTickStopped();
        sim.engine.onBotTickStarting();
        sim.run(400);
        assertTrue(sim.bot.fights.isEmpty(), sim.bot.history.toString());

        sim.arrowFrom(p); // now it shoots the working bot: that one counts
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 20), sim.state());
    }

    // ------------------------------------------------------------------------------- (b) no mob behind the damage

    @Test
    void fallAndWallDamageWithAPillagerNearStartNoFight() {
        Sim sim = working();
        Mob p = nextToTheBot(sim, 6);
        sim.inv.health = 18f;
        sim.damage("minecraft:fall");
        sim.run(20);
        sim.inv.health = 16.5f;
        sim.damage("minecraft:fly_into_wall");
        sim.run(200);
        // a player's hit and a skeleton's arrow (not listed) are no hits by listed mobs either
        Thing player = new Thing(77, "player", sim.world.x + 2, sim.world.y, sim.world.z);
        sim.engine.onHit(new Hit("minecraft:player_attack", player, player));
        sim.engine.onHit(new Hit("minecraft:arrow", new Thing(78, "skeleton", sim.world.x + 9, sim.world.y, sim.world.z),
            new Thing(79, "arrow", sim.world.x, sim.world.y + 1, sim.world.z)));
        sim.inv.health = 14f;
        sim.run(200);
        assertTrue(sim.bot.fights.isEmpty(), "no fight: " + sim.bot.history);

        sim.inv.health = 13f;
        sim.arrowFrom(p); // the pillager shoots: now it is self-defence
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 20), sim.state());
    }

    @Test
    void aFallToLowHealthBesideAPillagerHoldsWithoutFightOrRetreat() {
        Sim sim = working();
        nextToTheBot(sim, 6);
        sim.inv.health = 7.5f; // the health hold starts
        sim.damage("minecraft:fall");
        sim.run(300);
        assertTrue(sim.bot.fights.isEmpty(), sim.bot.history.toString());
        assertTrue(sim.bot.retreats.isEmpty(), "no retreat from a pillager that did nothing: " + sim.bot.history);
        assertTrue(sim.engine.status().note().contains("low health"), sim.state());
    }

    @Test
    void aPausedBotHurtByFireBesideAPillagerStandsStill() {
        Sim sim = working();
        sim.engine.pause("lunch");
        sim.run(40);
        int tasks = sim.bot.history.size();
        nextToTheBot(sim, 6);
        sim.inv.health = 17f;
        sim.damage("minecraft:on_fire");
        sim.run(200);
        assertEquals(tasks, sim.bot.history.size(), "nothing moves: " + sim.bot.history);
    }

    // ------------------------------------------------------------------------------- (c) a pillager's arrow

    @Test
    void aPillagersArrowStartsTheFightAndTheShooterIsFoughtFirst() {
        Sim sim = working();
        Mob near = nextToTheBot(sim, 3); // listed and nearer, but it did nothing
        Mob shooter = nextToTheBot(sim, 10);
        sim.inv.health = 16.5f;
        sim.arrowFrom(shooter);
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 20), sim.state());
        FightArea a = sim.bot.fights.getFirst();
        assertTrue(a.minX() > a.maxX(), "self-defense on moss: the sphere around the bot");
        assertTrue(sim.runUntil(() -> sim.bot.killed.size() == 2, 400), sim.state());
        assertEquals(shooter, sim.bot.killed.getFirst(), "the one that shot first");
        assertEquals(near, sim.bot.killed.get(1));
    }

    @Test
    void theShooterStaysTheTargetWhenTheFightOutlastsTheHurtWindow() {
        Sim sim = working();
        sim.bot.killTicks = BuildEngine.HURT_WINDOW_TICKS + 100; // the first kill comes after the window
        Mob near = nextToTheBot(sim, 3);
        Mob shooter = nextToTheBot(sim, 10);
        sim.arrowFrom(shooter);
        assertTrue(sim.runUntil(() -> sim.bot.killed.size() == 2, 2_000), sim.state());
        assertEquals(List.of(shooter, near), sim.bot.killed, "the shooter first, though it last shot long ago");
    }

    @Test
    void anIdleBotShotByAPillagerFightsAtOnceNotAfterTheIdlePoll() {
        // local run 2026-10-13 at 10x: "fighting now" in IDLE, the fight began only at the next idle poll (10 s)
        Sim sim = sim();
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.IDLE && sim.bot.lastTask == null, 2_000), sim.state());
        sim.run(250);
        Mob p = nextToTheBot(sim, 6);
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 2 * 10 + 2), "within a combat poll: " + sim.state());
        assertTrue(sim.runUntil(() -> sim.world.mobList.isEmpty(), 400), sim.state());
    }

    @Test
    void aPillagersArrowAtLowHealthWithNoFightPossibleRetreats() {
        Sim sim = sim();
        sim.config.combat.selfDefense = false; // no fight on moss
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob p = nextToTheBot(sim, 6);
        sim.inv.health = 7.9f;
        sim.damage("minecraft:fall"); // a fall first: no retreat for that
        sim.run(40);
        assertTrue(sim.bot.retreats.isEmpty(), sim.bot.history.toString());

        sim.inv.health = 7.4f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.retreats.isEmpty(), 40), sim.state());
        assertEquals(List.of(p), sim.bot.retreats.getFirst());
        assertTrue(sim.bot.fights.isEmpty());
        sim.bot.regenTicks = 10;
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }

    // ------------------------------------------------------------------------------- (d) the out-of-reach memory

    @Test
    void theOutOfReachMemoryClearsOnlyOnAMobsHit() {
        Sim sim = sim();
        sim.moss.fightMobs = true; // the platform fights: the unreachable pillager is set aside after one try
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob p = inTheChannel(sim, 6);
        assertTrue(sim.runUntil(() -> sim.bot.reportedUnreachable.size() == 1, 200), sim.state());
        int fights = sim.bot.fights.size();

        sim.inv.health = 17f;
        sim.damage("minecraft:fall");
        sim.run(100);
        sim.inv.health = 16f;
        sim.damage("minecraft:fly_into_wall");
        sim.run(400);
        assertEquals(fights, sim.bot.fights.size(), "still set aside after a fall and a wall: " + sim.bot.history);

        sim.inv.health = 15f;
        sim.arrowFrom(p); // it can reach the bot after all
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == fights + 1, 40), sim.state());
    }
}
