package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Sonic's Shoes (netherite boots), modelled on the Mega Drive / Genesis games.
 *
 *  - Running builds momentum (acceleration), and momentum carries on when you let go (slow deceleration).
 *  - Slopes: faster downhill, slower uphill.  Automatic step-up like a horse.
 *  - Fast enough: run on water, up walls and across ceilings; lose speed and you fall.
 *  - Sneak while running fast = roll (the spin ball). Jump = spin ball too: land on a mob to hurt it and bounce.
 *  - Spin Dash: sneak + right-click to rev (max 5), stop sneaking to launch.
 *
 * Players move on their own client, so this works by nudging velocity and attributes every tick.
 */
final class SonicShoesAbility extends Ability {

    private enum Mode {RUN, CHARGE, ROLL, AIR, WALL, CEIL}

    private static final Color BLUE = Color.fromRGB(40, 110, 255);
    private static final Color WHITE = Color.fromRGB(235, 245, 255);

    private static final class S {
        Mode mode = Mode.RUN;
        Vector last;
        boolean lastGround = true;
        double mom;                  // momentum in blocks per tick
        Vector dir = new Vector(0, 0, 1);
        int charge;
        int offGround;               // ticks off the ground while charging (a little is forgiven)
        int unsneak;                 // consecutive ticks not sneaking while charging
        long lastClick;
        long lastChargeLoss;
        double spin;
        int modeTicks;
        int wallTotal;               // ticks spent off the ground on walls/ceilings since last on ground
        boolean wasRoll;
        double snap;                 // downward pull this tick so slopes never launch you
        boolean ballAir;             // the current airborne spin is a ball (jump / roll off a ledge)
        SpinBall ball;
        boolean hidSelf;
        int noBall;                  // ticks since the ball form last applied (the ball stays a few ticks so it never flickers)
        Vector lastDir = new Vector(0, 0, 1);
        int ticks;
        Vector wallDir = new Vector(0, 0, 1);
        double appliedFactor = -1;
        AttributeModifier speedMod;
        AttributeModifier stepMod;
        final ArrayDeque<Double> yHist = new ArrayDeque<>();
        final Map<UUID, Integer> hitTick = new HashMap<>();
        final Map<Long, int[]> fake = new HashMap<>();
    }

    private final Map<UUID, S> states = new HashMap<>();
    private final NamespacedKey speedKey;
    private final NamespacedKey stepKey;

