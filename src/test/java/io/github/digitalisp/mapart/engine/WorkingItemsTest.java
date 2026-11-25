package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import io.github.digitalisp.mapart.engine.SimFakes.FakeInventory.FakeTool;
import io.github.digitalisp.mapart.engine.SimFakes.SerpentinePlanner;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.plan.GreedyRestockPlanner;
import io.github.digitalisp.mapart.testutil.RecordingLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot's working items (live 2b2t, 2026-10-11, ellen map 1): during a cover wait FILL_MAP made one more map after
 * each of two reconnects and after a restart (map ids change per connection, so the held map was not recognised), and
 * the stale maps, a glass pane and XP bottles then sat in the inventory long after their use, taking load slots.
 * Now the tile's carried map is found again (by its pixels, else in the main hand), at most one map is made per tile,
 * and after STORE the stale maps go to the scrap map chest and panes, empty maps and XP bottles to their chests, never
 * dropped; the owner's own items never move.
 */
class WorkingItemsTest {
    private static final int LONG = 80_000;
    private static final int GLASS = 64 + 27;
    private static final String WOOL = "minecraft:red_wool";
    private static final String PANE = "minecraft:glass_pane";
    private static final String MAP = "minecraft:map";
    private static final String XP = "minecraft:experience_bottle";
    private static final String ROCKET = "minecraft:firework_rocket";
    private static final String SHULKER = "minecraft:shulker_box";
    private static final String TOTEM = "minecraft:totem_of_undying";
    private static final Pos SCRAP = new Pos(-36, 61, 100);

    private static Sim sim(RecordingLogger log) {
        Sim sim = new Sim();
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        return sim;
    }

    private static String tail(RecordingLogger log) {
        List<String> l = log.lines;
        return "\n" + String.join("\n", l.subList(Math.max(0, l.size() - 40), l.size()));
    }

    private static long lines(RecordingLogger log, Level level, String part) {
        return log.at(level).stream().filter(l -> l.contains(part)).count();
    }

    private static List<Pos> cover(Sim sim, int px, int pz, int w, int h) {
        List<Pos> out = new ArrayList<>();
        for (int dz = 0; dz < h; dz++)
            for (int dx = 0; dx < w; dx++) {
                Pos p = new Pos(sim.moss.minX + px + dx, GLASS + 1, sim.moss.minZ + pz + dz);
                sim.world.set(p, BlockSpec.of(WOOL));
                out.add(p);
            }
        return out;
    }

    private static void uncover(Sim sim, List<Pos> blocks) {
        for (Pos p : blocks) sim.world.set(p, BlockSpec.AIR);
    }

    private static boolean waiting(Sim sim) {
        String n = sim.engine.status().note();
        return sim.phase() == Phase.FILL_MAP && n != null && n.startsWith(BuildEngine.COVER_NOTE) && sim.maps.held != null;
    }

    /**
     * A new connection (a reconnect, or ZenithProxy restarting): every carried map comes back under a new id with its
     * picture, name and locked flag; the main hand holds the map it held when {@code keepHeld} (the server restores the
     * selected slot), else nothing. Returns old id → new id.
     */
    private static Map<Integer, Integer> newIds(Sim sim, boolean keepHeld) {
        Map<Integer, byte[]> pictures = new HashMap<>(sim.maps.colors);
        Map<Integer, String> names = new HashMap<>(sim.inv.mapNames);
        List<Integer> locked = new ArrayList<>(sim.maps.locked);
        List<Integer> order = new ArrayList<>(sim.inv.filledMaps);
        Integer held = sim.maps.held;
        sim.maps.colors.clear();
        sim.inv.mapNames.clear();
        sim.maps.locked.clear();
        sim.inv.filledMaps.clear();
        sim.maps.held = null;
        Map<Integer, Integer> ids = new HashMap<>();
        for (int old : order) {
            int id = sim.maps.nextId++;
            ids.put(old, id);
            sim.inv.filledMaps.add(id);
            if (locked.contains(old)) sim.maps.locked.add(id);
            if (names.containsKey(old)) sim.inv.mapNames.put(id, names.get(old));
            if (pictures.containsKey(old)) sim.maps.colors.put(id, pictures.get(old));
        }
        if (keepHeld && held != null) sim.maps.held = ids.get(held);
        return ids;
    }

