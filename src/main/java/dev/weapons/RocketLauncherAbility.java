package dev.weapons;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Rocket Launcher. Counter-Strike 2 has no launcher, so the blast uses the numbers of the CS2 HE grenade
 * (98 damage at the centre, falling off in a straight line to nothing at 350 units = 8.75 blocks, nothing through walls,
 * hurts the shooter too). The rocket itself is a real projectile: its motor lights after a moment, it flies fast and
 * explodes on whatever it touches. One rocket per load; the reserve is firework rockets.
 */
final class RocketLauncherAbility extends GunAbility {

    private static final class Rocket {
        final Player owner;
        final World w;
        final Vector dir;
        Vector pos;
        Vector vel;
        Item item;
        int age;

        Rocket(Player owner, World w, Vector pos, Vector dir) {
            this.owner = owner;
            this.w = w;
            this.pos = pos.clone();
            this.dir = dir.clone();
            this.vel = dir.clone().multiply(0.5);
        }
    }

    private final List<Rocket> rockets = new ArrayList<>();
    private BukkitTask task;

    RocketLauncherAbility(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.ROCKET_LAUNCHER;
    }

    @Override
    protected int defaultMagazine() {
        return 1;
    }

    @Override
    protected double defaultReloadSeconds() {
        return 3.5;
    }

    @Override
    protected String defaultAmmoItem() {
        return "FIREWORK_ROCKET";
    }

    @Override
    protected String ammoName() {
        return "firework rockets";
    }

