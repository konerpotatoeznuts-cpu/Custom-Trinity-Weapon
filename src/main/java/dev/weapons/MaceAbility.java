package dev.weapons;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.ThreadLocalRandom;

/** Eldritch Mace: shockwave on hit, dash on right-click, pull on sneak + right-click. */
final class MaceAbility extends Ability {

    private static final Color PURPLE = Color.fromRGB(150, 60, 220);
    private static final Color DEEP = Color.fromRGB(70, 20, 130);

    MaceAbility(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.ELDRITCH_MACE;
    }

    // ------------------------------------------------------------------ on hit: shockwave

    @Override
    void onHit(Player p, LivingEntity target, ItemStack item, EntityDamageByEntityEvent e) {
        // Only a real smash attack (falling onto the target) can cause a shockwave
        if (p.getFallDistance() <= (float) num("smash-min-fall", 1.5) || p.isGliding()) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= num("proc-chance", 0.4)) {
            return;
        }
        if (!allowed(p)) {
            return;
        }
        later(() -> shockwave(p, target));
    }

    private void shockwave(Player p, LivingEntity center) {
        if (!center.isValid() || !p.isOnline()) {
            return;
        }
        Location c = center.getLocation();
        double radius = num("proc-radius", 4.0);
        double dmg = num("proc-damage", 2.0);

        for (LivingEntity le : Combat.nearby(plugin, p, c, radius)) {
            if (le.equals(center)) {
                continue;
            }
            Combat.damage(le, dmg, p);
            Vector push = le.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (push.lengthSquared() > 0.001) {
                push.normalize().multiply(0.6);
            }
            push.setY(0.3);
            le.setVelocity(push);
        }

        Fx.sound(c, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 2.0f, 0.8f);
        Fx.sound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.7f);
        Fx.sound(c, Sound.ENTITY_WITHER_BREAK_BLOCK, 0.8f, 0.6f);
        Fx.burst(c.clone().add(0, 0.3, 0), Particle.EXPLOSION_EMITTER, 1, 0, 0);
        Fx.burst(c.clone().add(0, 0.5, 0), Particle.SOUL_FIRE_FLAME, 40, 0.8, 0.15);
        Fx.burst(c.clone().add(0, 0.5, 0), Particle.REVERSE_PORTAL, 60, 0.6, 0.4);

        // terrain damage: a small crater under the impact
        if (plugin.getConfig().getBoolean("weapons.eldritch_mace.crater", true)) {
            Terrain.crater(p, c, num("crater-radius", 2.5), (int) num("crater-max-blocks", 40), num("crater-max-hardness", 30));
        }

        new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                double r = 0.6 + step * (radius / 6.0);
                Fx.ring(c.clone().add(0, 0.2, 0), r, (int) (r * 10) + 8, 0, l -> {
                    Fx.dust(l, PURPLE, 1.6f, 2, 0.05);
                    Fx.burst(l, Particle.CLOUD, 1, 0.05, 0.02);
                });
                if (++step > 6) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ------------------------------------------------------------------ right-click

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        if (p.isSneaking()) {
            pull(p);
        } else {
            dash(p);
        }
    }

    private void dash(Player p) {
        if (!allowed(p)) {
            return;
        }
        if (!cooldown(p, "dash", num("dash-cooldown", 20), "Dash")) {
            return;
        }
        Vector dir = p.getEyeLocation().getDirection().normalize();
        p.setVelocity(dir.multiply(num("dash-power", 2.4)));
        p.setFallDistance(0f);
        plugin.grantNoFall(p, num("no-fall-seconds", 6));
        plugin.cooldowns().overlay(p, type());

        Fx.sound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 1.0f);
        Fx.sound(p.getLocation(), Sound.ITEM_MACE_SMASH_AIR, 1.0f, 0.8f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || t++ > 10) {
                    cancel();
                    return;
                }
                Location l = p.getLocation().add(0, 1.0, 0);
                Fx.dust(l, PURPLE, 1.7f, 8, 0.3);
                Fx.dust(l, DEEP, 1.2f, 5, 0.25);
                Fx.burst(l, Particle.SOUL_FIRE_FLAME, 4, 0.2, 0.01);
                Fx.burst(l, Particle.CLOUD, 3, 0.2, 0.02);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void pull(Player p) {
        if (!allowed(p)) {
            return;
        }
        if (!cooldown(p, "pull", num("pull-cooldown", 60), "Pull")) {
            return;
        }
        plugin.cooldowns().overlay(p, type());

        boolean air = !p.isOnGround();
        double strength = air ? num("pull-air", 1.6) : num("pull-ground", 0.7);
        double radius = num("pull-radius", 12.0);
        Location center = p.getLocation();

        // Reach is much taller than it is wide, so targets far above or below get caught too.
        final double height = num("pull-height", 24.0);
        final double speed = Math.max(0.3, strength);
        final int maxTicks = (int) num("pull-ticks", 14);
        List<LivingEntity> caught = new ArrayList<>();
        for (LivingEntity le : Combat.nearby(plugin, p, center, Math.hypot(radius, height))) {
            Location el = le.getLocation();
            if (Math.hypot(el.getX() - center.getX(), el.getZ() - center.getZ()) <= radius
                    && Math.abs(el.getY() - center.getY()) <= height) {
                caught.add(le);
            }
        }
        // Everyone is dragged to the spot right under the wielder's feet and held there: the speed drops as they get close,
        // and they are stopped dead on arrival, so nobody overshoots or flies past.
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || ++t > maxTicks || caught.isEmpty()) {
                    cancel();
                    return;
                }
                Location pl = p.getLocation();
                double ty = pl.getY() - (p.isOnGround() ? 0.0 : 1.2);
                for (LivingEntity le : caught) {
                    if (!le.isValid() || le.isDead() || le.getWorld() != pl.getWorld()) {
                        continue;
                    }
                    Location el = le.getLocation();
                    Vector delta = new Vector(pl.getX() - el.getX(), ty - el.getY(), pl.getZ() - el.getZ());
                    double d = delta.length();
                    if (d < 0.7) {
                        le.setVelocity(new Vector(0, Math.min(0.0, le.getVelocity().getY()), 0)); // arrived: stop
                        continue;
                    }
                    double v = Math.min(speed, d * 0.45); // never faster than the distance left
                    Vector vel = delta.normalize().multiply(v);
                    if (le.isOnGround() && vel.getY() < 0.1) {
                        vel.setY(0.1); // a little lift so they do not catch on the floor
                    }
                    le.setVelocity(vel);
                    if (t % 3 == 1) {
                        Fx.line(el.clone().add(0, le.getHeight() / 2.0, 0), pl.clone().add(0, 1, 0), 0.6,
                                l -> Fx.dust(l, DEEP, 1.0f, 1, 0.02));
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        Fx.sound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.2f, 0.7f);
        Fx.sound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.0f, 1.2f);

        // Rings that collapse toward the wielder
        new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    return;
                }
                double r = Math.max(0.8, radius * (1.0 - step / 8.0));
                Location base = p.getLocation().add(0, 0.2, 0);
                for (int h = 0; h < 3; h++) {
                    Fx.ring(base.clone().add(0, h * 0.7, 0), r, (int) (r * 6) + 10, step * 0.5, l -> {
                        Fx.dust(l, PURPLE, 1.4f, 1, 0.02);
                        Fx.burst(l, Particle.ENCHANT, 1, 0.1, 0.1);
                    });
                }
                if (++step > 8) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
