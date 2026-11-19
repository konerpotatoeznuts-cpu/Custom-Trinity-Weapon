package dev.weapons;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Flashbang aftermath: heavy Slowness and no jumping for a while.
 * Jumping is removed with a TRANSIENT jump-strength modifier (never saved to disk, so a crash can't leave anyone stuck).
 */
final class Immobilize {

    private record Entry(AttributeModifier modifier, BukkitTask task) {
    }

    private final WeaponsPlugin plugin;
    private final NamespacedKey key;
    private final Map<UUID, Entry> active = new HashMap<>();

    Immobilize(WeaponsPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "flash_no_jump");
    }

    void apply(LivingEntity e, int ticks, int slownessLevel) {
        UUID id = e.getUniqueId();
        release(e);

        if (slownessLevel > 0) {
            e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, slownessLevel - 1, false, true, true));
        }
        AttributeInstance jump = e.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump == null) {
            return;
        }
        AttributeModifier mod = new AttributeModifier(key, -1.0, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY);
        try {
            jump.addTransientModifier(mod);
        } catch (Throwable t) {
            return; // already present or not supported: slowness alone still applies
        }
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                release(e);
            }
        }.runTaskLater(plugin, ticks);
        active.put(id, new Entry(mod, task));
    }

    private void release(LivingEntity e) {
        Entry old = active.remove(e.getUniqueId());
        if (old == null) {
            return;
        }
        old.task().cancel();
        AttributeInstance jump = e.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) {
            try {
                jump.removeModifier(old.modifier());
            } catch (Throwable ignored) {
                // entity gone
            }
        }
    }

    void shutdown() {
        for (UUID id : new java.util.ArrayList<>(active.keySet())) {
            org.bukkit.entity.Entity en = plugin.getServer().getEntity(id);
            if (en instanceof LivingEntity le) {
                release(le);
            }
        }
        active.clear();
    }
}
