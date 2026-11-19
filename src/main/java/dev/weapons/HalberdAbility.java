package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Halberd of Despair: scrambles the target's hotbar, stuns them and summons wither skeletons. */
final class HalberdAbility extends Ability {

    private static final Color DARK = Color.fromRGB(40, 40, 50);
    private static final Color SOUL = Color.fromRGB(90, 220, 255);

    private final Set<UUID> minions = new HashSet<>();
    private final Map<UUID, BukkitTask> spinning = new HashMap<>();

    HalberdAbility(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.HALBERD_OF_DESPAIR;
    }

    @Override
    void onHit(Player p, LivingEntity target, ItemStack item, EntityDamageByEntityEvent e) {
        if (!Combat.valid(plugin, p, target) || !allowed(p)) {
            return;
        }
        breakShield(target);
        if (!cooldown(p, "hit", num("cooldown", 40), "Despair")) {
            return;
        }
        plugin.cooldowns().overlay(p, type());
        later(() -> despair(p, target));
    }

    /** Hits disable the target's shield like an axe does. */
    private void breakShield(LivingEntity target) {
        if (target instanceof Player tp && tp.isBlocking()) {
            tp.setCooldown(Material.SHIELD, (int) (num("shield-disable-seconds", 5) * 20));
            tp.clearActiveItem();
            Location l = tp.getLocation().add(0, 1.2, 0);
            Fx.sound(l, Sound.ITEM_SHIELD_BREAK, 1.5f, 0.8f);
            Fx.burst(l, Particle.CRIT, 25, 0.4, 0.3);
            Fx.burst(l, Particle.SOUL_FIRE_FLAME, 12, 0.3, 0.08);
        }
    }

