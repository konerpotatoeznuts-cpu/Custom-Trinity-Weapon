package dev.weapons;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.function.Consumer;

/** Small helpers for particles and sounds. */
final class Fx {

    private Fx() {
    }

    private static PlayerSettings settings;
    private static WeaponsPlugin plugin;

    static void init(WeaponsPlugin pl, PlayerSettings st) {
        plugin = pl;
        settings = st;
    }

    // ------------------------------------------------------------------ who made this effect?

    private static final java.util.Map<String, WeaponType> SOURCES = java.util.Map.ofEntries(
            java.util.Map.entry("DurandalAbility", WeaponType.DURANDAL),
            java.util.Map.entry("MaceAbility", WeaponType.ELDRITCH_MACE),
            java.util.Map.entry("HalberdAbility", WeaponType.HALBERD_OF_DESPAIR),
            java.util.Map.entry("PrismaticAbility", WeaponType.PRISMATIC_BLADE),
            java.util.Map.entry("FlashOverlay", WeaponType.PRISMATIC_BLADE),
            java.util.Map.entry("WhipAbility", WeaponType.WARDEN_WHIP),
            java.util.Map.entry("RevolverAbility", WeaponType.MEDUSAS_REVOLVER),
            java.util.Map.entry("MinerToolAbility", WeaponType.MINERS_HANDY_TOOL),
            java.util.Map.entry("ExcaliburAbility", WeaponType.EXCALIBUR),
            java.util.Map.entry("TamerAbility", WeaponType.FANTASY_TAMER),
            java.util.Map.entry("TamerManager", WeaponType.FANTASY_TAMER),
            java.util.Map.entry("TameQte", WeaponType.FANTASY_TAMER),
            java.util.Map.entry("GreatAxeAbility", WeaponType.GREAT_AXE),
            java.util.Map.entry("SeaTruncheonAbility", WeaponType.SEA_TRUNCHEON),
            java.util.Map.entry("TsunamiWave", WeaponType.SEA_TRUNCHEON),
            java.util.Map.entry("SonicShoesAbility", WeaponType.SONIC_SHOES),
            java.util.Map.entry("SpinBall", WeaponType.SONIC_SHOES),
            java.util.Map.entry("SmokeGrenadeAbility", WeaponType.SMOKE_GRENADE),
            java.util.Map.entry("Ak47Ability", WeaponType.AK47),
            java.util.Map.entry("RocketLauncherAbility", WeaponType.ROCKET_LAUNCHER),
            java.util.Map.entry("CryoTempestAbility", WeaponType.CRYO_TEMPEST));

    private static WeaponType source(String className) {
        String n = className.substring(className.lastIndexOf('.') + 1);
        int d = n.indexOf('$');
        if (d >= 0) {
            n = n.substring(0, d);
        }
        return SOURCES.get(n);
    }

    /** Which weapon is this effect from? Found from the calling class, so no ability code needs to pass it around. */
    static WeaponType currentWeapon() {
        return context();
    }

    private static final StackWalker WALKER = StackWalker.getInstance();

    /**
     * Effects that loop (rings, lines, clouds) look up their weapon ONCE and open a scope, so the thousands of particle calls
     * inside do not each walk the stack. {@link #begin(WeaponType)} / {@link #end()} are main-thread only.
     */
    private static boolean scopeOn;
    private static WeaponType scopeType;

    static void begin(WeaponType t) {
        scopeOn = true;
        scopeType = t;
    }

    /** Opens a scope for the calling weapon unless one is already open. Returns true if THIS call opened it (and must call end()). */
    static boolean beginHere() {
        if (!scopeOn && !fast()) {
            begin(context());
            return true;
        }
        return false;
    }

    static void end() {
        scopeOn = false;
        scopeType = null;
    }

