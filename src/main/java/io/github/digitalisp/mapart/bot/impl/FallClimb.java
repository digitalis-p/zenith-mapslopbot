package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

/**
 * The climb of the fall rescue ({@code build.fallRescuePillar}): how a bot that fell
 * into a pit or a channel it cannot swim or jump out of gets back up with blocks of its own, and which of them it
 * breaks again from where. Pure and unit-tested; the world comes through {@link Grid}.
 *
 * <p>Live 2026-10-15..19 (soul sand platform): the water channels beside the tile are 1 wide with 1 block of water over
 * a glass floor; the floor of the tile (soul sand, top 0.875) is 2.875 above the channel's floor and the walls are
 * solid up to it. A jump in that water rises about 1.1, so neither the old swim-and-step nor Baritone (no placing)
 * ever got out: every fall ended BLOCKED until the owner pillared the bot out by hand.
 *
 * <p>Moves (each from a stand: the bot's feet cell {@code at} and the height of its feet {@code h}):
 * <ul>
 *   <li>{@link Kind#WALK}: to a side cell at the same height (only before the first block of a plan; at most
 *       {@link #MAX_WALK}), to reach a column with room above;</li>
 *   <li>{@link Kind#STAIR}: a block into the side cell at the feet's level (clicked against the top of the block below
 *       it or the side of a wall block beside it, never the bot's own column), then a jump onto it: one block up and
 *       one sideways, the bot's box never in the cell it fills;</li>
 *   <li>{@link Kind#PILLAR}: a jump, and the block into the bot's own feet cell against the top of the block it stood
 *       on, once its feet are above that cell; dearer than a stair. Never from water: a jump from the floor of 1-deep
 *       water rises 0.36 (ZenithProxy, local run 2026-10-23);</li>
 *   <li>{@link Kind#EXIT}: onto a dry {@link Grid#exit} cell beside the stand whose floor is at most
 *       {@link #MAX_RISE} above the feet (a jump when more than {@link #STEP}).</li>
 * </ul>
 * Every block is the bot's own and is broken again: at each stand the blocks it no longer needs (not the one under its
 * feet, not a later move's support) that it can see within reach, the highest first ({@link Move#breaks}); what is left
 * after the exit is {@link Plan#stranded}. A plan is the one with the fewest stranded blocks, then the fewest blocks,
 * then the fewest pillars, walks and moves. Placing is only into cells {@link Grid#placeAllowed} allows.
 * The bot's body may pass cells of no-go zones (its head over the channel is in the zone of the parked machines):
 * every cell it passes is checked free of collision.
 */
final class FallClimb {
    /** Blocks a plan may place at most. */
    static final int MAX_BLOCKS = 6;
    /** Side steps at the pit's bottom before the first block. */
    static final int MAX_WALK = 4;
    /** Horizontal (Chebyshev) distance from the start a plan stays within. */
    static final int RADIUS = 4;
    /** Search states expanded at most (a pit with no way out ends here). */
    static final int MAX_STATES = 6000;
    /**
     * A jump lands on a floor at most this much above the feet (vanilla's jump peaks at {@link #JUMP}): a block, or a
     * carpet on a block (1.0625: from the pond over a slab onto the art's carpets beside the channel).
     */
    static final double MAX_RISE = 1.1;
    /** A rise above this needs a jump (vanilla steps up 0.6 without one). */
    static final double STEP = 0.5;
    /** Vanilla jump height from the ground. */
    static final double JUMP = 1.25;
    /** The player's box. */
    static final double HEIGHT = 1.8;
    static final double EYE = 1.62;
    /** Cost units: a block outweighs any number of the rest. */
    private static final int BLOCK_COST = 100;
    private static final int PILLAR_COST = 5;
    private static final int WALK_COST = 3;
    private static final double EPS = 1e-6;

    private FallClimb() { }

    enum Kind { WALK, PILLAR, STAIR, EXIT }

    /**
     * One move: the bot ends in feet cell {@code to} with its feet at height {@code feet}. PILLAR and STAIR place
     * {@code block} by clicking face {@code face} ({@link Aim} codes) of {@code support}. {@code jump}: the move needs a
     * jump. {@code breaks}: own blocks broken from {@code to} afterwards, in that order.
     */
    record Move(Kind kind, Pos to, double feet, @Nullable Pos block, @Nullable Pos support, int face, boolean jump,
                List<Pos> breaks) {
        Move withBreaks(List<Pos> b) {
            return new Move(kind, to, feet, block, support, face, jump, List.copyOf(b));
        }

        @Override
        public String toString() {
            String s = kind + " to " + to.key();
            if (block != null && support != null) s += " placing " + block.key() + " on " + FACE_NAMES[face] + " of " + support.key();
            if (!breaks.isEmpty()) s += ", then breaking " + keys(breaks);
            return s;
        }
    }

