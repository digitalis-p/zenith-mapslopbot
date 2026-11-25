package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.FightArea;
import io.github.digitalisp.mapart.bot.Hit;
import io.github.digitalisp.mapart.bot.WorldView.Mob;
import io.github.digitalisp.mapart.bot.WorldView.Thing;
import io.github.digitalisp.mapart.coord.Teammates;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teammates are not threats (coordinator rule 9.4): every combat path with a
 * teammate and with a stranger. Players are fought only when the owner lists {@code player} in {@code combat.mobs}; a
 * teammate is then still never a fight target (the platform's fight area, self-defence, a fight while stopped), never
 * an attacker when it hits the bot, and never a threat the health hold retreats from. With nobody a teammate, a
 * stranger is handled as before.
 */
class TeammatesCombatTest {
    private static final int LONG = 80_000;
    private static final UUID MATE = UUID.fromString("0f3c2a1e-6b7d-4c8e-9f10-112233445566");
    private static final UUID STRANGER = UUID.fromString("deadbeef-0000-4000-8000-000000000001");
    private static int ids = 900;

    private static Sim sim(Teammates t) {
        Sim sim = new Sim();
        sim.modules.state.put("KillAura", false);
        sim.bot.killAuraOn = () -> sim.modules.isEnabled("KillAura");
        sim.config.combat.mobs.add("player"); // the owner fights players: the only way a player is ever a target
        sim.engine.teammates(t);
        return sim;
    }

    private static Teammates mates() {
        Teammates t = new Teammates();
        t.configured(() -> List.of(MATE.toString()));
        return t;
    }

    private static Mob teammate(double x, double y, double z) {
        return Mob.player(x, y, z, ids++, MATE, "Mate");
    }

    private static Mob stranger(double x, double y, double z) {
        return Mob.player(x, y, z, ids++, STRANGER, "Stranger");
    }

    private static Thing thing(Mob m) {
        return new Thing(m.id(), m.type(), m.x(), m.y(), m.z(), m.uuid(), m.name());
    }

    private static Job soulJob(Sim sim) {
        TileArt art = Sim.stripes(8, 0);
        return sim.addJob("j0001", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
    }

    private static Job mossJob(Sim sim) {
        TileArt art = Sim.stripes(64, 4);
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(Sim.BLACK, 1728));
        return sim.addJob("j0301", "Moss", 1, 1, List.of("moss"), (x, z) -> art);
    }

    private static boolean building(Sim sim) {
        return sim.phase() == Phase.BUILD && sim.bot.history.stream().anyMatch(h -> h.startsWith("placeLane"));
    }

    private static boolean laneRunning(Sim sim) {
        BotTask t = sim.bot.lastTask;
        return t != null && t.name().startsWith("lane") && !t.isFinished();
    }

    private static Sim workingOnMoss(Teammates t) {
        Sim sim = sim(t);
        sim.bot.placementsPerTick = 1; // long lanes: the work is still under way when the hits come
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        return sim;
    }

    // ------------------------------------------------------------------------------------- pure rules

    @Test
    void aTeammateIsNeverAnAttacker() {
        Set<String> listed = Set.of("pillager", "player");
        Teammates t = mates();
        Thing mate = thing(teammate(1, 64, 0));
        Thing stranger = thing(stranger(2, 64, 0));
        Thing arrow = new Thing(977, "arrow", 0.5, 65, 0.5);
        assertEquals(stranger, Combat.attacker(new Hit("minecraft:player_attack", stranger, stranger), listed, t),
            "a stranger's blow, players listed");
        assertNull(Combat.attacker(new Hit("minecraft:player_attack", mate, mate), listed, t), "a teammate's blow");
        assertNull(Combat.attacker(new Hit("minecraft:arrow", mate, arrow), listed, t), "a teammate's arrow");
        assertNull(Combat.attacker(new Hit("minecraft:player_attack", null, mate), listed, t), "no cause given");
        assertTrue(Combat.byTeammate(new Hit("minecraft:arrow", mate, arrow), t));
        assertFalse(Combat.byTeammate(new Hit("minecraft:arrow", stranger, arrow), t));
        assertFalse(Combat.byTeammate(new Hit("minecraft:fall", null, null), t));
        Thing byName = new Thing(5, "player", 0, 64, 0, null, "MATE");
        Teammates named = new Teammates();
        named.configured(() -> List.of("mate"));
        assertNull(Combat.attacker(new Hit("minecraft:player_attack", byName, byName), listed, named), "by name");

        // nobody a teammate: exactly the old rule
        Teammates none = new Teammates();
        Thing pillager = new Thing(412, "pillager", 10.5, 64, -3.5);
        for (Hit h : List.of(new Hit("minecraft:player_attack", mate, mate), new Hit("minecraft:arrow", pillager, arrow),
            new Hit("minecraft:fall", null, null), new Hit("minecraft:arrow", null, arrow)))
            assertSame(Combat.attacker(h, listed), Combat.attacker(h, listed, none), h.describe());
    }

    @Test
    void aTeammateIsNeverCountedInAFightArea() {
        Teammates t = mates();
        FightArea plain = new FightArea(Set.of("player", "pillager"), 0, 10, 60, 70, 0, 10, 5, 64, 5, 16, true);
        Mob mate = teammate(5, 64, 5);
        Mob stranger = stranger(6, 64, 5);
        Mob pillager = new Mob("pillager", 7, 64, 5);
        assertTrue(plain.contains(mate), "without the exemption a listed player counts");
        FightArea area = plain.exempting(m -> Combat.teammate(t, m));
        assertFalse(area.contains(mate));
        assertTrue(area.contains(stranger));
        assertTrue(area.contains(pillager));
        assertEquals(List.of(stranger, pillager), area.filter(List.of(mate, stranger, pillager)));
        assertEquals(stranger, area.nearest(List.of(mate, stranger, pillager), 5, 64, 5).orElseThrow(),
            "the nearest is the stranger, the teammate standing nearer is skipped");
        assertEquals(stranger, area.target(List.of(mate, stranger), Set.of(mate.id()), 5, 64, 5).orElseThrow(),
            "never gone for, even named as an attacker");

        assertEquals(List.of(stranger, pillager), Combat.withoutTeammates(List.of(mate, stranger, pillager), t));
        List<Mob> mobs = List.of(mate, stranger);
        assertSame(mobs, Combat.withoutTeammates(mobs, new Teammates()), "nobody a teammate: the list itself");
        assertFalse(Combat.teammate(t, new Mob("pillager", 0, 0, 0)), "a mob has no identity");
    }

    // ------------------------------------------------------------------------------------- the platform's fight

    @Test
    void aTeammateOnTheFightingPlatformStartsNoFight() {
        Sim sim = sim(mates());
        Job job = soulJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> building(sim), LONG), sim.state());
        Mob mate = teammate(250.5, 64, 20.5);
        sim.world.mobList.add(mate);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.fights.isEmpty(), "no fight: " + sim.bot.history);
        assertTrue(sim.notifier.of("info").stream().noneMatch(m -> m.title().equals("Fighting mobs")));
        assertEquals(List.of(mate), sim.world.mobList, "the teammate is left alone");
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void aStrangerOnTheFightingPlatformIsFoughtAsBefore() {
        Sim sim = sim(mates());
        Job job = soulJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> building(sim), LONG), sim.state());
        sim.world.mobList.add(stranger(250.5, 64, 20.5));
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.fights.size());
        assertTrue(sim.world.mobList.isEmpty(), "the stranger was fought");
    }

    @Test
    void nobodyATeammateEveryListedPlayerIsFoughtAsBefore() {
        Sim sim = sim(new Teammates());
        Job job = soulJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> building(sim), LONG), sim.state());
        sim.world.mobList.add(teammate(250.5, 64, 20.5)); // the account, but not listed: a player like any
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.fights.size());
        assertTrue(sim.world.mobList.isEmpty());
    }

    @Test
    void aFightForAPillagerLeavesTheTeammateBesideItAlone() {
        Sim sim = sim(mates());
        Job job = soulJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> building(sim), LONG), sim.state());
        Mob mate = teammate(250.5, 64, 21.5);
        Mob pillager = new Mob("pillager", 250.5, 64, 20.5, ids++);
        sim.world.mobList.add(mate);
        sim.world.mobList.add(pillager);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.fights.size());
        assertFalse(sim.bot.fights.getFirst().contains(mate), "the fight's area never counts the teammate");
        assertEquals(List.of(pillager), sim.bot.killed);
        assertEquals(List.of(mate), sim.world.mobList);
        assertTrue(sim.notifier.of("info").stream().anyMatch(m -> m.body().contains("1 pillager")
            && !m.body().contains("player")), "the alert names the pillager only: " + sim.notifier.of("info"));
    }

    @Test
    void theConfigListExemptsByName() {
        Teammates t = new Teammates();
        Sim sim = sim(t);
        t.configured(() -> sim.config.teammates);
        sim.config.teammates.add("mate");
        Job job = soulJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> building(sim), LONG), sim.state());
        Mob mate = Mob.player(250.5, 64, 20.5, ids++, UUID.randomUUID(), "Mate"); // the name decides
        sim.world.mobList.add(mate);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.fights.isEmpty(), sim.bot.history.toString());
        assertEquals(List.of(mate), sim.world.mobList);
    }

    // ------------------------------------------------------------------------------------- hits and self-defence

    @Test
    void aTeammatesHitStartsNoSelfDefenceAStrangersDoes() {
        Sim sim = workingOnMoss(mates());
        Mob mate = teammate(sim.world.x + 2, sim.world.y, sim.world.z + 1);
        sim.world.mobList.add(mate);
        Thing m = thing(mate);
        sim.inv.health = 18f;
        sim.engine.onHit(new Hit("minecraft:player_attack", m, m));
        sim.run(200);
        assertTrue(sim.bot.fights.isEmpty() && sim.bot.retreats.isEmpty(), "a teammate's hit: " + sim.bot.history);
        assertEquals(List.of(mate), sim.world.mobList);

        Mob stranger = stranger(sim.world.x + 4, sim.world.y, sim.world.z + 1);
        sim.world.mobList.add(stranger);
        Thing s = thing(stranger);
        sim.engine.onHit(new Hit("minecraft:player_attack", s, s));
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 60), sim.state());
        FightArea area = sim.bot.fights.getFirst();
        assertTrue(area.minX() > area.maxX(), "self-defense on moss");
        assertTrue(sim.runUntil(() -> !sim.world.mobList.contains(stranger), 2000), sim.state());
        assertTrue(sim.world.mobList.contains(mate), "the teammate beside it is never a target");
        assertFalse(area.contains(mate));
    }

    @Test
    void nobodyATeammateAListedPlayersHitIsSelfDefenceAsBefore() {
        Sim sim = workingOnMoss(new Teammates());
        Mob player = teammate(sim.world.x + 2, sim.world.y, sim.world.z + 1);
        sim.world.mobList.add(player);
        Thing p = thing(player);
        sim.engine.onHit(new Hit("minecraft:player_attack", p, p));
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 60), sim.state());
    }

    @Test
    void theHurtBotRetreatsFromThePillagerNotFromTheTeammate() {
        Sim sim = sim(mates());
        sim.config.combat.selfDefense = false; // no fight possible on moss: the health hold retreats
        sim.bot.regenTicks = 10;
        sim.bot.placementsPerTick = 1;
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob mate = teammate(sim.world.x - 3, sim.world.y, sim.world.z + 1);
        Mob pillager = new Mob("pillager", sim.world.x + 6, sim.world.y, sim.world.z + 1, ids++);
        sim.world.mobList.add(mate);
        sim.world.mobList.add(pillager);
        sim.inv.health = 7.9f;
        sim.arrowFrom(pillager);
        assertTrue(sim.runUntil(() -> !sim.bot.retreats.isEmpty(), 60), sim.state());
        assertEquals(List.of(pillager), sim.bot.retreats.getFirst(), "away from the pillager only");
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }

    @Test
    void theHurtBotRetreatsFromAListedStrangerToo() {
        Sim sim = sim(mates());
        sim.config.combat.selfDefense = false;
        sim.bot.regenTicks = 10;
        sim.bot.placementsPerTick = 1;
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob stranger = stranger(sim.world.x - 3, sim.world.y, sim.world.z + 1);
        Mob pillager = new Mob("pillager", sim.world.x + 6, sim.world.y, sim.world.z + 1, ids++);
        sim.world.mobList.add(stranger);
        sim.world.mobList.add(pillager);
        sim.inv.health = 7.9f;
        sim.arrowFrom(pillager);
        assertTrue(sim.runUntil(() -> !sim.bot.retreats.isEmpty(), 60), sim.state());
        assertEquals(Set.of(stranger, pillager), Set.copyOf(sim.bot.retreats.getFirst()));
    }

    @Test
    void pausedTheBotFightsThePillagerButNeverTheTeammate() {
        Sim sim = workingOnMoss(mates());
        sim.engine.pause("lunch");
        sim.run(40);
        int tasks = sim.bot.history.size();
        Mob mate = teammate(sim.world.x - 2, sim.world.y, sim.world.z + 1);
        sim.world.mobList.add(mate);
        Thing m = thing(mate);
        sim.engine.onHit(new Hit("minecraft:player_attack", m, m));
        sim.run(100);
        assertEquals(tasks, sim.bot.history.size(), "a teammate's hit while paused: nothing moves");

        Mob pillager = new Mob("pillager", sim.world.x + 6, sim.world.y, sim.world.z + 1, ids++);
        sim.world.mobList.add(pillager);
        sim.inv.health = 16.5f;
        sim.arrowFrom(pillager);
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 20), sim.state());
        assertFalse(sim.bot.fights.getFirst().contains(mate));
        assertTrue(sim.runUntil(() -> !sim.world.mobList.contains(pillager) && sim.bot.lastTask.isFinished(), 400),
            sim.state());
        assertEquals(List.of(pillager), sim.bot.killed);
        assertEquals(List.of(mate), sim.world.mobList);
        assertTrue(sim.engine.status().paused(), "still paused");
    }
}
