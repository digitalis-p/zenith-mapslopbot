package io.github.digitalisp.mapart.bot.impl;

/**
 * The sneak key of {@link LeaveCartTask} as the server sees it through the player-command packet (press / release
 * shift key): which command to send on a tick. Pure, so the sequence is tested without a connection. The key goes down
 * on the first tick in the vehicle; while the bot is still in the vehicle {@link #REPRESS_TICKS} ticks later it goes up
 * and, {@link #UP_TICKS} ticks after that, down again (a player letting go and pressing again). Every press is paired
 * with one release: never two presses in a row, never a release without a press.
 */
public final class DismountKey {
    public enum Action { NONE, PRESS, RELEASE }

    /** Still in the vehicle this long after a press: the key goes up for another press. */
    public static final int REPRESS_TICKS = 25;
    /** Ticks the key stays up between two presses. */
    public static final int UP_TICKS = 3;

    private boolean down;
    private long changedAt = Long.MIN_VALUE;
    private int presses;

    /** The command for a tick on which the bot still sits in the vehicle. */
    public Action inVehicle(long now) {
        if (!down) {
            if (changedAt != Long.MIN_VALUE && now - changedAt < UP_TICKS) return Action.NONE;
            down = true;
            changedAt = now;
            presses++;
            return Action.PRESS;
        }
        if (now - changedAt >= REPRESS_TICKS) {
            down = false;
            changedAt = now;
            return Action.RELEASE;
        }
        return Action.NONE;
    }

    /** The key goes up (out of the vehicle, given up, cancelled): RELEASE when it was down, else NONE. */
    public Action release() {
        if (!down) return Action.NONE;
        down = false;
        return Action.RELEASE;
    }

    public boolean down() {
        return down;
    }

    public int presses() {
        return presses;
    }
}
