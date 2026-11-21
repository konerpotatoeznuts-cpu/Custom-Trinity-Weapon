package dev.weapons;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.Tool;
import io.papermc.paper.datacomponent.item.UseCooldown;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.set.RegistryKeySet;
import io.papermc.paper.registry.set.RegistrySet;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.util.TriState;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Creates and recognises the weapons.
 * The item NAME is exactly the weapon name (plain text, same as an anvil rename) so your texture pack's
 * name-based item model definitions match. The ability is tied to a hidden tag, not the name.
 */
final class WeaponItems {

    private final WeaponsPlugin plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey ammoKey;

    WeaponItems(WeaponsPlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "weapon");
        this.ammoKey = new NamespacedKey(plugin, "ammo");
    }

    // ------------------------------------------------------------------ identify

    WeaponType identify(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return id == null ? null : WeaponType.byId(id);
    }

    // ------------------------------------------------------------------ create

    ItemStack create(WeaponType t) {
        ItemStack item = new ItemStack(resolveMaterial(t));

        item.editMeta(meta -> {
            meta.customName(Component.text(t.displayName()));
            meta.lore(lore(t, t == WeaponType.MEDUSAS_REVOLVER ? magazine() : null));
            meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, t.id());

            if (t.unbreakable()) {
                meta.setUnbreakable(true);
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            }

            switch (t) {
                case DURANDAL -> {
                    attack(meta, "durandal", n(t, "attack-damage", 7.0) - 1.0, -2.4);
                    secret(meta, Enchantment.FIRE_ASPECT, 2, Enchantment.SHARPNESS, 1);
                }
                case ELDRITCH_MACE -> secret(meta, Enchantment.WIND_BURST, 2, Enchantment.DENSITY, 5,
                        Enchantment.BREACH, 2);
                case HALBERD_OF_DESPAIR -> {
                    // hidden Lunge III (looked up by key so it also builds if the constant is named differently)
                    Enchantment lunge = org.bukkit.Registry.ENCHANTMENT.get(NamespacedKey.minecraft("lunge"));
                    if (lunge != null) {
                        secret(meta, lunge, 3);
                    }
                }
                case PRISMATIC_BLADE -> attack(meta, "prismatic", n(t, "attack-damage", 9.0) - 1.0, n(t, "attack-speed-modifier", -3.0));
                case WARDEN_WHIP -> {
                    attack(meta, "whip", 3.0, -2.4);
                    meta.addAttributeModifier(Attribute.ENTITY_INTERACTION_RANGE, new AttributeModifier(
                            new NamespacedKey(plugin, "whip_reach"), n(t, "attack-range", 4.5) - 3.0,
                            AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
                    secret(meta, Enchantment.KNOCKBACK, 2, Enchantment.UNBREAKING, 10);
                }
                case EXCALIBUR -> secret(meta, Enchantment.FIRE_ASPECT, 2, Enchantment.SHARPNESS, 1);
                case SONIC_SHOES -> {
                }
                case GREAT_AXE -> attack(meta, "great_axe", n(t, "attack-damage", 11.0) - 1.0, n(t, "attack-speed-modifier", -3.3));
                case SEA_TRUNCHEON -> secret(meta, Enchantment.SHARPNESS, 5, Enchantment.IMPALING, 5,
                        Enchantment.MENDING, 1, Enchantment.UNBREAKING, 5);
                case MEDUSAS_REVOLVER -> {
                    meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.INTEGER, magazine());
                    secret(meta, Enchantment.UNBREAKING, 3);
                }
                default -> {
                }
            }
        });

        // Own cooldown group per weapon, so the white hotbar bar is separate for each weapon.
        try {
            item.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(1.0f)
                    .cooldownGroup(Key.key("customweapons", t.id()))
                    .build());
        } catch (Throwable ex) {
            plugin.getLogger().warning("Could not set the cooldown group on " + t.displayName() + ": " + ex.getMessage());
        }

        // Lets Geyser (Bedrock) pick the right texture. Java packs that match on the item name are not affected.
        try {
            item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addString(t.id()).build());
        } catch (Throwable ex) {
            plugin.getLogger().warning("Could not set custom model data on " + t.displayName() + ": " + ex.getMessage());
        }

        if (t == WeaponType.MINERS_HANDY_TOOL) {
            try {
                item.setData(DataComponentTypes.TOOL, miningTool());
            } catch (Throwable ex) {
                plugin.getLogger().warning("Could not set the mining speed component: " + ex.getMessage());
            }
        }
        return item;
    }

    /** A custom attribute list replaces the vanilla one, so damage AND speed are both set here. */
    private void attack(ItemMeta meta, String name, double damageBonus, double speedModifier) {
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                new NamespacedKey(plugin, name + "_damage"), damageBonus,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                new NamespacedKey(plugin, name + "_speed"), speedModifier,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
    }

    /** Enchantments that work but are completely hidden: no glint and no tooltip lines. Pairs of (enchantment, level). */
    private static void secret(ItemMeta meta, Object... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            int level = (Integer) pairs[i + 1];
            if (level > 0) {
                meta.addEnchant((Enchantment) pairs[i], level, true);
            }
        }
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.setEnchantmentGlintOverride(false);
    }

    /** Diamond pickaxe speed on every block, and every block drops normally. */
    private Tool miningTool() {
        float speed = (float) plugin.getConfig().getDouble("weapons.miners_handy_tool.mining-speed", 8.0);
        RegistryKeySet<BlockType> all = RegistrySet.keySetFromValues(
                RegistryKey.BLOCK, RegistryAccess.registryAccess().getRegistry(RegistryKey.BLOCK));
        return Tool.tool()
                .defaultMiningSpeed(speed)
                .damagePerBlock(1)
                .addRule(Tool.rule(all, speed, TriState.TRUE))
                .build();
    }

    private Material resolveMaterial(WeaponType t) {
        String name = plugin.getConfig().getString("weapons." + t.id() + ".material", t.defaultMaterial());
        Material m = Material.matchMaterial(name);
        if (m == null || !m.isItem()) {
            plugin.getLogger().warning("Unknown material '" + name + "' for " + t.displayName() + ", using " + t.defaultMaterial());
            m = Material.matchMaterial(t.defaultMaterial());
        }
        if (m == null) {
            // e.g. a server without spears
            m = t == WeaponType.HALBERD_OF_DESPAIR ? Material.NETHERITE_SWORD : Material.IRON_SWORD;
            plugin.getLogger().warning(t.defaultMaterial() + " does not exist on this server, using " + m.name());
        }
        return m;
    }

    // ------------------------------------------------------------------ revolver ammo

    int magazine() {
        return Math.max(1, plugin.getConfig().getInt("weapons.medusas_revolver.magazine", 6));
    }

    int getAmmo(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        Integer v = item.getItemMeta().getPersistentDataContainer().get(ammoKey, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    void setAmmo(ItemStack item, int ammo) {
        int clamped = Math.max(0, Math.min(magazine(), ammo));
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.INTEGER, clamped);
            meta.lore(lore(WeaponType.MEDUSAS_REVOLVER, clamped));
        });
    }

    // ------------------------------------------------------------------ lore

    private double n(WeaponType t, String path, double def) {
        return plugin.getConfig().getDouble("weapons." + t.id() + "." + path, def);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    private static Component line(String title, String desc) {
        return Component.text(title + ": ", NamedTextColor.GOLD)
                .append(Component.text(desc, NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false);
    }

    private List<Component> lore(WeaponType t, Integer ammo) {
        List<Component> l = new ArrayList<>();
        switch (t) {
            case DURANDAL -> {
                l.add(line("Lifesteal Strike", "On hit: heal " + fmt(n(t, "lifesteal-hearts", 2))
                        + " hearts and call down lightning. " + fmt(n(t, "cooldown", 15)) + "s cooldown."));
                l.add(line("Heavenly Beam", "Right-click: a beam of light falls where you aim (up to " + fmt(n(t, "beam-range", 100))
                        + " blocks). It burns and stuns everything in " + fmt(n(t, "beam-radius", 10)) + " blocks for "
                        + fmt(n(t, "beam-seconds", 5)) + "s and shatters the blocks above. " + fmt(n(t, "beam-cooldown", 60)) + "s cooldown."));
            }
            case ELDRITCH_MACE -> {
                l.add(line("Shockwave", "Smash attacks have a " + fmt(n(t, "proc-chance", 0.4) * 100) + "% chance of a shockwave and a crater."));
                l.add(line("Dash", "Right-click: dash where you look. " + fmt(n(t, "dash-cooldown", 20)) + "s cooldown."));
                l.add(line("Pull", "Sneak + right-click: pull entities to you. " + fmt(n(t, "pull-cooldown", 60)) + "s cooldown."));
            }
            case HALBERD_OF_DESPAIR -> {
                l.add(line("Despair", "On hit: scramble the target's hotbar, stun them and summon "
                        + fmt(n(t, "minions", 4)) + " wither skeletons. " + fmt(n(t, "cooldown", 40)) + "s cooldown."));
                l.add(line("Shield Breaker", "Hits disable shields like an axe."));
                l.add(line("Reaping Spin", "Sneak + right-click: spin for " + fmt(n(t, "spin-seconds", 5)) + "s, withering and dragging in everything within "
                        + fmt(n(t, "spin-radius", 4.5)) + " blocks. Stops if you stop sneaking. " + fmt(n(t, "spin-cooldown", 30)) + "s cooldown."));
            }
            case PRISMATIC_BLADE -> {
                l.add(line("Flashbang", "Hold sneak to charge, then right-click to blind and cripple (very slow, no jumping) everything within "
                        + fmt(n(t, "radius", 10)) + " blocks (players see a white flash). " + fmt(n(t, "cooldown", 15)) + "s cooldown."));
                l.add(line("Heavy", "Hits hard but slowly, and slows you down while held."));
            }
            case WARDEN_WHIP -> l.add(line("Sonic Boom", "Right-click: unleash a sonic boom. " + fmt(n(t, "cooldown", 30)) + "s cooldown. Long reach."));
            case MEDUSAS_REVOLVER -> {
                l.add(line("Revolver", "Right-click to fire. Sneak + right-click (or F) to reload from your arrows (1s to 5s, fewer bullets load faster)."));
                l.add(line("Medusa's Gaze", "A hit may poison or petrify the target. " + fmt(n(t, "cooldown", 20)) + "s cooldown."));
                if (ammo != null) {
                    l.add(Component.text("Ammo: " + ammo + "/" + magazine(), NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false));
                }
            }
            case MINERS_HANDY_TOOL -> {
                l.add(line("Handy Tool", "Mines every block at diamond pickaxe speed."));
                l.add(line("Vein Miner", "Mines whole ore veins at once."));
                l.add(line("Auto-Smelt", "Smelts ore drops (not with Silk Touch). Fortune applies."));
            }
            case EXCALIBUR -> {
                l.add(line("Slash Barrage", "Sneak + right-click: dash " + fmt(n(t, "dash-distance", 7))
                        + " blocks in a barrage of slashes. " + fmt(n(t, "cooldown", 15)) + "s cooldown."));
                l.add(line("Rising Slash", "Right-click: an uppercut that launches enemies skyward. " + fmt(n(t, "uppercut-cooldown", 15)) + "s cooldown."));
            }
            case FANTASY_TAMER -> {
                l.add(line("Tame", "Hit a wild mob to start taming: time your hits to the shrinking ring (tougher mobs take more rounds). Tamed mobs fight for you."));
                l.add(line("Sit", "Right-click your tamed mob to make it sit or follow."));
                l.add(line("Recall", "Hold sneak to charge, then right-click: all tamed mobs teleport around you. "
                        + fmt(n(t, "recall-cooldown", 60)) + "s cooldown."));
            }
            case SONIC_SHOES -> {
                l.add(line("Gotta Go Fast", "Run to build momentum (faster downhill, slower uphill). Auto-steps blocks. At speed: run on water, up walls and across ceilings, until you slow down."));
                l.add(line("Spin Dash", "Sneak and right-click (hold any item) to rev, up to 5 times. Stop sneaking to launch. Sneak while running fast to roll. Jumping spins: land on a mob to hurt it and bounce."));
            }
            case CRYO_TEMPEST -> {
                l.add(line("Frost Arrows", "Every arrow is icy: it slows and freezes what it hits."));
                l.add(line("Arrow Rain", "Shoot while sneaking (aim up): the arrow flies on. Let go of sneak and frost arrows rain where the arrow is. "
                        + fmt(n(t, "rain-cooldown", 25)) + "s cooldown."));
                l.add(line("Pilot", "Shoot, then sneak twice while the arrow flies: you steer it where you look. Explodes on the first hit. A harder draw means faster, stronger, bigger blast and easier to turn. "
                        + fmt(n(t, "pilot-cooldown", 12)) + "s cooldown."));
            }
            case AK47 -> {
                l.add(line("Full Auto", "Hold right-click: 600 rounds per minute, 30 round magazine. Counter-Strike 2 spray pattern: the first shots are accurate, then the gun climbs and sways. Moving and jumping spoil your aim."));
                l.add(line("Hitgroups", "Headshots do 4x. Bullets go through thin things (glass, leaves, wood) for less damage. Reload: F or right-click when empty (uses arrows)."));
            }
            case ROCKET_LAUNCHER -> {
                l.add(line("Rocket", "Right-click: fire one rocket. It explodes on impact, hurts everything within about 9 blocks (less with distance, nothing behind walls) and throws you around too."));
                l.add(line("Reload", "F or right-click when empty. Uses a firework rocket as ammo."));
            }
            case SMOKE_GRENADE -> {
                l.add(line("Throw", "Right-click: a full throw, like Counter-Strike 2. Sneak + right-click: a low throw. It bounces off walls, floors and mobs."));
                l.add(line("Smoke", "Pops when it stops moving: a thick cloud that fills the room (never through walls) for " + fmt(n(t, "smoke-seconds", 18)) + "s and puts out fires."));
            }
            case SEA_TRUNCHEON -> {
                l.add(line("Riptide", "Right-click: riptide dash, no water needed. Crash into a mob for a mace-style smash. Dolphin's Grace II while in water."));
                l.add(line("Tsunami", "Hold sneak to charge (" + fmt(n(t, "charge-max-seconds", 10)) + "s, more in rain or a lake), then right-click: a huge wave rolls where you aim, dragging and drowning everything. "
                        + fmt(n(t, "tsunami-cooldown", 45)) + "s cooldown."));
            }
            case GREAT_AXE -> {
                l.add(line("Chain Throw", "Right-click: throw the axe. Hit nothing and you grapple to it ("
                        + fmt(n(t, "grapple-cooldown", 20)) + "s). Hit a target and it is dragged to you ("
                        + fmt(n(t, "hit-cooldown", 25)) + "s)."));
                l.add(line("Heavy", "Hits very hard but slowly, and slows you down while held."));
            }
        }
        return l;
    }
}
