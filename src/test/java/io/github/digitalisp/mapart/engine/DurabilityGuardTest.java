package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.BotTask;
import io.github.digitalisp.mapart.engine.SimFakes.FakeInventory.FakeArmor;
import io.github.digitalisp.mapart.engine.SimFakes.FakeInventory.FakeTool;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "It should never get into a situation where it breaks its own tools/boots" (the owner, 2026-10-23): the durability guard
 * in every state the bot ticks in. Synthetic coordinates.
 */
class DurabilityGuardTest {
    private static final int LONG = 150_000;
    private static final int MINUTE = 1_200;
    private static final String BOOTS = "minecraft:netherite_boots";       // 481 uses
    private static final String HELMET = "minecraft:diamond_helmet";       // 363
    private static final String CHEST = "minecraft:diamond_chestplate";    // 528
    private static final String LEGS = "minecraft:diamond_leggings";       // 495
    private static final String PICK = "minecraft:diamond_pickaxe";
    private static final String XP = "minecraft:experience_bottle";

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    private static boolean logged(RecordingLogger log, String part) {
        return log.lines.stream().anyMatch(l -> l.contains(part));
    }

    private static long loggedCount(RecordingLogger log, String part) {
        return log.lines.stream().filter(l -> l.contains(part)).count();
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 60), l.size()));
    }

    private static long alerts(Sim sim, String title) {
        return sim.notifier.of("alert").stream().filter(m -> m.title().equals(title)).count();
    }

    private static void noXpAnywhere(Sim sim) {
        for (Pos xp : sim.config.site.storage.xpChests) sim.bot.containers.get(xp).items.clear();
        sim.inv.items.remove(XP);
    }

    private static FakeTool pickaxe(Sim sim, int uses, boolean mending) {
        sim.inv.items.remove(PICK);
        sim.inv.toolStacks.clear();
        return sim.inv.addTool(PICK, uses, mending);
    }

    private static boolean running(Sim sim, String prefix) {
        BotTask t = sim.bot.lastTask;
        return t != null && t.name().startsWith(prefix) && !t.isFinished();
    }

    private static void wearBoots(Sim sim, double durability) {
        sim.inv.boots = BOOTS;
        sim.inv.bootsDurability = durability;
        sim.inv.bootsMending = true;
    }

    // ---- the root cause (live 2026-10-21) ----

    /**
     * Live 2026-10-21 (coordinates masked): 09:57:19 "boots at 50%: mending", 09:57:20 "boots mend stopped at 53% (no XP
     * bottles left in the inventory); trying again in 10 min", 09:58:41 "boots at 49% and no XP bottles carried:
     * fetching 32", then no mend until 10:07:25 "boots at 27%: mending them to 95%". The bottles fetched at 09:58 sat in
     * the inventory behind the 10-minute retry wait of the run that ran dry, while Soul Speed wore the boots on (about
     * 2.4 % a minute). Here the wear is faster (one use a second): the old path lets the boots run down to nothing with
     * bottles carried; the guard throws the fetched bottles at once.
     */
    @Test
    void fetchedBottlesAreThrownAtOnceNotHeldBehindTheRetryWait() {
        RecordingLogger oldLog = new RecordingLogger();
        RootCause old = rootCauseRun(oldLog, false);
        assertTrue(logged(oldLog, "boots mend stopped at") && logged(oldLog, "no XP bottles left in the inventory"),
            "the run ran dry: " + tail(oldLog));
        assertTrue(logged(oldLog, "and no XP bottles carried: fetching"), "then fetched: " + tail(oldLog));
        assertTrue(old.sim.bot.lowestBootsUses <= 120, "old path: worn to " + old.sim.bot.lowestBootsUses + "/481 uses"
            + " with the fetched bottles carried");
        assertTrue(old.heldTicks > 500, "old path: " + old.heldTicks + " ticks below 40 % with bottles carried");

        RecordingLogger log = new RecordingLogger();
        RootCause now = rootCauseRun(log, true);
        assertFalse(now.sim.bot.armorBroke);
        assertTrue(now.sim.bot.lowestBootsUses >= 0.40 * 481, "the guard: lowest " + now.sim.bot.lowestBootsUses + "/481");
        assertEquals(0, now.heldTicks, "the guard: ticks below 40 % with bottles carried");
        assertTrue(logged(log, "mending it to 95% with the carried XP bottles"), tail(log));
        assertTrue(now.sim.notifier.of("alert").isEmpty(), now.sim.notifier.toString());
        System.out.printf("root cause 2026-10-21: old path lowest %d/481 uses (broke: %b), %d ticks below 40 %% with"
                + " bottles carried; guard lowest %d/481 (broke: %b), %d ticks%n", old.sim.bot.lowestBootsUses,
            old.sim.bot.armorBroke, old.heldTicks, now.sim.bot.lowestBootsUses, now.sim.bot.armorBroke, now.heldTicks);
    }

    /** A {@link #rootCauseRun}: the sim, and its ticks with the boots below 40 % and XP bottles carried (no throw). */
    private record RootCause(Sim sim, long heldTicks) { }

    private static RootCause rootCauseRun(RecordingLogger log, boolean guard) {
        Sim sim = sim(log);
        sim.config.build.gearGuard = guard;
        wearBoots(sim, 0.49);
        sim.inv.add(XP, 1); // the 09:57 mend began with the few bottles left from the last one
        sim.bot.bootsWearTicks = 5;
        sim.bot.placementsPerTick = 1; // a lane takes about 6 s, as live
        TileArt art = Sim.single(Sim.WHITE, 24);
        Job job = sim.addJob("j0700", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        long held = 0;
        for (int i = 0; i < LONG && job.status != JobStatus.DONE && !sim.bot.armorBroke; i++) {
            sim.tick();
            if (sim.inv.boots != null && sim.inv.bootsDurability < 0.40 && sim.inv.count(XP) > 0
                && !running(sim, "throwXp")) held++;
        }
        return new RootCause(sim, held);
    }

    /**
     * Live 2026-10-23 08:37 (coordinates masked): "boots at 49%: mending them to 95% with the carried XP bottles", then
     * "boots mend stopped at 83% (no XP bottles left in the inventory)": the bot carries 11-41 bottles. With too few
     * carried for the whole mend, one top-up comes first, for the boots and the carried Mending tools together.
     */
    @Test
    void tooFewBottlesCarriedTopsUpForTheBootsAndTheToolsTogether() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        wearBoots(sim, 0.49);
        sim.inv.add(XP, 4);
        pickaxe(sim, 1093, true); // 70 %: not due, but it takes its share of the XP
        TileArt art = Sim.stripes(6, 0);
        sim.addJob("j0714", "TopUp", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.inv.bootsDurability >= 0.95, 4 * MINUTE), sim.state() + tail(log));
        List<String> h = sim.bot.history;
        int fetch = h.indexOf(h.stream().filter(s -> s.startsWith("withdraw " + Sim.XP)).findFirst().orElseThrow());
        int firstThrow = h.indexOf(h.stream().filter(s -> s.startsWith("throwXp")).findFirst().orElseThrow());
        assertTrue(fetch < firstThrow, "the top-up before the first throw: " + h);
        // boots (457 - 236) / 14 = 16, pickaxe (1483 - 1093) / 14 = 28, less the 4 carried
        assertTrue(h.get(fetch).contains("=40"), "fetched for both: " + h.get(fetch));
        assertFalse(logged(log, "worn armor mend paused"), "no mend that runs dry: " + tail(log));
    }

    // ---- a running task stops for the gear ----

    @Test
    void aLaneStopsAtASafePointForUrgentBootsAndCarriesOnAfterTheMend() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.bootsMendBelowPercent = 30; // between tasks only below 30 %: the lanes reach 25 % midway
        wearBoots(sim, 0.32);
        sim.inv.add(XP, 64);
        sim.bot.bootsWearTicks = 1;
        sim.bot.placementsPerTick = 1;
        TileArt art = Sim.single(Sim.WHITE, 8);
        Job job = sim.addJob("j0701", "Park", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE || sim.bot.armorBroke, LONG), sim.state());

        assertFalse(sim.bot.armorBroke);
        assertTrue(sim.bot.parks >= 1 && sim.bot.resumes >= 1, sim.bot.parks + " parks, " + sim.bot.resumes + " resumes");
        assertTrue(logged(log, "is due for mending: stopping lane"), tail(log));
        assertTrue(logged(log, "waits"), "mended with the lane parked: " + tail(log));
        assertTrue(sim.bot.lowestBootsUses >= 0.20 * 481, "lowest " + sim.bot.lowestBootsUses);
        assertEquals(JobStatus.DONE, job.status);
    }

    /** A task that cannot stop midway is cut short after 30 s for a piece at the hard floor (no XP anywhere). */
    @Test
    void aTaskThatCannotStopIsCutShortForBootsAtTheFloor() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        noXpAnywhere(sim);
        sim.config.map.rename = false; // no XP for the anvil either: reach the nuke
        sim.bot.tasksRefusePark = true;
        sim.bot.breakTicksPerBlock = 20;
        TileArt art = Sim.stripes(0, 4);
        sim.addJob("j0702", "Cut", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> running(sim, "break") && sim.phase() == Phase.NUKE, LONG), sim.state());

        wearBoots(sim, 20 / 481.0); // at the hard floor (29 uses) mid-break
        sim.bot.bootsWearTicks = 40;
        assertTrue(sim.runUntil(() -> sim.inv.boots == null || sim.bot.armorBroke, 2 * MINUTE), sim.state() + tail(log));
        assertFalse(sim.bot.armorBroke);
        assertTrue(logged(log, "it cannot stop midway"), tail(log));
        assertTrue(logged(log, "did not stop for it within 30 s: cut short"), tail(log));
        assertEquals(1, sim.inv.count(BOOTS), "kept in the inventory");
        assertTrue(sim.bot.lowestBootsUses > 0);

        sim.run(2 * MINUTE);
        assertNull(sim.inv.boots, "not worn again without bottles");
        assertEquals(1, alerts(sim, "Boots taken off"), sim.notifier.toString());
    }

    // ---- not working: paused, BLOCKED ----

    @Test
    void whilePausedWornBootsAreMendedWithCarriedBottlesAndTakenOffWithout() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        TileArt art = Sim.stripes(6, 0);
        sim.addJob("j0703", "Paused", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD && sim.bot.placed > 50, LONG), sim.state());
        sim.engine.pause("lunch");
        sim.run(5);
        int placed = sim.bot.placed;
        int withdrawals = sim.bot.withdrawLog.size();

        wearBoots(sim, 0.30);
        sim.inv.add(XP, 30);
        assertTrue(sim.runUntil(() -> sim.inv.bootsDurability >= 0.95, 400), sim.state() + tail(log));
        assertTrue(sim.engine.status().paused());
        assertEquals(placed, sim.bot.placed, "nothing built while paused");
        assertTrue(logged(log, "mending it to 95%"), tail(log));

        sim.inv.items.remove(XP);
        sim.inv.bootsDurability = 0.04;
        assertTrue(sim.runUntil(() -> sim.inv.boots == null, 400), sim.state() + tail(log));
        assertEquals(1, sim.inv.count(BOOTS));
        assertEquals(1, alerts(sim, "Boots taken off"), sim.notifier.toString());
        assertTrue(sim.engine.status().paused());
        assertEquals(placed, sim.bot.placed);
        assertEquals(withdrawals, sim.bot.withdrawLog.size(), "no walk to a chest while paused");
    }

    /** BLOCKED (a tool at the floor, no XP, no spare): the helmet is mended with bottles handed over, the tool too. */
    @Test
    void whileBlockedTheArmourAndTheWatchdogMendWithCarriedBottles() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        noXpAnywhere(sim);
        sim.bot.containers.get(Sim.TOOLS).items.clear();
        sim.config.map.rename = false;
        FakeTool pick = pickaxe(sim, 300, true);
        TileArt art = Sim.stripes(0, 4);
        sim.addJob("j0704", "Blocked", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED, LONG), sim.state());
        assertEquals(20, pick.remaining);

        FakeArmor helmet = new FakeArmor(HELMET, 100, true); // 27 %
        sim.inv.wear(helmet);
        sim.inv.add(XP, 200); // a player hands bottles over; nobody resumes
        assertTrue(sim.runUntil(() -> helmet.remaining >= 0.95 * 363, 400), helmet + tail(log));
        assertEquals(Phase.BLOCKED, sim.phase());

        // the pickaxe has been due since the start: the watchdog mends it in place once it is overdue
        assertTrue(sim.runUntil(() -> pick.remaining > 20, 12 * MINUTE), pick + tail(log));
        assertTrue(logged(log, "mend watchdog: diamond_pickaxe"), tail(log));
        assertTrue(logged(log, "mending it now"), tail(log));
        assertEquals(Phase.BLOCKED, sim.phase(), "still BLOCKED: only a resume carries on");
        assertEquals(0, sim.bot.breaksAtFloor);
    }

    // ---- waits and IDLE ----

    @Test
    void aMaterialsWaitFetchesXpAndMendsTheArmour() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        TileArt art = Sim.single(Sim.WHITE, 30); // 3840 white, 3456 in stock: STOCK_CHECK waits for the rest
        Job job = sim.addJob("j0705", "Wait", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.STOCK_CHECK && sim.engine.status().note() != null
            && sim.engine.status().note().startsWith(BuildEngine.MATERIALS_NOTE), LONG), sim.state());
        sim.inv.items.remove(XP);
        FakeArmor legs = new FakeArmor(LEGS, 150, true); // 30 %
        sim.inv.wear(legs);
        int withdrawals = sim.bot.withdrawLog.size();

        assertTrue(sim.runUntil(() -> legs.remaining >= 0.95 * 495, 2 * MINUTE), legs + tail(log));
        assertTrue(sim.bot.withdrawLog.subList(withdrawals, sim.bot.withdrawLog.size()).stream()
            .anyMatch(e -> e.getKey().equals(Sim.XP) && e.getValue() == Phase.STOCK_CHECK), "XP fetched in the wait");
        assertEquals(JobStatus.WAITING_MATERIALS, job.status, "still waiting");
        assertTrue(logged(log, "is due for mending and too few XP bottles are carried"), tail(log));
    }

    @Test
    void inIdleTheArmourIsMendedAndTheWatchdogMendsADueTool() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        FakeTool pick = pickaxe(sim, 702, true); // 45 %: tools are not mended in IDLE, but for the watchdog
        sim.inv.items.remove(XP);
        FakeArmor helmet = new FakeArmor(HELMET, 100, true);
        sim.inv.wear(helmet);
        sim.start();
        assertTrue(sim.runUntil(() -> helmet.remaining >= 0.95 * 363, 2 * MINUTE), helmet + tail(log));
        assertEquals(Phase.IDLE, sim.phase());
        assertTrue(pick.remaining < 1483, "no tool mend in IDLE before the watchdog: " + pick);

        assertTrue(sim.runUntil(() -> pick.remaining >= 1483, 12 * MINUTE), pick + tail(log));
        assertTrue(logged(log, "mend watchdog: diamond_pickaxe"), tail(log));
        assertEquals(Phase.IDLE, sim.phase());
    }

    @Test
    void theWatchdogCanBeSwitchedOff() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.mendWatchdogMinutes = 0;
        FakeTool pick = pickaxe(sim, 702, true);
        sim.start();
        sim.run(12 * MINUTE);
        assertEquals(702, pick.remaining);
        assertFalse(logged(log, "mend watchdog"), tail(log));
    }

    // ---- reconnect ----

    @Test
    void afterAReconnectWornBootsAreMendedBeforeTheNextLane() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        TileArt art = Sim.stripes(12, 0);
        Job job = sim.addJob("j0706", "Again", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> running(sim, "lane") && sim.bot.placed > 100, LONG), sim.state());
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        // offline: a player (or a fall) wore the boots down; bottles are carried
        sim.inv.bootsDurability = 0.10;
        sim.inv.add(XP, 40);
        int from = sim.bot.history.size();
        sim.start();
        assertTrue(sim.runUntil(() -> sim.inv.bootsDurability >= 0.95, 400), sim.state() + tail(log));
        List<String> since = List.copyOf(sim.bot.history.subList(from, sim.bot.history.size()));
        int firstThrow = since.indexOf(since.stream().filter(h -> h.startsWith("throwXp")).findFirst().orElseThrow());
        int firstLane = since.stream().filter(h -> h.startsWith("placeLane")).findFirst().map(since::indexOf).orElse(99);
        assertTrue(firstThrow < firstLane, "mended before any lane: " + since);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertFalse(sim.bot.armorBroke);
    }

    @Test
    void afterAReconnectWithNoXpWornOutBootsComeOffBeforeAnyWalk() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        TileArt art = Sim.stripes(12, 0);
        sim.addJob("j0707", "Again", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> running(sim, "lane") && sim.bot.placed > 100, LONG), sim.state());
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        noXpAnywhere(sim);
        sim.inv.bootsDurability = 0.05;
        sim.bot.bootsWearTicks = 5;
        sim.start();
        assertTrue(sim.runUntil(() -> sim.inv.boots == null, 400), sim.state() + tail(log));
        assertTrue(sim.bot.lowestBootsUses >= 20, "lowest " + sim.bot.lowestBootsUses);
        sim.run(5 * MINUTE);
        assertNull(sim.inv.boots, "not worn again without XP");
        assertFalse(sim.bot.armorBroke);
    }

    // ---- every slot ----

    @Test
    void everyArmourSlotIsWatched() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        FakeArmor helmet = new FakeArmor(HELMET, 120, true);   // 33 %: mended
        FakeArmor chest = new FakeArmor(CHEST, 30, false);     // 5 %, no Mending: comes off, not worn again
        sim.inv.wear(helmet);
        sim.inv.wear(chest);
        sim.bot.armorWearTicks = 50;
        TileArt art = Sim.single(Sim.WHITE, 8);
        Job job = sim.addJob("j0708", "Slots", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE || sim.bot.armorBroke, LONG), sim.state());

        assertFalse(sim.bot.armorBroke);
        assertTrue(helmet.remaining >= 0.5 * 363, "helmet mended: " + helmet);
        assertTrue(logged(log, "diamond_helmet"), tail(log));
        assertFalse(sim.inv.worn.containsKey(InventoryArmorSlots.CHEST), "the plain chestplate is off");
        assertEquals(1, sim.inv.count(CHEST), "kept in the inventory");
        assertEquals(1, alerts(sim, "Armor taken off"), sim.notifier.toString());
        assertTrue(sim.notifier.toString().contains("Without Mending it is not worn again"), sim.notifier.toString());
        assertEquals(1, sim.output().maps.size(), "the map was still made");
    }

    /** Slot numbers of {@link io.github.digitalisp.mapart.bot.InventoryView.Armor}. */
    private static final class InventoryArmorSlots {
        static final int CHEST = io.github.digitalisp.mapart.bot.InventoryView.Armor.CHEST;
    }

    @Test
    void autoArmorIsHeldOffWhileAWornOutPieceIsCarried() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.modules.state.put("AutoArmor", true);
        sim.config.build.disableModules.removeIf(m -> m.equalsIgnoreCase("AutoArmor")); // the owner keeps it on
        FakeArmor chest = new FakeArmor(CHEST, 20, false);
        sim.inv.wear(chest);
        sim.start();
        assertTrue(sim.runUntil(() -> !sim.inv.worn.containsKey(InventoryArmorSlots.CHEST), 400), sim.state() + tail(log));
        sim.run(40);
        assertFalse(sim.modules.isEnabled("AutoArmor"), "AutoArmor would put it straight back on");
        assertTrue(logged(log, "AutoArmor off while a worn-out armor piece is carried"), tail(log));

        // put away (a player takes it): AutoArmor comes back
        sim.inv.carriedArmor.clear();
        sim.inv.items.remove(CHEST);
        sim.run(300);
        assertTrue(sim.modules.isEnabled("AutoArmor"), tail(log));
    }

    // ---- a full inventory ----

    @Test
    void pausedWithAFullInventoryThePieceStaysOnWithOneAlertUntilASlotIsFree() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.start();
        sim.run(60);
        sim.engine.pause("full");
        sim.run(5);
        noXpAnywhere(sim);
        sim.inv.add("minecraft:cobblestone", 64 * sim.inv.freeSlots());
        assertEquals(0, sim.inv.freeSlots());
        wearBoots(sim, 0.03);
        sim.run(400);
        assertEquals(BOOTS, sim.inv.boots, "no free slot: still worn (standing still, it wears no further)");
        assertEquals(1, alerts(sim, "Armor at the floor"), sim.notifier.toString());

        sim.inv.remove("minecraft:cobblestone", 64);
        assertTrue(sim.runUntil(() -> sim.inv.boots == null, 2 * MINUTE), sim.state() + tail(log));
        assertEquals(1, alerts(sim, "Armor at the floor"), "still one: " + sim.notifier);
    }

    @Test
    void workingWithAFullInventoryRoomIsMadeOrTheBotStopsWhereItStands() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        noXpAnywhere(sim);
        TileArt art = Sim.stripes(12, 0);
        sim.addJob("j0709", "Full", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> running(sim, "lane") && sim.bot.placed > 100, LONG), sim.state());
        sim.inv.add("minecraft:cobblestone", 64 * sim.inv.freeSlots());
        sim.inv.bootsDurability = 0.04;
        sim.bot.bootsWearTicks = 10;
        assertTrue(sim.runUntil(() -> sim.inv.boots == null || sim.phase() == Phase.BLOCKED || sim.bot.armorBroke,
            2 * MINUTE), sim.state() + tail(log));
        assertFalse(sim.bot.armorBroke);
        if (sim.inv.boots != null) {
            assertTrue(sim.engine.status().note().contains("no free slot to take it off"), sim.state());
        }
        sim.run(2 * MINUTE);
        assertFalse(sim.bot.armorBroke, sim.state());
    }

    /**
     * Local run 2026-10-23 (10x): a STOCK_CHECK wait with every slot full of items the bot never moves and the
     * leggings at the floor stopped BLOCKED with two alerts ("Map art blocked" and "Armour at the floor") for the one
     * piece. The block's alert is the only one; a freed slot takes the piece off (one "Armour taken off").
     */
    @Test
    void aBlockForAFullInventoryAlertsOnceAndAFreedSlotTakesThePieceOff() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        noXpAnywhere(sim);
        TileArt art = Sim.stripes(12, 0);
        sim.addJob("j0710", "Full", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> running(sim, "lane") && sim.bot.placed > 100, LONG), sim.state());
        sim.inv.add("minecraft:stick", 64 * sim.inv.freeSlots());
        assertEquals(0, sim.inv.freeSlots());
        wearBoots(sim, 0.04);
        sim.bot.bootsWearTicks = 10;
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BLOCKED || sim.bot.armorBroke, 2 * MINUTE),
            sim.state() + tail(log));
        assertFalse(sim.bot.armorBroke);
        assertTrue(sim.engine.status().note().contains("no free slot to take it off"), sim.state());
        sim.run(400);
        assertEquals(BOOTS, sim.inv.boots, "no free slot: still worn (standing still, it wears no further)");
        assertEquals(1, alerts(sim, "Map art blocked"), sim.notifier.toString());
        assertEquals(0, alerts(sim, "Armor at the floor"), "one alert for the piece: " + sim.notifier);

        sim.inv.remove("minecraft:stick", 64);
        assertTrue(sim.runUntil(() -> sim.inv.boots == null, 2 * MINUTE), sim.state() + tail(log));
        assertFalse(sim.bot.armorBroke);
        assertEquals(1, alerts(sim, "Boots taken off"), sim.notifier.toString());
        assertEquals(0, alerts(sim, "Armor at the floor"), sim.notifier.toString());
    }

    // ---- XP the worn armour takes ----

    /**
     * Live 2026-10-17 16:33 and 10-18 21:42: one-bottle tool mends that "gained nothing" because damaged Mending armour
     * took the orbs; three in a row mark the tool "Mending does not repair" for the session. The worn armour's gain is
     * not counted against the tool.
     */
    @Test
    void xpTheWornArmourTakesIsNotCountedAgainstTheToolsMending() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = soakRun(log, true);
        assertTrue(logged(log, "the worn armor took the XP"), tail(log));
        assertFalse(log.at(org.slf4j.event.Level.WARN).stream().anyMatch(l -> l.startsWith("mending minecraft:diamond_pickaxe"
            + " gained nothing")), tail(log));
        assertTrue(sim.inv.tool(PICK).remaining >= 1483, "the pickaxe mended on: " + sim.inv.tool(PICK));

        RecordingLogger oldLog = new RecordingLogger();
        soakRun(oldLog, false);
        assertTrue(oldLog.at(org.slf4j.event.Level.WARN).stream().anyMatch(l -> l.startsWith("mending minecraft:diamond_pickaxe"
            + " gained nothing")), "the old path counted it: " + tail(oldLog));
    }

    private static Sim soakRun(RecordingLogger log, boolean guard) {
        Sim sim = sim(log);
        sim.config.build.gearGuard = guard;
        sim.bot.armorTakesAllMendXp = true;
        // above the armour's own mend threshold (50 %), so only the tool's mends feed them: 313 XP points in all
        sim.inv.wear(new FakeArmor(HELMET, 200, true));
        sim.inv.wear(new FakeArmor(CHEST, 290, true));
        sim.inv.wear(new FakeArmor(LEGS, 272, true));
        pickaxe(sim, 702, true);
        TileArt art = Sim.single(Sim.WHITE, 4);
        Job job = sim.addJob("j0710", "Soak", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        return sim;
    }

    /** Three mends that gained nothing mark the tool; the watchdog lifts the mark and mends it after 10 min. */
    @Test
    void theWatchdogMendsAToolMarkedMendingDoesNotRepair() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.bot.toolMendsGainNothing = 3;
        FakeTool pick = pickaxe(sim, 702, true);
        TileArt art = Sim.single(Sim.WHITE, 4);
        Job job = sim.addJob("j0711", "Marked", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.runUntil(() -> pick.remaining >= 1483, 15 * MINUTE), pick + tail(log));
        assertTrue(logged(log, "mend watchdog: diamond_pickaxe"), tail(log));
        assertEquals(0, sim.bot.toolMendsGainNothing);
    }

    // ---- the switch ----

    @Test
    void withTheGuardOffNothingOfItRuns() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.gearGuard = false;
        FakeArmor helmet = new FakeArmor(HELMET, 100, true);
        sim.inv.wear(helmet);
        TileArt art = Sim.single(Sim.WHITE, 4);
        Job job = sim.addJob("j0712", "Off", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        // the old engine never looks at the helmet (RENAME's own throws may still feed it, as the XP orbs would)
        assertFalse(logged(log, "worn armor"), tail(log));
        assertFalse(logged(log, "diamond_helmet"), tail(log));
        assertFalse(logged(log, "mend watchdog"), tail(log));
        assertEquals(0, sim.bot.armorOff);
    }

    /** Synthetic stock: a second XP chest, as on the live site; both are tried before the alert. */
    @Test
    void everyXpChestIsTriedBeforeTheAlert() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Pos second = new Pos(-36, 61, 100);
        sim.config.site.storage.xpChests.add(second);
        sim.bot.addContainer(second, Map.of());
        noXpAnywhere(sim);
        wearBoots(sim, 0.05);
        TileArt art = Sim.stripes(6, 0);
        sim.addJob("j0713", "Chests", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> alerts(sim, "Boots taken off") > 0, 4 * MINUTE), sim.state() + tail(log));
        assertTrue(sim.bot.history.stream().anyMatch(h -> h.startsWith("withdraw " + Sim.XP)), sim.bot.history.toString());
        assertTrue(sim.bot.history.stream().anyMatch(h -> h.startsWith("withdraw " + second)), sim.bot.history.toString());
        assertNull(sim.inv.boots);
        assertFalse(sim.bot.armorBroke);
    }
}
