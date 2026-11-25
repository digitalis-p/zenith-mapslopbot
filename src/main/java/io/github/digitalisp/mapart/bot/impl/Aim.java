package io.github.digitalisp.mapart.bot.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Pure aiming / geometry math (vanilla conventions): yaw 0 = south (+z), 90 = west (-x); pitch 90 = straight
 * down. Hit points are {@code double[]{x, y, z}} in world coordinates. No ZenithProxy types, unit-tested.
 */
public final class Aim {
    private Aim() { }

    /** Standing player collision box half width and height (vanilla). */
    public static final double PLAYER_HALF_WIDTH = 0.3;
    public static final double PLAYER_HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.62;
    /** Eye height while sneaking (vanilla crouching pose). */
    public static final double SNEAK_EYE_HEIGHT = 1.27;

    /** {yaw, pitch} in degrees to look from (sx,sy,sz) at (tx,ty,tz). Same formula as ZenithProxy RotationHelper. */
    public static float[] rotationTo(double sx, double sy, double sz, double tx, double ty, double tz) {
        double dx = tx - sx;
        double dy = ty - sy;
        double dz = tz - sz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double pitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{(float) wrapDegrees(yaw), (float) pitch};
    }

    /** Unit look vector for a rotation (vanilla Entity.calculateViewVector). */
    public static double[] viewVector(float yaw, float pitch) {
        double f = Math.toRadians(pitch);
        double g = Math.toRadians(-yaw);
        double cosF = Math.cos(f);
        return new double[]{Math.sin(g) * cosF, -Math.sin(f), Math.cos(g) * cosF};
    }

