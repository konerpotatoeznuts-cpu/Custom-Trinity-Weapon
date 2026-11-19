package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Excalibur: sneak + right-click dashes forward in a barrage of slashes. */
final class ExcaliburAbility extends Ability {

    private static final Color GOLD = Color.fromRGB(255, 215, 80);

    ExcaliburAbility(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.EXCALIBUR;
    }

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        if (!allowed(p)) {
            return;
        }
        if (!p.isSneaking()) {
            uppercut(p);
            return;
        }

        double dashDistance = num("dash-distance", 7.0);
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        // Stop the dash before walls
        RayTraceResult rt = p.getWorld().rayTraceBlocks(eye, dir, dashDistance + 0.9, FluidCollisionMode.NEVER, true);
        double travel = dashDistance;
        if (rt != null) {
            travel = Math.min(dashDistance, eye.toVector().distance(rt.getHitPosition()) - 0.9);
        }
        if (travel < 0.5) {
            p.sendActionBar(Component.text("No room to dash.", NamedTextColor.RED));
            return;
        }

        if (!cooldown(p, "slash", num("cooldown", 15), "Slash Barrage")) {
            return;
        }
        plugin.cooldowns().overlay(p, type());
        plugin.grantNoFall(p, 3);

        final double total = travel;
        final Location start = p.getLocation();
        final double damage = num("damage", 6.0);
        final Set<UUID> hit = new HashSet<>();

        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.7f);
        Fx.shockwave(start.clone().add(0, 0.3, 0), org.bukkit.Color.fromRGB(255, 215, 80), 4.0, 8);
        Fx.burst(start.clone().add(0, 1, 0), Particle.END_ROD, 15, 0.5, 0.15);
        Fx.sound(start, Sound.ITEM_TRIDENT_THUNDER, 0.6f, 1.6f);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    cancel();
                    return;
                }
                double moved = p.getLocation().toVector().distance(start.toVector());
                if (moved >= total - 0.2 || ticks++ > 12) {
                    p.setVelocity(dir.clone().multiply(0.1));
                    p.setFallDistance(0f);
                    Location end = p.getLocation().add(0, 1, 0);
                    Fx.burst(end, Particle.FIREWORK, 30, 0.4, 0.2);
                    Fx.sound(end, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.8f);
                    cancel();
                    return;
                }

                p.setVelocity(dir.clone().multiply(Math.min(1.5, total - moved)));
                p.setFallDistance(0f);
                slashes(p, dir);

                for (LivingEntity le : Combat.nearby(plugin, p, p.getLocation().add(0, 1, 0), 3.0)) {
                    if (hit.add(le.getUniqueId())) {
                        Combat.damage(le, damage * Combat.strength(p), p);
                        le.setVelocity(dir.clone().multiply(0.5).setY(0.25));
                        Fx.burst(le.getLocation().add(0, 1, 0), Particle.ENCHANTED_HIT, 25, 0.4, 0.3);
                        Fx.sound(le.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0f, 1.0f);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Right-click: a rising slash in a fan in front of the player. Launches the player a little and the targets high. */
    private void uppercut(Player p) {
        if (!cooldown(p, "uppercut", num("uppercut-cooldown", 15), "Rising Slash")) {
            return;
        }
        plugin.cooldowns().overlay(p, type());
        plugin.grantNoFall(p, 3);

        double range = num("uppercut-range", 2.0);
        double halfAngle = Math.toRadians(num("uppercut-angle", 120.0) / 2.0);
        double damage = num("uppercut-damage", 6.0);
        double launch = num("uppercut-launch", 1.0);

        Location base = p.getLocation();
        Vector fwd = base.getDirection().setY(0);
        if (fwd.lengthSquared() < 0.001) {
            fwd = new Vector(0, 0, 1);
        }
        final Vector flat = fwd.normalize();

        // Player hops a little forward and up
        p.setVelocity(p.getVelocity().add(new Vector(0, 0.55, 0)).add(flat.clone().multiply(0.25)));

        Fx.sound(base, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f, 0.6f);
        Fx.shockwave(base.clone().add(0, 0.2, 0), org.bukkit.Color.fromRGB(255, 245, 200), 4.5, 10);
        Fx.burst(base.clone().add(0, 1, 0), Particle.FIREWORK, 20, 0.4, 0.2);
        Fx.sound(base, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8f, 1.5f);
        Fx.sound(base, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.8f);

        // Targets in the fan (range measured from the player, a bit taller than the player)
        Location center = base.clone().add(0, 1.0, 0);
        for (LivingEntity le : Combat.nearby(plugin, p, center, range + 1.0)) {
            Vector to = le.getLocation().add(0, le.getHeight() / 2.0, 0).toVector().subtract(center.toVector());
            double flatDist = Math.hypot(to.getX(), to.getZ());
            if (flatDist > range + le.getWidth() / 2.0 || Math.abs(to.getY()) > 2.5) {
                continue;
            }
            if (flatDist > 0.4) {
                Vector dirTo = new Vector(to.getX(), 0, to.getZ()).normalize();
                if (flat.angle(dirTo) > halfAngle) {
                    continue;
                }
            }
            Combat.damage(le, damage * Combat.strength(p), p);
            le.setVelocity(new Vector(flat.getX() * 0.15, launch, flat.getZ() * 0.15));
            Location at = le.getLocation().add(0, le.getHeight() / 2.0, 0);
            Fx.burst(at, Particle.ENCHANTED_HIT, 30, 0.4, 0.4);
            Fx.burst(at, Particle.FIREWORK, 15, 0.3, 0.25);
            Fx.burst(at, Particle.FLASH, 1, 0, 0);
            Fx.sound(le.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 0.8f);
        }

        // Visual: a golden crescent that sweeps from low to high across the fan
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || t > 4) {
                    cancel();
                    return;
                }
                double lift = t * 0.55;
                for (double a = -halfAngle; a <= halfAngle; a += 0.12) {
                    double cs = Math.cos(a);
                    double sn = Math.sin(a);
                    Vector rot = new Vector(flat.getX() * cs - flat.getZ() * sn, 0, flat.getX() * sn + flat.getZ() * cs);
                    for (double r = range * 0.55; r <= range; r += range * 0.45) {
                        Location l = base.clone().add(rot.clone().multiply(r)).add(0, 0.2 + lift, 0);
                        Fx.dust(l, GOLD, 1.5f, 1, 0.02);
                        if (t % 2 == 0) {
                            Fx.burst(l, Particle.END_ROD, 1, 0.02, 0.02);
                        }
                    }
                }
                Fx.burst(base.clone().add(flat.clone().multiply(range * 0.8)).add(0, 0.5 + lift, 0), Particle.SWEEP_ATTACK, 1, 0, 0);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** A few randomly tilted golden arcs in front of the player each tick. */
    private void slashes(Player p, Vector dir) {
        Vector up = new Vector(0, 1, 0);
        Vector right = dir.clone().crossProduct(up);
        if (right.lengthSquared() < 0.001) {
            right = new Vector(1, 0, 0);
        }
        right.normalize();
        Vector up2 = right.clone().crossProduct(dir).normalize();

        Location center = p.getLocation().add(0, 1.0, 0).add(dir.clone().multiply(1.3));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int s = 0; s < 3; s++) {
            double roll = rnd.nextDouble() * Math.PI;
            Vector axis = right.clone().multiply(Math.cos(roll)).add(up2.clone().multiply(Math.sin(roll)));
            for (double a = -1.2; a <= 1.2; a += 0.3) {
                Vector off = axis.clone().multiply(2.0 * Math.sin(a)).add(dir.clone().multiply(0.8 * Math.cos(a) - 0.8));
                Location l = center.clone().add(off);
                Fx.dust(l, GOLD, 1.4f, 1, 0.02);
                Fx.burst(l, Particle.CRIT, 1, 0.02, 0.0);
            }
            Fx.burst(center.clone().add(axis.clone().multiply(rnd.nextDouble() * 1.5 - 0.75)), Particle.SWEEP_ATTACK, 1, 0, 0);
        }
        Fx.burst(center, Particle.END_ROD, 3, 0.4, 0.1);
    }
}
