package io.github.digitalisp.mapart.discord;

import net.dv8tion.jda.api.OnlineStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.command.StatusWords;
import io.github.digitalisp.mapart.discord.PresenceText.Link;
import io.github.digitalisp.mapart.discord.PresenceText.Presence;
import io.github.digitalisp.mapart.engine.BuildEngine;
import io.github.digitalisp.mapart.engine.EngineControl.EngineStatus;
import io.github.digitalisp.mapart.engine.Phase;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.TileStatus;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PresenceTextTest {
    /** 2x2 job: map 1 stored, map 2 (tile 1,0) in progress. */
    Job sunset;
    /** 1x1 job. */
    Job pine;

    @BeforeEach
    void setUp() {
        sunset = job("j0042", "Sunset", 2, 2);
        sunset.status = JobStatus.ACTIVE;
        sunset.tiles.get(0).status = TileStatus.STORED;
        sunset.tiles.get(1).status = TileStatus.ACTIVE;
        pine = job("j0007", "Pine", 1, 1);
        pine.status = JobStatus.ACTIVE;
    }

    static Job job(String id, String name, int tx, int tz) {
        Job j = new Job();
        j.id = id;
        j.name = name;
        j.tilesX = tx;
        j.tilesZ = tz;
        for (int z = 0; z < tz; z++)
            for (int x = 0; x < tx; x++) {
                Job.TileState t = new Job.TileState();
                t.x = x;
                t.z = z;
                t.total = 10_000;
                j.tiles.add(t);
            }
        return j;
    }

    static EngineStatus working(Phase phase, Job j, int tileX, int tileZ, int placed, int total) {
        return new EngineStatus(true, false, "", phase, j.id, j.name, tileX, tileZ, "moss", placed, total, "", 0);
    }

    static EngineStatus idle() {
        return new EngineStatus(true, false, "", Phase.IDLE, null, null, -1, -1, null, 0, 0, "", 0);
    }

    static EngineStatus paused(EngineStatus s, String reason) {
        return new EngineStatus(s.enabled(), true, reason, s.phase(), s.jobId(), s.jobName(), s.tileX(), s.tileZ(),
            s.platformId(), s.placed(), s.total(), s.note(), s.phaseSince());
    }

    static EngineStatus blocked(EngineStatus s, String note) {
        return new EngineStatus(s.enabled(), false, "", Phase.BLOCKED, s.jobId(), s.jobName(), s.tileX(), s.tileZ(),
            s.platformId(), s.placed(), s.total(), note, s.phaseSince());
    }

    Presence sunsetIn(Phase phase) {
        return PresenceText.describe(PresenceText.input(working(phase, sunset, 1, 0, 7_200, 10_000), List.of(sunset),
            true, Link.IN_GAME, 0));
    }

    static Presence of(EngineStatus s, List<Job> jobs) {
        return PresenceText.describe(PresenceText.input(s, jobs, true, Link.IN_GAME, 0));
    }

    // ---------------------------------------------------------------- phases

    @Test
    void multiMapShowsMapIndexMapPercentAndJobPercent() {
        Presence p = sunsetIn(Phase.BUILD);
        assertEquals("Printing · Sunset · map 2 of 4 (72%) · 1 map done", p.text());
        assertEquals(OnlineStatus.ONLINE, p.status());
    }

    @Test
    void singleMapShowsOnePercent() {
        Presence p = of(working(Phase.BUILD, pine, 0, 0, 4_300, 10_000), List.of(pine));
        assertEquals("Printing · Pine · 43%", p.text());
        assertEquals(OnlineStatus.ONLINE, p.status());
    }

    @Test
    void mainPhasesReadNaturally() {
        String tail = " · Sunset · map 2 of 4 (72%) · 1 map done";
        assertEquals("Inspecting the platform" + tail, sunsetIn(Phase.INSPECT).text());
        assertEquals("Checking stock" + tail, sunsetIn(Phase.STOCK_CHECK).text());
        assertEquals("Getting tools and food" + tail, sunsetIn(Phase.GEAR).text());
        assertEquals("Restocking" + tail, sunsetIn(Phase.RESTOCK).text());
        assertEquals("Checking the art" + tail, sunsetIn(Phase.VERIFY).text());
        assertEquals("Filling the map" + tail, sunsetIn(Phase.FILL_MAP).text());
        assertEquals("Checking the map" + tail, sunsetIn(Phase.CHECK_MAP).text());
        assertEquals("Locking the map" + tail, sunsetIn(Phase.LOCK).text());
        assertEquals("Naming the map" + tail, sunsetIn(Phase.RENAME).text());
        assertEquals("Storing the map" + tail, sunsetIn(Phase.STORE).text());
        assertEquals("Clearing leftovers" + tail, sunsetIn(Phase.NUKE).text());
        assertEquals("Waiting for the platform to clear" + tail, sunsetIn(Phase.WAIT_CLEAR).text());
    }

    @Test
    void everyWorkingPhaseHasAVerbAndIsOnline() {
        for (Phase phase : Phase.values()) {
            String verb = PresenceText.verb(phase);
            assertFalse(verb.isBlank(), phase.name());
            assertTrue(verb.length() <= 40, phase.name());
            if (phase == Phase.IDLE || phase == Phase.BLOCKED) continue;
            Presence p = sunsetIn(phase);
            assertTrue(p.text().startsWith(verb + " · Sunset · "), phase + ": " + p.text());
            // WAIT_CLEAR is the one phase that only waits (the machines clear, the bot stands)
            assertEquals(phase == Phase.WAIT_CLEAR ? OnlineStatus.IDLE : OnlineStatus.ONLINE, p.status(), phase.name());
        }
    }

    @Test
    void storedMapCountsAsDoneWhileThePlatformClears() {
        sunset.tiles.get(1).status = TileStatus.STORED;
        Presence p = sunsetIn(Phase.WAIT_CLEAR);
        assertEquals("Waiting for the platform to clear · Sunset · map 2 of 4 (done) · 2 maps done", p.text());
    }

    @Test
    void collectionRunOnAfterTheClearShowsCollectingItems() {
        sunset.tiles.get(1).status = TileStatus.STORED;
        EngineStatus w = working(Phase.WAIT_CLEAR, sunset, 1, 0, 10_000, 10_000);
        EngineStatus runOn = new EngineStatus(true, false, "", Phase.WAIT_CLEAR, w.jobId(), w.jobName(), 1, 0, "moss",
            10_000, 10_000, "carts collecting, 18 s left", 0);
        Presence p = of(runOn, List.of(sunset));
        assertEquals("Collecting items · Sunset · map 2 of 4 (done) · 2 maps done", p.text());
        assertEquals(OnlineStatus.IDLE, p.status(), "the carts collect, the bot waits");
        for (String counted : List.of("carts collecting, 12 items left, at most 21 s", "carts collecting, no items left, off in 2 s")) {
            EngineStatus c = new EngineStatus(true, false, "", Phase.WAIT_CLEAR, w.jobId(), w.jobName(), 1, 0, "moss",
                10_000, 10_000, counted, 0);
            assertEquals("Collecting items · Sunset · map 2 of 4 (done) · 2 maps done", of(c, List.of(sunset)).text(), counted);
        }
        EngineStatus clearing = new EngineStatus(true, false, "", Phase.WAIT_CLEAR, w.jobId(), w.jobName(), 1, 0, "moss",
            10_000, 10_000, "812 blocks left", 0);
        assertTrue(of(clearing, List.of(sunset)).text().startsWith("Waiting for the platform to clear · "));
    }

    @Test
    void setbackBackoffShowsBackingOff() {
        EngineStatus w = working(Phase.BUILD, sunset, 1, 0, 7_200, 10_000);
        EngineStatus b = new EngineStatus(true, false, "", Phase.BUILD, w.jobId(), w.jobName(), 1, 0, "moss",
            7_200, 10_000, BuildEngine.BACKOFF_NOTE, 0);
        Presence p = of(b, List.of(sunset));
        assertEquals("Backing off (server setbacks) · Sunset · map 2 of 4 (72%) · 1 map done", p.text());
        assertEquals(OnlineStatus.IDLE, p.status(), "standing still");
        // a pause, a safety stop and the link state still win
        assertTrue(of(paused(b, "SAFETY STOP: x"), List.of(sunset)).text().startsWith("SAFETY STOP: "));
        assertTrue(of(paused(b, "lunch"), List.of(sunset)).text().startsWith("Paused: lunch · "));
        assertTrue(PresenceText.describe(PresenceText.input(b, List.of(sunset), true, Link.PLAYER_IN_CONTROL, 0))
            .text().startsWith("A player is controlling the account · "));
    }

    @Test
    void eatingShowsEatingInsteadOfThePhaseItInterrupts() {
        EngineStatus e = new EngineStatus(true, false, "", Phase.BUILD, "j0042", "Sunset", 1, 0, "moss",
            7_200, 10_000, BuildEngine.EATING_NOTE + ": hunger 5/20, health 20/20", 0);
        Presence p = of(e, List.of(sunset));
        assertEquals("Eating · Sunset · map 2 of 4 (72%) · 1 map done", p.text());
        assertEquals(OnlineStatus.ONLINE, p.status());
        EngineStatus nuke = new EngineStatus(true, false, "", Phase.NUKE, "j0007", "Pine", 0, 0, "moss",
            0, 0, BuildEngine.EATING_NOTE + ": hunger 6/20, health 9/20", 0);
        assertTrue(of(nuke, List.of(pine)).text().startsWith("Eating · Pine"), of(nuke, List.of(pine)).text());
        assertTrue(of(paused(e, "lunch"), List.of(sunset)).text().startsWith("Paused: lunch · "), "a pause still wins");
    }

    @Test
    void mendingShowsMendingTools() {
        EngineStatus m = new EngineStatus(true, false, "", Phase.NUKE, "j0042", "Sunset", 1, 0, "moss",
            10_000, 10_000, BuildEngine.MENDING_NOTE + ": diamond_pickaxe at 700/1561", 0);
        Presence p = of(m, List.of(sunset));
        assertEquals("Mending tools · Sunset · map 2 of 4 (100%) · 1 map done", p.text());
        assertEquals(OnlineStatus.ONLINE, p.status());
        EngineStatus building = new EngineStatus(true, false, "", Phase.NUKE, "j0042", "Sunset", 1, 0, "moss",
            10_000, 10_000, "breaking 12 blocks", 0);
        assertTrue(of(building, List.of(sunset)).text().startsWith("Clearing leftovers · "));
    }

    @Test
    void waitingForThePlatformAboveShowsTheEngineNote() {
        EngineStatus w = new EngineStatus(true, false, "", Phase.FILL_MAP, "j0042", "Sunset", 1, 0, "moss",
            10_000, 10_000, BuildEngine.COVER_NOTE + " (3,200 pixels covered)", 0);
        Presence p = of(w, List.of(sunset));
        assertEquals("Waiting: platform above in use (3,200 pixels covered) · Sunset · map 2 of 4 (100%) · 1 map done",
            p.text());
        assertEquals(OnlineStatus.IDLE, p.status(), "only the dot says it waits; the text stays");
        EngineStatus eating = new EngineStatus(true, false, "", Phase.FILL_MAP, "j0042", "Sunset", 1, 0, "moss",
            10_000, 10_000, BuildEngine.EATING_NOTE + ": hunger 12/20, health 20/20", 0);
        assertTrue(of(eating, List.of(sunset)).text().startsWith("Eating · "), "eating during the wait shows Eating");
        assertTrue(of(paused(w, "lunch"), List.of(sunset)).text().startsWith("Paused: lunch · "), "a pause still wins");
        assertTrue(of(working(Phase.FILL_MAP, sunset, 1, 0, 10_000, 10_000), List.of(sunset)).text()
            .startsWith("Filling the map · "), "no note: the plain step");
    }

    @Test
    void aPartlyBuiltMapWaitingForMaterialsShowsTheItemsAndIsIdle() {
        EngineStatus w = new EngineStatus(true, false, "", Phase.STOCK_CHECK, "j0042", "Sunset", 1, 0, "moss",
            9_400, 10_000, BuildEngine.MATERIALS_NOTE + ": 1,122 cyan terracotta, 1 crimson nylium", 0);
        Presence p = of(w, List.of(sunset));
        assertEquals("Waiting for materials: 1,122 cyan terracotta, 1 crimson nylium · Sunset · map 2 of 4 (94%)"
            + " · 1 map done", p.text());
        assertEquals(OnlineStatus.IDLE, p.status(), "it waits with nothing to do");
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.STOCK_CHECK, "")), "the stock check itself is work");
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.BUILD, BuildEngine.MATERIALS_NOTE)), "only in STOCK_CHECK");
        assertTrue(of(new EngineStatus(true, false, "", Phase.STOCK_CHECK, "j0042", "Sunset", 1, 0, "moss", 9_400, 10_000,
            BuildEngine.EATING_NOTE + ": hunger 12/20", 0), List.of(sunset)).text().startsWith("Eating · "));
    }

    // ---------------------------------------------------------------- pause, safety, blocked

    @Test
    void pausedIsIdleAndSaysWhy() {
        EngineStatus build = working(Phase.BUILD, sunset, 1, 0, 7_200, 10_000);
        assertEquals("Paused by command · Sunset · map 2 of 4 (72%) · 1 map done",
            of(paused(build, "paused by command"), List.of(sunset)).text());
        assertEquals("Paused · Sunset · map 2 of 4 (72%) · 1 map done", of(paused(build, "paused"), List.of(sunset)).text());
        assertEquals("Paused · Sunset · map 2 of 4 (72%) · 1 map done", of(paused(build, ""), List.of(sunset)).text());
        assertEquals("Paused: chest at … moved · Sunset · map 2 of 4 (72%) · 1 map done",
            of(paused(build, "chest at 123456,64,-654321 moved"), List.of(sunset)).text(), "never a coordinate");
        Presence p = of(paused(build, "lunch break"), List.of(sunset));
        assertEquals("Paused: lunch break · Sunset · map 2 of 4 (72%) · 1 map done", p.text());
        assertEquals(OnlineStatus.IDLE, p.status(), "paused by hand: waits for resume, no human needed");
    }

    @Test
    void stuckPauseIsDoNotDisturbAndSaysStuck() {
        Presence p = of(paused(working(Phase.BUILD, sunset, 1, 0, 7_200, 10_000),
            BuildEngine.STUCK_PREFIX + "3 setback bursts at 123456,64,-654321"), List.of(sunset));
        assertEquals("Paused: stuck: 3 setback bursts at … · Sunset · map 2 of 4 (72%) · 1 map done", p.text());
        assertEquals(OnlineStatus.DO_NOT_DISTURB, p.status());
    }

    @Test
    void safetyStopIsLoudAndNeverShowsTheReasonCoordinates() {
        EngineStatus s = paused(working(Phase.BUILD, sunset, 1, 0, 7_200, 10_000),
            "SAFETY STOP: server teleported the bot 2000.0 blocks: [123456,64,-654321] -> [9000,64,9000]");
        Presence p = of(s, List.of(sunset));
        assertEquals("SAFETY STOP: server teleported the bot … blocks: … -> … · Sunset · map 2 of 4 (72%) · 1 map done",
            p.text());
        assertFalse(p.text().contains("123456") || p.text().contains("9000"), p.text());
        // a player name with 4+ digits is redacted like any long number
        assertEquals("SAFETY STOP: manual stop by jared… (Discord) · Sunset · map 2 of 4 (72%) · 1 map done",
            of(paused(working(Phase.BUILD, sunset, 1, 0, 7_200, 10_000), "SAFETY STOP: manual stop by jared2013 (Discord)"),
                List.of(sunset)).text());
        assertEquals(OnlineStatus.DO_NOT_DISTURB, p.status());
        // wins over everything else
        Presence off = PresenceText.describe(PresenceText.input(s, List.of(sunset), false, Link.OFFLINE, 0));
        assertTrue(off.text().startsWith("SAFETY STOP: "));
    }

    @Test
    void blockedShowsAShortRedactedNote() {
        EngineStatus s = blocked(working(Phase.VERIFY, sunset, 1, 0, 7_200, 10_000),
            "VERIFY: cannot break 3 blocks on the art layer, e.g. minecraft:obsidian at [123456,64,-654321]");
        Presence p = of(s, List.of(sunset));
        assertEquals("Needs help: cannot break 3 blocks on the art layer, e.g. obsidian at … · Sunset · "
            + "map 2 of 4 (72%) · 1 map done", p.text());
        assertEquals(OnlineStatus.DO_NOT_DISTURB, p.status());
    }

    @Test
    void blockedWithALongNoteStaysWithinTheLimit() {
        sunset.name = "A very long job name that somebody typed in the upload message for fun";
        EngineStatus s = blocked(working(Phase.RENAME, sunset, 1, 0, 7_200, 10_000),
            "RENAME: the anvil at 876543,70,765432 is gone and the anvil chests are empty and nobody restocked them "
                + "for a very long time, x=876543 z=-765432");
        Presence p = of(s, List.of(sunset));
        String t = p.text();
        assertTrue(t.length() <= PresenceText.MAX_LENGTH, t.length() + ": " + t);
        assertTrue(t.startsWith("Needs help: the anvil at … is gone"), t);
        assertFalse(t.contains("876543") || t.contains("765432") || t.contains("70,7"), t);
        assertFalse(t.contains("j0042"), "never a job id: " + t);
        assertTrue(t.endsWith(" · map 2 of 4 (72%) · 1 map done"), t);
        assertTrue(t.contains(" · A very long job"), t);
    }

    @Test
    void blockedWithoutNote() {
        Presence p = of(blocked(working(Phase.CLEAR, pine, 0, 0, 10_000, 10_000), ""), List.of(pine));
        assertEquals("Needs help · Pine · 100%", p.text());
    }

    @Test
    void noteRedactsPositionsAndLongNumbers() {
        assertEquals("anvil at … is gone", PresenceText.note("RENAME: anvil at [12,64,-34] is gone"));
        assertEquals("chest … 64 … unreachable", PresenceText.note("chest 876543 64 765432 unreachable"));
        assertEquals("map 1 wrong", PresenceText.note("map 1 wrong"));
        assertEquals("NOT_A_PHASE: kept", PresenceText.note("NOT_A_PHASE: kept"));
        assertEquals("", PresenceText.note(null));
        assertEquals("line one line two", PresenceText.note("line one\n\tline two"));
        assertTrue(PresenceText.note("x".repeat(500)).length() <= PresenceText.NOTE_MAX);
    }

    // ---------------------------------------------------------------- idle, builder off, link

    @Test
    void idleWithEmptyQueue() {
        Job done = job("j0001", "Old", 1, 1);
        done.status = JobStatus.DONE;
        Presence p = of(idle(), List.of(done));
        assertEquals("Idle, queue empty", p.text());
        assertEquals(OnlineStatus.IDLE, p.status());
        assertEquals("Idle, queue empty", of(null, null).text());
    }

    @Test
    void idleWithQueuedOrWaitingJobs() {
        Job a = job("j0001", "A", 1, 1);
        Job b = job("j0002", "B", 1, 1);
        assertEquals("Idle, 2 jobs queued", of(idle(), List.of(a, b)).text());
        a.status = JobStatus.WAITING_MATERIALS;
        b.status = JobStatus.WAITING_MATERIALS;
        Presence waiting = of(idle(), List.of(a, b));
        assertEquals("Waiting for materials (2 jobs)", waiting.text());
        assertEquals(OnlineStatus.IDLE, waiting.status());
        b.status = JobStatus.CANCELLED;
        assertEquals("Waiting for materials (1 job)", of(idle(), List.of(a, b)).text());
    }

    @Test
    void builderOff() {
        Job a = job("j0001", "A", 1, 1);
        Presence p = PresenceText.describe(PresenceText.input(idle(), List.of(a, sunset), false, Link.IN_GAME, 0));
        assertEquals("Builder off · 2 jobs queued", p.text());
        assertEquals(OnlineStatus.IDLE, p.status());
        assertEquals("Builder off · queue empty",
            PresenceText.describe(PresenceText.input(idle(), List.of(), false, Link.IN_GAME, 0)).text());
        Presence mid = PresenceText.describe(PresenceText.input(working(Phase.BUILD, sunset, 1, 0, 7_200, 10_000),
            List.of(sunset), false, Link.IN_GAME, 0));
        assertEquals("Builder off · Sunset · map 2 of 4 (72%) · 1 map done", mid.text());
        assertEquals(OnlineStatus.IDLE, mid.status());
    }

    @Test
    void gameLinkStates() {
        EngineStatus s = working(Phase.BUILD, sunset, 1, 0, 7_200, 10_000);
        String tail = " · Sunset · map 2 of 4 (72%) · 1 map done";
        Presence off = PresenceText.describe(PresenceText.input(s, List.of(sunset), true, Link.OFFLINE, 0));
        assertEquals("Disconnected" + tail, off.text());
        assertEquals(OnlineStatus.IDLE, off.status());
        assertEquals("In the server queue (position 123)" + tail,
            PresenceText.describe(PresenceText.input(s, List.of(sunset), true, Link.IN_QUEUE, 123)).text());
        assertEquals("In the server queue" + tail,
            PresenceText.describe(PresenceText.input(s, List.of(sunset), true, Link.IN_QUEUE, 0)).text());
        assertEquals("A player is controlling the account" + tail,
            PresenceText.describe(PresenceText.input(s, List.of(sunset), true, Link.PLAYER_IN_CONTROL, 0)).text());
        // paused wins over the link
        Presence pausedOff = PresenceText.describe(PresenceText.input(paused(s, "x"), List.of(sunset), true,
            Link.OFFLINE, 0));
        assertEquals("Paused: x" + tail, pausedOff.text());
    }

    // ---------------------------------------------------------------- online status (statusFor)

    static EngineStatus noted(Phase phase, String note) {
        return new EngineStatus(true, false, "", phase, "j0042", "Sunset", 1, 0, "moss", 7_200, 10_000, note, 0);
    }

    OnlineStatus statusOf(EngineStatus s) {
        return statusOf(s, true, Link.IN_GAME);
    }

    OnlineStatus statusOf(EngineStatus s, boolean builder, Link link) {
        PresenceText.Input in = PresenceText.input(s, List.of(sunset), builder, link, 0);
        OnlineStatus status = PresenceText.statusFor(in);
        assertEquals(status, PresenceText.describe(in).status(), "describe uses statusFor");
        return status;
    }

    @Test
    void workingStepsAreOnline() {
        for (Phase p : List.of(Phase.SELECT, Phase.INSPECT, Phase.STOCK_CHECK, Phase.GEAR, Phase.RESTOCK, Phase.BUILD,
            Phase.VERIFY, Phase.FILL_MAP, Phase.CHECK_MAP, Phase.LOCK, Phase.RENAME, Phase.STORE, Phase.COUNT,
            Phase.NUKE, Phase.CLEAR, Phase.FINISH_TILE))
            assertEquals(OnlineStatus.ONLINE, statusOf(noted(p, "")), p.name());
        // notes of work in progress keep it online
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.NUKE, "breaking 12 blocks")));
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.COUNT, BuildEngine.COUNTER_NOTE)));
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.COUNT, BuildEngine.COUNTER_WAIT_NOTE)), "seconds only");
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.RESTOCK, "chest unreachable")), "a step retry");
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.CHECK_MAP, "no map data for map 990: making a fresh map")),
            "making the fresh map is work");
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.BUILD, BuildEngine.EATING_NOTE + ": hunger 5/20")));
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.NUKE, BuildEngine.MENDING_NOTE + ": diamond_pickaxe")));
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.BUILD,
            BuildEngine.BACKOFF_NOTE + "; stepping away (escape 1 of 3)")), "the escape walks");
    }

    @Test
    void waitsWithNothingToDoAreIdle() {
        assertEquals(OnlineStatus.IDLE, statusOf(noted(Phase.FILL_MAP, BuildEngine.COVER_NOTE + " (3,200 pixels covered)")));
        assertEquals(OnlineStatus.IDLE, statusOf(noted(Phase.FILL_MAP, BuildEngine.EYEBLOSSOM_NOTE + "close (day)")));
        assertEquals(OnlineStatus.IDLE, statusOf(noted(Phase.CHECK_MAP, BuildEngine.MAP_DATA_NOTE + " for map 990")));
        assertEquals(OnlineStatus.IDLE, statusOf(noted(Phase.WAIT_CLEAR, "")));
        assertEquals(OnlineStatus.IDLE, statusOf(noted(Phase.WAIT_CLEAR, "812 blocks left")));
        assertEquals(OnlineStatus.IDLE, statusOf(noted(Phase.WAIT_CLEAR, BuildEngine.COLLECTING_NOTE + ", 18 s left")));
        for (Phase p : List.of(Phase.BUILD, Phase.FILL_MAP, Phase.RESTOCK, Phase.WAIT_CLEAR))
            assertEquals(OnlineStatus.IDLE, statusOf(noted(p, BuildEngine.BACKOFF_NOTE)), "back-off in " + p);
        // the wait notes only count in their own phase
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.BUILD, BuildEngine.COVER_NOTE)));
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.FILL_MAP, BuildEngine.MAP_DATA_NOTE)));
        // eating or mending during a wait is work
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.FILL_MAP, BuildEngine.EATING_NOTE + ": hunger 12/20")));
        assertEquals(OnlineStatus.ONLINE, statusOf(noted(Phase.WAIT_CLEAR, BuildEngine.MENDING_NOTE + ": shears")));
    }

    @Test
    void idleQueuePauseAndLinkAreIdle() {
        assertEquals(OnlineStatus.IDLE, statusOf(idle()), "queue empty");
        assertEquals(OnlineStatus.IDLE, statusOf(null), "engine not built yet");
        assertEquals(OnlineStatus.IDLE, statusOf(noted(Phase.IDLE, "")), "idle with a job");
        Job a = job("j0001", "A", 1, 1);
        a.status = JobStatus.WAITING_MATERIALS;
        assertEquals(OnlineStatus.IDLE, PresenceText.statusFor(PresenceText.input(idle(), List.of(a), true,
            Link.IN_GAME, 0)), "every job short of materials");
        EngineStatus build = noted(Phase.BUILD, "");
        assertEquals(OnlineStatus.IDLE, statusOf(paused(build, "lunch")));
        assertEquals(OnlineStatus.IDLE, statusOf(build, false, Link.IN_GAME), "builder off");
        assertEquals(OnlineStatus.IDLE, statusOf(build, true, Link.OFFLINE));
        assertEquals(OnlineStatus.IDLE, statusOf(build, true, Link.IN_QUEUE));
        assertEquals(OnlineStatus.IDLE, statusOf(build, true, Link.PLAYER_IN_CONTROL));
        assertEquals(OnlineStatus.ONLINE, PresenceText.statusFor(new PresenceText.Input(build, sunset, true, null, 0, 1, 0)),
            "no link known: as in game");
    }

    @Test
    void aHumanNeededIsDoNotDisturb() {
        EngineStatus build = noted(Phase.BUILD, "");
        assertEquals(OnlineStatus.DO_NOT_DISTURB, statusOf(paused(build, "SAFETY STOP: respawn")));
        assertEquals(OnlineStatus.DO_NOT_DISTURB, statusOf(paused(build, "SAFETY STOP: manual stop by jared2013 (Discord)")),
            "the kill switch is a safety stop");
        assertEquals(OnlineStatus.DO_NOT_DISTURB, statusOf(paused(build, BuildEngine.STUCK_PREFIX + "setbacks")));
        assertEquals(OnlineStatus.DO_NOT_DISTURB, statusOf(blocked(build, "VERIFY: cannot break 3 blocks")));
        assertEquals(OnlineStatus.DO_NOT_DISTURB, statusOf(paused(blocked(build, "x"), "lunch")), "BLOCKED, paused");
        // wins over the link, the builder switch and a back-off note
        assertEquals(OnlineStatus.DO_NOT_DISTURB, statusOf(paused(build, "SAFETY STOP: x"), false, Link.OFFLINE));
        assertEquals(OnlineStatus.DO_NOT_DISTURB, statusOf(blocked(build, BuildEngine.BACKOFF_NOTE), true, Link.IN_QUEUE));
        assertEquals(OnlineStatus.IDLE, statusOf(paused(build, "stuck in traffic")), "a hand pause that says stuck");
    }

    // ---------------------------------------------------------------- % math

    @Test
    void zeroOverZeroShowsNoPercent() {
        Job empty = job("j0009", "Empty", 1, 1);
        empty.tiles.get(0).total = 0;
        assertEquals("Printing · Empty", of(working(Phase.BUILD, empty, 0, 0, 0, 0), List.of(empty)).text());
        assertNull(StatusWords.tileFraction(null, null));
    }

    @Test
    void withoutLiveNumbersTheSavedTileStateIsTheLastKnownPercent() {
        pine.tiles.get(0).placed = 5_000;
        // e.g. SELECT/INSPECT after a restart: the snapshot has no total yet
        assertEquals("Inspecting the platform · Pine · 50%",
            of(working(Phase.INSPECT, pine, 0, 0, 0, 0), List.of(pine)).text());
        sunset.tiles.get(1).placed = 2_000;
        assertEquals("Starting · Sunset · map 2 of 4 (20%) · 1 map done",
            of(working(Phase.SELECT, sunset, 1, 0, 0, 0), List.of(sunset)).text());
    }

    @Test
    void jobMissingFromTheStoreFallsBackToTheSnapshot() {
        assertEquals("Printing · Sunset · 72%",
            of(working(Phase.BUILD, sunset, 1, 0, 7_200, 10_000), List.of()).text());
    }

    @Test
    void percentMath() {
        assertEquals(0, PresenceText.percent(0));
        assertEquals(29, PresenceText.percent(0.29));
        assertEquals(99, PresenceText.percent(0.999));
        assertEquals(100, PresenceText.percent(1));
        assertEquals(100, PresenceText.percent(1.5));
        assertEquals(0, PresenceText.percent(-1));
        // more placed than planned is clamped
        assertEquals("Printing · Pine · 100%",
            of(working(Phase.BUILD, pine, 0, 0, 12_000, 10_000), List.of(pine)).text());
    }

    // ---------------------------------------------------------------- names and length

    @Test
    void namesAreOneLineAndNotRepeatedAsId() {
        pine.name = "  Pine\n\ttree  ";
        assertEquals("Printing · Pine tree · 43%",
            of(working(Phase.BUILD, pine, 0, 0, 4_300, 10_000), List.of(pine)).text());
        pine.name = "j0007";
        assertEquals("Printing · j0007 · 43%",
            of(working(Phase.BUILD, pine, 0, 0, 4_300, 10_000), List.of(pine)).text());
    }

    @Test
    void longNamesAreShortened() {
        sunset.name = "N".repeat(100);
        String t = sunsetIn(Phase.BUILD).text();
        assertEquals("Printing · " + "N".repeat(PresenceText.NAME_MAX - 1) + "… · map 2 of 4 (72%) · 1 map done", t);
    }

    @Test
    void everythingStaysWithin128Chars() {
        sunset.name = "🌅".repeat(80); // emoji: surrogate pairs must not be split
        List<String> notes = List.of("", "short", "BUILD: " + "word ".repeat(80));
        List<Job> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) many.add(job("j" + i, "x", 1, 1));
        for (Phase phase : Phase.values())
            for (String note : notes)
                for (Link link : Link.values()) {
                    EngineStatus s = new EngineStatus(true, false, "", phase, "j0042-with-a-long-id-" + "9".repeat(60),
                        sunset.name, 1, 0, "moss", 7_200, 10_000, note, 0);
                    for (EngineStatus v : List.of(s, paused(s, "SAFETY STOP: x"), blocked(s, note))) {
                        Presence p = PresenceText.describe(PresenceText.input(v, many, true, link, 99_999));
                        String t = p.text();
                        assertTrue(t.length() <= PresenceText.MAX_LENGTH, t.length() + ": " + t);
                        assertFalse(t.isBlank());
                        for (int i = 0; i < t.length(); i++) {
                            char c = t.charAt(i);
                            if (Character.isHighSurrogate(c))
                                assertTrue(i + 1 < t.length() && Character.isLowSurrogate(t.charAt(i + 1)), t);
                            if (Character.isLowSurrogate(c))
                                assertTrue(i > 0 && Character.isHighSurrogate(t.charAt(i - 1)), t);
                        }
                    }
                }
    }

    @Test
    void cutNeverSplitsSurrogatesAndEndsWithEllipsis() {
        assertEquals("abc", PresenceText.cut("abc", 3));
        assertEquals("ab…", PresenceText.cut("abcd", 3));
        assertEquals("a…", PresenceText.cut("a🌅bc", 3));
        assertEquals("", PresenceText.cut("abc", 0));
    }

    // ---------------------------------------------------------------- ETA, queue count, several maps

    static final long T0 = 1_800_000_000_000L;

    static EngineStatus eta(EngineStatus s, long etaSeconds) {
        return new EngineStatus(s.enabled(), s.paused(), s.pauseReason(), s.phase(), s.jobId(), s.jobName(), s.tileX(),
            s.tileZ(), s.platformId(), s.placed(), s.total(), s.note(), s.phaseSince(), etaSeconds, 0, 0);
    }

    @Test
    void oneMapShowsItsEta() {
        assertEquals("Printing · Pine · 43% · ~10m left",
            of(eta(working(Phase.BUILD, pine, 0, 0, 4_300, 10_000), 600), List.of(pine)).text());
        assertEquals("Printing · Pine · 43%", of(eta(working(Phase.BUILD, pine, 0, 0, 4_300, 10_000), -1), List.of(pine)).text());
    }

    @Test
    void severalMapsShowTheJobsEtaFromTheStoredMapsElseTheMaps() {
        EngineStatus s = eta(working(Phase.BUILD, sunset, 1, 0, 7_200, 10_000), 600);
        assertEquals("Printing · Sunset · map 2 of 4 (72%) · 1 map done · map ~10m left", of(s, List.of(sunset)).text());
        sunset.tiles.get(0).startedAt = T0;
        sunset.tiles.get(0).finishedAt = T0 + 30 * 60_000L;
        // this map's 10 minutes and two more maps of 30 minutes each
        assertEquals("Printing · Sunset · map 2 of 4 (72%) · 1 map done · job ~1h 10m left", of(s, List.of(sunset)).text());
    }

    @Test
    void theMapNumberIsItsPlaceInTheJobsBuildOrder() {
        Job big = job("j0050", "Mural", 10, 8);
        big.status = JobStatus.ACTIVE;
        for (int i = 0; i < 4; i++) big.tiles.get(i).status = TileStatus.STORED;
        // tile 4,0 is the fifth map
        assertEquals("Printing · Mural · map 5 of 80 (72%) · 4 maps done",
            of(working(Phase.BUILD, big, 4, 0, 7_200, 10_000), List.of(big)).text());
        // tile 3,4 is map 4 * 10 + 3 + 1
        assertEquals("Printing · Mural · map 44 of 80 (72%) · 4 maps done",
            of(working(Phase.BUILD, big, 3, 4, 7_200, 10_000), List.of(big)).text());
    }

    @Test
    void otherQueuedJobsAreCounted() {
        List<Job> jobs = new ArrayList<>(List.of(pine));
        for (int i = 0; i < 5; i++) jobs.add(job("j010" + i, "Q" + i, 1, 1));
        Job done = job("j0001", "Old", 1, 1);
        done.status = JobStatus.DONE;
        jobs.add(done);
        assertEquals("Printing · Pine · 43% · +5 queued", of(working(Phase.BUILD, pine, 0, 0, 4_300, 10_000), jobs).text());
        assertEquals("Printing · Pine · 43%", of(working(Phase.BUILD, pine, 0, 0, 4_300, 10_000), List.of(pine, done)).text());
    }

    @Test
    void etaWords() {
        assertEquals("", StatusWords.eta(-1));
        assertEquals("", StatusWords.eta(0));
        assertEquals("~1m left", StatusWords.eta(20));
        assertEquals("~25m left", StatusWords.eta(25 * 60 + 40));
        assertEquals("~2h 10m left", StatusWords.eta(2 * 3600 + 11 * 60));
        assertEquals("~1d 06h left", StatusWords.eta(30 * 3600));
        assertEquals("", StatusWords.eta(ETA_MAX + 1), "not a sane ETA");
    }

    static final long ETA_MAX = 30L * 24 * 3600;

    // ---------------------------------------------------------------- colour suppression: one map on two layers

    static final String FOX = "Colorado Hike with a Fox 2,1 (jared2013)";

    /** A colour-suppression job as stored: 8 passes as tiles (i,0), the top layer's four first, 4,000 blocks each. */
    static Job fox(int passesDone) {
        Job j = job("j0004", FOX, 8, 1);
        j.status = JobStatus.ACTIVE;
        j.suppression = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            j.suppression.add(new Job.SuppressionPass(i < 4 ? io.github.digitalisp.mapart.model.SuppressionArt.TOP
                : io.github.digitalisp.mapart.model.SuppressionArt.BOTTOM, i % 2, i % 4 < 2));
            Job.TileState t = j.tiles.get(i);
            t.total = 4_000;
            if (i < passesDone) {
                t.status = TileStatus.STORED;
                t.placed = 4_000;
            }
        }
        return j;
    }

    static EngineStatus cs(Phase phase, Job j, int pass, int placed, String note) {
        return new EngineStatus(true, false, "", phase, j.id, j.name, pass, 0, "cs", placed, 4_000, note, 0);
    }

    static EngineStatus riding(Job j, long startedAt, long expectedSeconds) {
        return new EngineStatus(true, false, "", Phase.RIDE, j.id, j.name, 7, 0, "cs", 4_000, 4_000,
            BuildEngine.RIDE_NOTE, 0, -1, startedAt, expectedSeconds);
    }

    static Presence at(EngineStatus s, List<Job> jobs, long now) {
        return PresenceText.describe(PresenceText.input(s, jobs, true, Link.IN_GAME, 0).at(now));
    }

    @Test
    void csPrintShowsTheLayerAndThePercentOfTheWholeMap() {
        Job fox = fox(2);
        // 2 passes of 8 done and half of the third: 10,000 of 32,000 blocks
        Presence p = of(eta(cs(Phase.BUILD, fox, 2, 2_000, ""), 7_800), List.of(fox));
        assertEquals("Printing · " + FOX + " · top layer · 31% · ~2h 10m left", p.text());
        assertEquals(OnlineStatus.ONLINE, p.status());
        Job low = fox(5);
        assertEquals("Printing · " + FOX + " · bottom layer · 68%", of(cs(Phase.BUILD, low, 5, 2_000, ""), List.of(low)).text());
        assertEquals("Restocking · " + FOX + " · bottom layer · 68%", of(cs(Phase.RESTOCK, low, 5, 2_000, ""), List.of(low)).text());
        assertEquals("Checking the art · " + FOX + " · bottom layer · 75%", of(cs(Phase.VERIFY, low, 5, 4_000, ""), List.of(low)).text());
        // a pass not started yet (SELECT, INSPECT: no live count) counts by its saved state
        low.tiles.get(5).placed = 1_000;
        assertEquals("Inspecting the platform · " + FOX + " · bottom layer · 65%",
            of(new EngineStatus(true, false, "", Phase.INSPECT, low.id, low.name, 5, 0, "cs", 0, 0, "", 0), List.of(low)).text());
        List<Job> queue = new ArrayList<>(List.of(fox));
        for (int i = 0; i < 5; i++) queue.add(job("j010" + i, "Q" + i, 1, 1));
        assertEquals("Printing · " + FOX + " · top layer · 31% · ~2h 10m left · +5 queued",
            of(eta(cs(Phase.BUILD, fox, 2, 2_000, ""), 7_800), queue).text());
    }

    @Test
    void csWaitsReadInWords() {
        Job fox = fox(4);
        Presence layer = of(cs(Phase.INSPECT, fox, 4, 0, BuildEngine.LAYER_WAIT_NOTE + "bottom layer: 1240 blocks left"), List.of(fox));
        assertEquals("Waiting for the muncher to clear the bottom layer (1,240 blocks left) · " + FOX + " · 50%", layer.text());
        assertEquals(OnlineStatus.IDLE, layer.status(), "the muncher works, the bot waits");
        Presence short_ = of(cs(Phase.STOCK_CHECK, fox, 4, 0, BuildEngine.MATERIALS_NOTE + ": 1,122 cyan terracotta"), List.of(fox));
        assertEquals("Waiting for materials: 1,122 cyan terracotta · " + FOX + " · bottom layer · 50%", short_.text());
        assertEquals(OnlineStatus.IDLE, short_.status());
    }

    @Test
    void csStepsFromTheFillToTheCounterReadInWordsWithoutAPercent() {
        Job fox = fox(7);
        fox.tiles.get(7).status = TileStatus.ACTIVE;
        fox.tiles.get(7).placed = 3_990; // a stale count must not bring "99%" back after the print
        List<Job> jobs = List.of(fox);
        assertEquals("Filling the map · " + FOX, of(cs(Phase.FILL_MAP, fox, 7, 4_000, ""), jobs).text());
        assertEquals("Checking the map · " + FOX, of(cs(Phase.CHECK_MAP, fox, 7, 3_990, ""), jobs).text());
        assertEquals("Pre-ride checks · " + FOX, of(cs(Phase.RIDE_BOARD, fox, 7, 4_000, ""), jobs).text());
        Presence machines = of(cs(Phase.RIDE_BOARD, fox, 7, 4_000,
            BuildEngine.GUARD_WAIT_NOTE + " before the ride: 64 cells differ"), jobs);
        assertEquals("Waiting for the machines to come home · " + FOX, machines.text());
        assertEquals(OnlineStatus.IDLE, machines.status());
        Presence restart = of(cs(Phase.RIDE_BOARD, fox, 7, 4_000, BuildEngine.RESTART_NOTE + ": the ride waits"), jobs);
        assertEquals("Waiting for the server restart · " + FOX, restart.text());
        assertEquals(OnlineStatus.IDLE, restart.status());
        assertEquals("Riding the preload lap · " + FOX, of(cs(Phase.RIDE_PRELOAD, fox, 7, 4_000, BuildEngine.PRELOAD_NOTE), jobs).text());
        assertEquals("Leaving the cart · " + FOX, of(cs(Phase.RIDE_END, fox, 7, 4_000, ""), jobs).text());
        assertEquals("Locking the map · " + FOX, of(cs(Phase.LOCK, fox, 7, 4_000, ""), jobs).text());
        assertEquals("Naming the map · " + FOX, of(cs(Phase.RENAME, fox, 7, 4_000, ""), jobs).text());
        fox.tiles.get(7).cartReturnDue = true;
        assertEquals("Returning the cart · " + FOX, of(cs(Phase.STORE, fox, 7, 4_000, ""), jobs).text());
        fox.tiles.get(7).cartReturned = true;
        assertEquals("Storing the map · " + FOX, of(cs(Phase.STORE, fox, 7, 4_000, ""), jobs).text());
        fox.tiles.get(7).status = TileStatus.STORED;
        assertEquals("Counting the map · " + FOX, of(cs(Phase.COUNT, fox, 7, 4_000, BuildEngine.COUNTER_NOTE), jobs).text());
        Presence reset = of(cs(Phase.RIDE_RESET, fox, 7, 4_000, BuildEngine.RESET_WAIT_NOTE + ": 2100 blocks still on the layers"), jobs);
        assertEquals("Waiting for the muncher to reset · " + FOX, reset.text());
        assertEquals(OnlineStatus.IDLE, reset.status());
    }

    /** The owner's screenshot, 2026-11-14: "Riding the muncher · ... (j0004) · tile 7,0 (100%) · 7/8 maps done". */
    @Test
    void csRideShowsTheTimeSinceTheLaunchAgainstTheExpectedRide() {
        Job fox = fox(7);
        fox.tiles.get(7).status = TileStatus.ACTIVE;
        fox.tiles.get(7).placed = 4_000;
        List<Job> jobs = List.of(fox);
        Presence p = at(riding(fox, T0, 75 * 60), jobs, T0 + 41 * 60_000L);
        assertEquals("Riding the muncher · " + FOX + " · 41 of ~75 min (54%)", p.text());
        assertEquals(OnlineStatus.ONLINE, p.status());
        assertEquals("Riding the muncher · " + FOX + " · 0 of ~75 min (0%)", at(riding(fox, T0, 75 * 60), jobs, T0 + 5_000).text());
        // the last ride took 75.5 minutes
        assertEquals("Riding the muncher · " + FOX + " · 74 of ~76 min (98%)", at(riding(fox, T0, 4_530), jobs, T0 + 74 * 60_000L).text());
        // running over: never 100% before the brake
        assertEquals("Riding the muncher · " + FOX + " · 80 of ~75 min (99%)", at(riding(fox, T0, 75 * 60), jobs, T0 + 80 * 60_000L).text());
        // in the cart before the launch, or no clock: the step alone
        assertEquals("Riding the muncher · " + FOX, at(riding(fox, 0, 0), jobs, T0).text());
        assertEquals("Riding the muncher · " + FOX, of(riding(fox, T0, 75 * 60), jobs).text());
        List<Job> queue = new ArrayList<>(jobs);
        queue.add(job("j0101", "Next", 1, 1));
        assertEquals("Riding the muncher · " + FOX + " · 41 of ~75 min (54%) · +1 queued",
            at(riding(fox, T0, 75 * 60), queue, T0 + 41 * 60_000L).text());
    }

    @Test
    void csNeverShowsATileAPassOrACountOfMaps() {
        for (int done = 0; done <= 8; done++) {
            Job fox = fox(done);
            int pass = Math.min(done, 7);
            List<String> notes = List.of("", BuildEngine.LAYER_WAIT_NOTE + "top layer: 12 blocks left",
                BuildEngine.MATERIALS_NOTE + ": 3 clay", BuildEngine.RIDE_NOTE, "RIDE: the cart stood still for 10 min");
            for (Phase phase : Phase.values())
                for (String note : notes) {
                    EngineStatus s = new EngineStatus(true, false, "", phase, fox.id, fox.name, pass, 0, "cs", 1_234, 4_000,
                        note, 0, 3_000, T0, 4_500);
                    for (EngineStatus v : List.of(s, paused(s, "lunch"), blocked(s, note)))
                        for (Link link : Link.values()) {
                            String t = PresenceText.describe(PresenceText.input(v, List.of(fox, pine), true, link, 7)
                                .at(T0 + 600_000)).text();
                            String where = phase + " / " + note + ": " + t;
                            assertTrue(t.length() <= PresenceText.MAX_LENGTH, where);
                            assertFalse(t.contains("tile"), where);
                            assertFalse(t.toLowerCase(java.util.Locale.ROOT).contains("pass"), where);
                            assertFalse(t.contains("maps done") || t.contains("map done") || t.contains(" of 8"), where);
                            assertFalse(t.contains("j0004"), where);
                            assertTrue(t.contains("Colorado"), where);
                        }
                }
        }
    }

    // ---------------------------------------------------------------- the 128-character limit

    @Test
    void theNameIsShortenedFirstAndTheNumbersNeverAre() {
        String longName = "A very long job name that somebody typed in the upload message for fun, twice over";
        List<Job> queue = new ArrayList<>();
        for (int i = 0; i < 99; i++) queue.add(job("j1" + i, "Q" + i, 1, 1));

        Job fox = fox(2);
        fox.name = longName;
        List<Job> jobs = new ArrayList<>(List.of(fox));
        jobs.addAll(queue);
        String print = of(eta(cs(Phase.BUILD, fox, 2, 2_000, ""), 7_800), jobs).text();
        assertEquals("Printing · " + longName.substring(0, 39) + "… · top layer · 31% · ~2h 10m left · +99 queued", print);

        Job waiting = fox(4);
        waiting.name = longName;
        jobs.set(0, waiting);
        String wait = of(cs(Phase.INSPECT, waiting, 4, 0, BuildEngine.LAYER_WAIT_NOTE + "bottom layer: 31240 blocks left"), jobs).text();
        assertTrue(wait.length() <= PresenceText.MAX_LENGTH, wait.length() + ": " + wait);
        assertTrue(wait.startsWith("Waiting for the muncher to clear the bottom layer (31,240 blocks left) · A very long job"), wait);
        assertTrue(wait.endsWith("… · 50% · +99 queued"), wait);

        Job ride = fox(7);
        ride.name = longName;
        jobs.set(0, ride);
        String riding = at(riding(ride, T0, 75 * 60), jobs, T0 + 41 * 60_000L).text();
        assertEquals("Riding the muncher · " + longName.substring(0, 39) + "… · 41 of ~75 min (54%) · +99 queued", riding);

        Job big = job("j0050", longName, 10, 8);
        big.status = JobStatus.ACTIVE;
        for (int i = 0; i < 79; i++) {
            big.tiles.get(i).status = TileStatus.STORED;
            big.tiles.get(i).startedAt = T0;
            big.tiles.get(i).finishedAt = T0 + 30 * 60_000L;
        }
        jobs.set(0, big);
        String multi = of(eta(working(Phase.BUILD, big, 9, 7, 9_999, 10_000), 29L * 24 * 3600), jobs).text();
        assertTrue(multi.length() <= PresenceText.MAX_LENGTH, multi.length() + ": " + multi);
        assertTrue(multi.startsWith("Printing · A very long job"), multi);
        assertTrue(multi.endsWith("… · map 80 of 80 (99%) · 79 maps done · job ~29d 00h left · +99 queued"), multi);

        // a long reason in front: the name goes down to its minimum, then the reason is cut; the numbers stay
        String blocked = of(blocked(working(Phase.BUILD, big, 9, 7, 9_999, 10_000),
            "BUILD: " + "the chest is unreachable and ".repeat(6)), jobs).text();
        assertTrue(blocked.length() <= PresenceText.MAX_LENGTH, blocked.length() + ": " + blocked);
        assertTrue(blocked.endsWith(" · A very lo… · map 80 of 80 (99%) · 79 maps done · +99 queued"), blocked);
        assertTrue(blocked.startsWith("Needs help: the chest is unreachable"), blocked);
    }
}
