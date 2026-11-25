package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.bot.impl.ItemIds;
import io.github.digitalisp.mapart.bot.impl.TopFaceRules;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.catalog.Eyeblossoms;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.stock.StockIndex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Same-colour substitution ({@code map.substituteSameColour} at upload, {@code mapart resubstitute} for a queued job's
 * tiles with nothing placed). A block state of the art is swapped for a stocked block (a material chest has held it)
 * that shows the identical 1.21.4 map colour id when
 * <ul>
 *   <li>its material has no chest at this base ({@link StockIndex#everHeld} false; "no chest"), or</li>
 *   <li>its map colour follows the time of day ({@link Eyeblossoms}; "time of day"), or</li>
 *   <li>with {@link Options#stocked} ({@code map.substituteStocked}), it is stocked but a stocked block of the same
 *       colour is better for its tile: one easier to build (placed without a LOOK, or piston-cleared where the block
 *       is the tile's only nuke block), or one the tile already uses more that is no harder to build (the tile and its
 *       lanes need one item fewer: a slot and a chest visit less per load). Reason "fewer items" when the stand-in is
 *       in the tile already, else "easier to build".</li>
 * </ul>
 * A flat map shades a pixel by the Y of its top block only (vanilla MapItem: the column height, never the shape), so a
 * carpet and a full block on the same art layer give the same map byte: every swap keeps the map identical.
 *
 * <p>A stand-in must be known to the registry, placed by its own item, buildable by a top-face click, not protected,
 * not an eyeblossom, and clearable (piston-clearable or nukeable) on every enabled platform. Choices are made per tile,
 * in passes until nothing changes, states with the most cells first. Preference, in order: the lower build cost (a
 * nuke block costs nothing extra in a tile the NUKE clears anyway, else piston-clearable first; then placed without a
 * LOOK, {@link TopFaceRules#airPlaceProblem}), the block the tile already uses most (no new item for its loads),
 * piston-clearable (no breaking, no tool wear), carpets, stock covering what the job needs of it, the most stock, then
 * the id (deterministic). An eyeblossom with no stand-in is kept and recorded with {@code to}
 * null; the engine then fills its map when the world shows the wanted state. A block with no chest and no stand-in is
 * left alone (the upload card marks it ✗). Nothing is swapped before the stock has been scanned. Pure apart from
 * reading the stock index (under its lock).
 */
public final class SameColourSwap {
    private SameColourSwap() { }

    public static final String NO_CHEST = "no chest";
    public static final String TIME_OF_DAY = "time of day";
    public static final String FEWER_ITEMS = "fewer items";
    public static final String EASIER = "easier to build";
    static final String HEAD = "Swapped for identical colors: ";
    static final String NONE = "(none stocked)";
    private static final Set<String> PLATFORM_KINDS = Set.of("MOSS", "SOUL_SAND");

    /**
     * @param stocked also swap stocked blocks when a stocked block of the same colour is better for the tile
     *                ({@code map.substituteStocked})
     * @param tiles   the tiles that may change; the others are copied as they are
     */
    public record Options(boolean stocked, Predicate<TileCoord> tiles) {
        /** Unstocked blocks and eyeblossoms only, every tile ({@code map.substituteStocked} off). */
        public static final Options UPLOAD = new Options(false, c -> true);

        public Options {
            if (tiles == null) tiles = c -> true;
        }
    }

    /**
     * @param submission    the art with every swap applied (the input itself when nothing was swapped; a tile without
     *                      swaps is the input's own instance)
     * @param substitutions every swap, and every eyeblossom state kept for want of a stand-in, by original state, then
     *                      stand-in, then reason
     */
    public record Result(ParsedSubmission submission, List<Job.Substitution> substitutions) {
        /** Tiles whose art changed. */
        public Set<TileCoord> changedTiles(ParsedSubmission original) {
            Set<TileCoord> out = new TreeSet<>();
            if (original == null || submission == null) return out;
            submission.tiles().forEach((c, t) -> {
                if (t != original.tiles().get(c)) out.add(c);
            });
            return out;
        }
    }

    /** A stocked block that can stand in for any art block of the same map colour. */
    record Candidate(String id, int mapColor, boolean pistonClearable, boolean carpet, int stock) { }

    public static Result apply(ParsedSubmission sub, BlockCatalog catalog, MapArtConfig.SiteCfg site, StockIndex stock,
                               Collection<String> deny) {
        return apply(sub, catalog, site, stock, deny, Options.UPLOAD);
    }

    public static Result apply(ParsedSubmission sub, BlockCatalog catalog, MapArtConfig.SiteCfg site, StockIndex stock,
                               Collection<String> deny, Options options) {
        if (sub == null || stock == null || catalog == null) return new Result(sub, List.of());
        Options opt = options == null ? Options.UPLOAD : options;
        Set<String> denied = new HashSet<>();
        if (deny != null) for (String d : deny) if (d != null && !d.isBlank()) denied.add(normalizeId(d));

        Map<TileCoord, Map<BlockSpec, BlockSpec>> replace = new TreeMap<>();
        Map<Way, int[]> ways = new TreeMap<>();
        synchronized (stock) {
            if (stock.containers.isEmpty()) return new Result(sub, List.of()); // nothing known about the stock yet
            Map<Integer, List<Candidate>> byColour = candidates(catalog, site, stock, denied);
            Clearing clearing = Clearing.of(site);
            // what the whole job uses of each item, kept up to date as swaps are made (for "stock covers the need")
            Map<String, Integer> jobUse = new HashMap<>();
            for (TileArt t : sub.tiles().values())
                counts(t).forEach((s, n) -> jobUse.merge(item(catalog, s), n, Integer::sum));
            for (var te : sub.tiles().entrySet()) {
                if (!opt.tiles().test(te.getKey())) continue;
                Map<BlockSpec, Integer> orig = counts(te.getValue());
                // original state → what it is built as, and why; passes until nothing changes (a swap can make a
                // block the tile's only nuke block, which a later pass may then replace)
                Map<BlockSpec, BlockSpec> fin = new HashMap<>();
                Map<BlockSpec, String> why = new HashMap<>();
                for (BlockSpec s : orig.keySet()) fin.put(s, s);
                for (int pass = 0; pass < MAX_PASSES; pass++) {
                    Map<BlockSpec, Integer> cur = new TreeMap<>(Comparator.comparing(BlockSpec::toString));
                    orig.forEach((s, n) -> cur.merge(fin.get(s), n, Integer::sum));
                    Map<BlockSpec, Step> steps = new TileChoice(catalog, stock, opt, denied, byColour, clearing, jobUse,
                        cur).choose(pass == 0 ? ways : null);
                    if (steps.isEmpty()) break;
                    for (BlockSpec s : orig.keySet()) {
                        Step st = steps.get(fin.get(s));
                        if (st == null) continue;
                        fin.put(s, st.to());
                        why.merge(s, st.reason(), (was, now) -> was.equals(NO_CHEST) || was.equals(TIME_OF_DAY) ? was : now);
                    }
                }
                Map<BlockSpec, BlockSpec> r = new HashMap<>();
                for (var e : orig.entrySet()) {
                    BlockSpec s = e.getKey();
                    BlockSpec to = fin.get(s);
                    if (to.equals(s)) continue;
                    r.put(s, to);
                    ways.computeIfAbsent(new Way(s.toString(), to.toString(), why.get(s)),
                        k -> new int[]{0, catalog.mapColorId(s)})[0] += e.getValue();
                }
                if (!r.isEmpty()) replace.put(te.getKey(), r);
            }
        }
        List<Job.Substitution> out = new ArrayList<>();
        ways.forEach((w, n) -> out.add(new Job.Substitution(w.from(), w.to(), n[0], n[1], w.reason())));
        if (replace.isEmpty()) return new Result(sub, List.copyOf(out));

        SortedMap<TileCoord, TileArt> tiles = new TreeMap<>();
        for (var e : sub.tiles().entrySet()) {
            Map<BlockSpec, BlockSpec> r = replace.get(e.getKey());
            if (r == null) {
                tiles.put(e.getKey(), e.getValue());
                continue;
            }
            TileArt from = e.getValue();
            TileArt to = new TileArt();
            for (int pz = 0; pz < TileArt.SIZE; pz++)
                for (int px = 0; px < TileArt.SIZE; px++) {
                    BlockSpec b = from.get(px, pz);
                    if (b != null) to.set(px, pz, r.getOrDefault(b, b));
                }
            tiles.put(e.getKey(), to);
        }
        ParsedSubmission swapped = new ParsedSubmission(sub.baseName(), sub.tilesX(), sub.tilesZ(), sub.dataVersion(),
            tiles, sub.warnings());
        return new Result(swapped, List.copyOf(out));
    }

    /** Passes over one tile at most (each pass that changes something lowers its items or build cost). */
    private static final int MAX_PASSES = 8;

    /** One swap of a pass: the stand-in and why. */
    private record Step(BlockSpec to, String reason) { }

    /** One line of {@link Result#substitutions}: original state, stand-in (null = kept), reason. */
    private record Way(String from, String to, String reason) implements Comparable<Way> {
        @Override
        public int compareTo(Way o) {
            int c = from.compareTo(o.from);
            if (c != 0) return c;
            c = Comparator.<String>nullsLast(Comparator.naturalOrder()).compare(to, o.to);
            return c != 0 ? c : reason.compareTo(o.reason);
        }
    }

    /** Which blocks the enabled platforms clear with pistons, and which at all. */
    private record Clearing(List<Set<String>> piston, List<Set<String>> nuke) {
        static Clearing of(MapArtConfig.SiteCfg site) {
            List<Set<String>> p = new ArrayList<>();
            List<Set<String>> n = new ArrayList<>();
            for (MapArtConfig.PlatformCfg pc : enabledPlatforms(site)) {
                p.add(normalized(pc.clearableBlocks));
                n.add(normalized(pc.nukeBlocks));
            }
            return new Clearing(p, n);
        }

        /** Piston-clearable on every enabled platform (false with none). */
        boolean piston(String id) {
            if (piston.isEmpty()) return false;
            for (Set<String> s : piston) if (!s.contains(id)) return false;
            return true;
        }
    }

    /** Build cost of a block in its tile: 1 when it forces a NUKE the tile would not need, 1 when it needs a LOOK. */
    private record Cost(int nuke, int look) {
        boolean notWorse(Cost o) {
            return nuke <= o.nuke && look <= o.look;
        }

        boolean better(Cost o) {
            return notWorse(o) && (nuke < o.nuke || look < o.look);
        }
    }

    /** The swaps of one tile, decided state by state, most cells first. */
    private static final class TileChoice {
        final BlockCatalog catalog;
        final StockIndex stock;
        final Options opt;
        final Set<String> denied;
        final Map<Integer, List<Candidate>> byColour;
        final Clearing clearing;
        final Map<String, Integer> jobUse;
        final Map<BlockSpec, Integer> counts;
        final Map<String, Integer> totals;
        /** Cells per item in the tile as it stands after the swaps so far. */
        final Map<String, Integer> use = new HashMap<>();
        /** Cells of blocks only a NUKE clears, after the swaps so far. */
        int nukeCells;

        TileChoice(BlockCatalog catalog, StockIndex stock, Options opt, Set<String> denied,
                   Map<Integer, List<Candidate>> byColour, Clearing clearing, Map<String, Integer> jobUse,
                   Map<BlockSpec, Integer> counts) {
            this.catalog = catalog;
            this.stock = stock;
            this.opt = opt;
            this.denied = denied;
            this.byColour = byColour;
            this.clearing = clearing;
            this.jobUse = jobUse;
            this.counts = counts;
            this.totals = stock.totals();
            counts.forEach((s, n) -> {
                use.merge(item(catalog, s), n, Integer::sum);
                if (!clearing.piston(ItemIds.ns(s.id()))) nukeCells += n;
            });
        }

        /**
         * One pass over the tile, most cells first: state → its stand-in and reason. Eyeblossom states kept for want of
         * a stand-in are recorded in {@code kept} (null: not recorded).
         */
        Map<BlockSpec, Step> choose(Map<Way, int[]> kept) {
            Map<BlockSpec, Step> out = new HashMap<>();
            List<Map.Entry<BlockSpec, Integer>> order = new ArrayList<>(counts.entrySet());
            order.sort(Comparator.comparing((Map.Entry<BlockSpec, Integer> e) -> -e.getValue())
                .thenComparing(e -> e.getKey().toString()));
            for (var e : order) {
                BlockSpec s = e.getKey();
                int n = e.getValue();
                if (denied.contains(s.id()) || !catalog.isKnownBlock(s.id())) continue;
                Optional<String> placedBy = catalog.placementItem(s);
                if (placedBy.isPresent() && denied.contains(ItemIds.ns(placedBy.get()))) continue;
                int colour = catalog.mapColorId(s);
                if (colour <= 0) continue;
                boolean timeOfDay = Eyeblossoms.is(s);
                boolean noChest = placedBy.isPresent() && !stock.everHeld(placedBy.get());
                String material = placedBy.map(ItemIds::material).orElse(null);
                String item = item(catalog, s);
                boolean sNuke = !clearing.piston(ItemIds.ns(s.id()));
                int otherNuke = nukeCells - (sNuke ? n : 0);
                List<Candidate> cands = new ArrayList<>();
                for (Candidate c : byColour.getOrDefault(colour, List.of()))
                    if (!c.id().equals(material) && !c.id().equals(s.id()) && !c.id().equals(item)) cands.add(c);
                Comparator<Candidate> rank = rank(n, otherNuke);
                if (timeOfDay || noChest) {
                    Candidate best = cands.stream().min(rank).orElse(null);
                    String reason = timeOfDay ? TIME_OF_DAY : NO_CHEST;
                    if (best != null) swap(s, n, item, sNuke, best, reason, out);
                    else if (timeOfDay && kept != null)
                        kept.computeIfAbsent(new Way(s.toString(), null, reason), k -> new int[]{0, colour})[0] += n;
                    continue;
                }
                if (!opt.stocked()) continue;
                // a stocked block goes to a block easier to build, or to one the tile uses more that is no harder
                Cost own = new Cost(sNuke && otherNuke == 0 ? 1 : 0, needsLook(s) ? 1 : 0);
                int ownUse = use.getOrDefault(item, 0);
                Candidate best = null;
                for (Candidate c : cands) {
                    Cost cost = cost(c, otherNuke);
                    int u = use.getOrDefault(c.id(), 0);
                    boolean merge = u > 0 && (u > ownUse || (u == ownUse && c.id().compareTo(item) < 0)) && cost.notWorse(own);
                    if ((merge || cost.better(own)) && (best == null || rank.compare(c, best) < 0)) best = c;
                }
                if (best != null)
                    swap(s, n, item, sNuke, best, use.getOrDefault(best.id(), 0) > 0 ? FEWER_ITEMS : EASIER, out);
            }
            return out;
        }

        private void swap(BlockSpec s, int n, String item, boolean sNuke, Candidate to, String reason,
                          Map<BlockSpec, Step> out) {
            out.put(s, new Step(BlockSpec.of(to.id()), reason));
            move(use, item, to.id(), n);
            move(jobUse, item, to.id(), n);
            if (sNuke) nukeCells -= n;
            if (!to.pistonClearable()) nukeCells += n;
        }

        private static void move(Map<String, Integer> m, String from, String to, int n) {
            int left = m.getOrDefault(from, 0) - n;
            if (left > 0) m.put(from, left);
            else m.remove(from);
            m.merge(to, n, Integer::sum);
        }

        /**
         * Placed by a LOOK even in AIRPLACE mode (multiface blocks, pointed dripstone, lanterns, …): a rotation, line
         * of sight and the shorter LOOK reach in an airplace lane. Counted in LOOK mode as well (state from the look).
         */
        private static boolean needsLook(BlockSpec spec) {
            return TopFaceRules.airPlaceProblem(spec) != null;
        }

        private Cost cost(Candidate c, int otherNuke) {
            return new Cost(!c.pistonClearable() && otherNuke == 0 ? 1 : 0, needsLook(BlockSpec.of(c.id())) ? 1 : 0);
        }

        /** Best first: lower build cost, most used by the tile, piston, carpet, stock covering the need, stock, id. */
        private Comparator<Candidate> rank(int n, int otherNuke) {
            return Comparator
                .comparingInt((Candidate c) -> cost(c, otherNuke).nuke())
                .thenComparingInt(c -> cost(c, otherNuke).look())
                .thenComparingInt(c -> -use.getOrDefault(c.id(), 0))
                .thenComparing(c -> !c.pistonClearable())
                .thenComparing(c -> !c.carpet())
                .thenComparing(c -> totals.getOrDefault(c.id(), 0) < jobUse.getOrDefault(c.id(), 0) + n)
                .thenComparing(Comparator.comparingInt(Candidate::stock).reversed())
                .thenComparing(Candidate::id);
        }
    }

    private static Map<BlockSpec, Integer> counts(TileArt t) {
        Map<BlockSpec, Integer> counts = new TreeMap<>(Comparator.comparing(BlockSpec::toString));
        for (int pz = 0; pz < TileArt.SIZE; pz++)
            for (int px = 0; px < TileArt.SIZE; px++) {
                BlockSpec s = t.get(px, pz);
                if (s != null) counts.merge(s, 1, Integer::sum);
            }
        return counts;
    }

    /** The material that places {@code s} (as stock and loads count it), or its block id. */
    private static String item(BlockCatalog catalog, BlockSpec s) {
        return catalog.placementItem(s).map(ItemIds::material).map(ItemIds::ns).orElse(ItemIds.ns(s.id()));
    }

    private static List<MapArtConfig.PlatformCfg> enabledPlatforms(MapArtConfig.SiteCfg site) {
        List<MapArtConfig.PlatformCfg> platforms = new ArrayList<>();
        if (site != null && site.platforms != null)
            for (MapArtConfig.PlatformCfg p : site.platforms)
                if (p != null && p.enabled && p.kind != null && PLATFORM_KINDS.contains(p.kind.trim().toUpperCase(Locale.ROOT)))
                    platforms.add(p);
        return platforms;
    }

    /** Stocked stand-ins by map colour id. Caller holds the stock lock. */
    static Map<Integer, List<Candidate>> candidates(BlockCatalog catalog, MapArtConfig.SiteCfg site, StockIndex stock,
                                                    Set<String> denied) {
        List<MapArtConfig.PlatformCfg> platforms = enabledPlatforms(site);
        Map<Integer, List<Candidate>> out = new HashMap<>();
        if (platforms.isEmpty()) return out; // nothing could clear a stand-in
        List<Set<String>> pistonSets = new ArrayList<>();
        List<Set<String>> nukeSets = new ArrayList<>();
        for (MapArtConfig.PlatformCfg p : platforms) {
            pistonSets.add(normalized(p.clearableBlocks));
            nukeSets.add(normalized(p.nukeBlocks));
        }
        Set<String> isProtected = normalized(site.protectedBlocks);

        Set<String> ids = new TreeSet<>(stock.totals().keySet());
        if (stock.seen != null) ids.addAll(stock.seen);
        Map<String, Integer> totals = stock.totals();
        Set<String> done = new HashSet<>();
        for (String raw : ids) {
            String id = ItemIds.ns(raw);
            if (!done.add(id) || !stock.everHeld(raw)) continue;
            if (denied.contains(id) || isProtected.contains(id) || Eyeblossoms.is(id)) continue;
            if (!catalog.isKnownBlock(id)) continue;
            BlockSpec spec = BlockSpec.of(id);
            if (!catalog.placementItem(spec).equals(Optional.of(id))) continue; // its item must place this block
            if (!catalog.placeableOnTopFace(spec)) continue;
            int colour = catalog.mapColorId(spec);
            if (colour <= 0) continue;
            boolean piston = true;
            boolean clearable = true;
            for (int i = 0; i < platforms.size(); i++) {
                boolean p = pistonSets.get(i).contains(id);
                piston &= p;
                clearable &= p || nukeSets.get(i).contains(id);
            }
            if (!clearable) continue;
            int have = totals.getOrDefault(raw, 0);
            out.computeIfAbsent(colour, k -> new ArrayList<>())
                .add(new Candidate(id, colour, piston, id.endsWith("_carpet"), have));
        }
        out.values().forEach(l -> l.sort(Comparator.comparing(Candidate::id)));
        return out;
    }

    /**
     * "Swapped for identical colours: closed_eyeblossom ×1,200 → (none stocked), decorated_pot ×235 → red_terracotta"
     * (ids without the {@code minecraft:} namespace; states of one block that went the same way are summed), ending
     * with "and N more" when it would pass {@code maxChars}; null when there is nothing to list.
     */
    public static String line(List<Job.Substitution> subs, int maxChars) {
        return line(HEAD, subs, maxChars);
    }

    /** {@link #line(List, int)} with another lead-in (e.g. for a re-substitution). */
    public static String line(String head, List<Job.Substitution> subs, int maxChars) {
        if (subs == null || subs.isEmpty()) return null;
        record Pair(String from, String to) { }
        Map<Pair, Integer> grouped = new LinkedHashMap<>();
        for (Job.Substitution s : subs) {
            if (s == null || s.from == null) continue;
            String from = shortId(BlockSpec.parse(s.from).id());
            String to = s.to == null ? NONE : shortId(BlockSpec.parse(s.to).id());
            grouped.merge(new Pair(from, to), s.count, Integer::sum);
        }
        if (grouped.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        grouped.forEach((w, n) -> parts.add(w.from() + " ×" + String.format(Locale.ROOT, "%,d", n) + " → " + w.to()));
        StringBuilder sb = new StringBuilder(head);
        for (int i = 0; i < parts.size(); i++) {
            String part = (i > 0 ? ", " : "") + parts.get(i);
            int after = parts.size() - i - 1;
            int reserve = after == 0 ? 0 : (" and " + after + " more").length();
            if (i > 0 && sb.length() + part.length() + reserve > maxChars) {
                sb.append(" and ").append(parts.size() - i).append(" more");
                break;
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static Set<String> normalized(Collection<String> ids) {
        Set<String> s = new HashSet<>();
        if (ids != null) for (String id : ids) if (id != null && !id.isBlank()) s.add(normalizeId(id));
        return s;
    }

    private static String normalizeId(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        int b = s.indexOf('[');
        if (b >= 0) s = s.substring(0, b);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
