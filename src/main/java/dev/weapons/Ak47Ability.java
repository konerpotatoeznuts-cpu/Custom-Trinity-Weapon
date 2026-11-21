package dev.weapons;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AK-47, after the Counter-Strike 2 one (Source units: 40 units = 1 block):
 * 36 damage, 600 rounds per minute full auto (hold right-click), 30 round magazine, 2.5 s reload, damage falls off 2% per
 * 500 units, hitgroups (head x4, chest x1, stomach x1.25, legs x0.75), the first shot is dead accurate, then the
 * well known AK spray pattern climbs and sways, moving and jumping ruin accuracy, and holding it slows you down.
 * Bullets are instant (hitscan) and can go through thin things (glass, leaves, wood...) for less damage.
 * CS2 has 100 hp and Minecraft has 20, so damage is scaled (damage-scale in config.yml).
 */
final class Ak47Ability extends GunAbility {

    // Degrees the view is kicked after shot number i (0-based), up (pitch) and sideways (yaw, negative = left).
    private static final double[] KICK_UP = {0.0, 0.75, 1.05, 1.25, 1.35, 1.35, 1.1, 0.95, 0.7, 0.5, 0.4, 0.3, 0.25, 0.2, 0.15,
            0.12, 0.1, 0.1, 0.1, 0.08, 0.08, 0.06, 0.06, 0.05, 0.05, 0.05, 0.05, 0.05, 0.05, 0.05};
    private static final double[] KICK_YAW = {0.0, 0.0, 0.05, 0.1, 0.15, 0.25, -0.4, -0.55, -0.6, -0.55, -0.5, -0.35, 0.1, 0.5,
            0.65, 0.7, 0.6, 0.45, 0.2, -0.3, -0.55, -0.65, -0.6, -0.5, -0.1, 0.3, 0.5, 0.55, 0.5, 0.4};

    private static final class St {
        int idx;               // shot number in the current spray
        long lastShotMs;
        int lastShotTick = -100;
        int lastClickTick = -100;
        int clicks;
        BukkitTask task;
        Vector lastPos;
        double speed;          // blocks per tick
    }

    private final Map<UUID, St> states = new HashMap<>();
    private final NamespacedKeyHolder keys = new NamespacedKeyHolder();

    private final class NamespacedKeyHolder {
        final org.bukkit.NamespacedKey slow = new org.bukkit.NamespacedKey(plugin, "ak47_slow");
    }

    Ak47Ability(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.AK47;
    }

    @Override
    protected int defaultMagazine() {
        return 30;
    }

    @Override
    protected double defaultReloadSeconds() {
        return 2.5;
    }

    @Override
    protected String defaultAmmoItem() {
        return "ARROW";
    }

    @Override
    protected String ammoName() {
        return "arrows";
    }