    /** The moves, the last one the {@link Kind#EXIT}; {@code stranded}: own blocks left in the world afterwards. */
    record Plan(List<Move> moves, List<Pos> stranded, int cost) {
        int blocks() {
            int n = 0;
            for (Move m : moves) if (m.block() != null) n++;
            return n;
        }

        Move exit() {
            return moves.getLast();
        }

        @Override
        public String toString() {
            List<String> out = new ArrayList<>();
            for (Move m : moves) out.add(m.toString());
            return String.join("; ", out) + (stranded.isEmpty() ? "" : "; leaving " + keys(stranded));
        }
    }

    /** The world around the pit. Cells that are not loaded are walls. */
    interface Grid {
        boolean loaded(Pos p);

        /** Highest collision box top of the cell's block: 0 none (air, water, signs, fans), 1 full, soul sand 0.875. */
        double top(Pos p);

        /** Highest outline (raycast) box top: 0 none (air, water), soul sand 1, a carpet 1/16. */
        double outline(Pos p);

        /** Water or lava in the cell (a waterlogged block too). */
        boolean fluid(Pos p);

        /** A placed block may go into the cell: air or a plain fluid. */
        boolean replaceable(Pos p);

        /** A face of the cell's block may be clicked to place against: a full cube, no block entity, nothing usable. */
        boolean clickable(Pos p);

        /** The rescue may put a block of its own into the cell (no protected region, art cell or other no-go zone). */
        boolean placeAllowed(Pos p);

        /** A dry feet cell at the floor's level the bot may end in ({@link FallExit#standable}, no no-go zone). */
        boolean exit(Pos feet);
    }

    static final String[] FACE_NAMES = {"bottom", "top", "north side", "south side", "west side", "east side"};

    /**
     * The best plan from feet cell {@code at} with the feet at height {@code h}, or null when there is none within
     * {@link #MAX_BLOCKS} blocks. {@code own}: blocks of earlier attempts still in the world (the rescue breaks them too,
     * and may stand on them or click them). {@code reach}: block reach for clicks and breaks.
     */
    static @Nullable Plan plan(Pos at, double h, Set<Pos> own, Grid g, double reach) {
        Search s = new Search(at, own, g, reach);
        return s.run(h);
    }

    /** One state of the search. */
    private record Node(Pos at, double h, List<Move> moves, Set<Pos> placed, int blocks, int walks, int cost) { }

    private static final class Search {
        final Pos start;
        final Set<Pos> own;
        final Grid g;
        final double reach;
        final Map<String, Integer> seen = new HashMap<>();
        final List<Plan> found = new ArrayList<>();

        Search(Pos start, Set<Pos> own, Grid g, double reach) {
            this.start = start;
            this.own = Set.copyOf(own);
            this.g = g;
            this.reach = reach;
        }

        @Nullable Plan run(double h) {
            PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingInt(Node::cost));
            open.add(new Node(start, h, List.of(), new LinkedHashSet<>(own), 0, 0, 0));
            int expanded = 0;
            int best = Integer.MAX_VALUE;
            while (!open.isEmpty() && expanded < MAX_STATES) {
                Node n = open.poll();
                // a plan with up to two more blocks may strand fewer of them
                if (best != Integer.MAX_VALUE && n.cost() > best + 2 * BLOCK_COST) break;
                String key = n.at().key() + "|" + n.h() + "|" + new TreeSet<>(n.placed().stream().map(Pos::key).toList());
                Integer was = seen.get(key);
                if (was != null && was <= n.cost()) continue;
                seen.put(key, n.cost());
                expanded++;
                View v = new View(g, n.placed(), Set.of());
                Move exit = exit(n, v);
                if (exit != null) {
                    List<Move> moves = new ArrayList<>(n.moves());
                    moves.add(exit);
                    Plan p = schedule(moves, n.cost());
                    found.add(p);
                    best = Math.min(best, n.cost());
                    continue; // a longer climb from a stand with an exit is never better
                }
                if (n.blocks() < MAX_BLOCKS) {
                    for (Node c : climbs(n, v)) open.add(c);
                }
                if (n.blocks() == 0 && n.walks() < MAX_WALK) {
                    for (Node c : walks(n, v)) open.add(c);
                }
            }
            Plan pick = null;
            for (Plan p : found) {
                if (pick == null || p.stranded().size() < pick.stranded().size()
                    || (p.stranded().size() == pick.stranded().size() && p.cost() < pick.cost())) pick = p;
            }
            return pick;
        }

