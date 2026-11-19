package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Prismatic Blade: heavy (slows you), hold sneak to charge, right-click to release a flashbang. */
final class PrismaticAbility extends Ability {

    private final Map<UUID, Integer> charge = new HashMap<>();

    PrismaticAbility(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.PRISMATIC_BLADE;
    }

    private int needed() {
        return Math.max(2, (int) (num("charge-seconds", 2.0) * 20));
    }

    @Override
    void tick(Player p, ItemStack held) {
        UUID id = p.getUniqueId();
        if (held == null) {
            charge.remove(id);
            return;
        }

        // Passive: the blade is heavy.
        int level = (int) num("slowness-level", 1);
        if (level > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, level - 1, true, false, false));
        }

        if (!p.isSneaking() || !plugin.cooldowns().ready(p, type(), "flash")) {
            charge.remove(id);
            return;
        }

        int need = needed();
        int before = charge.getOrDefault(id, 0);
        int now = Math.min(need, before + 2);
        charge.put(id, now);

        double frac = now / (double) need;
        Location base = p.getLocation();

        if (now < need) {
            // Rainbow sparks spiral in toward the wielder
            double radius = 2.4 - 1.5 * frac;
            for (int i = 0; i < 3; i++) {
                double a = now * 0.45 + i * (Math.PI * 2 / 3);
                Location l = base.clone().add(Math.cos(a) * radius, 0.2 + frac * 1.4, Math.sin(a) * radius);
                Fx.dust(l, Fx.rainbow(now * 0.03 + i / 3.0), 1.3f, 1, 0.02);
            }
            int bars = (int) Math.round(frac * 10);
            p.sendActionBar(Component.text("Charging [" + "|".repeat(bars) + ".".repeat(10 - bars) + "]", NamedTextColor.AQUA));
        } else {
            Fx.dust(base.clone().add(0, 1.0, 0), Fx.rainbow(System.currentTimeMillis() / 800.0), 1.5f, 3, 0.5);
            Fx.burst(base.clone().add(0, 1.0, 0), Particle.END_ROD, 1, 0.5, 0.01);
            p.sendActionBar(Component.text("Charged! Right-click to release", NamedTextColor.GOLD));
            if (before < need) {
                Fx.sound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f);
                Fx.sound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 2.0f);
            }
        }
    }

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        if (!p.isSneaking()) {
            return;
        }
        Integer c = charge.get(p.getUniqueId());
        if (c == null || c < needed()) {
            return; // not fully charged yet
        }
        if (!allowed(p)) {
            return;
        }
        if (!cooldown(p, "flash", num("cooldown", 60), "Flashbang")) {
            return;
        }
        charge.remove(p.getUniqueId());
        plugin.cooldowns().overlay(p, type());
        flash(p);
    }

    private void flash(Player p) {
        double radius = num("radius", 10.0);
        int blind = (int) (num("blind-seconds", 6) * 20);
        Location origin = p.getLocation().add(0, 1.0, 0);

        double overlaySeconds = num("overlay-seconds", 8.0);
        for (LivingEntity le : Combat.nearby(plugin, p, origin, radius)) {
            if (!le.hasLineOfSight(p)) {
                continue; // a flashbang needs a clear line of sight
            }
            if (le instanceof Player tp) {
                plugin.flash().flash(tp, overlaySeconds); // players get a white screen instead of Blindness
            } else {
                le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blind, 0, false, true, true));
            }
            // Flashbanged: very slow and unable to jump
            plugin.immobilize().apply(le, (int) (num("immobilize-seconds", 8.0) * 20), (int) num("immobilize-slowness-level", 3));
        }

        Fx.sound(origin, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.5f, 1.3f);
        Fx.shockwave(origin.clone().add(0, 0.3, 0), org.bukkit.Color.fromRGB(255, 255, 255), 10.0, 14);
        Fx.shockwave(origin.clone().add(0, 1.0, 0), org.bukkit.Color.fromRGB(120, 240, 255), 7.0, 10);
        Fx.burst(origin.clone().add(0, 1, 0), Particle.END_ROD, 60, 1.5, 0.35);
        Fx.sound(origin, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 0.8f);
        Fx.sound(origin, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.5f, 1.0f);
        Fx.burst(origin, Particle.FIREWORK, 80, 0.4, 0.4);
        Fx.burst(origin, Particle.END_ROD, 60, 0.3, 0.5);
        try {
            origin.getWorld().spawnParticle(Particle.FLASH, origin, 1, 0, 0, 0, 0, Color.WHITE);
        } catch (Throwable ignored) {
            // particle data differs between versions, the rest of the effect is unaffected
        }
        Fx.sound(origin, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 2.0f);

        // Expanding rainbow sphere
        new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                double r = radius * (step + 1) / 8.0;
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < 70; i++) {
                    double u = rnd.nextDouble() * 2 - 1;
                    double th = rnd.nextDouble() * Math.PI * 2;
                    double s = Math.sqrt(1 - u * u);
                    Vector v = new Vector(s * Math.cos(th), u, s * Math.sin(th)).multiply(r);
                    Fx.dust(origin.clone().add(v), Fx.rainbow(i / 70.0 + step * 0.1), 1.8f, 1, 0.0);
                }
                if (++step >= 8) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
