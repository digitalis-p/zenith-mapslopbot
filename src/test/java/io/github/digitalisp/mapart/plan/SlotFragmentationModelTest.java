package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.digitalisp.mapart.bot.impl.TopFaceRules;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.TileStatus;
import io.github.digitalisp.mapart.stock.StockIndex;
import io.github.digitalisp.mapart.store.FileJobStore;
import io.github.digitalisp.mapart.testutil.FakeCatalog;
import io.github.digitalisp.mapart.testutil.MossStock;
import io.github.digitalisp.mapart.testutil.RestockModel;
import io.github.digitalisp.mapart.testutil.TileRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Slot fragmentation of a live job and what same-colour substitution can do about it (Track D). Reads a copy of the
 * job's data directory (job.json, tile files, stock.json) from {@code testserver/local/<name>/data} (gitignored: the
 * stock index holds base positions), and is skipped without it:
 * {@code ./gradlew test --offline --tests 'io.github.digitalisp.mapart.plan.SlotFragmentationModelTest' -i}; the tables go to
 * stdout and {@code build/slot-fragmentation-model.md}.
 *
 * <p>Per tile with nothing placed: distinct items, items used fewer than 64 times (a slot each for a few cells), cells
 * placed by a LOOK even in AIRPLACE mode, slots of the whole tile (Σ ceil(n / stack)), per 9-wide lane the items and
 * slots, and the restock model ({@link RestockModel}, today's engine: SPLIT/AUTO planner, top-up, crowding returns, 31
 * material slots, 2 kept for the last load) with trips, chest opens, restock and lane-change seconds. Scenarios: the art
 * as queued; after {@code mapart resubstitute} with {@code map.substituteStocked} on and today's stock; and what-ifs with
 * a block stocked that the base has no chest for. For the brown terracotta what-if, the changed tiles with the most
 * LOOK cells are also built in the lane simulator ({@link TileRunner}, AIRPLACE, silent looks, reach 4.5).
 *
 * <p>Result for ellen (j0002, 2026-10-11, 24 tiles with nothing placed): 52 items in 52 map colours, so no two items of
 * the art can merge; no stocked block shares a colour with any of them, and re-substitution changes nothing (restock
 * model 19,968 s restock + 3,248 s lane changes, unchanged). Slot fragmentation of mixed art is the number of colours,
 * which identical-colour swaps cannot lower. What-if brown terracotta (or dripstone block) stocked: pointed dripstone
 * ×6,126 → brown terracotta on 23 tiles, LOOK cells 6,619 → 493, placing −802 s / −835 s over the 23 tiles in the lane
 * simulator (seeds 7 / 11; about −35 s a tile), restock −37 s; the tiles NUKE anyway, so the cost is about 266 more
 * terracotta breaks a tile.
 */
class SlotFragmentationModelTest {
    private static final Path DATA = Path.of("testserver", "local", "ellen", "data");
    private static final String JOB = "j0002";
    private static final PlatformGeometry GEO = new PlatformGeometry(0, 0, 64);
    private static final int AIRPLACE = 4;
    /** Tiles run in the lane simulator per what-if (the ones with the most LOOK cells). */
    private static final int SIM_TILES = 6;
    private final ZenithBlockCatalog catalog = new ZenithBlockCatalog();

