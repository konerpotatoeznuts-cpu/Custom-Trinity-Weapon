package dev.weapons;

import io.papermc.paper.event.player.PlayerTradeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trinity Dihrama (magma cream): the pirate token. Right-click it and the whole server is told who used it and where.
 * For 24 real hours (the clock keeps running while they are offline) the user has 20 hearts, Speed III and
 * Strength III, and every villager trade is free. While it is active the player also holds the permission
 * {@code trinity.dihrama.active}, which a shop plugin (the kill shop) can use to make its items free.
 */
final class TrinityDihrama implements Listener {

    static final String PERMISSION = "trinity.dihrama.active";

    private final WeaponsPlugin plugin;
    private final NamespacedKey tag;
    private final NamespacedKey untilKey;
    private final NamespacedKey healthKey;
    private final Map<UUID, PermissionAttachment> perms = new HashMap<>();
    private BukkitTask keeper;

    TrinityDihrama(WeaponsPlugin plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "trinity_dihrama");
        this.untilKey = new NamespacedKey(plugin, "dihrama_until");
        this.healthKey = new NamespacedKey(plugin, "dihrama_health");
    }

    void start() {
        keeper = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                maintain(p);
            }
        }, 40L, 40L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            maintain(p);
        }
    }

    void shutdown() {
        if (keeper != null) {
            keeper.cancel();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            dropPermission(p);
        }
    }

    private long durationMillis() {
        return (long) (plugin.getConfig().getDouble("dihrama.duration-hours", 24.0) * 3_600_000L);
    }

    ItemStack create(int amount) {
        ItemStack it = new ItemStack(Material.MAGMA_CREAM, Math.max(1, Math.min(16, amount)));
        it.editMeta(m -> {
            m.customName(Component.text("Trinity Dihrama", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(
                    Component.text("Right-click to use. The whole server will know.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("20 hearts, Speed III and Strength III", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                    Component.text("Free villager trades and free kill shop items", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                    Component.text("Lasts 24 real hours.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            m.setMaxStackSize(16);
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
        try {
            it.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                    io.papermc.paper.datacomponent.item.CustomModelData.customModelData().addString("trinity_dihrama").build());
        } catch (Throwable ignored) {
            // the Java pack matches on the name
        }
        return it;
    }

    boolean is(ItemStack it) {
        return it != null && it.getType() == Material.MAGMA_CREAM && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    long remainingMillis(Player p) {
        Long until = p.getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    boolean active(Player p) {
        return remainingMillis(p) > 0;
    }

    // ------------------------------------------------------------------ using it

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = e.getItem();
        if (!is(item)) {
            return;
        }
        Player p = e.getPlayer();
        if (active(p)) {
            long min = remainingMillis(p) / 60000;
            p.sendActionBar(Component.text("Trinity Dihrama is already active (" + (min + 1) + " min left).", NamedTextColor.YELLOW));
            return;
        }
        item.setAmount(item.getAmount() - 1);
        p.getPersistentDataContainer().set(untilKey, PersistentDataType.LONG, System.currentTimeMillis() + durationMillis());
        maintain(p);
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) {
            p.setHealth(Math.min(max.getValue(), p.getHealth() + 1000.0)); // full heal
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        Fx.burst(p.getLocation().add(0, 1, 0), org.bukkit.Particle.TOTEM_OF_UNDYING, 40, 0.5, 0.4);

        if (plugin.getConfig().getBoolean("dihrama.announce", true)) {
            Location l = p.getLocation();
            String where = l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
            String dim = switch (l.getWorld().getEnvironment()) {
                case NETHER -> "the Nether";
                case THE_END -> "the End";
                default -> "the Overworld";
            };
            Component msg = Component.text("✦ ", NamedTextColor.GOLD)
                    .append(Component.text(p.getName(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" used a ", NamedTextColor.GOLD))
                    .append(Component.text("Trinity Dihrama", NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text("! Find them at ", NamedTextColor.GOLD))
                    .append(Component.text("X: " + l.getBlockX() + " Y: " + l.getBlockY() + " Z: " + l.getBlockZ(), NamedTextColor.AQUA, TextDecoration.BOLD))
                    .append(Component.text(" in " + dim + ".", NamedTextColor.GOLD));
            Bukkit.broadcast(msg);
            for (Player v : Bukkit.getOnlinePlayers()) {
                v.playSound(v.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.35f, 1.6f);
            }
            plugin.getLogger().info(p.getName() + " used a Trinity Dihrama at " + where + " (" + l.getWorld().getName() + ")");
        }
    }

    // ------------------------------------------------------------------ keeping the effects on

    /** Applies or removes everything the token gives, depending on whether it is still active. */
    void maintain(Player p) {
        long left = remainingMillis(p);
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        AttributeModifier have = null;
        if (max != null) {
            for (AttributeModifier m : max.getModifiers()) {
                if (m.getKey().equals(healthKey)) {
                    have = m;
                }
            }
        }
        if (left <= 0) {
            if (max != null && have != null) {
                max.removeModifier(have);
                if (p.getHealth() > max.getValue()) {
                    p.setHealth(max.getValue());
                }
            }
            if (p.getPersistentDataContainer().has(untilKey, PersistentDataType.LONG)) {
                p.getPersistentDataContainer().remove(untilKey);
                p.removePotionEffect(PotionEffectType.SPEED);
                p.removePotionEffect(PotionEffectType.STRENGTH);
                p.sendActionBar(Component.text("Your Trinity Dihrama has worn off.", NamedTextColor.GRAY));
            }
            dropPermission(p);
            return;
        }
        if (max != null && have == null) {
            max.addModifier(new AttributeModifier(healthKey, plugin.getConfig().getDouble("dihrama.extra-health", 20.0),
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
        int ticks = (int) Math.min(Integer.MAX_VALUE, left / 50L) + 20;
        refresh(p, PotionEffectType.SPEED, (int) plugin.getConfig().getInt("dihrama.speed-level", 3) - 1, ticks);
        refresh(p, PotionEffectType.STRENGTH, (int) plugin.getConfig().getInt("dihrama.strength-level", 3) - 1, ticks);
        grantPermission(p, left);
    }

    private void refresh(Player p, PotionEffectType type, int amp, int ticks) {
        PotionEffect cur = p.getPotionEffect(type);
        if (cur == null || cur.getAmplifier() < amp || cur.getDuration() < ticks - 100) {
            p.addPotionEffect(new PotionEffect(type, ticks, amp, false, false, true));
        }
    }

    private void grantPermission(Player p, long leftMillis) {
        if (perms.containsKey(p.getUniqueId())) {
            return;
        }
        perms.put(p.getUniqueId(), p.addAttachment(plugin, PERMISSION, true));
        if (plugin.getConfig().getBoolean("dihrama.luckperms-permission", true) && Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            // many shop plugins read permissions through LuckPerms, so the node is also set there, expiring by itself
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + p.getName() + " permission settemp " + PERMISSION
                    + " true " + Math.max(1, leftMillis / 1000) + "s replace");
        }
    }

    private void dropPermission(Player p) {
        PermissionAttachment a = perms.remove(p.getUniqueId());
        if (a != null) {
            try {
                p.removeAttachment(a);
            } catch (Throwable ignored) {
                // already gone
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> maintain(e.getPlayer()), 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        dropPermission(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (plugin.getConfig().getBoolean("dihrama.keep-on-death", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> maintain(e.getPlayer()), 5L);
        } else {
            e.getPlayer().getPersistentDataContainer().remove(untilKey);
            Bukkit.getScheduler().runTaskLater(plugin, () -> maintain(e.getPlayer()), 5L);
        }
    }

    // ------------------------------------------------------------------ free villager trades

    /** The trade goes through normally; what it cost is handed straight back, and the trade is never used up. */
    @EventHandler
    public void onTrade(PlayerTradeEvent e) {
        Player p = e.getPlayer();
        if (!active(p) || !plugin.getConfig().getBoolean("dihrama.free-trades", true)) {
            return;
        }
        MerchantRecipe r = e.getTrade();
        final List<ItemStack> refund = new ArrayList<>();
        ItemStack a = r.getAdjustedIngredient1();
        if (a != null && !a.getType().isAir()) {
            refund.add(a.clone());
        }
        List<ItemStack> ing = r.getIngredients();
        if (ing.size() > 1 && ing.get(1) != null && !ing.get(1).getType().isAir()) {
            refund.add(ing.get(1).clone());
        }
        final AbstractVillager v = e.getVillager();
        final ItemStack result = r.getResult().clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (ItemStack back : refund) {
                for (ItemStack left : p.getInventory().addItem(back).values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), left);
                }
            }
            if (v.isValid()) {
                List<MerchantRecipe> all = new ArrayList<>(v.getRecipes());
                for (int i = 0; i < all.size(); i++) {
                    MerchantRecipe mr = all.get(i);
                    if (mr.getUses() > 0 && mr.getResult().isSimilar(result)) {
                        mr.setUses(0); // never sold out
                        v.setRecipe(i, mr);
                    }
                }
            }
        });
    }
}