    private static WeaponType context() {
        return WALKER.walk(frames -> frames.limit(14)
                .map(f -> source(f.getClassName()))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null));
    }

    private static boolean fast() {
        return settings == null || !settings.anyRestriction();
    }

    // ------------------------------------------------------------------ particles

    /** The one place particles are spawned. Players who turned a weapon's particles off simply don't receive them. */
    static void particle(Location l, Particle particle, int count, double dx, double dy, double dz, double speed, Object data, boolean force) {
        World w = l.getWorld();
        if (w == null) {
            return;
        }
        if (data == null && particle == Particle.FLASH) {
            data = Color.WHITE; // newer versions need a color for the flash particle (without it the call throws)
        }
        if (fast()) {
            if (data == null) {
                w.spawnParticle(particle, l, count, dx, dy, dz, speed, null, force);
            } else {
                w.spawnParticle(particle, l, count, dx, dy, dz, speed, data, force);
            }
            return;
        }
        WeaponType src = scopeOn ? scopeType : context();
        double range = force ? 192 : 64;
        Snap s = snap(w);
        double lx = l.getX(), ly = l.getY(), lz = l.getZ();
        for (int i = 0; i < s.n; i++) {
            Player v = s.v[i];
            double ddx = s.x[i] - lx, ddy = s.y[i] - ly, ddz = s.z[i] - lz;
            double r = range;
            boolean low = settings.lowFx(v);
            if (low) {
                r = Math.min(r, 40); // low-FPS mode: only effects that are close
            }
            if (ddx * ddx + ddy * ddy + ddz * ddz > r * r || !settings.particles(v, src)) {
                continue;
            }
            int c = count;
            if (low) {
                // low-FPS mode (opt-in per player): this player receives fewer particles. Everybody else is unaffected.
                if (count <= 1) {
                    if (java.util.concurrent.ThreadLocalRandom.current().nextInt(100) >= 45) {
                        continue;
                    }
                } else {
                    c = Math.max(1, (int) Math.ceil(count * 0.45));
                }
            }
            if (data == null) {
                v.spawnParticle(particle, l, c, dx, dy, dz, speed);
            } else {
                v.spawnParticle(particle, l, c, dx, dy, dz, speed, data);
            }
        }
    }

    // ------------------------------------------------------------------ one player list per world per tick

    private static final class Snap {
        int tick = -1;
        int n;
        Player[] v = new Player[0];
        double[] x = new double[0];
        double[] y = new double[0];
        double[] z = new double[0];
    }

    private static final java.util.Map<World, Snap> SNAPS = new java.util.IdentityHashMap<>();

    /** Every player in the world and where they stand, taken once per tick (hundreds of effect calls share it). */
    private static Snap snap(World w) {
        Snap s = SNAPS.computeIfAbsent(w, k -> new Snap());
        int now = org.bukkit.Bukkit.getCurrentTick();
        if (s.tick != now) {
            java.util.List<Player> list = w.getPlayers();
            int n = list.size();
            if (s.v.length < n) {
                s.v = new Player[n];
                s.x = new double[n];
                s.y = new double[n];
                s.z = new double[n];
            }
            int k = 0;
            for (Player p : list) {
                if (!p.isOnline()) {
                    continue;
                }
                s.v[k] = p;
                s.x[k] = p.getX();
                s.y[k] = p.getY();
                s.z[k] = p.getZ();
                k++;
            }
            for (int i = k; i < s.n; i++) {
                s.v[i] = null; // do not hold on to players who left
            }
            s.n = k;
            s.tick = now;
        }
        return s;
    }

    /** Forget a world (unloaded). */
    static void forgetWorlds() {
        SNAPS.clear();
    }

    static void dust(Location l, Color color, float size, int count, double spread) {
        particle(l, Particle.DUST, count, spread, spread, spread, 0, new Particle.DustOptions(color, size), false);
    }

    /** Dust that slowly changes from one color to another: nicer trails and sparks. */
    static void dustFade(Location l, Color from, Color to, float size, int count, double spread) {
        particle(l, Particle.DUST_COLOR_TRANSITION, count, spread, spread, spread, 0, new Particle.DustTransition(from, to, size), false);
    }

    static void burst(Location l, Particle particle, int count, double spread, double speed) {
        particle(l, particle, count, spread, spread, spread, speed, null, false);
    }

    /** Particle that is visible from far away (force = true), used for tall beams. */
    static void force(Location l, Particle particle, int count, double spread, double speed) {
        particle(l, particle, count, spread, spread, spread, speed, null, true);
    }

    static void forceDust(Location l, Color color, float size) {
        particle(l, Particle.DUST, 1, 0, 0, 0, 0, new Particle.DustOptions(color, size), true);
    }

    /** A ring that expands and fades: shockwaves for launches and smashes. */
    static void shockwave(Location center, Color color, double maxRadius, int ticks) {
        if (plugin == null) {
            return;
        }
        disturbSmoke(center, Math.min(5.0, maxRadius), 2.5);
        final Location c = center.clone();
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                double r = maxRadius * t / ticks;
                int points = (int) Math.max(12, r * 10);
                ring(c, r, points, t * 0.3, l -> dustFade(l, color, Color.WHITE, 1.3f, 1, 0.0));
                if (t >= ticks) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    static void sound(Location l, Sound sound, float volume, float pitch) {
        World w = l.getWorld();
        if (w == null) {
            return;
        }
        if (settings == null || !settings.anyRestriction()) {
            w.playSound(l, sound, SoundCategory.PLAYERS, volume, pitch);
            return;
        }
        double range = Math.max(16, 16.0 * volume);
        Snap s = snap(w);
        for (int i = 0; i < s.n; i++) {
            double ddx = s.x[i] - l.getX(), ddy = s.y[i] - l.getY(), ddz = s.z[i] - l.getZ();
            if (ddx * ddx + ddy * ddy + ddz * ddz <= range * range && settings.on(s.v[i], "sounds")) {
                s.v[i].playSound(l, sound, SoundCategory.PLAYERS, volume, pitch);
            }
        }
    }

    /** Rainbow color, t is any number (wraps every 1.0). */
    static Color rainbow(double t) {
        double h = (t - Math.floor(t)) * 6.0;
        int i = (int) Math.floor(h);
        double f = h - i;
        int q = (int) Math.round(255 * (1 - f));
        int u = (int) Math.round(255 * f);
        return switch (i % 6) {
            case 0 -> Color.fromRGB(255, u, 0);
            case 1 -> Color.fromRGB(q, 255, 0);
            case 2 -> Color.fromRGB(0, 255, u);
            case 3 -> Color.fromRGB(0, q, 255);
            case 4 -> Color.fromRGB(u, 0, 255);
            default -> Color.fromRGB(255, 0, q);
        };
    }

    /** Weapon effects punch holes in Smoke Grenade clouds (like bullets and grenades do in CS2). */
    static void disturbSmoke(Location center, double radius, double seconds) {
        SmokeGrenadeAbility s = smoke();
        if (s != null) {
            s.disturb(center.getWorld(), center.toVector(), center.toVector(), radius, seconds);
        }
    }

    static void disturbSmoke(Location from, Location to, double radius, double seconds) {
        SmokeGrenadeAbility s = smoke();
        if (s != null && from.getWorld() != null && from.getWorld().equals(to.getWorld())) {
            s.disturb(from.getWorld(), from.toVector(), to.toVector(), radius, seconds);
        }
    }

    private static SmokeGrenadeAbility smoke() {
        if (plugin == null) {
            return null;
        }
        Ability a = plugin.ability(WeaponType.SMOKE_GRENADE);
        return a instanceof SmokeGrenadeAbility s && s.hasClouds() ? s : null;
    }

    /** Calls {@code each} for points on a horizontal ring around center. */
    static void ring(Location center, double radius, int points, double angleOffset, Consumer<Location> each) {
        boolean mine = beginHere();
        try {
            for (int i = 0; i < points; i++) {
                double a = angleOffset + (Math.PI * 2 * i) / points;
                each.accept(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius));
            }
        } finally {
            if (mine) {
                end();
            }
        }
    }

    /** Calls {@code each} for points on a line between two locations. */
    static void line(Location from, Location to, double step, Consumer<Location> each) {
        disturbSmoke(from, to, 1.2, 2.5);
        Vector diff = to.toVector().subtract(from.toVector());
        double len = diff.length();
        if (len < 0.01) {
            return;
        }
        Vector dir = diff.normalize().multiply(step);
        Location cur = from.clone();
        boolean mine = beginHere();
        try {
            for (double d = 0; d < len; d += step) {
                each.accept(cur.clone());
                cur.add(dir);
            }
        } finally {
            if (mine) {
                end();
            }
        }
    }
}
