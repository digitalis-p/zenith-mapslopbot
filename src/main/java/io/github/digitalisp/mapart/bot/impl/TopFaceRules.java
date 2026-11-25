package io.github.digitalisp.mapart.bot.impl;

import io.github.digitalisp.mapart.model.BlockSpec;

import java.util.Map;
import java.util.Set;

/**
 * Pure rules for "does right-clicking the top face of the block below produce exactly this state?" and for
 * comparing a placed state with a target state. ZenithProxy 3.7.2 ships no default-state data, so the vanilla
 * default value of each property that a top-face click can leave at its default is listed here, plus the values a
 * top-face click sets explicitly (axis=y, type=bottom, half=bottom, down=true for lichen-like blocks).
 * Any other property (facing, rotation, age, ...) depends on the player's orientation or on growth, so the
 * state is reported as not placeable.
 */
public final class TopFaceRules {
    private TopFaceRules() { }

    private static final Set<String> SIDES = Set.of("north", "east", "south", "west", "up");

    /**
     * Huge mushroom blocks: six face booleans that the server sets to false wherever the block touches the same
     * block (placement and neighbour updates), so they say nothing about how the block was placed. Not multiface.
     */
    private static final Set<String> HUGE_MUSHROOMS = Set.of("mushroom_stem", "brown_mushroom_block", "red_mushroom_block");
    private static final Set<String> FACES = Set.of("north", "east", "south", "west", "up", "down");

    /** Properties set from the player's look direction; the map colour never depends on them. */
    private static final Set<String> LOOK = Set.of("facing", "rotation");

    /** property → the single value a top-face placement yields (on a flat platform with no special neighbours). */
    private static final Map<String, String> PLACED_VALUE = Map.ofEntries(
        Map.entry("axis", "y"),
        Map.entry("type", "bottom"),
        Map.entry("half", "bottom"),
        Map.entry("waterlogged", "false"),
        Map.entry("persistent", "true"),
        Map.entry("distance", "7"),
        Map.entry("candles", "1"),
        Map.entry("lit", "false"),
        Map.entry("snowy", "false"),
        Map.entry("powered", "false"),
        Map.entry("power", "0"),
        Map.entry("layers", "1"),
        Map.entry("hanging", "false"),
        Map.entry("thickness", "tip"),
        Map.entry("vertical_direction", "up"),
        Map.entry("cracked", "false"),
        Map.entry("bottom", "true"),
        Map.entry("flower_amount", "1"),
        Map.entry("unstable", "false"));

    /**
     * Properties the server recomputes from neighbours after placement, so a different world value is not a
     * wrong block: leaf {@code distance} (logs nearby), pale moss carpet side walls (full blocks next to it) and
     * huge mushroom block faces (false where the same block touches it).
     */
    public static boolean neighbourDependent(String blockPath, String property) {
        if (property.equals("distance")) return blockPath.endsWith("_leaves");
        if (blockPath.equals("pale_moss_carpet")) return SIDES.contains(property);
        if (HUGE_MUSHROOMS.contains(blockPath)) return FACES.contains(property);
        return false;
    }

    /** True if every property of {@code spec} has the value a top-face click yields. Ids are not checked here. */
    public static boolean placeableOnTopFace(BlockSpec spec) {
        return problem(spec) == null;
    }

    /**
     * Why a click on the top face of the block below cannot produce {@code spec}, naming the first offending
     * property (for upload messages), or null if it can. Leaf {@code distance} and huge mushroom faces are
     * accepted with any value (the server recomputes them; {@link #equivalent} ignores them).
     */
    public static String problem(BlockSpec spec) {
        String path = ItemIds.path(spec.id());
        Map<String, String> props = spec.props();
        boolean mushroom = HUGE_MUSHROOMS.contains(path);
        boolean multiface = props.containsKey("down") && !mushroom;
        for (var e : props.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (mushroom && FACES.contains(k)) continue;
            if (k.equals("distance") && path.endsWith("_leaves")) continue;
            if (multiface && k.equals("down")) {
                if (!v.equals("true")) return "down=" + v + ": a top-face click attaches it to the floor (down=true)";
                continue;
            }
            if (multiface && SIDES.contains(k)) {
                if (!v.equals("false")) return k + "=" + v + ": a top-face click covers only the floor face";
                continue;
            }
            if (SIDES.contains(k)) {
                // wall-type sides (pale_moss_carpet): "none" on an open floor; fence-like booleans are not allowed
                if (!v.equals("none")) return k + "=" + v + ": depends on the neighboring blocks";
                continue;
            }
            String want = PLACED_VALUE.get(k);
            if (want != null && want.equals(v)) continue;
            return reason(k, v, want);
        }
        return null;
    }

    private static String reason(String k, String v, String want) {
        String kv = k + "=" + v;
        if (LOOK.contains(k)) return kv + ": " + k + " follows the direction the bot looks, which v1 does not control";
        if (k.equals("waterlogged")) return kv + ": needs water; the platform is dry";
        if (k.equals("persistent")) return kv + ": placed leaves are always persistent=true (non-persistent leaves decay)";
        if (k.equals("flower_amount") || k.equals("candles") || k.equals("layers"))
            return kv + ": needs several clicks on the same block; v1 clicks once";
        if (want == null) return kv + ": a top-face click cannot set " + k;
        return kv + ": a top-face click gives " + k + "=" + want;
    }

