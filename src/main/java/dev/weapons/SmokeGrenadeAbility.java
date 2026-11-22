package dev.weapons;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Smoke Grenade: a Counter-Strike 2 smoke grenade.
 * <ul>
 *   <li>Throw speed, throw angle, gravity and bounce follow CS2 (Source units, 40 units = 1 block):
 *       the grenade leaves the hand 10 degrees higher than you aim, speed is (90 - pitch) * 6 capped at 750 units/s,
 *       a low throw is 30% of that, your own movement adds 125%, gravity is 0.4 * 800 units/s^2 and every bounce
 *       keeps 45% of the speed.</li>
 *   <li>It is simulated by hand (not a vanilla egg): it bounces off walls, ceilings, floors and mobs and rolls to a stop.</li>
 *   <li>When it stops moving it pops into a cloud that fills the room, spreading around corners and never through walls,
 *       blocks the view for about 18 seconds and puts out fires.</li>
 * </ul>
 */
final class SmokeGrenadeAbility extends Ability {

    private static final double R = 0.08; // collision radius of the grenade

    private final Random rnd = new Random();
    private final List<Grenade> grenades = new ArrayList<>();
    private final List<Cloud> clouds = new ArrayList<>();
    private final Map<UUID, Vector> lastPos = new HashMap<>();
    private final Map<UUID, Vector> moveVel = new HashMap<>();
    private BukkitTask task;

    SmokeGrenadeAbility(WeaponsPlugin plugin) {
        super(plugin);
        plugin.getServer().getPluginManager().registerEvents(new BlastListener(), plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.SMOKE_GRENADE;
    }

    // ------------------------------------------------------------------ own movement (so a running throw inherits speed)

    @Override
    void tick(Player p, ItemStack held) {
        Vector pos = p.getLocation().toVector();
        Vector prev = lastPos.put(p.getUniqueId(), pos);
        if (prev != null && prev.distanceSquared(pos) < 9) {
            moveVel.put(p.getUniqueId(), pos.clone().subtract(prev).multiply(0.5)); // called every 2 ticks: blocks per tick
        } else {
            moveVel.put(p.getUniqueId(), new Vector());
        }
    }

    @Override
    void onQuit(Player p) {
        lastPos.remove(p.getUniqueId());
        moveVel.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------ throwing

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        e.setCancelled(true); // never the vanilla egg throw
        if (!allowed(p)) {
            return;
        }
        if (!cooldown(p, "throw", num("cooldown", 1.0), "Smoke Grenade")) {
            return;
        }
        double strength = p.isSneaking() ? num("low-strength", 0.0) : num("high-strength", 1.0);
        launch(p, strength);
        Animations.trigger(plugin, p, type(), "throw");
        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
        }
        p.swingMainHand();
    }

    private void launch(Player p, double strength) {
        Location eye = p.getEyeLocation();
        // CS2: the grenade is thrown 10 degrees above the crosshair
        double pitch = eye.getPitch(); // negative = up, like the Source engine
        double adj = -10.0 + pitch * (80.0 / 90.0);
        Location aim = eye.clone();
        aim.setPitch((float) adj);
        Vector dir = aim.getDirection().normalize();

        double unitsPerSecond = Math.min(num("max-speed", 750), (90.0 - adj) * 6.0);
        unitsPerSecond *= 0.7 * strength + 0.3;
        double speed = unitsPerSecond / 800.0; // blocks per tick (40 units per block, 20 ticks per second)

        Vector vel = dir.clone().multiply(speed);
        Vector mv = moveVel.get(p.getUniqueId());
        if (mv == null) {
            mv = p.getVelocity();
        }
        double inherit = num("inherit-velocity", 1.25);
        vel.add(new Vector(mv.getX() * inherit, p.isOnGround() ? 0 : mv.getY() * inherit, mv.getZ() * inherit));

        // leaves the hand a little lower for a weak throw, and 16 units (0.4 blocks) in front of the eyes
        Vector start = eye.toVector().add(new Vector(0, -0.3 * (1.0 - strength), 0));
        Vector front = start.clone().add(dir.clone().multiply(0.4));
        World w = p.getWorld();
        RayTraceResult r = w.rayTraceBlocks(new Location(w, start.getX(), start.getY(), start.getZ()), dir, 0.4 + R,
                FluidCollisionMode.NEVER, true);
        if (r != null && r.getHitPosition() != null) {
            front = r.getHitPosition().clone().subtract(dir.clone().multiply(R + 0.02));
        }

        Grenade g = new Grenade(p, w, front, vel);
        g.item = spawnVisual(w, front, vel);
        grenades.add(g);
        ensureTask();

        Location at = p.getLocation();
        Fx.sound(at, Sound.BLOCK_LEVER_CLICK, 0.8f, 1.6f);
        Fx.sound(at, Sound.ENTITY_SNOWBALL_THROW, 0.9f, strength > 0.5 ? 0.7f : 0.9f);
    }

