package dev.weapons;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Miner's Handy Tool: diamond pickaxe speed on every block (set on the item), vein mining for ores,
 * auto-smelt (skipped with Silk Touch, so you get the full ore block) and Fortune from the tool's own enchantments.
 */
final class MinerToolAbility extends Ability {

    private boolean busy; // true while we fire our own block break events
    private Map<Material, ItemStack> smeltMap;

    MinerToolAbility(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.MINERS_HANDY_TOOL;
    }

    @Override
    void onBreak(Player p, ItemStack item, BlockBreakEvent e) {
        if (busy || p.getGameMode() == GameMode.CREATIVE || !plugin.protection().canUse(p)) {
            return;
        }
        Block origin = e.getBlock();
        if (!isOre(origin.getType())) {
            return;
        }

        boolean silk = item.containsEnchantment(Enchantment.SILK_TOUCH);
        boolean smelt = plugin.getConfig().getBoolean("weapons.miners_handy_tool.autosmelt", true) && !silk;
        boolean needSneak = plugin.getConfig().getBoolean("weapons.miners_handy_tool.vein-require-sneak", false);
        boolean vein = !needSneak || p.isSneaking();
        int max = Math.max(0, plugin.getConfig().getInt("weapons.miners_handy_tool.vein-max-blocks", 64));

        List<Block> extra = vein ? findVein(origin, max) : new ArrayList<>();

        // The block that was broken normally: we only change what it drops.
        if (e.isDropItems()) {
            e.setDropItems(false);
            dropAll(origin.getLocation().add(0.5, 0.5, 0.5), process(origin.getDrops(item, p), smelt));
        }
        if (extra.isEmpty()) {
            return;
        }

        int broken = 0;
        for (Block b : extra) {
            if (durabilityLeft(item) <= 2) {
                break; // never let vein mining destroy the tool
            }
            BlockBreakEvent be = new BlockBreakEvent(b, p);
            busy = true;
            try {
                Bukkit.getPluginManager().callEvent(be);
            } finally {
                busy = false;
            }
            if (be.isCancelled() || !plugin.protection().canBuild(p, b.getLocation())) {
                continue;
            }

            Material mat = b.getType();
            BlockData data = b.getBlockData();
            Collection<ItemStack> drops = be.isDropItems() ? b.getDrops(item, p) : new ArrayList<>();
            Location center = b.getLocation().add(0.5, 0.5, 0.5);
            b.setType(Material.AIR);
            dropAll(center, process(drops, smelt));

            int xp = silk ? 0 : oreXp(mat);
            if (xp > 0) {
                center.getWorld().spawn(center, ExperienceOrb.class, o -> o.setExperience(xp));
            }
            center.getWorld().spawnParticle(Particle.BLOCK, center, 12, 0.3, 0.3, 0.3, data);
            Fx.burst(center, Particle.END_ROD, 2, 0.2, 0.03);
            wear(item);
            broken++;
        }

        if (broken > 0) {
            p.getInventory().setItemInMainHand(item);
            Fx.sound(origin.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 1.2f);
        }
    }

    // ------------------------------------------------------------------ vein search

    private boolean isOre(Material m) {
        String n = m.name();
        if (n.endsWith("_ORE") || m == Material.ANCIENT_DEBRIS) {
            return true;
        }
        for (String extra : plugin.getConfig().getStringList("weapons.miners_handy_tool.extra-vein-blocks")) {
            if (n.equalsIgnoreCase(extra)) {
                return true;
            }
        }
        return false;
    }

    private static String family(Material m) {
        return m.name().replace("DEEPSLATE_", "");
    }

    private List<Block> findVein(Block origin, int max) {
        List<Block> result = new ArrayList<>();
        if (max <= 0) {
            return result;
        }
        String fam = family(origin.getType());
        Set<Block> seen = new HashSet<>();
        seen.add(origin);
        Queue<Block> queue = new ArrayDeque<>();
        queue.add(origin);

        while (!queue.isEmpty() && result.size() < max) {
            Block cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Block n = cur.getRelative(dx, dy, dz);
                        if (seen.contains(n) || !isOre(n.getType()) || !family(n.getType()).equals(fam)) {
                            continue;
                        }
                        seen.add(n);
                        queue.add(n);
                        result.add(n);
                        if (result.size() >= max) {
                            return result;
                        }
                    }
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ drops

    private List<ItemStack> process(Collection<ItemStack> drops, boolean smelt) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : drops) {
            if (smelt) {
                ItemStack result = smeltResult(s.getType());
                if (result != null) {
                    out.add(new ItemStack(result.getType(), s.getAmount() * result.getAmount()));
                    continue;
                }
            }
            out.add(s);
        }
        return out;
    }

    private ItemStack smeltResult(Material in) {
        if (smeltMap == null) {
            smeltMap = new EnumMap<>(Material.class);
            Iterator<Recipe> it = Bukkit.recipeIterator();
            while (it.hasNext()) {
                Recipe r = it.next();
                if (r instanceof FurnaceRecipe fr && fr.getInputChoice() instanceof RecipeChoice.MaterialChoice mc) {
                    for (Material m : mc.getChoices()) {
                        smeltMap.putIfAbsent(m, fr.getResult());
                    }
                }
            }
        }
        return smeltMap.get(in);
    }

    private void dropAll(Location loc, List<ItemStack> stacks) {
        for (ItemStack s : stacks) {
            if (s != null && !s.getType().isAir() && s.getAmount() > 0) {
                loc.getWorld().dropItemNaturally(loc, s);
            }
        }
    }

    private int oreXp(Material m) {
        String n = m.name();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (n.contains("COAL")) {
            return r.nextInt(0, 3);
        }
        if (n.contains("DIAMOND") || n.contains("EMERALD")) {
            return r.nextInt(3, 8);
        }
        if (n.contains("LAPIS") || n.contains("QUARTZ")) {
            return r.nextInt(2, 6);
        }
        if (n.contains("REDSTONE")) {
            return r.nextInt(1, 6);
        }
        if (n.contains("NETHER_GOLD")) {
            return r.nextInt(0, 2);
        }
        return 0;
    }

    // ------------------------------------------------------------------ durability

    private int durabilityLeft(ItemStack tool) {
        if (tool.getItemMeta() instanceof Damageable d) {
            return tool.getType().getMaxDurability() - d.getDamage();
        }
        return Integer.MAX_VALUE;
    }

    /** One point of wear, with Unbreaking. */
    private void wear(ItemStack tool) {
        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) {
            return;
        }
        tool.editMeta(Damageable.class, d -> d.setDamage(d.getDamage() + 1));
    }
}
