package io.github.digitalisp.mapart.testutil;

import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic test art for the restock model ({@link RestockModel}). */
public final class SyntheticArt {
    /** The 16 carpet colours, in the moss sorter's column order (white at x 0 … pink at x 15). */
    public static final List<String> CARPETS = List.of(
        "minecraft:white_carpet", "minecraft:light_gray_carpet", "minecraft:gray_carpet", "minecraft:black_carpet",
        "minecraft:brown_carpet", "minecraft:red_carpet", "minecraft:orange_carpet", "minecraft:yellow_carpet",
        "minecraft:lime_carpet", "minecraft:green_carpet", "minecraft:cyan_carpet", "minecraft:light_blue_carpet",
        "minecraft:blue_carpet", "minecraft:purple_carpet", "minecraft:magenta_carpet", "minecraft:pink_carpet");

    /**
     * 24 materials the local test world's moss sorter stocks: 10 carpets, the 8 test terracottas
     * and plain terracotta, two full blocks and three pressure plates. A mixed tile like live j0002 ("ellen": many
     * materials per lane, full blocks among carpets), but all in stock, so the local regression needs no swaps.
     */
    public static final List<String> MIXED24 = List.of(
        "minecraft:white_carpet", "minecraft:light_gray_carpet", "minecraft:gray_carpet", "minecraft:black_carpet",
        "minecraft:brown_carpet", "minecraft:red_carpet", "minecraft:orange_carpet", "minecraft:yellow_carpet",
        "minecraft:green_carpet", "minecraft:cyan_carpet", "minecraft:white_terracotta", "minecraft:light_gray_terracotta",
        "minecraft:gray_terracotta", "minecraft:black_terracotta", "minecraft:brown_terracotta", "minecraft:red_terracotta",
        "minecraft:orange_terracotta", "minecraft:yellow_terracotta", "minecraft:terracotta", "minecraft:dripstone_block",
        "minecraft:mushroom_stem", "minecraft:stone_pressure_plate", "minecraft:oak_pressure_plate",
        "minecraft:birch_pressure_plate");

    /** The six colours of {@code engine/RestockLoadTest.dithered}, in its order. */
    public static final List<String> SIX = List.of("minecraft:white_carpet", "minecraft:red_carpet",
        "minecraft:black_carpet", "minecraft:lime_carpet", "minecraft:blue_carpet", "minecraft:yellow_carpet");

    private static final int SIZE = TileArt.SIZE;
    private static final int CELLS = SIZE * SIZE;

    /** 4x4 Bayer matrix (ordered dithering thresholds 0..15). */
    private static final int[][] BAYER = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

    private SyntheticArt() { }

