package dev.weapons;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom death messages. Whenever a weapon's ability damages someone (through {@link Combat}), the hit is remembered for a few
 * seconds. If that player dies right after, the chat shows a message written for that weapon instead of the vanilla
 * "was killed by magic" text.
 */
final class Deaths implements Listener {

    private record Hit(WeaponType weapon, UUID killer, int tick, String tag) {
    }

    private static Deaths instance;

    private final WeaponsPlugin plugin;
    private final Map<UUID, Hit> hits = new HashMap<>();
    private final Map<WeaponType, String[]> messages = new EnumMap<>(WeaponType.class);
    private final Map<WeaponType, String[]> selfMessages = new EnumMap<>(WeaponType.class);

    /** One-shot label for the next recorded hit, e.g. "headshot". */
    private static String nextTag;

    Deaths(WeaponsPlugin plugin) {
        this.plugin = plugin;
        instance = this;
        // {v} = the victim, {k} = the killer. A random one of the lines is used.
        messages.put(WeaponType.DURANDAL, new String[]{
                "{v} was cleaved in two by {k}'s Durandal",
                "{v} could not outrun {k}'s Durandal beam",
                "{v} was cut down by the legendary blade of {k}"});
        messages.put(WeaponType.ELDRITCH_MACE, new String[]{
                "{v} was flattened by {k}'s Eldritch Mace",
                "{v} was smashed into the ground by {k}",
                "{v} fell victim to {k}'s eldritch power"});
        messages.put(WeaponType.HALBERD_OF_DESPAIR, new String[]{
                "{v} was run through by {k}'s Halberd of Despair",
                "{v} fell into despair at the hands of {k}",
                "{v} was spun into pieces by {k}"});
        messages.put(WeaponType.PRISMATIC_BLADE, new String[]{
                "{v} was blinded and cut down by {k}'s Prismatic Blade",
                "{v} saw the light, courtesy of {k}",
                "{v} was shattered into colors by {k}"});
        messages.put(WeaponType.WARDEN_WHIP, new String[]{
                "{v} was lashed to death by {k}'s Warden Whip",
                "{v} was whipped into silence by {k}",
                "{v} was dragged into the dark by {k}"});
        messages.put(WeaponType.MEDUSAS_REVOLVER, new String[]{
                "{v} was shot dead by {k}'s Medusa's Revolver",
                "{v} met Medusa's gaze courtesy of {k}",
                "{v} was gunned down by {k} and turned to stone"});
        messages.put(WeaponType.MINERS_HANDY_TOOL, new String[]{
                "{v} was mined out by {k}",
                "{v} was treated like ore by {k}'s Handy Tool"});
        messages.put(WeaponType.EXCALIBUR, new String[]{
                "{v} was judged by {k}'s Excalibur",
                "{v} fell to the barrage of {k}'s Excalibur",
                "{v} was launched into the heavens by {k}'s Rising Slash"});
        messages.put(WeaponType.FANTASY_TAMER, new String[]{
                "{v} was mauled by {k}'s tamed beasts",
                "{v} was hunted down by the pack of {k}"});
        messages.put(WeaponType.GREAT_AXE, new String[]{
                "{v} was chopped in half by {k}'s Great Axe",
                "{v} was hooked and hauled in by {k}",
                "{v} did not escape {k}'s chain"});
        messages.put(WeaponType.SEA_TRUNCHEON, new String[]{
                "{v} was swept away by {k}'s tsunami",
                "{v} drowned in the wave of {k}",
                "{v} was crushed by {k}'s Sea Truncheon"});
        messages.put(WeaponType.SONIC_SHOES, new String[]{
                "{v} was run over by {k} at supersonic speed",
                "{v} could not keep up with {k}'s Spin Dash",
                "{v} was no match for {k}'s speed"});
        messages.put(WeaponType.AK47, new String[]{
                "{v} was gunned down by {k}'s AK-47",
                "{v} was sprayed down by {k}",
                "{v} ate lead from {k}'s AK-47"});
        messages.put(WeaponType.ROCKET_LAUNCHER, new String[]{
                "{v} was blown to bits by {k}'s Rocket Launcher",
                "{v} took a rocket from {k}",
                "{v} was vaporized by {k}'s rocket"});
        messages.put(WeaponType.CRYO_TEMPEST, new String[]{
                "{v} was frozen solid by {k}'s Cryo Tempest",
                "{v} was caught in the arrow storm of {k}",
                "{v} was pierced by a frost bolt piloted by {k}"});
        messages.put(WeaponType.SMOKE_GRENADE, new String[]{
                "{v} was killed by {k}'s Smoke Grenade"});

        selfMessages.put(WeaponType.ROCKET_LAUNCHER, new String[]{
                "{v} blew themselves up with a Rocket Launcher",
                "{v} should not have fired a rocket at their own feet"});
        selfMessages.put(WeaponType.SONIC_SHOES, new String[]{"{v} crashed at supersonic speed"});
        selfMessages.put(WeaponType.SEA_TRUNCHEON, new String[]{"{v} was swept away by their own wave"});
    }