    public static double wrapDegrees(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    /** Absolute yaw difference in [0, 180]. */
    public static double yawDelta(double a, double b) {
        return Math.abs(wrapDegrees(a - b));
    }

    public static double distSq(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Candidate hit points on one face of the unit block at (bx,by,bz). {@code face} is the outward normal
     * ({dx,dy,dz}, exactly one non-zero). The first point is the inset point nearest to the eye, randomly jittered
     * by up to {@code jitter} (so consecutive placements never repeat a rotation delta exactly — Grim
     * DuplicateRotPlace), then the face centre, then the four inset corners nearest-first.
     */
    public static List<double[]> facePoints(double ex, double ey, double ez, int bx, int by, int bz, int[] face,
                                            double inset, double jitter, RandomGenerator rnd) {
        double lo = inset;
        double hi = 1.0 - inset;
        // u/v: the two in-plane axes; fixed: the plane coordinate
        int axis = face[0] != 0 ? 0 : face[1] != 0 ? 1 : 2;
        double[] base = {bx, by, bz};
        double plane = base[axis] + (face[axis] > 0 ? 1.0 : 0.0);
        double[] eye = {ex, ey, ez};
        int ua = axis == 0 ? 1 : 0;
        int va = axis == 2 ? 1 : 2;
        List<double[]> out = new ArrayList<>(6);

        double nu = clamp(eye[ua] - base[ua], lo, hi);
        double nv = clamp(eye[va] - base[va], lo, hi);
        if (jitter > 0) {
            nu = clamp(nu + (rnd.nextDouble() * 2 - 1) * jitter, lo, hi);
            nv = clamp(nv + (rnd.nextDouble() * 2 - 1) * jitter, lo, hi);
        }
        out.add(point(axis, plane, ua, base[ua] + nu, va, base[va] + nv));
        out.add(point(axis, plane, ua, base[ua] + 0.5, va, base[va] + 0.5));
        List<double[]> corners = new ArrayList<>(4);
        for (double cu : new double[]{lo, hi}) {
            for (double cv : new double[]{lo, hi}) {
                corners.add(point(axis, plane, ua, base[ua] + cu, va, base[va] + cv));
            }
        }
        corners.sort(Comparator.comparingDouble(p -> distSq(ex, ey, ez, p[0], p[1], p[2])));
        out.addAll(corners);
        return out;
    }

    /**
     * Candidate hit points on the top face of the support block (bx,by,bz), the click that places on the art layer.
     * Centre first: the face centre jittered by up to {@code jitter} (Grim DuplicateRotPlace), then the remaining
     * {@link #facePoints} order (jittered inset point nearest to the eye, exact centre, inset corners nearest-first)
     * as fallbacks when the centre is out of reach or hidden.
     *
     * <p>Why the centre: a ray from an eye h above the face to a point u blocks inside the face's near edge, at
     * horizontal distance d, passes the near edge at height h·u/d. It clears a placed carpet (1/16) on the
     * neighbouring cell only while u &gt; d/(16h), ≈ 0.039·d for h = 1.62. A 0.15 inset point fails past d ≈ 3.9
     * blocks, the centre (u ≥ 0.5 − jitter) stays clear within any legal reach.
     */
    public static List<double[]> topFacePoints(double ex, double ey, double ez, int bx, int by, int bz,
                                               double inset, double jitter, RandomGenerator rnd) {
        double cu = 0.5;
        double cv = 0.5;
        if (jitter > 0) {
            cu = clamp(cu + (rnd.nextDouble() * 2 - 1) * jitter, inset, 1.0 - inset);
            cv = clamp(cv + (rnd.nextDouble() * 2 - 1) * jitter, inset, 1.0 - inset);
        }
        List<double[]> out = new ArrayList<>(7);
        out.add(new double[]{bx + cu, by + 1.0, bz + cv});
        out.addAll(facePoints(ex, ey, ez, bx, by, bz, new int[]{0, 1, 0}, inset, jitter, rnd));
        return out;
    }

    /**
     * Height above the face plane at which the ray from an eye {@code eyeHeight} above the plane to a point
     * {@code inset} blocks inside the face's near edge crosses that edge, {@code horizontalDist} being the horizontal
     * eye-to-point distance (similar triangles). A neighbouring obstacle lower than this does not hide the point.
     */
    public static double rayHeightAtNearEdge(double eyeHeight, double horizontalDist, double inset) {
        return horizontalDist <= 0 ? eyeHeight : eyeHeight * inset / horizontalDist;
    }

    /**
     * Centres of the faces of a box that are visible from the eye (eye strictly outside the face plane),
     * nearest first, then the box centre as a last resort.
     */
    public static List<double[]> visibleFaceCentres(double ex, double ey, double ez, double minX, double minY,
                                                    double minZ, double maxX, double maxY, double maxZ) {
        double cx = (minX + maxX) / 2;
        double cy = (minY + maxY) / 2;
        double cz = (minZ + maxZ) / 2;
        List<double[]> out = new ArrayList<>(7);
        if (ey > maxY) out.add(new double[]{cx, maxY, cz});
        if (ey < minY) out.add(new double[]{cx, minY, cz});
        if (ex > maxX) out.add(new double[]{maxX, cy, cz});
        if (ex < minX) out.add(new double[]{minX, cy, cz});
        if (ez > maxZ) out.add(new double[]{cx, cy, maxZ});
        if (ez < minZ) out.add(new double[]{cx, cy, minZ});
        out.sort(Comparator.comparingDouble(p -> distSq(ex, ey, ez, p[0], p[1], p[2])));
        out.add(new double[]{cx, cy, cz});
        return out;
    }

    /**
     * An {@code n} x {@code n} grid of points (cell centres) on every face of the box that is turned to the eye (the
     * eye strictly beyond its plane), for a target of which only a strip is in sight.
     */
    public static List<double[]> visibleFaceGrid(double ex, double ey, double ez, double minX, double minY, double minZ,
                                                 double maxX, double maxY, double maxZ, int n) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double u = (i + 0.5) / n;
            for (int j = 0; j < n; j++) {
                double v = (j + 0.5) / n;
                double x = minX + (maxX - minX) * u, y = minY + (maxY - minY) * v, z = minZ + (maxZ - minZ) * v;
                double zu = minZ + (maxZ - minZ) * u;
                if (ey > maxY) out.add(new double[]{x, maxY, z});
                if (ey < minY) out.add(new double[]{x, minY, z});
                if (ex > maxX) out.add(new double[]{maxX, y, zu});
                if (ex < minX) out.add(new double[]{minX, y, zu});
                if (ez > maxZ) out.add(new double[]{x, y, maxZ});
                if (ez < minZ) out.add(new double[]{x, y, minZ});
            }
        }
        return out;
    }

