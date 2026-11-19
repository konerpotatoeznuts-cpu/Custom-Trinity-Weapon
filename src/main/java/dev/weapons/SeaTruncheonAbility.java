package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sea Truncheon.
 * Passive: riptide anywhere (right-click), mace-style smash on melee hits, Dolphin's Grace II in water.
 * Sneak (charge) + right-click: a tsunami. Rain and big bodies of water make it longer to charge and much stronger.
 */
final class SeaTruncheonAbility extends Ability {

    private static final Color BLUE = Color.fromRGB(60, 150, 240);
    private static final Color FOAM = Color.fromRGB(225, 245, 255);

    private final Map<UUID, Long> chargeStart = new HashMap<>();
    private final Map<UUID, Boolean> chargedSound = new HashMap<>();
    private final Map<UUID, long[]> lakeCache = new HashMap<>(); // {blockPosHash, expiresAt, isLake(0/1)}
    private final List<TsunamiWave> waves = new ArrayList<>();
    /** When each player pressed right-click (non-sneaking). A quick tap = rain; a held throw = riptide. */
    private final Map<UUID, Long> pressed = new HashMap<>();

    SeaTruncheonAbility(WeaponsPlugin plugin) {
        super(plugin);
    }

    @Override
    WeaponType type() {
        return WeaponType.SEA_TRUNCHEON;
    }

    // ------------------------------------------------------------------ conditions

    /** Hot biomes where the trident's powers are dead (desert, savanna, badlands/mesa by default). */
    private boolean tooHot(Player p) {
        String biome = p.getLocation().getBlock().getBiome().getKey().getKey();
        for (String s : plugin.getConfig().getStringList("weapons.sea_truncheon.disabled-biomes")) {
            if (biome.contains(s.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean blockedByHeat(Player p) {
        if (tooHot(p)) {
            p.sendActionBar(Component.text("The Sea Truncheon is dry in this heat...", NamedTextColor.GOLD));
            return true;
        }
        return false;
    }

    private boolean raining(Player p) {
        World w = p.getWorld();
        if (!w.hasStorm()) {
            return false;
        }
        Location l = p.getLocation();
        return w.getHighestBlockYAt(l) <= l.getBlockY() + 1; // open sky above
    }

    /** True if the player is in/over a lake-sized body of water. Cached for a second. */
    private boolean inLake(Player p) {
        Location l = p.getLocation();
        long hash = (((long) l.getBlockX()) * 31 + l.getBlockZ()) * 31 + l.getBlockY();
        long now = System.currentTimeMillis();
        long[] c = lakeCache.get(p.getUniqueId());
        if (c != null && c[0] == hash && c[1] > now) {
            return c[2] == 1;
        }
        boolean lake = computeLake(l);
        lakeCache.put(p.getUniqueId(), new long[]{hash, now + 1000, lake ? 1 : 0});
        return lake;
    }

    private boolean computeLake(Location l) {
        World w = l.getWorld();
        int need = (int) num("lake-min-blocks", 250);
        int radius = (int) num("lake-search-radius", 24);
        Block start = null;
        for (int dy = 0; dy >= -2 && start == null; dy--) {
            Block b = l.getBlock().getRelative(0, dy, 0);
            if (b.getType() == org.bukkit.Material.WATER) {
                start = b;
            }
        }
        if (start == null && l.getBlock().getRelative(0, 1, 0).getType() == org.bukkit.Material.WATER) {
            start = l.getBlock().getRelative(0, 1, 0);
        }
        if (start == null) {
            return false;
        }
        int sx = start.getX();
        int sy = start.getY();
        int sz = start.getZ();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{sx, sy, sz});
        seen.add(key(sx, sy, sz));
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty() && seen.size() < need) {
            int[] c = q.poll();
            for (int[] d : n) {
                int x = c[0] + d[0];
                int y = c[1] + d[1];
                int z = c[2] + d[2];
                if (Math.abs(x - sx) > radius || Math.abs(z - sz) > radius || Math.abs(y - sy) > 12) {
                    continue;
                }
                long k = key(x, y, z);
                if (seen.contains(k)) {
                    continue;
                }
                if (w.getBlockAt(x, y, z).getType() == org.bukkit.Material.WATER) {
                    seen.add(k);
                    q.add(new int[]{x, y, z});
                }
            }
        }
        return seen.size() >= need;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
    }

    private double capSeconds(boolean rain, boolean lake) {
        double base = num("charge-max-seconds", 10);
        if (rain && lake) {
            return num("charge-max-both", 35);
        }
        if (lake) {
            return num("charge-max-lake", 25);
        }
        if (rain) {
            return num("charge-max-rain", 15);
        }
        return base;
    }

    private double tierMult(boolean rain, boolean lake) {
        if (rain && lake) {
            return num("damage-mult-both", 3.0);
        }
        if (lake) {
            return num("damage-mult-lake", 2.0);
        }
        if (rain) {
            return num("damage-mult-rain", 1.5);
        }
        return 1.0;
    }

    private static String tierName(boolean rain, boolean lake) {
        if (rain && lake) {
            return "Storm Tide";
        }
        if (lake) {
            return "Deep Water";
        }
        if (rain) {
            return "Rain";
        }
        return "Calm";
    }

    // ------------------------------------------------------------------ tick: dolphin's grace + charge

    @Override
    void tick(Player p, ItemStack held) {
        UUID id = p.getUniqueId();
        Long press = pressed.get(id);
        if (press != null) {
            if (held == null) {
                pressed.remove(id);
            } else if (System.currentTimeMillis() - press >= 150 && !p.isHandRaised()) {
                pressed.remove(id); // released without throwing = a tap
                rain(p);
            }
        }
        if (held == null) {
            reset(id);
            return;
        }
        if (p.isInWater()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 60, 1, false, false, true));
        }
        if (!p.isSneaking() || !plugin.cooldowns().ready(p, type(), "tsunami") || tooHot(p)) {
            reset(id);
            return;
        }
        boolean rain = raining(p);
        boolean lake = inLake(p);
        double cap = capSeconds(rain, lake);
        long now = System.currentTimeMillis();
        long start = chargeStart.computeIfAbsent(id, k -> now);
        double have = Math.min(cap, (now - start) / 1000.0);
        double frac = have / cap;

        Location base = p.getLocation();
        double t = now / 140.0;
        for (int i = 0; i < 3; i++) {
            double a = t + i * (Math.PI * 2 / 3);
            double r = 1.5 + Math.sin(now / 300.0 + i) * 0.3;
            double y = 0.1 + ((now / 50.0 + i * 7) % 18) / 9.0 * (0.6 + frac);
            Fx.dust(base.clone().add(Math.cos(a) * r, y, Math.sin(a) * r), i == 0 ? FOAM : BLUE, 1.2f, 1, 0.0);
        }
        if (rain || lake) {
            Fx.burst(base.clone().add(0, 2.2, 0), Particle.CLOUD, 1, 0.5, 0.0);
        }
        boolean full = have >= cap - 0.05;
        if (full) {
            if (chargedSound.put(id, true) == null) {
                Fx.sound(base, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 0.7f);
                Fx.sound(base, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.5f, 0.8f);
            }
        } else if (((int) (have * 10)) % 5 == 0) {
            Fx.sound(base, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.5f, 0.5f + (float) frac);
        }
        int filled = (int) Math.round(frac * 20);
        p.sendActionBar(Component.text("Tsunami [" + tierName(rain, lake) + "]  " + "█".repeat(filled) + "░".repeat(20 - filled)
                        + String.format(Locale.ROOT, "  %.1fs/%.0fs", have, cap) + (full ? "  MAX - right-click!" : ""),
                full ? NamedTextColor.AQUA : NamedTextColor.BLUE));
    }

