package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.bot.FightArea;
import io.github.digitalisp.mapart.bot.WorldView.Mob;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live 2026-10-12 (j0002 on moss, STOCK_CHECK): two pillagers shot the bot for two minutes on the moss walkway (49
 * arrow hits in the replay). Moss fights nothing, so the bot ate, stood still for its health, went BLOCKED, popped its
 * totem and died. Now a bot hit by a listed mob fights listed mobs around it on any platform
 * ({@code combat.selfDefense}), a fight comes before standing still, and without a totem nothing that walks runs
 * ({@code safety.requireTotem}). A hit is the server's damage event with a listed mob behind it ({@link Sim#arrowFrom});
 * the health drops beside them stand for what the hits cost.
 */
class SelfDefenseTest {
    private static final int LONG = 80_000;
    private static final String TOTEM = "minecraft:totem_of_undying";
    private static int mobIds = 400;

    private static Sim sim() {
        Sim sim = new Sim();
        sim.bot.placementsPerTick = 1; // long lanes: the work is still under way when the arrows come
        sim.modules.state.put("KillAura", false);
        sim.bot.killAuraOn = () -> sim.modules.isEnabled("KillAura");
        return sim;
    }

    private static Job mossJob(Sim sim) {
        TileArt art = Sim.stripes(64, 4);
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(Sim.BLACK, 1728));
        return sim.addJob("j0101", "Moss", 1, 1, List.of("moss"), (x, z) -> art);
    }

    private static Mob pillagerNextToTheBot(Sim sim) {
        return new Mob("pillager", sim.world.x + 6, sim.world.y, sim.world.z + 1, mobIds++);
    }

    @Test
    void aHurtBotOnMossFightsThePillagersShootingItThenFinishesTheMap() {
        Sim sim = sim();
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.run(40);
        assertTrue(sim.bot.fights.isEmpty(), "a pillager that does not hurt the bot is left alone on moss");

        sim.inv.health = 16.5f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 60), sim.state());
        FightArea a = sim.bot.fights.getFirst();
        assertTrue(a.minX() > a.maxX(), "self-defense: only the mobs around the bot, no platform box");
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.world.mobList.isEmpty(), "the pillager is dead");
        assertEquals(List.of("KillAura=true"), sim.bot.fightModules);
        assertFalse(sim.modules.isEnabled("KillAura"), "back off afterwards");
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void underFireAtLowHealthItFightsRatherThanStandStill() {
        Sim sim = sim();
        sim.bot.regenTicks = 10;
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.inv.health = 7.9f; // the health hold starts, and the bot is being shot
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 60), sim.state());
        assertNotEquals(Phase.BLOCKED, sim.phase());
        assertTrue(sim.bot.escapes.isEmpty(), "a fight, not a walk away");
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.world.mobList.isEmpty());
    }

    @Test
    void withoutAFightTheHurtBotRetreatsFromThePillagersInsteadOfStandingStill() {
        Sim sim = sim();
        sim.config.combat.selfDefense = false; // no fight possible on moss
        sim.bot.regenTicks = 10;
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.inv.health = 7.9f; // shot: the health hold starts
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.retreats.isEmpty(), 60), sim.state());
        assertEquals(List.of(p), sim.bot.retreats.getFirst(), "away from the pillager");
        assertTrue(sim.bot.escapes.isEmpty() && sim.bot.fights.isEmpty());
        assertTrue(sim.runUntil(() -> sim.bot.lastTask.name().startsWith("retreat") && sim.bot.lastTask.isFinished(), 60),
            sim.state());
        assertTrue(p.distSq(sim.world.x, sim.world.y, sim.world.z) > 16 * 16, "out of its range");
        assertNotEquals(Phase.BLOCKED, sim.phase());
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }

    @Test
    void anUnreachablePillagerThatDoesNotHurtTheBotIsLeftAloneUntilItDoes() {
        // live 2026-10-12 with fightMobs on for moss: a pillager in the water channel under the walkway, unreachable
        // and harmless, stopped work for 120 s plus 60 s of cooldown again and again
        Sim sim = sim();
        sim.moss.fightMobs = true;
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob p = new Mob("pillager", sim.world.x + 6, sim.world.y - 6, sim.world.z + 1, mobIds++);
        sim.bot.unreachableMobs.add(p);
        sim.world.mobList.add(p);
        assertTrue(sim.runUntil(() -> sim.bot.reportedUnreachable.size() == 1, 200), sim.state());
        int fights = sim.bot.fights.size();
        int placed = sim.bot.placed;
        sim.run(1_000);
        assertEquals(fights, sim.bot.fights.size(), "left alone while it does not hurt the bot");
        assertTrue(sim.bot.placed > placed, "the work goes on");

        sim.inv.health = 15f; // it hurts the bot after all: fought again at once
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == fights + 1, 40), sim.state());
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(fights + 1, sim.bot.fights.size(), "and left alone again afterwards");
    }

    @Test
    void selfDefenseOffLeavesMossAlone() {
        Sim sim = sim();
        sim.config.combat.selfDefense = false;
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.inv.health = 16.5f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.fights.isEmpty());
    }

    @Test
    void theDefenceAreaRules() {
        MapArtConfig.CombatCfg cfg = new MapArtConfig.CombatCfg();
        FightArea a = Combat.defenceArea(cfg, Phase.STOCK_CHECK, 100.5, 174, 50.5);
        assertNotNull(a, "the stock scan fights back");
        assertTrue(a.contains(new Mob("pillager", 110.5, 174, 55.5)));
        assertFalse(a.contains(new Mob("pillager", 120.5, 174, 50.5)), "beyond the radius");
        assertFalse(a.contains(new Mob("cow", 101.5, 174, 50.5)));
        assertNotNull(Combat.defenceArea(cfg, Phase.IDLE, 0, 0, 0), "a requested scan walks the storage in IDLE");
        assertNull(Combat.defenceArea(cfg, Phase.WAIT_CLEAR, 0, 0, 0), "never while the machines run");
        assertNull(Combat.defenceArea(cfg, Phase.BLOCKED, 0, 0, 0));
        cfg.selfDefense = false;
        assertNull(Combat.defenceArea(cfg, Phase.BUILD, 0, 0, 0));
        cfg.selfDefense = true;
        cfg.enabled = false;
        assertNull(Combat.defenceArea(cfg, Phase.BUILD, 0, 0, 0), "the global kill switch");
    }

    // ------------------------------------------------------------------------------------------ no totem

    private static boolean laneRunning(Sim sim) {
        BotTask t = sim.bot.lastTask;
        return t != null && t.name().startsWith("lane") && !t.isFinished();
    }

    @Test
    void withoutATotemNothingWalksUntilOneIsBackInTheOffhand() {
        Sim sim = sim();
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        sim.inv.offhand.clear(); // the totem popped
        assertTrue(sim.runUntil(() -> sim.notifier.has("alert", "No totem"), BuildEngine.TOTEM_GONE_TICKS + 20),
            sim.state());
        assertTrue(sim.runUntil(() -> sim.bot.parks > 0, 40), "the lane parks: " + sim.state());
        int placed = sim.bot.placed;
        int tasks = sim.bot.history.size();
        sim.run(400);
        assertEquals(placed, sim.bot.placed, "nothing placed without a totem");
        assertEquals(tasks, sim.bot.history.size(), "no new task without a totem");
        assertTrue(sim.engine.status().note().contains("no totem"), sim.state());
        assertNotEquals(Phase.BLOCKED, sim.phase());

        sim.inv.offhand.put(TOTEM, 1); // the owner put one back
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(sim.bot.parks, sim.bot.resumes);
        assertEquals(1, sim.notifier.of("alert").stream().filter(m -> m.title().equals("No totem")).count(),
            "one alert per episode");
    }

    /**
     * Drill gs2 (local 1x Grim, 100 ms each way): after an airplace the inventory cache showed carpet in the offhand and
     * no totem anywhere, while the server had the totem; "No totem" held the bot for the remaining 9 minutes. Now the
     * guard's server check comes first: the lane parks, the repair asks the server, the totem shows, the job goes on and
     * no "No totem" alert goes out.
     */
    @Test
    void aTotemLostOnlyFromTheCacheIsCheckedWithTheServerNotAlerted() {
        Sim sim = sim();
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        sim.inv.offhand.clear(); // the stale cache: no totem anywhere
        sim.bot.offhandCheck = true;
        assertTrue(sim.runUntil(() -> sim.bot.offhandChecks > 0, BuildEngine.TOTEM_GONE_TICKS + 200), sim.state());
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertFalse(sim.notifier.has("alert", "No totem"), sim.notifier.toString());
        assertEquals(1, sim.bot.offhandChecks);
    }

    /** The server's answer has no totem either: then "No totem" goes out, after the check, not before it. */
    @Test
    void aTotemTheServerConfirmsGoneIsAlertedAfterTheCheck() {
        Sim sim = sim();
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        sim.inv.offhand.clear();
        sim.bot.offhandCheck = true;
        sim.bot.checkFinds = 0;
        assertTrue(sim.runUntil(() -> sim.notifier.has("alert", "No totem"), BuildEngine.TOTEM_GONE_TICKS + 400),
            sim.state());
        assertEquals(1, sim.bot.offhandChecks, "the alert only after the server's answer");
    }

    @Test
    void withoutATotemTheBotStillFightsBack() {
        Sim sim = sim();
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        sim.inv.offhand.clear();
        assertTrue(sim.runUntil(() -> sim.notifier.has("alert", "No totem"), BuildEngine.TOTEM_GONE_TICKS + 20),
            sim.state());
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.inv.health = 15f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> sim.world.mobList.isEmpty(), 400), "fought without a totem: " + sim.state());
        sim.inv.offhand.put(TOTEM, 1);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }

    @Test
    void requireTotemOffKeepsWorking() {
        Sim sim = sim();
        sim.config.safety.requireTotem = false;
        sim.inv.offhand.clear();
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertFalse(sim.notifier.has("alert", "No totem"));
    }

    @Test
    void aTotemInTheInventoryCountsOnlyWhenTheOffhandRepairPutsItBack() {
        Sim sim = sim();
        sim.inv.offhand.clear();
        sim.inv.add(TOTEM, 1);
        sim.config.build.placeMode = "LOOK";
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.notifier.has("alert", "No totem"), 2_000), sim.state());
        assertTrue(sim.notifier.of("alert").stream().anyMatch(m -> m.body().contains("nothing puts one back")),
            sim.notifier.toString());
        assertNotEquals(JobStatus.DONE, job.status);
    }

    // ------------------------------------------------------------------------------------------ while stopped

    /**
     * The live death once more: the health hold went BLOCKED ("still dropping while standing still") and the bot stood
     * there under the arrows. Self-defence was off here only to reach BLOCKED that way; then it is on, as by default.
     */
    private static Sim blockedInAHealthHold() {
        Sim sim = sim();
        sim.config.combat.selfDefense = false;
        sim.config.safety.leaveWhenHurt = false;
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        sim.inv.health = 7.9f;
        sim.run(20);
        sim.inv.health = 5.5f;
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, 200), sim.state());
        sim.config.combat.selfDefense = true;
        sim.config.safety.leaveWhenHurt = true;
        sim.run(BuildEngine.HURT_WINDOW_TICKS + 20); // that drop is no longer "being hurt"
        return sim;
    }

    @Test
    void aBlockedBotFightsThePillagerHurtingItAndStaysBlocked() {
        Sim sim = blockedInAHealthHold();
        String reason = sim.engine.status().note();
        assertTrue(reason.contains("still dropping"), reason);
        int tasks = sim.bot.history.size();
        int placed = sim.bot.placed;
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.run(100);
        assertEquals(tasks, sim.bot.history.size(), "not hurt: nothing moves");
        assertTrue(sim.bot.fights.isEmpty());

        sim.inv.health -= 1f; // an arrow
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 20), sim.state());
        assertEquals(Phase.BLOCKED, sim.phase(), "the fight does not end BLOCKED");
        assertTrue(sim.notifier.has("info", "Fighting mobs"), sim.notifier.toString());
        assertTrue(sim.runUntil(() -> sim.world.mobList.isEmpty() && sim.bot.lastTask.isFinished(), 400), sim.state());
        sim.run(100);
        assertEquals(Phase.BLOCKED, sim.phase());
        assertEquals(reason, sim.engine.status().note(), "the BLOCKED reason is shown again");
        assertEquals(placed, sim.bot.placed, "no work while BLOCKED");
        assertEquals(1, sim.bot.fights.size());
    }

    @Test
    void aPausedBotFightsBackAndStaysPaused() {
        Sim sim = sim();
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        sim.engine.pause("lunch");
        sim.run(40);
        int tasks = sim.bot.history.size();
        int placed = sim.bot.placed;
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.run(100);
        assertEquals(tasks, sim.bot.history.size(), "not hurt: nothing moves");

        sim.inv.health = 16.5f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 20), sim.state());
        assertTrue(sim.bot.fights.getFirst().chase(), "a plain pause chases the pillager");
        assertTrue(sim.runUntil(() -> sim.world.mobList.isEmpty() && sim.bot.lastTask.isFinished(), 400), sim.state());
        sim.run(200);
        assertTrue(sim.engine.status().paused(), "still paused");
        assertEquals(placed, sim.bot.placed, "no work while paused");
        assertEquals(tasks + 1, sim.bot.history.size(), "the fight and nothing else: " + sim.bot.history);
    }

    @Test
    void stoppedWithoutAFightTheHurtBotRetreatsAtMostTwiceThenStands() {
        Sim sim = sim();
        sim.config.combat.selfDefense = false;
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        sim.engine.pause("lunch");
        sim.run(40);
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.inv.health = 16.5f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> sim.bot.retreats.size() == 1, 20), sim.state());
        for (int i = 0; i < 3; i++) {
            // the pillager follows the bot and keeps shooting
            sim.run(40);
            sim.world.mobList.clear();
            Mob q = pillagerNextToTheBot(sim);
            sim.world.mobList.add(q);
            sim.inv.health -= 1f;
            sim.arrowFrom(q);
            sim.run(20);
        }
        assertEquals(2, sim.bot.retreats.size(), "two leaves per hurt episode: " + sim.bot.history);
        assertTrue(sim.bot.fights.isEmpty());
        assertTrue(sim.engine.status().paused());
    }

    @Test
    void safetyStoppedItFightsOnlyWhatComesInReachAndNeverRetreats() {
        Sim sim = sim();
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        sim.engine.emergencyStop("server teleported the bot 40.0 blocks");
        sim.run(40);
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.inv.health = 16.5f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 20), sim.state());
        assertFalse(sim.bot.fights.getFirst().chase(), "a safety stop does not walk the bot");

        Sim off = sim();
        off.config.combat.selfDefense = false;
        mossJob(off);
        off.start();
        assertTrue(off.runUntil(() -> laneRunning(off) && off.bot.placed > 100, LONG), off.state());
        off.engine.emergencyStop("server teleported the bot 40.0 blocks");
        off.run(40);
        Mob q = pillagerNextToTheBot(off);
        off.world.mobList.add(q);
        off.inv.health = 16.5f;
        off.arrowFrom(q);
        off.run(100);
        assertTrue(off.bot.retreats.isEmpty() && off.bot.fights.isEmpty(), off.bot.history.toString());
    }

    @Test
    void aBackOffDoesNotCutTheFightShort() {
        Sim sim = sim();
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        sim.engine.backoff("5 setbacks in 2 s", 60_000);
        sim.run(20);
        int placed = sim.bot.placed;
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.world.mobList.add(new Mob("pillager", sim.world.x - 6, sim.world.y, sim.world.z - 1, mobIds++));
        sim.inv.health = 16.5f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 20), sim.state());
        assertTrue(sim.runUntil(() -> sim.world.mobList.isEmpty() && sim.bot.lastTask.isFinished(), 400),
            "both dead, backing off: " + sim.state());
        assertEquals(1, sim.bot.fights.size(), "one fight, never cancelled and restarted");
        assertEquals(placed, sim.bot.placed, "no work while backing off");
        assertEquals(BuildEngine.BACKOFF_NOTE, sim.engine.status().note(), sim.state());
    }

    @Test
    void duringTheClearTheHurtBotNeitherLeavesNorFights() {
        Sim sim = sim();
        sim.bot.placementsPerTick = 50;
        sim.clearWorks = false; // the art stays: the bot waits for the clear until it times out
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.WAIT_CLEAR, LONG), sim.state());
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.inv.health = 7.9f; // the health hold starts, and the bot is being shot
        sim.arrowFrom(p);
        sim.run(20);
        sim.inv.health = 6.9f; // still dropping while it stands
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> sim.phase() != Phase.WAIT_CLEAR && sim.phase() != Phase.CLEAR, 2_000)
            || sim.phase() == Phase.WAIT_CLEAR || sim.phase() == Phase.CLEAR, sim.state());
        assertTrue(sim.bot.retreats.isEmpty() && sim.bot.escapes.isEmpty(), "never walks off its clear spot: "
            + sim.bot.history);
        assertTrue(sim.bot.fights.isEmpty(), sim.bot.history.toString());
        sim.inv.health = 5.5f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, 400), sim.state());
        sim.inv.health -= 1f; // BLOCKED from the clear: still no fight, no retreat
        sim.arrowFrom(p);
        sim.run(100);
        assertTrue(sim.bot.retreats.isEmpty() && sim.bot.escapes.isEmpty() && sim.bot.fights.isEmpty(),
            sim.bot.history.toString());
        assertNotEquals(JobStatus.DONE, job.status);
    }

    @Test
    void aFightThatCannotReachThePillagerHurtingTheBotRetreatsFromIt() {
        Sim sim = sim();
        Job job = mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob p = new Mob("pillager", sim.world.x + 6, sim.world.y - 3, sim.world.z + 1, mobIds++); // across the water
        sim.bot.unreachableMobs.add(p);
        sim.world.mobList.add(p);
        sim.inv.health = 17f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 20), sim.state());
        sim.run(5);
        sim.inv.health = 16f; // it keeps shooting while the bot cannot get at it
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.retreats.isEmpty(), 20), "away from it: " + sim.state());
        assertEquals(List.of(p), sim.bot.retreats.getFirst());
        assertTrue(sim.bot.reportedUnreachable.isEmpty(), "not just set aside: it hurts the bot");
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertFalse(sim.bot.died);
        assertEquals(1, sim.bot.fights.size(), "back near it after the retreat, but not hurt again: no new fight "
            + sim.bot.history);
    }

    @Test
    void afterAFightRanOutOfTimeOnlyAMobAtTheBotIsFoughtInTheCooldown() {
        Sim sim = sim();
        sim.config.combat.maxSeconds = 5;
        sim.config.combat.cooldownSeconds = 60;
        sim.bot.killTicks = 1_000_000; // the fight never ends by itself
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob p = pillagerNextToTheBot(sim);
        sim.world.mobList.add(p);
        sim.inv.health = 19f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> sim.notifier.has("alert", "Fight not over"), 200), sim.state());
        sim.bot.killTicks = 20;
        sim.run(20);
        sim.inv.health -= 1f; // the pillager (6 blocks away) shoots on: ignored for the cooldown
        sim.arrowFrom(p);
        sim.run(60);
        assertEquals(1, sim.bot.fights.size(), sim.bot.history.toString());

        Mob zombie = new Mob("zombie", sim.world.x + 1.5, sim.world.y, sim.world.z, mobIds++);
        sim.world.mobList.add(zombie);
        sim.inv.health -= 1f; // a zombie at the bot hits it
        sim.blowFrom(zombie);
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == 2, 40), "fought in the cooldown: " + sim.state());
    }

    @Test
    void aGoneUnreachableMobNoLongerHidesANewOneAtItsSpot() {
        Sim sim = sim();
        sim.moss.fightMobs = true;
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Mob p = new Mob("pillager", sim.world.x + 6, sim.world.y - 6, sim.world.z + 1, mobIds++);
        sim.bot.unreachableMobs.add(p);
        sim.world.mobList.add(p);
        assertTrue(sim.runUntil(() -> sim.bot.reportedUnreachable.size() == 1, 200), sim.state());
        int fights = sim.bot.fights.size();
        sim.world.mobList.remove(p); // killed by a player, despawned
        sim.run(100); // a few combat polls without it
        sim.world.mobList.add(new Mob("pillager", p.x() + 1, p.y(), p.z())); // a new one where it was, reachable
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == fights + 1, 40),
            "not hidden by the old record, though the bot is not hurt: " + sim.state());
    }

    @Test
    void aMobSetAsideStaysSetAsideWhileTheBotIsAwayFromIt() {
        // local run 2026-10-13: the failed fight's retreat took the bot out of the pillager's range, the record went
        // after three looks, and the scan walking back fought the same pillager again within the hurt window
        Sim sim = sim();
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        Pos lane = sim.world.playerBlock();
        Mob p = new Mob("pillager", sim.world.x + 6, sim.world.y - 3, sim.world.z + 1, mobIds++); // across the water
        sim.bot.unreachableMobs.add(p);
        sim.world.mobList.add(p);
        sim.inv.health = 17f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.fights.isEmpty(), 20), sim.state());
        sim.run(5);
        sim.inv.health = 16f;
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> !sim.bot.retreats.isEmpty() && sim.bot.lastTask.isFinished(), 40), sim.state());
        Pos away = lane.offset(-30, 0, 0);
        for (int i = 0; i < 40; i++) { // four combat polls with the pillager out of range
            sim.world.moveTo(away);
            sim.tick();
        }
        sim.world.moveTo(lane); // back where it was shot, still within the 10 s after the hit
        sim.run(40);
        assertEquals(1, sim.bot.fights.size(), "set aside until the next hit: " + sim.bot.history);
        sim.inv.health = 15f; // the next hit
        sim.arrowFrom(p);
        assertTrue(sim.runUntil(() -> sim.bot.fights.size() == 2, 40), sim.state());
    }

    @Test
    void aTwoSecondOffhandBlipAfterASetbackIsNoMissingTotem() {
        // live 2026-10-13 00:18: after a setback the offhand read as gray terracotta for about 2 s, then the totem
        Sim sim = sim();
        mossJob(sim);
        sim.start();
        assertTrue(sim.runUntil(() -> laneRunning(sim) && sim.bot.placed > 100, LONG), sim.state());
        int parks = sim.bot.parks;
        sim.inv.offhand.clear();
        sim.inv.offhand.put("minecraft:gray_terracotta", 1);
        sim.run(60); // 3 s
        sim.inv.offhand.clear();
        sim.inv.offhand.put(TOTEM, 1);
        int placed = sim.bot.placed;
        sim.run(200);
        assertFalse(sim.notifier.has("alert", "No totem"), sim.notifier.toString());
        assertEquals(parks, sim.bot.parks, "no hold for a blip");
        assertTrue(sim.bot.placed > placed, "the work went on");

        sim.inv.offhand.clear(); // a real pop: gone for good
        assertTrue(sim.runUntil(() -> sim.notifier.has("alert", "No totem"), BuildEngine.TOTEM_GONE_TICKS + 20),
            sim.state());
        assertTrue(BuildEngine.TOTEM_GONE_TICKS >= 100, "5 s or more");
        assertTrue(sim.runUntil(() -> sim.bot.parks > parks, 40), "the lane parks: " + sim.state());
    }
}
