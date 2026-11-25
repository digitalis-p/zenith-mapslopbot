package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.player.World;
import com.zenith.feature.player.raycast.BlockRaycastResult;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.mc.block.Direction;
import com.zenith.mc.block.LocalizedCollisionBox;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.zenith.Globals.BOT;

/**
 * Line-of-sight aiming with ZenithProxy's real block raycast ({@code blockRaycastFromPos}, the same ray
 * {@code playerBlockRaycast} uses — never the through-walls variant). A {@link Shot} is only produced when the
 * ray from the bot's eye with the exact rotation we will send hits the wanted block (and face) within reach.
 */
final class Aiming {
    private Aiming() { }

    /** Rotation to send plus the ray it produces. */
    record Shot(float yaw, float pitch, BlockRaycastResult ray) implements PlacePipeline.Rotation { }

    static double eyeX() {
        return BOT.getX();
    }

    static double eyeY() {
        return BOT.getEyeY();
    }

    static double eyeZ() {
        return BOT.getZ();
    }

    /**
     * First point whose ray hits block (x,y,z) — on {@code face} if non-null — within {@code reach}.
     * Points farther than reach are skipped without raycasting.
     */
    static @Nullable Shot shoot(int x, int y, int z, @Nullable Direction face, List<double[]> points, double reach,
                                int maxRays) {
        return shootFrom(eyeX(), eyeY(), eyeZ(), x, y, z, face, points, reach, maxRays);
    }

    /** {@link #shoot} from an eye at (ex, ey, ez) (the bot's own eye, or where it would be on a stand spot). */
    static @Nullable Shot shootFrom(double ex, double ey, double ez, int x, int y, int z, @Nullable Direction face,
                                    List<double[]> points, double reach, int maxRays) {
        double reachSq = reach * reach;
        int rays = 0;
        for (double[] p : points) {
            if (Aim.distSq(ex, ey, ez, p[0], p[1], p[2]) > reachSq) continue;
            if (rays++ >= maxRays) break;
            float[] rot = Aim.rotationTo(ex, ey, ez, p[0], p[1], p[2]);
            float pitch = Clicker.effectivePitch(rot[1]);
            BlockRaycastResult ray = RaycastHelper.blockRaycastFromPos(ex, ey, ez, rot[0], pitch, reach, false);
            if (!ray.hit() || ray.x() != x || ray.y() != y || ray.z() != z) continue;
            if (face != null && ray.direction() != face) continue;
            if (ray.intersection() == null) continue;
            if (Aim.distSq(ex, ey, ez, ray.intersection().x(), ray.intersection().y(), ray.intersection().z()) > reachSq) {
                continue;
            }
            return new Shot(rot[0], pitch, ray);
        }
        return null;
    }

    /**
     * The given rotation's ray, if it still hits block (x,y,z) — on {@code face} if non-null — within reach from the
     * bot's eye now (used to click with the rotation sent on the previous tick).
     */
    static @Nullable Shot check(int x, int y, int z, @Nullable Direction face, float yaw, float pitch, double reach) {
        double ex = eyeX();
        double ey = eyeY();
        double ez = eyeZ();
        BlockRaycastResult ray = RaycastHelper.blockRaycastFromPos(ex, ey, ez, yaw, pitch, reach, false);
        if (!ray.hit() || ray.x() != x || ray.y() != y || ray.z() != z) return null;
        if (face != null && ray.direction() != face) return null;
        var hit = ray.intersection();
        if (hit == null || Aim.distSq(ex, ey, ez, hit.x(), hit.y(), hit.z()) > reach * reach) return null;
        return new Shot(yaw, pitch, ray);
    }

    /**
     * Shot at the top face of the support block (x,y,z) — the click that places on the art layer. The jittered face
     * centre first; the other points only if it is out of reach or hidden ({@link Aim#topFacePoints}).
     */
    static @Nullable Shot topFace(int x, int y, int z, double reach) {
        List<double[]> pts = Aim.topFacePoints(eyeX(), eyeY(), eyeZ(), x, y, z, 0.15, 0.12, ThreadLocalRandom.current());
        return shoot(x, y, z, Direction.UP, pts, reach, 4);
    }

