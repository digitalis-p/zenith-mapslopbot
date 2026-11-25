package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.plan.FarStorage;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.InventoryView;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * The elytra legs' supply ({@code build.elytraLegs}): which chest holds the
 * rockets, the elytra and its spare, and what a RESTOCK fetches from it. The chest is the first of
 * {@code site.storage.rocketChests}, else the chest or barrel a sign saying "rocket" is on (a wall sign on one of its
 * faces, or a standing sign on top; any case, front or back text), read from ZenithProxy's chunk cache; the nearest to
 * the bot when several. A RESTOCK takes an elytra when none is carried, or when every carried one is at the
 * durability floor and Mending cannot raise it (no Mending, or no XP bottles carried): the worn-out one goes back and
 * the spare comes out. It tops the flight rockets up to {@code build.elytraRocketStock} when fewer than
 * {@code build.elytraRocketLow} are carried. A visit that finds none of what is missing holds the next one for
 * {@link #EMPTY_RETRY_MS}. Pure apart from the remembered chest and those holds.
 */
final class ElytraKit {
    static final String ELYTRA = "minecraft:elytra";
    static final String ROCKET = "minecraft:firework_rocket";
    /** The sign word (lower case) that names the rocket chest. */
    static final String WORD = "rocket";
    /** An elytra's durability (vanilla), for the floor in uses when no carried elytra tells its maximum. */
    static final int ELYTRA_MAX = 432;
    /**
     * Search the signs at most this often (ms). The engine looks every tick with the switch on (throttled to this),
     * not only in RESTOCK: the first RESTOCK may start far from the chest (the working items go back to the station
     * chests first, out of view), so the chest is found while the Bot passes it (STOCK_CHECK) and remembered.
     */
    static final long SEARCH_EVERY_MS = 30_000;
    /**
     * After a visit that found none of what was missing (or failed), look again only this much later (ms): no walk
     * to an empty chest every RESTOCK; the owner refills it meanwhile.
     */
    static final long EMPTY_RETRY_MS = 10 * 60_000;

    private static final Set<String> CONTAINERS = Set.of("chest", "trapped_chest", "barrel");

    /** What a RESTOCK fetches: an elytra (take one, or exchange a worn-out one), and how many rockets (0: none). */
    record Need(boolean elytra, int rockets, String why) {
        boolean any() {
            return elytra || rockets > 0;
        }
    }

    /**
     * What to fetch for {@code armor} (the inventory's armour list: worn and carried pieces), {@code rockets} flight
     * rockets and {@code xpBottles} carried. An elytra worn in the chest slot is never exchanged here (the owner's, in
     * {@code elytraGear} "worn", or one a flight wears): it counts as carried.
     */
    static Need need(List<InventoryView.Armor> armor, int rockets, int xpBottles, MapArtConfig.BuildCfg b) {
        List<String> why = new ArrayList<>();
        boolean elytra = false;
        List<InventoryView.Armor> elytras = armor.stream().filter(a -> ELYTRA.equals(a.itemId())).toList();
        if (elytras.isEmpty()) {
            elytra = true;
            why.add("no elytra carried");
        } else if (elytras.stream().noneMatch(InventoryView.Armor::worn)
            && elytras.stream().allMatch(a -> atFloor(a, b.elytraMinPercent))) {
            boolean mendable = xpBottles > 0 && elytras.stream().anyMatch(InventoryView.Armor::mending);
            if (!mendable) {
                elytra = true;
                InventoryView.Armor a = elytras.get(0);
                why.add(String.format(Locale.ROOT, "the elytra at %d of %d uses (floor %d %%) and %s", a.remaining(),
                    a.max(), b.elytraMinPercent, a.mending() ? "no XP bottles to mend it" : "no Mending on it"));
            }
        }
        int want = 0;
        int stock = Math.max(1, b.elytraRocketStock);
        if (rockets < Math.min(stock, Math.max(1, b.elytraRocketLow))) {
            want = stock - Math.max(0, rockets);
            why.add(rockets + " flight rockets carried (under " + b.elytraRocketLow + ")");
        }
        return new Need(elytra, Math.max(0, want), String.join(", ", why));
    }

    /** The elytra is at or below the durability floor ({@code build.elytraMinPercent}). */
    static boolean atFloor(InventoryView.Armor a, int minPercent) {
        return a.max() > 0 && a.remaining() * 100L <= (long) a.max() * Math.max(0, minPercent);
    }

    /** The floor in uses that {@code exchangeElytra} returns at or below: {@code minPercent} % of {@code max}. */
    static int floorUses(int max, int minPercent) {
        int m = max > 0 ? max : ELYTRA_MAX;
        return (int) Math.floor(m * Math.max(0, minPercent) / 100.0);
    }

    /**
     * The block a sign is on: a wall sign's (the block behind it, against its {@code facing}), a standing sign's (the
     * block under it); null for a hanging sign or a sign without the state that says where it is.
     */
    static @Nullable Pos attached(WorldView.Sign s) {
        String id = s.block().id();
        String path = id.substring(id.indexOf(':') + 1);
        if (path.endsWith("_hanging_sign")) return null;
        if (path.endsWith("_wall_sign")) {
            String f = s.block().props().get("facing");
            if (f == null) return null;
            return switch (f) {
                case "north" -> s.pos().offset(0, 0, 1);
                case "south" -> s.pos().offset(0, 0, -1);
                case "east" -> s.pos().offset(-1, 0, 0);
                case "west" -> s.pos().offset(1, 0, 0);
                default -> null;
            };
        }
        if (path.endsWith("_sign")) return s.pos().offset(0, -1, 0);
        return null;
    }

    /** The sign's text names the rocket chest: it holds {@link #WORD}, any case. */
    static boolean names(WorldView.Sign s) {
        return s.text() != null && s.text().toLowerCase(Locale.ROOT).contains(WORD);
    }

    /**
     * The chests and barrels that a sign naming the rocket chest is on, nearest to {@code near} first (then by
     * position, so the order is fixed); each once.
     */
    static List<Pos> signedChests(List<WorldView.Sign> signs, Function<Pos, BlockSpec> block, Pos near) {
        List<Pos> out = new ArrayList<>();
        for (WorldView.Sign s : signs) {
            if (s == null || !names(s)) continue;
            Pos at = attached(s);
            if (at == null || out.contains(at)) continue;
            BlockSpec b = block.apply(at);
            if (b == null) continue;
            String path = b.id().substring(b.id().indexOf(':') + 1);
            if (CONTAINERS.contains(path)) out.add(at);
        }
        out.sort(Comparator.<Pos>comparingDouble(p -> distSq(p, near)).thenComparing(Pos::toString));
        return out;
    }

    /**
     * The rocket chest for a job that keeps to the platform whose map area starts at ({@code minX}, {@code minZ}) (a
     * colour-suppression job with its own chests): {@code chest} when it is not far storage for that platform
     * ({@link FarStorage#far(int, int, int, Pos)}, {@code blocks} = {@code build.farStorageBlocks}), else the first of
     * {@code signed} (the chests a sign names, nearest first) that is not, else null: no trip for the kit. Pure.
     */
    static @Nullable Pos forPlatform(Pos chest, List<Pos> signed, int minX, int minZ, int blocks) {
        if (!FarStorage.far(minX, minZ, blocks, chest)) return chest;
        for (Pos p : signed) if (p != null && !FarStorage.far(minX, minZ, blocks, p)) return p;
        return null;
    }

    /**
     * {@code build.elytraKitFarTrip}: a bot whose feet are more than this many blocks above the {@code foot} of the
     * platform's access path is on the path or on the platform ({@link #aboveFoot}); at most this many above or below
     * it, it is at the storage walkway's level (the moss tile one below, a chest stand a block or two up).
     */
    static final int FOOT_LEVELS = 4;

    /**
     * {@code build.elytraKitFarTrip}: why platform {@code own} has no way for the kit's trip to a far rocket chest, or
     * null: the trip goes down the access path to its {@code foot} and comes back by the foot and the {@code entry},
     * and judges "on the platform" by the bottom glass ({@code glassY}). Pure.
     */
    static @Nullable String farTripProblem(MapArtConfig.PlatformCfg own) {
        if (own.foot == null) return "platform " + own.id + " has no foot (the bottom of its access path)";
        if (own.entry == null) return "platform " + own.id + " has no entry (where its access path reaches the layers)";
        if (own.glassY == null || own.glassY.isEmpty() || own.glassY.getFirst() == null)
            return "platform " + own.id + " has no glassY";
        return null;
    }

    /** The feet level {@code feetY} lies above the storage walkway at {@code foot}: on the access path or the platform. */
    static boolean aboveFoot(int feetY, Pos foot) {
        return feetY > foot.y() + FOOT_LEVELS;
    }

    /**
     * The feet level {@code feetY} is on the platform's levels: at the bottom glass ({@code glassY}'s first) less one or
     * higher, as the engine's access-path walks judge it ({@code climbToPlatform}, {@code descendToFoot}).
     */
    static boolean onPlatform(int feetY, @Nullable List<Integer> glassY) {
        return glassY != null && !glassY.isEmpty() && glassY.getFirst() != null && feetY >= glassY.getFirst() - 1;
    }

    /**
     * {@code build.elytraKitFarTrip}: the bot stands where only the ride's openings let it be (in the booth after a
     * ride, at the start stand): its feet or head in a no-go zone ({@code zones}) and in none of the platform's own
     * openings ({@code own}). A trip that starts there keeps the ride's openings for its walk down.
     */
    static boolean onRideWays(@Nullable List<MapArtConfig.RegionCfg> zones, @Nullable List<MapArtConfig.RegionCfg> own,
                              Pos feet) {
        for (int y = feet.y(); y <= feet.y() + 1; y++)
            if (io.github.digitalisp.mapart.config.SiteRules.regionAt(zones, feet.x(), y, feet.z()) != null
                && io.github.digitalisp.mapart.config.SiteRules.regionAt(own, feet.x(), y, feet.z()) == null) return true;
        return false;
    }

    private static double distSq(Pos a, Pos b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- the remembered chest

    private @Nullable Pos found;
    /** The sign that named {@link #found}. */
    private @Nullable Pos foundSign;
    private long searchedAt = Long.MIN_VALUE;

    /** The rocket chest a sign named (null: none found yet, or the list is used). */
    @Nullable Pos found() {
        return found;
    }

    /**
     * The rocket chest: the first configured one, else the remembered one, else the nearest a sign names. The
     * remembered one stays while a sign still names it, and while its sign is not among the signs read but may still
     * be there: its chunk (or the chest's) out of view or out of the scan, the block still a sign (the
     * sign one block east of the chest sat in the next chunk, out of the scan, and the chest was "gone" for a while).
     * It goes when its sign is read with other text, or its block is loaded and no sign, or the chest is no chest.
     * The signs are searched at most every {@link #SEARCH_EVERY_MS}; in between the remembered one (or none) is the
     * answer. {@code note} gets one line when the chest is found or changes (the engine logs it).
     */
    @Nullable Pos chest(List<Pos> configured, WorldView w, Pos near, long nowMs, java.util.function.Consumer<String> note) {
        for (Pos p : configured) if (p != null) return p;
        if (searchedAt != Long.MIN_VALUE && nowMs >= searchedAt && nowMs - searchedAt < SEARCH_EVERY_MS) return found;
        if (found != null && !w.isLoaded(found)) return found;
        searchedAt = nowMs;
        List<WorldView.Sign> signs = w.signs();
        List<Pos> all = signedChests(signs, w::block, near);
        if (found != null && !all.contains(found) && mayStillBeThere(signs, w)) return found;
        Pos next = found != null && all.contains(found) ? found : all.isEmpty() ? null : all.get(0);
        if (next != null && !next.equals(found)) {
            note.accept("rocket chest found by its sign at " + next + (all.size() > 1 ? " (nearest of " + all.size() + ")" : ""));
        } else if (next == null && found != null) {
            note.accept("the sign of the rocket chest at " + found + " is gone: no rocket chest");
        }
        found = next;
        foundSign = null;
        if (next != null)
            for (WorldView.Sign s : signs)
                if (s != null && names(s) && next.equals(attached(s))) {
                    foundSign = s.pos();
                    break;
                }
        return found;
    }

    /** {@link #found}'s sign was not read naming it, but may be there: out of view or out of the scan, still a sign. */
    private boolean mayStillBeThere(List<WorldView.Sign> signs, WorldView w) {
        if (foundSign == null || found == null) return false;
        for (WorldView.Sign s : signs) if (s != null && foundSign.equals(s.pos())) return false; // read: its text decides
        if (w.isLoaded(found) && !CONTAINERS.contains(path(w.block(found)))) return false;
        return !w.isLoaded(foundSign) || path(w.block(foundSign)).endsWith("_sign");
    }

    private static String path(BlockSpec b) {
        if (b == null) return "";
        String id = b.id();
        return id.substring(id.indexOf(':') + 1);
    }

    // ---- empty visits

    private @Nullable Pos elytraEmptyAt, rocketsEmptyAt;
    private long elytraEmptyMs, rocketsEmptyMs;

    /** The visit to {@code chest} found no elytra ({@code elytra}) or no flight rockets, or failed, at {@code nowMs}. */
    void empty(boolean elytra, Pos chest, long nowMs) {
        if (elytra) {
            elytraEmptyAt = chest;
            elytraEmptyMs = nowMs;
        } else {
            rocketsEmptyAt = chest;
            rocketsEmptyMs = nowMs;
        }
    }

    /** The visit took some: the next RESTOCK looks again as needed. */
    void stocked(boolean elytra) {
        if (elytra) elytraEmptyAt = null;
        else rocketsEmptyAt = null;
    }

    /**
     * Seconds since a visit to {@code chest} found none (of the elytra, or of the rockets) when that is under
     * {@link #EMPTY_RETRY_MS} ago (the visit is held); -1 when a visit may go.
     */
    long emptyAgo(boolean elytra, Pos chest, long nowMs) {
        Pos at = elytra ? elytraEmptyAt : rocketsEmptyAt;
        long ms = elytra ? elytraEmptyMs : rocketsEmptyMs;
        if (at == null || !at.equals(chest) || nowMs - ms >= EMPTY_RETRY_MS || nowMs < ms) return -1;
        return (nowMs - ms) / 1000;
    }
}
