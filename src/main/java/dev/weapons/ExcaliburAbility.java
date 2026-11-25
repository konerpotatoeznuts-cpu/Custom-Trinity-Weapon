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

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Excalibur: sneak + right-click dashes forward in a barrage of slashes. */
final class ExcaliburAbility extends Ability {

    private static final Color GOLD = Color.fromRGB(255, 215, 80);

    private static final Color BLOOD = Color.fromRGB(150, 8, 12);
    private static final Color BLOOD_DARK = Color.fromRGB(85, 0, 4);

    /** A victim of the Ground Slash: bleeds for a while, and every hit taken makes the bleed hurt more. */
    private static final class Bleed {
        final LivingEntity victim;
        final Player source;
        long until;
        int stacks;
        long nextTick;
        Location lastDrop;
        final ArrayDeque<Object[]> puddles = new ArrayDeque<>(); // {Location, Long expiry}

        Bleed(LivingEntity victim, Player source) {
            this.victim = victim;
            this.source = source;
        }
    }

    private final Map<UUID, Bleed> bleeds = new HashMap<>();
    private final Map<UUID, Long> lastSneak = new HashMap<>();
    private final Set<UUID> slamming = new HashSet<>();
    private org.bukkit.scheduler.BukkitTask bleedTask;

    ExcaliburAbility(WeaponsPlugin plugin) {
        super(plugin);
        plugin.getServer().getPluginManager().registerEvents(new Events(), plugin);
        bleedTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::bleedTick, 5L, 5L);
    }

    @Override
    void onQuit(Player p) {
        lastSneak.remove(p.getUniqueId());
        slamming.remove(p.getUniqueId());
    }

    @Override
    void shutdown() {
        if (bleedTask != null) {
            bleedTask.cancel();
        }
        bleeds.clear();
    }

    private final class Events implements org.bukkit.event.Listener {

        /** Double sneak in the air, high enough up: Ground Slash. */
        @org.bukkit.event.EventHandler
        public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent e) {
            if (!e.isSneaking()) {
                return;
            }
            Player p = e.getPlayer();
            long now = System.currentTimeMillis();
            Long last = lastSneak.put(p.getUniqueId(), now);
            if (last == null || now - last > num("slam-double-sneak-ms", 450)
                    || plugin.items().identify(p.getInventory().getItemInMainHand()) != type()
                    || p.isOnGround() || p.isGliding() || p.isInWater() || slamming.contains(p.getUniqueId())
                    || plugin.stun().isStunned(p)) {
                return;
            }
            lastSneak.remove(p.getUniqueId());
            double h = groundHeight(p);
            if (h < num("slam-min-height", 4.0)) {
                p.sendActionBar(Component.text("Ground Slash needs at least " + (int) num("slam-min-height", 4.0) + " blocks of height.",
                        NamedTextColor.GRAY));
                return;
            }
            if (!allowed(p) || !cooldown(p, "groundslash", num("slam-cooldown", 30), "Ground Slash")) {
                return;
            }
            plugin.cooldowns().overlay(p, type());
            groundSlash(p, h);
        }

        /** Every hit a bleeding victim takes (not the bleed itself) adds a stack. */
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
            Bleed b = bleeds.get(e.getEntity().getUniqueId());
            if (b == null || Combat.isInternal() || e.getFinalDamage() <= 0) {
                return;
            }
            if (b.stacks < (int) num("bleed-max-stacks", 12)) {
                b.stacks++;
            }
        }

        @org.bukkit.event.EventHandler
        public void onDeath(org.bukkit.event.entity.EntityDeathEvent e) {
            bleeds.remove(e.getEntity().getUniqueId());
        }
    }

    private double groundHeight(Player p) {
        Location l = p.getLocation();
        RayTraceResult r = p.getWorld().rayTraceBlocks(l, new Vector(0, -1, 0), 96, FluidCollisionMode.NEVER, true);
        return r == null || r.getHitPosition() == null ? 96 : Math.max(0, l.getY() - r.getHitPosition().getY());
    }

    /** Dive to the ground, and slam it: damage, stun and a bleed on everyone nearby. */
    private void groundSlash(Player p, double startHeight) {
        slamming.add(p.getUniqueId());
        plugin.grantNoFall(p, 8);
        Fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_3, 1.2f, 1.2f);
        Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, 0.6f);
        final double speed = num("slam-speed", 2.4);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    slamming.remove(p.getUniqueId());
                    cancel();
                    return;
                }
                t++;
                if ((t > 2 && p.isOnGround()) || t > 60) {
                    slamming.remove(p.getUniqueId());
                    cancel();
                    impact(p, startHeight);
                    return;
                }
                p.setVelocity(new Vector(0, -speed, 0));
                p.setFallDistance(0f);
                Location l = p.getLocation().add(0, 1.0, 0);
                Fx.dust(l, GOLD, 1.6f, 6, 0.3);
                Fx.burst(l, Particle.END_ROD, 2, 0.25, 0.05);
                Fx.burst(l.clone().add(0, 0.8, 0), Particle.SWEEP_ATTACK, 1, 0.3, 0);
                if (t % 2 == 0) {
                    Fx.dust(l.clone().add(0, 1.5, 0), BLOOD, 1.2f, 2, 0.2);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void impact(Player p, double height) {
        Location c = p.getLocation();
        double radius = num("slam-radius", 6.0);
        double dmg = Math.min(num("slam-max-damage", 14.0), num("slam-damage", 6.0) + height * num("slam-damage-per-block", 0.25));
        int stunTicks = (int) (num("slam-stun-seconds", 2.0) * 20);
        long bleedFor = (long) (num("bleed-seconds", 20.0) * 1000);

        for (LivingEntity le : Combat.nearby(plugin, p, c, radius)) {
            Combat.damage(le, dmg * Combat.strength(p), p);
            Vector away = le.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (away.lengthSquared() > 0.001) {
                away.normalize().multiply(0.5);
            }
            away.setY(0.35);
            le.setVelocity(away);
            plugin.stun().stun(le, stunTicks, Stun.Style.STONE);
            Bleed b = bleeds.computeIfAbsent(le.getUniqueId(), k -> new Bleed(le, p));
            b.until = System.currentTimeMillis() + bleedFor;
            b.nextTick = 0;
            b.stacks = Math.max(b.stacks, 0);
            Fx.burst(le.getLocation().add(0, le.getHeight() / 2.0, 0), Particle.DAMAGE_INDICATOR, 10, 0.4, 0.2);
            Fx.dust(le.getLocation().add(0, le.getHeight() / 2.0, 0), BLOOD, 1.6f, 14, 0.4);
        }

        Fx.sound(c, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 2.0f, 0.8f);
        Fx.sound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.8f);
        Fx.sound(c, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.4f, 0.6f);
        Fx.burst(c.clone().add(0, 0.3, 0), Particle.EXPLOSION, 3, 0.8, 0);
        Fx.burst(c.clone().add(0, 0.5, 0), Particle.END_ROD, 30, 1.2, 0.3);
        Fx.shockwave(c.clone().add(0, 0.2, 0), GOLD, radius, 8);
        Fx.shockwave(c.clone().add(0, 0.25, 0), BLOOD, radius * 0.7, 8);
        if (plugin.getConfig().getBoolean("weapons.excalibur.slam-crater", true)) {
            Terrain.crater(p, c, num("slam-crater-radius", 3.0), (int) num("slam-crater-max-blocks", 50), num("slam-crater-max-hardness", 30));
        }
        // a golden cross of slashes on the ground
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + Math.PI / 4;
            Vector d = new Vector(Math.cos(a), 0, Math.sin(a));
            Fx.line(c.clone().add(0, 0.3, 0), c.clone().add(d.multiply(radius * 0.9)).add(0, 0.3, 0), 0.5, l -> {
                Fx.dust(l, GOLD, 1.4f, 1, 0.05);
                Fx.burst(l, Particle.CRIT, 1, 0.05, 0.02);
            });
        }
    }

    /** Runs every 5 ticks: bleed damage, blood drops and the blood trail. */
    private void bleedTick() {
        if (bleeds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Bleed>> it = bleeds.entrySet().iterator();
        while (it.hasNext()) {
            Bleed b = it.next().getValue();
            LivingEntity v = b.victim;
            boolean over = now > b.until || !v.isValid() || v.isDead() || !b.source.isOnline();
            // fading puddles
            b.puddles.removeIf(pd -> now > (Long) pd[1]);
            for (Object[] pd : b.puddles) {
                Location pl = (Location) pd[0];
                Fx.dust(pl, BLOOD, 1.5f, 2, 0.25);
                Fx.dust(pl, BLOOD_DARK, 1.0f, 1, 0.15);
            }
            if (over) {
                if (b.puddles.isEmpty()) {
                    it.remove();
                }
                continue;
            }
            Location feet = v.getLocation();
            Location mid = feet.clone().add(0, v.getHeight() * 0.6, 0);
            Fx.dust(mid, BLOOD, 1.1f, 2, 0.25);
            Fx.burst(mid, Particle.DAMAGE_INDICATOR, 1, 0.2, 0);
            // blood trail: a puddle every so often along their path
            if (b.lastDrop == null || b.lastDrop.getWorld() != feet.getWorld() || b.lastDrop.distanceSquared(feet) > 0.9) {
                Location drop = feet.clone().add(0, 0.12, 0);
                b.lastDrop = drop;
                b.puddles.addLast(new Object[]{drop, now + (long) (num("blood-trail-seconds", 8.0) * 1000)});
                while (b.puddles.size() > (int) num("blood-trail-max", 40)) {
                    b.puddles.removeFirst();
                }
            }
            // damage once a second, harder with every hit the victim took while bleeding
            if (now >= b.nextTick) {
                b.nextTick = now + 1000;
                double tickDmg = Math.min(num("bleed-max-tick", 6.0), num("bleed-damage", 0.4) + b.stacks * num("bleed-per-hit", 0.25));
                Combat.damage(v, tickDmg, b.source);
                Fx.dust(mid, BLOOD, 1.4f, 8, 0.3);
                Fx.sound(feet, Sound.BLOCK_HONEY_BLOCK_SLIDE, 0.5f, 0.6f);
            }
        }
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
