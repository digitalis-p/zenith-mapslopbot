package io.github.digitalisp.mapart.bot.impl;

import com.zenith.mc.enchantment.EnchantmentData;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.InventoryView;

/**
 * Durability of one item stack, read from its 1.21.4 data components. A stack only carries a patch on top of the
 * item's default components: {@code DAMAGE} is present only when non-zero, and {@code MAX_DAMAGE} normally comes
 * from the item registry ({@code ItemData.components()}, as ZenithProxy's {@code ItemUtil.getMaxDamage}); a stack
 * that overrides it wins. Mending is looked up by id in ZenithProxy's enchantment registry, which is rebuilt from
 * the server's registry on join (never a hard-coded id).
 */
final class Wear {
    private Wear() { }

    /** Maximum durability, 0 if the item takes no damage. */
    static int maxUses(@Nullable ItemStack s) {
        if (Inv.empty(s)) return 0;
        Integer max = s.getDataComponentsOrEmpty().get(DataComponentTypes.MAX_DAMAGE);
        if (max == null) {
            ItemData d = ItemRegistry.REGISTRY.get(s.getId());
            if (d != null && d.components() != null) max = d.components().get(DataComponentTypes.MAX_DAMAGE);
        }
        return max == null ? 0 : Math.max(0, max);
    }

    /** Current damage (0 when the component is absent). */
    static int damage(@Nullable ItemStack s) {
        if (Inv.empty(s)) return 0;
        Integer d = s.getDataComponentsOrEmpty().get(DataComponentTypes.DAMAGE);
        return d == null ? 0 : Math.max(0, d);
    }

    static boolean unbreakable(@Nullable ItemStack s) {
        return !Inv.empty(s) && s.getDataComponentsOrEmpty().get(DataComponentTypes.UNBREAKABLE) != null;
    }

    /** Wears out with use: has a maximum durability and is not unbreakable. */
    static boolean damageable(@Nullable ItemStack s) {
        return maxUses(s) > 0 && !unbreakable(s);
    }

    /** Uses left before the item breaks; {@link Integer#MAX_VALUE} for an item that never wears. */
    static int remaining(@Nullable ItemStack s) {
        if (!damageable(s)) return Integer.MAX_VALUE;
        return Math.max(0, maxUses(s) - damage(s));
    }

    /** Remaining durability as a fraction of the maximum, 0..1 (1 for items that never wear). */
    static double fraction(@Nullable ItemStack s) {
        if (!damageable(s)) return 1.0;
        return Math.max(0.0, Math.min(1.0, (double) remaining(s) / maxUses(s)));
    }

    static boolean mending(@Nullable ItemStack s) {
        if (Inv.empty(s)) return false;
        DataComponents own = s.getDataComponentsOrEmpty();
        ItemEnchantments ench = own.get(DataComponentTypes.ENCHANTMENTS);
        if (ench == null || ench.getEnchantments() == null) return false;
        EnchantmentData mending = EnchantmentRegistry.MENDING.get();
        return mending != null && ench.getEnchantments().containsKey(mending.id());
    }

    /** The stack's Unbreaking level (0 without it), looked up like {@link #mending}. */
    static int unbreaking(@Nullable ItemStack s) {
        if (Inv.empty(s)) return 0;
        ItemEnchantments ench = s.getDataComponentsOrEmpty().get(DataComponentTypes.ENCHANTMENTS);
        if (ench == null || ench.getEnchantments() == null) return 0;
        EnchantmentData unbreaking = EnchantmentRegistry.UNBREAKING.get();
        return unbreaking == null ? 0 : Math.max(0, ench.getEnchantments().getOrDefault(unbreaking.id(), 0));
    }

    /** Block-breaking tool kind of the stack ({@link InventoryView#toolKind}), or null. */
    static @Nullable String kind(@Nullable ItemStack s) {
        return InventoryView.toolKind(Inv.id(s));
    }

    /** The stack as an {@link InventoryView.Tool} (unbreakable: remaining = max). */
    static InventoryView.Tool tool(ItemStack s) {
        int max = maxUses(s);
        int rem = damageable(s) ? remaining(s) : max;
        return new InventoryView.Tool(Inv.id(s), rem, max, mending(s));
    }
}
