package dev.weapons;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Who may use abilities and where.
 * By default EVERY player can use the abilities (no permission needed). Players can still never hurt other players
 * where WorldGuard says pvp is deny. Both rules can be changed in config.yml.
 */
final class Protection {

    private final WeaponsPlugin plugin;
    private WorldGuardHook hook;

    Protection(WeaponsPlugin plugin) {
        this.plugin = plugin;
        if (plugin.getConfig().getBoolean("worldguard.enabled", true)
                && Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                hook = new WorldGuardHook();
                plugin.getLogger().info("WorldGuard found: players cannot be hurt by abilities where pvp is deny.");
            } catch (Throwable t) {
                hook = null;
                plugin.getLogger().warning("WorldGuard was found but could not be hooked: " + t);
            }
        }
    }

    boolean requiresPermission() {
        return plugin.getConfig().getBoolean("permissions.require-use-permission", false);
    }

    boolean blocksInNoPvp() {
        return plugin.getConfig().getBoolean("worldguard.block-abilities-in-no-pvp", false);
    }

    boolean worldGuardActive() {
        return hook != null;
    }

    /** May this player use weapon abilities where they stand? */
    boolean canUse(Player p) {
        if (requiresPermission() && !p.hasPermission("customweapons.use")) {
            return false;
        }
        return !(blocksInNoPvp() && hook != null && !hook.pvpAllowed(p, p.getLocation()));
    }

    /** May this player hurt that player at their location? */
    boolean canHit(Player attacker, Player victim) {
        return hook == null || hook.pvpAllowed(attacker, victim.getLocation());
    }

    boolean pvpHere(Player p) {
        return hook == null || hook.pvpAllowed(p, p.getLocation());
    }

    boolean canBuild(Player p, Location loc) {
        return hook == null || hook.canBuild(p, loc);
    }
}