    record TileRow(TileCoord c, int items, int rare, int rareCells, int look, int slots, double laneItems, int laneItemsMax,
                   double laneSlots, int laneSlotsMax, int trips, int chests, double restock, double laneChange) { }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void liveJob() throws IOException {
        assumeTrue(Files.isRegularFile(DATA.resolve("jobs").resolve(JOB).resolve("job.json")),
            "no local copy of the live job in " + DATA);
        FileJobStore store = new FileJobStore(DATA, null, Clock.systemUTC());
        Job job = store.get(JOB).orElseThrow();
        SortedMap<TileCoord, TileArt> tiles = new TreeMap<>();
        for (Job.TileState t : job.tiles) tiles.put(t.coord(), store.loadTile(job.id, t.coord()));
        Set<TileCoord> fresh = new TreeSet<>();
        for (Job.TileState t : job.tiles) if (t.status == TileStatus.PENDING && t.placed <= 0) fresh.add(t.coord());
        ParsedSubmission art = new ParsedSubmission(job.name, job.tilesX, job.tilesZ,
            DefaultSubmissionService.DATA_VERSION_1_21_4, tiles, List.of());
        MapArtConfig.SiteCfg site = MossStock.site();
        StringBuilder sb = new StringBuilder("# Slot fragmentation model (SlotFragmentationModelTest)\n\n");

        // 1. colours: how many items share a map colour (only those can merge)
        Map<String, Integer> jobItems = new TreeMap<>();
        Map<Integer, Set<String>> byColour = new TreeMap<>();
        for (TileArt t : tiles.values())
            t.blockCounts().forEach((k, v) -> {
                BlockSpec s = BlockSpec.parse(k);
                String item = catalog.placementItem(s).orElse(s.id());
                jobItems.merge(item, v, Integer::sum);
                byColour.computeIfAbsent(catalog.mapColorId(s), x -> new TreeSet<>()).add(item);
            });
        long shared = byColour.values().stream().filter(s -> s.size() > 1).count();
        sb.append(String.format(Locale.ROOT, "Job %s (%s): %d tiles, %d with nothing placed; %d items in %d map colors, "
            + "%d color(s) shown by more than one item.%n%n", job.id, job.name, tiles.size(), fresh.size(), jobItems.size(),
            byColour.size(), shared));

        // 2. stocked same-colour stand-ins per item (today's stock)
        StockIndex stock = store.stock();
        Map<Integer, List<SameColourSwap.Candidate>> cands;
        synchronized (stock) {
            cands = SameColourSwap.candidates(catalog, site, stock, Set.of());
        }
        int withStandIn = 0;
        for (var e : jobItems.entrySet()) {
            int colour = catalog.mapColorId(BlockSpec.of(e.getKey()));
            for (SameColourSwap.Candidate c : cands.getOrDefault(colour, List.of()))
                if (!c.id().equals(e.getKey())) {
                    withStandIn++;
                    sb.append("- ").append(e.getKey()).append(" ×").append(e.getValue()).append(": stocked stand-in ")
                        .append(c.id()).append('\n');
                    break;
                }
        }
        sb.append(withStandIn).append(" of ").append(jobItems.size()).append(" items have a stocked block of the same map "
            + "color.\n\n");

        // 3. scenarios
        FakeCatalog fake = new FakeCatalog();
        Map<String, Map<TileCoord, TileRow>> scenarios = new LinkedHashMap<>();
        Map<TileCoord, TileRow> today = rows(art, fresh, fake, null);
        String placing = "";
        scenarios.put("as queued", today);
        SameColourSwap.Options stocked = new SameColourSwap.Options(true, fresh::contains);
        SameColourSwap.Result now = SameColourSwap.apply(art, catalog, site, stock, List.of(), stocked);
        sb.append("Re-substitution with today's stock (substituteStocked on): ")
            .append(now.changedTiles(art).size()).append(" tile(s) change; ")
            .append(Objects.requireNonNullElse(SameColourSwap.line(now.substitutions(), 600), "no swaps")).append("\n\n");
        scenarios.put("re-substituted, today's stock", rows(now.submission(), fresh, fake, today));
        for (String extra : List.of("minecraft:brown_terracotta", "minecraft:dripstone_block")) {
            StockIndex more = copy(stock);
            more.update(new Pos(-999, 0, -999), Map.of(extra, 10_000), 0, 27, 1);
            SameColourSwap.Result r = SameColourSwap.apply(art, catalog, site, more, List.of(), stocked);
            sb.append("What-if ").append(extra).append(" stocked: ").append(r.changedTiles(art).size())
                .append(" tile(s) change; ").append(Objects.requireNonNullElse(SameColourSwap.line(r.substitutions(), 600),
                    "no swaps")).append("\n\n");
            scenarios.put("what-if " + extra.substring("minecraft:".length()) + " stocked",
                rows(r.submission(), fresh, fake, today));
            if (extra.equals("minecraft:brown_terracotta")) placing = placing(art, r, fresh);
        }
        scenarios.forEach((name, m) -> sb.append(table(name, m)).append('\n'));
        sb.append(placing);
        System.out.println(sb);
        Path out = Path.of("build", "slot-fragmentation-model.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        assertEquals(fresh.size(), today.size());
    }

    /**
     * Placement in the lane simulator ({@link TileRunner}: LaneRun on the simulated platform, AIRPLACE with the live
     * look settings, up to 4 repair passes) of the changed tiles with the most LOOK cells, before and after.
     */
    private String placing(ParsedSubmission art, SameColourSwap.Result r, Set<TileCoord> fresh) {
        List<TileCoord> changed = new ArrayList<>(r.changedTiles(art));
        changed.retainAll(fresh);
        changed.sort(Comparator.comparingInt((TileCoord c) -> -lookCells(art.tiles().get(c))));
        StringBuilder sb = new StringBuilder("### placing in the lane simulator, what-if brown_terracotta stocked\n\n"
            + "| Tile | LOOK cells before → after | Ticks before | Ticks after | Δ s | Stand-spot lanes before → after |\n"
            + "|---|---:|---:|---:|---:|---:|\n");
        long before = 0, after = 0;
        for (TileCoord c : changed.subList(0, Math.min(SIM_TILES, changed.size()))) {
            TileRunner a = simulate(art.tiles().get(c));
            TileRunner b = simulate(r.submission().tiles().get(c));
            before += a.ticks;
            after += b.ticks;
            sb.append(String.format(Locale.ROOT, "| %s | %,d → %,d | %,d | %,d | %+.0f | %d → %d |%n", c,
                lookCells(art.tiles().get(c)), lookCells(r.submission().tiles().get(c)), a.ticks, b.ticks,
                (b.ticks - a.ticks) / 20.0, a.spotLanes(), b.spotLanes()));
        }
        sb.append(String.format(Locale.ROOT, "| **sum** | | %,d | %,d | %+.0f | |%n%n", before, after, (after - before) / 20.0));
        return sb.toString();
    }

    private static TileRunner simulate(TileArt t) {
        TileRunner r = new TileRunner(7);
        r.build.placeMode = "AIRPLACE";
        r.build.rotatePlayer = false;
        r.build.grimRotate = true;
        r.build.reach = 4.5;
        r.build.airPlaceRange = 5.0;
        r.build(t, 4);
        return r;
    }

    private static int lookCells(TileArt t) {
        int n = 0;
        for (int z = 0; z < TileArt.SIZE; z++)
            for (int x = 0; x < TileArt.SIZE; x++) {
                BlockSpec b = t.get(x, z);
                if (b != null && TopFaceRules.airPlaceProblem(b) != null) n++;
            }
        return n;
    }

    private static StockIndex copy(StockIndex s) {
        StockIndex c = new StockIndex();
        synchronized (s) {
            s.containers.forEach((k, v) -> c.containers.put(k, v));
            if (s.seen != null) c.seen.addAll(s.seen);
        }
        return c;
    }

    /** Per fresh tile; a tile equal to {@code base}'s is not modelled again. */
    private Map<TileCoord, TileRow> rows(ParsedSubmission art, Set<TileCoord> fresh, FakeCatalog fake,
                                         Map<TileCoord, TileRow> base) {
        Map<TileCoord, TileRow> out = new TreeMap<>();
        for (TileCoord c : fresh) {
            TileArt t = art.tiles().get(c);
            Map<String, Integer> counts = new TreeMap<>();
            int look = 0;
            for (int z = 0; z < TileArt.SIZE; z++)
                for (int x = 0; x < TileArt.SIZE; x++) {
                    BlockSpec b = t.get(x, z);
                    if (b == null) continue;
                    String item = catalog.placementItem(b).orElse(b.id());
                    counts.merge(item, 1, Integer::sum);
                    if (TopFaceRules.airPlaceProblem(b) != null) look++;
                    fake.known.add(b.id());
                    fake.stackSize.put(item, catalog.maxStackSize(item));
                }
            TileRow prev = base == null ? null : base.get(c);
            if (prev != null && prev.items() == counts.size() && prev.look() == look && prev.slots() == slots(counts)) {
                out.put(c, prev);
                continue;
            }
            int rare = 0, rareCells = 0;
            for (int n : counts.values()) if (n < 64) {
                rare++;
                rareCells += n;
            }
            List<Lane> lanes = new SerpentineBuildPlanner().plan(t, GEO, catalog, AIRPLACE, p -> false);
            double items = 0, slots = 0;
            int itemsMax = 0, slotsMax = 0;
            for (Lane l : lanes) {
                Map<String, Integer> lc = new HashMap<>();
                for (Placement p : l.placements()) lc.merge(p.itemId(), 1, Integer::sum);
                items += lc.size();
                slots += slots(lc);
                itemsMax = Math.max(itemsMax, lc.size());
                slotsMax = Math.max(slotsMax, slots(lc));
            }
            RestockModel.Result r = new RestockModel(t, AIRPLACE, fake).run(new RestockModel.Config("today",
                () -> new ConfiguredRestockPlanner(() -> "AUTO"), 31, 2, true, RestockModel.Sweep.END_AWARE, true,
                RestockModel.Withdraw.STACKS));
            out.put(c, new TileRow(c, counts.size(), rare, rareCells, look, slots(counts), items / lanes.size(), itemsMax,
                slots / lanes.size(), slotsMax, r.trips(), r.chests, r.restockSeconds(), r.laneChangeSeconds));
        }
        return out;
    }

    private static int slots(Map<String, Integer> counts) {
        int s = 0;
        for (int n : counts.values()) s += (n + 63) / 64;
        return s;
    }

    private static String table(String name, Map<TileCoord, TileRow> rows) {
        StringBuilder sb = new StringBuilder("### " + name + "\n\n");
        sb.append("| Tile | Items | Items < 64 (cells) | LOOK cells | Slots | Items per lane mean / max | Slots per lane "
            + "mean / max | Trips | Chests | Restock s | Lane-change s |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        int items = 0, rare = 0, look = 0, trips = 0, chests = 0;
        double restock = 0, lane = 0;
        for (TileRow r : rows.values()) {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %d (%d) | %,d | %d | %.1f / %d | %.1f / %d | %d | %d | %.0f | %.0f |%n",
                r.c(), r.items(), r.rare(), r.rareCells(), r.look(), r.slots(), r.laneItems(), r.laneItemsMax(),
                r.laneSlots(), r.laneSlotsMax(), r.trips(), r.chests(), r.restock(), r.laneChange()));
            items += r.items();
            rare += r.rare();
            look += r.look();
            trips += r.trips();
            chests += r.chests();
            restock += r.restock();
            lane += r.laneChange();
        }
        sb.append(String.format(Locale.ROOT, "| **%d tiles** | %.1f mean | %.1f mean | %,d | | | | %d | %d | %,.0f | %,.0f |%n",
            rows.size(), (double) items / rows.size(), (double) rare / rows.size(), look, trips, chests, restock, lane));
        return sb.toString();
    }
}
