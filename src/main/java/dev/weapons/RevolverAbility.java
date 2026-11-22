package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Medusa's Revolver: a crossbow that works like a revolver.
 * Right-click fires (vanilla crossbow loading is blocked), sneak + right-click or F reloads from arrows in the
 * inventory (fireworks do nothing). A hit has a 50/50 chance to poison or petrify, with its own cooldown.
 */
final class RevolverAbility extends Ability {

    private static final class Reload {
        int slot;
        int ticks;
        int total;
        int load; // bullets this reload will put in
    }

    private final Map<UUID, Reload> reloading = new HashMap<>();
    private final Map<UUID, Long> lastShot = new HashMap<>();

    RevolverAbility(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.MEDUSAS_REVOLVER;
    }

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        e.setUseItemInHand(Event.Result.DENY); // stop the vanilla crossbow from loading
        if (!allowed(p)) {
            return;
        }
        if (reloading.containsKey(p.getUniqueId())) {
            return;
        }
        if (p.isSneaking() || plugin.items().getAmmo(item) <= 0) {
            startReload(p);
        } else {
            fire(p, item);
        }
    }

    // ------------------------------------------------------------------ reload

    void startReload(Player p) {
        UUID id = p.getUniqueId();
        ItemStack item = p.getInventory().getItemInMainHand();
        if (plugin.items().identify(item) != type() || reloading.containsKey(id)) {
            return;
        }
        if (plugin.items().getAmmo(item) >= plugin.items().magazine()) {
            p.sendActionBar(Component.text("Already fully loaded.", NamedTextColor.YELLOW));
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE && !p.getInventory().contains(Material.ARROW)) {
            p.sendActionBar(Component.text("No arrows to load!", NamedTextColor.RED));
            Fx.sound(p.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1.0f, 1.2f);
            return;
        }
        // Reload time depends on how many bullets are actually loaded (missing bullets, limited by arrows you carry)
        int need = plugin.items().magazine() - plugin.items().getAmmo(item);
        int load = p.getGameMode() == GameMode.CREATIVE ? need : Math.min(need, countArrows(p));
        Reload r = new Reload();
        r.slot = p.getInventory().getHeldItemSlot();
        r.load = Math.max(1, load);
        r.total = Math.max(2, (int) (reloadSeconds(r.load) * 20));
        Animations.trigger(plugin, p, type(), "reload");
        Anim.play(plugin, p, type(), "reload", r.total);
        reloading.put(id, r);
        Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_START, 1.0f, 0.9f);
    }

    /** Seconds to load this many bullets, from the reload-times table in config.yml. */
    private double reloadSeconds(int bullets) {
        double def = switch (Math.min(bullets, 6)) {
            case 1 -> 1.0;
            case 2 -> 2.4;
            case 3 -> 3.0;
            case 4 -> 3.8;
            case 5 -> 4.5;
            default -> 5.0;
        };
        return plugin.getConfig().getDouble("weapons.medusas_revolver.reload-times." + Math.min(bullets, 6), def);
    }

    private static int countArrows(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s != null && s.getType() == Material.ARROW) {
                n += s.getAmount();
            }
        }
        return n;
    }

    @Override
    void tick(Player p, ItemStack held) {
        UUID id = p.getUniqueId();
        Reload r = reloading.get(id);
        if (r == null) {
            return;
        }
        if (held == null || p.getInventory().getHeldItemSlot() != r.slot) {
            reloading.remove(id);
            ModelAnim.stop(p);
            p.sendActionBar(Component.text("Reload cancelled.", NamedTextColor.RED));
            return;
        }
        r.ticks += 2;
        double frac = Math.min(1.0, r.ticks / (double) r.total);
        int bars = (int) Math.round(frac * 12);
        p.sendActionBar(Component.text("Reloading [" + "#".repeat(bars) + "-".repeat(12 - bars) + "] "
                + String.format(Locale.ROOT, "%.1f", Math.max(0, (r.total - r.ticks) / 20.0)) + "s", NamedTextColor.YELLOW));

        if (r.ticks % 20 == 0 && r.ticks < r.total) {
            Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_MIDDLE, 1.0f, 1.1f);
        }
        if (r.ticks >= r.total) {
            reloading.remove(id);
            finishReload(p, held, r.load);
        }
    }

    private void finishReload(Player p, ItemStack held, int planned) {
        int mag = plugin.items().magazine();
        int ammo = plugin.items().getAmmo(held);
        int need = Math.min(mag - ammo, planned);
        int take = need;
        if (p.getGameMode() != GameMode.CREATIVE) {
            take = takeArrows(p, need);
        }
        if (take <= 0) {
            p.sendActionBar(Component.text("No arrows to load!", NamedTextColor.RED));
            return;
        }
        plugin.items().setAmmo(held, ammo + take);
        p.getInventory().setItemInMainHand(held);
        Fx.sound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 1.0f, 1.0f);
        Fx.sound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.0f, 1.4f);
        status(p, ammo + take);
    }

    private int takeArrows(Player p, int need) {
        int remaining = need;
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType() != Material.ARROW) {
                continue;
            }
            int t = Math.min(remaining, s.getAmount());
            s.setAmount(s.getAmount() - t);
            remaining -= t;
        }
        p.getInventory().setStorageContents(contents);
        return need - remaining;
    }

    // ------------------------------------------------------------------ fire

    private void fire(Player p, ItemStack item) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        long delay = (long) (num("fire-delay-ticks", 8) * 50);
        if (now - lastShot.getOrDefault(id, 0L) < delay) {
            return;
        }
        lastShot.put(id, now);
        Animations.trigger(plugin, p, type(), "fire");

        int ammo = plugin.items().getAmmo(item) - 1;
        plugin.items().setAmmo(item, ammo);
        p.getInventory().setItemInMainHand(item);

        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        Arrow arrow = p.launchProjectile(Arrow.class, dir.clone().multiply(num("bullet-speed", 3.0)));
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setCritical(false);
        arrow.setGravity(false);
        arrow.setDamage(num("bullet-damage", 4.0) * Combat.strength(p));
        arrow.getPersistentDataContainer().set(plugin.bulletKey(), PersistentDataType.BYTE, (byte) 1);
        plugin.bullets().follow(type(), arrow);

        // bullet trail, and the bullet disappears after 3 seconds
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!arrow.isValid() || t++ > 60) {
                    if (arrow.isValid()) {
                        arrow.remove();
                    }
                    cancel();
                    return;
                }
                Location l = arrow.getLocation();
                Fx.dust(l, Color.fromRGB(90, 255, 130), 1.0f, 2, 0.02);
                Fx.burst(l, Particle.END_ROD, 1, 0, 0);
            }
        }.runTaskTimer(plugin, 0L, 1L);

        // muzzle flash and sound
        Location muzzle = eye.clone().add(dir.clone().multiply(1.3));
        Fx.burst(muzzle, Particle.FLAME, 10, 0.06, 0.04);
        Fx.burst(muzzle, Particle.SMOKE, 8, 0.1, 0.02);
        Fx.burst(muzzle, Particle.END_ROD, 4, 0.05, 0.08);
        Fx.sound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1.2f, 0.7f);
        Fx.burst(eye.clone().add(dir.clone().multiply(1.0)), Particle.FLASH, 1, 0, 0);
        Fx.dustFade(eye.clone().add(dir.clone().multiply(1.1)), org.bukkit.Color.fromRGB(90, 255, 120), org.bukkit.Color.fromRGB(20, 90, 40), 1.4f, 6, 0.15);
        Fx.sound(eye, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 0.5f);

        // recoil: shove backwards and jerk the view upward
        Vector back = dir.clone().multiply(-num("recoil", 0.45));
        back.setY(Math.max(back.getY(), 0) + 0.1);
        p.setVelocity(p.getVelocity().add(back));
        double kick = num("camera-kick", 3.5);
        if (kick > 0) {
            Location l = p.getLocation();
            p.setRotation(l.getYaw(), (float) Math.max(-90.0, l.getPitch() - kick));
        }

        status(p, ammo);
    }

    private void status(Player p, int ammo) {
        int mag = plugin.items().magazine();
        long gaze = plugin.cooldowns().remainingMillis(p, type(), "gaze");
        String gazeText = gaze <= 0 ? "Gaze ready" : "Gaze " + (int) Math.ceil(gaze / 1000.0) + "s";
        String text = "Ammo " + "O".repeat(Math.max(0, ammo)) + ".".repeat(Math.max(0, mag - ammo)) + " " + ammo + "/" + mag
                + "  |  " + gazeText + (ammo <= 0 ? "  |  Empty! Right-click to reload" : "");
        p.sendActionBar(Component.text(text, ammo <= 0 ? NamedTextColor.RED : NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ bullet hit (called by the listener)

    void onBulletHit(Arrow arrow, LivingEntity victim) {
        if (!(arrow.getShooter() instanceof Player p)) {
            return;
        }
        if (!Combat.valid(plugin, p, victim)) {
            return;
        }
        if (!plugin.cooldowns().ready(p, type(), "gaze")) {
            return;
        }
        plugin.cooldowns().start(p, type(), "gaze", num("cooldown", 20));
        later(() -> gaze(p, victim));
    }

    private void gaze(Player p, LivingEntity victim) {
        if (!victim.isValid()) {
            return;
        }
        Location body = victim.getLocation().add(0, victim.getHeight() / 2.0, 0);
        if (ThreadLocalRandom.current().nextBoolean()) {
            plugin.stun().stun(victim, (int) (num("stone-seconds", 3) * 20), Stun.Style.STONE);
            Fx.burst(body, Particle.CLOUD, 25, 0.4, 0.05);
            Fx.sound(body, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 1.2f);
            Fx.sound(body, Sound.BLOCK_STONE_BREAK, 1.2f, 0.6f);
            if (p.isOnline()) {
                p.sendActionBar(Component.text("Petrified!", NamedTextColor.GRAY));
            }
        } else {
            int level = Math.max(1, (int) num("poison-level", 2));
            int ticks = (int) (num("poison-seconds", 8) * 20);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, ticks, level - 1));
            Fx.dust(body, Color.fromRGB(60, 200, 60), 1.6f, 30, 0.5);
            Fx.burst(body, Particle.WITCH, 20, 0.4, 0.05);
            Fx.sound(body, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0f, 1.6f);
            if (p.isOnline()) {
                p.sendActionBar(Component.text("Poisoned!", NamedTextColor.DARK_GREEN));
            }
        }
    }

    @Override
    void shutdown() {
        reloading.clear();
    }
}
