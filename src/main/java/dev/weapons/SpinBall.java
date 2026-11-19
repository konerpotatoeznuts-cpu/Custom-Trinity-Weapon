package dev.weapons;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The Sonic spin ball: while spinning the player is replaced by a glowing, rolling ball with trailing quills
 * (like the sprite in the games). Java players see rotating block displays; Bedrock can't see displays,
 * so Bedrock viewers get a dense particle ball instead.
 */
final class SpinBall {

    /** One piece of the ball. {@code spin} pieces roll with the ball, the quills only point backwards. */
    private record Part(Vector3f size, Quaternionf base, Vector3f off, boolean spin, int mat) {
    }

    private static final double TAU = Math.PI * 2;

    private final Player owner;
    private final List<Part> specs = new ArrayList<>();
    private final List<BlockDisplay> parts = new ArrayList<>();
    private double roll;
    private int tick;
    private String paletteId = "";

    SpinBall(WeaponsPlugin plugin, Player owner, Location at, PlayerSettings.Palette pal) {
        this.owner = owner;
        // two cubes turned against each other make a round silhouette; an inner lighter cube gives it depth
        specs.add(new Part(new Vector3f(0.62f), new Quaternionf(), new Vector3f(), true, 0));
        specs.add(new Part(new Vector3f(0.62f), new Quaternionf().rotateY((float) (Math.PI / 4)).rotateX(0.615f), new Vector3f(), true, 0));
        specs.add(new Part(new Vector3f(0.5f), new Quaternionf().rotateZ((float) (Math.PI / 4)).rotateX(0.4f), new Vector3f(), true, 1));
        specs.add(new Part(new Vector3f(0.16f), new Quaternionf().rotateY(0.5f), new Vector3f(0f, 0.27f, 0.22f), true, 2)); // shine
        // quills streaming behind
        specs.add(new Part(new Vector3f(0.1f, 0.1f, 0.8f), new Quaternionf(), new Vector3f(0f, 0.1f, -0.55f), false, 0));
        specs.add(new Part(new Vector3f(0.09f, 0.09f, 0.7f), new Quaternionf().rotateY(0.25f), new Vector3f(0.2f, -0.06f, -0.55f), false, 0));
        specs.add(new Part(new Vector3f(0.09f, 0.09f, 0.7f), new Quaternionf().rotateY(-0.25f), new Vector3f(-0.2f, 0.02f, -0.6f), false, 1));
        for (Part ignored : specs) {
            BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, e -> {
                e.setBlock(Material.BLUE_CONCRETE.createBlockData());
                e.setBrightness(new Display.Brightness(15, 15));
                e.setPersistent(false);
                e.setViewRange(1.5f);
                e.setShadowRadius(0f);
                e.setShadowStrength(0f);
                e.setTeleportDuration(1);
                e.setInterpolationDuration(1);
            });
            parts.add(d);
        }
    }

    boolean valid() {
        return !parts.isEmpty() && parts.get(0).isValid();
    }

    /** Moves and rolls the ball. {@code speed} is blocks per tick; {@code dir} is the horizontal travel direction. */
    void update(Location feet, Vector dir, double speed, PlayerSettings.Palette pal) {
        tick++;
        Location center = feet.clone().add(0, 0.52, 0);
        double theta = Math.atan2(dir.getX(), dir.getZ());
        roll += 0.6 + speed * 1.1;
        Quaternionf qy = new Quaternionf().rotateY((float) theta);
        Quaternionf qroll = new Quaternionf(qy).rotateX((float) roll);

        for (int i = 0; i < specs.size(); i++) {
            Part sp = specs.get(i);
            BlockDisplay d = parts.get(i);
            if (!d.isValid()) {
                continue;
            }
            Quaternionf frame = sp.spin() ? qroll : qy;
            Quaternionf q = new Quaternionf(frame).mul(sp.base());
            Vector3f t = new Vector3f(sp.off());
            frame.transform(t);
            Vector3f half = new Vector3f(sp.size()).mul(0.5f);
            q.transform(half);
            t.sub(half);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(1);
            d.setTransformation(new Transformation(t, q, new Vector3f(sp.size()), new Quaternionf()));
            d.teleport(center);
            // quills stretch a little with speed
            if (tick % 3 == 0 || !pal.id().equals(paletteId)) {
                d.setBlock(blockFor(sp.mat(), pal).createBlockData());
            }
        }
        paletteId = pal.id();
    }

    private Material blockFor(int mat, PlayerSettings.Palette pal) {
        if (pal.rainbow()) {
            Material[] r = PlayerSettings.RAINBOW_BLOCKS;
            return mat == 2 ? Material.WHITE_CONCRETE : r[(tick / 3 + mat * 3) % r.length];
        }
        return switch (mat) {
            case 1 -> pal.inner();
            case 2 -> Material.WHITE_CONCRETE;
            default -> pal.core();
        };
    }

    /** Bedrock viewers: a spinning particle sphere in the ball's color. */
    void bedrockParticles(Location feet, PlayerSettings.Palette pal, List<Player> viewers) {
        if (viewers.isEmpty()) {
            return;
        }
        Location c = feet.clone().add(0, 0.52, 0);
        Color col = pal.at(System.currentTimeMillis());
        Particle.DustOptions main = new Particle.DustOptions(col, 1.6f);
        Particle.DustOptions shine = new Particle.DustOptions(Color.WHITE, 1.0f);
        int n = 22;
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - (i / (double) (n - 1)) * 2;
            double r = Math.sqrt(1 - y * y);
            double a = i * golden + roll;
            double x = Math.cos(a) * r * 0.42;
            double z = Math.sin(a) * r * 0.42;
            for (Player v : viewers) {
                v.spawnParticle(Particle.DUST, c.getX() + x, c.getY() + y * 0.42, c.getZ() + z, 1, 0, 0, 0, 0, main);
            }
        }
        for (Player v : viewers) {
            v.spawnParticle(Particle.DUST, c.getX(), c.getY(), c.getZ(), 3, 0.12, 0.12, 0.12, 0, shine);
        }
    }

    void remove() {
        for (BlockDisplay d : parts) {
            if (d.isValid()) {
                d.remove();
            }
        }
        parts.clear();
    }

    Player owner() {
        return owner;
    }
}
