package io.github.digitalisp.mapart.bot;

import io.github.digitalisp.mapart.model.Pos;

/**
 * A walk failed boxed in after a drop: the bot stands at {@code at}, its track came
 * in by a drop of {@code drop} blocks from {@code from}, which it cannot walk back up. The engine treats it as a fall and
 * climbs back to {@code from}'s level ({@link BotActions#takeBoxedInDrop}).
 */
public record BoxedInDrop(Pos at, Pos from, int drop) {
    /** What the engine saw, for its log lines and the "Bot fell" alert. */
    public String why() {
        return "boxed in after a drop of " + drop + " blocks from " + from.key() + ", no walk leads out";
    }
}
