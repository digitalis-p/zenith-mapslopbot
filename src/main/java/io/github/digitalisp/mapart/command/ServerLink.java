package io.github.digitalisp.mapart.command;

/**
 * The game-server connection, as far as {@code stop disconnect} needs it. Production:
 * {@link io.github.digitalisp.mapart.engine.ZenithServerLink}; tests pass a recorder.
 */
@FunctionalInterface
public interface ServerLink {
    /**
     * Disconnects from the game server and keeps it disconnected: no auto-reconnect afterwards. Safe to call when
     * already disconnected. Returns one line saying what happened, for the reply.
     */
    String disconnect();

    /** No connection control (tests, or wiring without ZenithProxy). */
    ServerLink NONE = () -> "Not disconnected: no server connection control is wired in.";
}
