package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.engine.Sim.RED;
import static io.github.digitalisp.mapart.engine.Sim.WHITE;

/**
 * The elytra legs' supply ({@code build.elytraLegs}): the first RESTOCK takes one
 * elytra and a stack of rockets from the rocket chest (listed, or named by a sign), the spare stays; a worn-out elytra
 * Mending cannot raise goes back for the spare; no chest or an empty one is one log line a RESTOCK and the job goes
 * on; the elytra and the rockets are never deposited, dropped or thrown, with the switch on or off; the switch
 * turned off mid-run loses nothing.
 */
class ElytraKitSupplyTest {
    private static final int LONG = 200_000;
    private static final String ELYTRA = "minecraft:elytra";
    private static final String ROCKET = "minecraft:firework_rocket";
    private static final Pos ROCKETS = new Pos(-42, 61, 100);
    private static final Pos JUNK_STAND = new Pos(-60, 64, -66);
    private static final Pos JUNK_AT = new Pos(-56, 64, -66);

    /** A 2-row white job on moss with the switch on and a rocket chest holding 2 elytras and 4 stacks of rockets. */
    private static Sim sim(RecordingLogger log, boolean listed, Map<String, Integer> chest) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.config.build.elytraLegs = true;
        sim.inv.add(RED, 100); // leftovers: the RESTOCK deposits them
        sim.bot.addContainer(ROCKETS, chest);
        if (listed) sim.config.site.storage.rocketChests.add(ROCKETS);
        return sim;
    }

    private static Job job(Sim sim, String id) {
        return sim.addJob(id, "Kit", 1, 1, List.of("moss"), (x, z) -> Sim.single(WHITE, 2));
    }

    private static long lines(RecordingLogger log, String part) {
        return log.at(Level.INFO).stream().filter(l -> l.contains(part)).count();
    }

    @Test
    void theFirstRestockTakesOneElytraAndAStackOfRocketsTheSpareStays() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, Map.of(ELYTRA, 2, ROCKET, 256));
        Job job = job(sim, "j0401");
        sim.start();
        assertTrue(sim.runUntil(() -> sim.inv.count(ROCKET) > 0, LONG), sim.state() + log.lines);
        assertEquals(1, sim.inv.count(ELYTRA), "one elytra, never two");
        assertEquals(64, sim.inv.count(ROCKET));
        assertEquals(1, sim.bot.containers.get(ROCKETS).items.get(ELYTRA), "the spare stays in the chest");
        assertEquals(192, sim.bot.containers.get(ROCKETS).items.get(ROCKET));
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.inv.count(ELYTRA));
        assertEquals(64, sim.inv.count(ROCKET), "full: no further visit");
        assertEquals(1, sim.bot.elytraExchanges, "one elytra visit in the job");
        assertEquals(1, lines(log, "Elytra kit: took an elytra"));
        assertEquals(1, lines(log, "Elytra kit: took 64 rockets"));
    }

    @Test
    void aSignSayingRocketNamesTheChestWhenNoneIsListed() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, false, Map.of(ELYTRA, 1, ROCKET, 64));
        sim.world.set(ROCKETS, BlockSpec.of("minecraft:chest", Map.of("facing", "north", "type", "single")));
        sim.world.signList.add(new WorldView.Sign(ROCKETS.offset(0, 0, -1),
            BlockSpec.of("minecraft:oak_wall_sign", Map.of("facing", "north")), "{\"text\":\"Rockets\"}\n\"\"\n\"\"\n\"\""));
        Job job = job(sim, "j0402");
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertEquals(1, sim.inv.count(ELYTRA));
        assertEquals(64, sim.inv.count(ROCKET));
        assertEquals(1, lines(log, "Elytra kit: rocket chest found by its sign at " + ROCKETS), log.lines.toString());
        String report = sim.engine.rocketChestReport();
        assertTrue(report.startsWith("Rocket chest (build.elytraLegs on): found by its sign at " + ROCKETS.key()), report);
        assertTrue(report.contains("; sign " + ROCKETS.offset(0, 0, -1).key() + " oak_wall_sign[north] on " + ROCKETS.key()
            + " chest"), report);
    }

    @Test
    void theSignSeenBeforeTheRestockServesARestockOutOfItsView() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, false, Map.of(ELYTRA, 1, ROCKET, 64));
        Pos sign = ROCKETS.offset(0, 0, -1);
        sim.world.set(ROCKETS, BlockSpec.of("minecraft:chest", Map.of("facing", "north", "type", "single")));
        sim.world.signList.add(new WorldView.Sign(sign, BlockSpec.of("minecraft:oak_wall_sign", Map.of("facing", "north")),
            "\"rockets\""));
        Job job = job(sim, "j0414");
        sim.start();
        assertTrue(sim.runUntil(() -> lines(log, "Elytra kit: rocket chest found by its sign at " + ROCKETS) == 1, LONG),
            sim.state() + log.lines);
        assertEquals(0, lines(log, "phase GEAR -> RESTOCK"), "found before the first RESTOCK");
        sim.world.loaded = p -> !p.equals(ROCKETS) && !p.equals(sign); // out of view from here on
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertEquals(1, sim.inv.count(ELYTRA));
        assertEquals(64, sim.inv.count(ROCKET));
        assertEquals(0, lines(log, "no rocket chest"), log.lines.toString());
    }

    @Test
    void noRocketChestIsOneLineARestockAndTheJobGoesOn() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, false, Map.of());
        Job job = job(sim, "j0403");
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        long restocks = lines(log, "phase BUILD -> RESTOCK") + lines(log, "-> RESTOCK");
        long noChest = lines(log, "no rocket chest (site.storage.rocketChests is empty");
        assertTrue(noChest >= 1, log.lines.toString());
        assertTrue(noChest <= restocks, noChest + " lines, " + restocks + " restocks");
        assertEquals(0, sim.inv.count(ELYTRA));
        assertTrue(sim.notifier.messages.stream().noneMatch(m -> (m.title() + m.body()).contains("rocket")),
            "no Discord notice: " + sim.notifier.messages);
    }

    @Test
    void anEmptyChestIsLoggedAndTheJobGoesOn() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, Map.of());
        Job job = job(sim, "j0404");
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertTrue(lines(log, "Elytra kit: no elytra above 43 uses in the rocket chest") >= 1, log.lines.toString());
        assertTrue(lines(log, "Elytra kit: no flight rockets (plain, duration 1-3) in the rocket chest") >= 1);
        assertEquals(0, sim.inv.count(ELYTRA));
    }

    @Test
    void anEmptyChestIsNotVisitedAgainForTenMinutes() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, Map.of());
        Job first = job(sim, "j0411");
        sim.start();
        assertTrue(sim.runUntil(() -> first.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        long visits = sim.bot.withdrawLog.stream().filter(e -> e.getKey().equals(ROCKETS)).count();
        assertEquals(1, sim.bot.elytraExchanges, "one look for the elytra");
        assertEquals(1, visits, "one look for the rockets");
        assertEquals(0, lines(log, "had no elytra at the last look"), "the RESTOCK of the look holds nothing: "
            + log.lines);
        sim.inv.add(RED, 100);
        Job second = job(sim, "j0412");
        assertTrue(sim.runUntil(() -> second.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertEquals(1, sim.bot.elytraExchanges, "held: no second walk to the empty chest");
        assertEquals(visits, sim.bot.withdrawLog.stream().filter(e -> e.getKey().equals(ROCKETS)).count());
        assertTrue(lines(log, "had no elytra and no flight rockets at the last look") >= 1, log.lines.toString());
        sim.clock.advance(ElytraKit.EMPTY_RETRY_MS);
        sim.bot.containers.get(ROCKETS).items.putAll(Map.of(ELYTRA, 1, ROCKET, 64));
        sim.inv.add(RED, 100);
        Job third = job(sim, "j0413");
        assertTrue(sim.runUntil(() -> third.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertEquals(2, sim.bot.elytraExchanges, "ten minutes on: a new look");
        assertEquals(1, sim.inv.count(ELYTRA));
        assertEquals(64, sim.inv.count(ROCKET));
    }

    @Test
    void aWornOutElytraWithoutXpGoesBackForTheSpare() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, Map.of(ELYTRA, 1, ROCKET, 64));
        sim.inv.carry(new SimFakes.FakeInventory.FakeArmor(ELYTRA, 40, true)); // under the 10 % floor (43 of 432)
        sim.inv.add(ROCKET, 64);
        Job job = job(sim, "j0405");
        sim.start();
        assertTrue(sim.runUntil(() -> sim.bot.elytrasReturned > 0, LONG), sim.state() + log.lines);
        assertEquals(1, sim.inv.count(ELYTRA), "the spare came out, the worn one went in");
        assertTrue(sim.inv.carriedArmor.isEmpty(), "the worn-out record is gone: the one carried is new");
        assertEquals(1, sim.bot.containers.get(ROCKETS).items.get(ELYTRA));
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
    }

    @Test
    void aWornOutElytraWithXpBottlesStaysForItsMend() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, Map.of(ELYTRA, 1, ROCKET, 64));
        sim.inv.carry(new SimFakes.FakeInventory.FakeArmor(ELYTRA, 40, true));
        sim.inv.add(ROCKET, 64);
        sim.inv.add("minecraft:experience_bottle", 16);
        Job job = job(sim, "j0406");
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertEquals(0, sim.bot.elytraExchanges, "Mending and XP raise it: no exchange");
        assertEquals(1, sim.inv.count(ELYTRA));
    }

    /**
     * The elytra and the rockets, listed as junk on purpose: with a return chest (the leftovers are deposited), or
     * with a drop point and a junk spot beside it (the leftovers dropped, the junk thrown).
     */
    private static void neverLost(boolean on, boolean dropPoint) {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, Map.of());
        sim.config.build.elytraLegs = on;
        sim.inv.add(ELYTRA, 1);
        sim.inv.add(ROCKET, 64);
        sim.config.build.dropJunk = true;
        sim.config.build.junkItems.addAll(List.of(ELYTRA, ROCKET, "minecraft:rotten_flesh"));
        sim.inv.add("minecraft:rotten_flesh", 10);
        if (dropPoint) {
            sim.config.site.storage.returnChests.clear();
            MapArtConfig.DropPointCfg drop = new MapArtConfig.DropPointCfg();
            drop.standAt = new Pos(-65, 64, -66);
            drop.target = new Pos(-65, 60, -62);
            sim.config.site.storage.returnDrops.add(drop);
            sim.moss.junkDropStand = JUNK_STAND;
            sim.moss.junkDropAt = JUNK_AT;
        }
        Job job = job(sim, "j04" + (on ? "1" : "2") + (dropPoint ? "1" : "2"));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        String what = "switch " + (on ? "on" : "off") + (dropPoint ? ", drop point" : ", return chest");
        assertEquals(1, sim.inv.count(ELYTRA), what);
        assertEquals(64, sim.inv.count(ROCKET), what);
        assertFalse(sim.bot.droppedItems.containsKey(ELYTRA), what);
        assertFalse(sim.bot.droppedItems.containsKey(ROCKET), what);
        if (dropPoint) {
            assertTrue(sim.bot.droppedItems.containsKey("minecraft:rotten_flesh"), "the junk rule works: " + sim.bot.droppedItems);
            assertTrue(sim.bot.droppedItems.containsKey(RED), "the leftovers drop works: " + sim.bot.droppedItems);
        } else {
            assertTrue(sim.bot.containers.get(Sim.RETURN).items.containsKey(RED), "the leftovers deposit works");
        }
        for (var c : sim.bot.containers.entrySet()) {
            if (c.getKey().equals(ROCKETS)) continue;
            assertFalse(c.getValue().items.containsKey(ELYTRA), what + ": " + c.getKey() + " got an elytra");
            assertFalse(c.getValue().items.containsKey(ROCKET), what + ": " + c.getKey() + " got rockets");
        }
    }

    @Test
    void neverDepositedDroppedOrThrownWithTheSwitchOn() {
        neverLost(true, false);
        neverLost(true, true);
    }

    @Test
    void neverDepositedDroppedOrThrownWithTheSwitchOff() {
        neverLost(false, false);
        neverLost(false, true);
    }

    @Test
    void theSwitchTurnedOffMidRunLosesNothingAndStopsTheVisits() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, true, Map.of(ELYTRA, 2, ROCKET, 256));
        Job job = job(sim, "j0409");
        sim.start();
        assertTrue(sim.runUntil(() -> sim.inv.count(ROCKET) > 0, LONG), sim.state() + log.lines);
        sim.config.build.elytraLegs = false;
        long visits = sim.bot.withdrawLog.stream().filter(e -> e.getKey().equals(ROCKETS)).count();
        sim.inv.remove(ROCKET, 60); // flown: only 4 left, under the low mark
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.inv.count(ELYTRA), "carried: never deposited or dropped");
        assertEquals(4, sim.inv.count(ROCKET), "no top-up with the switch off, none lost");
        assertEquals(visits, sim.bot.withdrawLog.stream().filter(e -> e.getKey().equals(ROCKETS)).count());
        assertEquals(1, sim.bot.elytraExchanges);
    }

    @Test
    void aRocketChestInsideTheMaterialRegionIsNoMaterialChest() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log, false, Map.of());
        Pos inRegion = new Pos(-90, 61, 104); // the Sim's material region: x -100..-80, z 100..110
        sim.bot.addContainer(inRegion, Map.of(ELYTRA, 2, ROCKET, 128));
        sim.config.site.storage.rocketChests.add(inRegion);
        Job job = job(sim, "j0410");
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + log.lines);
        assertTrue(sim.store.stock().containersWith(ROCKET).isEmpty(), "not indexed: " + sim.store.stock().containers.keySet());
        assertEquals(1, sim.inv.count(ELYTRA));
        assertEquals(64, sim.inv.count(ROCKET));
    }
}
