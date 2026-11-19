package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shared ammo and reload for the crossbow-based guns (AK-47, Rocket Launcher).
 * The magazine is stored on the item. Reloading takes a fixed time and uses items from the inventory as reserve ammo.
 * F or right-click on an empty gun reloads.
 */
abstract class GunAbility extends Ability {

    private static final class Reload {
        int slot;
        int ticks;
        int total;
        int load;
    }

    private final NamespacedKey ammoKey;
    private final Map<UUID, Reload> reloading = new HashMap<>();

    protected GunAbility(WeaponsPlugin plugin) {
        super(plugin);
        this.ammoKey = new NamespacedKey(plugin, "gun_ammo");
    }

    protected abstract int defaultMagazine();

    protected abstract double defaultReloadSeconds();

    protected abstract String defaultAmmoItem();

    protected abstract String ammoName();

    protected int magazine() {
        return Math.max(1, (int) num("magazine", defaultMagazine()));
    }

    protected Material ammoMaterial() {
        String name = plugin.getConfig().getString("weapons." + type().id() + ".ammo-item", defaultAmmoItem());
        Material m = Material.matchMaterial(name);
        return m == null ? Material.matchMaterial(defaultAmmoItem()) : m;
    }

    protected boolean infiniteReserve(Player p) {
        return p.getGameMode() == GameMode.CREATIVE || plugin.getConfig().getBoolean("weapons." + type().id() + ".infinite-reserve", false);
    }

    protected boolean isReloading(Player p) {
        return reloading.containsKey(p.getUniqueId());
    }

    // ------------------------------------------------------------------ ammo on the item

    protected int ammo(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return magazine();
        }
        Integer v = item.getItemMeta().getPersistentDataContainer().get(ammoKey, PersistentDataType.INTEGER);
        return v == null ? magazine() : Math.min(v, magazine());
    }

    protected void setAmmo(ItemStack item, int ammo) {
        int clamped = Math.max(0, Math.min(magazine(), ammo));
        item.editMeta(meta -> meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.INTEGER, clamped));
    }

    protected int reserve(Player p) {
        Material m = ammoMaterial();
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s != null && s.getType() == m) {
                n += s.getAmount();
            }
        }
        return n;
    }

    private int takeReserve(Player p, int need) {
        Material m = ammoMaterial();
        int remaining = need;
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType() != m) {
                continue;
            }
            int t = Math.min(remaining, s.getAmount());
            s.setAmount(s.getAmount() - t);
            remaining -= t;
        }
        p.getInventory().setStorageContents(contents);
        return need - remaining;
    }

    // ------------------------------------------------------------------ reload

    void startReload(Player p) {
        UUID id = p.getUniqueId();
        ItemStack item = p.getInventory().getItemInMainHand();
        if (plugin.items().identify(item) != type() || reloading.containsKey(id)) {
            return;
        }
        int have = ammo(item);
        if (have >= magazine()) {
            p.sendActionBar(Component.text("Already fully loaded.", NamedTextColor.YELLOW));
            return;
        }
        int load = infiniteReserve(p) ? magazine() - have : Math.min(magazine() - have, reserve(p));
        if (load <= 0) {
            p.sendActionBar(Component.text("No " + ammoName() + " to load!", NamedTextColor.RED));
            Fx.sound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1.0f, 1.2f);
            return;
        }
        Reload r = new Reload();
        r.slot = p.getInventory().getHeldItemSlot();
        r.load = load;
        r.total = Math.max(2, (int) (num("reload-seconds", defaultReloadSeconds()) * 20));
        reloading.put(id, r);
        onReloadStart(p);
    }

    protected void onReloadStart(Player p) {
        Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_START, 1.0f, 0.9f);
    }

    protected void onReloadDone(Player p) {
        Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 1.0f, 1.0f);
        Fx.sound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.0f, 1.4f);
    }

    /** Subclasses that override this must call super.tick(p, held). Runs every 2 ticks. */
    @Override
    void tick(Player p, ItemStack held) {
        UUID id = p.getUniqueId();
        Reload r = reloading.get(id);
        if (r == null) {
            return;
        }
        if (held == null || plugin.items().identify(held) != type() || p.getInventory().getHeldItemSlot() != r.slot) {
            reloading.remove(id);
            p.sendActionBar(Component.text("Reload cancelled.", NamedTextColor.RED));
            return;
        }
        r.ticks += 2;
        double frac = Math.min(1.0, r.ticks / (double) r.total);
        int bars = (int) Math.round(frac * 12);
        p.sendActionBar(Component.text("Reloading [" + "#".repeat(bars) + "-".repeat(12 - bars) + "] "
                + String.format(Locale.ROOT, "%.1f", Math.max(0, (r.total - r.ticks) / 20.0)) + "s", NamedTextColor.YELLOW));
        if (r.ticks % 16 == 0 && r.ticks < r.total) {
            Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 1.0f, 1.1f);
        }
        if (r.ticks >= r.total) {
            reloading.remove(id);
            int have = ammo(held);
            int take = Math.min(magazine() - have, r.load);
            if (!infiniteReserve(p)) {
                take = takeReserve(p, take);
            }
            if (take <= 0) {
                p.sendActionBar(Component.text("No " + ammoName() + " to load!", NamedTextColor.RED));
                return;
            }
            setAmmo(held, have + take);
            p.getInventory().setItemInMainHand(held);
            onReloadDone(p);
            status(p, have + take);
        }
    }

    protected void status(Player p, int ammo) {
        int mag = magazine();
        String res = infiniteReserve(p) ? "inf" : String.valueOf(reserve(p));
        p.sendActionBar(Component.text(type().displayName() + "  " + ammo + "/" + mag + "  |  " + res + " " + ammoName()
                + (ammo <= 0 ? "  |  Empty! Right-click or F to reload" : ""), ammo <= 0 ? NamedTextColor.RED : NamedTextColor.GREEN));
    }

    @Override
    void shutdown() {
        reloading.clear();
    }
}
