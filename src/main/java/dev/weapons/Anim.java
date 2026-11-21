package dev.weapons;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Ability animations without any model: a ghost copy of the weapon appears around the player and performs the move
 * (a slash, an overhead smash, a spin, a thrust, a rising cut, a throw, a summoning...). It is a display entity driven by
 * keyframes, so the game itself smooths the motion between them. Java players see it. Bedrock players cannot see display
 * entities, they keep the particle effects. It follows the player, uses the weapon's own texture and can be switched off per
 * player in /weapons config (the weapon's particles toggle) or for everybody with {@code animations.ghost-weapon: false}.
 */
final class Anim {

    private Anim() {
    }

    private record Key(int ticks, float x, float y, float z, float yaw, float pitch, float roll, float scale) {
    }

    private static Key k(int ticks, double x, double y, double z, double yaw, double pitch, double roll, double scale) {
        return new Key(ticks, (float) x, (float) y, (float) z, (float) Math.toRadians(yaw), (float) Math.toRadians(pitch),
                (float) Math.toRadians(roll), (float) scale);
    }

    // ------------------------------------------------------------------ which ability plays what

    /** Called whenever an ability actually starts (its cooldown starts). */
    static void ability(WeaponsPlugin plugin, Player p, WeaponType t, String key) {
        Animations.trigger(plugin, p, t, "ability_" + key); // command hooks, see config.yml
        if (ModelAnim.play(plugin, p, t, "ability_" + key)) {
            return; // a BetterModel player animation took over: no ghost weapon
        }
        String name = switch (t) {
            case DURANDAL -> key.equals("beam") ? "smash" : "slash";
            case HALBERD_OF_DESPAIR -> key.equals("spin") ? "spin" : "thrust";
            case ELDRITCH_MACE -> key.equals("dash") ? "thrust" : "spin";
            case PRISMATIC_BLADE -> "summon";
            case WARDEN_WHIP -> "crack";
            case EXCALIBUR -> key.equals("uppercut") ? "uppercut" : "barrage";
            case FANTASY_TAMER -> "summon";
            case SEA_TRUNCHEON -> key.equals("smash") ? "smash" : key.equals("tsunami") ? "tsunami" : "summon";
            case CRYO_TEMPEST -> key.equals("pilot") ? "thrust" : "uppercut";
            default -> null; // Great Axe plays its own throw; guns and the grenade have no ghost
        };
        if (name != null) {
            play(plugin, p, t, name, 0);
        }
    }

    // ------------------------------------------------------------------ the moves

    private static List<Key> build(String name, int ticks) {
        List<Key> l = new ArrayList<>();
        switch (name) {
            case "slash" -> {
                // horizontal sweep, right to left, blade pointing outward
                for (int i = 0; i <= 6; i++) {
                    double az = -85 + i * (170.0 / 6);
                    l.add(k(i == 0 ? 2 : 1, 1.6 * Math.sin(Math.toRadians(az)), 1.1, 1.6 * Math.cos(Math.toRadians(az)), az, 90, 0, 2.6));
                }
                l.add(k(3, 0, 1.1, 1.6, 85, 90, 0, 2.6));
            }
            case "crack" -> {
                for (int i = 0; i <= 5; i++) {
                    double az = 55 - i * 22;
                    l.add(k(1, 2.3 * Math.sin(Math.toRadians(az)), 1.2, 2.3 * Math.cos(Math.toRadians(az)), az, 90, 0, 3.4));
                }
                l.add(k(3, 0, 1.2, 2.5, -55, 90, 0, 3.4));
            }
            case "barrage" -> {
                // three quick cuts: right to left, left to right, then a stab
                for (int round = 0; round < 2; round++) {
                    for (int i = 0; i <= 3; i++) {
                        double az = (round == 0 ? -80 : 80) + (round == 0 ? 1 : -1) * i * (160.0 / 3);
                        l.add(k(1, 1.7 * Math.sin(Math.toRadians(az)), 1.1, 1.7 * Math.cos(Math.toRadians(az)), az, 90, 0, 2.6));
                    }
                }
                l.add(k(2, 0, 1.1, 0.8, 0, 90, 0, 2.6));
                l.add(k(2, 0, 1.1, 3.2, 0, 90, 0, 3.0));
                l.add(k(3, 0, 1.1, 3.4, 0, 90, 0, 3.0));
            }
            case "smash" -> {
                l.add(k(5, 0, 2.2, 0.2, 0, -35, 0, 3.0));   // raised high behind
                l.add(k(1, 0, 2.3, 0.4, 0, -40, 0, 3.2));
                l.add(k(2, 0, 1.4, 1.2, 0, 60, 0, 3.4));    // coming down
                l.add(k(1, 0, 0.5, 1.9, 0, 165, 0, 3.4));   // slammed into the ground
                l.add(k(5, 0, 0.5, 1.9, 0, 165, 0, 3.4));
            }
            case "spin" -> {
                for (int i = 1; i <= 16; i++) {
                    double az = i * 45;
                    l.add(k(2, 1.7 * Math.sin(Math.toRadians(az)), 1.0, 1.7 * Math.cos(Math.toRadians(az)), az, 90, 0, 2.8));
                }
            }
            case "thrust" -> {
                l.add(k(3, 0, 1.1, 0.5, 0, 90, 0, 2.2));
                l.add(k(1, 0, 1.1, 2.6, 0, 90, 0, 2.8));
                l.add(k(5, 0, 1.1, 2.8, 0, 90, 0, 2.8));
                l.add(k(3, 0, 1.1, 0.6, 0, 90, 0, 2.2));
            }
            case "uppercut" -> {
                l.add(k(3, 0.4, 0.3, 1.0, 0, 150, 0, 2.8));
                l.add(k(2, 0.2, 0.9, 1.5, 0, 90, 0, 3.0));
                l.add(k(2, 0, 1.8, 1.3, 0, 10, 0, 3.2));
                l.add(k(2, 0, 2.5, 0.9, 0, -35, 0, 3.2));
                l.add(k(4, 0, 2.5, 0.9, 0, -35, 0, 3.2));
            }
            case "summon" -> {
                l.add(k(4, 0, 1.4, 0.2, 0, 0, 0, 1.0));
                for (int i = 1; i <= 8; i++) {
                    l.add(k(3, 0, 1.6 + i * 0.13, 0.1, i * 90, 0, 0, 1.0 + i * 0.2));
                }
                l.add(k(2, 0, 2.8, 0.1, 810, 0, 0, 3.4));
            }
            case "tsunami" -> {
                // raised to the sky, then brought down toward the front
                for (int i = 1; i <= 5; i++) {
                    l.add(k(3, 0, 1.6 + i * 0.2, 0.1, i * 120, 0, 0, 1.0 + i * 0.35));
                }
                l.add(k(5, 0, 2.7, 0.2, 600, 0, 0, 3.0));
                l.add(k(2, 0, 1.6, 1.4, 0, 70, 0, 3.4));
                l.add(k(1, 0, 0.7, 2.4, 0, 160, 0, 3.4));
                l.add(k(5, 0, 0.7, 2.4, 0, 160, 0, 3.4));
            }
            case "throw" -> {
                l.add(k(2, 0, 1.2, 0.5, 0, 0, 0, 2.0));
                for (int i = 1; i <= 6; i++) {
                    l.add(k(1, 0, 1.2, 0.5 + i * 0.75, 0, 0, i * 120, 2.2));
                }
                for (int i = 1; i <= 7; i++) {
                    l.add(k(1, 0, 1.2, 5.0 - i * 0.6, 0, 0, 720 + i * 120, 2.2));
                }
            }
            case "reload" -> {
                // the gun floats up in front of the player and flips over while it reloads
                int spins = Math.max(1, ticks / 6);
                l.add(k(3, 0.5, 1.0, 0.9, 0, 0, 0, 1.4));
                for (int i = 1; i <= spins; i++) {
                    l.add(k(6, 0.5, 1.0 + 0.1 * Math.sin(i), 0.9, 0, i * 180, 0, 1.4));
                }
            }
            default -> {
                return null;
            }
        }
        // the ghost shrinks away at the end
        Key last = l.get(l.size() - 1);
        l.add(new Key(3, last.x, last.y, last.z, last.yaw, last.pitch, last.roll, 0.01f));
        return l;
    }

    // ------------------------------------------------------------------ playing

    static void play(WeaponsPlugin plugin, Player p, WeaponType type, String name, int ticks) {
        if (!plugin.getConfig().getBoolean("animations.ghost-weapon", true)
                || (name.equals("reload") && ModelAnim.has(plugin, type, "reload"))) {
            return;
        }
        final List<Key> keys = build(name, ticks);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        final float yaw = p.getLocation().getYaw();
        final ItemStack stack = plugin.items().create(type);
        stack.setAmount(1);
        final ItemDisplay d;
        try {
            d = p.getWorld().spawn(at(p, yaw), ItemDisplay.class, e -> {
                e.setItemStack(stack);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setBillboard(Display.Billboard.FIXED);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setViewRange(1.5f);
                e.setTeleportDuration(1);
                e.setInterpolationDelay(0);
                e.setInterpolationDuration(0);
                Key f = keys.get(0);
                e.setTransformation(pose(f, 0.01f));
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Ability animation failed: " + t);
            return;
        }
        for (Player v : p.getWorld().getPlayers()) {
            if (!FlashOverlay.isBedrock(v) && v.getLocation().distanceSquared(p.getLocation()) < 64 * 64
                    && plugin.settings().particles(v, type)) {
                v.showEntity(plugin, d);
            }
        }
        int total = 0;
        for (Key key : keys) {
            total += key.ticks;
        }
        final int end = total + 2;
        new BukkitRunnable() {
            int t = 0;
            int idx = 0;
            int nextAt = 0;

            @Override
            public void run() {
                if (!d.isValid() || !p.isOnline() || p.isDead() || t > end) {
                    d.remove();
                    cancel();
                    return;
                }
                d.teleport(at(p, yaw));
                if (t == nextAt && idx < keys.size()) {
                    Key key = keys.get(idx++);
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(Math.max(1, key.ticks));
                    d.setTransformation(pose(key, key.scale));
                    nextAt += key.ticks;
                }
                t++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static Location at(Player p, float yaw) {
        Location l = p.getLocation();
        return new Location(l.getWorld(), l.getX(), l.getY(), l.getZ(), yaw, 0);
    }

    /** blade up in the item picture is a 45 degree turn; then pitch tilts it forward, yaw turns it around the player. */
    private static Transformation pose(Key k, float scale) {
        Quaternionf q = new Quaternionf().rotateY(k.yaw).rotateX(k.pitch).rotateZ(k.roll).rotateZ((float) Math.toRadians(45));
        return new Transformation(new Vector3f(k.x, k.y, k.z), q, new Vector3f(scale, scale, scale), new Quaternionf());
    }
}
