package dev.weapons;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A rolling wall of water made of semi-transparent BlockDisplays (no collision, no lag from particles).
 * Bedrock players cannot see Display entities, so they get a particle version of the same wave.
 */
final class TsunamiWave {

    private static final int STEP_TICKS = 3;
    private static final double STEP = 1.9; // blocks per step (about the same speed, but far fewer updates)

    private final WeaponsPlugin plugin;
    private final Player caster;
    private final World world;
    private final Vector dir;
    private final Vector right;
    private final double half;
    private final double height;
    private final double range;
    private final double damage;
    private final int columns;
    private final double[] colY;
    private List<Player> bedrockViewers = new ArrayList<>();
    /** Bedrock can't see display entities: they get a wall of client-side water blocks instead (per viewer). */
    private final java.util.Map<java.util.UUID, java.util.Map<Long, int[]>> fakeBlocks = new java.util.HashMap<>();
    private final float yaw;

    private final List<BlockDisplay> displays = new ArrayList<>();
    /** per display: column index (-1..1 scaled), layer. */
    private final List<int[]> slots = new ArrayList<>();
    private final Location center;
    private double travelled;
    private double refY;
    private int step;
    private BukkitRunnable task;
    private boolean finished;

    private static final Material[] LAYER = {Material.LIGHT_BLUE_STAINED_GLASS, Material.CYAN_STAINED_GLASS};
    private static final double[] LAYER_BACK = {0.0, 1.8};
    private static final double[] LAYER_H = {1.0, 0.65};

    TsunamiWave(WeaponsPlugin plugin, Player caster, Location origin, Vector dir, double half, double height,
                double range, double damage) {
        this.plugin = plugin;
        this.caster = caster;
        this.world = origin.getWorld();
        this.dir = dir.clone().setY(0).normalize();
        this.right = new Vector(-this.dir.getZ(), 0, this.dir.getX());
        this.half = half;
        this.height = height;
        this.range = range;
        this.damage = damage;
        this.columns = (int) Math.max(3, Math.min(9, Math.round(half / 2.6)));
        this.colY = new double[this.columns];
        this.yaw = (float) Math.toDegrees(Math.atan2(-this.dir.getX(), this.dir.getZ()));
        this.center = origin.clone();
        this.refY = origin.getY();
    }