    @Override
    protected void onReloadStart(Player p) {
        Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_START, 1.0f, 0.6f);
        Fx.sound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 0.8f);
    }

    @Override
    protected void onReloadDone(Player p) {
        Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 1.0f, 0.7f);
        Fx.sound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.0f, 0.7f);
    }

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        e.setUseItemInHand(Event.Result.DENY);
        if (!allowed(p) || isReloading(p)) {
            return;
        }
        if (ammo(item) <= 0) {
            startReload(p);
            return;
        }
        if (!cooldown(p, "fire", num("fire-delay", 0.8), "Rocket Launcher")) {
            return;
        }
        int left = ammo(item) - 1;
        setAmmo(item, left);
        p.getInventory().setItemInMainHand(item);
        launch(p);
        status(p, left);
    }

    private void launch(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        }
        right.normalize();
        World w = p.getWorld();
        Vector start = eye.toVector().add(dir.clone().multiply(1.0)).add(right.clone().multiply(0.3)).add(new Vector(0, -0.25, 0));
        // do not start inside a wall
        RayTraceResult wall = w.rayTraceBlocks(eye, dir, 1.0, FluidCollisionMode.NEVER, true);
        if (wall != null && wall.getHitPosition() != null) {
            start = wall.getHitPosition().clone().subtract(dir.clone().multiply(0.1));
        }
        Rocket r = new Rocket(p, w, start, dir);
        try {
            ItemStack stack = new ItemStack(org.bukkit.Material.FIREWORK_ROCKET);
            r.item = w.dropItem(new Location(w, start.getX(), start.getY(), start.getZ()), stack, it -> {
                it.setPickupDelay(32767);
                it.setCanPlayerPickup(false);
                it.setCanMobPickup(false);
                it.setUnlimitedLifetime(true);
                it.setGravity(false);
                it.setInvulnerable(true);
                it.setPersistent(false);
                it.setVelocity(r.vel);
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Rocket model failed: " + t);
        }
        rockets.add(r);
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
        }

        // launch: sound, back-blast, kick
        Location mouth = new Location(w, start.getX(), start.getY(), start.getZ());
        Fx.sound(eye, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2.0f, 0.6f);
        Fx.sound(eye, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.6f);
        Fx.burst(mouth, Particle.FLAME, 14, 0.12, 0.08);
        Fx.burst(mouth, Particle.LARGE_SMOKE, 8, 0.15, 0.04);
        Location behind = eye.clone().add(dir.clone().multiply(-1.2)).add(0, -0.3, 0);
        Fx.burst(behind, Particle.CLOUD, 14, 0.2, 0.15);
        Fx.burst(behind, Particle.FLAME, 8, 0.15, 0.1);
        Vector back = dir.clone().multiply(-num("recoil", 0.5));
        back.setY(Math.max(back.getY(), 0) + 0.05);
        p.setVelocity(p.getVelocity().add(back));
        double kick = num("camera-kick", 5.0);
        if (kick > 0) {
            Location l = p.getLocation();
            p.setRotation(l.getYaw(), (float) Math.max(-90.0, l.getPitch() - kick));
        }
    }

    private void tickAll() {
        for (Iterator<Rocket> it = rockets.iterator(); it.hasNext(); ) {
            Rocket r = it.next();
            Location boom = null;
            try {
                boom = step(r);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Rocket error", t);
                boom = new Location(r.w, r.pos.getX(), r.pos.getY(), r.pos.getZ());
            }
            if (boom != null) {
                it.remove();
                if (r.item != null && r.item.isValid()) {
                    r.item.remove();
                }
                explode(r, boom);
            }
        }
        if (rockets.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Moves the rocket one tick. Returns where it exploded, or null if it is still flying. */
    private Location step(Rocket r) {
        r.age++;
        double motorTicks = num("motor-ticks", 8);
        double v0 = num("start-speed", 0.5);
        double vmax = num("max-speed", 2.2);
        double speed = r.age >= motorTicks ? vmax : v0 + (vmax - v0) * (r.age / motorTicks);
        r.vel = r.dir.clone().multiply(speed);
        if (r.age > motorTicks) {
            r.vel.setY(r.vel.getY() - num("drop", 0.0015) * (r.age - motorTicks)); // a little sag over distance
        }
        double len = r.vel.length();
        Location from = new Location(r.w, r.pos.getX(), r.pos.getY(), r.pos.getZ());
        Vector dir = r.vel.clone().normalize();
        RayTraceResult hit = r.w.rayTrace(from, dir, len + 0.2, FluidCollisionMode.NEVER, true, 0.25,
                en -> en instanceof LivingEntity le && !le.isDead() && (!en.equals(r.owner) || r.age > 6)
                        && !(en instanceof Player pl && pl.getGameMode() == org.bukkit.GameMode.SPECTATOR));
        Location result = null;
        if (hit != null && hit.getHitPosition() != null) {
            Vector hp = hit.getHitPosition();
            Vector n = hit.getHitBlockFace() != null ? hit.getHitBlockFace().getDirection() : dir.clone().multiply(-1);
            result = new Location(r.w, hp.getX() + n.getX() * 0.2, hp.getY() + n.getY() * 0.2, hp.getZ() + n.getZ() * 0.2);
        } else {
            r.pos.add(r.vel);
            if (r.age > num("fuse-seconds", 8) * 20 || !r.w.isChunkLoaded(r.pos.getBlockX() >> 4, r.pos.getBlockZ() >> 4)
                    || r.pos.getY() < r.w.getMinHeight()) {
                result = new Location(r.w, r.pos.getX(), r.pos.getY(), r.pos.getZ());
            }
        }
        Location now = new Location(r.w, r.pos.getX(), r.pos.getY(), r.pos.getZ());
        if (r.item != null && r.item.isValid()) {
            r.item.teleport(now.clone().add(0, 0.1, 0));
            r.item.setVelocity(r.vel);
        }
        // exhaust trail
        Fx.burst(now, Particle.FLAME, 2, 0.04, 0.02);
        Fx.burst(now, Particle.LARGE_SMOKE, 2, 0.06, 0.01);
        Fx.dust(now, Color.fromRGB(255, 190, 80), 1.2f, 2, 0.05);
        if (r.age % 3 == 0) {
            Fx.burst(now.clone().subtract(r.vel.clone().multiply(0.5)), Particle.CLOUD, 1, 0.05, 0.01);
        }
        if (r.age % 4 == 0) {
            Fx.sound(now, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.5f, 0.7f);
        }
        // the rocket cuts through smoke
        Fx.disturbSmoke(from, now, 1.4, 3.0);
        return result;
    }

    private void explode(Rocket r, Location c) {
        World w = r.w;
        double radius = num("blast-radius", 8.75); // 350 units
        double damage = num("damage", 98.0) * num("damage-scale", 0.35);
        double selfMult = num("self-damage", 0.5);

        // effects
        Fx.burst(c, Particle.EXPLOSION_EMITTER, 1, 0, 0);
        Fx.burst(c, Particle.EXPLOSION, 6, 1.5, 0);
        Fx.burst(c, Particle.FLAME, 50, 1.4, 0.2);
        Fx.burst(c, Particle.LARGE_SMOKE, 30, 1.6, 0.08);
        Fx.shockwave(c, Color.fromRGB(255, 160, 60), Math.min(radius, 9.0), 8);
        Fx.sound(c, Sound.ENTITY_GENERIC_EXPLODE, 4.0f, 0.8f);
        Fx.sound(c, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 3.0f, 0.6f);
        Fx.disturbSmoke(c, radius, 7.0);

        if (plugin.getConfig().getBoolean("weapons.rocket_launcher.break-blocks", false)) {
            w.createExplosion(c, (float) num("block-power", 3.0), false, true, r.owner);
        }

        double sigma = radius;
        for (Entity en : w.getNearbyEntities(c, radius, radius, radius)) {
            if (!(en instanceof LivingEntity le) || le.isDead()) {
                continue;
            }
            boolean self = en.equals(r.owner);
            if (!self && !Combat.valid(plugin, r.owner, en)) {
                continue;
            }
            Vector centre = le.getBoundingBox().getCenter();
            double dist = centre.distance(c.toVector());
            if (dist > radius) {
                continue;
            }
            // nothing through walls (CS2: cover protects you)
            if (dist > 1.5) {
                Vector dir = centre.clone().subtract(c.toVector()).normalize();
                RayTraceResult wall = w.rayTraceBlocks(c, dir, dist - 0.3, FluidCollisionMode.NEVER, true);
                if (wall != null) {
                    continue;
                }
            }
            double falloff = 1.0 - dist / sigma; // straight line to zero at the radius, like the HE grenade
            double d = damage * falloff;
            if (self) {
                d *= selfMult;
            }
            Vector push = centre.clone().subtract(c.toVector());
            if (push.lengthSquared() < 1.0E-4) {
                push = new Vector(0, 1, 0);
            }
            push.normalize().multiply(num("knockback", 1.5) * falloff).setY(Math.max(0.25, push.getY() * num("knockback", 1.5) * falloff + 0.25));
            if (d > 0.1) {
                Combat.damage(le, d / Math.max(0.01, Combat.globalMult()), r.owner);
            }
            if (le.isValid()) {
                le.setVelocity(le.getVelocity().add(push));
            }
            Fx.burst(le.getLocation().add(0, 1, 0), Particle.FLAME, 8, 0.3, 0.05);
            if (!self && r.owner.isOnline()) {
                r.owner.playSound(r.owner.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 0.8f);
            }
        }
    }

    @Override
    void shutdown() {
        super.shutdown();
        for (Rocket r : rockets) {
            if (r.item != null && r.item.isValid()) {
                r.item.remove();
            }
        }
        rockets.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