    private static void reconnect(Sim sim, boolean keepHeld) {
        sim.engine.onBotTickStopped();
        sim.engine.onDisconnect();
        newIds(sim, keepHeld);
        sim.engine.onBotTickStarting();
    }

    /** ZenithProxy restarts: a new engine from the checkpoint and the job file, and a new connection. */
    private static void restart(Sim sim, RecordingLogger log, boolean keepHeld) {
        sim.engine.onBotTickStopped();
        sim.engine.shutdown();
        newIds(sim, keepHeld);
        sim.newEngine(new SerpentinePlanner(), new GreedyRestockPlanner(), log);
        sim.start();
    }

    /** the owner's own unlocked map of another place, carried by the account. */
    private static byte[] elsewhere() {
        byte[] px = new byte[128 * 128];
        Arrays.fill(px, (byte) (11 << 2 | 1));
        return px;
    }

    /** What a map of the finished {@code Sim.single(WHITE, 4)} tile shows on the art rows (the first row shaded high). */
    private static byte[] artPicture() {
        byte[] px = new byte[128 * 128];
        for (int pz = 0; pz < 4; pz++)
            for (int px0 = 0; px0 < 128; px0++) px[pz * 128 + px0] = (byte) (8 << 2 | (pz == 0 ? 2 : 1));
        return px;
    }

    // ---------------------------------------------------------------------------------------------
    // the hold map

    @Test
    void aCoverWaitThroughTwoReconnectsAndARestartMakesOneMap() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.inv.filledMaps.add(8);
        sim.maps.colors.put(8, elsewhere());
        Job job = sim.addJob("j0400", "Held", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        List<Pos> wool = cover(sim, 10, 0, 20, 4); // 80 of the 512 art pixels, as the live wait hid 40 % of ellen
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state() + tail(log));
        assertEquals(1, sim.bot.createdMaps);
        assertEquals(1, job.tiles.getFirst().unlockedMaps, "the tile counts its map");

        reconnect(sim, false);
        sim.run(200);
        assertTrue(waiting(sim), sim.state() + tail(log));
        assertEquals(1, sim.bot.createdMaps, "no map made after the first reconnect" + tail(log));
        reconnect(sim, false);
        sim.run(200);
        assertTrue(waiting(sim), sim.state() + tail(log));
        assertEquals(1, sim.bot.createdMaps, "no map made after the second reconnect" + tail(log));
        restart(sim, log, false);
        assertTrue(sim.runUntil(() -> waiting(sim), 2_000), sim.state() + tail(log));
        assertEquals(1, sim.bot.createdMaps, "no map made after the restart" + tail(log));
        // a reconnect: the picture the map showed before; the restart forgot it: the art that shows
        assertEquals(2, lines(log, Level.INFO, "found again (100 % the same as the map held before)"), tail(log));
        assertEquals(1, lines(log, Level.INFO, "found again (84 % of the art)"), tail(log));
        assertEquals(1, lines(log, Level.INFO, "creating the map to hold"), "only before the reconnects" + tail(log));

        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(1, sim.bot.createdMaps, "one map for the tile, the one held through the wait");
        assertEquals(1, sim.bot.count("withdraw " + Sim.MAPS), "one empty map, with the last load: " + sim.bot.history);
        assertEquals(1, sim.output().maps.size());
        assertEquals("Held", sim.output().maps.getFirst().name());
        assertTrue(sim.bot.containers.get(Sim.MAPS).maps.isEmpty(), "no stale map to scrap");
        assertEquals(1, sim.inv.filledMaps.size(), "the owner's map stays: " + sim.inv.filledMaps);
        int phis = sim.inv.filledMaps.getFirst();
        assertFalse(sim.maps.locked.contains(phis));
        assertTrue(Arrays.equals(elsewhere(), sim.maps.colors.get(phis)), "the owner's map never held at the center");
        assertEquals(0, job.tiles.getFirst().unlockedMaps, "counted down by the lock");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void underACoverOverTheWholeArtAReconnectFindsTheMapByThePictureItShowed() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = sim.addJob("j0401", "Hidden", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        List<Pos> wool = cover(sim, 0, 0, 128, 4); // nothing of the art shows on the map
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state() + tail(log));

