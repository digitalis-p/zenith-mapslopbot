package io.github.digitalisp.mapart.bot;

import io.github.digitalisp.mapart.model.Pos;

/**
 * A stack the last disconnect may have dropped: on the cursor, or in a
 * cartography table's or anvil's input slots, when the connection ended. The server drops both at the player's feet
 * when the player leaves (vanilla {@code AbstractContainerMenu.removed} and {@code clearContainer}), and nothing gives
 * them back. CONTRACT (lead-owned).
 *
 * @param item      namespaced item id
 * @param count     how many were there
 * @param at        the bot's feet block then (never logged above debug)
 * @param where     "on the cursor", "in the cartography table", "in the anvil"
 * @param last      still there in the last look before the connection ended; false: there only in the moments before
 *                  (its later clicks may not have reached the server), so finding none on the ground means nothing was
 *                  lost
 * @param inventory how many of the item the inventory (main and hotbar) held besides it then, as ZenithProxy's cache
 *                  had it after the clicks sent: after the reconnect, {@code inventory + count} or more there means the
 *                  stack is back (the click that took it never reached the server, or the server picked it up again
 *                  at the rejoin) and nothing dropped
 */
public record DisconnectDrop(String item, int count, Pos at, String where, boolean last, int inventory) {
    /** Where a cursor stack was. */
    public static final String CURSOR = "on the cursor";
    /** How {@link Outcome#missed} begins when the pickup looked where it dropped and found none of it. */
    public static final String NONE_ON_THE_GROUND = "none on the ground within ";

    /**
     * What the reconnect's pickup made of one drop: {@code pickedUp} came back from the ground; {@code missed} why not
     * all, or null; {@code back}: it was in the inventory already ({@link #inventory}), nothing to pick up.
     */
    public record Outcome(DisconnectDrop drop, int pickedUp, @org.jspecify.annotations.Nullable String missed,
                          boolean back) { }
}
