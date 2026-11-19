package dev.weapons;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Great Axe. Right-click throws the axe on a chain.
 * Hits nothing solid or living: nothing happens but a short cooldown.
 * Hits a block: you are pulled to it like a grappling hook (sneak to let go).
 * Hits a mob or player: it is dragged to you and takes damage.
 */
final class GreatAxeAbility extends Ability {

    private static final Color CHAIN = Color.fromRGB(170, 170, 185);

    private static final class Throw {
        BukkitTask task;
        ItemDisplay display;
    }

    private final Map<UUID, Throw> active = new HashMap<>();

    GreatAxeAbility(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.GREAT_AXE;
    }

    /** Heavy: Slowness while held. */
    @Override
    void tick(Player p, ItemStack held) {
        if (held == null) {
            return;
        }
        int level = (int) num("slowness-level", 1);
        if (level > 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 10, level - 1, true, false, false));
        }
    }

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        if (!allowed(p) || active.containsKey(p.getUniqueId())) {
            return;
        }
        if (!plugin.cooldowns().check(p, type(), "throw", "Chain Throw")) {
            return;
        }
        e.setCancelled(true);
        launch(p);
    }

    private void launch(Player p) {
        final World w = p.getWorld();
        final Vector dir = p.getEyeLocation().getDirection().normalize();
        final double range = num("throw-range", 30.0);
        final double speed = num("throw-speed", 2.0); // blocks per tick
        final Location start = p.getEyeLocation().add(dir.clone().multiply(0.8)).add(0, -0.3, 0);

        final Throw th = new Throw();
        active.put(p.getUniqueId(), th);

        try {
            th.display = w.spawn(start, ItemDisplay.class, d -> {
                d.setItemStack(new ItemStack(Material.NETHERITE_AXE));
                d.setPersistent(false);
                d.setInterpolationDuration(2);
                d.setInterpolationDelay(0);
                d.setTeleportDuration(1);
                d.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                d.setViewRange(2.0f);
            });
        } catch (Throwable ex) {
            th.display = null; // still works, just without the spinning model
        }

        Fx.sound(start, Sound.ITEM_TRIDENT_THROW, 1.5f, 0.6f);
        Fx.sound(start, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.7f);
        Fx.sound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.5f);

        th.task = new BukkitRunnable() {
            final Location pos = start.clone();
            double travelled = 0;
            float spin = 0f;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || !p.getWorld().equals(w)) {
                    finish(p, th);
                    return;
                }
                double step = Math.min(speed, range - travelled);
                Location from = pos.clone();
                RayTraceResult blockHit = w.rayTraceBlocks(from, dir, step, FluidCollisionMode.NEVER, true);
                RayTraceResult entHit = w.rayTraceEntities(from, dir, step, 0.7,
                        en -> en instanceof LivingEntity && Combat.valid(plugin, p, en));

                double blockDist = blockHit == null ? Double.MAX_VALUE : from.toVector().distance(blockHit.getHitPosition());
                double entDist = entHit == null ? Double.MAX_VALUE : from.toVector().distance(entHit.getHitPosition());

                if (entHit != null && entHit.getHitEntity() instanceof LivingEntity target && entDist <= blockDist) {
                    Location at = entHit.getHitPosition().toLocation(w);
                    chain(p, at);
                    hitEntity(p, target, at);
                    finish(p, th);
                    return;
                }
                if (blockHit != null) {
                    Location at = blockHit.getHitPosition().toLocation(w);
                    chain(p, at);
                    hitBlock(p, at);
                    finish(p, th);
                    return;
                }

                pos.add(dir.clone().multiply(step));
                travelled += step;
                trail(pos, from);
                chain(p, pos);
                if (th.display != null && th.display.isValid()) {
                    spin += 0.9f;
                    th.display.teleport(pos);
                    th.display.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateZ(spin),
                            new Vector3f(2.2f, 2.2f, 2.2f), new Quaternionf()));
                }
                if (travelled >= range - 0.01) {
                    miss(p, pos);
                    finish(p, th);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void finish(Player p, Throw th) {
        if (th.task != null) {
            th.task.cancel();
        }
        if (th.display != null && th.display.isValid()) {
            th.display.remove();
        }
        active.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------ outcomes

    private void hitEntity(Player p, LivingEntity target, Location at) {
        plugin.cooldowns().start(p, type(), "throw", num("hit-cooldown", 25));
        plugin.cooldowns().overlay(p, type());

        Combat.damage(target, num("hit-damage", 7.0) * Combat.strength(p), p);
        World w = at.getWorld();
        Fx.burst(at, Particle.EXPLOSION, 1, 0, 0);
        Fx.burst(at, Particle.CRIT, 40, 0.5, 0.6);
        Fx.burst(at, Particle.SWEEP_ATTACK, 3, 0.4, 0);
        Fx.sound(at, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.6f, 0.9f);
        Fx.shockwave(at.clone().add(0, 0.2, 0), org.bukkit.Color.fromRGB(170, 175, 190), 4.0, 8);
        Fx.burst(at, Particle.CRIT, 25, 0.5, 0.4);
        Fx.sound(at, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.5f, 0.6f);
        Fx.sound(at, Sound.BLOCK_CHAIN_BREAK, 1.2f, 0.8f);

        // Drag it to the thrower in a few pulses so it reliably arrives (and cannot be shrugged off by knockback)
        new BukkitRunnable() {
            int n = 0;

            @Override
            public void run() {
                if (n++ >= 6 || !target.isValid() || target.isDead() || !p.isOnline()
                        || !p.getWorld().equals(target.getWorld())) {
                    cancel();
                    return;
                }
                Location goal = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(1.6));
                Vector to = goal.toVector().subtract(target.getLocation().toVector());
                double dist = to.length();
                if (dist < 1.4) {
                    cancel();
                    return;
                }
                to.normalize().multiply(Math.min(2.4, 0.5 + dist * 0.22));
                to.setY(Math.max(to.getY(), 0.28));
                target.setVelocity(to);
                Fx.burst(target.getLocation().add(0, target.getHeight() / 2.0, 0), Particle.CRIT, 4, 0.2, 0.05);
            }
        }.runTaskTimer(plugin, 1L, 2L);
        w.playSound(at, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.5f, 0.6f);
    }

    private void hitBlock(Player p, Location at) {
        plugin.cooldowns().start(p, type(), "throw", num("grapple-cooldown", 20));
        plugin.cooldowns().overlay(p, type());
        plugin.grantNoFall(p, 6);

        Fx.burst(at, Particle.CRIT, 25, 0.3, 0.4);
        Fx.burst(at, Particle.CLOUD, 10, 0.2, 0.05);
        Fx.sound(at, Sound.BLOCK_ANVIL_LAND, 0.8f, 1.6f);
        Fx.shockwave(at.clone().add(0, 0.2, 0), org.bukkit.Color.fromRGB(200, 200, 215), 3.0, 8);
        Fx.sound(at, Sound.BLOCK_CHAIN_PLACE, 1.5f, 0.7f);

        final Location anchor = at.clone();
        final double maxSpeed = num("grapple-speed", 1.9);
        new BukkitRunnable() {
            int ticks = 0;
            boolean first = true;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || !p.getWorld().equals(anchor.getWorld()) || ticks++ > 50) {
                    cancel();
                    return;
                }
                // Sneak to let go
                if (p.isSneaking() && ticks > 3) {
                    cancel();
                    return;
                }
                Location body = p.getLocation().add(0, 1.0, 0);
                Vector to = anchor.toVector().subtract(body.toVector());
                double dist = to.length();
                if (dist < 2.2) {
                    p.setVelocity(p.getVelocity().multiply(0.4));
                    Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 1.2f);
                    cancel();
                    return;
                }
                to.normalize().multiply(Math.min(maxSpeed, 0.6 + dist * 0.15));
                if (first) {
                    to.setY(to.getY() + 0.35);
                    first = false;
                }
                p.setVelocity(to);
                p.setFallDistance(0f);
                chain(p, anchor);
                Fx.burst(p.getLocation().add(0, 0.5, 0), Particle.CLOUD, 2, 0.2, 0.01);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void miss(Player p, Location at) {
        plugin.cooldowns().start(p, type(), "throw", num("miss-cooldown", 5));
        plugin.cooldowns().overlay(p, type());
        Fx.burst(at, Particle.POOF, 8, 0.2, 0.05);
        Fx.sound(at, Sound.ITEM_TRIDENT_RETURN, 1.2f, 0.8f);
    }

    // ------------------------------------------------------------------ visuals

    private void trail(Location pos, Location from) {
        Fx.line(from, pos, 0.5, l -> {
            Fx.burst(l, Particle.CRIT, 1, 0.05, 0.0);
            Fx.burst(l, Particle.SMOKE, 1, 0.05, 0.0);
        });
        Fx.burst(pos, Particle.SWEEP_ATTACK, 1, 0, 0);
    }

    /** Iron chain from the player's hand to the axe. */
    private void chain(Player p, Location end) {
        Location hand = p.getEyeLocation().add(0, -0.5, 0);
        Fx.line(hand, end, 0.6, l -> Fx.dust(l, CHAIN, 0.9f, 1, 0.0));
    }

    // ------------------------------------------------------------------ cleanup

    @Override
    void onQuit(Player p) {
        Throw th = active.get(p.getUniqueId());
        if (th != null) {
            finish(p, th);
        }
    }

    @Override
    void shutdown() {
        for (Throw th : active.values()) {
            if (th.task != null) {
                th.task.cancel();
            }
            if (th.display != null && th.display.isValid()) {
                th.display.remove();
            }
        }
        active.clear();
    }
}
