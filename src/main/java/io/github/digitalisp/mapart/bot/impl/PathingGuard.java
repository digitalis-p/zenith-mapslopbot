package io.github.digitalisp.mapart.bot.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.zenith.Globals.CONFIG;

/**
 * Forces ZenithProxy's pathfinder to walk only: allowPlace, allowBreak and allowInventory false and the
 * allowBreakAnyway list empty while any task is pathing (while {@link BreakGuard} holds, that list is its
 * {@link BreakGuard#SEAL_BLOCKS}: allowBreak itself is never on); the previous values come back when the last holder
 * releases (reference-counted, see {@link RefCountedOverride}).
 *
 * <p>Sprint ({@link #sprint}): while held, allowSprint is what the walking {@link Walker} asks for this tick, false
 * unless its walk may sprint now ({@link SprintLegs#rule}, {@link SprintLegs#allowed}); so no other walk of the plugin
 * sprints, whatever the global setting says.
 *
 * <p>Risk: the overridden values live in ZenithProxy's global config. If ZenithProxy saves its config while a
 * walk is in progress and then crashes, config.json keeps the safe (false) values until changed by hand (allowSprint:
 * the value of that tick).
 */
public final class PathingGuard {
    private PathingGuard() { }

    private record Saved(boolean allowPlace, boolean allowBreak, boolean allowInventory, List<String> breakAnyway,
                         boolean allowSprint) { }

    /** allowSprint while held: set by the walking {@link Walker} every tick. */
    private static volatile boolean sprint;

    private static final RefCountedOverride<Saved> OVERRIDE = new RefCountedOverride<>(
        () -> {
            var p = CONFIG.client.extra.pathfinder;
            return new Saved(p.allowPlace, p.allowBreak, p.allowInventory, new ArrayList<>(p.allowBreakAnyway),
                p.allowSprint);
        },
        () -> {
            var p = CONFIG.client.extra.pathfinder;
            p.allowPlace = false;
            // never allowBreak (live 2026-10-13 23:43: with it on, a breaking walk broke storage chests); a walk's last
            // resort while BreakGuard holds breaks only its seal blocks (a bot sealed in by pointed dripstone)
            p.allowBreak = false;
            p.allowInventory = false;
            Set<String> anyway = breakAnyway(BreakGuard.holders());
            if (!p.allowBreakAnyway.equals(anyway)) {
                p.allowBreakAnyway.clear();
                p.allowBreakAnyway.addAll(anyway);
            }
            p.allowSprint = sprint;
        },
        s -> {
            var p = CONFIG.client.extra.pathfinder;
            p.allowPlace = s.allowPlace();
            p.allowBreak = s.allowBreak();
            p.allowInventory = s.allowInventory();
            p.allowBreakAnyway.clear();
            p.allowBreakAnyway.addAll(s.breakAnyway());
            p.allowSprint = s.allowSprint();
        });

    /** allowBreakAnyway while pathing: BreakGuard's seal blocks while it holds (a walk's last resort), else none. */
    static Set<String> breakAnyway(int breakGuardHolders) {
        return breakGuardHolders > 0 ? BreakGuard.SEAL_BLOCKS : Set.of();
    }

    public static RefCountedOverride.Handle acquire() {
        return OVERRIDE.acquire();
    }

    /** Re-applies the walk-only settings while held. */
    public static void enforce() {
        OVERRIDE.enforce();
    }

    public static int holders() {
        return OVERRIDE.holders();
    }

    /**
     * Baritone may sprint ({@code on}) or not from now on while the guard is held; applied at once when held. Each
     * walking {@link Walker} sets it every tick; a walk that ends sets it false.
     */
    public static void sprint(boolean on) {
        if (sprint == on) return;
        sprint = on;
        OVERRIDE.enforce();
    }
}
