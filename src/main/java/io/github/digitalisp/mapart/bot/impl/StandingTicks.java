package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Paced RUN ticks with movement keys up, separate from cap evaluations (which may still steer or move on). */
final class StandingTicks {
    enum Idle {
        WINDOW("window full"), PACER("pacer interval"), ACK("cell ack"), RETRY("cell retry"),
        STACK("used-up stack"), MOVE("hotbar move/fence"), BUSY("inventory busy"),
        NONE("no clickable cell in reach"), OTHER("other");

        final String label;
        Idle(String label) { this.label = label; }
    }

    enum Hold {
        STACK("stack"), ACK("ack"), RETRY("retry"), LOOK("LOOK cell"), MOVE("hotbar move/fence"),
        BUSY("inventory busy"), QUEUE("click queue"), OTHER("other");

        final String label;
        Hold(String label) { this.label = label; }
    }

    private int ticks;
    private final Map<Idle, Integer> idle = new EnumMap<>(Idle.class);
    private final Map<Hold, Integer> cap = new EnumMap<>(Hold.class);
    private final Map<Hold, String> firstCell = new EnumMap<>(Hold.class);

    void tick(boolean clicked, Idle reason) {
        ticks++;
        if (!clicked) idle.merge(reason, 1, Integer::sum);
    }

    /** One binding cause per cap hold. Samples use lane-relative row/column, never world coordinates. */
    void cap(Hold reason, @Nullable String cell) {
        cap.merge(reason, 1, Integer::sum);
        if (cell != null) firstCell.putIfAbsent(reason, cell);
    }

    String summary() {
        int without = idle.values().stream().mapToInt(Integer::intValue).sum();
        String reasons = java.util.Arrays.stream(Idle.values())
            .map(r -> idle.getOrDefault(r, 0) + " " + r.label).collect(Collectors.joining(", "));
        String holds = java.util.Arrays.stream(Hold.values())
            .map(r -> cap.getOrDefault(r, 0) + " " + r.label).collect(Collectors.joining(", "));
        String cells = firstCell.entrySet().stream().map(e -> e.getKey().label + "=" + e.getValue())
            .collect(Collectors.joining(", "));
        return "; standing ticks " + ticks + ", " + without + " without a click: " + reasons
            + "; cap holds: " + holds + (cells.isEmpty() ? "" : "; first limiting cells: " + cells);
    }
}
