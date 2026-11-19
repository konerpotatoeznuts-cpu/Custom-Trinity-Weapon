package dev.weapons;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Freezes entities in place for a short time. Mobs lose their AI, players can't move, attack or interact (see WeaponListener). */
final class Stun {

    enum Style { STONE, SHOCK }

    private final WeaponsPlugin plugin;
    private final Map<UUID, Long> until = new HashMap<>();
    private final Map<UUID, Boolean> aiWas = new HashMap<>();
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();

    Stun(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isStunned(Entity e) {
        Long end = until.get(e.getUniqueId());
        return end != null && end > System.currentTimeMillis();
    }

    void stun(LivingEntity e, int ticks, Style style) {
        UUID id = e.getUniqueId();
        long end = System.currentTimeMillis() + ticks * 50L;
        boolean already = until.containsKey(id);
        until.put(id, Math.max(end, until.getOrDefault(id, 0L)));
        if (already) {
            return;
        }
        if (!(e instanceof Player)) {
            aiWas.put(id, e.hasAI());
            e.setAI(false);
        }
        e.setVelocity(new Vector(0, 0, 0));

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!e.isValid() || System.currentTimeMillis() >= until.getOrDefault(id, 0L)) {
                    release(e);
                    return;
                }
                visuals(e, style);
            }
        }.runTaskTimer(plugin, 0L, 2L);
        tasks.put(id, task);
    }

    private void visuals(LivingEntity e, Stun.Style style) {
        Location body = e.getLocation().add(0, e.getHeight() / 2.0, 0);
        if (style == Style.STONE) {
            body.getWorld().spawnParticle(Particle.BLOCK, body, 12, 0.35, 0.5, 0.35, Material.STONE.createBlockData());
            e.setFreezeTicks(Math.max(0, e.getMaxFreezeTicks() - 8)); // grey frost overlay without freeze damage
        } else {
            Location head = e.getLocation().add(0, e.getHeight() + 0.3, 0);
            long t = System.currentTimeMillis() / 60;
            Fx.ring(head, 0.5, 4, t * 0.5, l -> {
                Fx.burst(l, Particle.ELECTRIC_SPARK, 2, 0.05, 0.02);
                Fx.burst(l, Particle.CRIT, 1, 0.02, 0.0);
            });
        }
    }

    private void release(LivingEntity e) {
        UUID id = e.getUniqueId();
        until.remove(id);
        BukkitTask t = tasks.remove(id);
        if (t != null) {
            t.cancel();
        }
        Boolean ai = aiWas.remove(id);
        if (ai != null && e.isValid()) {
            e.setAI(ai);
        }
        if (e.isValid()) {
            e.setFreezeTicks(0);
        }
    }

    void releaseAll() {
        for (UUID id : new ArrayList<>(tasks.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le) {
                release(le);
            }
        }
        for (BukkitTask t : tasks.values()) {
            t.cancel();
        }
        tasks.clear();
        until.clear();
        aiWas.clear();
    }
}
