package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code build.elytraKitFarTrip} (the far trip): a colour-suppression job with its
 * own chests tops its flight rockets up from a rocket chest that is far storage for its platform, by the platform's
 * access path: down to the foot, the visit from the storage walkway, back by the foot and the entry. Live 2026-11-15
 * 09:28 the leg went straight from the cs chests and ended on the ride's walkway. Off (the default): no trip.
 */
class ElytraKitFarTripTest {
    private static final int LONG = 80_000;
    private static final String ELYTRA = "minecraft:elytra";
    private static final String ROCKET = "minecraft:firework_rocket";
    private static final String WALKWAY = "ride: walkway";

    /** The simulated colour-suppression platform with its own chests, a foot and an entry, and the far rocket chest. */
    private record Site(Sim sim, MapArtConfig.PlatformCfg cs, Pos rockets, Job job, RecordingLogger log) {
        long lines(String part) {
            return log.at(Level.INFO).stream().filter(l -> l.contains(part)).count();
        }

        /** What a task of the bot's history is to the trip: "foot", "entry", "chest" (a visit), or null. */
        String step(String task) {
            if (task.equals("walkTo " + cs.foot)) return "foot";
            if (task.equals("walkTo " + cs.entry)) return "entry";
            return task.contains(rockets.toString()) && !task.startsWith("walkTo") ? "chest" : null;
        }

        /** The trip's walks and rocket chest visits in order. */
        List<String> way() {
            List<String> out = new ArrayList<>();
            for (String h : sim.bot.history) if (step(h) != null) out.add(step(h));
            return out;
        }

        boolean walkwayOpen() {
            return sim.config.site.openings.stream().anyMatch(o -> WALKWAY.equals(o.name));
        }

        boolean ended() {
            return job.status == JobStatus.READY_FOR_RIDE || sim.phase() == Phase.BLOCKED;
        }
    }

    private static MapArtConfig.RegionCfg box(Pos min, Pos max, String name) {
        MapArtConfig.RegionCfg r = new MapArtConfig.RegionCfg();
        r.min = min;
        r.max = max;
        r.name = name;
        return r;
    }

    /**
     * A colour-suppression job on the simulated platform (glass at y 100 and 114) with its own chests beside the top
     * walkway, an elytra and a stack of flight rockets carried, and a rocket chest 196 blocks west of the map area at
     * the storage level holding {@code inChest} rockets. With {@code ride} the platform has a ride whose walkway is
     * one of the job's openings (the job then goes on to the ride after the print).
     */
    private static Site site(boolean farTrip, int inChest, boolean ride) {
        RecordingLogger log = new RecordingLogger();
        Sim sim = new Sim();
        sim.newEngine(new SimFakes.SerpentinePlanner(), new GreedyRestockPlanner(), log);
        MapArtConfig.PlatformCfg cs = SuppressionEngineTest.csPlatform(sim);
        cs.materialRegions.add(box(new Pos(cs.minX + 1, 116, cs.minZ - 8), new Pos(cs.minX + 8, 118, cs.minZ - 7), "cs chests"));
        sim.bot.addContainer(new Pos(cs.minX + 1, 116, cs.minZ - 7), Map.of(Sim.WHITE, 1728));
        sim.bot.addContainer(new Pos(cs.minX + 2, 116, cs.minZ - 7), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(cs.minX + 3, 116, cs.minZ - 7), Map.of(Sim.BLACK, 1728));
        cs.allowedBlocks = new ArrayList<>(List.of(Sim.WHITE, Sim.RED, Sim.BLACK));
        // the platform's own leftovers chest, beside its material chests: a RESTOCK's kit step finds the bot up here
        Pos leftovers = new Pos(cs.minX + 6, 116, cs.minZ - 7);
        sim.bot.addContainer(leftovers, Map.of());
        cs.returnChests.add(leftovers);
        cs.foot = new Pos(cs.minX - 14, 61, cs.minZ - 3);
        cs.entry = new Pos(cs.minX - 1, 102, cs.minZ - 24);
        if (ride) {
            cs.ride.enabled = true;
            cs.ride.startRail = new Pos(cs.minX, 124, cs.minZ + 15);
            cs.ride.startStand = new Pos(cs.minX - 1, 122, cs.minZ + 14);
            cs.ride.button = new Pos(cs.minX, 127, cs.minZ + 18);
            cs.ride.brake = new Pos(cs.minX - 129, 122, cs.minZ + 15);
            cs.ride.cartographyTable = Sim.TABLE;
            cs.ride.anvil = Sim.ANVIL;
            sim.inv.add("minecraft:minecart", 1);
            cs.ride.access.add(box(new Pos(cs.minX - 126, 122, cs.minZ + 12), new Pos(cs.minX - 7, 124, cs.minZ + 14), WALKWAY));
            sim.config.site.noGoZones.add(box(new Pos(cs.minX - 130, 118, cs.minZ + 10), new Pos(cs.minX + 1, 135, cs.minZ + 119),
                "muncher"));
        }
        sim.config.build.elytraLegs = true;
        sim.config.build.elytraKitFarTrip = farTrip;
        Pos rockets = new Pos(cs.minX - 196, 61, cs.minZ - 6);
        sim.bot.addContainer(rockets, inChest > 0 ? Map.of(ROCKET, inChest) : Map.of());
        sim.config.site.storage.rocketChests.add(rockets);
        sim.inv.add(ELYTRA, 1);
        sim.inv.add(ROCKET, 64);
        Job job = SuppressionEngineTest.addJob(sim, SuppressionEngineTest.art());
        return new Site(sim, cs, rockets, job, log);
    }

