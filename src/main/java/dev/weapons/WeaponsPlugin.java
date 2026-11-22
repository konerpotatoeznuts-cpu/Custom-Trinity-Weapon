package dev.weapons;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class WeaponsPlugin extends JavaPlugin {

    private WeaponItems items;
    private Cooldowns cooldowns;
    private Protection protection;
    private Stun stun;
    private RevolverAbility revolver;

    private final Map<WeaponType, Ability> abilities = new EnumMap<>(WeaponType.class);
    private final Map<UUID, Long> noFall = new HashMap<>();

    private NamespacedKey ownerKey;
    private NamespacedKey victimKey;
    private NamespacedKey bulletKey;
    private NamespacedKey tamedKey;
    private NamespacedKey sitKey;
    private TamerManager tamers;
    private TameQte qte;
    private FlashOverlay flash;
    private Immobilize immobilize;
    private PlayerSettings settings;
    private CrossPvp crossPvp;
    private SettingsMenu menu;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Combat.configure(this);
        settings = new PlayerSettings(this);
        menu = new SettingsMenu(this, settings);
        Fx.init(this, settings);

        ownerKey = new NamespacedKey(this, "owner");
        victimKey = new NamespacedKey(this, "victim");
        bulletKey = new NamespacedKey(this, "bullet");
        tamedKey = new NamespacedKey(this, "tamed_by");
        sitKey = new NamespacedKey(this, "sitting");

        items = new WeaponItems(this);
        cooldowns = new Cooldowns(this);
        protection = new Protection(this);
        stun = new Stun(this);
        flash = new FlashOverlay(this);
        immobilize = new Immobilize(this);

        revolver = new RevolverAbility(this);
        register(new DurandalAbility(this));
        register(new MaceAbility(this));
        register(new HalberdAbility(this));
        register(new PrismaticAbility(this));
        register(new WhipAbility(this));
        register(revolver);
        register(new MinerToolAbility(this));
        register(new ExcaliburAbility(this));
        tamers = new TamerManager(this);
        qte = new TameQte(this);
        register(new TamerAbility(this, tamers));
        register(new GreatAxeAbility(this));
        register(new SeaTruncheonAbility(this));
        register(new SonicShoesAbility(this));
        register(new SmokeGrenadeAbility(this));
        register(new Ak47Ability(this));
        register(new RocketLauncherAbility(this));
        register(new CryoTempestAbility(this));

        WeaponCommand command = new WeaponCommand(this);
        PluginCommand pc = Objects.requireNonNull(getCommand("weapon"), "weapon command missing from plugin.yml");
        pc.setExecutor(command);
        pc.setTabCompleter(command);

        getServer().getPluginManager().registerEvents(settings, this);
        getServer().getPluginManager().registerEvents(menu, this);
        settings.loadOnline();
        crossPvp = new CrossPvp(this);
        getServer().getPluginManager().registerEvents(crossPvp, this);
        crossPvp.start();
        getServer().getPluginManager().registerEvents(new Deaths(this), this);
        getServer().getPluginManager().registerEvents(new WeaponListener(this), this);
        getServer().getPluginManager().registerEvents(new RestrictionListener(this), this);
        getServer().getPluginManager().registerEvents(tamers, this);
        getServer().getPluginManager().registerEvents(qte, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 2L);
    }

    @Override
    public void onDisable() {
        for (Ability a : abilities.values()) {
            a.shutdown();
        }
        if (stun != null) {
            stun.releaseAll();
        }
        if (flash != null) {
            flash.shutdown();
            if (crossPvp != null) {
                crossPvp.shutdown();
            }
            ModelAnim.shutdown();
        }
        if (qte != null) {
            qte.shutdown();
        }
        if (immobilize != null) {
            immobilize.shutdown();
        }
    }

    private void register(Ability a) {
        abilities.put(a.type(), a);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack main = p.getInventory().getItemInMainHand();
            WeaponType held = items.identify(main);
            for (Ability a : abilities.values()) {
                a.tick(p, a.type() == held ? main : null);
            }
        }
    }

    // ------------------------------------------------------------------ accessors

    WeaponItems items() {
        return items;
    }

    Cooldowns cooldowns() {
        return cooldowns;
    }

    Protection protection() {
        return protection;
    }

    Stun stun() {
        return stun;
    }

    RevolverAbility revolver() {
        return revolver;
    }

    java.util.Collection<Ability> abilities() {
        return abilities.values();
    }

    Ability ability(WeaponType type) {
        return abilities.get(type);
    }

    NamespacedKey ownerKey() {
        return ownerKey;
    }

    NamespacedKey victimKey() {
        return victimKey;
    }

    NamespacedKey bulletKey() {
        return bulletKey;
    }

    NamespacedKey tamedKey() {
        return tamedKey;
    }

    NamespacedKey sitKey() {
        return sitKey;
    }

    PlayerSettings settings() {
        return settings;
    }

    SettingsMenu menu() {
        return menu;
    }

    Immobilize immobilize() {
        return immobilize;
    }

    FlashOverlay flash() {
        return flash;
    }

    TameQte qte() {
        return qte;
    }

    TamerManager tamers() {
        return tamers;
    }

    // ------------------------------------------------------------------ fall damage grace (mace dash)

    void grantNoFall(Player p, double seconds) {
        noFall.put(p.getUniqueId(), System.currentTimeMillis() + (long) (seconds * 1000));
    }

    boolean hasNoFall(Player p) {
        Long until = noFall.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    void reload() {
        reloadConfig();
        Combat.configure(this);
        flash.invalidate();
    }
}