    /**
     * True if the top face of support block (x,y,z) could be hit within reach from an eye at (ex, ey, ez): a stand spot
     * the bot is not on yet. Same candidate points and raycast as {@link #topFace}, without jitter.
     */
    static boolean topFaceVisibleFrom(double ex, double ey, double ez, int x, int y, int z, double reach) {
        List<double[]> pts = Aim.topFacePoints(ex, ey, ez, x, y, z, 0.15, 0, ThreadLocalRandom.current());
        return shootFrom(ex, ey, ez, x, y, z, Direction.UP, pts, reach, 7) != null;
    }

    /**
     * The rotation in effect now (ZenithProxy's Bot yaw/pitch, i.e. the one sent with the last movement packet),
     * if its ray still hits the top face of support block (x,y,z) within reach from the eye now.
     */
    static @Nullable Shot topFaceNow(int x, int y, int z, double reach) {
        return check(x, y, z, Direction.UP, BOT.getYaw(), BOT.getPitch(), reach);
    }

    /**
     * Shot at the side face of a horizontal neighbour of cell (x, y, z) that looks at the cell (the block placed by the
     * click goes into the cell), from the bot's eye now: {@link #sideFaceFrom}, the points jittered.
     */
    static @Nullable Shot sideFace(int x, int y, int z, double reach) {
        return sideFaceFrom(eyeX(), eyeY(), eyeZ(), x, y, z, reach, 0.12, 4);
    }

    /**
     * {@link #sideFace} could hit from an eye at (ex, ey, ez) (a stand spot), without jitter, through the face's point
     * nearest to the eye or its centre ({@link Aim#SIDE_SPOT_RAYS}).
     */
    static boolean sideFaceVisibleFrom(double ex, double ey, double ez, int x, int y, int z, double reach) {
        return sideFaceFrom(ex, ey, ez, x, y, z, reach, 0, Aim.SIDE_SPOT_RAYS) != null;
    }

    /**
     * Side-face shot for cell (x, y, z) from an eye at (ex, ey, ez):
     * over the four horizontal neighbours that are {@link BlockStates#sideClickable} and whose face towards the
     * cell is turned to the eye (the eye outside its plane by 0.05: Grim PositionPlace), nearest face centre first,
     * the face points of {@link Aim#facePoints} (the inset point nearest to the eye first: the upper part of the face,
     * which is what an eye above a wall sees) whose look is pitched down at least {@link Aim#SIDE_MIN_PITCH}; the first
     * ray that hits that neighbour on that face within reach. {@code maxRays} per face.
     */
    private static @Nullable Shot sideFaceFrom(double ex, double ey, double ez, int x, int y, int z, double reach,
                                              double jitter, int maxRays) {
        List<int[]> sides = new ArrayList<>(4);
        for (int[] d : Aim.SIDES) {
            if (!Aim.outsideSideFace(ex, ez, x, z, d[0], d[1], 0.05)) continue;
            if (!BlockStates.sideClickable(World.getBlockStateId(x + d[0], y, z + d[1]))) continue;
            sides.add(d);
        }
        sides.sort(java.util.Comparator.comparingDouble(d -> Aim.distSq(ex, ey, ez,
            x + 0.5 + d[0] * 0.5, y + 0.5, z + 0.5 + d[1] * 0.5)));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double reachSq = reach * reach;
        for (int[] d : sides) {
            int nx = x + d[0];
            int nz = z + d[1];
            int code = Aim.sideFace(d[0], d[1]);
            Direction face = Direction.values()[code];
            List<double[]> pts = Aim.facePoints(ex, ey, ez, nx, y, nz, new int[]{-d[0], 0, -d[1]}, 0.15, jitter, rnd);
            int rays = 0;
            for (double[] p : pts) {
                if (Aim.distSq(ex, ey, ez, p[0], p[1], p[2]) > reachSq) continue;
                float[] rot = Aim.rotationTo(ex, ey, ez, p[0], p[1], p[2]);
                if (rot[1] < Aim.SIDE_MIN_PITCH) continue;
                if (rays++ >= maxRays) break;
                Shot s = shootFrom(ex, ey, ez, nx, y, nz, face, List.of(p), reach, 1);
                if (s != null && s.pitch() >= Aim.SIDE_MIN_PITCH) return s;
            }
        }
        return null;
    }