        private boolean near(Pos p) {
            return Math.abs(p.x() - start.x()) <= RADIUS && Math.abs(p.z() - start.z()) <= RADIUS
                && p.y() <= start.y() + MAX_BLOCKS + 2 && p.y() >= start.y() - 2;
        }

        /** The exit from {@code n}'s stand: the lowest dry cell beside it within reach of a jump, sides in order. */
        private @Nullable Move exit(Node n, View v) {
            Pos at = n.at();
            double h = n.h();
            Move best = null;
            for (int[] d : Aim.SIDES) {
                for (int ey = at.y() - 2; ey <= at.y() + 2; ey++) {
                    Pos e = new Pos(at.x() + d[0], ey, at.z() + d[1]);
                    if (!g.loaded(e) || !g.exit(e) || n.placed().contains(e) || n.placed().contains(e.above())) continue;
                    double t = standTop(e, v);
                    if (Double.isNaN(t)) continue;
                    double rise = t - h;
                    if (rise > MAX_RISE + EPS) continue;
                    boolean jump = rise > STEP;
                    double peak = Math.max(t, h + (jump ? JUMP : 0));
                    if (!free(v, at.x(), at.z(), h + EPS, peak + HEIGHT)) continue;
                    if (!free(v, e.x(), e.z(), t + EPS, peak + HEIGHT)) continue;
                    Move m = new Move(Kind.EXIT, e, t, null, null, -1, jump, List.of());
                    if (best == null || t < best.feet()) best = m;
                    break;
                }
            }
            return best;
        }

        private List<Node> climbs(Node n, View v) {
            List<Node> out = new ArrayList<>();
            Pos at = n.at();
            double h = n.h();
            boolean onTop = Math.abs(h - at.y()) < 1e-3;
            if (!free(v, at.x(), at.z(), h + EPS, h + JUMP + HEIGHT)) return out; // no room to jump
            // PILLAR: into the own feet cell, against the top of the full block the bot stands on
            Pos below = at.below();
            if (onTop && v.replaceable(at) && !v.fluid(at) && !v.fluid(at.above()) && g.placeAllowed(at)
                && v.clickable(below) && v.top(below) >= 1 - EPS && near(at.above())) {
                Move m = new Move(Kind.PILLAR, at.above(), h + 1, at, below, Aim.UP, true, List.of());
                out.add(child(n, m, at, BLOCK_COST + PILLAR_COST));
            }
            // STAIR: into a side cell at the feet's level, then a jump onto it
            double ex = at.x() + 0.5;
            double ey = h + EYE;
            double ez = at.z() + 0.5;
            for (int[] d : Aim.SIDES) {
                Pos t = new Pos(at.x() + d[0], at.y(), at.z() + d[1]);
                double nh = t.y() + 1;
                if (nh - h > MAX_RISE + EPS || nh - h < STEP - EPS) continue;
                if (!near(t) || !g.loaded(t) || !v.replaceable(t) || !g.placeAllowed(t)) continue;
                Pos to = t.above();
                if (!free(v, t.x(), t.z(), nh + EPS, Math.max(nh, h + JUMP) + HEIGHT)) continue;
                int[] support = support(v, t, at, ex, ey, ez);
                if (support == null) continue;
                Pos s = new Pos(support[0], support[1], support[2]);
                Move m = new Move(Kind.STAIR, to, nh, t, s, support[3], true, List.of());
                out.add(child(n, m, t, BLOCK_COST));
            }
            return out;
        }

        private Node child(Node n, Move m, Pos block, int cost) {
            List<Move> moves = new ArrayList<>(n.moves());
            moves.add(m);
            Set<Pos> placed = new LinkedHashSet<>(n.placed());
            placed.add(block);
            return new Node(m.to(), m.feet(), moves, placed, n.blocks() + 1, n.walks(), n.cost() + cost + 1);
        }

        /**
         * The face to click for a block into {@code t}: the top of the block below it, else the side of a block beside
         * it facing {@code t} (never the bot's column {@code at}); blocks of the world before the bot's own; seen from
         * the eye within reach. {x, y, z, face} or null.
         */
        private int @Nullable [] support(View v, Pos t, Pos at, double ex, double ey, double ez) {
            List<int[]> cands = new ArrayList<>();
            cands.add(new int[]{t.x(), t.y() - 1, t.z(), Aim.UP});
            for (int[] d : Aim.SIDES) {
                Pos s = new Pos(t.x() + d[0], t.y(), t.z() + d[1]);
                if (s.x() == at.x() && s.z() == at.z()) continue;
                cands.add(new int[]{s.x(), s.y(), s.z(), faceToward(-d[0], -d[1])}); // its side toward t
            }
            int[] ownPick = null;
            for (int[] c : cands) {
                Pos s = new Pos(c[0], c[1], c[2]);
                if (!g.loaded(s) || !v.clickable(s)) continue;
                if (!faceVisible(v, ex, ey, ez, s, c[3], reach)) continue;
                if (v.present.contains(s)) {
                    if (ownPick == null) ownPick = c;
                    continue;
                }
                return c;
            }
            return ownPick;
        }

