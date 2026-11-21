package dev.weapons;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Animation hooks. Every time something happens with a weapon (use, hit, fire, reload, throw, spin dash...) the commands listed
 * under {@code animations.<weapon id>.<event>} in config.yml run from the console. That is how a model plugin (BetterModel,
 * Animated Java, ModelEngine...) plays an animation for the player. Placeholders: %player% %uuid% %world% %x% %y% %z%.
 */
final class Animations {

    private Animations() {
    }

    static void trigger(WeaponsPlugin plugin, Player p, WeaponType type, String event) {
        if (type == null || !plugin.getConfig().getBoolean("animations.enabled", true)) {
            return;
        }
        // BetterModel player animation (abilities are played from Anim.ability, so they can fall back to the ghost weapon)
        if (!event.equals("use") && !event.equals("hit") && !event.startsWith("ability_")) {
            ModelAnim.play(plugin, p, type, event);
        }
        List<String> cmds = plugin.getConfig().getStringList("animations." + type.id() + "." + event);
        if (cmds.isEmpty()) {
            return;
        }
        for (String c : cmds) {
            String cmd = c.replace("%player%", p.getName())
                    .replace("%uuid%", p.getUniqueId().toString())
                    .replace("%world%", p.getWorld().getName())
                    .replace("%x%", String.valueOf(p.getLocation().getX()))
                    .replace("%y%", String.valueOf(p.getLocation().getY()))
                    .replace("%z%", String.valueOf(p.getLocation().getZ()));
            if (cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Throwable t) {
                plugin.getLogger().warning("Animation command failed (" + cmd + "): " + t);
            }
        }
    }
}
