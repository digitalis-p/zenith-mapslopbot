package io.github.digitalisp.mapart.bot;

/**
 * Exclusive use of a block whose menu a task opens (a container, the cartography table, the anvil): asked for before
 * the click that opens the menu, given back when the task ends, however it ends. Keys are claim keys
 * ({@code chest:<x,y,z>} of the container's canonical block, {@code station:cartography}, {@code station:anvil}).
 * Non-blocking: the task polls {@link #mayOpen} once per tick and holds still until it says yes.
 * {@link #OPEN} (the default) always says yes: one bot, nobody to share with.
 */
public interface MenuGate {
    /** True once the menu behind {@code key} may be opened; the first call asks for it, later calls poll. */
    boolean mayOpen(String key);

    /** The task that asked for {@code key} ended (menu closed, cancelled, failed): give it back. Idempotent. */
    void done(String key);

    /**
     * Polled every tick while the menu behind {@code key} is open (or its click went out): false once its claim is gone
     * (lost to its ttl, or the link to the coordinator down), and the task closes the menu at once. Default: true.
     */
    default boolean mayKeep(String key) {
        return true;
    }

    /** The cartography table's key (one per base: {@code site.cartographyTable}). */
    String CARTOGRAPHY = "station:cartography";
    /** The anvil's key ({@code site.anvil}). */
    String ANVIL = "station:anvil";

    /** {@code chest:<x,y,z>} of a container's canonical block (a double chest's own half). */
    static String chest(io.github.digitalisp.mapart.model.Pos container) {
        return "chest:" + container.key();
    }

    MenuGate OPEN = new MenuGate() {
        @Override
        public boolean mayOpen(String key) {
            return true;
        }

        @Override
        public void done(String key) { }
    };
}
