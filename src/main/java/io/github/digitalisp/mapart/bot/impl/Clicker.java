package io.github.digitalisp.mapart.bot.impl;

import com.zenith.Proxy;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.InputRequestFuture;
import com.zenith.feature.player.raycast.BlockRaycastResult;
import com.zenith.feature.player.raycast.RayIntersection;
import com.zenith.network.client.ClientSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.jspecify.annotations.Nullable;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.INVENTORY;

/**
 * Input and click plumbing for one task.
 *
 * <p><b>Rotation.</b> Every active tick the task submits exactly one {@link InputRequest} (rotation + movement keys)
 * at {@link #PRIORITY} (above Baritone 7000, below AutoEat 11000 / AutoTotem 13000).
 *
 * <p><b>Use-item-on is sent by this class, not by ZenithProxy's Bot.</b> ZenithProxy 3.7.2
 * {@code PlayerInteractionManager.useItemOn} always sends cursor (0,0,0) ("todo: cursor raytrace"); a real client
 * sends the hit point (for a top-face click the cursor y is 1.0), so a server-side check comparing cursor and face
 * would see a fabricated click. We compute the hit with ZenithProxy's own (non through-walls) raycast from the bot's
 * eye with the rotation in effect, then send {@code ServerboundUseItemOnPacket} (face + exact cursor, block-state
 * prediction sequence) and a swing from the task tick. The engine ticks tasks from a ClientBotTick handler that runs
 * before ZenithProxy's InventoryManager (-5000), InputManager (-10000) and Bot (-20000), so the packet order is the
 * vanilla one: [hotbar select,] use-item-on, swing, then the movement packet with this tick's rotation request.
 * The lane placer clicks with the rotation sent on the previous tick and requests the next target's rotation in
 * the same tick ({@link PlacePipeline}). A click is only sent when the request submitted on the previous tick was
 * accepted and executed (proves the tick order and that no higher-priority module owns the input) and the request
 * for this tick was not rejected.
 *
 * <p><b>Wire order.</b> Our {@code send}/{@code sendAwait} and ZenithProxy's {@code sendAsync} all queue on the
 * channel's event loop in call order (the client tick runs on ZenithProxy's separate client event loop), so the
 * packets above leave in the order the tick produced them.
 */
final class Clicker {
    /** Input / inventory request priority of the map-art bot. */
    static final int PRIORITY = 7600;
    /** Last submitted use-item-on timestamp; written and read only on the client event loop. */
    static long lastClickNanos;

    private final Object owner;
    /** Our request of the tick before {@link #current}'s, or null. */
    private @Nullable Req last;
    /** Our latest request, or null. */
    private @Nullable Req current;
    private int lostTicks;

    /** One submitted request: its tick, future, movement keys, and the bot's position when it was submitted. */
    private record Req(long tick, InputRequestFuture future, boolean moving, double x, double y, double z) { }

    Clicker(Object owner) {
        this.owner = owner;
    }

    /**
     * Submits this tick's rotation/movement. Call at most once per tick, before {@link #canClick}.
     * {@code yaw}/{@code pitch} may be null to keep the current rotation.
     */
    InputRequestFuture submit(long now, @Nullable Float yaw, @Nullable Float pitch, @Nullable Input input) {
        if (current != null && current.tick() != now) last = current;
        var b = InputRequest.builder().owner(owner).priority(PRIORITY);
        if (input != null) b.input(input);
        if (yaw != null) b.yaw(continuousYaw(yaw));
        if (pitch != null) b.pitch(effectivePitch(pitch));
        InputRequestFuture f = INPUTS.submit(b.build());
        current = new Req(now, f, moving(input), BOT.getX(), BOT.getY(), BOT.getZ());
        if (f == InputRequestFuture.rejected) lostTicks++;
        else lostTicks = 0;
        return f;
    }