    SonicShoesAbility(WeaponsPlugin plugin) {
        super(plugin);
        this.speedKey = new NamespacedKey(plugin, "sonic_speed");
        this.stepKey = new NamespacedKey(plugin, "sonic_step");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    @Override
    WeaponType type() {
        return WeaponType.SONIC_SHOES;
    }

    private boolean wearing(Player p) {
        ItemStack boots = p.getInventory().getBoots();
        return boots != null && plugin.items().identify(boots) == WeaponType.SONIC_SHOES;
    }

    // ------------------------------------------------------------------ main loop

    private void tickAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            S s = states.get(id);
            if (!wearing(p)) {
                if (s != null) {
                    cleanup(p, s);
                    states.remove(id);
                }
                continue;
            }
            if (s == null) {
                s = new S();
                states.put(id, s);
                AttributeInstance step = p.getAttribute(Attribute.STEP_HEIGHT);
                if (step != null) {
                    s.stepMod = new AttributeModifier(stepKey, num("step-height", 1.0) - 0.6, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY);
                    try {
                        step.addTransientModifier(s.stepMod);
                    } catch (Throwable ignored) {
                        s.stepMod = null;
                    }
                }
            }
            try {
                step(p, s);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Sonic's Shoes error", t);
                reset(p, s);
            }
        }
    }

    private void step(Player p, S s) {
        if (!allowedHere(p)) {
            reset(p, s);
            return;
        }
        Location loc = p.getLocation();
        Vector pos = loc.toVector();
        boolean ground = p.isOnGround();
        if (s.last == null || p.isDead() || p.isGliding() || p.isFlying() || p.isInsideVehicle()
                || p.getGameMode() == GameMode.SPECTATOR || s.last.distanceSquared(pos) > 36) {
            reset(p, s);
            s.last = pos;
            s.lastGround = ground;
            return;
        }
        Vector d = pos.clone().subtract(s.last);
        double hs = Math.hypot(d.getX(), d.getZ());
        double dy = d.getY();
        s.yHist.addLast(pos.getY());
        while (s.yHist.size() > 8) {
            s.yHist.removeFirst();
        }
        double drop = s.yHist.getFirst() - pos.getY();
        boolean jumped = s.lastGround && !ground && dy > 0.2;
        boolean left = s.lastGround && !ground; // jumped or walked off a ledge
        // Stick to the floor like the games: running or rolling down a slope never launches you off it
        s.snap = 0;
        if (left && (s.mode == Mode.RUN || s.mode == Mode.ROLL) && s.mom > 0.35 && dy < 0.12 && num("stick-to-floor", 1) > 0) {
            double gd = groundDist(loc, num("stick-max-drop", 1.7));
            if (gd >= 0) {
                ground = true;
                left = false;
                jumped = false;
                s.snap = Math.max(0.0784, gd);
            }
        }
        if (ground) {
            s.wallTotal = 0;
        }
        s.modeTicks++;

        switch (s.mode) {
            case RUN -> run(p, s, loc, d, hs, dy, drop, ground, jumped, left);
            case CHARGE -> charge(p, s, loc, ground);
            case ROLL -> roll(p, s, loc, d, hs, dy, drop, ground, left);
            case AIR -> air(p, s, loc, hs, dy, ground);
            case WALL -> wall(p, s, loc, hs);
            case CEIL -> ceiling(p, s, loc);
        }

        if (s.snap > 0 && s.mode == Mode.RUN) {
            p.setVelocity(new Vector(d.getX(), -s.snap, d.getZ())); // glue to the slope
        }
        if (s.mode != Mode.RUN && s.mode != Mode.CHARGE) {
            plugin.grantNoFall(p, 3.0); // Sonic doesn't take fall damage; this also covers falling off a wall
        }
        waterFloor(p, s, loc);
        visuals(p, s, loc, hs);

        s.last = pos;
        s.lastGround = ground;
    }

    /** Distance from the feet down to the floor, or -1 if it is further than max. */
    private double groundDist(Location loc, double max) {
        org.bukkit.util.RayTraceResult r = loc.getWorld().rayTraceBlocks(loc.clone().add(0, 0.2, 0), new Vector(0, -1, 0),
                max + 0.2, org.bukkit.FluidCollisionMode.NEVER, true);
        if (r == null || r.getHitPosition() == null) {
            return -1;
        }
        return Math.max(0, loc.getY() - r.getHitPosition().getY());
    }

    private boolean allowedHere(Player p) {
        return plugin.protection().canUse(p);
    }

    // ------------------------------------------------------------------ RUN

    private void run(Player p, S s, Location loc, Vector d, double hs, double dy, double drop, boolean ground, boolean jumped, boolean left) {
        double top = num("top-speed", 0.9);
        boolean sprint = p.isSprinting();

        if (ground && hs > 0.12 && (sprint || hs > s.mom)) {
            s.dir = new Vector(d.getX() / hs, 0, d.getZ() / hs);
        }
        if (ground && sprint && hs > 0.15) {
            s.mom = Math.max(s.mom, hs);
            if (s.mom < top) {
                s.mom = Math.min(top, s.mom + num("accel", 0.009));
            } else {
                s.mom = Math.max(top, s.mom * num("over-top-decay", 0.995));
            }
        } else if (ground && s.mom > 0.3) {
            // let go of the keys: momentum carries you, slowly
            if (hs < s.mom * 0.6) {
                s.mom *= num("coast-decay", 0.78);
                p.setVelocity(new Vector(s.dir.getX() * s.mom, -0.0784, s.dir.getZ() * s.mom));
            } else {
                s.mom = Math.max(hs, s.mom * num("release-decay", 0.9));
            }
        } else if (ground && s.mom <= 0.3) {
            s.mom = 0;
        }

        // slopes
        if (ground && s.mom > 0) {
            if (drop > 0.35) {
                s.mom = Math.min(top * 1.4, s.mom + num("slope-gain", 0.02));
            } else if (drop < -0.35) {
                s.mom = Math.max(0, s.mom - num("slope-loss", 0.015));
            }
        }

        setSpeed(p, s, s.mom);

        if (s.mom > 0.55) {
            speedTrail(loc, s);
        }

        // roll: sneak while running fast
        if (ground && p.isSneaking() && s.mom >= num("roll-min-speed", 0.55) && hs >= num("roll-min-speed", 0.55) * 0.7) {
            startRoll(p, s, Math.max(s.mom, hs), false);
            return;
        }
        // spin ball when jumping
        if (jumped || (left && s.mom > 0.5)) {
            toAir(s, true);
            s.wasRoll = false;
            setSpeed(p, s, 0);
            Fx.sound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.6f);
            Fx.burst(loc.clone().add(0, 0.1, 0), Particle.CLOUD, 10, 0.3, 0.05);
            return;
        }
        // wall running
        if (s.mom >= num("wall-min-speed", 0.6) && ground && hs < s.mom * 0.5 && wallAhead(loc, s.dir)) {
            startWall(p, s, loc);
        }
    }

    // ------------------------------------------------------------------ spin dash (charge)

    /** Called from the interact listener: sneak + right-click while wearing the shoes. */
    boolean click(Player p, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return false;
        }
        S s = states.get(p.getUniqueId());
        if (s == null || !p.isSneaking() || !p.isOnGround()) {
            return false;
        }
        ItemStack hand = e.getItem();
        if (hand != null && plugin.items().identify(hand) != null) {
            return false; // a custom weapon in hand keeps its own sneak + right-click
        }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && hand != null && hand.getType().isBlock()) {
            return false; // let people bridge
        }
        if (s.mode != Mode.RUN && s.mode != Mode.CHARGE) {
            return false;
        }
        if (s.mode == Mode.RUN && s.mom > 0.3) {
            return false; // spin dash starts from standing still
        }
        if (!allowed(p)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - s.lastClick < 120) {
            e.setCancelled(true);
            return true;
        }
        s.lastClick = now;
        s.lastChargeLoss = now;
        int max = (int) num("max-clicks", 5);
        e.setCancelled(true);
        if (s.mode == Mode.RUN) {
            enter(s, Mode.CHARGE);
            s.charge = 0;
            s.offGround = 0;
            s.unsneak = 0;
        }
        if (s.charge < max) {
            s.charge++;
        }
        Location l = p.getLocation();
        Animations.trigger(plugin, p, type(), "spin_dash_charge");
        Fx.sound(l, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 0.6f + 0.2f * s.charge);
        Fx.sound(l, Sound.ENTITY_BREEZE_WIND_BURST, 0.5f, 0.8f + 0.15f * s.charge);
        Fx.burst(l.clone().add(0, 0.1, 0), Particle.CLOUD, 6 + 2 * s.charge, 0.4, 0.05);
        p.sendActionBar(Component.text("Spin Dash  " + "●".repeat(s.charge) + "○".repeat(Math.max(0, max - s.charge)), NamedTextColor.AQUA));
        return true;
    }

    private void charge(Player p, S s, Location loc, boolean ground) {
        long now = System.currentTimeMillis();
        if (ground) {
            s.offGround = 0;
        } else if (++s.offGround > 8) {
            toAir(s, false);
            return;
        }
        if (p.isSneaking()) {
            s.unsneak = 0;
        } else {
            s.unsneak++;
        }
        if (s.unsneak == 1) {
            return; // one tick of "not sneaking" can be a glitch; keep the revs
        }
        if (s.unsneak >= 2) {
            if (s.charge > 0) {
                double speed = num("spin-dash-base", 1.2) + num("spin-dash-per-click", 0.2) * (s.charge - 1);
                Vector look = loc.getDirection().setY(0);
                if (look.lengthSquared() < 1.0E-4) {
                    look = s.dir.clone();
                }
                s.dir = look.normalize();
                s.charge = 0;
                startRoll(p, s, speed, true);
                Animations.trigger(plugin, p, type(), "spin_dash_launch");
                Fx.burst(loc.clone().add(0, 0.5, 0), Particle.CLOUD, 25, 0.4, 0.3);
                Fx.sound(loc, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.2f, 1.4f);
                Fx.shockwave(loc.clone().add(0, 0.15, 0), plugin.settings().spin(p).at(System.currentTimeMillis()), 3.5, 8);
                Fx.burst(loc.clone().add(0, 0.9, 0), Particle.FLASH, 1, 0, 0);
            } else {
                enter(s, Mode.RUN);
            }
            return;
        }
        // revving: stay put and keep spinning
        p.setVelocity(new Vector(0, ground ? -0.0784 : Math.min(0, p.getVelocity().getY()), 0));
        if (now - s.lastChargeLoss > num("charge-decay-seconds", 3.5) * 1000) {
            s.lastChargeLoss = now;
            s.charge--;
            if (s.charge <= 0) {
                enter(s, Mode.RUN);
                return;
            }
        }
        s.spin += 1.4 + s.charge * 0.5;
        spinRing(loc, s, 0.6 + 0.05 * s.charge);
        if (s.modeTicks % 3 == 0) {
            Fx.burst(loc.clone().add(0, 0.1, 0), Particle.CLOUD, 2, 0.3, 0.02);
        }
    }

    // ------------------------------------------------------------------ ROLL (spin dash / sneak roll)

    private void startRoll(Player p, S s, double speed, boolean fromSpinDash) {
        enter(s, Mode.ROLL);
        s.mom = speed;
        s.wasRoll = true;
        setSpeed(p, s, 0);
        Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.7f);
    }

    private void roll(Player p, S s, Location loc, Vector d, double hs, double dy, double drop, boolean ground, boolean left) {
        // steering: a little, like the games
        Vector look = loc.getDirection().setY(0);
        if (look.lengthSquared() > 1.0E-4) {
            s.dir = s.dir.clone().multiply(0.94).add(look.normalize().multiply(0.06)).normalize();
        }
        double fr = num("roll-friction-mult", 0.9975);
        s.mom = s.mom * fr - num("roll-friction-const", 0.0012);
        if (ground) {
            if (drop > 0.35) {
                s.mom += num("roll-slope-gain", 0.06);
            } else if (drop < -0.35) {
                s.mom -= num("roll-slope-loss", 0.05);
            }
        }

        if (left) {
            toAir(s, true);
            s.wasRoll = true;
            return;
        }
        // hit a wall
        if (s.modeTicks > 3 && hs < s.mom * 0.3 && ground) {
            if (s.mom >= num("wall-min-speed", 0.6) && wallAhead(loc, s.dir)) {
                startWall(p, s, loc);
            } else {
                Fx.sound(loc, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.8f);
                Fx.burst(loc.clone().add(0, 0.8, 0), Particle.CRIT, 12, 0.3, 0.3);
                s.mom = 0;
                enter(s, Mode.RUN);
            }
            return;
        }
        if (s.mom < 0.16) {
            s.mom = 0.2;
            enter(s, Mode.RUN);
            return;
        }

        p.setVelocity(new Vector(s.dir.getX() * s.mom, ground ? -Math.max(0.0784, s.snap) : 0, s.dir.getZ() * s.mom));
        spinRing(loc, s, 0.7);
        speedTrail(loc, s);
        crash(p, s, loc, num("roll-damage", 5.0), 1.5);
    }

    // ------------------------------------------------------------------ AIR (spin ball)

    private void air(Player p, S s, Location loc, double hs, double dy, boolean ground) {
        if (ground && s.modeTicks > 2) {
            if ((s.wasRoll && s.mom >= 0.4) || (p.isSneaking() && s.mom >= num("roll-min-speed", 0.55))) {
                startRoll(p, s, s.mom, false);
            } else {
                enter(s, Mode.RUN);
            }
            return;
        }
        // keep the speed you jumped with (vanilla air drag would kill it), and steer a little toward where you look
        Vector look = loc.getDirection().setY(0);
        if (look.lengthSquared() > 1.0E-4 && s.mom > 0.3) {
            s.dir = s.dir.clone().multiply(0.93).add(look.normalize().multiply(0.07)).normalize();
        }
        s.mom *= 0.996;
        if (s.mom > 0.3) {
            double vy = (dy - 0.08) * 0.98;
            p.setVelocity(new Vector(s.dir.getX() * s.mom, vy, s.dir.getZ() * s.mom));
        }
        s.spin += 1.6;
        spinRing(loc, s, 0.7);

        // land on a mob: damage + bounce
        if (dy < -0.05) {
            for (LivingEntity le : Combat.nearby(plugin, p, loc.clone().add(0, -0.3, 0), 1.6)) {
                double top = le.getBoundingBox().getMaxY();
                if (top > loc.getY() + 0.6 || top < loc.getY() - 1.4) {
                    continue;
                }
                if (!canHit(s, le)) {
                    continue;
                }
                Combat.damage(le, num("jump-damage", 8.0) * (1.0 + s.mom * 0.5), p);
                Fx.burst(le.getLocation().add(0, le.getHeight(), 0), Particle.CRIT, 18, 0.3, 0.4);
                Fx.burst(le.getLocation().add(0, le.getHeight(), 0), Particle.POOF, 6, 0.2, 0.05);
                Fx.sound(le.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 1.2f);
                p.setVelocity(new Vector(s.dir.getX() * s.mom * 0.8, num("bounce", 0.7), s.dir.getZ() * s.mom * 0.8));
                s.modeTicks = 0;
                break;
            }
        }
        // wall running from the air
        if (s.mom >= num("wall-min-speed", 0.6) && hs < s.mom * 0.4 && s.modeTicks > 2 && wallAhead(loc, s.dir)) {
            startWall(p, s, loc);
        }
    }

    // ------------------------------------------------------------------ WALL / CEILING

    private boolean wallAhead(Location loc, Vector dir) {
        for (double h : new double[]{0.4, 1.4}) {
            Block b = loc.clone().add(dir.getX() * 0.65, h, dir.getZ() * 0.65).getBlock();
            if (b.isPassable() || b.isLiquid()) {
                return false;
            }
        }
        return true;
    }

    private void startWall(Player p, S s, Location loc) {
        enter(s, Mode.WALL);
        s.wallDir = Math.abs(s.dir.getX()) > Math.abs(s.dir.getZ())
                ? new Vector(Math.signum(s.dir.getX()), 0, 0) : new Vector(0, 0, Math.signum(s.dir.getZ()));
        setSpeed(p, s, 0);
        Fx.sound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.8f);
        p.sendActionBar(Component.text("Wall run!", NamedTextColor.AQUA));
    }

    private void wall(Player p, S s, Location loc, double hs) {
        s.wallTotal++;
        s.mom *= num("wall-decay", 0.978);
        if (p.isSneaking() || s.mom < num("wall-hold-min", 0.28) || (num("wall-max-ticks", 0) > 0 && s.wallTotal > num("wall-max-ticks", 0))) {
            detach(p, s);
            return;
        }
        Vector w = s.wallDir;
        boolean touching = true;
        for (double h : new double[]{0.4, 1.3}) {
            if (loc.clone().add(w.getX() * 0.65, h, w.getZ() * 0.65).getBlock().isPassable()) {
                touching = false;
            }
        }
        if (!touching) {
            // reached the top: hop over the edge
            p.setVelocity(new Vector(w.getX() * 0.35, 0.45, w.getZ() * 0.35));
            toAir(s, false);
            s.wasRoll = false;
            return;
        }
        if (!loc.clone().add(0, 2.1, 0).getBlock().isPassable()) {
            // a ceiling: keep going upside down
            enter(s, Mode.CEIL);
            Fx.sound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.2f);
            p.sendActionBar(Component.text("Upside down!", NamedTextColor.AQUA));
            return;
        }
        p.setVelocity(new Vector(w.getX() * 0.1, Math.min(1.0, s.mom), w.getZ() * 0.1));
        speedTrail(loc, s);
        Fx.burst(loc.clone().add(w.getX() * 0.5, 0.2, w.getZ() * 0.5), Particle.CRIT, 2, 0.2, 0.05);
    }

    private void ceiling(Player p, S s, Location loc) {
        s.wallTotal++;
        s.mom *= num("ceiling-decay", 0.99);
        if (p.isSneaking() || s.mom < num("wall-hold-min", 0.28) || (num("wall-max-ticks", 0) > 0 && s.wallTotal > num("wall-max-ticks", 0))
                || loc.clone().add(0, 2.1, 0).getBlock().isPassable()) {
            detach(p, s);
            return;
        }
        Vector look = loc.getDirection().setY(0);
        if (look.lengthSquared() > 1.0E-4) {
            s.dir = look.normalize();
        }
        p.setVelocity(new Vector(s.dir.getX() * s.mom, 0.12, s.dir.getZ() * s.mom));
        speedTrail(loc, s);
        Fx.burst(loc.clone().add(0, 2.0, 0), Particle.CRIT, 2, 0.2, 0.05);
    }

    private void detach(Player p, S s) {
        toAir(s, false);
        s.wasRoll = false;
        s.mom *= 0.5;
        Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.7f);
    }

    // ------------------------------------------------------------------ damage while rolling

    private boolean canHit(S s, LivingEntity le) {
        int now = Bukkit.getCurrentTick();
        Integer last = s.hitTick.get(le.getUniqueId());
        if (last != null && now - last < 8) {
            return false;
        }
        s.hitTick.put(le.getUniqueId(), now);
        if (s.hitTick.size() > 64) {
            s.hitTick.values().removeIf(t -> now - t > 40);
        }
        return true;
    }

    private void crash(Player p, S s, Location loc, double base, double radius) {
        for (LivingEntity le : Combat.nearby(plugin, p, loc.clone().add(0, 0.9, 0), radius)) {
            if (!canHit(s, le)) {
                continue;
            }
            Combat.damage(le, base * (0.6 + s.mom), p);
            le.setVelocity(s.dir.clone().multiply(0.8).setY(0.3));
            Fx.burst(le.getLocation().add(0, le.getHeight() / 2, 0), Particle.CRIT, 15, 0.3, 0.4);
            Fx.sound(le.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, 1.3f);
            s.mom *= 0.93;
        }
    }

    // ------------------------------------------------------------------ running on water

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
    }

    /** Fast enough = a (client-side only) glass floor appears under your feet over water, and is removed again behind you. */
    private void waterFloor(Player p, S s, Location loc) {
        double speed = Math.max(s.mom, s.last == null ? 0 : Math.hypot(loc.getX() - s.last.getX(), loc.getZ() - s.last.getZ()));
        boolean on = speed >= num("water-min-speed", 0.5) && s.mode != Mode.WALL && s.mode != Mode.CEIL && !p.isSneaking() || (s.mode == Mode.ROLL && speed >= 0.5);
        Map<Long, int[]> want = new HashMap<>();
        if (on) {
            double ax = loc.getX() + s.dir.getX() * speed * 2.0;
            double az = loc.getZ() + s.dir.getZ() * speed * 2.0;
            int cx = (int) Math.floor(ax);
            int cz = (int) Math.floor(az);
            int py = loc.getBlockY();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int bx = cx + dx;
                    int bz = cz + dz;
                    if (!loc.getWorld().isChunkLoaded(bx >> 4, bz >> 4)) {
                        continue;
                    }
                    for (int by = py + 1; by >= py - 2; by--) {
                        Block b = loc.getWorld().getBlockAt(bx, by, bz);
                        if (b.getType() == Material.WATER) {
                            if (loc.getWorld().getBlockAt(bx, by + 1, bz).isPassable()
                                    && !loc.getWorld().getBlockAt(bx, by + 1, bz).isLiquid()
                                    && Math.abs(loc.getY() - (by + 1)) <= 1.3) {
                                want.put(key(bx, by, bz), new int[]{bx, by, bz});
                            }
                            break;
                        }
                        if (!b.isPassable()) {
                            break;
                        }
                    }
                }
            }
        }
        if (want.isEmpty() && s.fake.isEmpty()) {
            return;
        }
        BlockData glass = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
        for (Map.Entry<Long, int[]> en : want.entrySet()) {
            if (!s.fake.containsKey(en.getKey())) {
                int[] c = en.getValue();
                p.sendBlockChange(new Location(loc.getWorld(), c[0], c[1], c[2]), glass);
                s.fake.put(en.getKey(), c);
            }
        }
        for (Iterator<Map.Entry<Long, int[]>> it = s.fake.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, int[]> en = it.next();
            if (!want.containsKey(en.getKey())) {
                int[] c = en.getValue();
                Location l = new Location(loc.getWorld(), c[0], c[1], c[2]);
                p.sendBlockChange(l, l.getBlock().getBlockData());
                it.remove();
            }
        }
        if (!want.isEmpty() && speed > 0.4) {
            Fx.burst(loc.clone().add(0, 0.1, 0), Particle.SPLASH, 8, 0.4, 0.2);
        }
    }

    // ------------------------------------------------------------------ visuals: ball, trail, electricity

    private boolean ballMode(S s) {
        return s.mode == Mode.CHARGE || s.mode == Mode.ROLL || (s.mode == Mode.AIR && s.ballAir);
    }

    private void visuals(Player p, S s, Location loc, double hs) {
        s.ticks++;
        PlayerSettings st = plugin.settings();
        double speed = Math.max(s.mom, hs);
        boolean ball = ballMode(s) && st.on(p, "sonic.ball");
        Vector dir = s.mode == Mode.CHARGE ? loc.getDirection().setY(0) : s.dir.clone();
        if (dir.lengthSquared() < 1.0E-4) {
            dir = s.lastDir.clone();
        } else {
            dir.normalize();
            s.lastDir = dir.clone();
        }
        PlayerSettings.Palette spin = st.spin(p);

        if (ball) {
            s.noBall = 0;
        } else if (s.ball != null) {
            s.noBall++;
        }
        // A one-tick slip out of ball form (a bump, a sneak glitch, ground contact flickering) must not show the normal body
        boolean keep = ball || (s.ball != null && s.ball.valid() && s.noBall < 4 && st.on(p, "sonic.ball"));
        if (keep) {
            if (s.ball == null || !s.ball.valid()) {
                if (s.ball != null) {
                    s.ball.remove();
                }
                s.ball = new SpinBall(plugin, p, loc.clone().add(0, 0.52, 0), spin);
                hideSelf(p, s, true);
                Fx.burst(loc.clone().add(0, 0.6, 0), Particle.POOF, 12, 0.3, 0.1);
            }
            s.ball.update(loc, dir, s.mode == Mode.CHARGE ? 0.5 + 0.2 * s.charge : speed, spin);
            if (s.mode == Mode.CHARGE) {
                // revving: the ball shakes and sprays dust
                Fx.burst(loc.clone().add(-dir.getX() * 0.4, 0.15, -dir.getZ() * 0.4), Particle.CLOUD, 2 + s.charge, 0.25, 0.05);
                Fx.dustFade(loc.clone().add(0, 0.5, 0), spin.at(System.currentTimeMillis()), Color.WHITE, 1.2f, 2 + s.charge, 0.4);
            }
            // keep the player hidden (Invisibility is refreshed each tick, equipment every half second)
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY, 6, 0, false, false, false));
            if (s.ticks % 10 == 0) {
                sendEquipment(p, false);
            }
            java.util.List<Player> bedrock = new ArrayList<>();
            for (Player v : loc.getWorld().getPlayers()) {
                if (FlashOverlay.isBedrock(v) && v.getLocation().distanceSquared(loc) < 64 * 64) {
                    bedrock.add(v);
                }
            }
            s.ball.bedrockParticles(loc, spin, bedrock);
        } else if (s.ball != null) {
            dropBall(p, s);
        }

        PlayerSettings.Palette trail = st.trail(p);
        PlayerSettings.Palette elec = st.electric(p);
        if (speed >= num("trail-min-speed", 0.6) && st.on(p, "sonic.trail")) {
            trail(loc, s, dir, speed, trail, ball);
        }
        if (speed >= num("electric-min-speed", 0.75) && st.on(p, "sonic.electric")) {
            electric(loc, speed, elec);
        }
    }

    /** Ribbon trail: several fading dust lines from where you were to where you are, plus streaks. */
    private void trail(Location loc, S s, Vector dir, double speed, PlayerSettings.Palette pal, boolean ball) {
        if (s.last == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Color c1 = pal.at(now);
        Color c2 = pal.rainbow() ? Fx.rainbow(now / 700.0 + 0.3) : Color.WHITE;
        Location from = new Location(loc.getWorld(), s.last.getX(), s.last.getY(), s.last.getZ());
        double[] heights = ball ? new double[]{0.52} : new double[]{0.35, 0.9, 1.45};
        Vector side = new Vector(-dir.getZ(), 0, dir.getX()).multiply(0.22);
        for (int i = 0; i < heights.length; i++) {
            double h = heights[i];
            Location a = from.clone().add(0, h, 0);
            Location b = loc.clone().add(0, h, 0);
            if (heights.length > 1 && i != 1) {
                Vector off = side.clone().multiply(i == 0 ? 1 : -1);
                a.add(off);
                b.add(off);
            }
            Fx.line(a, b, 0.6, l -> Fx.dustFade(l, c1, c2, 1.4f, 1, 0.02));
        }
        Location tail = loc.clone().add(-dir.getX() * 0.5, 0.9, -dir.getZ() * 0.5);
        Fx.burst(tail, Particle.END_ROD, 1, 0.3, 0.01);
        if (speed > 0.9) {
            Fx.burst(tail, Particle.CLOUD, 1, 0.25, 0.0);
        }
    }

    /** Small arcs of electricity crackling around the body, more of them the faster you go. */
    private void electric(Location loc, double speed, PlayerSettings.Palette pal) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        Color col = pal.at(System.currentTimeMillis());
        int arcs = 1 + (int) Math.min(4, speed * 3);
        Location center = loc.clone().add(0, 0.9, 0);
        for (int a = 0; a < arcs; a++) {
            double theta = r.nextDouble() * Math.PI * 2;
            double phi = Math.acos(2 * r.nextDouble() - 1);
            double rad = 0.55 + r.nextDouble() * 0.35;
            double x = Math.sin(phi) * Math.cos(theta) * rad;
            double y = Math.cos(phi) * rad * 0.9;
            double z = Math.sin(phi) * Math.sin(theta) * rad;
            Location cur = center.clone().add(x, y, z);
            int segs = 3 + r.nextInt(2);
            for (int i = 0; i < segs; i++) {
                Fx.dust(cur, col, 0.55f, 1, 0.0);
                cur.add((r.nextDouble() - 0.5) * 0.45, (r.nextDouble() - 0.5) * 0.45, (r.nextDouble() - 0.5) * 0.45);
            }
            Fx.burst(cur, Particle.ELECTRIC_SPARK, 1, 0.05, 0.1);
        }
    }

    private void hideSelf(Player p, S s, boolean hide) {
        if (hide) {
            s.hidSelf = !p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
            sendEquipment(p, false);
        }
    }

    /** Sends the player's armor and hands as empty (hide = false means "blank them") or real (restore) to everyone nearby. */
    private void sendEquipment(Player p, boolean restore) {
        org.bukkit.inventory.EquipmentSlot[] slots = {org.bukkit.inventory.EquipmentSlot.HEAD, org.bukkit.inventory.EquipmentSlot.CHEST,
                org.bukkit.inventory.EquipmentSlot.LEGS, org.bukkit.inventory.EquipmentSlot.FEET,
                org.bukkit.inventory.EquipmentSlot.HAND, org.bukkit.inventory.EquipmentSlot.OFF_HAND};
        for (Player v : p.getWorld().getPlayers()) {
            // Never to the player themselves: their own client applies it to their REAL inventory, which blanked the
            // held slot and left the wrong item behind when they switched slots mid-spin.
            if (v.equals(p) || v.getLocation().distanceSquared(p.getLocation()) > 80 * 80) {
                continue;
            }
            for (org.bukkit.inventory.EquipmentSlot sl : slots) {
                try {
                    ItemStack real = p.getInventory().getItem(sl);
                    v.sendEquipmentChange(p, sl, restore && real != null ? real : new ItemStack(Material.AIR));
                } catch (Throwable ignored) {
                    // viewer logging out
                }
            }
        }
    }

    private void dropBall(Player p, S s) {
        if (s.ball == null) {
            return;
        }
        s.ball.remove();
        s.ball = null;
        if (s.hidSelf) {
            p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        }
        s.hidSelf = false;
        sendEquipment(p, true);
        p.updateInventory(); // repairs any slot an earlier version blanked on the client
        Fx.burst(p.getLocation().add(0, 0.6, 0), Particle.POOF, 10, 0.3, 0.1);
    }

    // ------------------------------------------------------------------ helpers / effects

    private void toAir(S s, boolean ball) {
        enter(s, Mode.AIR);
        s.ballAir = ball;
    }

    private void enter(S s, Mode m) {
        s.mode = m;
        s.modeTicks = 0;
        s.hitTick.clear();
    }

    private void spinRing(Location loc, S s, double radius) {
        if (s.ball != null) {
            return; // the ball is the effect
        }
        Location c = loc.clone().add(0, 0.9, 0);
        Fx.ring(c, radius, 8, s.spin, l -> Fx.dust(l, BLUE, 1.1f, 1, 0.0));
        Fx.ring(c.clone().add(0, 0.3, 0), radius * 0.8, 6, -s.spin, l -> Fx.dust(l, WHITE, 0.9f, 1, 0.0));
    }

    private void speedTrail(Location loc, S s) {
        Location behind = loc.clone().add(-s.dir.getX() * 0.6, 0.6, -s.dir.getZ() * 0.6);
        Fx.burst(behind, Particle.CLOUD, 1, 0.15, 0.0);
        Fx.dust(behind, BLUE, 1.0f, 1, 0.1);
    }

    /** Applies the running speed. Sprint speed is about 0.28 blocks/tick, so a factor of mom / 0.28. */
    private void setSpeed(Player p, S s, double mom) {
        double factor = mom >= 0.3 ? mom / 0.2806 - 1.0 : 0.0;
        if (factor < 0) {
            factor = 0;
        }
        if (Math.abs(factor - s.appliedFactor) < 0.02) {
            return;
        }
        AttributeInstance inst = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (inst == null) {
            return;
        }
        if (s.speedMod != null) {
            try {
                inst.removeModifier(s.speedMod);
            } catch (Throwable ignored) {
                // already gone
            }
            s.speedMod = null;
        }
        if (factor > 0.001) {
            AttributeModifier m = new AttributeModifier(speedKey, factor, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY);
            try {
                inst.addTransientModifier(m);
                s.speedMod = m;
            } catch (Throwable ignored) {
                // a leftover with the same key: next update removes it
            }
        }
        s.appliedFactor = factor;
    }

    private void reset(Player p, S s) {
        dropBall(p, s);
        s.mode = Mode.RUN;
        s.mom = 0;
        s.charge = 0;
        s.modeTicks = 0;
        s.yHist.clear();
        setSpeed(p, s, 0);
        clearFake(p, s);
    }

    private void clearFake(Player p, S s) {
        if (s.fake.isEmpty()) {
            return;
        }
        for (int[] c : new ArrayList<>(s.fake.values())) {
            Location l = new Location(p.getWorld(), c[0], c[1], c[2]);
            p.sendBlockChange(l, l.getBlock().getBlockData());
        }
        s.fake.clear();
    }

    private void cleanup(Player p, S s) {
        dropBall(p, s);
        setSpeed(p, s, 0);
        clearFake(p, s);
        if (s.stepMod != null) {
            AttributeInstance step = p.getAttribute(Attribute.STEP_HEIGHT);
            if (step != null) {
                try {
                    step.removeModifier(s.stepMod);
                } catch (Throwable ignored) {
                    // already gone
                }
            }
            s.stepMod = null;
        }
    }

    @Override
    void onQuit(Player p) {
        S s = states.remove(p.getUniqueId());
        if (s != null) {
            cleanup(p, s);
        }
    }

    @Override
    void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            S s = states.get(p.getUniqueId());
            if (s != null) {
                cleanup(p, s);
            }
        }
        states.clear();
    }
}
