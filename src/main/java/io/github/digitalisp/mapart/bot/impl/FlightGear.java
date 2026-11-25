package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The elytra flight leg's gear rules ({@code build.elytraLegs}): whether a leg may
 * fly at all (the fall-back rules), the clicks that put the elytra on in place of the chestplate and back, and when the
 * chestplate goes back on. Pure.
 *
 * <p><b>Modes</b> ({@code build.elytraGear}):
 * <ul>
 *   <li>{@link Mode#FLIGHT} (the default): the chestplate goes back on after every landing (two swaps per flight); an
 *       elytra below its mend threshold stays on until the restock ends, so the durability guard can mend it (Mending
 *       repairs worn gear only). The chestplate is off only in the air (about 0.9 s of swaps a
 *       flight more than RESTOCK's second flight in a restock).</li>
 *   <li>{@link Mode#RESTOCK}: the elytra goes on before the restock's first flight and the chestplate back on when the
 *       restock ends (the walk back to the lane start landed, or the engine is out of the restock), and before any
 *       fight or escape. Two swaps per restock, but no chestplate in the chest menus and hops in between.</li>
 *   <li>{@link Mode#WORN}: the owner wears the elytra; the plugin never swaps, and flies only while it is worn.</li>
 * </ul>
 *
 * <p>A swap is one hotbar-key click on the chest slot when the piece to put on sits in the hotbar (it trades places with
 * the worn one), else three hotbar-key clicks with a borrowed key ({@link #borrowKey}): the piece trades places with
 * that hotbar slot, the hotbar slot with the chest slot, and the piece's old slot with the hotbar slot again (the worn
 * one lands where the piece was, the hotbar slot gets its own stack back). Nothing is ever on the cursor: the server
 * drops a cursor stack when the connection ends (vanilla {@code AbstractContainerMenu.removed}), and local run r1off
 * 2026-10-25 lost the elytra that way (ZenithProxy killed 80 ms into the old pick-up, chest slot, put-down swap). The
 * worn piece never leaves the inventory: an exception to {@link OffhandGuard}'s armour rule, sanctioned by the switch.
 */
final class FlightGear {
    private FlightGear() { }

    /** Player slot of the chest armour. */
    static final int CHEST_SLOT = 6;

    enum Mode { RESTOCK, FLIGHT, WORN;
        static Mode of(@Nullable String s) {
            if (s == null) return FLIGHT;
            return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "restock" -> RESTOCK;
                case "worn" -> WORN;
                default -> FLIGHT;
            };
        }
    }

    /** One click of a swap: plain left click on {@code slot}, or the hotbar key {@code hotbarKey} (0..8) over it. */
    record Click(int slot, int hotbarKey) {
        boolean swapKey() {
            return hotbarKey >= 0;
        }
    }

    /**
     * The clicks that put the piece in player slot {@code from} (9..44) into the chest slot, the worn one (if any) going
     * to {@code from}; {@code key} (0..8) is the hotbar key a piece in the main inventory borrows. With {@code from} an
     * empty slot the same clicks take the worn piece off into it.
     */
    static List<Click> swap(int from, int key) {
        int h = ItemIds.hotbarIndex(from);
        if (h >= 0) return List.of(new Click(CHEST_SLOT, h));
        return List.of(new Click(from, key), new Click(CHEST_SLOT, key), new Click(from, key));
    }

    /** The hotbar key a swap from the main inventory borrows: not the held slot, so the hand keeps its item. */
    static int borrowKey(int heldSlot) {
        return heldSlot == 8 ? 7 : 8;
    }

    /** The bot's state for {@link #refusal}. */
    record State(boolean autoArmor, boolean elytraWorn, int elytraSlot, int elytraLeft, int elytraMax, int rockets,
                 float health, boolean chestOccupied) { }

    /**
     * Why the leg must walk instead (null: it may fly): no elytra, worn and carried both missing; the mode WORN without
     * it worn; ZenithProxy's AutoArmor on (it would put the chestplate back on mid-flight: an elytra has no armour
     * material); the elytra at its floor ({@code minPercent} of its durability, or fewer uses left than the flight's
     * seconds plus a margin; vanilla also refuses to glide on the last use); no rockets; health under {@code minHealth}.
     */
    static @Nullable String refusal(State s, Mode mode, int minPercent, double minHealth, int flightSeconds) {
        if (s.autoArmor()) return "ZenithProxy's AutoArmor is on (it would swap the chestplate back on in the air)";
        if (!s.elytraWorn() && s.elytraSlot() < 0) return "no elytra";
        if (mode == Mode.WORN && !s.elytraWorn()) return "the elytra is not worn (elytraGear worn)";
        if (s.elytraMax() > 0) {
            int floor = Math.max(2, (int) Math.ceil(s.elytraMax() * minPercent / 100.0));
            if (s.elytraLeft() <= floor) {
                return "the elytra is at its durability floor (" + s.elytraLeft() + " of " + s.elytraMax() + " left)";
            }
            if (s.elytraLeft() <= flightSeconds + 5) {
                return "the elytra has " + s.elytraLeft() + " uses left for a flight of about " + flightSeconds + " s";
            }
        }
        if (s.rockets() <= 0) return "no rockets";
        if (s.health() < minHealth) return String.format("health %.0f under %.0f", s.health(), minHealth);
        return null;
    }

    /**
     * Whether the chestplate goes back on now, the elytra being on by the plugin's own swap: {@code justLanded} a flight
     * just ended on the ground; {@code inRestock} the engine's restock legs go on (until the first lane start after the
     * restock is reached); {@code danger} a fight or an escape; {@code mendWanted} the elytra is below its mend threshold
     * (FLIGHT keeps it on for the mend until the restock ends).
     */
    static boolean chestBack(Mode mode, boolean justLanded, boolean inRestock, boolean danger, boolean mendWanted) {
        if (mode == Mode.WORN) return false;
        if (danger || !inRestock) return true;
        return mode == Mode.FLIGHT && justLanded && !mendWanted;
    }
}