    /** Label the next hit (read once): the AK-47 uses "headshot". */
    static void tagNext(String tag) {
        nextTag = tag;
    }

    /** Called from Combat for every ability hit. */
    static void record(LivingEntity victim, Player killer, WeaponType weapon) {
        if (instance == null || !(victim instanceof Player) || weapon == null || killer == null) {
            nextTag = null;
            return;
        }
        String tag = nextTag;
        nextTag = null;
        instance.hits.put(victim.getUniqueId(), new Hit(weapon, killer.getUniqueId(), Bukkit.getCurrentTick(), tag));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        ModelAnim.stop(v);
        Hit h = hits.remove(v.getUniqueId());
        if (h == null || Bukkit.getCurrentTick() - h.tick() > plugin.getConfig().getInt("death-messages.window-ticks", 100)) {
            return;
        }
        if (!plugin.getConfig().getBoolean("death-messages.enabled", true)) {
            return;
        }
        EntityDamageEvent last = v.getLastDamageCause();
        if (last != null) {
            switch (last.getCause()) {
                case FALL, VOID, LAVA, FIRE, FIRE_TICK, SUFFOCATION, STARVATION, WORLD_BORDER, KILL, SUICIDE, CRAMMING -> {
                    return; // a different thing killed them
                }
                default -> {
                }
            }
        }
        Player killer = Bukkit.getPlayer(h.killer());
        String killerName = killer != null ? killer.getName() : "someone";
        boolean self = h.killer().equals(v.getUniqueId());
        String[] pool = self ? selfMessages.get(h.weapon()) : messages.get(h.weapon());
        if (pool == null) {
            pool = self ? new String[]{"{v} was killed by their own " + h.weapon().displayName()} : messages.get(h.weapon());
        }
        if (pool == null) {
            return;
        }
        String line;
        if ("headshot".equals(h.tag()) && h.weapon() == WeaponType.AK47 && !self) {
            line = ThreadLocalRandom.current().nextBoolean() ? "{v} was headshot by {k}'s AK-47" : "{v} lost their head to {k}'s AK-47";
        } else {
            line = pool[ThreadLocalRandom.current().nextInt(pool.length)];
        }
        Component vn = v.displayName();
        Component kn = Component.text(killerName);
        if (killer != null) {
            kn = killer.displayName();
        }
        Component msg = Component.empty();
        int i = 0;
        while (i < line.length()) {
            int a = line.indexOf('{', i);
            if (a < 0) {
                msg = msg.append(Component.text(line.substring(i)));
                break;
            }
            msg = msg.append(Component.text(line.substring(i, a)));
            if (line.startsWith("{v}", a)) {
                msg = msg.append(vn);
            } else {
                msg = msg.append(kn);
            }
            i = a + 3;
        }
        e.deathMessage(msg);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        hits.remove(e.getPlayer().getUniqueId());
    }
}