    /**
     * Zipf-like target shares (Zipf–Mandelbrot, share of rank i ∝ (i + 10)^−s), with s chosen so the smallest is
     * 1/60 of the largest: for 16 colours 30.2 % down to 0.5 %.
     */
    public static double[] zipfShares(int n) {
        double q = 10.0;
        double s = Math.log(60.0) / Math.log((n - 1 + q) / q);
        double[] w = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) total += (w[i] = Math.pow(i + q, -s));
        for (int i = 0; i < n; i++) w[i] /= total;
        return w;
    }

    /**
     * {@link #carpet16(long, int, double, double, double)} as the restock model's carpet tile: 6 waves of 16–64 pixels,
     * dither 0.30. Seed 16 gives 13.8 colours per 5-wide lane (the roadmap's real 16-colour tile: 13.7).
     */
    public static TileArt carpet16(long seed) {
        return carpet16(seed, 6, 16, 64, 0.30);
    }

    /**
     * A full 128x128 carpet tile with all 16 colours and a skewed, image-like histogram.
     * <ol>
     *   <li>A smooth field: the sum of {@code waves} seeded 2-D sinusoids (wavelengths {@code minWavelength} to
     *       {@code maxWavelength} pixels, random directions, phases and amplitudes), turned into ranks in [0, 1);</li>
     *   <li>ordered dithering: a 4x4 Bayer offset of ±{@code dither}/2 in rank units, so pixels near a band edge
     *       alternate between the neighbouring colours, as a dithered export mixes them;</li>
     *   <li>cut into 16 bands at the quantiles of {@link #zipfShares} (the shares hold up to rounding). The bands take
     *       the shares in a seeded order and the colours in another, so big and small bands interleave.</li>
     * </ol>
     */
    public static TileArt carpet16(long seed, int waves, double minWavelength, double maxWavelength, double dither) {
        return banded(seed, CARPETS, waves, minWavelength, maxWavelength, dither);
    }

    /** {@link #banded} over {@link #MIXED24} with the carpet tile's field (6 waves of 16–64 pixels, dither 0.30). */
    public static TileArt mixed24(long seed) {
        return banded(seed, MIXED24, 6, 16, 64, 0.30);
    }

    /**
     * The {@link #carpet16(long, int, double, double, double)} construction over any {@code palette}: a smooth seeded
     * field, ordered dithering, bands at the quantiles of {@link #zipfShares}{@code (palette.size())}.
     */
    public static TileArt banded(long seed, List<String> palette, int waves, double minWavelength, double maxWavelength,
                                 double dither) {
        Random r = new Random(seed);
        double[] kx = new double[waves], kz = new double[waves], ph = new double[waves], amp = new double[waves];
        for (int i = 0; i < waves; i++) {
            double wavelength = minWavelength + r.nextDouble() * (maxWavelength - minWavelength);
            double angle = r.nextDouble() * Math.PI;
            kx[i] = 2 * Math.PI / wavelength * Math.cos(angle);
            kz[i] = 2 * Math.PI / wavelength * Math.sin(angle);
            ph[i] = r.nextDouble() * 2 * Math.PI;
            amp[i] = 0.5 + r.nextDouble();
        }
        double[] field = new double[CELLS];
        for (int z = 0; z < SIZE; z++)
            for (int x = 0; x < SIZE; x++) {
                double v = 0;
                for (int i = 0; i < waves; i++) v += amp[i] * Math.sin(kx[i] * x + kz[i] * z + ph[i]);
                field[z * SIZE + x] = v;
            }
        double[] ranked = new double[CELLS];
        Integer[] order = byValue(field);
        for (int i = 0; i < CELLS; i++) ranked[order[i]] = (double) i / CELLS;
        for (int z = 0; z < SIZE; z++)
            for (int x = 0; x < SIZE; x++)
                ranked[z * SIZE + x] += dither * ((BAYER[z & 3][x & 3] + 0.5) / 16.0 - 0.5);
        double[] shares = zipfShares(palette.size());
        List<Integer> bandShare = new ArrayList<>();
        for (int i = 0; i < shares.length; i++) bandShare.add(i);
        Collections.shuffle(bandShare, r);
        List<String> colours = new ArrayList<>(palette);
        Collections.shuffle(colours, r);
        int[] shareRank = new int[CELLS];
        Integer[] dithered = byValue(ranked);
        double cum = 0;
        int start = 0;
        for (int b = 0; b < bandShare.size(); b++) {
            cum += shares[bandShare.get(b)];
            int end = b == bandShare.size() - 1 ? CELLS : (int) Math.round(cum * CELLS);
            for (int i = start; i < end; i++) shareRank[dithered[i]] = bandShare.get(b);
            start = end;
        }
        TileArt art = new TileArt();
        for (int z = 0; z < SIZE; z++)
            for (int x = 0; x < SIZE; x++) art.set(x, z, BlockSpec.of(colours.get(shareRank[z * SIZE + x])));
        return art;
    }

    /** Cell indices sorted by value (ties by index). */
    private static Integer[] byValue(double[] v) {
        Integer[] idx = new Integer[v.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> v[a] != v[b] ? Double.compare(v[a], v[b]) : Integer.compare(a, b));
        return idx;
    }

    /** Six carpet colours uniformly at random per pixel, exactly as {@code engine/RestockLoadTest.dithered(seed)}. */
    public static TileArt dithered6(long seed) {
        Random r = new Random(seed);
        TileArt a = new TileArt();
        for (int pz = 0; pz < SIZE; pz++)
            for (int px = 0; px < SIZE; px++) a.set(px, pz, BlockSpec.of(SIX.get(r.nextInt(SIX.size()))));
        return a;
    }

    /** Block id → pixels, largest first (ties by id). */
    public static Map<String, Integer> histogram(TileArt art) {
        Map<String, Integer> m = new TreeMap<>();
        for (int z = 0; z < SIZE; z++)
            for (int x = 0; x < SIZE; x++) {
                BlockSpec b = art.get(x, z);
                if (b != null) m.merge(b.id(), 1, Integer::sum);
            }
        Map<String, Integer> out = new LinkedHashMap<>();
        m.entrySet().stream()
            .sorted((a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().compareTo(b.getKey()) : b.getValue() - a.getValue())
            .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Mean distinct blocks per lane of {@code 2·halfWidth + 1} columns (the serpentine planner's lane split). */
    public static double coloursPerLane(TileArt art, int halfWidth) {
        int width = 2 * halfWidth + 1;
        int lanes = 0;
        long sum = 0;
        for (int lo = 0; lo < SIZE; lo += width) {
            Set<String> ids = new HashSet<>();
            for (int x = lo; x < Math.min(SIZE, lo + width); x++)
                for (int z = 0; z < SIZE; z++) {
                    BlockSpec b = art.get(x, z);
                    if (b != null) ids.add(b.id());
                }
            if (ids.isEmpty()) continue;
            lanes++;
            sum += ids.size();
        }
        return lanes == 0 ? 0 : (double) sum / lanes;
    }
}