    private void reset(UUID id) {
        chargeStart.remove(id);
        chargedSound.remove(id);
    }

    // ------------------------------------------------------------------ right-click

    @Override
    void onUse(Player p, ItemStack item, PlayerInteractEvent e) {
        if (p.isSneaking()) {
            e.setCancelled(true); // sneak + right-click = tsunami, never the vanilla throw
            if (allowed(p) && !blockedByHeat(p)) {
                tsunami(p);
            }
            return;
        }
        // Not cancelled: the normal trident raise animation plays. Hold + release throws (see thrown() = riptide),
        // a quick tap that never throws summons rain (see tick()).
        pressed.put(p.getUniqueId(), System.currentTimeMillis());
    }

    /** The player released a charged throw: the trident never leaves, they riptide instead (no water needed). */
    void thrown(Player p) {
        pressed.remove(p.getUniqueId());
        if (!allowed(p) || blockedByHeat(p)) {
            return;
        }
        riptide(p);
    }

    // ------------------------------------------------------------------ rain

    private void rain(Player p) {
        if (!allowed(p) || blockedByHeat(p)) {
            return;
        }
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) {
            p.sendActionBar(Component.text("There is no weather here.", NamedTextColor.RED));
            return;
        }
        if (!plugin.cooldowns().check(p, type(), "rain", "Rain")) {
            return;
        }
        if (w.hasStorm()) {
            p.sendActionBar(Component.text("It's already raining.", NamedTextColor.AQUA));
            return;
        }
        plugin.cooldowns().start(p, type(), "rain", num("rain-cooldown", 600));
        int ticks = (int) (num("rain-seconds", 300) * 20);
        boolean thunder = ThreadLocalRandom.current().nextDouble() < num("rain-thunder-chance", 0.2);
        w.setStorm(true);
        w.setWeatherDuration(ticks);
        if (thunder) {
            w.setThundering(true);
            w.setThunderDuration(ticks);
        }
        Location c = p.getLocation().add(0, 1, 0);
        Fx.burst(c.clone().add(0, 3, 0), Particle.CLOUD, 80, 2.0, 0.05);
        Fx.burst(c, Particle.SPLASH, 80, 1.5, 0.3);
        Fx.ring(p.getLocation().add(0, 0.2, 0), 3.0, 40, 0, l -> Fx.dust(l, BLUE, 1.6f, 1, 0.0));
        Fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 2.0f, 0.8f);
        if (thunder) {
            w.strikeLightningEffect(p.getLocation().add(8, 0, 8));
            Fx.sound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 1.0f);
        }
        p.sendActionBar(Component.text(thunder ? "A thunderstorm rolls in!" : "Rain begins to fall...", NamedTextColor.AQUA));
    }

    // ------------------------------------------------------------------ tsunami

    private void tsunami(Player p) {
        UUID id = p.getUniqueId();
        if (!plugin.cooldowns().check(p, type(), "tsunami", "Tsunami")) {
            return;
        }
        Long start = chargeStart.get(id);
        boolean rain = raining(p);
        boolean lake = inLake(p);
        double cap = capSeconds(rain, lake);
        double have = start == null ? 0 : Math.min(cap, (System.currentTimeMillis() - start) / 1000.0);
        if (have < num("min-charge-seconds", 1.0)) {
            p.sendActionBar(Component.text("Keep sneaking to charge the tsunami...", NamedTextColor.RED));
            return;
        }
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            return; // looking straight up/down
        }
        dir.normalize();
        reset(id);
        plugin.cooldowns().start(p, type(), "tsunami", num("tsunami-cooldown", 45));
        plugin.cooldowns().overlay(p, type());

        double mult = tierMult(rain, lake);
        double half = Math.min(num("wave-max-half-width", 22), num("wave-base-half-width", 3) + num("wave-width-per-second", 0.9) * have);
        double height = Math.min(num("wave-max-height", 9), num("wave-base-height", 2) + num("wave-height-per-second", 0.35) * have);
        double range = Math.min(num("wave-max-range", 100), num("wave-base-range", 14) + num("wave-range-per-second", 3.0) * have);
        double dmg = (num("wave-base-damage", 1.5) + num("wave-damage-per-second", 0.35) * have) * mult;

        Location origin = p.getLocation().add(dir.clone().multiply(2.0));
        TsunamiWave w = new TsunamiWave(plugin, p, origin, dir, half, height, range, dmg);
        waves.removeIf(TsunamiWave::done);
        waves.add(w);
        w.start();

        Location c = p.getLocation().add(0, 1, 0);
        Fx.burst(c, Particle.SPLASH, 120, 2.0, 0.4);
        Fx.burst(c, Particle.CLOUD, 25, 1.0, 0.1);
        Fx.shockwave(p.getLocation().add(0, 0.2, 0), BLUE, 7.0, 10);
        Fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 1.5f, 0.8f);
        p.sendActionBar(Component.text("TSUNAMI! (" + tierName(rain, lake) + ")", NamedTextColor.AQUA));
    }

    // ------------------------------------------------------------------ riptide dash

    private void riptide(Player p) {
        Vector dir = p.getEyeLocation().getDirection().normalize();
        double speed = num("riptide-speed", 1.7);
        int maxTicks = (int) num("riptide-ticks", 12);
        plugin.grantNoFall(p, 2.5);
        Fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_3, 1.4f, 1.0f);
        Fx.burst(p.getLocation().add(0, 1, 0), Particle.SPLASH, 50, 0.6, 0.3);
        Fx.shockwave(p.getLocation().add(0, 0.2, 0), BLUE, 4.0, 7);
        Fx.burst(p.getLocation().add(0, 1, 0), Particle.FLASH, 1, 0, 0);

        new BukkitRunnable() {
            int t = 0;
            final Set<UUID> hit = new HashSet<>();

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t++ >= maxTicks) {
                    cancel();
                    return;
                }
                double k = 1.0 - (t / (double) maxTicks) * 0.35;
                p.setVelocity(dir.clone().multiply(speed * k));
                p.setFallDistance(0f);
                Location l = p.getLocation().add(0, 1, 0);
                Fx.burst(l, Particle.BUBBLE_POP, 8, 0.4, 0.05);
                Fx.burst(l, Particle.SPLASH, 10, 0.4, 0.2);
                Fx.dustFade(l, BLUE, FOAM, 1.6f, 3, 0.35);
                // a water spiral wrapped around the dash
                for (int arm = 0; arm < 2; arm++) {
                    double a = t * 0.9 + arm * Math.PI;
                    Vector side = new Vector(-dir.getZ(), 0, dir.getX()).normalize();
                    Vector up = side.clone().crossProduct(dir).normalize();
                    Location h = l.clone().add(side.clone().multiply(Math.cos(a) * 0.8)).add(up.clone().multiply(Math.sin(a) * 0.8));
                    Fx.burst(h, Particle.BUBBLE_POP, 2, 0.05, 0.0);
                    Fx.dustFade(h, FOAM, BLUE, 1.1f, 1, 0.0);
                }
                if (t % 3 == 0) {
                    Fx.ring(l, 1.2, 12, t, x -> Fx.dust(x, BLUE, 1.2f, 1, 0.0));
                }
                for (LivingEntity le : Combat.nearby(plugin, p, l, 1.8)) {
                    if (hit.add(le.getUniqueId())) {
                        smash(p, le, num("riptide-smash-damage", 14), 3.5, true);
                        p.setVelocity(new Vector(0, 0.7, 0)); // bounce off
                        cancel();
                        return;
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ------------------------------------------------------------------ melee smash

    @Override
    void onHit(Player p, LivingEntity target, ItemStack item, EntityDamageByEntityEvent e) {
        if (Combat.isInternal() || !allowed(p) || tooHot(p)) {
            return;
        }
        // mace-like: falling makes the smash hit harder
        double fall = Math.max(0, p.getFallDistance());
        double bonus = num("smash-base-damage", 5) + Math.min(num("smash-max-fall-bonus", 30), fall * num("smash-per-fall-block", 2.0));
        if (fall > 1.5) {
            plugin.grantNoFall(p, 1.0);
            p.setFallDistance(0f);
        }
        if (!cooldown(p, "smash", num("smash-cooldown", 0.6), "Smash")) {
            return;
        }
        final double b = bonus;
        later(() -> smash(p, target, b, 2.5, fall > 1.5));
    }

    /** The shared smash: extra damage + splash damage + knock-up with a water burst. */
    private void smash(Player p, LivingEntity target, double dmg, double radius, boolean big) {
        if (!target.isValid()) {
            return;
        }
        Location l = target.getLocation().add(0, 0.5, 0);
        double str = Combat.strength(p); // Strength makes the smash hit harder
        Combat.damage(target, dmg * str, p);
        target.setVelocity(target.getVelocity().add(new Vector(0, 0.55, 0)));
        for (LivingEntity o : Combat.nearby(plugin, p, l, radius)) {
            if (o == target) {
                continue;
            }
            Combat.damage(o, dmg * 0.4 * str, p);
            Vector away = o.getLocation().toVector().subtract(l.toVector()).setY(0);
            if (away.lengthSquared() > 1.0E-4) {
                away.normalize().multiply(0.7);
            }
            away.setY(0.5);
            o.setVelocity(away);
        }
        Fx.burst(l, Particle.SPLASH, big ? 160 : 70, radius * 0.5, 0.5);
        Fx.shockwave(l.clone().add(0, 0.1, 0), BLUE, radius + (big ? 2.0 : 0.5), big ? 8 : 5);
        Fx.burst(l, Particle.BUBBLE_POP, big ? 60 : 25, radius * 0.4, 0.2);
        Fx.burst(l, Particle.CLOUD, big ? 30 : 12, radius * 0.3, 0.15);
        Fx.ring(l.clone().add(0, 0.1, 0), radius, big ? 50 : 28, 0, x -> Fx.dust(x, BLUE, 1.8f, 1, 0.0));
        Fx.ring(l.clone().add(0, 0.5, 0), radius * 0.6, 24, 0, x -> Fx.dust(x, FOAM, 1.4f, 1, 0.0));
        if (big) {
            Fx.burst(l, Particle.EXPLOSION, 2, 0.3, 0.0);
        }
        Fx.sound(l, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, big ? 1.6f : 1.0f, 1.2f);
        Fx.sound(l, Sound.ENTITY_GENERIC_SPLASH, 1.5f, 0.7f);
    }

    // ------------------------------------------------------------------ cleanup

    @Override
    void onQuit(Player p) {
        pressed.remove(p.getUniqueId());
        reset(p.getUniqueId());
        lakeCache.remove(p.getUniqueId());
    }

    @Override
    void shutdown() {
        for (Iterator<TsunamiWave> it = waves.iterator(); it.hasNext(); ) {
            it.next().kill();
            it.remove();
        }
    }
}