    /** Multiface blocks: placed into an empty cell they attach to the first face, in look order, they can stick to. */
    private static final Set<String> MULTIFACE = Set.of("glow_lichen", "sculk_vein", "resin_clump");
    /**
     * Items that place a different block or state depending on the look when the clicked cell is the target itself
     * (vanilla BlockPlaceContext with replaceClicked true orders the faces by the look, not by the clicked face):
     * torches and coral fans (standing or wall form, StandingAndWallBlockItem), lanterns (hanging), pointed
     * dripstone (vertical_direction from the pitch).
     */
    private static final Set<String> LOOK_PLACED = Set.of("torch", "soul_torch", "redstone_torch", "copper_torch",
        "lantern", "soul_lantern", "pointed_dripstone");
    /** Properties whose placed value follows the look in an airplace click. */
    private static final Set<String> LOOK_PROPS = Set.of("hanging", "vertical_direction", "thickness");

    /**
     * Placement method for a target ({@code build.placeMode}). LOOK mode:
     * always {@link PlaceMethod#LOOK}. AIRPLACE mode: {@link PlaceMethod#AIRPLACE} unless {@link #airPlaceProblem}
     * names a reason, then LOOK (its top-face click fixes the state: the clicked face's opposite, DOWN, comes first).
     */
    public static PlaceMethod placeMethod(BlockSpec spec, PlaceMode mode) {
        if (mode != PlaceMode.AIRPLACE) return PlaceMethod.LOOK;
        return airPlaceProblem(spec) == null ? PlaceMethod.AIRPLACE : PlaceMethod.LOOK;
    }

    /**
     * Why an airplace click (use-item-on the empty target cell itself, face UP, cursor at its centre, inside, no
     * rotation) might not produce {@code spec}, or null if it yields the same state as a top-face click: carpets,
     * plates, full blocks, plants, slabs (face UP: bottom), pillars (face UP: axis=y), leaves, snow, pale moss carpet,
     * huge mushroom blocks. Placed into an empty cell the server orders the candidate faces by the player's look
     * (BlockPlaceContext.getNearestLookingDirections with replaceClicked true), so look-dependent blocks are named.
     */
    public static String airPlaceProblem(BlockSpec spec) {
        String path = ItemIds.path(spec.id());
        Map<String, String> props = spec.props();
        boolean mushroom = HUGE_MUSHROOMS.contains(path);
        if (MULTIFACE.contains(path) || (props.containsKey("down") && !mushroom)) {
            return path + ": attaches to the first face in look order it can stick to (a wall next to it, not the floor)";
        }
        if (path.endsWith("_coral_fan") || LOOK_PLACED.contains(path)) {
            return path + ": standing or wall/hanging form follows the look";
        }
        for (String k : props.keySet()) {
            if (LOOK_PROPS.contains(k)) return path + ": " + k + " follows the look";
        }
        return null;
    }

    /**
     * LOOK blocks a click on a horizontal side face of a neighbouring block may place ({@code build.lookSideFaces}):
     * the new block goes into the clicked block's cell + the face (the target
     * cell), and its state is the one a top-face click gives, as long as the look is pitched down
     * ({@link Aim#SIDE_MIN_PITCH}). Vanilla 1.21.4: pointed dripstone takes {@code vertical_direction} from the nearest
     * vertical look direction (down: it points up; with air above, up anyway) and {@code thickness} from the block
     * above; a lantern takes {@code hanging} from the first vertical direction in look order (down: standing); a candle
     * takes nothing from the look or the face. Not: torches and coral fans (a side click gives the wall form), glow
     * lichen, sculk vein and resin clump (they stick to the clicked face first). Ids are not checked here.
     */
    public static boolean sideFacePlaceable(BlockSpec spec) {
        String path = ItemIds.path(spec.id());
        return SIDE_PLACED.contains(path) || path.endsWith("copper_lantern") || path.equals("candle")
            || path.endsWith("_candle");
    }

    private static final Set<String> SIDE_PLACED = Set.of("pointed_dripstone", "lantern", "soul_lantern");

    /**
     * Full blocks without a block entity that act on a right-click with a block in hand (a menu, a note change, an
     * explosion) instead of placing it: never clicked on a side face ({@link #sideFacePlaceable}). Blocks with a block
     * entity (chests, barrels, jukeboxes, pots, ...) are refused by the caller.
     */
    private static final Set<String> USABLE = Set.of("note_block", "crafting_table", "cartography_table",
        "smithing_table", "fletching_table", "loom", "respawn_anchor", "redstone_ore", "deepslate_redstone_ore", "tnt");

    /**
     * A block (path or namespaced id) whose side face a side-face click may use: not one of the usable full blocks
     * above. The caller also requires a full cube without a block entity.
     */
    public static boolean sideClickable(String blockId) {
        return !USABLE.contains(ItemIds.path(blockId));
    }

    /**
     * Properties entities change at any time (a player or an item on a pressure plate), so a different world value
     * is not a wrong block either.
     */
    public static boolean entityDependent(String property) {
        return property.equals("powered") || property.equals("power");
    }

    /**
     * True if the world state {@code actual} counts as the target: same id, and every property present in the
     * target matches, except neighbour- and entity-dependent properties (see {@link #neighbourDependent},
     * {@link #entityDependent}). Properties missing from the target are ignored, so partial palette states compare
     * correctly. A null target means "must be air". Ids compare as materials ({@link ItemIds#material}): an open
     * and a closed eyeblossom are the same block, because the world flips them with the time of day; breaking one
     * to place the other would never end.
     */
    public static boolean equivalent(BlockSpec target, BlockSpec actual) {
        if (actual == null) return false;
        if (target == null || target.isAir()) return actual.isAir();
        if (!ItemIds.material(target.id()).equals(ItemIds.material(actual.id()))) return false;
        String path = ItemIds.path(target.id());
        for (var e : target.props().entrySet()) {
            String have = actual.props().get(e.getKey());
            if (e.getValue().equals(have)) continue;
            if (neighbourDependent(path, e.getKey()) || entityDependent(e.getKey())) continue;
            return false;
        }
        return true;
    }
}
