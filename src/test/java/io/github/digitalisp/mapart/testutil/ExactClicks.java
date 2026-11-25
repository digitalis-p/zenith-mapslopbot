package io.github.digitalisp.mapart.testutil;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Fewest vanilla container clicks that leave exactly a wanted count in the player's inventory (test-side cost model).
 * A breadth-first search over (container stack, player slot, cursor) with left clicks (pick up all; put down or merge)
 * and right clicks (with a cursor: put one; without, when {@code half}: pick up half). The source is one full container
 * stack; the cursor ends empty.
 */
public final class ExactClicks {
    private static final Map<Long, int[]> CACHE = new HashMap<>();

    private ExactClicks() { }

    /** Fewest clicks moving exactly {@code x} (1..max) from a full container stack into a slot holding {@code p}. */
    public static synchronized int into(int p, int x, int max, boolean half) {
        if (x <= 0) return 0;
        long key = ((long) p << 32) | ((long) max << 1) | (half ? 1 : 0);
        int[] t = CACHE.computeIfAbsent(key, k -> table(p, max, half));
        return x < t.length ? t[x] : Integer.MAX_VALUE;
    }

    /**
     * Clicks taking {@code w} more of an item held {@code h} (one partial stack {@code h % max} plus full ones), ending
     * with exactly {@code h + w} in the fewest slots: top-ups, whole stacks by shift-click, the remainder by
     * {@link #into}.
     */
    public static int withdraw(int h, int w, int max, boolean half) {
        if (w <= 0) return 0;
        int p = h % max;
        int f = h + w;
        int rho = f % max;
        int best = Integer.MAX_VALUE;
        int fill = p > 0 ? max - p : 0;
        int topUp = p > 0 ? 3 : 0;
        if (rho == 0 && w >= fill) best = Math.min(best, topUp + (w - fill) / max);
        if (rho == p) best = Math.min(best, w / max);
        if (p > 0 && rho > p) {
            int x = rho - p;
            best = Math.min(best, into(p, x, max, half) + (w - x) / max);
        }
        if (rho > 0 && w - fill >= rho) best = Math.min(best, topUp + into(0, rho, max, half) + (w - fill - rho) / max);
        return best;
    }

    private static int[] table(int p, int max, boolean half) {
        int total = max + p;
        // state: s (container slot), e (player slot), c = total - s - e (cursor)
        int[][] dist = new int[total + 1][total + 1];
        for (int[] row : dist) Arrays.fill(row, -1);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        dist[max][p] = 0;
        q.add(new int[] {max, p});
        while (!q.isEmpty()) {
            int[] st = q.poll();
            int s = st[0];
            int e = st[1];
            int c = total - s - e;
            int d = dist[s][e];
            for (int which = 0; which < 2; which++) {
                int x = which == 0 ? s : e;
                int[][] moves = new int[2][];
                // left click
                if (c == 0) {
                    if (x > 0) moves[0] = new int[] {0, x};
                } else {
                    int mv = Math.min(c, max - x);
                    if (mv > 0) moves[0] = new int[] {x + mv, c - mv};
                }
                // right click
                if (c == 0) {
                    if (half && x > 0) {
                        int up = (x + 1) / 2;
                        moves[1] = new int[] {x - up, up};
                    }
                } else if (x < max) {
                    moves[1] = new int[] {x + 1, c - 1};
                }
                for (int[] m : moves) {
                    if (m == null) continue;
                    int ns = which == 0 ? m[0] : s;
                    int ne = which == 1 ? m[0] : e;
                    if (dist[ns][ne] < 0) {
                        dist[ns][ne] = d + 1;
                        q.add(new int[] {ns, ne});
                    }
                }
            }
        }
        int[] out = new int[max + 1];
        Arrays.fill(out, Integer.MAX_VALUE);
        for (int s = 0; s <= total; s++)
            for (int e = p; e <= total; e++)
                if (dist[s][e] >= 0 && s + e == total && e - p <= max) out[e - p] = Math.min(out[e - p], dist[s][e]);
        return out;
    }
}