    private St st(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new St());
    }

    // ------------------------------------------------------------------ own speed + slowdown while held

    @Override
    void tick(Player p, ItemStack held) {
        super.tick(p, held);
        St s = st(p);
        Vector pos = p.getLocation().toVector();
        if (s.lastPos != null && s.lastPos.distanceSquared(pos) < 9) {
            s.speed = Math.hypot(pos.getX() - s.lastPos.getX(), pos.getZ() - s.lastPos.getZ()) / 2.0;
        } else {
            s.speed = 0;
        }
        s.lastPos = pos;
        setSlow(p, held != null && plugin.items().identify(held) == type());
    }

    /** CS2: 215 units/s with the AK against 250 with the knife: 14% slower. */
    private void setSlow(Player p, boolean on) {
        AttributeInstance a = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (a == null) {
            return;
        }
        AttributeModifier have = null;
        for (AttributeModifier m : a.getModifiers()) {
            if (keys.slow.equals(m.getKey())) {
                have = m;
            }
        }
        if (on && have == null) {
            try {
                a.addTransientModifier(new AttributeModifier(keys.slow, -num("slow", 0.14), AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY));
            } catch (Throwable ignored) {
                // already there
            }
        } else if (!on && have != null) {
            a.removeModifier(have);
        }
    }

    @Override
    void onQuit(Player p) {
        setSlow(p, false);
        St s = states.remove(p.getUniqueId());
        if (s != null && s.task != null) {
            s.task.cancel();
        }
    }

    // ------------------------------------------------------------------ trigger (hold right-click = full auto)

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        e.setUseItemInHand(Event.Result.DENY); // never the vanilla crossbow
        if (!allowed(p) || isReloading(p)) {
            return;
        }
        if (ammo(item) <= 0) {
            startReload(p);
            return;
        }
        St s = st(p);
        int now = org.bukkit.Bukkit.getCurrentTick();
        // A held right-click arrives every 4 ticks. The second click in a row means the trigger is held: full auto.
        s.clicks = now - s.lastClickTick <= 6 ? s.clicks + 1 : 1;
        s.lastClickTick = now;
        if (now - s.lastShotTick >= 2) {
            shoot(p, s, now);
        }
        if (s.clicks >= 2 && (s.task == null || s.task.isCancelled())) {
            s.task = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> auto(p, s), 1L, 1L);
        }
    }

    private void auto(Player p, St s) {
        int now = org.bukkit.Bukkit.getCurrentTick();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!p.isOnline() || p.isDead() || now - s.lastClickTick > 5 || plugin.items().identify(hand) != type()
                || isReloading(p) || ammo(hand) <= 0 || !plugin.protection().canUse(p)) {
            if (s.task != null) {
                s.task.cancel();
            }
            s.task = null;
            if (p.isOnline() && plugin.items().identify(hand) == type() && ammo(hand) <= 0) {
                status(p, 0);
                Fx.sound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.8f, 1.5f);
            }
            return;
        }
        if (now - s.lastShotTick >= 2) {
            shoot(p, s, now);
        }
    }

    // ------------------------------------------------------------------ the shot

    private void shoot(Player p, St s, int nowTick) {
        ItemStack item = p.getInventory().getItemInMainHand();
        int ammo = ammo(item) - 1;
        setAmmo(item, ammo);
        p.getInventory().setItemInMainHand(item);
        s.lastShotTick = nowTick;
        Animations.trigger(plugin, p, type(), "fire");

        long now = System.currentTimeMillis();
        if (now - s.lastShotMs > num("spray-reset-ms", 400)) {
            s.idx = 0;
        }
        s.lastShotMs = now;
        int shot = s.idx++;

        Location eye = p.getEyeLocation();
        Vector dir = spread(eye, inaccuracy(p, s, shot));
        World w = p.getWorld();

        // ---- trace (hitscan, through thin materials for less damage)
        double range = num("range", 200);
        double base = num("damage", 36.0) * num("damage-scale", 0.1) * Combat.strength(p);
        Vector pos = eye.toVector();
        double travelled = 0;
        double mult = 1.0;
        int pens = 0;
        Vector end = pos.clone().add(dir.clone().multiply(range));
        Location start = eye.clone();
        for (int seg = 0; seg < 4; seg++) {
            double left = range - travelled;
            if (left <= 0.1) {
                break;
            }
            RayTraceResult r = w.rayTrace(new Location(w, pos.getX(), pos.getY(), pos.getZ()), dir, left,
                    FluidCollisionMode.NEVER, true, 0.1, en -> en instanceof LivingEntity && Combat.valid(plugin, p, en));
            if (r == null || r.getHitPosition() == null) {
                end = pos.clone().add(dir.clone().multiply(left));
                break;
            }
            Vector hit = r.getHitPosition();
            double d = hit.distance(pos);
            if (r.getHitEntity() instanceof LivingEntity le) {
                end = hit;
                double total = travelled + d;
                hurt(p, le, hit, total, base * mult);
                break;
            }
            Block b = r.getHitBlock();
            end = hit;
            if (b == null) {
                break;
            }
            impact(b, hit, r);
            travelled += d;
            if (pens >= (int) num("max-penetrations", 2) || !thin(b) || !plugin.getConfig().getBoolean("weapons.ak47.wallbang", true)) {
                break;
            }
            // find where the bullet comes out the other side (at most 1.2 blocks of material)
            Vector exit = null;
            for (double t = 0.05; t <= 1.2; t += 0.05) {
                Vector q = hit.clone().add(dir.clone().multiply(t));
                Block bb = w.getBlockAt(q.getBlockX(), q.getBlockY(), q.getBlockZ());
                if (bb.isPassable() || !thin(bb)) {
                    exit = q;
                    break;
                }
            }
            if (exit == null) {
                break;
            }
            Block exitBlock = w.getBlockAt(exit.getBlockX(), exit.getBlockY(), exit.getBlockZ());
            if (!exitBlock.isPassable()) {
                break; // thin stuff backed by a wall
            }
            travelled += exit.distance(hit);
            pos = exit;
            mult *= num("penetration-damage", 0.6);
            pens++;
        }

        // ---- effects
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        }
        right.normalize();
        Location muzzle = eye.clone().add(dir.clone().multiply(1.1)).add(right.clone().multiply(0.2)).add(0, -0.18, 0);
        double tracer = Math.min(end.distance(start.toVector()), num("tracer-length", 40));
        for (double d = 1.5; d < tracer; d += 1.6) {
            Fx.dust(muzzle.clone().add(dir.clone().multiply(d)), Color.fromRGB(255, 225, 140), 0.7f, 1, 0);
        }
        Fx.disturbSmoke(start, new Location(w, end.getX(), end.getY(), end.getZ()), 0.7, 2.5);
        Fx.burst(muzzle, Particle.FLAME, 3, 0.04, 0.03);
        Fx.burst(muzzle, Particle.SMOKE, 2, 0.05, 0.01);
        Fx.dust(muzzle, Color.fromRGB(255, 200, 90), 1.2f, 2, 0.05);
        Fx.sound(eye, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.3f, 1.7f);
        Fx.sound(eye, Sound.ENTITY_GENERIC_EXPLODE, 0.25f, 2.0f);
        if (shot % 2 == 0) {
            Fx.sound(eye, Sound.BLOCK_PISTON_CONTRACT, 0.3f, 2.0f);
        }

        // ---- the spray pattern kicks the view
        int k = Math.min(shot, KICK_UP.length - 1);
        double up = KICK_UP[k] * num("recoil", 1.0);
        double yaw = KICK_YAW[k] * num("recoil", 1.0);
        if (up != 0 || yaw != 0) {
            Location l = p.getLocation();
            p.setRotation((float) (l.getYaw() + yaw), (float) Math.max(-90.0, l.getPitch() - up));
        }
        status(p, ammo);
    }

    /** Degrees of random cone, from CS2: none standing still on the first shots, worse moving, jumping and in long sprays. */
    private double inaccuracy(Player p, St s, int shot) {
        double deg = Math.min(1.0, 0.04 * shot);
        double sp = s.speed;
        if (sp > 0.03) {
            deg += Math.min(7.0, sp * 22.0);
        }
        if (!p.isOnGround()) {
            deg += 5.0;
        }
        if (p.isSneaking()) {
            deg *= 0.7;
        }
        return deg * num("inaccuracy", 1.0);
    }

    private Vector spread(Location eye, double deg) {
        Vector dir = eye.getDirection().normalize();
        if (deg < 0.01) {
            return dir;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ang = r.nextDouble() * Math.PI * 2;
        double rad = Math.sqrt(r.nextDouble()) * Math.tan(Math.toRadians(deg));
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        }
        right.normalize();
        Vector up = right.clone().crossProduct(dir).normalize();
        return dir.add(right.multiply(Math.cos(ang) * rad)).add(up.multiply(Math.sin(ang) * rad)).normalize();
    }

    private void hurt(Player p, LivingEntity le, Vector hit, double blocks, double damage) {
        BoundingBox bb = le.getBoundingBox();
        double frac = bb.getHeight() <= 0 ? 0.5 : (hit.getY() - bb.getMinY()) / bb.getHeight();
        double zone;
        boolean head = false;
        if (frac >= 0.78) {
            zone = num("headshot-multiplier", 4.0);
            head = true;
        } else if (frac >= 0.55) {
            zone = 1.0;
        } else if (frac >= 0.40) {
            zone = 1.25;
        } else {
            zone = 0.75;
        }
        double units = blocks * 40.0;
        double falloff = Math.pow(num("range-modifier", 0.98), units / 500.0);
        double finalDamage = damage * zone * falloff;

        Vector keep = le.getVelocity();
        // Combat.damage multiplies by the global ability buff: divide it out so damage-scale means what it says
        Deaths.tagNext(head ? "headshot" : null);
        Combat.damage(le, finalDamage / Math.max(0.01, Combat.globalMult()), p);
        le.setVelocity(keep); // bullets do not knock people back

        Location at = new Location(le.getWorld(), hit.getX(), hit.getY(), hit.getZ());
        Fx.dust(at, Color.fromRGB(200, 20, 20), 1.0f, head ? 12 : 6, 0.12);
        p.playSound(p.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1.0f, head ? 1.9f : 1.1f);
        if (head) {
            Fx.burst(at, Particle.CRIT, 10, 0.2, 0.2);
            Fx.sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.4f);
        }
    }

    private void impact(Block b, Vector hit, RayTraceResult r) {
        Location at = new Location(b.getWorld(), hit.getX(), hit.getY(), hit.getZ());
        Fx.particle(at, Particle.BLOCK, 8, 0.1, 0.1, 0.1, 0.05, b.getBlockData(), false);
        Fx.sound(at, b.getBlockData().getSoundGroup().getHitSound(), 0.8f, 1.2f);
        Fx.burst(at, Particle.SMOKE, 2, 0.04, 0.01);
    }

    /** Things a bullet can go through. */
    private boolean thin(Block b) {
        Material m = b.getType();
        if (m.isAir()) {
            return true;
        }
        String n = m.name();
        return n.contains("GLASS") || n.contains("LEAVES") || n.contains("WOOL") || n.contains("PLANKS") || n.endsWith("_DOOR")
                || n.contains("FENCE") || n.contains("TRAPDOOR") || n.contains("BAMBOO") || n.contains("SCAFFOLDING")
                || n.contains("HAY_BLOCK") || n.contains("CARPET") || n.contains("BANNER") || n.contains("SIGN")
                || n.contains("SNOW") || n.contains("SPONGE") || n.contains("BOOKSHELF") || n.contains("BARREL")
                || n.contains("CHEST") || n.contains("CAKE") || n.contains("MELON") || n.contains("PUMPKIN");
    }
}