    /**
     * The rotation (yaw, pitch) still hits, from the bot's eye now, the side face of a horizontal neighbour of cell
     * (x, y, z) that looks at the cell, within reach, pitched down at least {@link Aim#SIDE_MIN_PITCH}, the neighbour
     * {@link BlockStates#sideClickable} (the click the pipeline sends after the rotation went out).
     */
    static @Nullable Shot checkSide(int x, int y, int z, float yaw, float pitch, double reach) {
        if (pitch < Aim.SIDE_MIN_PITCH) return null;
        double ex = eyeX();
        double ey = eyeY();
        double ez = eyeZ();
        BlockRaycastResult ray = RaycastHelper.blockRaycastFromPos(ex, ey, ez, yaw, pitch, reach, false);
        if (!ray.hit() || ray.y() != y) return null;
        int dx = ray.x() - x;
        int dz = ray.z() - z;
        if (Math.abs(dx) + Math.abs(dz) != 1 || ray.direction() != Direction.values()[Aim.sideFace(dx, dz)]) return null;
        var hit = ray.intersection();
        if (hit == null || Aim.distSq(ex, ey, ez, hit.x(), hit.y(), hit.z()) > reach * reach) return null;
        if (!BlockStates.sideClickable(World.getBlockStateId(ray.x(), ray.y(), ray.z()))) return null;
        return new Shot(yaw, pitch, ray);
    }

    /** Shot at a given face of a full block. */
    static @Nullable Shot face(int x, int y, int z, Direction face, double reach) {
        List<double[]> pts = Aim.facePoints(eyeX(), eyeY(), eyeZ(), x, y, z, new int[]{face.x(), face.y(), face.z()},
            0.15, 0.12, ThreadLocalRandom.current());
        return shoot(x, y, z, face, pts, reach, 4);
    }

    /** Shot at any visible face of the block's interaction shape (containers, buttons, levers, blocks to mine). */
    static @Nullable Shot anyFace(int x, int y, int z, double reach) {
        var state = World.getBlockState(x, y, z);
        List<LocalizedCollisionBox> boxes = state.getLocalizedInteractionBoxes();
        List<double[]> pts = new ArrayList<>();
        double ex = eyeX();
        double ey = eyeY();
        double ez = eyeZ();
        if (boxes.isEmpty()) {
            pts.addAll(Aim.visibleFaceCentres(ex, ey, ez, x, y, z, x + 1, y + 1, z + 1));
        } else {
            for (LocalizedCollisionBox b : boxes) {
                pts.addAll(Aim.visibleFaceCentres(ex, ey, ez, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()));
            }
            pts.sort(java.util.Comparator.comparingDouble(p -> Aim.distSq(ex, ey, ez, p[0], p[1], p[2])));
        }
        return shoot(x, y, z, null, pts, reach, 7);
    }

    /** Points per edge of a face in {@link #anyFaceFine}. */
    static final int FINE_GRID = 16;

