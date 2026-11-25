package dev.weapons;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Cross-play PvP: the rules follow the ATTACKER's platform.
 * <ul>
 *   <li>Bedrock attackers fight with Bedrock rules: no attack cooldown (spam clicking deals full damage), no sweeping attacks,
 *       and Bedrock-style knockback (a fixed push, no sprint-hit bonus).</li>
 *   <li>Java attackers keep vanilla Java PvP (cooldown, sweeping, sprint knockback, critical hits).</li>
 * </ul>
 * Platform comes from the Floodgate UUID, same as everywhere else in this plugin. The custom weapons are linked in: a Bedrock
 * player swinging a custom weapon gets full-damage hits without waiting for the cooldown, and the custom weapon's own knockback
 * and abilities are left alone. Everything is under {@code pvp:} in config.yml.
 */
final class CrossPvp implements Listener {

    private final WeaponsPlugin plugin;
    private final NamespacedKey speedKey;
    private BukkitTask keeper;

    private static CrossPvp instance;

    /**
     * Bedrock nerf factor for something this entity does ("ability-damage-multiplier", "cooldown-multiplier"...).
     * 1.0 for Java players, for non-players, and (when {@code against} is not a player and only-vs-players is on) for PvE.
     */
    static double factor(org.bukkit.entity.Entity who, org.bukkit.entity.Entity against, String key, double def) {
        CrossPvp c = instance;
        if (c == null || !(who instanceof Player p) || !c.on() || !c.bedrockRules(p)) {
            return 1.0;
        }
        if (against != null && !(against instanceof Player) && c.plugin.getConfig().getBoolean("pvp.bedrock.only-vs-players", true)) {
            return 1.0;
        }
        return c.plugin.getConfig().getDouble("pvp.bedrock." + key, def);
    }

    CrossPvp(WeaponsPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        this.speedKey = new NamespacedKey(plugin, "bedrock_attack_speed");
    }