    /** Index of the point nearest to the centroid of {@code pts}; -1 when empty. */
    public static int nearestToCentroid(List<double[]> pts) {
        if (pts.isEmpty()) return -1;
        double cx = 0, cy = 0, cz = 0;
        for (double[] p : pts) {
            cx += p[0];
            cy += p[1];
            cz += p[2];
        }
        cx /= pts.size();
        cy /= pts.size();
        cz /= pts.size();
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            double d = distSq(cx, cy, cz, pts.get(i)[0], pts.get(i)[1], pts.get(i)[2]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Face codes, in the order of ZenithProxy's {@code Direction}: DOWN, UP, NORTH, SOUTH, WEST, EAST. */
    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    /**
     * Centres of the faces of a box that face the eye (eye strictly outside the face plane), nearest first, as
     * {@code {x, y, z, face}} with a face code ({@link #DOWN} .. {@link #EAST}). No fallback point: a block is only
     * used through a face whose centre the eye can see.
     */
    public static List<double[]> faceCentres(double ex, double ey, double ez, double minX, double minY, double minZ,
                                             double maxX, double maxY, double maxZ) {
        double cx = (minX + maxX) / 2;
        double cy = (minY + maxY) / 2;
        double cz = (minZ + maxZ) / 2;
        List<double[]> out = new ArrayList<>(3);
        if (ey < minY) out.add(new double[]{cx, minY, cz, DOWN});
        if (ey > maxY) out.add(new double[]{cx, maxY, cz, UP});
        if (ez < minZ) out.add(new double[]{cx, cy, minZ, NORTH});
        if (ez > maxZ) out.add(new double[]{cx, cy, maxZ, SOUTH});
        if (ex < minX) out.add(new double[]{minX, cy, cz, WEST});
        if (ex > maxX) out.add(new double[]{maxX, cy, cz, EAST});
        out.sort(Comparator.comparingDouble(p -> distSq(ex, ey, ez, p[0], p[1], p[2])));
        return out;
    }

    /** Name of a face code for messages. */
    public static String faceName(int face) {
        return switch (face) {
            case DOWN -> "bottom";
            case UP -> "top";
            case NORTH -> "north";
            case SOUTH -> "south";
            case WEST -> "west";
            case EAST -> "east";
            default -> "face " + face;
        };
    }

    // ------------------------------------------------------------------------------- side-face clicks (LOOK cells)

    /**
     * Least pitch (degrees, looking down) of a side-face click ({@link TopFaceRules#sideFacePlaceable}): the look's
     * nearest vertical direction must be down (pointed dripstone then points up, a lantern stands), with a margin.
     */
    public static final float SIDE_MIN_PITCH = 1f;

    /**
     * Face points tried per face when a stand spot is judged for a side-face click: the inset point nearest to the eye
     * and the face centre. A face seen only at a far corner is lost to the bot's centring and the aim's jitter (sim: a
     * spot diagonal to a 1×1 hole placed nothing).
     */
    public static final int SIDE_SPOT_RAYS = 2;

    /** The four horizontal neighbours of a cell as {dx, dz}: east, west, south, north. */
    public static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Face code of the face of neighbour (cell + (dx, dz)) that looks at the cell: its outward normal is (−dx, −dz). */
    public static int sideFace(int dx, int dz) {
        if (dx == 1) return WEST;
        if (dx == -1) return EAST;
        if (dz == 1) return NORTH;
        return SOUTH;
    }

    /**
     * The eye (ex, ez) is outside the plane of the face of neighbour (cell (cx, cz) + (dx, dz)) that looks at the cell,
     * on the side a click on that face comes from (Grim PositionPlace), by at least {@code margin}.
     */
    public static boolean outsideSideFace(double ex, double ez, int cx, int cz, int dx, int dz, double margin) {
        if (dx == 1) return ex <= cx + 1 - margin;
        if (dx == -1) return ex >= cx + margin;
        if (dz == 1) return ez <= cz + 1 - margin;
        return ez >= cz + margin;
    }

    /** Player collision box at feet (x,y,z), grown by {@code inflate} horizontally: {minX,minY,minZ,maxX,maxY,maxZ}. */
    public static double[] playerBox(double x, double y, double z, double inflate) {
        double h = PLAYER_HALF_WIDTH + inflate;
        return new double[]{x - h, y, z - h, x + h, y + PLAYER_HEIGHT, z + h};
    }

    /** True if the unit cell at (cx,cy,cz) and the box overlap with positive volume (vanilla AABB.intersects). */
    public static boolean cellOverlaps(int cx, int cy, int cz, double[] box) {
        return cx < box[3] && cx + 1 > box[0]
            && cy < box[4] && cy + 1 > box[1]
            && cz < box[5] && cz + 1 > box[2];
    }

    /**
     * Movement keys that best move the player in world direction (dx, dz) while looking at {@code yaw}.
     * Returns {forward, strafe}: forward 1 = W, -1 = S; strafe 1 = A (left), -1 = D (right). {0,0} for no motion.
     * Uses the vanilla input-to-velocity transform: dx = strafe*cos - fwd*sin, dz = fwd*cos + strafe*sin.
     */
    public static int[] movementKeys(double yaw, double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) return new int[]{0, 0};
        dx /= len;
        dz /= len;
        double sin = Math.sin(Math.toRadians(yaw));
        double cos = Math.cos(Math.toRadians(yaw));
        int bestF = 0;
        int bestS = 0;
        double best = -2;
        for (int f = -1; f <= 1; f++) {
            for (int s = -1; s <= 1; s++) {
                if (f == 0 && s == 0) continue;
                double wx = s * cos - f * sin;
                double wz = f * cos + s * sin;
                double wl = Math.sqrt(wx * wx + wz * wz);
                double dot = (wx * dx + wz * dz) / wl;
                if (dot > best + 1e-9) {
                    best = dot;
                    bestF = f;
                    bestS = s;
                }
            }
        }
        return new int[]{bestF, bestS};
    }

    /** World motion direction produced by keys {forward, strafe} at {@code yaw} (not normalised). */
    public static double[] keysToWorld(double yaw, int forward, int strafe) {
        double sin = Math.sin(Math.toRadians(yaw));
        double cos = Math.cos(Math.toRadians(yaw));
        return new double[]{strafe * cos - forward * sin, forward * cos + strafe * sin};
    }

    /** use-item-on cursor: hit point relative to the clicked block, clamped to [0, 1] per axis. */
    public static float[] cursor(double hx, double hy, double hz, int bx, int by, int bz) {
        return new float[]{
            (float) clamp(hx - bx, 0, 1),
            (float) clamp(hy - by, 0, 1),
            (float) clamp(hz - bz, 0, 1)};
    }

    /**
     * {dx, dz} from one half of a double chest to the other (vanilla ChestBlock.getConnectedDirection: type=left →
     * facing turned clockwise, right → counter-clockwise), or null for a single chest / unknown values.
     */
    public static int[] chestNeighbour(String facing, String type) {
        if (facing == null || type == null || type.equals("single")) return null;
        boolean left = type.equals("left");
        return switch (facing) {
            case "north" -> left ? new int[]{1, 0} : new int[]{-1, 0};
            case "east" -> left ? new int[]{0, 1} : new int[]{0, -1};
            case "south" -> left ? new int[]{-1, 0} : new int[]{1, 0};
            case "west" -> left ? new int[]{0, -1} : new int[]{0, 1};
            default -> null;
        };
    }

    /** The angle equivalent to {@code target} (mod 360) that is closest to {@code current}. */
    public static float nearestYaw(float current, float target) {
        double d = (target - current) % 360.0;
        if (d > 180) d -= 360;
        else if (d < -180) d += 360;
        return (float) (current + d);
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** A minecart's box: 0.98 wide and 0.7 high, its feet (the entity position) at the middle of the bottom. */
    public static final double CART_HALF_WIDTH = 0.49;
    public static final double CART_HEIGHT = 0.7;
    /** A click at the nearest point of a cart's box lies this far inside the box. */
    public static final double CART_INSET = 0.1;

    /**
     * The point of the box of a minecart at (cx, cy, cz) nearest the eye, {@link #CART_INSET} inside the box: where a
     * click from the edge of the reach aims (the server measures to the box; the middle of the box is up to 0.6 blocks
     * farther).
     */
    public static double[] cartNearestPoint(double ex, double ey, double ez, double cx, double cy, double cz) {
        double half = CART_HALF_WIDTH - CART_INSET;
        return new double[]{clamp(ex, cx - half, cx + half), clamp(ey, cy + CART_INSET, cy + CART_HEIGHT - CART_INSET),
            clamp(ez, cz - half, cz + half)};
    }

    /**
     * The feet point {x, z} within the block column (cellX, cellZ) nearest to (tx, tz) that keeps the whole player box
     * inside the column: the bot may stand anywhere in a cell after a walk (its centre a hair inside the edge it came
     * over), which costs up to 0.7 blocks of reach along each axis.
     */
    public static double[] nearestInCell(int cellX, int cellZ, double tx, double tz) {
        return new double[]{clamp(tx, cellX + PLAYER_HALF_WIDTH, cellX + 1 - PLAYER_HALF_WIDTH),
            clamp(tz, cellZ + PLAYER_HALF_WIDTH, cellZ + 1 - PLAYER_HALF_WIDTH)};
    }

    private static double[] point(int axis, double plane, int ua, double u, int va, double v) {
        double[] p = new double[3];
        p[axis] = plane;
        p[ua] = u;
        p[va] = v;
        return p;
    }
}