        private List<Node> walks(Node n, View v) {
            List<Node> out = new ArrayList<>();
            Pos at = n.at();
            double h = n.h();
            for (int[] d : Aim.SIDES) {
                for (int dy = -1; dy <= 1; dy++) {
                    Pos to = new Pos(at.x() + d[0], at.y() + dy, at.z() + d[1]);
                    if (!near(to) || !g.loaded(to)) continue;
                    double t = standTop(to, v);
                    if (Double.isNaN(t) || Math.abs(t - h) > 0.1) continue;
                    if (!free(v, to.x(), to.z(), t + EPS, Math.max(t, h) + HEIGHT)) continue;
                    if (!free(v, at.x(), at.z(), h + EPS, Math.max(t, h) + HEIGHT)) continue;
                    List<Move> moves = new ArrayList<>(n.moves());
                    moves.add(new Move(Kind.WALK, to, t, null, null, -1, false, List.of()));
                    out.add(new Node(to, t, moves, n.placed(), 0, n.walks() + 1, n.cost() + WALK_COST + 1));
                    break;
                }
            }
            return out;
        }

        /**
         * The breaks of {@code moves}: after each move, from its stand, every own block no longer needed that the bot
         * can see within reach, the highest first; what is left after the last move is stranded.
         */
        private Plan schedule(List<Move> moves, int cost) {
            Set<Pos> present = new LinkedHashSet<>(own);
            Set<Pos> gone = new HashSet<>();
            List<Move> out = new ArrayList<>();
            for (int i = 0; i < moves.size(); i++) {
                Move m = moves.get(i);
                if (m.block() != null) present.add(m.block());
                Set<Pos> keep = new HashSet<>();
                keep.add(m.to().below());
                for (int j = i + 1; j < moves.size(); j++) {
                    Move later = moves.get(j);
                    if (later.support() != null) keep.add(later.support());
                    keep.add(later.to().below());
                }
                double ex = m.to().x() + 0.5;
                double ey = m.feet() + EYE;
                double ez = m.to().z() + 0.5;
                List<Pos> broke = new ArrayList<>();
                boolean again = true;
                while (again) {
                    again = false;
                    List<Pos> cands = new ArrayList<>(present);
                    cands.sort(Comparator.comparingInt(Pos::y).reversed()
                        .thenComparingDouble(p -> Aim.distSq(ex, ey, ez, p.x() + 0.5, p.y() + 0.5, p.z() + 0.5)));
                    for (Pos b : cands) {
                        if (keep.contains(b)) continue;
                        View v = new View(g, present, gone);
                        if (!blockVisible(v, ex, ey, ez, b, reach)) continue;
                        present.remove(b);
                        gone.add(b);
                        broke.add(b);
                        again = true;
                        break;
                    }
                }
                out.add(m.withBreaks(broke));
            }
            return new Plan(List.copyOf(out), List.copyOf(present), cost);
        }
    }

    /** The grid with the plan's own blocks in it ({@code present}) and the ones broken again out of it ({@code gone}). */
    static final class View {
        final Grid g;
        final Set<Pos> present;
        final Set<Pos> gone;

        View(Grid g, Set<Pos> present, Set<Pos> gone) {
            this.g = g;
            this.present = present;
            this.gone = gone;
        }

        boolean loaded(Pos p) {
            return g.loaded(p);
        }

        double top(Pos p) {
            if (present.contains(p)) return 1;
            if (gone.contains(p)) return 0;
            return g.loaded(p) ? g.top(p) : 1;
        }

        double outline(Pos p) {
            if (present.contains(p)) return 1;
            if (gone.contains(p)) return 0;
            return g.loaded(p) ? g.outline(p) : 1;
        }

        boolean fluid(Pos p) {
            if (present.contains(p) || gone.contains(p)) return false;
            return g.fluid(p);
        }

        boolean replaceable(Pos p) {
            if (present.contains(p)) return false;
            if (gone.contains(p)) return true;
            return g.loaded(p) && g.replaceable(p);
        }

        boolean clickable(Pos p) {
            if (present.contains(p)) return true;
            if (gone.contains(p)) return false;
            return g.loaded(p) && g.clickable(p);
        }
    }

    /** Aim face code of the side of a block that faces its neighbour at (+dx, +dz). */
    static int faceToward(int dx, int dz) {
        if (dx == 1) return Aim.EAST;
        if (dx == -1) return Aim.WEST;
        if (dz == 1) return Aim.SOUTH;
        return Aim.NORTH;
    }

    /**
     * Height the feet rest at in feet cell {@code f}: on a low block in it (a carpet) or on the block below; NaN when
     * the cell holds more than a carpet or has nothing to stand on.
     */
    static double standTop(Pos f, View v) {
        double own = v.top(f);
        if (own > FallExit.LOW + EPS) return Double.NaN;
        if (own > 0) return f.y() + own;
        double t = v.top(f.below());
        if (t <= 0) return Double.NaN;
        return f.y() - 1 + t;
    }

    /** No collision box in column (x, z) between heights {@code lo} and {@code hi} (a box counted from its cell's bottom). */
    static boolean free(View v, int x, int z, double lo, double hi) {
        for (int y = (int) Math.floor(lo); y <= (int) Math.floor(hi - EPS); y++) {
            Pos p = new Pos(x, y, z);
            if (!v.loaded(p)) return false;
            double t = v.top(p);
            if (t <= 0) continue;
            if (y + t > lo + EPS) return false;
        }
        return true;
    }

    /** Some face of block {@code b} facing the eye can be seen within reach. */
    static boolean blockVisible(View v, double ex, double ey, double ez, Pos b, double reach) {
        for (int f = 0; f < 6; f++) if (faceVisible(v, ex, ey, ez, b, f, reach)) return true;
        return false;
    }

    /**
     * Face {@code face} of block {@code b} can be seen from the eye: the eye on its outer side, and the ray to one of
     * nine points of it (centre, edges and corners 0.15 in) within reach and through no other cell's outline (a
     * cell's outline counted over its whole footprint, from its bottom: conservative for slabs and wall signs).
     */
    static boolean faceVisible(View v, double ex, double ey, double ez, Pos b, int face, double reach) {
        if (!outside(ex, ey, ez, b, face)) return false;
        double[] uv = {0.5, 0.15, 0.85};
        for (double u : uv)
            for (double w : uv) {
                double[] p = facePoint(b, face, u, w);
                if (Aim.distSq(ex, ey, ez, p[0], p[1], p[2]) > reach * reach) continue;
                if (clearRay(v, ex, ey, ez, p[0], p[1], p[2], b)) return true;
            }
        return false;
    }

    private static boolean outside(double ex, double ey, double ez, Pos b, int face) {
        return switch (face) {
            case Aim.DOWN -> ey < b.y();
            case Aim.UP -> ey > b.y() + 1;
            case Aim.NORTH -> ez < b.z();
            case Aim.SOUTH -> ez > b.z() + 1;
            case Aim.WEST -> ex < b.x();
            default -> ex > b.x() + 1;
        };
    }

    private static double[] facePoint(Pos b, int face, double u, double w) {
        return switch (face) {
            case Aim.DOWN -> new double[]{b.x() + u, b.y(), b.z() + w};
            case Aim.UP -> new double[]{b.x() + u, b.y() + 1, b.z() + w};
            case Aim.NORTH -> new double[]{b.x() + u, b.y() + w, b.z()};
            case Aim.SOUTH -> new double[]{b.x() + u, b.y() + w, b.z() + 1};
            case Aim.WEST -> new double[]{b.x(), b.y() + w, b.z() + u};
            default -> new double[]{b.x() + 1, b.y() + w, b.z() + u};
        };
    }

    /** The segment from the eye to the point crosses no outline but the target's (sampled every 0.05). */
    private static boolean clearRay(View v, double ex, double ey, double ez, double px, double py, double pz, Pos target) {
        double len = Math.sqrt(Aim.distSq(ex, ey, ez, px, py, pz));
        int steps = Math.max(1, (int) Math.ceil(len / 0.05));
        for (int i = 1; i < steps; i++) {
            double k = (double) i / steps;
            double x = ex + (px - ex) * k;
            double y = ey + (py - ey) * k;
            double z = ez + (pz - ez) * k;
            Pos c = new Pos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (c.equals(target)) continue;
            double o = v.outline(c);
            if (o > 0 && y - c.y() < o) return false;
        }
        return true;
    }

    static String keys(List<Pos> ps) {
        List<String> out = new ArrayList<>();
        for (Pos p : ps) out.add(p.key());
        return String.join(" ", out);
    }
}
