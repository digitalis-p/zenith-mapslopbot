package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Both levels of a colour-suppression layer in one sweep ({@code build.suppressionMergeLevels},
 * docs/COLOR_SUPPRESSION.md): the lanes of the level-0 pass, planned over the cells of both levels,
 * the level-1 cells as raised cells one block above the lanes' height. No column of a layer holds both levels; a column
 * that does (an art the export check refused) keeps its level-1 cell for the level-1 pass. The planner's order holds
 * for both: row by row in the walking direction, nearest the walking line first, so a restock cut of a lane
 * ({@link RestockLoads#cut}) is a run of whole rows at both levels. Pure.
 */
public final class LevelMerge {
    private LevelMerge() {
    }

    /**
     * The lanes of {@code level0} on {@code geo0} with the cells of {@code level1} (one block higher, same columns and
     * rows) still to place: {@code done0} for a level-0 cell and {@code done1} for a raised cell (its own position)
     * leave a cell out, as the planner's predicate does. With no raised cell left this is the planner's plan of
     * {@code level0} alone.
     */
    public static List<Lane> lanes(BuildPlanner planner, TileArt level0, TileArt level1, PlatformGeometry geo0,
                                   BlockCatalog catalog, int halfWidth, Predicate<Placement> done0,
                                   Predicate<Placement> done1) {
        TileArt both = new TileArt();
        boolean[] raised = new boolean[TileArt.SIZE * TileArt.SIZE];
        for (int pz = 0; pz < TileArt.SIZE; pz++) {
            for (int px = 0; px < TileArt.SIZE; px++) {
                BlockSpec low = level0.get(px, pz);
                BlockSpec high = level1.get(px, pz);
                if (low != null) both.set(px, pz, low);
                else if (high != null) {
                    both.set(px, pz, high);
                    raised[pz * TileArt.SIZE + px] = true;
                }
            }
        }
        List<Lane> planned = planner.plan(both, geo0, catalog, halfWidth,
            p -> raisedAt(raised, geo0, p.pos()) ? done1.test(up(p)) : done0.test(p));
        List<Lane> out = new ArrayList<>(planned.size());
        for (Lane l : planned) {
            List<Placement> ps = new ArrayList<>(l.placements().size());
            boolean any = false;
            for (Placement p : l.placements()) {
                if (raisedAt(raised, geo0, p.pos())) {
                    ps.add(up(p));
                    any = true;
                } else {
                    ps.add(p);
                }
            }
            out.add(any ? new Lane(l.index(), l.from(), l.to(), List.copyOf(ps)) : l);
        }
        return List.copyOf(out);
    }

    /** The cells of {@code lanes} one above the lanes' height (raised cells). */
    public static int raisedCells(List<Lane> lanes) {
        int n = 0;
        for (Lane l : lanes) for (Placement p : l.placements()) if (p.pos().y() == l.from().y() + 1) n++;
        return n;
    }

    private static boolean raisedAt(boolean[] raised, PlatformGeometry geo, Pos p) {
        int px = p.x() - geo.minX();
        int pz = p.z() - geo.minZ();
        return px >= 0 && pz >= 0 && px < TileArt.SIZE && pz < TileArt.SIZE && raised[pz * TileArt.SIZE + px];
    }

    private static Placement up(Placement p) {
        return new Placement(p.pos().above(), p.block(), p.itemId());
    }
}
