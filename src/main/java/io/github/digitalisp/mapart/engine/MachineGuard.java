package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg;
import io.github.digitalisp.mapart.model.Pos;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Guard for the redstone flying machines of a colour-suppression platform: they must come back to the same place after
 * every minecart ride. A reference snapshot of the machine blocks is recorded once; before and after every ride a fresh
 * reading is compared with it, and any real difference means a machine broke. Pure.
 * <p>
 * Everything is chunk-granular, because the bot's client only holds the chunks near it: a chunk column is read only
 * while it is loaded, several viewpoints together cover all chunks ({@link #merge}), and {@link #compare} compares the
 * chunks both snapshots hold. Reading is incremental ({@link Scan#step}) since it runs on the game tick thread.
 * <p>
 * The guarded cells are the boxes minus the excluded cells. Inside a chunk column they are ordered from the boxes alone:
 * for each box in list order, y, then z, then x ascending, keeping the cells of that chunk not already taken by an
 * earlier box. A snapshot stores one palette index per such cell (excluded cells hold a marker), so a reference and a
 * reading recorded at different times line up, and positions are recomputed from the boxes instead of being stored.
 * The boxes' bounds are fingerprinted ({@link #layoutOf}); snapshots of different boxes are never compared or merged.
 */
public final class MachineGuard {
    /** Binary format version written by {@link #write}; {@link #read} rejects any other. */
    static final int FORMAT_VERSION = 1;
    /** Most changes kept in {@link Diff#sample()}. */
    static final int SAMPLE = 40;
    /** Longest {@link Diff#summary()}. */
    static final int SUMMARY_MAX = 400;
    private static final int MAGIC = 0x4d475244; // "MGRD"
    private static final short EXCLUDED = -1;
    private static final int MAX_PALETTE = Short.MAX_VALUE;
    private static final int MAX_CHUNK_CELLS = 1 << 22;
    private static final int KINDS_SHOWN = 4, BOXES_SHOWN = 3;
    /** Chunk columns by cx, then cz: the scan order. */
    private static final Comparator<Long> CHUNK_ORDER = (a, b) -> {
        int c = Integer.compare(cx(a), cx(b));
        return c != 0 ? c : Integer.compare(cz(a), cz(b));
    };

    private MachineGuard() { }

    /** The world as the bot's client sees it. */
    public interface Blocks {
        /** True when the chunk column holding {@code x, z} is loaded. */
        boolean loaded(int x, int y, int z);

        /** Block id at a cell, with or without "minecraft:" and with or without "[states]"; null counts as not loaded. */
        String id(int x, int y, int z);
    }

    /** Key of chunk column {@code cx, cz}: {@code ((long) cx << 32) | (cz & 0xffffffffL)}. */
    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private static int cx(long key) {
        return (int) (key >> 32);
    }

    private static int cz(long key) {
        return (int) key;
    }

    /**
     * Normalised id used for every comparison: no "minecraft:", no "[states]", lower case; wax and oxidation stripped
     * ("waxed_weathered_cut_copper" is "cut_copper", "exposed_copper" is "copper_block"); snow layers, fire, cave and
     * void air are "air", a bubble column is "water", a worn anvil is "anvil". Piston heads and moving pistons stay: a machine caught mid-move
     * is a real difference. Null is "air".
     */
    public static String normalise(String id) {
        if (id == null) return "air";
        String s = id.trim();
        int b = s.indexOf('[');
        if (b >= 0) s = s.substring(0, b);
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
        if (s.startsWith("waxed_")) s = s.substring("waxed_".length());
        for (String p : new String[]{"exposed_", "weathered_", "oxidized_"})
            if (s.startsWith(p)) {
                s = s.substring(p.length());
                break;
            }
        return switch (s) {
            case "copper" -> "copper_block"; // the oxidised full blocks are "exposed_copper" etc., the base "copper_block"
            case "snow", "fire", "soul_fire", "cave_air", "void_air" -> "air";
            case "bubble_column" -> "water";
            // the booth's anvil wears with every name (local run); once it breaks its cell is left out by the engine
            case "chipped_anvil", "damaged_anvil" -> "anvil";
            default -> s;
        };
    }

    /** Fingerprint of the boxes' bounds in list order (names do not count): snapshots line up only when it matches. */
    public static long layoutOf(List<RegionCfg> boxes) {
        int[][] nb = bounds(boxes);
        long h = 0xcbf29ce484222325L;
        h = mix(h, nb.length);
        for (int[] b : nb) {
            h = mix(h, b == null ? 0 : 1);
            if (b != null) for (int v : b) h = mix(h, v);
        }
        return h;
    }

    private static long mix(long h, int v) {
        for (int i = 0; i < 4; i++) {
            h ^= (v >>> (8 * i)) & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Per box {minX, minY, minZ, maxX, maxY, maxZ}, or null for a box without both corners. */
    private static int[][] bounds(List<RegionCfg> boxes) {
        if (boxes == null) return new int[0][];
        int[][] out = new int[boxes.size()][];
        for (int i = 0; i < out.length; i++) {
            RegionCfg r = boxes.get(i);
            if (r == null || r.min == null || r.max == null) continue;
            out[i] = new int[]{Math.min(r.min.x(), r.max.x()), Math.min(r.min.y(), r.max.y()), Math.min(r.min.z(), r.max.z()),
                Math.max(r.min.x(), r.max.x()), Math.max(r.min.y(), r.max.y()), Math.max(r.min.z(), r.max.z())};
        }
        return out;
    }

    /** Label of box {@code i}: its name, or "box N" (1-based) when unnamed. */
    private static String boxName(List<RegionCfg> boxes, int i) {
        RegionCfg r = boxes.get(i);
        return r != null && r.name != null && !r.name.isBlank() ? r.name : "box " + (i + 1);
    }

    /** The chunk columns the boxes touch, in scan order. */
    private static long[] chunkKeys(int[][] bounds) {
        Set<Long> keys = new HashSet<>();
        for (int[] b : bounds) {
            if (b == null) continue;
            for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++)
                for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) keys.add(chunkKey(cx, cz));
        }
        List<Long> sorted = new ArrayList<>(keys);
        sorted.sort(CHUNK_ORDER);
        long[] out = new long[sorted.size()];
        for (int i = 0; i < out.length; i++) out[i] = sorted.get(i);
        return out;
    }

    /**
     * Cursor over the cells of one chunk column in the fixed order: each box intersecting the column in list order,
     * y, z, x ascending. Cells already in an earlier box are visited too and flagged by {@link #taken()}.
     */
    private static final class ChunkCells {
        private final int[][] isect; // {ax, ay, az, bx, by, bz} of each intersecting box
        private final int[] boxOf;
        private final int n;
        private int k, x, y, z;

        ChunkCells(int[][] bounds, int cx, int cz) {
            int cx0 = cx << 4, cx1 = cx0 + 15, cz0 = cz << 4, cz1 = cz0 + 15;
            int[][] is = new int[bounds.length][];
            int[] bo = new int[bounds.length];
            int c = 0;
            for (int i = 0; i < bounds.length; i++) {
                int[] b = bounds[i];
                if (b == null) continue;
                int ax = Math.max(b[0], cx0), bx = Math.min(b[3], cx1), az = Math.max(b[2], cz0), bz = Math.min(b[5], cz1);
                if (ax > bx || az > bz) continue;
                is[c] = new int[]{ax, b[1], az, bx, b[4], bz};
                bo[c++] = i;
            }
            isect = is;
            boxOf = bo;
            n = c;
            if (n > 0) start();
        }

        private void start() {
            int[] b = isect[k];
            x = b[0];
            y = b[1];
            z = b[2];
        }

        boolean valid() {
            return k < n;
        }

        void advance() {
            int[] b = isect[k];
            if (x < b[3]) {
                x++;
                return;
            }
            x = b[0];
            if (z < b[5]) {
                z++;
                return;
            }
            z = b[2];
            if (y < b[4]) {
                y++;
                return;
            }
            if (++k < n) start();
        }

        /** True when an earlier box already holds the current cell. */
        boolean taken() {
            for (int j = 0; j < k; j++) {
                int[] b = isect[j];
                if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) return true;
            }
            return false;
        }

        /** List index of the box the current cell belongs to. */
        int box() {
            return boxOf[k];
        }
    }

    /**
     * A recorded reference or a fresh reading: per chunk column (cx = x>>4, cz = z>>4) the normalised ids of the
     * guarded cells of that chunk, in the fixed order derived from the boxes, as indices into a per-snapshot palette.
     */
    public static final class Snapshot {
        private final long layout;
        private final List<String> palette = new ArrayList<>();
        private final Map<String, Short> index = new HashMap<>();
        private final TreeMap<Long, short[]> chunks = new TreeMap<>(CHUNK_ORDER);
        private int cells;

        Snapshot(long layout) {
            this.layout = layout;
        }

        /** Chunk columns held. */
        public int chunks() {
            return chunks.size();
        }

        /** Guarded cells held (excluded cells not counted). */
        public int cells() {
            return cells;
        }

        /** True when chunk column {@code cx, cz} is held. */
        public boolean has(int cx, int cz) {
            return chunks.containsKey(chunkKey(cx, cz));
        }

        /** Keys ({@link #chunkKey}) of the chunk columns held, in scan order; a copy. */
        public Set<Long> chunkKeys() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(chunks.keySet()));
        }

        /** Fingerprint ({@link #layoutOf}) of the boxes this snapshot was read from. */
        public long layout() {
            return layout;
        }

        short code(String id) {
            Short c = index.get(id);
            if (c != null) return c;
            if (palette.size() >= MAX_PALETTE) throw new IllegalStateException("more than " + MAX_PALETTE + " distinct block ids");
            short n = (short) palette.size();
            palette.add(id);
            index.put(id, n);
            return n;
        }

        void put(long key, short[] ids) {
            short[] old = chunks.put(key, ids);
            if (old != null) cells -= held(old);
            cells += held(ids);
        }

        private static int held(short[] ids) {
            int n = 0;
            for (short v : ids) if (v != EXCLUDED) n++;
            return n;
        }

        @Override
        public String toString() {
            return "Snapshot[chunks=" + chunks() + ", cells=" + cells + "]";
        }
    }

    /** Incremental reader of the guarded cells: boxes minus excluded cells, chunk by chunk. */
    public static final class Scan {
        private final int[][] bounds;
        private final Predicate<Pos> excluded;
        private final long[] keys;
        private final Snapshot snapshot;
        private final Map<String, Short> rawCodes = new HashMap<>();
        private final ShortBuf buf = new ShortBuf();
        private int ci;
        private ChunkCells cur;
        private boolean done;

        /** {@code excluded} may be null (nothing excluded); it is asked once per cell, on the calling thread. */
        public Scan(List<RegionCfg> boxes, Predicate<Pos> excluded) {
            List<RegionCfg> list = boxes == null ? List.of() : boxes;
            this.bounds = bounds(list);
            this.excluded = excluded;
            this.snapshot = new Snapshot(layoutOf(list));
            this.keys = chunkKeys(bounds);
        }

        /**
         * Reads up to {@code budget} cells (at least one); chunks not loaded when reached are skipped (not in the
         * result). A chunk whose loaded state flips mid-read is dropped from the result. True when the scan is finished.
         */
        public boolean step(Blocks w, int budget) {
            int left = Math.max(1, budget);
            while (!done) {
                if (cur == null) {
                    if (ci >= keys.length) {
                        done = true;
                        break;
                    }
                    if (left <= 0) break;
                    left--;
                    ChunkCells c = new ChunkCells(bounds, cx(keys[ci]), cz(keys[ci]));
                    if (!c.valid() || !w.loaded(c.x, c.y, c.z)) {
                        ci++; // not loaded when reached: skipped
                        continue;
                    }
                    cur = c;
                    buf.clear();
                    continue;
                }
                if (!cur.valid()) {
                    snapshot.put(keys[ci], buf.toArray());
                    cur = null;
                    ci++;
                    continue;
                }
                if (left <= 0) break;
                left--;
                if (!cur.taken()) {
                    int x = cur.x, y = cur.y, z = cur.z;
                    if (excluded != null && excluded.test(new Pos(x, y, z))) buf.add(EXCLUDED);
                    else {
                        String raw = w.loaded(x, y, z) ? w.id(x, y, z) : null;
                        if (raw == null) { // unloaded mid-read: dropped
                            cur = null;
                            ci++;
                            continue;
                        }
                        buf.add(code(raw));
                    }
                }
                cur.advance();
            }
            return done;
        }

        private short code(String raw) {
            Short c = rawCodes.get(raw);
            if (c == null) {
                c = snapshot.code(normalise(raw));
                rawCodes.put(raw, c);
            }
            return c;
        }

        /** True when every chunk has been read or skipped. */
        public boolean done() {
            return done;
        }

        /** The chunks read; only valid once {@link #done()}. */
        public Snapshot result() {
            if (!done) throw new IllegalStateException("scan not finished");
            return snapshot;
        }

        /** Chunk columns the boxes touch. */
        public int chunksTotal() {
            return keys.length;
        }
    }

    /** Growable short array. */
    private static final class ShortBuf {
        private short[] a = new short[256];
        private int n;

        void add(short v) {
            if (n == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        void clear() {
            n = 0;
        }

        short[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }

    /**
     * Adds to {@code into} the chunks of {@code more} it does not hold yet (a second viewpoint). Returns chunks added.
     *
     * @throws IllegalArgumentException when the two were read from different boxes
     */
    public static int merge(Snapshot into, Snapshot more) {
        if (into.layout != more.layout) throw new IllegalArgumentException("snapshots of different boxes cannot be merged");
        if (into == more) return 0;
        int added = 0;
        for (Map.Entry<Long, short[]> e : more.chunks.entrySet()) {
            if (into.chunks.containsKey(e.getKey())) continue;
            short[] src = e.getValue(), dst = new short[src.length];
            for (int i = 0; i < src.length; i++) dst[i] = src[i] == EXCLUDED ? EXCLUDED : into.code(more.palette.get(src[i]));
            into.put(e.getKey(), dst);
            added++;
        }
        return added;
    }

    /** One differing cell: normalised ids in the reference ({@code was}) and in the reading ({@code now}). */
    public record Change(Pos pos, String was, String now) { }

    /**
     * Result of a comparison. {@code kinds}: "was -> now" to count, most frequent first; {@code perBox}: box name (or
     * "box N" when unnamed) to differing cells, most first; {@code sample}: at most 40 changes in scan
     * order; {@code chunksDiffer}: chunks with at least one difference, -1 when unknown; {@code chunks}: the keys
     * ({@link #chunkKey}) of the chunks with a difference and of the reference chunks not seen; {@code signature}: a
     * fingerprint of the whole difference (every differing cell with the block it holds now, and the chunks not seen):
     * two readings with the same signature differ from the reference in the same cells by the same blocks.
     */
    public record Diff(int chunksCompared, int chunksNotSeen, int cellsDiffer, Map<String, Integer> kinds,
                       Map<String, Integer> perBox, List<Change> sample, int chunksDiffer, Set<Long> chunks,
                       long signature) {
        /** Copies the maps (keeping their order), the list and the set. */
        public Diff {
            kinds = kinds == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(kinds));
            perBox = perBox == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(perBox));
            sample = sample == null ? List.of() : List.copyOf(sample);
            chunks = chunks == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(chunks));
        }

        /** Without the differing chunks' keys and the signature (unknown: none, 0). */
        public Diff(int chunksCompared, int chunksNotSeen, int cellsDiffer, Map<String, Integer> kinds,
                    Map<String, Integer> perBox, List<Change> sample, int chunksDiffer) {
            this(chunksCompared, chunksNotSeen, cellsDiffer, kinds, perBox, sample, chunksDiffer, Set.of(), 0);
        }

        /** Without the count of differing chunks (unknown, -1). */
        public Diff(int chunksCompared, int chunksNotSeen, int cellsDiffer, Map<String, Integer> kinds,
                    Map<String, Integer> perBox, List<Change> sample) {
            this(chunksCompared, chunksNotSeen, cellsDiffer, kinds, perBox, sample, -1);
        }

        /** True when no compared cell differs; a comparison of no chunks at all is clean too. */
        public boolean clean() {
            return cellsDiffer == 0;
        }

        /**
         * One line for a BLOCKED reason, at most 400 characters: "1,351 cells differ in 12 of 40
         * chunks: slime_block -> air 353, honey_block -> air 320, ... (box bottom units 1,351); e.g. 12,203,40
         * slime_block -> air", plus "; 3 chunks not seen" when some reference chunks were not read.
         */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (cellsDiffer == 0) sb.append("no cells differ in ").append(count(chunksCompared, "chunk"));
            else {
                sb.append(count(cellsDiffer, "cell")).append(cellsDiffer == 1 ? " differs in " : " differ in ");
                if (chunksDiffer >= 0) sb.append(fmt(chunksDiffer)).append(" of ");
                sb.append(count(chunksCompared, "chunk"));
                if (!kinds.isEmpty()) {
                    sb.append(": ");
                    join(sb, kinds, KINDS_SHOWN, false);
                }
                if (!perBox.isEmpty()) {
                    sb.append(" (");
                    join(sb, perBox, BOXES_SHOWN, true);
                    sb.append(')');
                }
                if (!sample.isEmpty()) {
                    Change c = sample.get(0);
                    sb.append("; e.g. ").append(c.pos() == null ? "?" : c.pos().key()).append(' ')
                        .append(c.was()).append(" -> ").append(c.now());
                }
            }
            if (chunksNotSeen > 0) sb.append("; ").append(count(chunksNotSeen, "chunk")).append(" not seen");
            return sb.length() <= SUMMARY_MAX ? sb.toString() : sb.substring(0, SUMMARY_MAX - 3) + "...";
        }

        /**
         * The sampled cells for the log, "x,y,z was -> now" each, joined by "; ": where to look when the summary's one
         * example is not enough (live 2026-11-16: 21 cells placed by hand, and only the first one was known).
         */
        public String cells() {
            StringBuilder sb = new StringBuilder();
            for (Change c : sample) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(c.pos() == null ? "?" : c.pos().key()).append(' ').append(c.was()).append(" -> ").append(c.now());
            }
            return sb.toString();
        }

        private static void join(StringBuilder sb, Map<String, Integer> m, int shown, boolean boxes) {
            int i = 0;
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (i == shown) {
                    sb.append(", ...");
                    break;
                }
                if (i++ > 0) sb.append(", ");
                if (boxes && !e.getKey().startsWith("box ")) sb.append("box ");
                sb.append(e.getKey()).append(' ').append(fmt(e.getValue() == null ? 0 : e.getValue()));
            }
        }

        private static String count(int n, String noun) {
            return fmt(n) + " " + noun + (n == 1 ? "" : "s");
        }

        private static String fmt(int n) {
            return String.format(Locale.ROOT, "%,d", n);
        }
    }

    /**
     * Compares the chunks both hold, cell by cell; cells excluded in either snapshot are skipped. chunksNotSeen =
     * reference chunks absent from {@code now}. {@code boxes} must be the boxes both were read from (they give the
     * positions and box names).
     *
     * @throws IllegalArgumentException when either snapshot was read from other boxes
     */
    public static Diff compare(Snapshot reference, Snapshot now, List<RegionCfg> boxes) {
        List<RegionCfg> list = boxes == null ? List.of() : boxes;
        long layout = layoutOf(list);
        if (reference.layout != layout || now.layout != layout)
            throw new IllegalArgumentException("snapshot recorded from other boxes: record the reference again");
        int[][] bounds = bounds(list);
        int refSize = reference.palette.size();
        int[] remap = new int[now.palette.size()]; // now's codes as reference codes; unknown ids beyond the palette
        for (int i = 0; i < remap.length; i++) {
            Short c = reference.index.get(now.palette.get(i));
            remap[i] = c != null ? c : refSize + i;
        }
        int compared = 0, notSeen = 0, chunksDiffer = 0, cellsDiffer = 0;
        Map<String, Integer> kinds = new LinkedHashMap<>();
        int[] perBoxIdx = new int[list.size()];
        List<Change> sample = new ArrayList<>();
        int[] diffs = new int[16];
        Set<Long> differing = new LinkedHashSet<>();
        long signature = 1125899906842597L;
        for (Map.Entry<Long, short[]> e : reference.chunks.entrySet()) {
            short[] b = now.chunks.get(e.getKey());
            if (b == null) {
                notSeen++;
                differing.add(e.getKey());
                signature = 31 * (31 * signature + e.getKey()) - 1;
                continue;
            }
            short[] a = e.getValue();
            if (a.length != b.length)
                throw new IllegalArgumentException("chunk " + cx(e.getKey()) + "," + cz(e.getKey()) + " holds "
                    + a.length + " cells in the reference but " + b.length + " in the reading");
            compared++;
            int nd = 0;
            for (int i = 0; i < a.length; i++) {
                if (a[i] == EXCLUDED || b[i] == EXCLUDED || a[i] == remap[b[i]]) continue;
                if (nd == diffs.length) diffs = Arrays.copyOf(diffs, nd * 2);
                diffs[nd++] = i;
            }
            if (nd == 0) continue;
            chunksDiffer++;
            cellsDiffer += nd;
            differing.add(e.getKey());
            signature = 31 * signature + e.getKey();
            for (int i = 0; i < nd; i++)
                signature = 31 * (31 * signature + diffs[i]) + now.palette.get(b[diffs[i]]).hashCode();
            int idx = -1, d = 0;
            for (ChunkCells c = new ChunkCells(bounds, cx(e.getKey()), cz(e.getKey())); c.valid() && d < nd; c.advance()) {
                if (c.taken() || ++idx != diffs[d]) continue;
                String was = reference.palette.get(a[idx]), is = now.palette.get(b[idx]);
                kinds.merge(was + " -> " + is, 1, Integer::sum);
                perBoxIdx[c.box()]++;
                if (sample.size() < SAMPLE) sample.add(new Change(new Pos(c.x, c.y, c.z), was, is));
                d++;
            }
        }
        Map<String, Integer> perBox = new LinkedHashMap<>();
        for (int i = 0; i < perBoxIdx.length; i++)
            if (perBoxIdx[i] > 0) perBox.merge(boxName(list, i), perBoxIdx[i], Integer::sum);
        return new Diff(compared, notSeen, cellsDiffer, mostFirst(kinds), mostFirst(perBox), sample, chunksDiffer, differing,
            signature);
    }

    /** The map sorted by count, highest first; ties keep their order. */
    private static Map<String, Integer> mostFirst(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort((p, q) -> Integer.compare(q.getValue(), p.getValue()));
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : es) out.put(e.getKey(), e.getValue());
        return out;
    }

    /**
     * Binary form for the plugin's data directory (gzip): magic, format version, layout, palette, then per chunk its
     * key and cells. Round-trips exactly. Does not close {@code out}.
     */
    public static void write(Snapshot s, OutputStream out) throws IOException {
        try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new KeepOpen(out))))) {
            d.writeInt(MAGIC);
            d.writeInt(FORMAT_VERSION);
            d.writeLong(s.layout);
            d.writeInt(s.palette.size());
            for (String id : s.palette) d.writeUTF(id);
            d.writeInt(s.chunks.size());
            for (Map.Entry<Long, short[]> e : s.chunks.entrySet()) {
                d.writeLong(e.getKey());
                short[] a = e.getValue();
                d.writeInt(a.length);
                for (short v : a) d.writeShort(v);
            }
        }
        out.flush();
    }

    /**
     * Reads what {@link #write} wrote. Does not close {@code in} (but may read past the snapshot).
     *
     * @throws IOException on another format version, a stream that is not a snapshot, or corrupt data
     */
    public static Snapshot read(InputStream in) throws IOException {
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new KeepOpenIn(in))))) {
            if (d.readInt() != MAGIC) throw new IOException("not a machine guard snapshot");
            int version = d.readInt();
            if (version != FORMAT_VERSION)
                throw new IOException("machine guard snapshot format " + version + ", expected " + FORMAT_VERSION);
            Snapshot s = new Snapshot(d.readLong());
            int np = d.readInt();
            if (np < 0 || np > MAX_PALETTE) throw new IOException("bad palette size " + np);
            for (int i = 0; i < np; i++)
                if (s.code(d.readUTF()) != i) throw new IOException("duplicate palette entry " + i);
            int nc = d.readInt();
            if (nc < 0) throw new IOException("bad chunk count " + nc);
            for (int i = 0; i < nc; i++) {
                long key = d.readLong();
                int n = d.readInt();
                if (n < 0 || n > MAX_CHUNK_CELLS) throw new IOException("bad cell count " + n);
                if (s.chunks.containsKey(key)) throw new IOException("duplicate chunk " + cx(key) + "," + cz(key));
                short[] a = new short[n];
                for (int j = 0; j < n; j++) {
                    short v = d.readShort();
                    if (v != EXCLUDED && (v < 0 || v >= np)) throw new IOException("bad palette index " + v);
                    a[j] = v;
                }
                s.put(key, a);
            }
            return s;
        }
    }

    /** Passes writes through to {@code out}; close only flushes. */
    private static final class KeepOpen extends OutputStream {
        private final OutputStream out;

        KeepOpen(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }

    /** Passes reads through to {@code in}; close does nothing. */
    private static final class KeepOpenIn extends FilterInputStream {
        KeepOpenIn(InputStream in) {
            super(in);
        }

        @Override
        public void close() { }
    }
}
