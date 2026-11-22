package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * AK-47 magazines (iron nuggets). There are two looks, an EMPTY magazine and a loaded one, and every magazine remembers how many
 * rounds it holds (0 to 30).
 * <ul>
 *   <li>Craft an empty magazine: 8 iron ingots in a ring (the middle stays empty).</li>
 *   <li>Right-click a magazine to push 1 arrow from your inventory into it, sneak + right-click to push 5 at a time.</li>
 *   <li>Reloading the AK-47 uses the fullest magazines first, then loose arrows. The spent magazine comes back to you, empty
 *       (or with the rounds the gun did not need).</li>
 * </ul>
 */
final class AkMagazine implements Listener {

    private final WeaponsPlugin plugin;
    private final NamespacedKey roundsKey;
    private final NamespacedKey recipeKey;

    AkMagazine(WeaponsPlugin plugin) {
        this.plugin = plugin;
        this.roundsKey = new NamespacedKey(plugin, "ak_magazine_rounds");
        this.recipeKey = new NamespacedKey(plugin, "ak_magazine_recipe");
        registerRecipe();
    }

    int capacity() {
        return Math.max(1, plugin.getConfig().getInt("weapons.ak47.magazine", 30));
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("magazine.enabled", true);
    }

    // ------------------------------------------------------------------ the item

    /** A magazine holding {@code rounds} rounds (0 = the empty magazine). */
    ItemStack create(int rounds) {
        int r = Math.max(0, Math.min(capacity(), rounds));
        ItemStack it = new ItemStack(Material.IRON_NUGGET);
        it.editMeta(m -> {
            m.customName(Component.text(r == 0 ? "Empty AK-47 Magazine" : "AK-47 Magazine", r == 0 ? NamedTextColor.GRAY : NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(
                    Component.text("Rounds: " + r + "/" + capacity(), r == 0 ? NamedTextColor.RED : NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                    Component.text("Right-click: load 1 arrow. Sneak + right-click: load 5.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Reloads the AK-47. Magazines are used before loose arrows.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
            m.setMaxStackSize(16);
            m.getPersistentDataContainer().set(roundsKey, PersistentDataType.INTEGER, r);
        });
        try {
            it.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                    io.papermc.paper.datacomponent.item.CustomModelData.customModelData()
                            .addString(r == 0 ? "ak47_magazine_empty" : "ak47_magazine").build());
        } catch (Throwable ignored) {
            // the Java pack matches on the name
        }
        return it;
    }

    boolean is(ItemStack it) {
        return it != null && it.getType() == Material.IRON_NUGGET && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(roundsKey, PersistentDataType.INTEGER);
    }

    int rounds(ItemStack it) {
        if (!is(it)) {
            return 0;
        }
        Integer r = it.getItemMeta().getPersistentDataContainer().get(roundsKey, PersistentDataType.INTEGER);
        return r == null ? 0 : r;
    }

    // ------------------------------------------------------------------ filling

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = e.getItem();
        if (!is(item) || (e.getHand() != EquipmentSlot.HAND && e.getHand() != EquipmentSlot.OFF_HAND)) {
            return;
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        int have = rounds(item);
        int room = capacity() - have;
        if (room <= 0) {
            p.sendActionBar(Component.text("This magazine is full (" + capacity() + "/" + capacity() + ").", NamedTextColor.YELLOW));
            return;
        }
        int want = Math.min(room, p.isSneaking() ? 5 : 1);
        int got = takeArrows(p, want);
        if (got <= 0) {
            p.sendActionBar(Component.text("No arrows to load.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.6f, 1.6f);
            return;
        }
        ItemStack filled = create(have + got);
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1); // the rest of the stack stays as it was
            for (ItemStack left : p.getInventory().addItem(filled).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        } else if (e.getHand() == EquipmentSlot.OFF_HAND) {
            p.getInventory().setItemInOffHand(filled);
        } else {
            p.getInventory().setItemInMainHand(filled);
        }
        p.playSound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 0.5f, 1.6f);
        p.sendActionBar(Component.text("Magazine " + (have + got) + "/" + capacity(), NamedTextColor.GOLD));
    }

    /** Takes loose arrows (plain ones) out of the storage slots. */
    private int takeArrows(Player p, int want) {
        int remaining = want;
        ItemStack[] c = p.getInventory().getStorageContents();
        for (int i = 0; i < c.length && remaining > 0; i++) {
            ItemStack s = c[i];
            if (s == null || s.getType() != Material.ARROW) {
                continue;
            }
            int t = Math.min(remaining, s.getAmount());
            s.setAmount(s.getAmount() - t);
            if (s.getAmount() <= 0) {
                c[i] = null;
            }
            remaining -= t;
        }
        p.getInventory().setStorageContents(c);
        return want - remaining;
    }

    // ------------------------------------------------------------------ reload support

    /** Total rounds in every magazine the player carries. */
    int totalRounds(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (is(s)) {
                n += rounds(s) * s.getAmount();
            }
        }
        return n;
    }

    /**
     * Takes rounds out of magazines, the fullest first. Returns how many rounds are STILL needed. The magazine that was used
     * comes back: empty, or with the rounds the gun did not need.
     */
    int take(Player p, int need) {
        int remaining = need;
        List<ItemStack> back = new ArrayList<>();
        while (remaining > 0) {
            ItemStack[] c = p.getInventory().getStorageContents();
            int best = -1;
            int bestRounds = 0;
            for (int i = 0; i < c.length; i++) {
                if (is(c[i]) && rounds(c[i]) > bestRounds) {
                    best = i;
                    bestRounds = rounds(c[i]);
                }
            }
            if (best < 0) {
                break;
            }
            ItemStack s = c[best];
            s.setAmount(s.getAmount() - 1);
            if (s.getAmount() <= 0) {
                c[best] = null;
            }
            p.getInventory().setStorageContents(c);
            int used = Math.min(remaining, bestRounds);
            remaining -= used;
            back.add(create(bestRounds - used));
        }
        for (ItemStack b : back) {
            for (ItemStack left : p.getInventory().addItem(b).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        return remaining;
    }

    // ------------------------------------------------------------------ crafting: 8 iron ingots in a ring = an empty magazine

    private void registerRecipe() {
        try {
            Bukkit.removeRecipe(recipeKey);
            ShapedRecipe r = new ShapedRecipe(recipeKey, create(0));
            r.shape("III", "I I", "III");
            r.setIngredient('I', Material.IRON_INGOT);
            Bukkit.addRecipe(r);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not register the AK-47 magazine recipe: " + t);
        }
    }

    void shutdown() {
        Bukkit.removeRecipe(recipeKey);
    }
}
