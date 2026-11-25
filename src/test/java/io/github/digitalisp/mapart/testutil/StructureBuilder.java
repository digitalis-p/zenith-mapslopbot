package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.model.BlockSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Builds structure .nbt files shaped like MapArtCraft exports, for synthetic parser tests. */
public final class StructureBuilder {
    public static final String SUPPORT = "minecraft:cobblestone";
    public static final String MAPARTCRAFT = "mike2b2t.github.io/mapartcraft";
    public static final String PNG_TO_NBT = "evmodder.net/PNG-to-NBT";

    private final int sx, sy, sz;
    private int dataVersion = 4189;
    private String author = "test";
    private boolean idKeys;
    private boolean pngToNbtOrder;
    private final List<Map<String, Object>> palette = new ArrayList<>();
    private final List<Object> blocks = new ArrayList<>();

    public StructureBuilder(int sx, int sy, int sz) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
    }

    public StructureBuilder dataVersion(int dv) {
        this.dataVersion = dv;
        return this;
    }

    /** The root {@code author} tag ("test" by default); null leaves it out. */
    public StructureBuilder author(String author) {
        this.author = author;
        return this;
    }

    /** Palette entries keyed {@code id}/{@code properties}, as newer structure files (PNG-to-NBT 26.x) write them. */
    public StructureBuilder idKeys() {
        this.idKeys = true;
        return this;
    }

    /** Root tags in PNG-to-NBT's order: DataVersion, size, author, palette, blocks. */
    public StructureBuilder pngToNbtOrder() {
        this.pngToNbtOrder = true;
        return this;
    }

    /** Appends a palette entry (duplicates allowed) and returns its index. */
    public int palette(String state) {
        BlockSpec s = BlockSpec.parse(state);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("Name", s.id());
        if (!s.props().isEmpty()) e.put("Properties", new LinkedHashMap<String, Object>(s.props()));
        palette.add(e);
        return palette.size() - 1;
    }

    public StructureBuilder block(int x, int y, int z, int state) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("pos", List.of(x, y, z));
        b.put("state", state);
        blocks.add(b);
        return this;
    }

    public Map<String, Object> root() {
        List<Object> pal = new ArrayList<>();
        for (Map<String, Object> e : palette) {
            if (!idKeys) {
                pal.add(e);
                continue;
            }
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", e.get("Name"));
            if (e.containsKey("Properties")) n.put("properties", e.get("Properties"));
            pal.add(n);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        if (pngToNbtOrder) {
            r.put("DataVersion", dataVersion);
            r.put("size", List.of(sx, sy, sz));
            if (author != null) r.put("author", author);
            r.put("palette", pal);
            r.put("blocks", blocks);
            return r;
        }
        r.put("blocks", blocks);
        r.put("entities", List.of());
        r.put("palette", pal);
        r.put("size", List.of(sx, sy, sz));
        if (author != null) r.put("author", author);
        r.put("DataVersion", dataVersion);
        return r;
    }

    public byte[] gzip() {
        return NbtWriter.gzip(root());
    }

    /**
     * A flat export of {@code tilesX x tilesZ} maps: art at y=2 with a support block under each pixel,
     * cobblestone noobline at z=0, support as the last palette entry. {@code pixel} maps global image
     * coordinates (px, pz) to a state string, or null for a transparent pixel (explicit air).
     */
    public static byte[] flat(int tilesX, int tilesZ, BiFunction<Integer, Integer, String> pixel) {
        return flat(tilesX, tilesZ, pixel, SUPPORT, null);
    }

    /**
     * As {@link #flat(int, int, BiFunction)} with another support block, and {@code setup} (may be null) applied to
     * the builder first (author, DataVersion, key style).
     */
    public static byte[] flat(int tilesX, int tilesZ, BiFunction<Integer, Integer, String> pixel, String supportBlock,
                              Consumer<StructureBuilder> setup) {
        int w = 128 * tilesX, d = 128 * tilesZ;
        StructureBuilder b = new StructureBuilder(w, 3, d + 1);
        if (setup != null) setup.accept(b);
        Map<String, Integer> idx = new LinkedHashMap<>();
        String[] states = new String[w * d];
        for (int pz = 0; pz < d; pz++)
            for (int px = 0; px < w; px++) {
                String s = pixel.apply(px, pz);
                states[pz * w + px] = s == null ? "minecraft:air" : s;
                idx.computeIfAbsent(states[pz * w + px], b::palette);
            }
        int support = b.palette(supportBlock);
        for (int x = 0; x < w; x++) {
            b.block(x, 2, 0, support);
            for (int pz = 0; pz < d; pz++) {
                String s = states[pz * w + x];
                b.block(x, 2, pz + 1, idx.get(s));
                if (!s.equals("minecraft:air")) b.block(x, 1, pz + 1, support);
            }
        }
        return b.gzip();
    }
}
