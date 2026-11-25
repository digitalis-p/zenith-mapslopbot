package io.github.digitalisp.mapart.coord;

import io.github.digitalisp.mapart.bot.MenuGate;

import java.util.HashMap;
import java.util.Map;

/**
 * The bot's menu gate on the coordination's claims: an exclusive claim with wait per key ({@code chest:<x,y,z>},
 * {@code station:cartography}, {@code station:anvil}) from the first {@link #mayOpen} until {@link #done}. A claim that
 * was denied or lost is asked for again on the next poll. With {@link LocalCoordination} every claim is held at once,
 * so the gate never holds a task.
 *
 * <p>Tick thread only (container tasks tick there).
 */
public final class ClaimMenuGate implements MenuGate {
    private final Coordination coord;
    private final Map<String, Claim> claims = new HashMap<>();

    public ClaimMenuGate(Coordination coord) {
        this.coord = coord;
    }

    @Override
    public boolean mayOpen(String key) {
        Claim c = claims.get(key);
        if (c == null || c.over()) {
            c = coord.claim(key, Claim.Mode.EXCLUSIVE, true);
            claims.put(key, c);
        }
        return c.held();
    }

    /** False once the claim asked for {@code key} was lost or refused after it was held. */
    @Override
    public boolean mayKeep(String key) {
        Claim c = claims.get(key);
        return c == null || c.state() == Claim.State.HELD || c.state() == Claim.State.WAITING;
    }

    @Override
    public void done(String key) {
        Claim c = claims.remove(key);
        if (c != null) c.release();
    }

    /** Gives every claim back (an abandoned unit). */
    public void releaseAll() {
        for (Claim c : claims.values()) c.release();
        claims.clear();
    }

    /** Keys asked for and not given back (tests, docs). */
    public int open() {
        return claims.size();
    }
}
