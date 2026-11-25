package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * A load that goes to far storage for an item fills that item there ({@code build.farLoadFill}), leaving the near
 * containers' stock for the next loads. Whether the load goes far is decided as
 * without it ({@link #goesFar}: today's split, near containers first). The split with the fill ({@link #farFirst}):
 * far storage gives all the cells the load serves ({@link FarStorage#farCap}), the near containers what far storage
 * did not have. Those near chests are then not opened by this load, so their snapshots do not show what the sorter
 * puts back: the engine looks at them again before the next load goes far for the item. Pure; the engine supplies
 * order and stock.
 *
 * <p>Live j0015 (2026-10-23, soul sand): 1.5 far trips a map for gray carpet, each about 73 s more than a near load.
 * Once the near chests ran dry, every load took the sorter's few hundred and topped up from the moss sorter
 * ([4,0]: 5 far trips for 496, 1250, 743, 305 and 1006 gray, about 2.4 min apart). With the fill a far load takes the
 * lot there, the sorter's chests fill up meanwhile, and the next load needs less or no far trip.
 */
public final class FarLoadFill {
    private FarLoadFill() { }

    /**
     * Today's split ({@code want} of one item over {@code chests} in withdrawal order, far containers capped by
     * {@link FarStorage#farCap}) takes some from a far container: the load goes far for this item.
     */
    public static boolean goesFar(int want, List<Pos> chests, Predicate<Pos> far, ToIntFunction<Pos> stock, int serve,
                                  int held) {
        int left = want;
        int taken = 0;
        for (Pos p : chests) {
            if (left <= 0) return false;
            int take = Math.min(left, Math.max(0, stock.applyAsInt(p)));
            boolean isFar = far.test(p);
            if (isFar) take = Math.min(take, FarStorage.farCap(serve, held, taken));
            if (take <= 0) continue;
            if (isFar) return true;
            left -= take;
            taken += take;
        }
        return false;
    }

    /** The split with the fill: the far containers first, then the near ones; each part in the order of {@code chests}. */
    public static List<Pos> farFirst(List<Pos> chests, Predicate<Pos> far) {
        List<Pos> out = new ArrayList<>(chests.size());
        for (Pos p : chests) if (far.test(p)) out.add(p);
        for (Pos p : chests) if (!far.test(p)) out.add(p);
        return out;
    }
}