        reconnect(sim, false); // nothing in hand (eating at the disconnect, say)
        sim.run(200);
        assertTrue(waiting(sim), sim.state() + tail(log));
        assertEquals(1, sim.bot.createdMaps, tail(log));
        assertEquals(1, lines(log, Level.INFO, "found again (100 % the same as the map held before)"), tail(log));

        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(1, sim.bot.createdMaps);
        assertTrue(sim.inv.filledMaps.isEmpty(), sim.inv.filledMaps.toString());
    }

    @Test
    void underACoverOverTheWholeArtARestartTakesTheMapInTheMainHand() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = sim.addJob("j0402", "Restarted", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        List<Pos> wool = cover(sim, 0, 0, 128, 4);
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state() + tail(log));

        restart(sim, log, true); // the server gives the bot back its selected slot: the map is still in hand
        assertTrue(sim.runUntil(() -> waiting(sim), 2_000), sim.state() + tail(log));
        assertEquals(1, sim.bot.createdMaps, tail(log));
        assertEquals(1, lines(log, Level.INFO, "found again (the main hand: the tile has a map made and not locked; 0 %"
            + " of the art shows)"), tail(log));

        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(1, sim.bot.createdMaps);
        assertTrue(sim.inv.filledMaps.isEmpty(), sim.inv.filledMaps.toString());
    }

    @Test
    void aMapThatCannotBeFoundAgainIsReplacedOnceAndScrappedAfterStore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        Job job = sim.addJob("j0403", "Lost", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        List<Pos> wool = cover(sim, 0, 0, 128, 4);
        sim.start();
        assertTrue(sim.runUntil(() -> waiting(sim), LONG), sim.state() + tail(log));

        // a restart (the old picture forgotten), the whole art hidden and the hand empty: nothing tells the map apart
        restart(sim, log, false);
        assertTrue(sim.runUntil(() -> sim.bot.createdMaps == 2 && waiting(sim), 2_000), sim.state() + tail(log));
        assertEquals(1, lines(log, Level.WARN, "the map made for this tile is not found among 1 unlocked map"), tail(log));
        sim.run(400);
        assertEquals(2, sim.bot.createdMaps, "not one per poll");
        assertEquals(2, job.tiles.getFirst().unlockedMaps);

        uncover(sim, wool);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        // the first map still shows the cover (a map changes only in hand) like the second one did while it was held:
        // the stale map of the tile, into the scrap map chest after STORE (none configured: the map chest)
        assertTrue(sim.inv.filledMaps.isEmpty(), "no stale map left: " + sim.inv.filledMaps);
        assertEquals(1, sim.output().maps.size());
        assertEquals(1, sim.bot.containers.get(Sim.MAPS).maps.size(), sim.bot.containers.get(Sim.MAPS).maps.toString());
        assertEquals(0, job.tiles.getFirst().unlockedMaps);
        assertTrue(sim.bot.droppedItems.isEmpty(), sim.bot.droppedItems.toString());
    }

    // ---------------------------------------------------------------------------------------------
    // after STORE

    @Test
    void afterStoreNoStaleMapPaneEmptyMapOrXpStaysAndPhisItemsDoNotMove() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.site.storage.scrapMapChests.add(SCRAP);
        sim.bot.addContainer(SCRAP, Map.of());
        sim.config.build.reservedSlots = 12; // nothing crowds a load: everything is put back at STORE
        sim.maps.blind = id -> id == 100; // the first map never gets data: CHECK_MAP makes a fresh one
        // working items left from before, and the owner's own things
        sim.inv.add(PANE, 5);
        sim.inv.add(MAP, 3);
        sim.inv.add(XP, 20);
        sim.inv.add(ROCKET, 59);
        sim.inv.add(SHULKER, 1);
        sim.inv.offhand.put(TOTEM, 1);
        sim.inv.filledMaps.add(8); // the owner's unlocked map of another place
        sim.maps.colors.put(8, elsewhere());
        sim.inv.filledMaps.add(9); // the owner's locked map
        sim.maps.locked.add(9);
        sim.maps.colors.put(9, new byte[128 * 128]);
        sim.inv.filledMaps.add(10); // the owner's own unlocked map of the platform, showing the whole art
        sim.maps.colors.put(10, artPicture());
        int panes = sim.bot.containers.get(Sim.PANES).items.get(PANE);
        int maps = sim.bot.containers.get(Sim.MAPS).items.get(MAP);
        int bottles = sim.bot.containers.get(Sim.XP).items.get(XP);
        Job job = sim.addJob("j0405", "Tidy", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 4));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE || sim.phase() == Phase.COUNT, LONG),
            sim.state() + tail(log));

        // by the end of STORE: the locked map in the output chest, the rest back in their chests
        assertEquals(List.of(8, 9, 10), sim.inv.filledMaps, "only the owner's maps are left");
        assertTrue(Arrays.equals(artPicture(), sim.maps.colors.get(10)), "the owner's map of the platform never held");
        assertEquals(0, sim.inv.count(PANE), "the 4 spare panes went back");
        assertEquals(0, sim.inv.count(MAP), "the 1 spare map went back");
        assertEquals(0, sim.inv.count(XP), "the bottles the rename left went back");
        assertEquals(panes + 4, sim.bot.containers.get(Sim.PANES).items.get(PANE));
        assertEquals(maps + 1, sim.bot.containers.get(Sim.MAPS).items.get(MAP));
        assertEquals(bottles + 12, sim.bot.containers.get(Sim.XP).items.get(XP), "20 less the 8 thrown for the rename");
        List<FakeBot.MapItem> scrap = sim.bot.containers.get(SCRAP).maps;
        assertEquals(1, scrap.size(), scrap.toString());
        assertEquals(100, scrap.getFirst().id(), "the map without data");
        assertFalse(scrap.getFirst().locked());
        assertTrue(sim.bot.containers.get(Sim.MAPS).maps.isEmpty(), "the scrap chest is configured: nothing in the map chest");
        assertEquals(1, sim.output().maps.size());
        // the owner's things never move
        assertEquals(59, sim.inv.count(ROCKET));
        assertEquals(1, sim.inv.count(SHULKER));
        assertEquals(Map.of(TOTEM, 1), sim.inv.offhand);
        assertTrue(Arrays.equals(elsewhere(), sim.maps.colors.get(8)));
        assertTrue(sim.bot.droppedItems.isEmpty(), sim.bot.droppedItems.toString());
        // one chest visit per kind, in STORE, before NUKE; the time it took is logged
        assertEquals(3, sim.bot.count("returnWorkingItems"), sim.bot.history.toString());
        assertEquals(1, lines(log, Level.INFO, "working items put back {minecraft:experience_bottle=12,"
            + " minecraft:filled_map=1, minecraft:glass_pane=4, minecraft:map=1} in "), tail(log));

        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(List.of(8, 9, 10), sim.inv.filledMaps);
        assertEquals(3, sim.bot.count("returnWorkingItems"), "nothing more to put back");
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    @Test
    void mendingStillWorksAfterTheBottlesWentBack() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        for (int i = 0; i < 2; i++) sim.bot.addContainer(new Pos(-90 + 2 * i, 61, 106), Map.of(Sim.TERRACOTTA, 1728));
        sim.inv.items.remove("minecraft:diamond_pickaxe");
        sim.inv.toolStacks.clear();
        FakeTool pick = sim.inv.addTool("minecraft:diamond_pickaxe", 1200, true); // 77 %: no mend due at STORE
        // no XP taken for the nuke: with mending during the clear STORE would take bottles to top the pickaxe up
        sim.config.build.mendDuringClear = false;
        TileArt art = Sim.stripes(0, 5); // 640 terracotta: NUKE wears the pickaxe below the 50 % mend threshold
        Job job = sim.addJob("j0406", "Mend", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.NUKE, LONG), sim.state() + tail(log));
        assertEquals(0, sim.inv.count(XP), "the rename's bottles went back at STORE");
        assertEquals(0, sim.bot.mends);

        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertTrue(sim.bot.mends >= 1, "mended during NUKE with bottles fetched again" + tail(log));
        assertTrue(sim.bot.mendPhases.contains(Phase.NUKE), sim.bot.mendPhases.toString());
        assertTrue(sim.bot.count("withdraw " + Sim.XP) >= 2, "the rename's fetch and the mend's: " + sim.bot.history);
        assertEquals(0, sim.bot.breaksAtFloor);
        assertTrue(sim.bot.lowestToolUses > sim.config.build.toolSafetyFloor, "lowest " + sim.bot.lowestToolUses);
        assertTrue(pick.remaining > 1561 / 2, "mended back up: " + pick);
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.notifier.toString());
    }

    // ---------------------------------------------------------------------------------------------
    // load slots

    @Test
    void bottlesBeyondTheReserveGoBackBeforeTheLoadIsPlanned() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.reservedSlots = 2; // pickaxe and carrots fill the reserve
        sim.inv.add(XP, 16);
        Job job = sim.addJob("j0407", "Slots", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(30, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state() + tail(log));
        assertEquals(0, sim.inv.count(XP), "back in the XP chest before the first load");
        List<String> h = sim.bot.history;
        int back = -1;
        int firstLoad = -1;
        for (int i = 0; i < h.size(); i++) {
            if (back < 0 && h.get(i).startsWith("returnWorkingItems " + Sim.XP)) back = i;
            if (firstLoad < 0 && h.get(i).startsWith("withdraw ") && !h.get(i).startsWith("withdraw " + Sim.FOOD)) firstLoad = i;
        }
        assertTrue(back >= 0 && back < firstLoad, "returned before the load was withdrawn: " + h);
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
    }

    @Test
    void aFullXpChestIsTriedOnceAtRestockAndAgainOnlyAtStore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.config.build.reservedSlots = 2;
        sim.inv.add(XP, 16);
        sim.bot.containers.get(Sim.XP).items.put(XP, 27 * 64); // stocked full (the local test server's XP chest)
        Job job = sim.addJob("j0409", "Full", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(30, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertTrue(lines(log, Level.INFO, "restock:") >= 2, "more than one load" + tail(log));
        assertEquals(2, sim.bot.count("returnWorkingItems " + Sim.XP), "the first RESTOCK and STORE: "
            + sim.bot.history);
        assertEquals(1, lines(log, Level.WARN, "stay in the inventory: their chests took none (full?)"), tail(log));
        assertEquals(8, sim.inv.count(XP), "16 less the 8 thrown for the rename stay, never dropped");
        assertTrue(sim.bot.droppedItems.isEmpty(), sim.bot.droppedItems.toString());
    }

    @Test
    void bottlesWithinTheReserveStayUntilStore() {
        RecordingLogger log = new RecordingLogger();
        Sim sim = sim(log);
        sim.inv.add(XP, 16); // reservedSlots 5: pickaxe, carrots and the bottles fit in it
        Job job = sim.addJob("j0408", "Reserve", 1, 1, List.of("moss"), (x, z) -> Sim.stripes(30, 0));
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state() + tail(log));
        assertEquals(16, sim.inv.count(XP), "no trip to the XP chest for a slot the load cannot use");
        assertEquals(0, sim.bot.count("returnWorkingItems"));
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state() + tail(log));
        assertEquals(0, sim.inv.count(XP), "back at STORE");
    }
}
