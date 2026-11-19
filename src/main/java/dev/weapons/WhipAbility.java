package dev.weapons;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.LinkedHashSet;
import java.util.Set;

/** Warden Whip: right-click fires a sonic boom beam that ignores armor, like the Warden's. */
final class WhipAbility extends Ability {

    WhipAbility(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.WARDEN_WHIP;
    }

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        if (!allowed(p)) {
            return;
        }
        if (!cooldown(p, "boom", num("cooldown", 30), "Sonic Boom")) {
            return;
        }
        plugin.cooldowns().overlay(p, type());

        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = eye.getWorld();
        double range = num("range", 16.0);

        RayTraceResult rt = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double len = rt == null ? range : eye.toVector().distance(rt.getHitPosition());

        Set<LivingEntity> hit = new LinkedHashSet<>();
        for (double d = 1.0; d <= len; d += 1.0) {
            Location pt = eye.clone().add(dir.clone().multiply(d));
            if (((int) d) % 2 == 0) {
                Fx.burst(pt, Particle.SONIC_BOOM, 1, 0, 0);
            }
            Fx.burst(pt, Particle.SCULK_SOUL, 2, 0.15, 0.02);
            Fx.burst(pt, Particle.ELECTRIC_SPARK, 2, 0.2, 0.05);
            hit.addAll(Combat.nearby(plugin, p, pt, 1.4));
        }

        DamageSource source = DamageSource.builder(DamageType.SONIC_BOOM)
                .withCausingEntity(p)
                .withDirectEntity(p)
                .build();
        double damage = num("damage", 10.0);
        for (LivingEntity le : hit) {
            Combat.damage(le, damage * Combat.strength(p), source);
            le.setVelocity(dir.clone().multiply(1.6).setY(0.4));
            Fx.burst(le.getLocation().add(0, 1, 0), Particle.SONIC_BOOM, 1, 0, 0);
        }

        Fx.sound(eye, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 1.2f);
        Fx.shockwave(eye.clone().add(eye.getDirection().multiply(1.5)), org.bukkit.Color.fromRGB(20, 150, 160), 3.0, 8);
        Fx.burst(eye.clone().add(eye.getDirection().multiply(1.0)), Particle.SCULK_SOUL, 8, 0.2, 0.05);
        Fx.sound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0f, 1.0f);
    }
}
