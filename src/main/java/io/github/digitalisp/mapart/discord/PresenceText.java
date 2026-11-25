package io.github.digitalisp.mapart.discord;

import net.dv8tion.jda.api.OnlineStatus;
import io.github.digitalisp.mapart.command.StatusWords;
import io.github.digitalisp.mapart.engine.BuildEngine;
import io.github.digitalisp.mapart.engine.EngineControl.EngineStatus;
import io.github.digitalisp.mapart.engine.Phase;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The bot's Discord presence: one line under its name saying what the builder does, on which map, and how far it is,
 * plus an online status. Pure; {@link PresenceUpdater} sends it. The words are {@link StatusWords}, shared with
 * {@code mapart status}, the queue listing and the progress post.
 *
 * <p>Line: {@code <doing> · <job name> · <how far> · +N queued}. No job ids and no tile coordinates (owner: they are
 * useless on Discord). Examples:
 * <ul>
 *   <li>one map: {@code Printing · Sunset · 43% · ~25m left};</li>
 *   <li>a job of several maps: {@code Printing · Sunset · map 2 of 4 (72%) · 1 map done · job ~1h 30m left}: the map's
 *   place in the job's build order with its own percent, then the maps stored;</li>
 *   <li>a colour-suppression map (ONE map printed on two layers; owner 2026-11-14: "tile 7,0 ... 7/8 maps done" was
 *   wrong, and "What does pass mean?"): {@code Printing · Fox · top layer · 62% · ~2h 10m left}, the percent over the
 *   whole map's blocks; its waits in words ({@code Waiting for the muncher to clear the bottom layer (1,240 blocks
 *   left)}, {@code Waiting for materials: 1,122 cyan terracotta}); then {@code Filling the map}, {@code Pre-ride
 *   checks}, {@code Riding the preload lap}, {@code Riding the muncher · Fox · 41 of ~75 min (54%)} (time since the
 *   launch against the expected ride), {@code Leaving the cart}, {@code Locking the map}, {@code Naming the map},
 *   {@code Returning the cart}, {@code Storing the map}, {@code Counting the map};</li>
 *   <li>{@code +5 queued}: the other unfinished jobs, when there are any.</li>
 * </ul>
 * Placed/total comes from the live engine snapshot, else from the job's saved tile state (the last known count), so
 * steps without live numbers still show the last known %. Percentages are rounded down, so 100% only once done.
 * Without an active job the tail is the queue ("queue empty", "3 jobs queued").
 *
 * <p>States that come before the step: {@code SAFETY STOP: <reason>}, {@code Paused: <reason>}, {@code Needs help:
 * <reason>} (BLOCKED), {@code Builder off}, {@code Disconnected}, {@code In the server queue (position 123)},
 * {@code A player is controlling the account}.
 *
 * <p>Online status (the colour dot, {@link #statusFor}): ONLINE while working, IDLE while waiting with nothing to
 * do, DO_NOT_DISTURB when a human is needed.
 *
 * <p>At most {@value #MAX_LENGTH} chars (Discord's limit for an activity state): the job name is shortened first (down
 * to {@value #NAME_MIN} chars), then the first part (a long reason or note); the progress parts and the queue count
 * are never cut. No markdown escaping (custom statuses show plain text). Never shows coordinates: pause, safety-stop
 * and BLOCKED reasons are redacted (position groups and numbers with 4+ digits become "…").
 */
public final class PresenceText {
    private PresenceText() { }

    public static final int MAX_LENGTH = 128;
    static final int NAME_MAX = 40;
    static final int NAME_MIN = 10;
    static final int NOTE_MAX = 60;
    static final int HEAD_MIN = 12;
    static final String SEP = " · ";
    static final String SAFETY_PREFIX = "SAFETY STOP";

    private static final Pattern POSITION =
        Pattern.compile("\\[?-?\\d+(?:\\.\\d+)?(?:\\s*,\\s*-?\\d+(?:\\.\\d+)?){1,2}]?");
    private static final Pattern LONG_NUMBER = Pattern.compile("-?\\d{4,}(?:\\.\\d+)?");
    private static final Pattern PHASE_PREFIX = Pattern.compile("^([A-Z_]+): ");
    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Cntrl}]+");

    /** Where the Minecraft account is. */
    public enum Link { IN_GAME, IN_QUEUE, OFFLINE, PLAYER_IN_CONTROL }

    /**
     * Everything the line is made from. {@code status} null = engine not built yet; {@code job} = the job
     * {@code status.jobId()} names (null if none or unknown); counts are over non-terminal jobs.
     */
    public record Input(EngineStatus status, Job job, boolean builderEnabled, Link link, int queuePosition,
                        int queuedJobs, int waitingJobs, long now) {
        public Input(EngineStatus status, Job job, boolean builderEnabled, Link link, int queuePosition, int queuedJobs,
                     int waitingJobs) {
            this(status, job, builderEnabled, link, queuePosition, queuedJobs, waitingJobs, 0);
        }

        /** The time of the snapshot (epoch ms): the ride's progress is counted to it. 0 = not known (none shown). */
        public Input at(long time) {
            return new Input(status, job, builderEnabled, link, queuePosition, queuedJobs, waitingJobs, time);
        }
    }

    /** {@code text} null = no custom status. */
    public record Presence(OnlineStatus status, String text) { }

    /** Collects the input from the engine snapshot and the job list ({@code JobStore.list()}). */
    public static Input input(EngineStatus status, List<Job> jobs, boolean builderEnabled, Link link,
                              int queuePosition) {
        String activeId = status == null ? null : status.jobId();
        Job active = null;
        int queued = 0;
        int waiting = 0;
        if (jobs != null) {
            for (Job j : jobs) {
                if (j == null) continue;
                if (activeId != null && activeId.equals(j.id)) active = j;
                if (j.status == null || j.status.terminal()) continue;
                queued++;
                if (j.status == JobStatus.WAITING_MATERIALS) waiting++;
            }
        }
        return new Input(status, active, builderEnabled, link == null ? Link.IN_GAME : link, queuePosition,
            queued, waiting);
    }

    public static Presence describe(Input in) {
        EngineStatus s = in.status();
        Phase phase = phase(s);
        boolean hasJob = s != null && s.jobId() != null;
        String head;
        boolean queueInHead = false;
        if (safetyStop(s)) {
            String why = note(s.pauseReason().substring(SAFETY_PREFIX.length()).replaceFirst("^[:\\s]+", ""));
            head = why.isEmpty() ? SAFETY_PREFIX : SAFETY_PREFIX + ": " + why;
        } else if (s != null && s.paused()) {
            head = paused(s.pauseReason());
        } else if (phase == Phase.BLOCKED) {
            String note = note(s.note());
            head = note.isEmpty() ? "Needs help" : "Needs help: " + note;
        } else if (!in.builderEnabled()) {
            head = "Builder off";
        } else if (in.link() == Link.OFFLINE) {
            head = "Disconnected";
        } else if (in.link() == Link.IN_QUEUE) {
            head = "In the server queue" + (in.queuePosition() > 0 ? " (position " + in.queuePosition() + ")" : "");
        } else if (in.link() == Link.PLAYER_IN_CONTROL) {
            head = "A player is controlling the account";
        } else if (phase == Phase.IDLE && !hasJob && !backingOff(s) && !noteStarts(s, BuildEngine.EATING_NOTE)
            && !noteStarts(s, BuildEngine.MENDING_NOTE)) {
            head = idle(in);
            queueInHead = true;
        } else {
            head = StatusWords.doing(s, in.job());
        }
        OnlineStatus status = statusFor(in);

        if (!hasJob) return new Presence(status, fit(head, "", queueInHead ? List.of() : List.of(queue(in))));
        String rawName = in.job() != null && in.job().name != null ? in.job().name : s.jobName();
        String name = cut(clean(rawName), NAME_MAX);
        List<String> rest = new ArrayList<>(StatusWords.progress(s, in.job(), in.now()));
        int others = in.queuedJobs() - (in.job() != null && in.job().status != null && in.job().status.terminal() ? 0 : 1);
        if (others > 0) rest.add("+" + others + " queued");
        return new Presence(status, fit(head, name, rest));
    }

    /**
     * "Paused", "Paused by command" (the reason starts with "paused"), "Paused: lunch break"; the reason redacted like a
     * BLOCKED note ({@link #note}).
     */
    static String paused(String reason) {
        String r = note(reason);
        if (r.isEmpty() || r.equalsIgnoreCase("paused")) return "Paused";
        if (r.regionMatches(true, 0, "paused ", 0, 7)) return "Paused " + r.substring(7).strip();
        return "Paused: " + r;
    }

    /**
     * The online status, the colour dot next to the line; from the same snapshot, so {@link PresenceUpdater} sends both
     * in one update.
     * <ul>
     *   <li>DO_NOT_DISTURB, a human is needed: a SAFETY STOP (the kill switch {@code !map stop} is one too), the "Stuck"
     *   pause (setback escapes used up), BLOCKED (also while paused).</li>
     *   <li>IDLE, waiting with nothing to do: paused by hand, builder off, disconnected, in the server queue, a player in
     *   control, phase IDLE (queue empty, every job short of materials), WAIT_CLEAR (also the carts' run-on), the
     *   FILL_MAP waits for the platform above and for the eyeblossoms, CHECK_MAP holding a map whose data has not come
     *   ({@link BuildEngine#MAP_DATA_NOTE}), a partly built map waiting for materials ({@link BuildEngine#MATERIALS_NOTE}),
     *   a colour-suppression map's waits for the muncher (a layer still being cleared, machines on their way home
     *   before the ride, the reset after it) and for a server restart before the ride, and a setback back-off standing
     *   still.</li>
     *   <li>ONLINE otherwise: a step runs, including eating, mending and the escape walk after a back-off.</li>
     * </ul>
     */
    public static OnlineStatus statusFor(Input in) {
        EngineStatus s = in.status();
        Phase phase = phase(s);
        if (safetyStop(s) || stuck(s) || phase == Phase.BLOCKED)
            return OnlineStatus.DO_NOT_DISTURB;
        if (s != null && s.paused()) return OnlineStatus.IDLE;
        if (!in.builderEnabled() || (in.link() != null && in.link() != Link.IN_GAME)) return OnlineStatus.IDLE;
        return waiting(phase, s) ? OnlineStatus.IDLE : OnlineStatus.ONLINE;
    }

    /** The engine's phase; IDLE before the engine is built. */
    private static Phase phase(EngineStatus s) {
        return s == null || s.phase() == null ? Phase.IDLE : s.phase();
    }

    /** Paused by {@link BuildEngine#stuck}: the setback escape ladder is used up, a human must look. */
    static boolean stuck(EngineStatus s) {
        return s != null && s.paused() && s.pauseReason() != null && s.pauseReason().startsWith(BuildEngine.STUCK_PREFIX);
    }

    /**
     * The engine waits with nothing to do (see {@link #statusFor}). Eating and mending are work in any phase; a back-off
     * standing still is a wait in any phase.
     */
    static boolean waiting(Phase phase, EngineStatus s) {
        if (noteStarts(s, BuildEngine.EATING_NOTE) || noteStarts(s, BuildEngine.MENDING_NOTE)) return false;
        if (standingStill(s)) return true;
        return switch (phase) {
            case IDLE, WAIT_CLEAR -> true;
            case INSPECT -> noteStarts(s, BuildEngine.LAYER_WAIT_NOTE);
            case RIDE_BOARD -> noteStarts(s, BuildEngine.GUARD_WAIT_NOTE) || noteStarts(s, BuildEngine.RESTART_NOTE);
            case RIDE_RESET -> noteStarts(s, BuildEngine.RESET_WAIT_NOTE);
            case FILL_MAP -> waitingForCover(phase, s) || waitingForBlossoms(phase, s);
            case CHECK_MAP -> noteStarts(s, BuildEngine.MAP_DATA_NOTE);
            case STOCK_CHECK -> waitingForMaterials(phase, s);
            default -> false;
        };
    }

    /** A setback back-off standing still: the note is exactly the back-off note (the escape walk after it adds to it). */
    static boolean standingStill(EngineStatus s) {
        return s != null && BuildEngine.BACKOFF_NOTE.equals(s.note());
    }

    /**
     * FILL_MAP with the engine's cover note ({@link BuildEngine#COVER_NOTE}): blocks above the art (the platform above
     * in use) hide it on the map, so the engine holds the map at the centre until they are gone. The note is the head:
     * "Waiting: platform above in use (3,200 pixels covered)".
     */
    static boolean waitingForCover(Phase phase, EngineStatus s) {
        return phase == Phase.FILL_MAP && noteStarts(s, BuildEngine.COVER_NOTE);
    }

    /** FILL_MAP with the engine's eyeblossom note: it waits at the map for the world to flip the eyeblossoms. */
    static boolean waitingForBlossoms(Phase phase, EngineStatus s) {
        return phase == Phase.FILL_MAP && s.note() != null && s.note().startsWith(BuildEngine.EYEBLOSSOM_NOTE);
    }

    /**
     * STOCK_CHECK with the engine's materials note ({@link BuildEngine#MATERIALS_NOTE}): a partly built map waits on its
     * platform for the items the stock lacks. The note is the head: "Waiting for materials: 1,122 cyan terracotta".
     */
    static boolean waitingForMaterials(Phase phase, EngineStatus s) {
        return phase == Phase.STOCK_CHECK && noteStarts(s, BuildEngine.MATERIALS_NOTE);
    }

    /** The engine's status note starts with {@code prefix} (eating, mending: they interrupt any phase). */
    static boolean noteStarts(EngineStatus s, String prefix) {
        return s != null && s.note() != null && s.note().startsWith(prefix);
    }

    /** The engine's setback back-off note: it stands still for a few seconds, then carries on. */
    static boolean backingOff(EngineStatus s) {
        return s != null && s.note() != null && s.note().startsWith(BuildEngine.BACKOFF_NOTE);
    }

    /** Short "what is it doing" for a phase ({@link StatusWords#verb}). */
    static String verb(Phase p) {
        return StatusWords.verb(p);
    }

    static boolean safetyStop(EngineStatus s) {
        return s != null && s.paused() && s.pauseReason() != null && s.pauseReason().startsWith(SAFETY_PREFIX);
    }

    private static String idle(Input in) {
        int n = in.queuedJobs();
        if (n <= 0) return "Idle, queue empty";
        if (in.waitingJobs() >= n) return "Waiting for materials (" + jobs(n) + ")";
        return "Idle, " + jobs(n) + " queued";
    }

    private static String queue(Input in) {
        int n = in.queuedJobs();
        if (n <= 0) return "queue empty";
        if (in.waitingJobs() >= n) return jobs(n) + " waiting for materials";
        return jobs(n) + " queued";
    }

    private static String jobs(int n) {
        return n + (n == 1 ? " job" : " jobs");
    }

    /** Whole percent, rounded down (100 only when complete). */
    static int percent(double fraction) {
        return StatusWords.percent(fraction);
    }

    // ---------------------------------------------------------------- text

    /** A BLOCKED note made short and safe: no phase prefix, no namespaces, no coordinates. */
    static String note(String raw) {
        String n = clean(raw);
        var m = PHASE_PREFIX.matcher(n);
        if (m.find() && isPhase(m.group(1))) n = n.substring(m.end());
        n = n.replace("minecraft:", "");
        n = POSITION.matcher(n).replaceAll("…");
        n = LONG_NUMBER.matcher(n).replaceAll("…");
        return cut(n.strip(), NOTE_MAX);
    }

    private static boolean isPhase(String s) {
        for (Phase p : Phase.values()) if (p.name().equals(s)) return true;
        return false;
    }

    /** One line: control characters and runs of whitespace become one space. */
    static String clean(String s) {
        return s == null ? "" : SPACE.matcher(s).replaceAll(" ").strip();
    }

    /**
     * Joins the parts and makes them fit: the name is shortened first (to {@link #NAME_MIN}), then the head (to
     * {@link #HEAD_MIN}); {@code rest} (the numbers) is never cut unless even that is not enough.
     */
    static String fit(String head, String name, List<String> rest) {
        String line = compose(head, name, rest);
        if (line.length() > MAX_LENGTH && name.length() > NAME_MIN) {
            name = cut(name, Math.max(NAME_MIN, name.length() - (line.length() - MAX_LENGTH)));
            line = compose(head, name, rest);
        }
        if (line.length() > MAX_LENGTH && head.length() > HEAD_MIN) {
            head = cut(head, Math.max(HEAD_MIN, head.length() - (line.length() - MAX_LENGTH)));
            line = compose(head, name, rest);
        }
        return cut(line, MAX_LENGTH);
    }

    private static String compose(String head, String name, List<String> rest) {
        StringBuilder sb = new StringBuilder(head);
        if (!name.isEmpty()) sb.append(SEP).append(name);
        for (String r : rest) if (!r.isEmpty()) sb.append(SEP).append(r);
        return sb.toString();
    }

    /** Truncates to {@code max} chars ending in "…", never splitting a surrogate pair. */
    static String cut(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        if (max <= 0) return "";
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end).stripTrailing() + "…";
    }
}
