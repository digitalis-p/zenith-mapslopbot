package io.github.digitalisp.mapart.bot;

/**
 * How a withdrawal moves items out of a container. CONTRACT (lead-owned).
 */
public enum WithdrawMode {
    /**
     * Whole-stack shift-clicks, merged into the player's partial stacks first: a request may be overshot by less than a
     * stack, and a held partial stack plus the overflow of the merge can take one slot more than the count needs. The
     * original behaviour.
     */
    WHOLE_STACKS,
    /**
     * As {@link #WHOLE_STACKS}, but first, for each wanted item whose held partial stack would otherwise leave an extra
     * slot: pick up a FULL container stack, left-click it onto the partial stack (filled to the max; the cursor keeps
     * the rest) and put the rest back into the container slot it came from. Then whole stacks for what is still
     * wanted. The item ends in the fewest slots. Top-ups come first in the request, so no shift-click is sent with an
     * item on the cursor. Done only when it saves a slot and the container has a full stack of the item.
     */
    TOP_UP,
    /**
     * Exactly the wanted count for small requests (below one stack): pick up a container stack, right-click the wanted
     * number into one empty player slot (or onto a partial stack of the item), put the rest back. A container stack of
     * exactly the wanted size is shift-clicked. Larger requests go as {@link #WHOLE_STACKS}. For the empty map and the
     * glass pane, so the bot carries one of each and not a stack (the trimmed kit).
     */
    EXACT
}
