package dev.weapons;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bullet visuals.
 * <ul>
 *   <li><b>Model tracers</b> (Java players, needs the resource pack): the AK-47 and the revolver fire a real 3D bullet model that
 *       flies down the shot. Bedrock players and players without models keep the particle tracer.</li>
 *   <li><b>Bullet holes</b>: a bullet that hits a block leaves a hole that fades away after a while. Java players see a small dark
 *       decal (no pack needed); Bedrock players see a few dark specks, because Geyser cannot show display entities.</li>
 * </ul>
 */
final class BulletFx {

    private final WeaponsPlugin plugin;
    private BukkitTask task;
    private int liveTracers;

    private static final class Hole {
        final List<TextDisplay> parts = new ArrayList<>();
        final Location at;
        final long bornTick;
        final WeaponType type;

        Hole(Location at, long bornTick, WeaponType type) {
            this.at = at;
            this.bornTick = bornTick;
            this.type = type;
        }
    }

    private final Deque<Hole> holes = new ArrayDeque<>();

    BulletFx(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::maintain, 20L, 10L);
    }

    void shutdown() {
        if (task != null) {
            task.cancel();
        }
        for (Hole h : holes) {
            kill(h);
        }
        holes.clear();
    }

    // ------------------------------------------------------------------ who sees what

    private static boolean java(Player v) {
        return !FlashOverlay.isBedrock(v);
    }

    private boolean models() {
        return plugin.getConfig().getBoolean("bullets.java-models", true);
    }

    /** The item that carries the bullet model. The pack picks the model from the item name, like for every weapon. */
    private ItemStack bulletItem() {
        ItemStack it = new ItemStack(Material.IRON_NUGGET);
        it.editMeta(m -> m.customName(Component.text("Bullet Tracer")));
        try {
            it.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                    io.papermc.paper.datacomponent.item.CustomModelData.customModelData().addString("bullet_tracer").build());
        } catch (Throwable ignored) {
            // the name is enough for the Java pack
        }
        return it;
    }

    // ------------------------------------------------------------------ model tracer: a flight along a straight line

    /** A bullet model flying from {@code from} to {@code to} (hitscan guns). Seen by Java players only. */
    void tracer(WeaponType type, Location from, Location to) {
        if (!models() || liveTracers >= plugin.getConfig().getInt("bullets.max-live-tracers", 60)) {
            return;
        }
        World w = from.getWorld();
        if (w == null || w != to.getWorld()) {
            return;
        }
        Vector dir = to.toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 1.0) {
            return;
        }
        dir.normalize();
        double speed = plugin.getConfig().getDouble("bullets.tracer-speed", 7.0); // blocks per tick: fast, but you can see it
        final int ticks = (int) Math.max(1, Math.min(59, Math.ceil(len / speed)));
        Location start = from.clone();
        start.setDirection(dir);
        final ItemDisplay d;
        try {
            d = w.spawn(start, ItemDisplay.class, e -> {
                e.setItemStack(bulletItem());
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setBillboard(Display.Billboard.FIXED);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setViewRange(1.0f);
                e.setTeleportDuration(ticks);
                float s = (float) plugin.getConfig().getDouble("bullets.tracer-scale", 1.6);
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
            });
        } catch (Throwable t) {
            return;
        }
        for (Player v : w.getPlayers()) {
            if (java(v) && v.getLocation().distanceSquared(from) < 96 * 96 && plugin.settings().particles(v, type)) {
                v.showEntity(plugin, d);
            }
        }
        liveTracers++;
        final Location end = to.clone();
        end.setDirection(dir);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (d.isValid()) {
                d.teleport(end); // the client slides it there over `ticks` ticks
            }
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            d.remove();
            liveTracers--;
        }, ticks + 2L);
    }

    // ------------------------------------------------------------------ model that rides a projectile (revolver)

    /** Replaces the look of an arrow with the bullet model for Java players (Bedrock keeps seeing the arrow). */
    void follow(WeaponType type, AbstractArrow arrow) {
        if (!models()) {
            return;
        }
        World w = arrow.getWorld();
        Location l = arrow.getLocation();
        l.setDirection(arrow.getVelocity().lengthSquared() > 1.0E-6 ? arrow.getVelocity() : l.getDirection());
        final ItemDisplay d;
        try {
            d = w.spawn(l, ItemDisplay.class, e -> {
                e.setItemStack(bulletItem());
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setBillboard(Display.Billboard.FIXED);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setViewRange(1.0f);
                e.setTeleportDuration(1);
                float s = (float) plugin.getConfig().getDouble("bullets.tracer-scale", 1.6);
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
            });
        } catch (Throwable t) {
            return;
        }
        arrow.setVisibleByDefault(false);
        for (Player v : w.getPlayers()) {
            if (v.getLocation().distanceSquared(l) > 96 * 96) {
                continue;
            }
            if (java(v) && plugin.settings().particles(v, type)) {
                v.showEntity(plugin, d);
            } else {
                v.showEntity(plugin, arrow); // Bedrock (and anyone who turned the effects off) keeps the plain arrow
            }
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!arrow.isValid()) {
                    d.remove();
                    cancel();
                    return;
                }
                Location a = arrow.getLocation();
                Vector v = arrow.getVelocity();
                if (v.lengthSquared() > 1.0E-6) {
                    a.setDirection(v);
                }
                d.teleport(a);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ------------------------------------------------------------------ bullet holes

    /** Leaves a hole in the block face that was hit. */
    void hole(WeaponType type, Location hit, BlockFace face) {
        if (!plugin.getConfig().getBoolean("bullet-holes.enabled", true) || face == null || hit.getWorld() == null) {
            return;
        }
        Vector n = face.getDirection();
        if (n.lengthSquared() < 0.5) {
            return;
        }
        int max = plugin.getConfig().getInt("bullet-holes.max", 100);
        while (holes.size() >= max) {
            kill(holes.pollFirst());
        }
        Location at = hit.clone().add(n.clone().multiply(0.012));
        at.setDirection(n);
        Hole h = new Hole(at, Bukkit.getCurrentTick(), type);
        try {
            // a text display is a flat, pack-free dark quad. Two of them, one turned 45 degrees, make an octagon.
            final float size = (float) plugin.getConfig().getDouble("bullet-holes.size", 0.17);
            final float sx = size / 0.1275f;
            final float sy = size / 0.26f;
            for (int i = 0; i < 2; i++) {
                final Quaternionf rot = new Quaternionf().rotateZ(i == 0 ? 0f : (float) (Math.PI / 4));
                // the text box grows upward from its position, so shift it down to centre the quad on the hit point
                final Vector3f shift = new Vector3f(0, size / 2f, 0).rotate(rot).negate();
                TextDisplay td = hit.getWorld().spawn(at, TextDisplay.class, e -> {
                    e.setText(" ");
                    e.setBackgroundColor(Color.fromARGB(215, 12, 12, 12));
                    e.setDefaultBackground(false);
                    e.setSeeThrough(false);
                    e.setShadowed(false);
                    e.setBrightness(new Display.Brightness(4, 4));
                    e.setPersistent(false);
                    e.setVisibleByDefault(false);
                    e.setViewRange(0.45f);
                    e.setTransformation(new Transformation(shift, rot, new Vector3f(sx, sy, 1f), new Quaternionf()));
                });
                h.parts.add(td);
            }
        } catch (Throwable t) {
            kill(h);
            return;
        }
        holes.addLast(h);
        reveal(h);
    }

    /** Shows a hole to Java players who are close. */
    private void reveal(Hole h) {
        for (Player v : h.at.getWorld().getPlayers()) {
            if (java(v) && v.getLocation().distanceSquared(h.at) < 40 * 40 && plugin.settings().particles(v, h.type)) {
                for (TextDisplay td : h.parts) {
                    if (td.isValid() && !v.canSee(td)) {
                        v.showEntity(plugin, td);
                    }
                }
            }
        }
    }

    private void kill(Hole h) {
        if (h == null) {
            return;
        }
        for (TextDisplay td : h.parts) {
            if (td.isValid()) {
                td.remove();
            }
        }
    }

    /** Every half second: fades and removes old holes, shows them to players who walked up, and speckles them for Bedrock. */
    private void maintain() {
        if (holes.isEmpty()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        double life = plugin.getConfig().getDouble("bullet-holes.seconds", 25.0) * 20.0;
        double fade = Math.min(life * 0.4, 160.0); // the last stretch is a slow fade
        List<Player> bedrock = null;
        java.util.Iterator<Hole> it = holes.iterator();
        while (it.hasNext()) {
            Hole h = it.next();
            double age = now - h.bornTick;
            if (age >= life || !h.at.getWorld().isChunkLoaded(h.at.getBlockX() >> 4, h.at.getBlockZ() >> 4)) {
                kill(h);
                it.remove();
                continue;
            }
            if (age > life - fade) {
                int a = (int) Math.max(0, 215 * ((life - age) / fade));
                for (TextDisplay td : h.parts) {
                    if (td.isValid()) {
                        td.setBackgroundColor(Color.fromARGB(a, 12, 12, 12));
                    }
                }
            }
            if (((now - h.bornTick) / 10) % 2 == 0) {
                reveal(h); // new viewers who walked into range
            }
            // Bedrock cannot see display entities: a few dark specks stand in for the hole
            if (bedrock == null) {
                bedrock = new ArrayList<>();
                for (Player v : Bukkit.getOnlinePlayers()) {
                    if (FlashOverlay.isBedrock(v)) {
                        bedrock.add(v);
                    }
                }
            }
            for (Player v : bedrock) {
                if (v.getWorld() == h.at.getWorld() && v.getLocation().distanceSquared(h.at) < 28 * 28 && plugin.settings().particles(v, h.type)) {
                    v.spawnParticle(Particle.DUST, h.at, 2, 0.03, 0.03, 0.03, 0, new Particle.DustOptions(Color.fromRGB(18, 18, 18), 0.8f));
                }
            }
        }
    }
}
