package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ability cooldowns. The real timing is kept here; the white hotbar sweep (like Ender Pearls / Wind Charges)
 * is shown with the item cooldown, and every weapon has its own cooldown group so two weapons that share a
 * base item (Durandal and Excalibur) never block each other.
 */
final class Cooldowns {

    private final WeaponsPlugin plugin;
    private final Map<UUID, Map<String, Long>> expiry = new HashMap<>();

    Cooldowns(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    private static String k(WeaponType t, String key) {
        return t.id() + ":" + key;
    }

    long remainingMillis(Player p, WeaponType t, String key) {
        Map<String, Long> m = expiry.get(p.getUniqueId());
        if (m == null) {
            return 0;
        }
        Long until = m.get(k(t, key));
        if (until == null) {
            return 0;
        }
        return Math.max(0, until - System.currentTimeMillis());
    }

    boolean ready(Player p, WeaponType t, String key) {
        return remainingMillis(p, t, key) <= 0;
    }

    void start(Player p, WeaponType t, String key, double seconds) {
        if (!key.equals("throw") || t != WeaponType.GREAT_AXE) { // the Great Axe plays its throw when it is thrown, not when it lands
            Anim.ability(plugin, p, t, key);
        }
        expiry.computeIfAbsent(p.getUniqueId(), x -> new HashMap<>())
                .put(k(t, key), System.currentTimeMillis() + (long) (seconds * 1000 * CrossPvp.factor(p, null, "cooldown-multiplier", 1.3)));
    }

    /** Starts the cooldown if ready. If not ready, tells the player how long is left. */
    boolean tryUse(Player p, WeaponType t, String key, double seconds, String label) {
        long left = remainingMillis(p, t, key);
        if (left > 0) {
            p.sendActionBar(Component.text(label + " ready in " + String.format(Locale.ROOT, "%.1f", left / 1000.0) + "s", NamedTextColor.RED));
            return false;
        }
        start(p, t, key, seconds);
        return true;
    }

    /** True if ready. Otherwise tells the player how long is left (and does not start anything). */
    boolean check(Player p, WeaponType t, String key, String label) {
        long left = remainingMillis(p, t, key);
        if (left > 0) {
            p.sendActionBar(Component.text(label + " ready in " + String.format(Locale.ROOT, "%.1f", left / 1000.0) + "s", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private static String[] overlayKeys(WeaponType t) {
        return switch (t) {
            case DURANDAL -> new String[]{"hit", "beam"};
            case HALBERD_OF_DESPAIR -> new String[]{"hit", "spin"};
            case ELDRITCH_MACE -> new String[]{"dash", "pull"};
            case PRISMATIC_BLADE -> new String[]{"flash"};
            case WARDEN_WHIP -> new String[]{"boom"};
            case EXCALIBUR -> new String[]{"slash", "uppercut"};
            case FANTASY_TAMER -> new String[]{"recall"};
            case GREAT_AXE -> new String[]{"throw"};
            default -> new String[0]; // revolver and tool use the action bar only
        };
    }

    /**
     * Shows the white cooldown bar on the weapon in the main hand. For weapons with several right-click
     * abilities (the mace) the bar only appears when all of them are cooling down, so it never blocks one that is ready.
     */
    void overlay(Player p, WeaponType t) {
        String[] keys = overlayKeys(t);
        if (keys.length == 0) {
            return;
        }
        long min = Long.MAX_VALUE;
        for (String key : keys) {
            long r = remainingMillis(p, t, key);
            if (r <= 0) {
                return;
            }
            min = Math.min(min, r);
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        if (plugin.items().identify(held) != t) {
            return;
        }
        p.setCooldown(held, (int) Math.ceil(min / 50.0));
    }
}
