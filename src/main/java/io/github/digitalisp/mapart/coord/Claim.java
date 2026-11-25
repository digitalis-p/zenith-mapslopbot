package io.github.digitalisp.mapart.coord;

import org.jspecify.annotations.Nullable;

/**
 * A lock on a world resource ({@link Coordination#claim}): {@code platform:<id>} while a bot builds on a platform,
 * {@code chest:<x,y,z>} while a container is open, {@code station:cartography} / {@code station:anvil} while the bot
 * uses one. Polled per tick: {@link #state()} moves from {@link State#WAITING} to {@link State#HELD} (or
 * {@link State#DENIED}), and a held claim may become {@link State#LOST} (its ttl ran out, the coordinator dropped it).
 * The holder gives it back with {@link #release()}; a waiting claim released stops waiting.
 */
public interface Claim {
    /** Exclusive: one holder. Shared: any number of shared holders (the later lane-band step's platform readers). */
    enum Mode { EXCLUSIVE, SHARED }

    enum State {
        /** Asked for with wait: queued behind the holders (first come, first served). */
        WAITING,
        HELD,
        /** Refused (asked without wait while held by someone else, or not allowed). Final. */
        DENIED,
        /** Was held, then taken away (ttl ran out, link gone too long, coordinator restart without resume). Final. */
        LOST,
        /** Given back by the holder (or the wait given up). Final. */
        RELEASED
    }

    /** The resource's name, e.g. {@code platform:soul} or {@code chest:-1200,64,880}. */
    String key();

    Mode mode();

    State state();

    /** Why it was denied or lost; null otherwise. */
    @Nullable String reason();

    /** Gives a held claim back, or stops waiting for it. Idempotent; never blocks. */
    void release();

    default boolean held() {
        return state() == State.HELD;
    }

    /** DENIED, LOST or RELEASED: it will never be held again (ask again with a new claim). */
    default boolean over() {
        State s = state();
        return s == State.DENIED || s == State.LOST || s == State.RELEASED;
    }

    /** Claim keys. */
    static String platform(String platformId) {
        return "platform:" + platformId;
    }

    /** {@code chest:<x,y,z>} of the container's canonical block (a double chest's own half). */
    static String chest(io.github.digitalisp.mapart.model.Pos container) {
        return io.github.digitalisp.mapart.bot.MenuGate.chest(container);
    }

    String CARTOGRAPHY = io.github.digitalisp.mapart.bot.MenuGate.CARTOGRAPHY;
    String ANVIL = io.github.digitalisp.mapart.bot.MenuGate.ANVIL;

    /**
     * The base's map counter ({@code site.mapCounter}): its press and the read-back after it, exclusive with wait (a
     * second bot's press would be read as this bot's count).
     */
    String COUNTER = "station:counter";

    /**
     * {@code sorter:<platform>}: a refilling platform's sorter pickup stand and the throw-back after it, exclusive with
     * wait (two bots in one item stream take each other's items).
     */
    static String sorter(String platformId) {
        return "sorter:" + platformId;
    }

    /**
     * {@code drop:<x,y,z>} of a drop stand (the junk spot's {@code junkDropStand}, a return drop point's {@code standAt}),
     * exclusive with wait: one bot drops from it at once.
     */
    static String drop(io.github.digitalisp.mapart.model.Pos stand) {
        return "drop:" + stand.key();
    }
}