    // ------------------------------------------------------------------ sneak + right-click: Reaping Spin

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        if (!p.isSneaking() || spinning.containsKey(p.getUniqueId()) || !allowed(p)) {
            return;
        }
        if (!cooldown(p, "spin", num("spin-cooldown", 30), "Reaping Spin")) {
            return;
        }
        plugin.cooldowns().overlay(p, type());
        startSpin(p);
    }

    private void startSpin(Player p) {
        final double radius = num("spin-radius", 4.5);
        final int duration = (int) (num("spin-seconds", 5) * 20);
        final int interval = Math.max(2, (int) (num("spin-damage-interval", 1.0) * 20));
        final double damage = num("spin-damage", 2.0);
        final double pull = num("spin-pull", 0.10);
        final int witherTicks = (int) (num("spin-wither-seconds", 2.5) * 20);

        Fx.sound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
        Fx.shockwave(p.getLocation().add(0, 0.2, 0), org.bukkit.Color.fromRGB(90, 30, 140), 5.0, 12);
        Fx.burst(p.getLocation().add(0, 1, 0), Particle.SOUL_FIRE_FLAME, 25, 0.6, 0.08);
        Fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_3, 1.5f, 0.8f);

        BukkitTask task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || !p.isSneaking()
                        || plugin.items().identify(p.getInventory().getItemInMainHand()) != type()
                        || plugin.stun().isStunned(p) || t >= duration) {
                    stop(p, t < duration);
                    return;
                }
                Location c = p.getLocation();
                Location mid = c.clone().add(0, 1.0, 0);

                // --- visuals: three spinning blade arms, rising soul sparks and a ground ring
                double base = t * 0.55;
                for (int arm = 0; arm < 3; arm++) {
                    double a0 = base + arm * (Math.PI * 2 / 3);
                    for (double r = 1.0; r <= radius; r += 0.5) {
                        double a = a0 - r * 0.22; // curved blade
                        Location l = mid.clone().add(Math.cos(a) * r, 0.1 * Math.sin(t * 0.3 + r), Math.sin(a) * r);
                        Fx.dust(l, r > radius - 1 ? SOUL : DARK, 1.5f, 1, 0.03);
                    }
                    Location tip = mid.clone().add(Math.cos(a0 - radius * 0.22) * radius, 0, Math.sin(a0 - radius * 0.22) * radius);
                    Fx.burst(tip, Particle.SWEEP_ATTACK, 1, 0, 0);
                    Fx.burst(tip, Particle.SOUL_FIRE_FLAME, 2, 0.1, 0.02);
                }
                if (t % 2 == 0) {
                    Fx.ring(c.clone().add(0, 0.15, 0), radius, 36, t * 0.1, l -> Fx.dust(l, SOUL, 1.2f, 1, 0.02));
                    Fx.burst(mid, Particle.SOUL, 2, 0.6, 0.05);
                }
                if (t % 10 == 0) {
                    Fx.sound(c, Sound.ITEM_TRIDENT_RIPTIDE_1, 1.0f, 0.6f + (t % 20) * 0.01f);
                }
                if (t % 20 == 0) {
                    Fx.sound(c, Sound.ENTITY_WITHER_AMBIENT, 0.8f, 1.6f);
                }

                // --- effects on entities: slowly drag them in, hurt and wither them
                boolean tickDamage = t % interval == 0;
                for (LivingEntity le : Combat.nearby(plugin, p, mid, radius)) {
                    Vector toward = mid.toVector().subtract(le.getLocation().add(0, le.getHeight() / 2.0, 0).toVector());
                    if (toward.lengthSquared() > 1.0) {
                        le.setVelocity(le.getVelocity().multiply(0.6).add(toward.normalize().multiply(pull)));
                    }
                    if (tickDamage) {
                        Combat.damage(le, damage * Combat.strength(p), p);
                        le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, witherTicks, 0, false, true, true));
                        Location body = le.getLocation().add(0, le.getHeight() / 2.0, 0);
                        Fx.burst(body, Particle.SOUL_FIRE_FLAME, 10, 0.3, 0.05);
                        Fx.burst(body, Particle.SMOKE, 8, 0.3, 0.02);
                        Fx.sound(body, Sound.ENTITY_WITHER_HURT, 0.6f, 1.4f);
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        spinning.put(p.getUniqueId(), task);
    }

    private void stop(Player p, boolean early) {
        BukkitTask t = spinning.remove(p.getUniqueId());
        if (t != null) {
            t.cancel();
        }
        if (p.isOnline()) {
            Fx.sound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.5f, 1.8f);
            Fx.burst(p.getLocation().add(0, 1, 0), Particle.SOUL, 25, 1.0, 0.1);
            if (early) {
                p.sendActionBar(Component.text("The spin ends.", NamedTextColor.GRAY));
            }
        }
    }

    @Override
    void onQuit(Player p) {
        BukkitTask t = spinning.remove(p.getUniqueId());
        if (t != null) {
            t.cancel();
        }
    }

    private void despair(Player p, LivingEntity target) {
        if (!target.isValid() || !p.isOnline()) {
            return;
        }
        plugin.stun().stun(target, (int) (num("stun-seconds", 1.5) * 20), Stun.Style.SHOCK);

        if (target instanceof Player tp) {
            scramble(tp);
        }
        summon(p, target);

        Location l = target.getLocation();
        Fx.sound(l, Sound.ENTITY_WITHER_SHOOT, 1.0f, 0.7f);
        Fx.sound(l, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1.2f, 0.6f);
        Fx.burst(l.clone().add(0, 1, 0), Particle.SOUL, 40, 0.6, 0.08);
        Fx.burst(l.clone().add(0, 1, 0), Particle.SOUL_FIRE_FLAME, 30, 0.5, 0.1);
    }

    private void scramble(Player tp) {
        PlayerInventory inv = tp.getInventory();
        List<ItemStack> hotbar = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            hotbar.add(inv.getItem(i));
        }
        Collections.shuffle(hotbar);
        for (int i = 0; i < 9; i++) {
            inv.setItem(i, hotbar.get(i));
        }
        tp.updateInventory();
        tp.playSound(tp.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.6f);
    }

    private void summon(Player p, LivingEntity target) {
        int count = (int) num("minions", 4);
        long life = (long) (num("minion-lifetime", 20) * 20);
        World w = target.getWorld();

        for (int i = 0; i < count; i++) {
            double a = (Math.PI * 2 * i) / Math.max(1, count);
            Location loc = target.getLocation().add(Math.cos(a) * 2.5, 0, Math.sin(a) * 2.5);
            if (!loc.getBlock().isPassable() || !loc.clone().add(0, 1, 0).getBlock().isPassable()) {
                loc = target.getLocation();
            }
            final Location spawn = loc;

            WitherSkeleton ws = w.spawn(spawn, WitherSkeleton.class, s -> {
                s.getPersistentDataContainer().set(plugin.ownerKey(), PersistentDataType.STRING, p.getUniqueId().toString());
                s.getPersistentDataContainer().set(plugin.victimKey(), PersistentDataType.STRING, target.getUniqueId().toString());
                s.setPersistent(false);
                s.setCanPickupItems(false);
                s.setTarget(target);
            });
            minions.add(ws.getUniqueId());

            Fx.burst(spawn.clone().add(0, 1, 0), Particle.SOUL_FIRE_FLAME, 25, 0.3, 0.1);
            Fx.burst(spawn.clone().add(0, 1, 0), Particle.LARGE_SMOKE, 10, 0.3, 0.02);

            Bukkit.getScheduler().runTaskLater(plugin, () -> remove(ws), life);
        }
    }

    private void remove(WitherSkeleton ws) {
        minions.remove(ws.getUniqueId());
        if (ws.isValid()) {
            Fx.burst(ws.getLocation().add(0, 1, 0), Particle.POOF, 15, 0.3, 0.05);
            Fx.burst(ws.getLocation().add(0, 1, 0), Particle.SOUL, 10, 0.3, 0.05);
            ws.remove();
        }
    }

    @Override
    void shutdown() {
        for (BukkitTask t : spinning.values()) {
            t.cancel();
        }
        spinning.clear();
        for (UUID id : new ArrayList<>(minions)) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        minions.clear();
    }
}
