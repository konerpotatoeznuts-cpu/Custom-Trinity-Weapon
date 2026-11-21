package dev.weapons;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Cryo Tempest, a bow (ammo: arrows).
 * <ul>
 *   <li>Every arrow is a frost arrow: icy trail, and it slows and freezes whoever it hits.</li>
 *   <li><b>Arrow Rain</b> (shoot while sneaking, e.g. upward): the arrow flies on; let go of sneak and a storm of frost arrows
 *       rains on the spot where the arrow is, damaging and slowing everything in it.</li>
 *   <li><b>Pilot</b> (shoot normally, then sneak twice while the arrow flies): the arrow becomes a frost bolt that flies
 *       wherever you look. The harder you drew the bow, the faster, stronger and bigger its hitbox is, and the quicker it turns, and
 *       it explodes in a frost blast on the first thing it hits.</li>
 * </ul>
 */
final class CryoTempestAbility extends Ability {

    private static final Color ICE = Color.fromRGB(150, 220, 255);
    private static final Color ICE_DEEP = Color.fromRGB(60, 140, 255);

    private static final class Flight {
        final Arrow arrow;
        final double force;
        boolean piloted;

        Flight(Arrow arrow, double force) {
            this.arrow = arrow;
            this.force = force;
        }
    }

    private final NamespacedKey frostKey;
    private final NamespacedKey rainKey;
    private final Map<UUID, Flight> flights = new HashMap<>();
    private final Map<UUID, Long> lastSneak = new HashMap<>();
    private final Random rnd = new Random();