    /** Submits a right-click "use item" (no block target) through ZenithProxy's Bot with the given rotation. */
    InputRequestFuture submitUseItem(long now, float yaw, float pitch) {
        Input in = Input.builder()
            .rightClick(true)
            .hand(Hand.MAIN_HAND)
            .clickTarget(ClickTarget.None.INSTANCE)
            .clickRequiresRotation(true)
            .build();
        return submit(now, yaw, pitch, in);
    }

    /** Submits a held left click (start/continue destroy) through ZenithProxy's Bot. */
    InputRequestFuture submitMine(long now, float yaw, float pitch, ClickTarget target) {
        Input in = Input.builder()
            .leftClick(true)
            .hand(Hand.MAIN_HAND)
            .clickTarget(target)
            .clickRequiresRotation(true)
            .build();
        return submit(now, yaw, pitch, in);
    }

    /**
     * Forgets the requests submitted so far, for a task that continues after a park (other tasks owned the input
     * meanwhile): {@link #canClick}, {@link #stillLastTick} and {@link #ownedLastTick} are false until a request of
     * this task was executed again.
     */
    void reset() {
        last = null;
        current = null;
        lostTicks = 0;
    }

    /** Ticks in a row our request was rejected outright (a higher-priority module owns the input). */
    int lostTicks() {
        return lostTicks;
    }

    /** A between-tick click also needs this tick's input to have executed and sprint to be quiet. */
    boolean completedInput() {
        return current != null && current.future().getNow() && !Inv.containerOpen()
            && SprintLegs.Watch.SESSION.quiet(BOT.isSprinting());
    }

    /**
     * True if last tick's request was executed and this tick's request is pending; see class doc. Also waits until the
     * bot has not sprinted for {@code build.sprintQuietTicks} ({@link SprintLegs.Watch}; always true while nothing
     * sprints).
     */
    boolean canClick(long now) {
        return current != null && current.tick() == now && current.future() != InputRequestFuture.rejected
            && last != null && last.tick() == now - 1 && last.future().getNow()
            && !Inv.containerOpen() && SprintLegs.Watch.SESSION.quiet(BOT.isSprinting());
    }

    /**
     * Outside menus: true if our request of the previous tick was executed and pressed no movement key
     * ({@link InputGate#stillOutside}). Inventory clicks are only safe then: Grim (MultiActionsC) flags, and may cancel,
     * a container click while the last input packet shows movement keys or sprinting. A vanilla client cannot click
     * slots while walking. Never true while a container is open (ZenithProxy completes every input request false then):
     * the task's own menu asks {@link InputGate#stillInMenu} with {@link #previous} instead ({@link ContainerRun}). Not
     * within {@code build.sprintQuietTicks} of the bot sprinting either ({@link SprintLegs.Watch}).
     */
    boolean stillLastTick(long now) {
        return InputGate.stillOutside(now, previous(now)) && SprintLegs.Watch.SESSION.quiet(BOT.isSprinting());
    }

    /**
     * Our request submitted on the tick before {@code now} (whether or not this tick's was submitted yet) and what
     * ZenithProxy did with it so far; null if there is none.
     */
    InputGate.@Nullable Sent previous(long now) {
        Req p = current != null && current.tick() == now ? last : current;
        if (p == null) return null;
        return new InputGate.Sent(p.tick(), p.moving(), outcome(p.future()), p.x(), p.y(), p.z());
    }

    private static InputGate.Outcome outcome(InputRequestFuture f) {
        if (f == InputRequestFuture.rejected) return InputGate.Outcome.REJECTED;
        if (!f.isCompleted()) return InputGate.Outcome.PENDING;
        return f.isAccepted() ? InputGate.Outcome.EXECUTED : InputGate.Outcome.DROPPED;
    }

    static boolean moving(@Nullable Input in) {
        return in != null && (in.pressingForward || in.pressingBack || in.pressingLeft || in.pressingRight
            || in.jumping || in.sprinting);
    }

