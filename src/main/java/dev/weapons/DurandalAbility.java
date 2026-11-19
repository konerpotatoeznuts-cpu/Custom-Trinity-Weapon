package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Durandal: lifesteal and lightning on hit, and a heavenly beam on right-click. */
final class DurandalAbility extends Ability {

    private static final Color GOLD = Color.fromRGB(255, 225, 120);
    private static final Color WHITE = Color.fromRGB(255, 255, 245);

    DurandalAbility(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.DURANDAL;
    }

    // ------------------------------------------------------------------ on hit

    @Override
    void onHit(Player p, LivingEntity target, ItemStack item, EntityDamageByEntityEvent e) {
        if (!Combat.valid(plugin, p, target) || !allowed(p)) {
            return;
        }
        if (!cooldown(p, "hit", num("cooldown", 15), "Lifesteal Strike")) {
            return;
        }
        plugin.cooldowns().overlay(p, type());

        later(() -> {
            if (!target.isValid() || !p.isOnline()) {
                return;
            }
            Combat.heal(p, num("lifesteal-hearts", 2) * 2.0);

            target.getWorld().strikeLightningEffect(target.getLocation());
            Combat.damage(target, num("lightning-damage", 5.0), p);

            Location from = target.getLocation().add(0, target.getHeight() / 2.0, 0);
            Location to = p.getLocation().add(0, 1.0, 0);
            Fx.line(from, to, 0.3, l -> Fx.dust(l, Color.fromRGB(220, 20, 60), 1.3f, 2, 0.04));
            Fx.burst(from, Particle.ELECTRIC_SPARK, 50, 0.6, 0.35);
            Fx.burst(from, Particle.END_ROD, 20, 0.4, 0.15);
            Fx.burst(from, Particle.FLAME, 25, 0.4, 0.1);
            Fx.burst(to, Particle.HEART, 6, 0.4, 0);
            Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.7f);
        });
    }

    // ------------------------------------------------------------------ right-click: Heavenly Beam

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        if (!allowed(p)) {
            return;
        }
        if (!plugin.cooldowns().check(p, type(), "beam", "Heavenly Beam")) {
            return;
        }

        double range = num("beam-range", 100);
        Location eye = p.getEyeLocation();
        World w = eye.getWorld();
        RayTraceResult rt = w.rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.4,
                en -> en instanceof LivingEntity le && Combat.valid(plugin, p, le));
        if (rt == null) {
            p.sendActionBar(Component.text("Aim at something within " + (int) range + " blocks.", NamedTextColor.RED));
            return;
        }

        double cx;
        double cz;
        double impactY;
        int baseY; // only blocks strictly above this height are destroyed
        if (rt.getHitBlock() != null) {
            Vector hp = rt.getHitPosition();
            cx = hp.getX();
            cz = hp.getZ();
            baseY = rt.getHitBlock().getY();
            impactY = hp.getY();
        } else if (rt.getHitEntity() != null) {
            Location l = rt.getHitEntity().getLocation();
            cx = l.getX();
            cz = l.getZ();
            baseY = l.getBlockY();
            impactY = l.getY();
        } else {
            return;
        }

        if (!cooldown(p, "beam", num("beam-cooldown", 60), "Heavenly Beam")) {
            return;
        }
        plugin.cooldowns().overlay(p, type());
        startBeam(p, new Location(w, cx, impactY, cz), baseY);
    }

    private void startBeam(Player p, Location center, int baseY) {
        final World w = center.getWorld();
        final double radius = num("beam-radius", 10);
        final int duration = (int) (num("beam-seconds", 5) * 20);
        final int fall = 6; // ticks the beam takes to fall from the sky
        final double damage = num("beam-damage", 2.0);
        final int interval = Math.max(2, (int) (num("beam-damage-interval", 1.0) * 20));
        final int perTick = (int) num("beam-blocks-per-tick", 120);
        final double maxHardness = num("beam-max-hardness", 30);
        final double top = Math.min(w.getMaxHeight(), center.getY() + num("beam-height", 70));

        final List<Block> blocks = collect(w, center, radius, baseY, (int) top, (int) num("beam-max-blocks", 6000), maxHardness);

        Fx.sound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 6.0f, 1.0f);
        Fx.shockwave(center.clone().add(0, 0.3, 0), org.bukkit.Color.fromRGB(255, 225, 120), 9.0, 16);
        Fx.shockwave(center.clone().add(0, 0.3, 0), org.bukkit.Color.fromRGB(255, 255, 255), 5.0, 10);
        Fx.burst(center.clone().add(0, 1, 0), Particle.END_ROD, 40, 1.2, 0.25);
        Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 5.0f, 1.4f);
        Fx.sound(center, Sound.ITEM_TRIDENT_THUNDER, 5.0f, 0.7f);
        Fx.sound(p.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.0f, 1.8f);
        w.strikeLightningEffect(center);

        new BukkitRunnable() {
            int t = 0;
            int next = 0;

            @Override
            public void run() {
                if (!p.isOnline() || t > duration + fall) {
                    finish();
                    cancel();
                    return;
                }

                // --- visuals: the beam falls from the sky, then holds
                double frontY = top - (top - center.getY()) * Math.min(1.0, (t + 1) / (double) fall);
                for (double y = frontY; y <= top; y += 0.8) {
                    Location l = new Location(w, center.getX(), y, center.getZ());
                    Fx.force(l, Particle.END_ROD, 1, 0.35, 0.0);
                    Fx.forceDust(l.clone().add(ThreadLocalRandom.current().nextDouble(-0.7, 0.7), 0, ThreadLocalRandom.current().nextDouble(-0.7, 0.7)), WHITE, 2.4f);
                }
                Location base = center.clone().add(0, 0.2, 0);
                Fx.burst(base, Particle.END_ROD, 6, 1.2, 0.15);
                Fx.burst(base, Particle.FIREWORK, 4, 1.5, 0.12);
                if (t % 2 == 0) {
                    // edge of the beam: a golden cylinder wall and a pulse that sweeps outward
                    for (int h = 0; h < 4; h++) {
                        double yy = center.getY() + 0.3 + h * 2.2 + ((t / 2) % 3) * 0.7;
                        Fx.ring(new Location(w, center.getX(), yy, center.getZ()), radius, 48, t * 0.07, l -> Fx.forceDust(l, GOLD, 1.8f));
                    }
                    double pr = (t % 20) / 20.0 * radius;
                    Fx.ring(base, Math.max(0.5, pr), (int) (pr * 8) + 12, 0, l -> {
                        Fx.dust(l, WHITE, 1.6f, 1, 0.05);
                        Fx.burst(l, Particle.END_ROD, 1, 0.05, 0.04);
                    });
                }
                if (t % 20 == 0) {
                    Fx.sound(center, Sound.BLOCK_BEACON_AMBIENT, 4.0f, 1.6f);
                    Fx.sound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE, 3.0f, 1.8f);
                }

                // --- effects on entities inside the beam
                if (t >= fall) {
                    boolean tickDamage = (t - fall) % interval == 0;
                    Location mid = center.clone().add(0, 20, 0);
                    for (LivingEntity le : w.getNearbyLivingEntities(mid, radius, 25, radius)) {
                        double dx = le.getLocation().getX() - center.getX();
                        double dz = le.getLocation().getZ() - center.getZ();
                        if (dx * dx + dz * dz > radius * radius || !Combat.valid(plugin, p, le)) {
                            continue;
                        }
                        if ((t - fall) % 4 == 0) {
                            plugin.stun().stun(le, 10, Stun.Style.SHOCK);
                        }
                        if (tickDamage) {
                            Combat.damage(le, damage, p);
                            Location body = le.getLocation().add(0, le.getHeight() / 2.0, 0);
                            Fx.burst(body, Particle.END_ROD, 12, 0.3, 0.1);
                            Fx.burst(body, Particle.ELECTRIC_SPARK, 10, 0.3, 0.2);
                            Fx.sound(body, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.5f);
                        }
                    }
                }

                // --- blocks above the aimed point are destroyed from the top down
                if (t >= 2) {
                    int budget = perTick;
                    while (budget > 0 && next < blocks.size()) {
                        Block b = blocks.get(next++);
                        if (Terrain.canBreak(b, maxHardness) && Terrain.destroy(p, b, false, true)) {
                            budget--;
                            if (next % 40 == 0) {
                                Fx.sound(b.getLocation(), Sound.BLOCK_STONE_BREAK, 1.5f, 0.6f);
                            }
                        }
                    }
                }
                t++;
            }

            private void finish() {
                Fx.sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 5.0f, 1.2f);
                Fx.sound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 4.0f, 1.4f);
                Fx.burst(center.clone().add(0, 1, 0), Particle.FIREWORK, 80, 2.0, 0.3);
                Fx.burst(center.clone().add(0, 1, 0), Particle.END_ROD, 60, 3.0, 0.2);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Solid blocks above {@code baseY} inside the beam, highest first. */
    private List<Block> collect(World w, Location center, double radius, int baseY, int topY, int cap, double maxHardness) {
        List<Block> out = new ArrayList<>();
        int r = (int) Math.ceil(radius);
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int maxY = Math.min(topY, w.getMaxHeight() - 1);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = maxY; y > baseY; y--) {
                    Block b = w.getBlockAt(x, y, z);
                    if (Terrain.canBreak(b, maxHardness)) {
                        out.add(b);
                    }
                }
            }
        }
        out.sort(Comparator.comparingInt((Block b) -> -b.getY()));
        if (out.size() > cap) {
            // keep the ones closest to the beam's landing point
            out.sort(Comparator.comparingDouble(b -> {
                double ddx = b.getX() + 0.5 - center.getX();
                double ddz = b.getZ() + 0.5 - center.getZ();
                return ddx * ddx + ddz * ddz;
            }));
            out = new ArrayList<>(out.subList(0, cap));
            out.sort(Comparator.comparingInt((Block b) -> -b.getY()));
        }
        return out;
    }
}
