package dev.weapons;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The mace's smash attack for the plain trident, but ONLY while the wielder is riptiding. The bonus damage grows with how high
 * above the ground (or water) the player got during the riptide.
 */
final class TridentSmash implements Listener {

    private static final Color WATER = Color.fromRGB(90, 190, 255);

    private static final class Run {
        double peak;
        final Set<UUID> done = new HashSet<>();
    }

    private final WeaponsPlugin plugin;
    private final Map<UUID, Run> runs = new HashMap<>();

    TridentSmash(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    private double num(String path, double def) {
        return plugin.getConfig().getDouble("trident-smash." + path, def);
    }

    private boolean on() {
        return plugin.getConfig().getBoolean("trident-smash.enabled", true);
    }

    /** Blocks between the player's feet and whatever is below (water surfaces count as ground). */
    private double height(Player p) {
        if (p.isInWater()) {
            return 0;
        }
        Location l = p.getLocation();
        RayTraceResult r = p.getWorld().rayTraceBlocks(l, new Vector(0, -1, 0), 96, FluidCollisionMode.ALWAYS, true);
        return r == null || r.getHitPosition() == null ? Math.max(0, p.getFallDistance()) : Math.max(0, l.getY() - r.getHitPosition().getY());
    }

    @EventHandler
    public void onRiptide(PlayerRiptideEvent e) {
        if (!on()) {
            return;
        }
        Player p = e.getPlayer();
        Run run = new Run();
        runs.put(p.getUniqueId(), run);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                if (!p.isOnline() || runs.get(p.getUniqueId()) != run || t > 60 || (t > 3 && !p.isRiptiding())) {
                    if (runs.get(p.getUniqueId()) == run) {
                        runs.remove(p.getUniqueId());
                    }
                    cancel();
                    return;
                }
                run.peak = Math.max(run.peak, height(p));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!on() || Combat.isInternal() || !(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity target)
                || !p.isRiptiding()) {
            return;
        }
        if (p.getInventory().getItemInMainHand().getType() != Material.TRIDENT
                && p.getInventory().getItemInOffHand().getType() != Material.TRIDENT) {
            return;
        }
        Run run = runs.computeIfAbsent(p.getUniqueId(), k -> new Run());
        if (!run.done.add(target.getUniqueId()) || !plugin.protection().canUse(p)) {
            return;
        }
        double h = Math.max(run.peak, height(p));
        double min = num("min-height", 3.0);
        if (h < min) {
            return;
        }
        double bonus = Math.min(num("max-damage", 12.0), (h - min + 1.0) * num("damage-per-block", 0.8));
        plugin.getServer().getScheduler().runTask(plugin, () -> smash(p, target, bonus, h));
    }

    private void smash(Player p, LivingEntity target, double bonus, double h) {
        if (!target.isValid() || !p.isOnline() || !Combat.valid(plugin, p, target)) {
            return;
        }
        Combat.damage(target, bonus * Combat.strength(p), p);
        plugin.grantNoFall(p, 4);
        p.setFallDistance(0f);

        Location c = target.getLocation();
        double radius = num("shockwave-radius", 3.0);
        for (LivingEntity le : Combat.nearby(plugin, p, c, radius)) {
            if (le.equals(target)) {
                continue;
            }
            Vector push = le.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (push.lengthSquared() > 0.001) {
                push.normalize().multiply(0.5);
            }
            push.setY(0.35);
            le.setVelocity(push);
        }
        boolean heavy = h >= num("heavy-height", 7.0);
        Fx.sound(c, heavy ? Sound.ITEM_MACE_SMASH_GROUND_HEAVY : Sound.ITEM_MACE_SMASH_GROUND, 1.6f, 1.0f);
        Fx.sound(c, Sound.ITEM_TRIDENT_THUNDER, 0.7f, 1.4f);
        Fx.burst(c.clone().add(0, 0.4, 0), Particle.SPLASH, 40, 0.8, 0.3);
        Fx.burst(c.clone().add(0, 0.5, 0), Particle.BUBBLE_POP, 20, 0.6, 0.2);
        Fx.shockwave(c.clone().add(0, 0.2, 0), WATER, radius, 6);
        p.sendActionBar(net.kyori.adventure.text.Component.text("Riptide Smash!  " + (int) h + " blocks high",
                net.kyori.adventure.text.format.NamedTextColor.AQUA));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        runs.remove(e.getPlayer().getUniqueId());
    }
}
