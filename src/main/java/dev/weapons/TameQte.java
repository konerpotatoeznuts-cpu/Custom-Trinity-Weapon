package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The taming minigame. Each round a floating circle appears next to the mob with a ring shrinking onto it.
 * Click (or tap) while the ring is in the final stretch to hit it. Clicking too early, missing the circle (Java) or
 * letting it run out costs a life, so spamming clicks never works. Rounds needed = how hard the mob is to tame.
 *
 * Bedrock touch screens only report WHICH entity was tapped, never where on screen, so Bedrock players only need the
 * right timing. Java players must also have the circle under their crosshair.
 */
final class TameQte implements Listener {

    private final class Session {
        final Player p;
        final Mob mob;
        final boolean hadAI;
        final int need;
        int done;
        int lives;
        BukkitTask task;

        Vector offset = new Vector();
        long circleStart = -1;   // -1 while waiting for the next circle
        long nextCircleAt;
        long lastInput;
        double seconds;          // length of the current round
        int ticks;

        Session(Player p, Mob mob, int need, int lives) {
            this.p = p;
            this.mob = mob;
            this.hadAI = mob.hasAI();
            this.need = need;
            this.lives = lives;
        }
    }

    private final WeaponsPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> retryAt = new HashMap<>();

    TameQte(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ config

    private double cfg(String key, double def) {
        return plugin.getConfig().getDouble("weapons.fantasy_tamer." + key, def);
    }

    boolean active(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    // ------------------------------------------------------------------ start / stop

    void start(Player p, Mob m, int rounds) {
        UUID id = p.getUniqueId();
        if (sessions.containsKey(id)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long wait = retryAt.get(id);
        if (wait != null && wait > now) {
            p.sendActionBar(Component.text("It's still wary of you. Try again in "
                    + String.format(java.util.Locale.ROOT, "%.0f", Math.ceil((wait - now) / 1000.0)) + "s.", NamedTextColor.RED));
            return;
        }
        for (Session other : sessions.values()) {
            if (other.mob.equals(m)) {
                p.sendActionBar(Component.text("Someone else is taming that.", NamedTextColor.RED));
                return;
            }
        }

        Session s = new Session(p, m, Math.max(1, rounds), (int) cfg("qte-lives", 3));
        m.setTarget(null);
        m.setAI(false); // it holds still while you tame it
        try {
            m.getPathfinder().stopPathfinding();
        } catch (Throwable ignored) {
            // not every mob has a navigator
        }
        m.setVelocity(new Vector(0, m.getVelocity().getY(), 0));
        s.nextCircleAt = now + 600;
        sessions.put(id, s);

        Fx.sound(m.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.8f);
        Fx.burst(m.getLocation().add(0, m.getHeight() + 0.4, 0), Particle.ENCHANT, 30, 0.5, 0.8);
        p.sendMessage(Component.text("Taming " + m.getName() + ": hit it when the ring reaches the circle! "
                + s.need + " round(s), " + s.lives + " lives.", NamedTextColor.LIGHT_PURPLE));

        s.task = new BukkitRunnable() {
            @Override
            public void run() {
                tick(s);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void end(Session s) {
        if (s.task != null) {
            s.task.cancel();
        }
        sessions.remove(s.p.getUniqueId());
        if (s.mob.isValid()) {
            s.mob.setAI(s.hadAI);
        }
    }

    private void cancel(Session s, String why) {
        end(s);
        if (s.p.isOnline()) {
            s.p.sendActionBar(Component.text(why, NamedTextColor.GRAY));
        }
    }

    private void fail(Session s) {
        end(s);
        Player p = s.p;
        retryAt.put(p.getUniqueId(), System.currentTimeMillis() + (long) (cfg("qte-retry-seconds", 3) * 1000));
        Location c = s.mob.getLocation().add(0, s.mob.getHeight() / 2.0, 0);
        Fx.burst(c, Particle.ANGRY_VILLAGER, 6, 0.4, 0);
        Fx.burst(c, Particle.SMOKE, 25, 0.4, 0.05);
        Fx.sound(s.mob.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.2f, 0.7f);
        Fx.sound(s.mob.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.2f, 0.5f);
        p.sendActionBar(Component.text(s.mob.getName() + " broke free!", NamedTextColor.RED));
        if (s.mob instanceof Enemy) {
            s.mob.setTarget(p); // a hostile mob that got away comes for you
        }
    }

    private void success(Session s) {
        end(s);
        plugin.tamers().tame(s.p, s.mob);
    }

    void shutdown() {
        for (Session s : new ArrayList<>(sessions.values())) {
            end(s);
        }
    }

    // ------------------------------------------------------------------ rounds

    private double roundSeconds(Session s) {
        double base = cfg("qte-seconds", 1.5);
        double min = cfg("qte-min-seconds", 0.9);
        return Math.max(min, base - 0.05 * s.need);
    }

    private Location mobCenter(Session s) {
        return s.mob.getLocation().add(0, s.mob.getHeight() / 2.0, 0);
    }

    private void spawnCircle(Session s) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location center = mobCenter(s);
        Vector toPlayer = s.p.getEyeLocation().toVector().subtract(center.toVector()).setY(0);
        if (toPlayer.lengthSquared() < 0.01) {
            toPlayer = new Vector(0, 0, 1);
        }
        toPlayer.normalize();
        double jitter = Math.toRadians(rnd.nextDouble(-70, 70));
        double cs = Math.cos(jitter);
        double sn = Math.sin(jitter);
        double r = Math.max(0.9, Math.min(2.0, s.mob.getWidth() / 2.0 + 0.8));
        double h = s.mob.getHeight();
        s.offset = new Vector(toPlayer.getX() * cs - toPlayer.getZ() * sn, 0, toPlayer.getX() * sn + toPlayer.getZ() * cs)
                .multiply(r).setY(rnd.nextDouble(-0.25, 0.6) * h);
        s.seconds = roundSeconds(s);
        s.circleStart = System.currentTimeMillis();
        Fx.sound(s.p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.5f);
    }

    private void tick(Session s) {
        Player p = s.p;
        if (!p.isOnline() || p.isDead()) {
            cancel(s, "");
            return;
        }
        if (plugin.items().identify(p.getInventory().getItemInMainHand()) != WeaponType.FANTASY_TAMER) {
            cancel(s, "Taming cancelled.");
            return;
        }
        if (!s.mob.isValid() || s.mob.isDead() || !s.mob.getWorld().equals(p.getWorld())
                || s.mob.getLocation().distanceSquared(p.getLocation()) > cfg("qte-max-distance", 12) * cfg("qte-max-distance", 12)) {
            cancel(s, "Taming cancelled.");
            return;
        }
        long now = System.currentTimeMillis();
        s.ticks++;

        // Frozen solid: no sliding, no pushing, no pathing until the minigame is over
        Vector v = s.mob.getVelocity();
        if (Math.abs(v.getX()) > 0.001 || Math.abs(v.getZ()) > 0.001) {
            s.mob.setVelocity(new Vector(0, v.getY(), 0));
        }
        if (s.mob.hasAI()) {
            s.mob.setAI(false);
        }

        if (s.ticks % 4 == 0) {
            p.sendActionBar(status(s));
        }
        Fx.burst(s.mob.getLocation().add(0, s.mob.getHeight() + 0.3, 0), Particle.ENCHANT, 2, 0.3, 0.4);

        if (s.circleStart < 0) {
            if (now >= s.nextCircleAt) {
                spawnCircle(s);
            }
            return;
        }

        long elapsed = now - s.circleStart;
        double progress = elapsed / (s.seconds * 1000.0);
        if (progress >= 1.0 + 0.12) {
            miss(s, "Too slow!");
            return;
        }
        draw(s, Math.min(1.0, progress));
    }

    private Component status(Session s) {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < s.need; i++) {
            bar.append(i < s.done ? "●" : "○");
        }
        return Component.text("Taming " + s.mob.getName() + "  ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(bar.toString(), NamedTextColor.GREEN))
                .append(Component.text("   " + "♥".repeat(Math.max(0, s.lives)), NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ drawing

    /** A ring in the plane facing the player, around {@code center}. */
    private void ring(Player p, Location center, double radius, int points, Color color, float size) {
        Vector n = p.getEyeLocation().toVector().subtract(center.toVector());
        if (n.lengthSquared() < 0.01) {
            return;
        }
        n.normalize();
        Vector up = Math.abs(n.getY()) > 0.95 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = n.clone().crossProduct(up).normalize();
        Vector v = n.clone().crossProduct(u).normalize();
        Particle.DustOptions dust = new Particle.DustOptions(color, size);
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            Vector off = u.clone().multiply(Math.cos(a) * radius).add(v.clone().multiply(Math.sin(a) * radius));
            p.spawnParticle(Particle.DUST, center.clone().add(off), 1, 0, 0, 0, 0, dust);
        }
    }

    private Location circleCenter(Session s) {
        return mobCenter(s).add(s.offset);
    }

    private void draw(Session s, double progress) {
        double r = cfg("qte-radius", 0.35);
        double r0 = cfg("qte-approach-radius", 1.3);
        boolean inWindow = progress >= 1.0 - cfg("qte-window", 0.4);
        Location c = circleCenter(s);

        // target circle: steady, turns green when you may click
        ring(s.p, c, r, 16, inWindow ? Color.fromRGB(80, 255, 120) : Color.fromRGB(255, 200, 60), 1.3f);
        ring(s.p, c, r * 0.45, 8, Color.WHITE, 0.9f);
        // approach ring shrinking onto it
        double ar = r + (r0 - r) * (1.0 - progress);
        ring(s.p, c, ar, 24, inWindow ? Color.fromRGB(150, 255, 170) : Color.WHITE, 1.0f);
    }

    // ------------------------------------------------------------------ input

    /** A click or tap. Returns true if it was used by the minigame. */
    boolean input(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - s.lastInput < 120) {
            return true; // one physical click can arrive as several events
        }
        s.lastInput = now;
        if (s.circleStart < 0) {
            return true; // between circles: nothing to hit yet
        }
        double progress = (now - s.circleStart) / (s.seconds * 1000.0);
        boolean inWindow = progress >= 1.0 - cfg("qte-window", 0.4) && progress <= 1.12;
        if (!inWindow) {
            miss(s, "Too early!");
            return true;
        }
        boolean needAim = !FlashOverlay.isBedrock(p) && plugin.getConfig().getBoolean("weapons.fantasy_tamer.qte-aim-java", true);
        if (needAim && !aimedAt(s)) {
            miss(s, "Aim at the circle!");
            return true;
        }
        hit(s);
        return true;
    }

    private boolean aimedAt(Session s) {
        Location eye = s.p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector w = circleCenter(s).toVector().subtract(eye.toVector());
        double t = w.dot(dir);
        if (t < 0.3 || t > 20) {
            return false;
        }
        Vector closest = eye.toVector().add(dir.multiply(t));
        double dist = closest.distance(circleCenter(s).toVector());
        return dist <= cfg("qte-radius", 0.35) + cfg("qte-aim-tolerance", 0.3);
    }

    private void hit(Session s) {
        s.done++;
        s.circleStart = -1;
        s.nextCircleAt = System.currentTimeMillis() + 300;
        Location c = circleCenter(s);
        Fx.burst(c, Particle.END_ROD, 14, 0.25, 0.15);
        Fx.burst(c, Particle.TOTEM_OF_UNDYING, 10, 0.2, 0.3);
        ring(s.p, c, 0.5, 20, Color.fromRGB(120, 255, 160), 1.4f);
        Fx.sound(s.mob.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f, 0.8f + 1.0f * s.done / s.need);
        Fx.sound(s.mob.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f + 0.8f * s.done / s.need);
        if (s.done >= s.need) {
            success(s);
        } else {
            s.p.sendActionBar(status(s));
        }
    }

    private void miss(Session s, String why) {
        s.lives--;
        s.circleStart = -1;
        s.nextCircleAt = System.currentTimeMillis() + 700;
        Location c = s.mob.getLocation().add(0, s.mob.getHeight() / 2.0, 0);
        Fx.burst(c, Particle.SMOKE, 12, 0.3, 0.03);
        Fx.sound(s.mob.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f);
        s.p.sendActionBar(Component.text(why + "  ", NamedTextColor.RED).append(status(s)));
        if (s.lives <= 0) {
            fail(s);
        }
    }

    // ------------------------------------------------------------------ events that count as a click or tap

    /** Left-clicks / swings in the air or on blocks count as a hit too. Right-click does nothing here. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (e.getHand() != EquipmentSlot.HAND || !(a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) || !active(e.getPlayer())) {
            return;
        }
        input(e.getPlayer());
    }

    /** Hitting a mob while taming does not hurt it, it counts as a click. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) {
            return;
        }
        if (active(p)) {
            e.setCancelled(true);
            input(p);
            return;
        }
        // Hitting a wild mob with the Fantasy Tamer starts the taming minigame instead of hurting it
        if (plugin.items().identify(p.getInventory().getItemInMainHand()) != WeaponType.FANTASY_TAMER
                || !(e.getEntity() instanceof Mob m) || !plugin.tamers().canTame(m)) {
            return;
        }
        UUID owner = plugin.tamers().ownerOf(m);
        if (owner == null) {
            e.setCancelled(true);
            if (plugin.protection().canUse(p)) {
                plugin.tamers().tameClick(p, m);
            }
        } else if (owner.equals(p.getUniqueId())) {
            e.setCancelled(true); // never hurt your own pet
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) {
            end(s);
        }
        retryAt.remove(e.getPlayer().getUniqueId());
    }
}