    private Item spawnVisual(World w, Vector pos, Vector vel) {
        try {
            ItemStack stack = plugin.items().create(WeaponType.SMOKE_GRENADE);
            stack.setAmount(1);
            return w.dropItem(new Location(w, pos.getX(), pos.getY(), pos.getZ()), stack, it -> {
                it.setPickupDelay(32767);
                it.setCanPlayerPickup(false);
                it.setCanMobPickup(false);
                it.setUnlimitedLifetime(true);
                it.setGravity(false);
                it.setInvulnerable(true);
                it.setPersistent(false);
                it.setVelocity(vel);
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Smoke grenade model failed: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ the simulation

    private void ensureTask() {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
        }
    }

    private void tickAll() {
        for (Iterator<Grenade> it = grenades.iterator(); it.hasNext(); ) {
            Grenade g = it.next();
            boolean done;
            try {
                done = stepGrenade(g);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Smoke grenade error", t);
                done = true;
            }
            if (done) {
                it.remove();
                pop(g);
            }
        }
        if (!clouds.isEmpty()) {
            scanProjectiles();
        }
        for (Iterator<Cloud> it = clouds.iterator(); it.hasNext(); ) {
            Cloud c = it.next();
            try {
                if (!stepCloud(c)) {
                    it.remove();
                }
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Smoke cloud error", t);
                it.remove();
            }
        }
        if (grenades.isEmpty() && clouds.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /** One tick (two half steps) of the grenade. Returns true when it should pop. */
    private boolean stepGrenade(Grenade g) {
        World w = g.w;
        g.age++;
        if (g.age > num("max-flight-seconds", 20) * 20) {
            return true;
        }
        if (!w.isChunkLoaded(((int) Math.floor(g.pos.getX())) >> 4, ((int) Math.floor(g.pos.getZ())) >> 4)
                || g.pos.getY() < w.getMinHeight()) {
            return true;
        }
        final double grav = 0.05 * num("gravity-scale", 0.4); // 800 units/s^2 = 0.05 blocks/tick^2, times CS2's 0.4
        final double elast = num("elasticity", 0.45);
        final double rollFriction = Math.pow(num("roll-friction", 0.85), 0.5); // per half step
        final double restSpeed = 20.0 / 800.0; // 20 units/s: below this on the floor the grenade is at rest
        boolean touchedFloor = false;

        for (int sub = 0; sub < 2; sub++) {
            final double dt = 0.5;
            g.vel.setY(g.vel.getY() - grav * dt);
            Block at = w.getBlockAt((int) Math.floor(g.pos.getX()), (int) Math.floor(g.pos.getY()), (int) Math.floor(g.pos.getZ()));
            if (at.isLiquid()) {
                g.vel.multiply(0.92);
            }
            Vector move = g.vel.clone().multiply(dt);
            double len = move.length();
            if (len > 1.0E-7) {
                Vector dir = move.clone().multiply(1.0 / len);
                Location from = new Location(w, g.pos.getX(), g.pos.getY(), g.pos.getZ());
                RayTraceResult r = w.rayTraceBlocks(from, dir, len + R, FluidCollisionMode.NEVER, true);
                if (r == null || r.getHitPosition() == null || r.getHitBlockFace() == null) {
                    g.pos.add(move);
                } else {
                    Vector n = r.getHitBlockFace().getDirection();
                    g.pos = r.getHitPosition().clone().add(n.clone().multiply(R + 0.005));
                    double vn = g.vel.dot(n);
                    if (n.getY() > 0.7 && Math.abs(vn) < 0.07) {
                        // rolling along the floor: no bounce, just friction
                        g.vel.setY(0);
                        g.vel.setX(g.vel.getX() * rollFriction);
                        g.vel.setZ(g.vel.getZ() * rollFriction);
                        touchedFloor = true;
                    } else {
                        // CS2: full reflection, then everything is scaled by the grenade's elasticity (0.45)
                        double impact = Math.abs(vn);
                        g.vel = g.vel.clone().subtract(n.clone().multiply(2.0 * vn)).multiply(elast);
                        if (n.getY() > 0.7) {
                            touchedFloor = true;
                        }
                        if (impact > 0.04) {
                            Fx.sound(new Location(w, g.pos.getX(), g.pos.getY(), g.pos.getZ()), Sound.BLOCK_STONE_HIT,
                                    (float) Math.min(1.0, 0.25 + impact * 2.0), (float) (0.8 + rnd.nextDouble() * 0.4));
                        }
                    }
                }
            }
            bounceOffMobs(g, elast);
        }

        // resting on the floor
        double hs = Math.hypot(g.vel.getX(), g.vel.getZ());
        if (touchedFloor && hs < restSpeed && Math.abs(g.vel.getY()) < restSpeed) {
            g.vel = new Vector();
            g.rest++;
        } else if (!touchedFloor || hs >= restSpeed) {
            g.rest = 0;
        }

        // the visible grenade
        Item it = g.item;
        if (it != null && it.isValid()) {
            it.teleport(new Location(w, g.pos.getX(), g.pos.getY() + 0.12, g.pos.getZ()));
            it.setVelocity(g.vel);
        }
        return g.rest >= 3; // pops as soon as it has stopped moving
    }

    private void bounceOffMobs(Grenade g, double elast) {
        Location loc = new Location(g.w, g.pos.getX(), g.pos.getY(), g.pos.getZ());
        for (Entity en : g.w.getNearbyEntities(loc, 1.2, 1.2, 1.2)) {
            if (!(en instanceof LivingEntity le) || le.isDead()) {
                continue;
            }
            if (en instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (g.thrower != null && en.getUniqueId().equals(g.thrower.getUniqueId()) && g.age < 8) {
                continue; // do not hit the thrower's own hands
            }
            BoundingBox bb = le.getBoundingBox().expand(R);
            if (!bb.contains(g.pos)) {
                continue;
            }
            Vector n = g.pos.clone().subtract(bb.getCenter());
            n.setY(n.getY() * 0.35);
            if (n.lengthSquared() < 1.0E-6) {
                n = new Vector(0, 1, 0);
            }
            n.normalize();
            double vn = g.vel.dot(n);
            if (vn < 0) {
                g.vel = g.vel.clone().subtract(n.clone().multiply(2.0 * vn)).multiply(elast);
                Fx.sound(loc, Sound.BLOCK_STONE_HIT, 0.6f, 1.2f);
            }
            g.pos.add(n.multiply(0.1));
        }
    }

    // ------------------------------------------------------------------ pop and smoke

    private void pop(Grenade g) {
        if (g.item != null && g.item.isValid()) {
            g.item.remove();
        }
        World w = g.w;
        Location c = new Location(w, g.pos.getX(), g.pos.getY() + 0.25, g.pos.getZ());
        Cloud cloud = buildCloud(w, c);
        clouds.add(cloud);
        ensureTask();
        Fx.sound(c, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 1.6f, 0.55f);
        Fx.sound(c, Sound.BLOCK_FIRE_EXTINGUISH, 1.4f, 0.6f);
        Fx.sound(c, Sound.ENTITY_BREEZE_WIND_BURST, 0.8f, 0.6f);
        Fx.burst(c, org.bukkit.Particle.CLOUD, 20, 0.3, 0.08);
    }

    /** The cloud fills the air around the pop like a liquid: it spreads around corners but never through walls. */
    private Cloud buildCloud(World w, Location c) {
        double radius = num("smoke-radius", 4.4);
        int cap = (int) num("max-voxels", 760);
        Cloud cl = new Cloud(w, c.toVector());
        cl.max = (int) (num("smoke-seconds", 18) * 20);
        cl.bloom = (int) Math.max(4, num("bloom-ticks", 20));

        int sx = c.getBlockX();
        int sy = c.getBlockY();
        int sz = c.getBlockZ();
        if (!w.getBlockAt(sx, sy, sz).isPassable()) {
            sy++;
        }
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(new int[]{sx, sy, sz, 0, 0});
        seen.add(key(sx, sy, sz));
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty() && cl.voxels.size() < cap) {
            int[] v = queue.poll();
            cl.voxels.add(v);
            cl.maxLevel = Math.max(cl.maxLevel, v[3]);
            for (int[] o : nb) {
                int x = v[0] + o[0];
                int y = v[1] + o[1];
                int z = v[2] + o[2];
                double dx = x + 0.5 - c.getX();
                double dy = y + 0.5 - c.getY();
                double dz = z + 0.5 - c.getZ();
                if (dx * dx + dy * dy + dz * dz > radius * radius) {
                    continue;
                }
                if (!seen.add(key(x, y, z))) {
                    continue;
                }
                if (!w.isChunkLoaded(x >> 4, z >> 4) || y < w.getMinHeight() || y >= w.getMaxHeight()) {
                    continue;
                }
                if (!w.getBlockAt(x, y, z).isPassable()) {
                    continue;
                }
                queue.add(new int[]{x, y, z, v[3] + 1, 0});
            }
        }
        return cl;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private boolean stepCloud(Cloud c) {
        c.age++;
        if (c.age > c.max || c.voxels.isEmpty()) {
            return false;
        }
        // blooms outward over the first second
        int allowedLevel = c.age >= c.bloom ? Integer.MAX_VALUE : (int) Math.ceil(c.maxLevel * (c.age / (double) c.bloom));
        int shown = 0;
        while (shown < c.voxels.size() && c.voxels.get(shown)[3] <= allowedLevel) {
            shown++;
        }
        int remaining = c.max - c.age;
        if (remaining < 80) {
            shown = (int) (shown * (remaining / 80.0)); // the edges thin out first at the end
        }
        if (shown <= 0) {
            return true;
        }
        if (c.age % 2 == 0) {
            double density = num("particle-density", 0.4);
            int n = (int) Math.max(6, Math.min((int) num("max-particles-per-burst", 170), shown * density));
            if (c.age < c.bloom) {
                n *= 2;
            }
            boolean scoped = Fx.beginHere();
            try {
            for (int i = 0; i < n; i++) {
                int[] v = c.voxels.get(rnd.nextInt(shown));
                if (v[4] > c.age) {
                    continue; // a bullet, blast or ability blew this part of the smoke away
                }
                int g = 150 + rnd.nextInt(50);
                Location l = new Location(c.w, v[0] + rnd.nextDouble(), v[1] + rnd.nextDouble(), v[2] + rnd.nextDouble());
                Fx.forceDust(l, Color.fromRGB(g, g, g + 4), 4.0f);
            }
            } finally {
                if (scoped) {
                    Fx.end();
                }
            }
        }
        if (c.age % 10 == 0) {
            extinguish(c);
        }
        if (c.age % 40 == 20) {
            Location mid = new Location(c.w, c.center.getX(), c.center.getY(), c.center.getZ());
            Fx.sound(mid, Sound.BLOCK_CAMPFIRE_CRACKLE, 0.35f, 0.6f);
        }
        return true;
    }

    /** CS2 smoke puts out fire: burning mobs and fire blocks inside the cloud. */
    private void extinguish(Cloud c) {
        for (int[] v : c.voxels) {
            Block b = c.w.getBlockAt(v[0], v[1], v[2]);
            Material m = b.getType();
            if (m == Material.FIRE || m == Material.SOUL_FIRE) {
                b.setType(Material.AIR, false);
            }
        }
        Location mid = new Location(c.w, c.center.getX(), c.center.getY(), c.center.getZ());
        double r = num("smoke-radius", 4.4);
        for (Entity en : c.w.getNearbyEntities(mid, r, r, r)) {
            if (en instanceof LivingEntity le && le.getFireTicks() > 0 && en.getLocation().distanceSquared(mid) <= r * r) {
                le.setFireTicks(0);
            }
        }
    }

    boolean hasClouds() {
        return !clouds.isEmpty();
    }

    // ------------------------------------------------------------------ holes: bullets, throwables, blasts, abilities

    /**
     * Blow a hole through the smoke along the line a-b (or around one point when a equals b). The smoke slowly fills it
     * in again. Called for projectiles, explosions and, through {@link Fx}, every weapon beam, slash and shockwave.
     */
    void disturb(World w, Vector a, Vector b, double radius, double seconds) {
        if (clouds.isEmpty() || w == null) {
            return;
        }
        double reach = num("smoke-radius", 4.4) + radius + 1;
        Vector mid = a.clone().add(b).multiply(0.5);
        double half = a.distance(b) / 2;
        int hold = (int) (seconds * 20);
        Vector ab = b.clone().subtract(a);
        double abLen2 = ab.lengthSquared();
        for (Cloud c : clouds) {
            if (!c.w.equals(w) || c.center.distance(mid) > reach + half) {
                continue;
            }
            for (int[] v : c.voxels) {
                double px = v[0] + 0.5 - a.getX();
                double py = v[1] + 0.5 - a.getY();
                double pz = v[2] + 0.5 - a.getZ();
                double t = abLen2 < 1.0E-9 ? 0 : Math.max(0, Math.min(1, (px * ab.getX() + py * ab.getY() + pz * ab.getZ()) / abLen2));
                double dx = px - ab.getX() * t;
                double dy = py - ab.getY() * t;
                double dz = pz - ab.getZ() * t;
                if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                    v[4] = Math.max(v[4], c.age + hold);
                }
            }
        }
    }

    private final Map<UUID, Vector> projPos = new HashMap<>();

    /** Every projectile (arrows, revolver bullets, tridents, snowballs, eggs, wind charges, fireballs...) cuts a hole as it flies. */
    private void scanProjectiles() {
        Set<UUID> seen = new HashSet<>();
        Set<World> worlds = new HashSet<>();
        for (Cloud c : clouds) {
            worlds.add(c.w);
        }
        double hole = num("projectile-hole-radius", 1.1);
        double holeSeconds = num("hole-seconds", 3.0);
        for (World w : worlds) {
            for (org.bukkit.entity.Projectile pr : w.getEntitiesByClass(org.bukkit.entity.Projectile.class)) {
                UUID id = pr.getUniqueId();
                seen.add(id);
                Vector cur = pr.getLocation().toVector();
                Vector prev = projPos.put(id, cur);
                if (prev == null) {
                    prev = cur;
                } else if (prev.distanceSquared(cur) < 1.0E-4) {
                    continue; // stuck in a block: no hole
                }
                String n = pr.getType().name();
                double r = n.contains("FIREBALL") || n.contains("WIND_CHARGE") || n.contains("WITHER_SKULL") ? 2.6 : hole;
                disturb(w, prev, cur, r, holeSeconds);
            }
        }
        projPos.keySet().retainAll(seen);
    }

    /** Explosions (TNT, creepers, end crystals, beds...) clear a big part of the cloud for a few seconds. */
    final class BlastListener implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
            blast(e.getLocation());
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
            blast(e.getBlock().getLocation().add(0.5, 0.5, 0.5));
        }

        private void blast(Location l) {
            disturb(l.getWorld(), l.toVector(), l.toVector(), num("explosion-hole-radius", 5.0), num("explosion-hole-seconds", 6.0));
        }
    }

    @Override
    void shutdown() {
        for (Grenade g : grenades) {
            if (g.item != null && g.item.isValid()) {
                g.item.remove();
            }
        }
        grenades.clear();
        clouds.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ------------------------------------------------------------------ state

    private static final class Grenade {
        final Player thrower;
        final World w;
        Vector pos;
        Vector vel;
        Item item;
        int age;
        int rest;

        Grenade(Player thrower, World w, Vector pos, Vector vel) {
            this.thrower = thrower;
            this.w = w;
            this.pos = pos.clone();
            this.vel = vel.clone();
        }
    }

    private static final class Cloud {
        final World w;
        final Vector center;
        final List<int[]> voxels = new ArrayList<>(); // x, y, z, distance through the air from the pop (ascending), cloud age until which a hole keeps it clear
        int maxLevel;
        int age;
        int max;
        int bloom;

        Cloud(World w, Vector center) {
            this.w = w;
            this.center = center;
        }
    }
}