    CryoTempestAbility(WeaponsPlugin plugin) {
        super(plugin);
        this.frostKey = new NamespacedKey(plugin, "cryo_arrow");
        this.rainKey = new NamespacedKey(plugin, "cryo_rain_arrow");
        plugin.getServer().getPluginManager().registerEvents(new Events(), plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.CRYO_TEMPEST;
    }

    @Override
    void onQuit(Player p) {
        flights.remove(p.getUniqueId());
        lastSneak.remove(p.getUniqueId());
    }

    /** The ground below a point (falls back to 30 blocks down when nothing is there). */
    private Location groundBelow(Location from) {
        World w = from.getWorld();
        RayTraceResult down = w.rayTraceBlocks(from, new Vector(0, -1, 0), 120, FluidCollisionMode.NEVER, true);
        double y = down != null && down.getHitPosition() != null ? down.getHitPosition().getY() : from.getY() - 30;
        return new Location(w, from.getX(), y, from.getZ());
    }

    // ------------------------------------------------------------------ events

    private final class Events implements Listener {

        @EventHandler
        public void onShoot(EntityShootBowEvent e) {
            if (!(e.getEntity() instanceof Player p) || plugin.items().identify(e.getBow()) != type()
                    || !(e.getProjectile() instanceof Arrow arrow) || !plugin.protection().canUse(p)) {
                return;
            }
            double force = e.getForce();
            arrow.getPersistentDataContainer().set(frostKey, PersistentDataType.BYTE, (byte) 1);

            // Skill 1: shoot while sneaking; let go of sneak and arrows rain where the arrow is
            if (p.isSneaking() && force >= num("min-force", 0.5)
                    && plugin.cooldowns().check(p, type(), "rain", "Arrow Rain")) {
                plugin.cooldowns().start(p, type(), "rain", num("rain-cooldown", 25));
                startRain(p, arrow);
                return;
            }
            // normal frost arrow, which can be piloted with a double sneak
            flights.put(p.getUniqueId(), new Flight(arrow, force));
            frostTrail(arrow, 100);
        }

        @EventHandler
        public void onHit(ProjectileHitEvent e) {
            if (!(e.getEntity() instanceof AbstractArrow arrow)) {
                return;
            }
            if (arrow.getPersistentDataContainer().has(rainKey, PersistentDataType.BYTE)) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (arrow.isValid()) {
                        arrow.remove();
                    }
                });
                return;
            }
            if (arrow.getPersistentDataContainer().has(frostKey, PersistentDataType.BYTE)
                    && e.getHitEntity() instanceof LivingEntity le) {
                freeze(le, 1.0);
                Fx.burst(le.getLocation().add(0, 1, 0), Particle.SNOWFLAKE, 14, 0.4, 0.05);
                Fx.sound(le.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.6f, 1.6f);
            }
        }

        @EventHandler
        public void onSneak(PlayerToggleSneakEvent e) {
            if (!e.isSneaking()) {
                return;
            }
            Player p = e.getPlayer();
            long now = System.currentTimeMillis();
            Long last = lastSneak.put(p.getUniqueId(), now);
            if (last == null || now - last > num("double-sneak-ms", 400)) {
                return;
            }
            lastSneak.remove(p.getUniqueId());
            Flight f = flights.get(p.getUniqueId());
            if (f == null || f.piloted || !f.arrow.isValid() || f.arrow.isInBlock() || f.arrow.isOnGround()) {
                return;
            }
            if (!plugin.protection().canUse(p) || !plugin.cooldowns().check(p, type(), "pilot", "Pilot")) {
                return;
            }
            plugin.cooldowns().start(p, type(), "pilot", num("pilot-cooldown", 12));
            pilot(p, f);
        }
    }

    // ------------------------------------------------------------------ frost

    private void freeze(LivingEntity le, double scale) {
        int ticks = (int) (num("slow-seconds", 3.0) * 20 * scale);
        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, (int) num("slow-level", 2) - 1));
        le.setFreezeTicks(Math.min(le.getMaxFreezeTicks() + 60, le.getFreezeTicks() + (int) (num("freeze-ticks", 140) * scale)));
    }

    private void frostTrail(AbstractArrow arrow, int maxTicks) {
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!arrow.isValid() || arrow.isInBlock() || arrow.isOnGround() || t++ > maxTicks) {
                    cancel();
                    return;
                }
                Location l = arrow.getLocation();
                Fx.dust(l, ICE, 1.0f, 2, 0.05);
                Fx.burst(l, Particle.SNOWFLAKE, 1, 0.05, 0.0);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ------------------------------------------------------------------ skill 1: arrow rain

    /** The arrow flies (no gravity) while the player keeps sneaking; letting go of sneak rains arrows where it is. */
    private void startRain(Player p, Arrow arrow) {
        final Vector dir = arrow.getVelocity().lengthSquared() < 1.0E-4 ? p.getEyeLocation().getDirection() : arrow.getVelocity().normalize();
        final double speed = num("rain-launch-speed", 1.6);
        arrow.setGravity(false);
        arrow.setDamage(0);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setVelocity(dir.clone().multiply(speed));
        Fx.sound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.3f, 0.5f);
        Fx.sound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.8f, 0.7f);

        final int max = (int) (num("rain-max-seconds", 4.0) * 20);
        final double radius = num("rain-radius", 5.5);
        new BukkitRunnable() {
            int t = 0;
            Location last = arrow.getLocation();

            @Override
            public void run() {
                t++;
                if (!p.isOnline()) {
                    arrow.remove();
                    cancel();
                    return;
                }
                boolean gone = !arrow.isValid() || arrow.isInBlock() || arrow.isOnGround();
                if (!gone) {
                    last = arrow.getLocation();
                    arrow.setVelocity(dir.clone().multiply(speed));
                    Fx.dust(last, ICE, 1.2f, 3, 0.1);
                    Fx.burst(last, Particle.SNOWFLAKE, 2, 0.1, 0.0);
                    if (t % 2 == 0) {
                        // shows where the rain would land
                        Location g = groundBelow(last);
                        Fx.ring(g.clone().add(0, 0.15, 0), radius, 26, rnd.nextDouble(), l -> Fx.dust(l, ICE, 1.1f, 1, 0));
                    }
                }
                if (gone || t > max || (t >= 4 && !p.isSneaking())) {
                    cancel();
                    if (arrow.isValid()) {
                        arrow.remove();
                    }
                    Fx.burst(last, Particle.SNOWFLAKE, 40, 1.2, 0.2);
                    Fx.burst(last, Particle.END_ROD, 12, 0.6, 0.1);
                    Fx.sound(last, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.5f, 0.8f);
                    rain(p, last);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** The storm itself, centred under the point where the arrow was. */
    private void rain(Player p, Location at) {
        final Location target = groundBelow(at);
        final double height = Math.max(8, Math.min(30, at.getY() - target.getY()));
        final int duration = (int) (num("rain-seconds", 3.5) * 20);
        final double radius = num("rain-radius", 5.5);
        final double dmg = num("rain-damage", 1.6);
        final World w = target.getWorld();
        Fx.sound(target, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.5f, 0.6f);
        new BukkitRunnable() {
            int k = 0;

            @Override
            public void run() {
                k++;
                if (k > duration || !p.isOnline()) {
                    cancel();
                    return;
                }
                if (k % 4 == 1) {
                    Fx.ring(target.clone().add(0, 0.2, 0), radius, 36, rnd.nextDouble(), l -> Fx.dust(l, ICE, 1.1f, 1, 0));
                }
                if (k % 2 == 0) {
                    int n = (int) num("rain-arrows-per-burst", 5);
                    for (int i = 0; i < n; i++) {
                        double a = rnd.nextDouble() * Math.PI * 2;
                        double d = Math.sqrt(rnd.nextDouble()) * radius;
                        spawnRainArrow(w, target.clone().add(Math.cos(a) * d, height, Math.sin(a) * d));
                    }
                    Fx.burst(target.clone().add(0, 1.5, 0), Particle.SNOWFLAKE, 6, radius * 0.5, 0.01);
                }
                if (k % 5 == 0) {
                    Fx.sound(target, Sound.ENTITY_ARROW_HIT, 0.5f, 1.5f);
                }
                if (k % 10 == 0) {
                    Location mid = target.clone().add(0, 1, 0);
                    for (LivingEntity le : w.getNearbyLivingEntities(mid, radius, 4, radius)) {
                        if (Combat.valid(plugin, p, le) && flat(le.getLocation(), target) <= radius) {
                            Combat.damage(le, dmg * Combat.strength(p), p);
                            freeze(le, 0.8);
                            Fx.burst(le.getLocation().add(0, 1, 0), Particle.SNOWFLAKE, 8, 0.3, 0.05);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static double flat(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private void spawnRainArrow(World w, Location from) {
        try {
            w.spawn(from, Arrow.class, a -> {
                a.setVelocity(new Vector((rnd.nextDouble() - 0.5) * 0.1, -num("rain-fall-speed", 1.8), (rnd.nextDouble() - 0.5) * 0.1));
                a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                a.setDamage(0);
                a.setSilent(true);
                a.setPersistent(false);
                a.setCritical(false);
                a.getPersistentDataContainer().set(rainKey, PersistentDataType.BYTE, (byte) 1);
                // safety: never lingers
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (a.isValid()) {
                        a.remove();
                    }
                }, 60L);
            });
        } catch (Throwable t) {
            // chunk not loaded etc.
        }
    }

    // ------------------------------------------------------------------ skill 2: pilot the arrow

    private void pilot(Player p, Flight f) {
        f.piloted = true;
        final Arrow arrow = f.arrow;
        final double force = Math.max(0.15, Math.min(1.0, f.force));
        final double speed = num("pilot-speed-min", 1.1) + (num("pilot-speed-max", 2.8) - num("pilot-speed-min", 1.1)) * force;
        final double turn = num("pilot-turn-min", 0.10) + (num("pilot-turn-max", 0.45) - num("pilot-turn-min", 0.10)) * force;
        final double radius = num("pilot-hitbox-min", 0.8) + (num("pilot-hitbox-max", 2.0) - num("pilot-hitbox-min", 0.8)) * force;
        final double dmg = num("pilot-damage-min", 4.0) + (num("pilot-damage-max", 10.0) - num("pilot-damage-min", 4.0)) * force;
        final double blast = num("pilot-blast-min", 2.5) + (num("pilot-blast-max", 5.0) - num("pilot-blast-min", 2.5)) * force;
        final int maxTicks = (int) (num("pilot-seconds", 6.0) * 20);

        arrow.setGravity(false);
        arrow.setDamage(0);
                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        Fx.sound(arrow.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.8f);
        Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 1.0f, 1.4f);
        Fx.burst(arrow.getLocation(), Particle.SNOWFLAKE, 20, 0.4, 0.1);

        final Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            int t = 0;
            int hits = 0;
            boolean exploded = false;
            Vector prev = arrow.getLocation().toVector();

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || !arrow.isValid() || arrow.isInBlock() || arrow.isOnGround() || t++ > maxTicks) {
                    finish();
                    return;
                }
                // steer toward where the player looks
                Vector cur = arrow.getVelocity();
                if (cur.lengthSquared() < 1.0E-4) {
                    cur = p.getEyeLocation().getDirection();
                }
                Vector look = p.getEyeLocation().getDirection().normalize();
                Vector dir = cur.normalize().multiply(1.0 - turn).add(look.multiply(turn)).normalize();
                arrow.setVelocity(dir.multiply(speed));

                World w = arrow.getWorld();
                Vector now = arrow.getLocation().toVector();
                // the (big) hitbox: sampled along the path so a fast arrow never skips a target
                double dist = prev.distance(now);
                int steps = Math.max(1, (int) Math.ceil(dist / 0.8));
                for (int i = 1; i <= steps; i++) {
                    Vector pt = prev.clone().add(now.clone().subtract(prev).multiply(i / (double) steps));
                    Location at = new Location(w, pt.getX(), pt.getY(), pt.getZ());
                    for (LivingEntity le : w.getNearbyLivingEntities(at, radius)) {
                        if (!Combat.valid(plugin, p, le)) {
                            continue;
                        }
                        // direct hit: the bolt detonates in a frost blast
                        Location b = le.getLocation().add(0, le.getHeight() / 2, 0);
                        for (LivingEntity v : w.getNearbyLivingEntities(b, blast)) {
                            if (Combat.valid(plugin, p, v)) {
                                hit.add(v.getUniqueId());
                                hits++;
                                Combat.damage(v, dmg * Combat.strength(p), p);
                                freeze(v, 1.2);
                                Fx.burst(v.getLocation().add(0, v.getHeight() / 2, 0), Particle.SNOWFLAKE, 16, 0.4, 0.1);
                            }
                        }
                        Fx.burst(b, Particle.SNOWFLAKE, 60, blast * 0.5, 0.25);
                        Fx.burst(b, Particle.END_ROD, 16, blast * 0.3, 0.2);
                        Fx.dustFade(b, ICE, Color.WHITE, 2.5f, 30, blast * 0.4);
                        Fx.shockwave(b, ICE, blast, 8);
                        Fx.sound(b, Sound.BLOCK_GLASS_BREAK, 1.5f, 0.7f);
                        Fx.sound(b, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.5f, 0.8f);
                        Fx.sound(b, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
                        p.playSound(p.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, 1.5f);
                        exploded = true;
                        finish();
                        return;
                    }
                }
                prev = now;
                // trail, as thick as the hitbox
                Location l = arrow.getLocation();
                Fx.dust(l, ICE, (float) Math.min(4.0, 1.0 + radius), 3, radius * 0.25);
                Fx.dustFade(l, ICE_DEEP, Color.WHITE, 1.2f, 2, radius * 0.3);
                Fx.burst(l, Particle.SNOWFLAKE, 2, radius * 0.2, 0.0);
                if (t % 3 == 0) {
                    p.sendActionBar(net.kyori.adventure.text.Component.text("Piloting",
                            net.kyori.adventure.text.format.NamedTextColor.AQUA));
                }
            }

            private void finish() {
                cancel();
                flights.remove(p.getUniqueId(), f);
                if (exploded) {
                    arrow.remove();
                } else if (arrow.isValid()) {
                    Location l = arrow.getLocation();
                    Fx.burst(l, Particle.SNOWFLAKE, 40, 0.8, 0.2);
                    Fx.burst(l, Particle.END_ROD, 10, 0.4, 0.15);
                    Fx.shockwave(l, ICE, 2.5, 6);
                    Fx.sound(l, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.8f);
                    // a small frost burst where it ends
                    for (LivingEntity le : w(l).getNearbyLivingEntities(l, 2.5)) {
                        if (!hit.contains(le.getUniqueId()) && Combat.valid(plugin, p, le)) {
                            Combat.damage(le, dmg * 0.4 * Combat.strength(p), p);
                            freeze(le, 1.0);
                        }
                    }
                    arrow.remove();
                }
            }

            private World w(Location l) {
                return l.getWorld();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    @Override
    void shutdown() {
        flights.clear();
    }
}
