package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/** Base class for the behaviour of one weapon. Every hook does nothing by default. */
abstract class Ability {

    protected final WeaponsPlugin plugin;

    protected Ability(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    abstract WeaponType type();

    /** The player hit {@code target} in melee while holding this weapon. */
    void onHit(Player p, LivingEntity target, ItemStack item, EntityDamageByEntityEvent e) {
    }

    /** The player right-clicked while holding this weapon (main hand). */
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
    }

    /** The player right-clicked an entity while holding this weapon (main hand). */
    void onInteractEntity(Player p, Entity target, ItemStack item, PlayerInteractEntityEvent e) {
    }

    /** The player broke a block while holding this weapon. */
    void onBreak(Player p, ItemStack item, BlockBreakEvent e) {
    }

    /** Runs every 2 ticks for every online player. {@code held} is the weapon stack if this weapon is in the main hand, otherwise null. */
    void tick(Player p, ItemStack held) {
    }

    /** The player left the server. */
    void onQuit(Player p) {
    }

    void shutdown() {
    }

    protected double num(String path, double def) {
        return plugin.getConfig().getDouble("weapons." + type().id() + "." + path, def);
    }

    protected boolean allowed(Player p) {
        if (plugin.protection().canUse(p)) {
            return true;
        }
        p.sendActionBar(Component.text("Weapon abilities are disabled here.", NamedTextColor.RED));
        return false;
    }

    protected boolean cooldown(Player p, String key, double seconds, String label) {
        return plugin.cooldowns().tryUse(p, type(), key, seconds, label);
    }

    /** Runs on the next tick (used so we never deal damage inside another damage event). */
    protected void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }
}