    /**
     * The equivalent of {@code yaw} closest to the bot's current (unbounded, continuous) yaw. ZenithProxy's Bot wraps
     * the requested difference only once, so once the bot's yaw has drifted past ±540° a request in [-180, 180] would
     * make it jump by a multiple of 360° (Grim AimModulo360). Pure arithmetic, see {@link Aim#nearestYaw}.
     */
    static float continuousYaw(float yaw) {
        return Aim.nearestYaw(BOT.getYaw(), yaw);
    }

    /**
     * Before Baritone takes over the rotation: turns the bot's yaw back into [-180, 180] in steps of at most 170° (no
     * movement), because Baritone's requests in [-180, 180] go through ZenithProxy's single wrap as well. True while
     * turning (call once per tick instead of {@link #submit}).
     */
    boolean unwindYaw(long now) {
        Float next = unwindStep(BOT.getYaw());
        if (next == null) return false;
        submit(now, next, null, null);
        return true;
    }

    /** The yaw {@link #unwindYaw} asks for from {@code cur}: at most 170° away, towards [-180, 180]; null if inside it. */
    static @Nullable Float unwindStep(float cur) {
        if (Math.abs(cur) <= 180f) return null;
        float target = Aim.nearestYaw(0f, cur);
        float step = Math.max(-170f, Math.min(170f, target - cur));
        return cur + step;
    }

    /** True if our request of the previous tick was accepted and executed. */
    boolean ownedLastTick(long now) {
        Req p = current != null && current.tick() == now ? last : current;
        return p != null && p.tick() == now - 1 && p.future().getNow();
    }

    /** ZenithProxy keeps the old pitch for changes below 0.01 deg (botPitchPrecisionClamping); mirror it. */
    static float effectivePitch(float pitch) {
        float p = Math.max(-90f, Math.min(90f, pitch));
        if (CONFIG.debug.botPitchPrecisionClamping && Math.abs(p - BOT.getPitch()) < 0.01f) return BOT.getPitch();
        return p;
    }

    /**
     * Selects hotbar slot {@code slot} now, for a use-item-on later in the same tick (vanilla order: the carried-item
     * packet precedes the click; a change after a click in the same tick is Grim PacketOrderE). Sent with
     * {@code sendAwait} from the client tick thread: ZenithProxy's post-outgoing handler then updates the cached held
     * slot before this returns (it runs inline on the client event loop), exactly as for its own InventoryManager.
     * Never re-sends the selected slot (Grim BadPacketsA) and does nothing while a container is open or an inventory
     * request is executing. True if {@code slot} is selected afterwards.
     */
    static boolean selectNow(int slot) {
        if (slot < 0 || slot > 8) return false;
        if (Inv.heldSlot() == slot) return true;
        if (Inv.containerOpen() || INVENTORY.hasActiveRequest()) return false;
        ClientSession client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected()) return false;
        client.sendAwait(new ServerboundSetCarriedItemPacket(slot));
        return Inv.heldSlot() == slot;
    }

    /**
     * Sends one use-item-on for {@code ray} (main hand) plus a swing. Returns false if not connected.
     * The cursor is the exact hit point relative to the block, the face is the hit face.
     */
    static boolean sendUseItemOn(BlockRaycastResult ray) {
        ClientSession client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected() || !ray.hit()) return false;
        RayIntersection hit = ray.intersection();
        float[] cursor = hit == null
            ? new float[]{0.5f, 1f, 0.5f}
            : Aim.cursor(hit.x(), hit.y(), hit.z(), ray.x(), ray.y(), ray.z());
        try (var prediction = CACHE.getChunkCache().getBlockStatePredictionHandler().startPredicting()) {
            int seq = prediction.currentSequence();
            client.send(new ServerboundUseItemOnPacket(
                ray.x(), ray.y(), ray.z(),
                ray.direction().mcpl(),
                Hand.MAIN_HAND,
                cursor[0], cursor[1], cursor[2],
                false,
                false,
                seq));
            lastClickNanos = System.nanoTime();
        }
        client.sendAsync(new ServerboundSwingPacket(Hand.MAIN_HAND));
        return true;
    }
}
