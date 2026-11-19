package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fantasy Tamer.
 * Right-click a mob: tame it (tougher mobs need more clicks). Right-click your own mob: sit / follow.
 * Hold sneak to charge, then right-click: every tamed mob teleports around you.
 */
final class TamerAbility extends Ability {

    private final TamerManager manager;
    /** When each player started charging (millis), only while sneaking with the tamer. */
    private final Map<UUID, Long> chargeStart = new HashMap<>();
    private final Map<UUID, Boolean> chargedSound = new HashMap<>();

    TamerAbility(WeaponsPlugin plugin, TamerManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override
    WeaponType type() {
        return WeaponType.FANTASY_TAMER;
    }

    // ------------------------------------------------------------------ charge display

    @Override
    void tick(Player p, ItemStack held) {
        UUID id = p.getUniqueId();
        if (held == null || !p.isSneaking() || manager.count(id) == 0
                || !plugin.cooldowns().ready(p, type(), "recall")) {
            if (chargeStart.remove(id) != null) {
                chargedSound.remove(id);
            }
            return;
        }
        long now = System.currentTimeMillis();
        long start = chargeStart.computeIfAbsent(id, k -> now);
        double need = manager.chargeSeconds(id);
        double have = (now - start) / 1000.0;
        double frac = Math.min(1.0, have / need);

        // spiral that tightens as the charge builds
        Location base = p.getLocation();
        double t = now / 120.0;
        for (int i = 0; i < 3; i++) {
            double a = t + i * (Math.PI * 2 / 3);
            double r = 1.6 - frac * 0.9;
            double y = 0.1 + ((now / 40.0 + i * 8) % 20) / 10.0;
            Fx.dust(base.clone().add(Math.cos(a) * r, y, Math.sin(a) * r), Fx.rainbow(frac * 0.3 + 0.7 + i * 0.03), 1.1f, 1, 0.0);
        }
        if (frac >= 1.0) {
            Fx.burst(base.clone().add(0, 1.0, 0), Particle.END_ROD, 2, 0.5, 0.05);
            if (chargedSound.put(id, true) == null) {
                Fx.sound(base, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 1.6f);
                Fx.sound(base, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.8f);
            }
            p.sendActionBar(Component.text("Charged! Right-click to recall your mobs", NamedTextColor.LIGHT_PURPLE));
        } else {
            int filled = (int) Math.round(frac * 20);
            p.sendActionBar(Component.text("Charging recall  " + "█".repeat(filled) + "░".repeat(20 - filled), NamedTextColor.AQUA));
            if (((int) (have * 10)) % 5 == 0) {
                Fx.sound(base, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.5f, 0.6f + (float) frac);
            }
        }
    }

    // ------------------------------------------------------------------ right-click in the air or on a block

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        // Right-clicking a mob also fires this event right after the mob click, so no message here (it would hide the progress).
        if (p.isSneaking() && !plugin.qte().active(p)) {
            recall(p);
        }
    }

    // ------------------------------------------------------------------ right-click on a mob

    @Override
    void onInteractEntity(Player p, Entity target, ItemStack item, PlayerInteractEntityEvent e) {
        // Taming is done by HITTING a mob (see TameQte). Right-click is only for sit/follow on your own mobs.
        if (!(target instanceof Mob m) || plugin.qte().active(p)) {
            return;
        }
        UUID owner = manager.ownerOf(m);
        if (owner != null && owner.equals(p.getUniqueId())) {
            e.setCancelled(true);
            if (allowed(p)) {
                manager.toggleSit(p, m);
            }
        } else if (p.isSneaking()) {
            e.setCancelled(true);
            recall(p);
        }
    }

    // ------------------------------------------------------------------ recall

    private void recall(Player p) {
        if (!allowed(p)) {
            return;
        }
        UUID id = p.getUniqueId();
        if (manager.count(id) == 0) {
            p.sendActionBar(Component.text("You have no tamed mobs.", NamedTextColor.RED));
            return;
        }
        if (!plugin.cooldowns().check(p, type(), "recall", "Recall")) {
            return;
        }
        Long start = chargeStart.get(id);
        double need = manager.chargeSeconds(id);
        if (start == null || (System.currentTimeMillis() - start) / 1000.0 < need) {
            p.sendActionBar(Component.text("Still charging... keep sneaking.", NamedTextColor.RED));
            return;
        }
        chargeStart.remove(id);
        chargedSound.remove(id);
        plugin.cooldowns().start(p, type(), "recall", num("recall-cooldown", 60));
        plugin.cooldowns().overlay(p, type());
        manager.recall(p);

        // burst around the caster
        Location c = p.getLocation().add(0, 1, 0);
        Fx.burst(c, Particle.FLASH, 1, 0, 0);
        Fx.shockwave(p.getLocation().add(0, 0.2, 0), org.bukkit.Color.fromRGB(200, 120, 255), 6.0, 14);
        Fx.burst(c, Particle.TOTEM_OF_UNDYING, 40, 0.6, 0.5);
        Fx.ring(p.getLocation().add(0, 0.1, 0), 3.0, 40, 0, l -> Fx.dust(l, Color.fromRGB(200, 120, 255), 1.4f, 1, 0.0));
    }

    @Override
    void onQuit(Player p) {
        chargeStart.remove(p.getUniqueId());
        chargedSound.remove(p.getUniqueId());
    }
}
