package dev.weapons;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player effect settings (/weapons config). Stored on the player itself, so they survive restarts.
 * Keys: fx.all, fx.<weaponId>, sounds, flash.overlay, sonic.ball, sonic.trail, sonic.electric,
 * color.spin, color.trail, color.electric. A missing key means the default.
 */
final class PlayerSettings implements Listener {

    record Palette(String id, String name, Material icon, Color color, Material core, Material inner) {
        boolean rainbow() {
            return id.equals("rainbow");
        }

        Color at(long millis) {
            return rainbow() ? Fx.rainbow(millis / 700.0) : color;
        }
    }

    static final List<Palette> PALETTE = List.of(
            new Palette("blue", "Blue", Material.BLUE_WOOL, Color.fromRGB(30, 110, 255), Material.BLUE_CONCRETE, Material.LIGHT_BLUE_CONCRETE),
            new Palette("cyan", "Cyan", Material.CYAN_WOOL, Color.fromRGB(60, 230, 255), Material.CYAN_CONCRETE, Material.LIGHT_BLUE_CONCRETE),
            new Palette("red", "Red", Material.RED_WOOL, Color.fromRGB(255, 50, 50), Material.RED_CONCRETE, Material.PINK_CONCRETE),
            new Palette("orange", "Orange", Material.ORANGE_WOOL, Color.fromRGB(255, 150, 30), Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE),
            new Palette("yellow", "Yellow", Material.YELLOW_WOOL, Color.fromRGB(255, 235, 60), Material.YELLOW_CONCRETE, Material.WHITE_CONCRETE),
            new Palette("green", "Green", Material.LIME_WOOL, Color.fromRGB(60, 230, 90), Material.GREEN_CONCRETE, Material.LIME_CONCRETE),
            new Palette("purple", "Purple", Material.PURPLE_WOOL, Color.fromRGB(170, 70, 255), Material.PURPLE_CONCRETE, Material.MAGENTA_CONCRETE),
            new Palette("pink", "Pink", Material.PINK_WOOL, Color.fromRGB(255, 120, 200), Material.MAGENTA_CONCRETE, Material.PINK_CONCRETE),
            new Palette("white", "White", Material.WHITE_WOOL, Color.fromRGB(240, 245, 255), Material.WHITE_CONCRETE, Material.LIGHT_GRAY_CONCRETE),
            new Palette("rainbow", "Rainbow", Material.NETHER_STAR, Color.fromRGB(255, 255, 255), Material.RED_CONCRETE, Material.YELLOW_CONCRETE));

    /** Block colors the rainbow ball cycles through. */
    static final Material[] RAINBOW_BLOCKS = {Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE,
            Material.LIME_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE, Material.PURPLE_CONCRETE, Material.MAGENTA_CONCRETE};

    private final WeaponsPlugin plugin;
    private final NamespacedKey storeKey;
    private final Map<UUID, Map<String, String>> data = new HashMap<>();
    private final Set<UUID> restricted = new HashSet<>();
    private volatile boolean anyRestriction;

    PlayerSettings(WeaponsPlugin plugin) {
        this.plugin = plugin;
        this.storeKey = new NamespacedKey(plugin, "settings");
    }

    static Palette palette(String id) {
        for (Palette p : PALETTE) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return PALETTE.get(0);
    }

    // ------------------------------------------------------------------ access

    private Map<String, String> map(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> load(p));
    }

    private Map<String, String> load(Player p) {
        Map<String, String> m = new HashMap<>();
        try {
            String raw = p.getPersistentDataContainer().get(storeKey, PersistentDataType.STRING);
            if (raw != null) {
                for (String part : raw.split(";")) {
                    int i = part.indexOf('=');
                    if (i > 0) {
                        m.put(part.substring(0, i), part.substring(i + 1));
                    }
                }
            }
        } catch (Throwable ignored) {
            // unreadable data: defaults
        }
        refresh(p.getUniqueId(), m);
        return m;
    }

    private void save(Player p) {
        Map<String, String> m = data.get(p.getUniqueId());
        if (m == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        try {
            p.getPersistentDataContainer().set(storeKey, PersistentDataType.STRING, sb.toString());
        } catch (Throwable ignored) {
            // offline
        }
    }

    private void refresh(UUID id, Map<String, String> m) {
        boolean r = false;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (((e.getKey().startsWith("fx.") || e.getKey().equals("sounds")) && e.getValue().equals("0"))
                    || (e.getKey().equals("perf.low") && e.getValue().equals("1"))) {
                r = true;
                break;
            }
        }
        if (r) {
            restricted.add(id);
        } else {
            restricted.remove(id);
        }
        anyRestriction = !restricted.isEmpty();
    }

    /** True if somebody online has turned some particles or sounds off (otherwise effects take the fast path). */
    boolean anyRestriction() {
        return anyRestriction;
    }

    boolean on(Player p, String key) {
        String v = map(p).get(key);
        return v == null || v.equals("1");
    }

    void setOn(Player p, String key, boolean value) {
        Map<String, String> m = map(p);
        m.put(key, value ? "1" : "0");
        refresh(p.getUniqueId(), m);
        save(p);
    }

    void toggle(Player p, String key) {
        setOn(p, key, !on(p, key));
    }

    Palette palette(Player p, String key, String def) {
        String v = map(p).get(key);
        return palette(v == null ? def : v);
    }

    /** The spin color; the trail uses it unless the player chose another one. */
    Palette spin(Player p) {
        return palette(p, "color.spin", "blue");
    }

    Palette trail(Player p) {
        String v = map(p).get("color.trail");
        return v == null ? spin(p) : palette(v);
    }

    Palette electric(Player p) {
        return palette(p, "color.electric", "yellow");
    }

    void cycle(Player p, String key, String def, int step) {
        String cur = map(p).get(key);
        int idx = 0;
        if (cur == null && key.equals("color.trail")) {
            cur = spin(p).id();
        }
        if (cur == null) {
            cur = def;
        }
        for (int i = 0; i < PALETTE.size(); i++) {
            if (PALETTE.get(i).id().equals(cur)) {
                idx = i;
            }
        }
        idx = Math.floorMod(idx + step, PALETTE.size());
        Map<String, String> m = map(p);
        m.put(key, PALETTE.get(idx).id());
        save(p);
    }

    void reset(Player p) {
        Map<String, String> m = map(p);
        m.clear();
        refresh(p.getUniqueId(), m);
        save(p);
    }

    /** Does this viewer want particles from this weapon? (weapon null = unknown source) */
    private static final String[] FX_KEYS = new String[WeaponType.values().length];

    static {
        for (WeaponType t : WeaponType.values()) {
            FX_KEYS[t.ordinal()] = "fx." + t.id();
        }
    }

    boolean particles(Player viewer, WeaponType weapon) {
        Map<String, String> m = map(viewer);
        if ("0".equals(m.get("fx.all"))) {
            return false;
        }
        return weapon == null || !"0".equals(m.get(FX_KEYS[weapon.ordinal()]));
    }

    /** Low-FPS mode (off unless the player turns it on): this player is sent fewer, closer particles. */
    boolean lowFx(Player p) {
        return anyRestriction && "1".equals(map(p).get("perf.low"));
    }

    // ------------------------------------------------------------------ lifecycle

    void loadOnline() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            map(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        map(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        save(e.getPlayer());
        data.remove(id);
        restricted.remove(id);
        anyRestriction = !restricted.isEmpty();
    }
}