    /**
     * {@link #anyFace} when no face centre is in sight (a button all but hidden behind the block it is on, seen from a
     * minecart seat beside that block): every face of the interaction shape turned to the eye is sampled on a
     * {@link #FINE_GRID} x {@link #FINE_GRID} grid, and of the points whose ray hits the block within reach the one
     * nearest to their centroid is the shot (the middle of the strip in sight, not its edge). Null when none hits. At
     * most 3 faces per box are turned to an eye: about 770 short rays, once per press.
     */
    static @Nullable Shot anyFaceFine(int x, int y, int z, double reach) {
        var state = World.getBlockState(x, y, z);
        List<LocalizedCollisionBox> boxes = state.getLocalizedInteractionBoxes();
        double ex = eyeX();
        double ey = eyeY();
        double ez = eyeZ();
        List<double[]> pts = new ArrayList<>();
        if (boxes.isEmpty()) pts.addAll(Aim.visibleFaceGrid(ex, ey, ez, x, y, z, x + 1, y + 1, z + 1, FINE_GRID));
        else for (LocalizedCollisionBox b : boxes)
            pts.addAll(Aim.visibleFaceGrid(ex, ey, ez, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), FINE_GRID));
        List<double[]> hitPts = new ArrayList<>();
        List<Shot> hits = new ArrayList<>();
        for (double[] p : pts) {
            Shot s = shootFrom(ex, ey, ez, x, y, z, null, List.of(p), reach, 1);
            if (s == null) continue;
            hitPts.add(p);
            hits.add(s);
        }
        int best = Aim.nearestToCentroid(hitPts);
        return best < 0 ? null : hits.get(best);
    }

    /**
     * Shot at the point of a full block's faces nearest to the eye, for breaking (the fast nuker): on each face turned
     * towards the eye (the eye strictly beyond its plane, which is what Grim PositionBreakA accepts) the inset point
     * nearest to the eye, slightly jittered, then that face's centre; nearest first, at most 4 rays. Reach is counted to
     * the point aimed at, so a block at feet level is in reach up to about half a block farther than through its face
     * centre (the server counts to the block's box, Grim FarBreak too). Null when none hits the block within reach (a
     * block that is not a full cube usually: then {@link #anyFace}).
     */
    static @Nullable Shot nearestFace(int x, int y, int z, double reach) {
        return nearestFaceFrom(eyeX(), eyeY(), eyeZ(), x, y, z, reach);
    }

    /**
     * {@link #nearestFace} from an eye at (ex, ey, ez): where the bot's eye will be after this tick's movement, which the
     * rotation goes out with (the fast nuke mining on the move, {@code build.nukeMineMoving}).
     */
    static @Nullable Shot nearestFaceFrom(double ex, double ey, double ez, int x, int y, int z, double reach) {
        int[][] normals = {{0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        boolean[] turned = {ey > y + 1, ey < y, ez < z, ez > z + 1, ex < x, ex > x + 1};
        List<double[]> pts = new ArrayList<>(6);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < normals.length; i++) {
            if (!turned[i]) continue;
            List<double[]> fp = Aim.facePoints(ex, ey, ez, x, y, z, normals[i], 0.06, 0.04, rnd);
            pts.add(fp.get(0));
            pts.add(fp.get(1));
        }
        pts.sort(java.util.Comparator.comparingDouble(p -> Aim.distSq(ex, ey, ez, p[0], p[1], p[2])));
        return shootFrom(ex, ey, ez, x, y, z, null, pts, reach, 4);
    }

    /**
     * Shot at the centre of a face of one of {@code targets} (their interaction shapes, which is also what the raycast
     * hits; the unit cube for a block without one) from an eye at (ex, ey, ez): the ray to that centre hits that block
     * on that face within reach. Every block with an interaction shape blocks the ray, wall signs included (they have
     * one, 2/16 thick, though no collision). Nearest face centre first; no other point of a face is used.
     * {@code ownEye}: the eye is the bot's and the rotation will be sent, so the pitch is the one ZenithProxy sends
     * ({@link Clicker#effectivePitch}); false for a stand spot the bot is not on yet.
     */
    static @Nullable Shot faceCentre(double ex, double ey, double ez, List<Pos> targets, double reach, boolean ownEye) {
        return faceCentre(ex, ey, ez, targets, reach, ownEye, null);
    }

    /** {@link #faceCentre}, only on the face {@code only} names for each target (null: any face). */
    static @Nullable Shot faceCentre(double ex, double ey, double ez, List<Pos> targets, double reach, boolean ownEye,
                                     @Nullable Map<Pos, Direction> only) {
        double reachSq = reach * reach;
        for (double[] p : faceCentres(ex, ey, ez, targets, only)) {
            if (Aim.distSq(ex, ey, ez, p[0], p[1], p[2]) > reachSq) break; // nearest first
            Pos t = targets.get((int) p[4]);
            Direction face = Direction.values()[(int) p[3]];
            float[] rot = Aim.rotationTo(ex, ey, ez, p[0], p[1], p[2]);
            float pitch = ownEye ? Clicker.effectivePitch(rot[1]) : rot[1];
            BlockRaycastResult ray = RaycastHelper.blockRaycastFromPos(ex, ey, ez, rot[0], pitch, reach, false);
            if (!ray.hit() || ray.x() != t.x() || ray.y() != t.y() || ray.z() != t.z() || ray.direction() != face) continue;
            var hit = ray.intersection();
            if (hit == null || Aim.distSq(ex, ey, ez, hit.x(), hit.y(), hit.z()) > reachSq) continue;
            return new Shot(rot[0], pitch, ray);
        }
        return null;
    }

    /** Why no face centre of {@code targets} is usable from an eye at (ex, ey, ez), for log lines. */
    static String hiddenFrom(double ex, double ey, double ez, List<Pos> targets, double reach) {
        return hiddenFrom(ex, ey, ez, targets, reach, null);
    }

    /** {@link #hiddenFrom}, only on the face {@code only} names for each target (null: any face). */
    static String hiddenFrom(double ex, double ey, double ez, List<Pos> targets, double reach,
                             @Nullable Map<Pos, Direction> only) {
        List<double[]> pts = faceCentres(ex, ey, ez, targets, only);
        if (pts.isEmpty()) return "no face turned towards the eye";
        double[] p = pts.getFirst();
        Pos t = targets.get((int) p[4]);
        String face = Aim.faceName((int) p[3]) + " face center";
        double d = Math.sqrt(Aim.distSq(ex, ey, ez, p[0], p[1], p[2]));
        if (d > reach) return String.format(Locale.ROOT, "the nearest %s is %.2f away, reach %.2f", face, d, reach);
        float[] rot = Aim.rotationTo(ex, ey, ez, p[0], p[1], p[2]);
        BlockRaycastResult ray = RaycastHelper.blockRaycastFromPos(ex, ey, ez, rot[0], rot[1], reach, false);
        if (!ray.hit()) return "the ray to the " + face + " hits nothing";
        Pos hit = new Pos(ray.x(), ray.y(), ray.z());
        if (!hit.equals(t)) {
            return BlockStates.spec(World.getBlockStateId(hit.x(), hit.y(), hit.z())).id() + " at " + hit + " hides the " + face;
        }
        return "the " + face + " is covered by the block's own shape";
    }

    /**
     * Face centres of every target turned towards the eye, nearest first: {x, y, z, face code, target index}. With
     * {@code only}, just the named face of each target.
     */
    private static List<double[]> faceCentres(double ex, double ey, double ez, List<Pos> targets,
                                              @Nullable Map<Pos, Direction> only) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Pos t = targets.get(i);
            Direction want = only == null ? null : only.get(t);
            List<LocalizedCollisionBox> boxes = World.getBlockState(t.x(), t.y(), t.z()).getLocalizedInteractionBoxes();
            List<double[]> cs = new ArrayList<>();
            if (boxes.isEmpty()) {
                cs.addAll(Aim.faceCentres(ex, ey, ez, t.x(), t.y(), t.z(), t.x() + 1, t.y() + 1, t.z() + 1));
            } else {
                for (LocalizedCollisionBox b : boxes) {
                    cs.addAll(Aim.faceCentres(ex, ey, ez, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()));
                }
            }
            for (double[] c : cs) {
                if (want == null || (int) c[3] == want.ordinal()) out.add(new double[]{c[0], c[1], c[2], c[3], i});
            }
        }
        out.sort(java.util.Comparator.comparingDouble(p -> Aim.distSq(ex, ey, ez, p[0], p[1], p[2])));
        return out;
    }

    /** Eye-to-nearest-point distance check for the unit cell (cheap pre-filter before raycasting). */
    static boolean cellInReach(int x, int y, int z, double reach) {
        double ex = eyeX();
        double ey = eyeY();
        double ez = eyeZ();
        double nx = Aim.clamp(ex, x, x + 1.0);
        double ny = Aim.clamp(ey, y, y + 1.0);
        double nz = Aim.clamp(ez, z, z + 1.0);
        return Aim.distSq(ex, ey, ez, nx, ny, nz) <= reach * reach;
    }
}
