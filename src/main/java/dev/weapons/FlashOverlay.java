package dev.weapons;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The flashbang screen.
 * Java players: a huge pure-white text-display panel is put right in front of THEIR camera (hidden from everybody
 * else) and faded out. It needs no resource pack and no helmet tricks.
 * Bedrock players (Geyser): a real white camera fade through Geyser's camera API, or short Blindness if that is missing.
 */
final class FlashOverlay {

    private final WeaponsPlugin plugin;
    private final Map<UUID, BukkitTask> active = new HashMap<>();
    private final Map<UUID, List<TextDisplay>> panels = new HashMap<>();

    FlashOverlay(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    static boolean isBedrock(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0L; // Floodgate UUIDs
    }

    void invalidate() {
        // nothing cached any more
    }

    /** Flash this player's screen for about {@code seconds}, with a fade out. */
    void flash(Player p, double seconds) {
        UUID id = p.getUniqueId();
        remove(id);

        // Ringing ears for everyone
        p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 2.0f, 1.6f);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 2.0f, 2.0f);

        if (!plugin.settings().on(p, "flash.overlay")) {
            return; // this player turned the white screen off
        }
        if (isBedrock(p)) {
            if (bedrockFade(p, seconds)) {
                return;
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                    (int) (plugin.getConfig().getDouble("weapons.prismatic_blade.bedrock-blind-seconds", 2.5) * 20), 0, false, false, false));
            return;
        }

        // Six huge pure-white, see-through text-display planes form a box around the player's camera.
        // Text backgrounds are flat white (no shading) and visible from both sides, so the whole screen is white in
        // first person, third person front and third person back. Only the flashed player can see them.
        final List<TextDisplay> list = new ArrayList<>();
        try {
            final float d = 5.0f;      // distance from the camera to each wall
            final float side = 70.0f;  // wall size (far bigger than needed, so inaccurate text sizes still cover everything)
            final float sx = side / 3.06f;  // 24 spaces are about 3.06 blocks wide per scale unit
            final float sy = side / 0.26f;  // one text line is about 0.26 blocks tall per scale unit
            final float h = 0.26f * sy;
            // Where a text display sits relative to its position is not certain on every client, so every wall is made
            // three times with its anchor guessed at the bottom, the middle and the top. Same white, same plane, so the
            // copies are invisible, but whichever guess is right (or close) leaves no gap.
            final float[] shifts = {-h / 2, 0f, h / 2};
            // A text background is only drawn from its FRONT (the +Z side). So every wall is turned to face the camera in
            // the middle of the box. (Before, the walls ahead of / beside / under the player faced outward and were invisible
            // from inside, which left a see-through hole in first person and in third person from behind.)
            final double q = Math.PI / 2;
            Quaternionf faceNeg = new Quaternionf();                  // wall at -Z, front already points to +Z (inward)
            Quaternionf facePos = new Quaternionf().rotateY((float) Math.PI); // wall at +Z, front turned to -Z
            Quaternionf faceMinusX = new Quaternionf().rotateY((float) q);     // wall at -X, front turned to +X
            Quaternionf facePlusX = new Quaternionf().rotateY((float) -q);     // wall at +X, front turned to -X
            Quaternionf ceiling = new Quaternionf().rotateX((float) q);        // wall at +Y, front turned down
            Quaternionf floor = new Quaternionf().rotateX((float) -q);         // wall at -Y, front turned up
            List<Object[]> walls = new ArrayList<>();
            for (float sh : shifts) {
                walls.add(new Object[]{facePos, new Vector3f(0, sh, d)});
                walls.add(new Object[]{faceNeg, new Vector3f(0, sh, -d)});
                walls.add(new Object[]{facePlusX, new Vector3f(d, sh, 0)});
                walls.add(new Object[]{faceMinusX, new Vector3f(-d, sh, 0)});
                walls.add(new Object[]{ceiling, new Vector3f(0, d, sh)});
                walls.add(new Object[]{floor, new Vector3f(0, -d, sh)});
            }
            for (Object[] w : walls) {
                Quaternionf rot0 = (Quaternionf) w[0];
                final Vector3f trans = (Vector3f) w[1];
                // A text display's background is only drawn from its front side. Turn every wall to face the camera,
                // otherwise the three walls that face outwards are invisible (that was the missing corner of the screen).
                Vector3f normal = new Vector3f(0, 0, 1).rotate(rot0);
                final Quaternionf rot = normal.dot(trans) > 0 ? new Quaternionf(rot0).rotateY((float) Math.PI) : rot0;
                TextDisplay td = p.getWorld().spawn(p.getEyeLocation(), TextDisplay.class, e -> {
                    e.setText(" ".repeat(24));
                    e.setBackgroundColor(Color.fromARGB(255, 255, 255, 255));
                    e.setDefaultBackground(false);
                    e.setSeeThrough(true);
                    e.setShadowed(false);
                    e.setBrightness(new Display.Brightness(15, 15));
                    e.setPersistent(false);
                    e.setVisibleByDefault(false);
                    e.setViewRange(3.0f);
                    e.setTeleportDuration(1);
                    e.setTransformation(new Transformation(trans, rot, new Vector3f(sx, sy, 1f), new Quaternionf()));
                });
                p.showEntity(plugin, td);
                list.add(td);
            }
        } catch (Throwable t) {
            for (TextDisplay td : list) {
                td.remove();
            }
            plugin.getLogger().warning("White flash overlay failed, using Blindness instead: " + t);
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false, false));
            return;
        }
        panels.put(id, list);
        final TextDisplay panel = list.get(0);

        final int total = Math.max(10, (int) (seconds * 20));
        final int hold = (int) (total * 0.55);
        BukkitTask task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || !panel.isValid() || t >= total) {
                    remove(id);
                    return;
                }
                Location eye = p.getEyeLocation();
                for (TextDisplay td : list) {
                    td.teleport(eye); // the box is so big that a tick of lag never lets the camera out of it
                }
                if (t >= hold) {
                    double f = 1.0 - (t - hold) / (double) (total - hold);
                    Color c = Color.fromARGB((int) Math.max(0, Math.min(255, f * 255)), 255, 255, 255);
                    for (TextDisplay td : list) {
                        td.setBackgroundColor(c);
                    }
                }
                if (t % 8 == 0 && t < hold + 8) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f * (1f - t / (float) total), 2.0f);
                }
                t++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
        active.put(id, task);
    }

    /** A point just in front of the player's eyes. */
    private static Location front(Player p) {
        Location eye = p.getEyeLocation();
        return eye.add(eye.getDirection().multiply(0.45));
    }

    private void remove(UUID id) {
        BukkitTask t = active.remove(id);
        if (t != null) {
            t.cancel();
        }
        List<TextDisplay> ds = panels.remove(id);
        if (ds != null) {
            for (TextDisplay d : ds) {
                if (d.isValid()) {
                    d.remove();
                }
            }
        }
    }

    /**
     * Bedrock: a real white screen fade through Geyser's camera API (looked up by reflection, so Geyser is optional).
     * Returns false if Geyser's API is missing or too old, and the caller falls back to Blindness.
     */
    private boolean bedrockFade(Player p, double seconds) {
        if (!plugin.getConfig().getBoolean("weapons.prismatic_blade.bedrock-white-flash", true)) {
            return false;
        }
        try {
            org.bukkit.plugin.Plugin geyser = plugin.getServer().getPluginManager().getPlugin("Geyser-Spigot");
            if (geyser == null || !geyser.isEnabled()) {
                return false;
            }
            ClassLoader cl = geyser.getClass().getClassLoader();
            Object api = Class.forName("org.geysermc.geyser.api.GeyserApi", true, cl).getMethod("api").invoke(null);
            Object conn = api.getClass().getMethod("connectionByUuid", UUID.class).invoke(api, p.getUniqueId());
            if (conn == null) {
                return false;
            }
            Object camera = conn.getClass().getMethod("camera").invoke(conn);
            Class<?> fadeClass = Class.forName("org.geysermc.geyser.api.bedrock.camera.CameraFade", true, cl);
            Object builder = fadeClass.getMethod("builder").invoke(null);
            Class<?> bc = builder.getClass();
            float total = (float) Math.max(0.6, Math.min(16.0, seconds));
            builder = bc.getMethod("color", java.awt.Color.class).invoke(builder, java.awt.Color.WHITE);
            builder = bc.getMethod("fadeInSeconds", float.class).invoke(builder, 0.05f);
            builder = bc.getMethod("fadeHoldSeconds", float.class).invoke(builder, total * 0.55f);
            builder = bc.getMethod("fadeOutSeconds", float.class).invoke(builder, total * 0.45f);
            Object fade = bc.getMethod("build").invoke(builder);
            for (java.lang.reflect.Method m : camera.getClass().getMethods()) {
                if (m.getName().equals("sendCameraFade") && m.getParameterCount() == 1) {
                    m.invoke(camera, fade);
                    return true;
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Bedrock white flash not available, using Blindness instead: " + t);
        }
        return false;
    }

    /** Remove every panel (plugin disable). */
    void shutdown() {
        for (UUID id : new ArrayList<>(panels.keySet())) {
            remove(id);
        }
        active.clear();
    }

    void forget(Player p) {
        remove(p.getUniqueId());
    }
}
