package dev.weapons;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Terrain damage. Every block goes through a normal BlockBreakEvent first, so WorldGuard, claim plugins and
 * CoreProtect treat it as if the player broke it. Chests, spawners and other tile entities, bedrock and very
 * hard blocks are never touched, and nothing drops.
 */
final class Terrain {

    private Terrain() {
    }

    static boolean canBreak(Block b, double maxHardness) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid() || m == Material.BEDROCK) {
            return false;
        }
        float h = m.getHardness();
        if (h < 0 || h > maxHardness) {
            return false;
        }
        return !(b.getState(false) instanceof TileState);
    }

    /** Breaks one block if the player is allowed to. */
    static boolean destroy(Player p, Block b, boolean physics, boolean particles) {
        BlockBreakEvent ev = new BlockBreakEvent(b, p);
        ev.setDropItems(false);
        ev.setExpToDrop(0);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) {
            return false;
        }
        if (particles) {
            b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 5, 0.3, 0.3, 0.3, 0.05, b.getBlockData());
        }
        b.setType(Material.AIR, physics);
        return true;
    }

    /** The first solid block at or below the location (up to 6 blocks down). */
    static Block ground(Location loc) {
        Block b = loc.getBlock();
        for (int i = 0; i < 7; i++) {
            if (!b.isPassable() && !b.isLiquid()) {
                return b;
            }
            b = b.getRelative(BlockFace.DOWN);
        }
        return null;
    }

    /** A bowl-shaped crater in the ground at the impact point. Returns how many blocks were removed. */
    static int crater(Player p, Location impact, double radius, int maxBlocks, double maxHardness) {
        Block ground = ground(impact);
        if (ground == null) {
            return 0;
        }
        int r = (int) Math.ceil(radius);
        List<Block> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d2 = dx * dx + dz * dz;
                if (d2 > radius * radius) {
                    continue;
                }
                int depth = (int) Math.floor(Math.sqrt(radius * radius - d2));
                for (int k = 0; k < depth; k++) {
                    Block b = ground.getRelative(dx, -k, dz);
                    if (canBreak(b, maxHardness)) {
                        list.add(b);
                    }
                }
            }
        }
        // Deepest and central blocks first, so a capped crater still looks like a crater
        Location c = ground.getLocation().add(0.5, 0.5, 0.5);
        list.sort(Comparator.comparingDouble(b -> b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(c)));
        int removed = 0;
        for (Block b : list) {
            if (removed >= maxBlocks) {
                break;
            }
            if (destroy(p, b, true, true)) {
                removed++;
            }
        }
        if (removed > 0) {
            ground.getWorld().playSound(c, Sound.BLOCK_GRAVEL_BREAK, SoundCategory.BLOCKS, 2.0f, 0.6f);
            ground.getWorld().playSound(c, Sound.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 2.0f, 0.5f);
        }
        return removed;
    }
}
