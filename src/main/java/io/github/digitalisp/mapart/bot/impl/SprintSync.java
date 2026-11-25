package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

/**
 * The bot's sprint state as a vanilla client keeps it, and as Grim predicts it, where ZenithProxy 3.7.2's Bot differs
 * (live setback bursts 2026-10-11 20:33 and 20:34). Pure: the
 * decisions; {@link ZenithSprintSync} applies them to the Bot.
 *
 * <ul>
 *   <li><b>The sprinting speed modifier (MC-69459).</b> A vanilla client takes the server's movement-speed modifiers from
 *       every attribute update, the sprinting one included or not as the server last knew, and changes the sprinting
 *       one only when it starts or stops sprinting itself. Grim predicts exactly that ({@code hasSprintingAttributeEnabled}:
 *       set from an update when the client answers the transaction sent with it, set to the sprint state at every
 *       toggle). ZenithProxy's Bot puts its own sprint state back over every update. On the soul-soil walkway (Soul Speed
 *       boots) the server sends an update at every block the bot enters, carrying its sprint state of that moment: one
 *       sent just before it heard the bot's START (or STOP) reaches the bot after it, and Grim then predicts the other
 *       speed. {@link #modifierWanted} gives the vanilla answer.</li>
 *   <li><b>Teleports.</b> A vanilla client keeps sprinting across a server teleport, and so does Grim. ZenithProxy's
 *       Bot clears its sprint state ({@code syncFromCache(true)}) without telling the server, so the next tick's walk
 *       is predicted sprinting and is not: every setback brings the next one (live: 10 in 5 s until an escape resynced
 *       the state). {@link #tick} gives the state to restore: the last one sent.</li>
 * </ul>
 * Time is counted in "seen at" ticks: {@link #tick} runs before the Bot moves, so a toggle seen at tick T happened in
 * tick T-1's movement, and an update that counts from tick T (its transaction answered between the movements of T-1 and
 * T) is {@linkplain #update applied at} T: the update wins when it counts from the toggle's tick or later.
 */
public final class SprintSync {
    /** Ticks after a teleport packet in which a cleared sprint state counts as the teleport's. */
    static final int TELEPORT_WINDOW = 5;

    /** Client ticks started (the module ticks this before the Bot moves). */
    private long tick;
    /** The Bot's sprint state at the last tick. */
    private boolean lastBot;
    /** Tick at which the Bot's last sprint toggle was seen. */
    private long toggledAt = Long.MIN_VALUE;
    /** Tick from which the last update counts, and whether it carried the sprinting modifier. */
    private long attributesAt = Long.MIN_VALUE;
    private boolean attributesSprint;
    /** Ticks left to look for the state a teleport cleared (ZenithProxy handles the teleport on its own loop). */
    private int teleportTicks;

    /** Ticks started so far (stamps for {@link #update}). */
    public synchronized long now() {
        return tick;
    }

    /** A server teleport (setback) arrived (any thread). */
    public synchronized void teleport() {
        teleportTicks = TELEPORT_WINDOW;
    }

    /**
     * One tick before the Bot moves. {@code botSprinting}: the Bot's sprint state now (after a teleport: as ZenithProxy
     * left it); {@code lastSent}: the sprint state the server last heard (ZenithProxy's cache). Returns the sprint state
     * to restore after a teleport, or null when nothing is to be restored.
     */
    public synchronized @Nullable Boolean tick(boolean botSprinting, boolean lastSent) {
        tick++;
        Boolean restore = null;
        if (teleportTicks > 0) {
            teleportTicks--;
            if (botSprinting != lastSent) {
                // ZenithProxy cleared it without a packet: the server (and Grim) still have the old state
                restore = lastSent;
                botSprinting = lastSent;
                teleportTicks = 0;
            }
            lastBot = botSprinting; // not a toggle: the client did not change it
        }
        if (botSprinting != lastBot) {
            toggledAt = tick;
            lastBot = botSprinting;
        }
        return restore;
    }

    /**
     * An attribute update that counts from tick {@code from} (seen-at ticks, at most {@link #now}); {@code sprint}: it
     * carries the sprinting modifier. Of several, the one counting from the latest tick wins (equal: the later call).
     */
    public synchronized void update(boolean sprint, long from) {
        if (attributesAt != Long.MIN_VALUE && from < attributesAt) return;
        attributesAt = from;
        attributesSprint = sprint;
    }

    /**
     * Whether the movement speed should carry the sprinting modifier now: the server's view from the last update when it
     * counts from the Bot's last toggle or later, else the Bot's own state.
     */
    public synchronized boolean modifierWanted(boolean botSprinting) {
        if (attributesAt != Long.MIN_VALUE && attributesAt >= toggledAt) return attributesSprint;
        return botSprinting;
    }
}