    void start() {
        double colW = (half * 2) / columns;
        updateColumns();
        for (int layer = 0; layer < LAYER.length; layer++) {
            for (int c = 0; c < columns; c++) {
                final int fl = layer;
                final int fc = c;
                Location l = slotLocation(c, layer, colW);
                BlockDisplay d = world.spawn(l, BlockDisplay.class, bd -> {
                    bd.setBlock(LAYER[fl].createBlockData());
                    bd.setTeleportDuration(STEP_TICKS);
                    bd.setInterpolationDuration(10);
                    bd.setInterpolationDelay(0);
                    bd.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                    bd.setViewRange(2.0f);
                    bd.setPersistent(false);
                    bd.setTransformation(shape(0.05f, colW, fl));
                });
                displays.add(d);
                slots.add(new int[]{fc, fl});
            }
        }
        // grow in (one task for all displays)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int i = 0; i < displays.size(); i++) {
                BlockDisplay d = displays.get(i);
                if (d.isValid()) {
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(10);
                    d.setTransformation(shape(1.0f, colW, slots.get(i)[1]));
                }
            }
        }, 1L);
        Fx.sound(center, Sound.ITEM_TRIDENT_RIPTIDE_3, 2.0f, 0.5f);
        Fx.sound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.6f);

        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!tick()) {
                    stop();
                }
            }
        };
        task.runTaskTimer(plugin, STEP_TICKS, STEP_TICKS);
    }

    private Transformation shape(float grow, double colW, int layer) {
        float sx = (float) (colW + 0.6) * grow;
        float sy = (float) (height * LAYER_H[layer]) * grow;
        float sz = 1.5f * grow;
        return new Transformation(new Vector3f(-sx / 2f, 0f, -sz / 2f), new AxisAngle4f(), new Vector3f(sx, sy, sz), new AxisAngle4f());
    }

    private Location slotLocation(int column, int layer, double colW) {
        double off = -half + colW * (column + 0.5);
        double x = center.getX() + right.getX() * off - dir.getX() * LAYER_BACK[layer];
        double z = center.getZ() + right.getZ() * off - dir.getZ() * LAYER_BACK[layer];
        return new Location(world, x, colY[column], z, yaw, 0f);
    }

    /** Ground height of every column: ONE scan per column per step (not per display), never loading chunks. */
    private void updateColumns() {
        double colW = (half * 2) / columns;
        for (int c = 0; c < columns; c++) {
            double off = -half + colW * (c + 0.5);
            double x = center.getX() + right.getX() * off;
            double z = center.getZ() + right.getZ() * off;
            colY[c] = world.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4) ? surface(x, z, refY) : refY;
        }
    }

    /** Top of the ground (water counts as ground) under (x,z), searching near the reference height. */
    private double surface(double x, double z, double ref) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int top = (int) Math.floor(ref) + 2;
        int bottom = (int) Math.floor(ref) - 4;
        for (int y = top; y >= bottom; y--) {
            Block b = world.getBlockAt(bx, y, bz);
            if (b.isLiquid()) {
                return y + 0.85;
            }
            if (!b.isPassable()) {
                return y + 1.0;
            }
        }
        return ref;
    }

    /** One step of the wave. Returns false when it is over. */
    private boolean tick() {
        if (finished || !caster.isOnline() || caster.getWorld() != world) {
            return false;
        }
        step++;
        Fx.disturbSmoke(center.clone().add(0, 1, 0), Math.min(7.0, half + 2), 1.5);
        // advance, stopping at walls
        double nx = center.getX() + dir.getX() * STEP;
        double nz = center.getZ() + dir.getZ() * STEP;
        if (!world.isChunkLoaded((int) Math.floor(nx) >> 4, (int) Math.floor(nz) >> 4)) {
            return false;
        }
        double ny = surface(nx, nz, refY);
        if (ny - refY > 2.6) {
            return false; // cliff / wall
        }
        center.setX(nx);
        center.setZ(nz);
        refY = ny;
        center.setY(ny);
        travelled += STEP;
        if (travelled >= range) {
            return false;
        }

        updateColumns();
        double colW = (half * 2) / columns;
        for (int i = 0; i < displays.size(); i++) {
            BlockDisplay d = displays.get(i);
            if (!d.isValid()) {
                continue;
            }
            int[] sl = slots.get(i);
            d.teleport(slotLocation(sl[0], sl[1], colW));
        }

        if (step % 3 == 0) {
            Fx.sound(center, Sound.ENTITY_GENERIC_SPLASH, 1.6f, 0.5f);
        }
        bedrockParticles(colW);

        // carry + damage: cheap geometry test first, the (heavier) validity check only for entities actually in the wave
        boolean hurt = step % 3 == 0;
        double reach = Math.max(half, 3.0) + 3.0;
        for (LivingEntity le : world.getNearbyLivingEntities(center, reach, height + 3.0, reach)) {
            Vector rel = le.getLocation().toVector().subtract(center.toVector());
            double along = rel.dot(dir);
            double across = rel.dot(right);
            double up = rel.getY();
            if (along < -3.8 || along > 2.4 || Math.abs(across) > half + 0.5 || up < -2.5 || up > height + 1.5) {
                continue;
            }
            if (!Combat.valid(plugin, caster, le)) {
                continue;
            }
            Vector v = dir.clone().multiply(0.62);
            v.setY(le.isOnGround() ? 0.22 : Math.max(0.05, le.getVelocity().getY()));
            le.setVelocity(v);
            if (le instanceof Player pl) {
                plugin.grantNoFall(pl, 1.5);
            }
            if (hurt) {
                Combat.damage(le, damage, caster);
                le.getWorld().spawnParticle(Particle.SPLASH, le.getLocation().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.2);
            }
        }
        return true;
    }

    private void bedrockParticles(double colW) {
        if (step % 10 == 1 || step == 1) { // refresh the viewer list only now and then
            bedrockViewers = new ArrayList<>();
            for (Player p : world.getPlayers()) {
                if (FlashOverlay.isBedrock(p) && p.getLocation().distanceSquared(center) <= 80 * 80) {
                    bedrockViewers.add(p);
                }
            }
        }
        if (bedrockViewers.isEmpty() && fakeBlocks.isEmpty()) {
            return;
        }
        java.util.Map<Long, int[]> want = bedrockWall();
        org.bukkit.block.data.BlockData water = Material.WATER.createBlockData();
        // viewers that left the list get their blocks restored
        for (java.util.Iterator<java.util.Map.Entry<java.util.UUID, java.util.Map<Long, int[]>>> it = fakeBlocks.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<java.util.UUID, java.util.Map<Long, int[]>> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !bedrockViewers.contains(p)) {
                if (p != null && p.isOnline()) {
                    revert(p, en.getValue(), null);
                }
                it.remove();
            }
        }
        for (Player p : bedrockViewers) {
            if (!p.isOnline()) {
                continue;
            }
            java.util.Map<Long, int[]> mine = fakeBlocks.computeIfAbsent(p.getUniqueId(), k -> new java.util.HashMap<>());
            revert(p, mine, want);
            for (java.util.Map.Entry<Long, int[]> en : want.entrySet()) {
                if (!mine.containsKey(en.getKey())) {
                    int[] c = en.getValue();
                    p.sendBlockChange(new Location(world, c[0], c[1], c[2]), water);
                    mine.put(en.getKey(), c);
                }
            }
        }
        if (step % 2 == 0) {
            for (int c = 0; c < columns; c++) {
                Location l = slotLocation(c, 0, colW).add(0, height, 0);
                for (Player p : bedrockViewers) {
                    p.spawnParticle(Particle.CLOUD, l, 2, colW / 2, 0.3, 0.6, 0.02);
                }
            }
        }
    }

    /** Blocks of the Bedrock wave: one thin wall of water across the front, capped in size to keep packets low. */
    private java.util.Map<Long, int[]> bedrockWall() {
        java.util.Map<Long, int[]> out = new java.util.HashMap<>();
        double hw = Math.min(half, 10.0);
        int hh = (int) Math.max(2, Math.min(height, 5));
        double colW = (half * 2) / columns;
        for (double off = -hw; off <= hw; off += 1.0) {
            double x = center.getX() + right.getX() * off;
            double z = center.getZ() + right.getZ() * off;
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                continue;
            }
            int col = (int) Math.max(0, Math.min(columns - 1, Math.floor((off + half) / colW)));
            int baseY = (int) Math.floor(colY[col]);
            for (int dy = 0; dy < hh; dy++) {
                Block b = world.getBlockAt(bx, baseY + dy, bz);
                if (b.isPassable() && !b.isLiquid()) {
                    out.put(((long) bx & 0x3FFFFFF) << 38 | ((long) bz & 0x3FFFFFF) << 12 | ((long) (baseY + dy) & 0xFFF),
                            new int[]{bx, baseY + dy, bz});
                }
            }
        }
        return out;
    }

    /** Restores the real blocks for every fake position not in {@code keep} (null = restore all). */
    private void revert(Player p, java.util.Map<Long, int[]> mine, java.util.Map<Long, int[]> keep) {
        for (java.util.Iterator<java.util.Map.Entry<Long, int[]>> it = mine.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<Long, int[]> en = it.next();
            if (keep != null && keep.containsKey(en.getKey())) {
                continue;
            }
            int[] c = en.getValue();
            Location l = new Location(world, c[0], c[1], c[2]);
            p.sendBlockChange(l, l.getBlock().getBlockData());
            it.remove();
        }
    }

    private void revertAll() {
        for (java.util.Map.Entry<java.util.UUID, java.util.Map<Long, int[]>> en : fakeBlocks.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null && p.isOnline()) {
                revert(p, en.getValue(), null);
            }
        }
        fakeBlocks.clear();
    }

    void stop() {
        if (finished) {
            return;
        }
        finished = true;
        if (task != null) {
            task.cancel();
        }
        revertAll();
        double colW = (half * 2) / columns;
        // shrink away, then remove
        for (int i = 0; i < displays.size(); i++) {
            BlockDisplay d = displays.get(i);
            if (d.isValid()) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(10);
                d.setTransformation(shape(0.05f, colW, slots.get(i)[1]));
            }
        }
        Fx.burst(center.clone().add(0, 1, 0), Particle.SPLASH, 80, Math.min(half, 6), 0.3);
        Fx.sound(center, Sound.ENTITY_GENERIC_SPLASH, 2.0f, 0.4f);
        Bukkit.getScheduler().runTaskLater(plugin, this::cleanup, 12L);
    }

    void cleanup() {
        for (BlockDisplay d : displays) {
            if (d.isValid()) {
                d.remove();
            }
        }
        displays.clear();
    }

    /** Server shutdown / reload: remove immediately. */
    void kill() {
        finished = true;
        if (task != null) {
            task.cancel();
        }
        revertAll();
        cleanup();
    }

    boolean done() {
        return finished && displays.isEmpty();
    }
}