    /** Runs until the bot stands on the platform in BUILD with the kit full, then flies the rockets down to 12. */
    private static void toThePlatformWithTwelveRockets(Site s) {
        Sim sim = s.sim();
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD
            && ElytraKit.onPlatform(sim.world.playerBlock().y(), s.cs().glassY), LONG), sim.state() + s.log().lines);
        sim.bot.history.clear(); // the walk up by the entry at the job's start is not the trip's
        sim.inv.remove(ROCKET, sim.inv.count(ROCKET) - 12);
        assertEquals(12, sim.inv.count(ROCKET));
    }

    @Test
    void fromThePlatformDownToTheFootToTheChestAndBackByTheFootAndTheEntry() {
        Site s = site(true, 256, true);
        Sim sim = s.sim();
        toThePlatformWithTwelveRockets(s);
        assertTrue(s.walkwayOpen(), "the ride's walkway is one of the job's openings");
        // the openings as each task of the trip found them when it was made
        List<String> madeWithTheWalkwayOpen = new ArrayList<>();
        int[] seen = {sim.bot.history.size()};
        assertTrue(sim.runUntil(() -> {
            for (; seen[0] < sim.bot.history.size(); seen[0]++) {
                String step = s.step(sim.bot.history.get(seen[0]));
                if (step != null && s.walkwayOpen()) madeWithTheWalkwayOpen.add(step);
            }
            return s.lines("Elytra kit: the trip to the far rocket chest and back to the entry took") == 1;
        }, LONG), sim.state() + s.way() + s.log().lines);
        assertEquals(List.of("foot", "chest", "foot", "entry"), s.way(), "down to the foot, the visit, back by the foot and"
            + " the entry: " + sim.bot.history);
        assertEquals(List.of(), madeWithTheWalkwayOpen, "the ride's openings are closed while the trip is under way");
        assertEquals(64, sim.inv.count(ROCKET));
        assertEquals(204, sim.bot.containers.get(s.rockets()).items.get(ROCKET));
        assertEquals(1, s.lines("is far storage for platform cs (build.elytraKitFarTrip): down the access path to its foot "
            + s.cs().foot.key() + " first"), s.log().lines.toString());
        assertEquals(1, s.lines("Elytra kit: took 52 rockets from the rocket chest at " + s.rockets()), s.log().lines.toString());
        assertEquals(1, s.lines("Elytra kit: back to platform cs from"), s.log().lines.toString());
        assertTrue(s.walkwayOpen(), "the ride's openings are back once the trip is over");
        // the print goes on from the entry: back on the platform's levels, the next lanes run
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD
            && ElytraKit.onPlatform(sim.world.playerBlock().y(), s.cs().glassY), LONG), sim.state() + s.log().lines);
    }

    @Test
    void theTripIsMadeOnceAJobWhileTheRocketsLast() {
        Site s = site(true, 256, false);
        Sim sim = s.sim();
        toThePlatformWithTwelveRockets(s);
        assertTrue(sim.runUntil(s::ended, LONG), sim.state());
        assertEquals(JobStatus.READY_FOR_RIDE, s.job().status, sim.state() + s.log().lines);
        assertEquals(64, sim.inv.count(ROCKET));
        assertEquals(List.of("foot", "chest", "foot", "entry"), s.way(), "one trip in the job: " + sim.bot.history);
    }

    @Test
    void switchedOffThereIsNoTripAndOneLineARestock() {
        Site s = site(false, 256, false);
        Sim sim = s.sim();
        toThePlatformWithTwelveRockets(s);
        assertTrue(sim.runUntil(s::ended, LONG), sim.state());
        assertEquals(JobStatus.READY_FOR_RIDE, s.job().status, sim.state() + s.log().lines);
        assertEquals(List.of(), s.way(), "no walk to the foot or the entry, no visit: " + sim.bot.history);
        assertEquals(12, sim.inv.count(ROCKET));
        assertTrue(s.lines("no trip for the kit from a color-suppression job, the flights go on with what is carried") >= 1,
            s.log().lines.toString());
        assertEquals(0, s.lines("build.elytraKitFarTrip"), s.log().lines.toString());
    }

    @Test
    void anEmptyChestIsOneTripAndThenHeldForTenMinutes() {
        Site s = site(true, 0, false);
        Sim sim = s.sim();
        toThePlatformWithTwelveRockets(s);
        assertTrue(sim.runUntil(() -> s.way().contains("entry"), LONG), sim.state() + s.way() + s.log().lines);
        assertEquals(List.of("foot", "chest", "foot", "entry"), s.way());
        assertEquals(1, s.lines("Elytra kit: no flight rockets (plain, duration 1-3) in the rocket chest at " + s.rockets()),
            s.log().lines.toString());
        long held = sim.clock.millis();
        assertTrue(sim.runUntil(() -> s.ended() || sim.clock.millis() - held >= ElytraKit.EMPTY_RETRY_MS, LONG), sim.state());
        assertTrue(sim.clock.millis() - held < ElytraKit.EMPTY_RETRY_MS, "the job ended inside the hold");
        assertEquals(JobStatus.READY_FOR_RIDE, s.job().status, sim.state() + s.log().lines);
        assertEquals(12, sim.inv.count(ROCKET));
        assertEquals(List.of("foot", "chest", "foot", "entry"), s.way(), "held: no second trip to the empty chest");
        assertTrue(s.lines("had no flight rockets at the last look") >= 1, s.log().lines.toString());
    }

    @Test
    void theFootNotReachedIsNoTripAndAHold() {
        Site s = site(true, 256, false);
        Sim sim = s.sim();
        toThePlatformWithTwelveRockets(s);
        // the next walk that is the trip's way down fails
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.RESTOCK, LONG), sim.state());
        sim.bot.failNext.put("walk", 1);
        assertTrue(sim.runUntil(() -> s.lines("Elytra kit: no trip to the far rocket chest at " + s.rockets()) == 1 || s.ended(),
            LONG), sim.state() + s.log().lines);
        assertTrue(s.log().at(Level.INFO).stream().anyMatch(l -> l.contains("of the access path was not reached: ")
            && l.contains("injected failure") && l.contains("the next try after 10 min")), s.log().lines.toString());
        assertEquals(0, s.lines("at the last look"), "the failure's own line says it: no hold line this RESTOCK");
        assertEquals(0, s.lines("the trip to the far rocket chest and back"), "no trip was made: no line for its time");
        assertTrue(sim.runUntil(s::ended, LONG), sim.state());
        assertEquals(JobStatus.READY_FOR_RIDE, s.job().status, sim.state() + s.log().lines);
        assertEquals(12, sim.inv.count(ROCKET));
        assertFalse(s.way().contains("chest"), "no leg toward the chest from the platform: " + s.way());
        assertFalse(s.way().contains("entry"), "the bot never left the platform: no way back: " + s.way());
    }

    @Test
    void aPlatformWithoutAFootMakesNoTrip() {
        Site s = site(true, 256, false);
        Sim sim = s.sim();
        Pos foot = s.cs().foot;
        s.cs().foot = null;
        toThePlatformWithTwelveRockets(s);
        assertTrue(sim.runUntil(s::ended, LONG), sim.state());
        assertEquals(JobStatus.READY_FOR_RIDE, s.job().status, sim.state() + s.log().lines);
        assertEquals(12, sim.inv.count(ROCKET));
        assertTrue(sim.bot.history.stream().noneMatch(h -> h.equals("walkTo " + foot) || h.contains(s.rockets().toString())),
            sim.bot.history.toString());
        assertTrue(s.lines("(build.elytraKitFarTrip: platform cs has no foot (the bottom of its access path))") >= 1,
            s.log().lines.toString());
    }

    /** A RESTOCK that starts at the far chest (a trip cut short by a pause or a reconnect) comes back by the access path. */
    @Test
    void aRestockThatStartsAtTheFarChestComesBackByTheFootAndTheEntry() {
        Site s = site(true, 256, false);
        Sim sim = s.sim();
        // the kit is full (no visit due); the RESTOCK's leftovers go to a chest out by the far rocket chest, so its kit
        // step finds the bot there, as after a trip cut short
        Pos farReturn = s.rockets().offset(6, 0, 0);
        sim.bot.addContainer(farReturn, Map.of());
        s.cs().returnChests.clear();
        s.cs().returnChests.add(farReturn);
        sim.inv.add(Sim.TERRACOTTA, 10);
        sim.start();
        assertTrue(sim.runUntil(() -> s.way().contains("foot") || s.ended(), LONG), sim.state() + s.way());
        assertTrue(sim.runUntil(() -> s.way().getLast().equals("entry") || s.ended(), LONG), sim.state() + s.way());
        int foot = sim.bot.history.indexOf("walkTo " + s.cs().foot);
        assertTrue(foot > 0 && sim.bot.history.get(foot - 1).startsWith("deposit " + farReturn), sim.bot.history.toString());
        assertEquals("walkTo " + s.cs().entry, sim.bot.history.get(foot + 1), sim.bot.history.toString());
        assertFalse(s.way().contains("chest"), "no visit: " + s.way());
        assertEquals(1, s.lines("Elytra kit: back to platform cs from"), s.log().lines.toString());
        assertEquals(0, s.lines("the trip to the far rocket chest and back"), "no visit was made: no trip line");
    }

    /**
     * A RESTOCK that starts on the ride's ways (in the booth after a ride, at the start stand): they are the bot's only
     * way out, so they stay open for the walk down to the foot and are closed from there on.
     */
    @Test
    void aTripThatStartsOnTheRidesWaysKeepsThemOpenUntilTheFoot() {
        Site s = site(true, 256, true);
        Sim sim = s.sim();
        // the platform's leftovers chest stands by the ride's walkway: a RESTOCK returns its leftovers first, so its kit
        // step finds the bot on the walkway (the bot stands two north of a container), inside the muncher's zone
        Pos byWalkway = new Pos(s.cs().minX - 60, 122, s.cs().minZ + 14);
        sim.bot.addContainer(byWalkway, Map.of());
        s.cs().returnChests.clear();
        s.cs().returnChests.add(byWalkway);
        toThePlatformWithTwelveRockets(s);
        List<String> open = new ArrayList<>();
        List<String> closed = new ArrayList<>();
        int[] seen = {0};
        assertTrue(sim.runUntil(() -> {
            for (; seen[0] < sim.bot.history.size(); seen[0]++) {
                String step = s.step(sim.bot.history.get(seen[0]));
                if (step != null) (s.walkwayOpen() ? open : closed).add(step);
            }
            return s.lines("Elytra kit: the trip to the far rocket chest and back to the entry took") == 1 || s.ended();
        }, LONG), sim.state() + s.way() + s.log().lines);
        assertEquals(List.of("foot", "chest", "foot", "entry"), s.way(), sim.bot.history.toString());
        assertEquals(List.of("foot"), open, "the ride's ways stay open for the walk down from them: "
            + s.log().lines.stream().filter(l -> l.contains("Elytra kit")).toList());
        assertEquals(List.of("chest", "foot", "entry"), closed, "and are closed from the foot on");
        assertEquals(1, s.lines("(the bot stands on the ride's ways: they stay open until it is at the foot)"),
            s.log().lines.toString());
        assertEquals(64, sim.inv.count(ROCKET));
        assertTrue(s.walkwayOpen(), "the ride's openings are back once the trip is over");
    }
}
