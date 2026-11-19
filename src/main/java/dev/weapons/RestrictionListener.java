package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Item rules:
 *  - no weapon can be renamed at an anvil,
 *  - Durandal, Eldritch Mace and Halberd of Despair cannot be enchanted at all (anvil, table, books),
 *  - the Revolver only accepts Unbreaking and Mending,
 *  - weapons cannot go through a grindstone, and cannot be used as the second anvil item (that could copy their secret enchants).
 * Admin commands such as /enchant are not affected.
 */
final class RestrictionListener implements Listener {

    private final WeaponsPlugin plugin;

    RestrictionListener(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    private static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static boolean allowedOnRevolver(Enchantment en) {
        return en.equals(Enchantment.UNBREAKING) || en.equals(Enchantment.MENDING);
    }

    // ------------------------------------------------------------------ anvil

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent e) {
        Inventory inv = e.getInventory();
        ItemStack first = inv.getItem(0);
        ItemStack second = inv.getItem(1);
        WeaponType a = plugin.items().identify(first);
        WeaponType b = plugin.items().identify(second);

        if (b != null) {
            e.setResult(null); // a weapon is never an ingredient
            return;
        }
        if (a == null) {
            return;
        }
        if (a.sealed() && second != null && !second.getType().isAir()) {
            e.setResult(null); // no enchanting or combining with these three
            return;
        }

        ItemStack result = e.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }

        String origName = plain(first.getItemMeta().customName());
        String newName = plain(result.getItemMeta().customName());
        boolean renamed = !origName.equals(newName);

        if (second == null || second.getType().isAir()) {
            // Only a rename is possible here
            if (renamed) {
                e.setResult(null);
            }
            return;
        }

        // Combining with an ingredient (book, repair item): keep the name, and limit the revolver
        Component keep = first.getItemMeta().customName();
        ItemStack fixed = result.clone();
        fixed.editMeta(m -> {
            m.customName(keep);
            if (a == WeaponType.MEDUSAS_REVOLVER) {
                for (Enchantment en : Map.copyOf(m.getEnchants()).keySet()) {
                    if (!allowedOnRevolver(en)) {
                        m.removeEnchant(en);
                    }
                }
            }
        });
        if (a == WeaponType.MEDUSAS_REVOLVER && fixed.getEnchantments().equals(first.getEnchantments())) {
            e.setResult(null); // nothing allowed was added
            return;
        }
        e.setResult(fixed);
    }

    // ------------------------------------------------------------------ enchanting table

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareEnchant(PrepareItemEnchantEvent e) {
        WeaponType t = plugin.items().identify(e.getItem());
        if (t != null && t.sealed()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnchant(EnchantItemEvent e) {
        WeaponType t = plugin.items().identify(e.getItem());
        if (t == null) {
            return;
        }
        if (t.sealed()) {
            e.setCancelled(true);
            return;
        }
        if (t == WeaponType.MEDUSAS_REVOLVER) {
            e.getEnchantsToAdd().keySet().removeIf(en -> !allowedOnRevolver(en));
            if (e.getEnchantsToAdd().isEmpty()) {
                e.setCancelled(true);
            }
        }
    }

    // ------------------------------------------------------------------ grindstone

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGrindstone(PrepareGrindstoneEvent e) {
        Inventory inv = e.getInventory();
        if (plugin.items().identify(inv.getItem(0)) != null || plugin.items().identify(inv.getItem(1)) != null) {
            e.setResult(null);
        }
    }
}
