package io.github.digitalisp.mapart.store;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.TileArt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact JSON form of a {@link TileArt}: a palette of canonical state strings plus base64 of 16384
 * little-endian shorts in row-major order (pz then px), each a palette index or -1 for empty. Also the inline art of a
 * coordination tile unit spec ({@code io.github.digitalisp.mapart.coord.TileUnitSpec}).
 */
public final class TileCodec {
    public static final int VERSION = 1;
    private static final int SIZE = TileArt.SIZE;

    /** Gson-mapped file body. */
    public static final class Stored {
        public int version = VERSION;
        public List<String> palette = new ArrayList<>();
        public String cells = "";
    }

    private TileCodec() { }

    public static Stored encode(TileArt art) {
        Stored s = new Stored();
        Map<BlockSpec, Integer> index = new HashMap<>();
        ByteBuffer buf = ByteBuffer.allocate(SIZE * SIZE * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int pz = 0; pz < SIZE; pz++)
            for (int px = 0; px < SIZE; px++) {
                BlockSpec b = art.get(px, pz);
                int i = -1;
                if (b != null) {
                    i = index.computeIfAbsent(b, k -> {
                        s.palette.add(k.toString());
                        return s.palette.size() - 1;
                    });
                }
                buf.putShort((short) i);
            }
        s.cells = Base64.getEncoder().encodeToString(buf.array());
        return s;
    }

    public static TileArt decode(Stored s) throws IOException {
        if (s == null || s.palette == null || s.cells == null) throw new IOException("tile file is incomplete");
        if (s.version != VERSION) throw new IOException("unsupported tile format version " + s.version);
        BlockSpec[] palette = new BlockSpec[s.palette.size()];
        for (int i = 0; i < palette.length; i++) palette[i] = BlockSpec.parse(s.palette.get(i));
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(s.cells);
        } catch (IllegalArgumentException e) {
            throw new IOException("tile cells are not valid base64", e);
        }
        if (raw.length != SIZE * SIZE * 2) throw new IOException("tile has " + raw.length / 2 + " cells, expected " + SIZE * SIZE);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        TileArt art = new TileArt();
        for (int pz = 0; pz < SIZE; pz++)
            for (int px = 0; px < SIZE; px++) {
                int i = buf.getShort();
                if (i == -1) continue;
                if (i < 0 || i >= palette.length) throw new IOException("tile cell " + px + "," + pz + " has bad index " + i);
                art.set(px, pz, palette[i]);
            }
        return art;
    }
}
