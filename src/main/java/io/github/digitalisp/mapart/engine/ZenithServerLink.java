package io.github.digitalisp.mapart.engine;

import com.zenith.Proxy;
import com.zenith.module.impl.AutoReconnect;
import io.github.digitalisp.mapart.command.ServerLink;

import java.util.ArrayList;
import java.util.List;

import static com.zenith.Globals.MODULE;

/**
 * {@link ServerLink} over ZenithProxy 3.7.2, doing what its own {@code disconnect} command does:
 * <ul>
 *   <li>{@link Proxy#disconnect()} closes the connection with the reason "Manual Disconnect". AutoReconnect's
 *       default decision handler never reconnects after that reason (its NON_RECONNECTABLE_DISCONNECT_REASONS).</li>
 *   <li>{@link AutoReconnect#cancelAutoReconnect()} cancels a reconnect that is already counting down.</li>
 *   <li>{@link Proxy#cancelLogin()} stops a login in progress.</li>
 * </ul>
 * ZenithProxy then stays offline until someone runs {@code connect}, or a player logs in to the proxy while
 * {@code client.extra.autoConnectOnLogin} is on (its default), or ActiveHours starts a session (off by default).
 */
public final class ZenithServerLink implements ServerLink {
    @Override
    public String disconnect() {
        Proxy proxy = Proxy.getInstance();
        List<String> done = new ArrayList<>();
        if (proxy.isConnected()) {
            proxy.disconnect(); // reason "Manual Disconnect": AutoReconnect does not reconnect
            done.add("disconnected from the server");
        }
        if (proxy.cancelLogin()) done.add("login cancelled");
        AutoReconnect reconnect = MODULE.get(AutoReconnect.class);
        if (reconnect != null && reconnect.cancelAutoReconnect()) done.add("pending auto-reconnect cancelled");
        if (done.isEmpty()) return "Already disconnected; no reconnect pending.";
        String line = String.join(", ", done);
        return Character.toUpperCase(line.charAt(0)) + line.substring(1) + ". No auto-reconnect.";
    }
}