    void start() {
        // keeps the "no cooldown" modifier on every Bedrock player (respawns and world changes can drop it)
        keeper = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                sync(p);
            }
        }, 20L, 40L);
    }

    void shutdown() {
        if (keeper != null) {
            keeper.cancel();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            setNoCooldown(p, false);
        }
    }

    private boolean on() {
        return plugin.getConfig().getBoolean("pvp.enabled", true);
    }

    /** Which rules apply to this attacker: "bedrock" or "java". */
    boolean bedrockRules(Player p) {
        String mode = plugin.getConfig().getString("pvp.mode", "auto");
        return switch (mode.toLowerCase()) {
            case "bedrock" -> true;
            case "java" -> false;
            default -> FlashOverlay.isBedrock(p);
        };
    }

    private void sync(Player p) {
        setNoCooldown(p, on() && bedrockRules(p) && plugin.getConfig().getBoolean("pvp.bedrock.no-attack-cooldown", true));
    }

    private void setNoCooldown(Player p, boolean want) {
        AttributeInstance a = p.getAttribute(Attribute.ATTACK_SPEED);
        if (a == null) {
            return;
        }
        AttributeModifier have = null;
        for (AttributeModifier m : a.getModifiers()) {
            if (m.getKey().equals(speedKey)) {
                have = m;
            }
        }
        if (want && have == null) {
            // the attack timer refills instantly, so every swing is a full-strength swing
            a.addTransientModifier(new AttributeModifier(speedKey, 1000.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        } else if (!want && have != null) {
            a.removeModifier(have);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> sync(e.getPlayer()), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastHit.remove(e.getPlayer().getUniqueId());
        setNoCooldown(e.getPlayer(), false);
    }

    // ------------------------------------------------------------------ the fight itself

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHitEarly(EntityDamageByEntityEvent e) {
        if (!on() || Combat.isInternal() || !(e.getDamager() instanceof Player p) || !bedrockRules(p)) {
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                && plugin.getConfig().getBoolean("pvp.bedrock.no-sweep", true)) {
            e.setCancelled(true); // Bedrock has no sweeping edge
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            // Bedrock has no attack cooldown, so clicking is not limited: a hit inside the victim's damage-immunity window
            // is simply ignored, exactly as the Java swing timer would have prevented it.
            if (plugin.getConfig().getBoolean("pvp.bedrock.respect-immunity", true) && e.getEntity() instanceof LivingEntity v
                    && v.getNoDamageTicks() > v.getMaximumNoDamageTicks() / 2.0f) {
                e.setCancelled(true);
                return;
            }
            balance(p, e);
        }
    }

    /** Arrows, tridents and other projectiles shot by a Bedrock player. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onProjectile(EntityDamageByEntityEvent e) {
        if (!on() || Combat.isInternal() || !(e.getDamager() instanceof org.bukkit.entity.Projectile pr)
                || !(pr.getShooter() instanceof Player p)) {
            return;
        }
        double f = factor(p, e.getEntity(), "projectile-multiplier", 0.85);
        if (f != 1.0) {
            e.setDamage(e.getDamage() * f);
        }
    }

    // ------------------------------------------------------------------ keeping Bedrock fighters fair against Java

    private final java.util.Map<java.util.UUID, Integer> lastHit = new java.util.HashMap<>();

    /**
     * Bedrock has no attack cooldown and its axes hit one point below the matching sword, so to keep fights fair:
     * 1) an axe deals (sword damage - 1) instead of Java's 9, and
     * 2) hitting faster than the weapon's Java swing time scales the damage down (never below a floor), so spam clicking
     *    deals about what a well-timed Java player deals (a sword spammed at the 10-tick hit gap does 80%, which is exactly
     *    its Java damage per second). A well-timed hit is always full damage.
     */
    private void balance(Player p, EntityDamageByEntityEvent e) {
        double f = plugin.getConfig().getDouble("pvp.bedrock.damage-multiplier", 0.9);
        ItemStack item = p.getInventory().getItemInMainHand();
        boolean pvp = e.getEntity() instanceof Player;
        if (!pvp && plugin.getConfig().getBoolean("pvp.bedrock.only-vs-players", true)) {
            return; // against mobs Bedrock players fight at full strength
        }
        if (plugin.items().identify(item) != null) {
            // custom weapons: their own damage numbers, nerfed for Bedrock, with the same anti-spam rule as a sword
            f *= plugin.getConfig().getDouble("pvp.bedrock.weapon-melee-multiplier", 0.6);
            if (plugin.getConfig().getBoolean("pvp.bedrock.spam-balance", true) && e.getEntity() instanceof LivingEntity victim
                    && victim.getNoDamageTicks() <= victim.getMaximumNoDamageTicks() / 2.0f) {
                int now = Bukkit.getCurrentTick();
                Integer last = lastHit.put(p.getUniqueId(), now);
                if (last != null) {
                    f *= Math.max(plugin.getConfig().getDouble("pvp.bedrock.spam-floor", 0.6), Math.min(1.0, (now - last) / 12.5));
                }
            }
        } else {
            String n = item.getType().name();
            if (n.endsWith("_AXE") && plugin.getConfig().getBoolean("pvp.bedrock.axe-one-behind-sword", true)) {
                f *= axeRatio(n);
            }
            if (plugin.getConfig().getBoolean("pvp.bedrock.spam-balance", true) && e.getEntity() instanceof LivingEntity victim) {
                // a hit inside the victim's invulnerability window does nothing anyway, so it does not count
                boolean counts = victim.getNoDamageTicks() <= victim.getMaximumNoDamageTicks() / 2.0f;
                if (counts) {
                    int now = Bukkit.getCurrentTick();
                    Integer last = lastHit.put(p.getUniqueId(), now);
                    if (last != null) {
                        double floor = plugin.getConfig().getDouble("pvp.bedrock.spam-floor", 0.6);
                        f *= Math.max(floor, Math.min(1.0, (now - last) / swingTicks(n)));
                    }
                }
            }
        }
        if (f != 1.0) {
            e.setDamage(e.getDamage() * f);
        }
    }

    /** Bedrock axe damage over Java axe damage for the same tier: sword - 1 against 7 / 9 / 10. */
    private static double axeRatio(String name) {
        double sword;
        double axe;
        if (name.startsWith("NETHERITE")) {
            sword = 8;
            axe = 10;
        } else if (name.startsWith("DIAMOND")) {
            sword = 7;
            axe = 9;
        } else if (name.startsWith("IRON")) {
            sword = 6;
            axe = 9;
        } else if (name.startsWith("STONE") || name.startsWith("COPPER")) {
            sword = 5;
            axe = 9;
        } else { // wood, gold
            sword = 4;
            axe = 7;
        }
        return (sword - 1) / axe;
    }

    /** How long Java makes you wait between full-strength swings with this item, in ticks. */
    private static double swingTicks(String name) {
        double speed;
        if (name.endsWith("_SWORD")) {
            speed = 1.6;
        } else if (name.endsWith("_AXE")) {
            speed = name.startsWith("WOODEN") || name.startsWith("STONE") ? 0.8 : name.startsWith("IRON") ? 0.9 : 1.0;
        } else if (name.endsWith("_PICKAXE")) {
            speed = 1.2;
        } else if (name.endsWith("_SHOVEL")) {
            speed = 1.0;
        } else if (name.endsWith("_HOE")) {
            speed = name.startsWith("WOODEN") || name.startsWith("GOLDEN") ? 1.0 : name.startsWith("STONE") ? 2.0 : name.startsWith("IRON") ? 3.0 : 4.0;
        } else if (name.equals("TRIDENT")) {
            speed = 1.1;
        } else if (name.equals("MACE")) {
            speed = 0.6;
        } else {
            speed = 4.0; // fists and everything else
        }
        return 20.0 / speed;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitLate(EntityDamageByEntityEvent e) {
        if (!on() || Combat.isInternal() || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity victim)
                || !bedrockRules(p) || !plugin.getConfig().getBoolean("pvp.bedrock.knockback.enabled", true)) {
            return;
        }
        ItemStack item = p.getInventory().getItemInMainHand();
        if (plugin.items().identify(item) != null) {
            return; // custom weapons keep their own knockback
        }
        final boolean grounded = victim.isOnGround();
        final int lvl = item.getEnchantmentLevel(Enchantment.KNOCKBACK);
        // one tick later, after vanilla has applied its (Java) knockback, replace it with the Bedrock push
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!victim.isValid() || victim.isDead() || !p.isOnline()) {
                return;
            }
            Vector dir = victim.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (dir.lengthSquared() < 1.0E-6) {
                dir = p.getLocation().getDirection().setY(0);
            }
            dir.normalize();
            double h = plugin.getConfig().getDouble("pvp.bedrock.knockback.horizontal", 0.42)
                    + lvl * plugin.getConfig().getDouble("pvp.bedrock.knockback.per-enchant-level", 0.45);
            AttributeInstance res = victim.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (res != null) {
                h *= Math.max(0.0, 1.0 - res.getValue());
            }
            Vector v = victim.getVelocity();
            double y = grounded ? plugin.getConfig().getDouble("pvp.bedrock.knockback.vertical", 0.36) : Math.min(v.getY(), 0.2);
            victim.setVelocity(new Vector(dir.getX() * h, y, dir.getZ() * h));
        });
    }
}
