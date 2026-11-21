package dev.weapons;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** Damage, healing and target selection shared by all abilities. */
final class Combat {

    private static boolean internal;

    private Combat() {
    }

    /** True while the plugin itself is dealing damage, so ability triggers ignore it. */
    static boolean isInternal() {
        return internal;
    }

    private static double damageMult = 2.5;
    private static double pierceShare = 0.6;
    private static double strengthPerLevel = 0.5;

    /** Reads the damage settings from config (on enable and /weapons reload). */
    static void configure(WeaponsPlugin plugin) {
        damageMult = plugin.getConfig().getDouble("damage.ability-multiplier", 2.5);
        pierceShare = Math.max(0, Math.min(1, plugin.getConfig().getDouble("damage.armor-piercing-share", 0.6)));
        strengthPerLevel = plugin.getConfig().getDouble("damage.strength-multiplier-per-level", 0.5);
    }

    /** Damage multiplier from the player's Strength effect (Strength I = x1.5, II = x2 by default). */
    static double strength(Player p) {
        org.bukkit.potion.PotionEffect e = p.getPotionEffect(org.bukkit.potion.PotionEffectType.STRENGTH);
        return e == null ? 1.0 : 1.0 + (e.getAmplifier() + 1) * strengthPerLevel;
    }

    static double globalMult() {
        return damageMult;
    }

    static void damage(LivingEntity target, double amount, Player source) {
        DamageSource src = DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK)
                .withCausingEntity(source).withDirectEntity(source).build();
        damage(target, amount, src);
    }

    /**
     * Ability damage: multiplied by the global buff, and part of it is armor-piercing (magic) damage so
     * fully enchanted netherite doesn't turn it into a tickle.
     */
    static void damage(LivingEntity target, double amount, DamageSource source) {
        if (source.getCausingEntity() instanceof Player killer) {
            Deaths.record(target, killer, Fx.currentWeapon());
        }
        double total = amount * damageMult;
        double pierce = total * pierceShare;
        double normal = total - pierce;
        boolean prev = internal;
        internal = true;
        try {
            target.setNoDamageTicks(0);
            if (normal > 0) {
                target.damage(normal, source);
            }
            if (pierce > 0 && target.isValid() && !target.isDead()) {
                rawPierce(target, pierce, source);
            }
        } finally {
            internal = prev;
        }
    }

    /** Armor-ignoring (magic) damage credited to the same attacker. No multipliers. */
    static void pierce(LivingEntity target, double amount, Player source) {
        Deaths.record(target, source, Fx.currentWeapon());
        boolean prev = internal;
        internal = true;
        try {
            rawPierce(target, amount, DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK)
                    .withCausingEntity(source).withDirectEntity(source).build());
        } finally {
            internal = prev;
        }
    }

    private static void rawPierce(LivingEntity target, double amount, DamageSource source) {
        if (amount <= 0 || !target.isValid() || target.isDead()) {
            return;
        }
        DamageSource.Builder b = DamageSource.builder(org.bukkit.damage.DamageType.MAGIC);
        if (source.getCausingEntity() != null) {
            b.withCausingEntity(source.getCausingEntity());
        }
        if (source.getDirectEntity() != null) {
            b.withDirectEntity(source.getDirectEntity());
        }
        target.setNoDamageTicks(0);
        target.damage(amount, b.build());
    }

    static void heal(Player p, double amount) {
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20.0 : max.getValue();
        p.setHealth(Math.min(cap, p.getHealth() + amount));
    }

    /** Can {@code caster}'s abilities affect {@code e}? */
    static boolean valid(WeaponsPlugin plugin, Player caster, Entity e) {
        if (!(e instanceof LivingEntity le) || e.equals(caster) || !le.isValid() || le.isDead()) {
            return false;
        }
        if (e instanceof ArmorStand) {
            return false;
        }
        if (e instanceof Player tp) {
            GameMode gm = tp.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
                return false;
            }
            if (!plugin.protection().canHit(caster, tp)) {
                return false;
            }
        }
        if (e instanceof Tameable t && t.isTamed() && caster.getUniqueId().equals(t.getOwnerUniqueId())) {
            return false;
        }
        String me = caster.getUniqueId().toString();
        String owner = e.getPersistentDataContainer().get(plugin.ownerKey(), PersistentDataType.STRING);
        String tamed = e.getPersistentDataContainer().get(plugin.tamedKey(), PersistentDataType.STRING);
        return !me.equals(owner) && !me.equals(tamed);
    }

    static List<LivingEntity> nearby(WeaponsPlugin plugin, Player caster, Location center, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        if (center.getWorld() == null) {
            return out;
        }
        for (LivingEntity le : center.getWorld().getNearbyLivingEntities(center, radius)) {
            if (valid(plugin, caster, le)) {
                out.add(le);
            }
        }
        return out;
    }
}
