package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Everything about tamed mobs for the Fantasy Tamer: who owns what, taming progress, sitting, following,
 * helping in fights and teleporting them to the owner.
 *
 * Ownership is stored on the mob itself (persistent data), so it survives restarts. Mobs in unloaded chunks
 * keep their owner but can only be recalled once their chunk is loaded.
 */
final class TamerManager implements Listener {

    private final WeaponsPlugin plugin;
    /** owner -> tamed mob ids (loaded or not). */
    private final Map<UUID, Set<UUID>> owned = new HashMap<>();
    /** owner -> (enemy id -> expiry millis). Mobs only fight these. */
    private final Map<UUID, Map<UUID, Long>> targets = new HashMap<>();
    private final Map<UUID, Long> nextAttack = new HashMap<>();

    TamerManager(WeaponsPlugin plugin) {
        this.plugin = plugin;
        for (World w : Bukkit.getWorlds()) {
            for (Mob m : w.getEntitiesByClass(Mob.class)) {
                index(m);
            }
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 40L, 10L);
    }

    // ------------------------------------------------------------------ ownership

    private void index(Entity e) {
        UUID owner = ownerOf(e);
        if (owner != null) {
            owned.computeIfAbsent(owner, k -> new HashSet<>()).add(e.getUniqueId());
        }
    }

    UUID ownerOf(Entity e) {
        String s = e.getPersistentDataContainer().get(plugin.tamedKey(), PersistentDataType.STRING);
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    boolean isSitting(Entity e) {
        return e.getPersistentDataContainer().has(plugin.sitKey(), PersistentDataType.BYTE);
    }

    int count(UUID owner) {
        Set<UUID> s = owned.get(owner);
        return s == null ? 0 : s.size();
    }

    /** Loaded tamed mobs of this owner. */
    List<Mob> loaded(UUID owner) {
        List<Mob> out = new ArrayList<>();
        Set<UUID> s = owned.get(owner);
        if (s == null) {
            return out;
        }
        for (UUID id : s) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof Mob m && m.isValid()) {
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ taming

    boolean canTame(Entity e) {
        return e instanceof Mob && !(e instanceof EnderDragon) && !e.hasMetadata("NPC");
    }

    /** Clicks needed: 1 for passive, 2 for hostile mobs, +1 per 20 max health, capped. */
    int clicksNeeded(Mob m) {
        double hp = 20;
        AttributeInstance a = m.getAttribute(Attribute.MAX_HEALTH);
        if (a != null) {
            hp = a.getValue();
        }
        int base = m instanceof Enemy ? 2 : 1;
        int need = base + (int) Math.floor(hp / plugin.getConfig().getDouble("weapons.fantasy_tamer.health-per-click", 20.0));
        return Math.max(1, Math.min((int) plugin.getConfig().getDouble("weapons.fantasy_tamer.max-clicks", 15), need));
    }

    /** A right-click on a wild mob: starts the taming minigame (see TameQte). */
    void tameClick(Player p, Mob m) {
        int max = (int) plugin.getConfig().getDouble("weapons.fantasy_tamer.max-tamed", 30);
        if (count(p.getUniqueId()) >= max) {
            p.sendActionBar(Component.text("You can only have " + max + " tamed mobs.", NamedTextColor.RED));
            return;
        }
        plugin.qte().start(p, m, clicksNeeded(m));
    }

    void tame(Player p, Mob m) {
        m.getPersistentDataContainer().set(plugin.tamedKey(), PersistentDataType.STRING, p.getUniqueId().toString());
        m.setPersistent(true);
        m.setRemoveWhenFarAway(false);
        m.setTarget(null);
        if (m instanceof Tameable t) {
            t.setTamed(true);
            t.setOwner(p);
        }
        owned.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(m.getUniqueId());

        Location c = m.getLocation().add(0, m.getHeight() / 2.0, 0);
        Fx.burst(c, Particle.HEART, 14, 0.6, 0.1);
        Fx.burst(c, Particle.TOTEM_OF_UNDYING, 30, 0.5, 0.4);
        Fx.burst(c, Particle.END_ROD, 20, 0.5, 0.2);
        Fx.sound(m.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.2f, 1.4f);
        Fx.sound(m.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.8f, 1.6f);
        Fx.sound(m.getLocation(), Sound.ITEM_TOTEM_USE, 0.4f, 1.8f);
        p.sendActionBar(Component.text("You tamed " + m.getName() + "!  (" + count(p.getUniqueId()) + " tamed)", NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ sitting

    void toggleSit(Player p, Mob m) {
        boolean sit = !isSitting(m);
        if (sit) {
            m.getPersistentDataContainer().set(plugin.sitKey(), PersistentDataType.BYTE, (byte) 1);
            m.setTarget(null);
        } else {
            m.getPersistentDataContainer().remove(plugin.sitKey());
        }
        if (m instanceof Sittable s) {
            s.setSitting(sit);
        } else {
            m.setAI(!sit);
        }
        Location c = m.getLocation().add(0, m.getHeight() + 0.3, 0);
        Fx.burst(c, sit ? Particle.NOTE : Particle.HEART, 4, 0.3, 0.5);
        Fx.sound(m.getLocation(), sit ? Sound.BLOCK_NOTE_BLOCK_BASS : Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, sit ? 0.8f : 1.6f);
        p.sendActionBar(Component.text(m.getName() + (sit ? " is staying put." : " is following you."), NamedTextColor.AQUA));
    }

    // ------------------------------------------------------------------ recall

    /** Teleports every loaded tamed mob around the player, spread out so they do not crowd. Returns how many were moved. */
    int recall(Player p) {
        List<Mob> mobs = loaded(p.getUniqueId());
        int total = count(p.getUniqueId());
        double radius = plugin.getConfig().getDouble("weapons.fantasy_tamer.recall-radius", 20.0);
        World w = p.getWorld();
        Location center = p.getLocation();
        List<Location> used = new ArrayList<>();

        Fx.sound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.8f);
        Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.6f);
        Fx.ring(center.clone().add(0, 0.2, 0), 1.2, 24, 0, l -> Fx.burst(l, Particle.PORTAL, 6, 0.1, 0.4));

        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                // a few per tick, so it ripples out instead of lagging
                for (int n = 0; n < 3 && i < mobs.size(); n++, i++) {
                    Mob m = mobs.get(i);
                    if (!m.isValid() || !p.isOnline() || !p.getWorld().equals(w)) {
                        continue;
                    }
                    Location from = m.getLocation().add(0, m.getHeight() / 2.0, 0);
                    Fx.burst(from, Particle.PORTAL, 25, 0.4, 0.6);
                    Fx.burst(from, Particle.REVERSE_PORTAL, 10, 0.3, 0.2);
                    Location to = spot(center, radius, used, m);
                    used.add(to);
                    m.teleport(to);
                    m.setFallDistance(0f);
                    Location arrive = to.clone().add(0, m.getHeight() / 2.0, 0);
                    Fx.burst(arrive, Particle.END_ROD, 20, 0.4, 0.2);
                    Fx.burst(arrive, Particle.FIREWORK, 10, 0.3, 0.15);
                    Fx.line(center.clone().add(0, 1, 0), arrive, 1.0, l -> Fx.dust(l, org.bukkit.Color.fromRGB(190, 120, 255), 0.8f, 1, 0));
                    Fx.sound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
                }
                if (i >= mobs.size()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        int unloaded = Math.max(0, total - mobs.size());
        p.sendActionBar(Component.text("Recalled " + mobs.size() + " mobs"
                + (unloaded > 0 ? " (" + unloaded + " are in unloaded chunks)" : "") + ".", NamedTextColor.LIGHT_PURPLE));
        return mobs.size();
    }

    /** A random standable place within radius, at least ~1.6 blocks from the ones already picked. */
    private Location spot(Location center, double radius, List<Location> used, Mob m) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        World w = center.getWorld();
        boolean flyer = m.getType().name().equals("BAT") || m.getType().name().equals("PARROT")
                || m.getType().name().equals("GHAST") || m.getType().name().equals("BLAZE")
                || m.getType().name().equals("ALLAY") || m.getType().name().equals("VEX")
                || m.getType().name().equals("PHANTOM") || m.getType().name().equals("BEE");
        for (int attempt = 0; attempt < 24; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double r = 2.0 + Math.sqrt(rnd.nextDouble()) * (radius - 2.0);
            double x = center.getX() + Math.cos(ang) * r;
            double z = center.getZ() + Math.sin(ang) * r;
            Location found = null;
            if (flyer) {
                found = new Location(w, x, center.getY() + 1 + rnd.nextDouble() * 4, z);
                if (!found.getBlock().isPassable()) {
                    found = null;
                }
            } else {
                int cy = center.getBlockY();
                for (int y = cy + 4; y >= cy - 8; y--) {
                    Block feet = w.getBlockAt((int) Math.floor(x), y, (int) Math.floor(z));
                    Block head = feet.getRelative(0, 1, 0);
                    Block below = feet.getRelative(0, -1, 0);
                    if (feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()
                            && !below.isPassable() && !below.isLiquid()) {
                        found = new Location(w, Math.floor(x) + 0.5, y, Math.floor(z) + 0.5);
                        break;
                    }
                }
            }
            if (found == null) {
                continue;
            }
            boolean crowded = false;
            for (Location u : used) {
                if (u.distanceSquared(found) < 2.5) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) {
                return found;
            }
        }
        // fallback: right next to the player, slightly offset
        return center.clone().add(rnd.nextDouble() * 4 - 2, 0, rnd.nextDouble() * 4 - 2);
    }

    /** Seconds the charge takes: a base plus a bit per tamed mob, capped. */
    double chargeSeconds(UUID owner) {
        double base = plugin.getConfig().getDouble("weapons.fantasy_tamer.charge-base-seconds", 2.0);
        double per = plugin.getConfig().getDouble("weapons.fantasy_tamer.charge-per-mob-seconds", 0.4);
        double cap = plugin.getConfig().getDouble("weapons.fantasy_tamer.charge-max-seconds", 15.0);
        return Math.min(cap, base + per * count(owner));
    }

    // ------------------------------------------------------------------ fights

    private void designate(UUID owner, Entity enemy) {
        if (owner.equals(enemy.getUniqueId()) || count(owner) == 0) {
            return;
        }
        if (enemy instanceof Mob m && owner.equals(ownerOf(m))) {
            return;
        }
        long secs = (long) (plugin.getConfig().getDouble("weapons.fantasy_tamer.assist-seconds", 15.0) * 1000);
        targets.computeIfAbsent(owner, k -> new HashMap<>()).put(enemy.getUniqueId(), System.currentTimeMillis() + secs);
    }

    private boolean isDesignated(UUID owner, Entity target) {
        Map<UUID, Long> m = targets.get(owner);
        if (m == null) {
            return false;
        }
        Long until = m.get(target.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private static Entity resolveAttacker(Entity damager) {
        if (damager instanceof Projectile pr) {
            ProjectileSource src = pr.getShooter();
            if (src instanceof Entity en) {
                return en;
            }
        }
        return damager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity attacker = resolveAttacker(e.getDamager());
        Entity victim = e.getEntity();

        // No friendly fire: pets never hurt their owner or each other, and owners do not hit their pets by accident.
        UUID attackerOwner = ownerOf(attacker);
        UUID victimOwner = ownerOf(victim);
        if (attackerOwner != null && (attackerOwner.equals(victim.getUniqueId()) || attackerOwner.equals(victimOwner))) {
            e.setCancelled(true);
            return;
        }
        if (victimOwner != null && victimOwner.equals(attacker.getUniqueId())) {
            e.setCancelled(true);
            return;
        }

        // The owner hits something, or something hits the owner: the pets join in.
        if (attacker instanceof Player p && victim instanceof LivingEntity) {
            designate(p.getUniqueId(), victim);
        } else if (victim instanceof Player p && attacker instanceof LivingEntity && !attacker.equals(p)) {
            designate(p.getUniqueId(), attacker);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        UUID owner = ownerOf(e.getEntity());
        if (owner == null || e.getEntity() instanceof Tameable || e.getTarget() == null) {
            return;
        }
        if (isSitting(e.getEntity()) || !isDesignated(owner, e.getTarget())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        UUID owner = ownerOf(e.getEntity());
        if (owner == null) {
            return;
        }
        Set<UUID> s = owned.get(owner);
        if (s != null) {
            s.remove(e.getEntity().getUniqueId());
        }
        nextAttack.remove(e.getEntity().getUniqueId());
        Player p = Bukkit.getPlayer(owner);
        if (p != null) {
            p.sendActionBar(Component.text("Your tamed " + e.getEntity().getName() + " has fallen.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Mob) {
                index(en);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (e instanceof EntityCombustByEntityEvent || e instanceof EntityCombustByBlockEvent) {
            return;
        }
        if (ownerOf(e.getEntity()) != null) {
            e.setCancelled(true); // tamed zombies and skeletons do not burn in daylight
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (e.getEntity() instanceof Creeper && ownerOf(e.getEntity()) != null) {
            e.blockList().clear(); // tamed creepers do not break blocks
        }
    }

    // ------------------------------------------------------------------ periodic: follow and fight

    private void tick() {
        long now = System.currentTimeMillis();
        double followDist = plugin.getConfig().getDouble("weapons.fantasy_tamer.follow-distance", 7.0);
        double assistRange = plugin.getConfig().getDouble("weapons.fantasy_tamer.assist-range", 40.0);
        double fallback = plugin.getConfig().getDouble("weapons.fantasy_tamer.passive-attack-damage", 4.0);

        for (Map.Entry<UUID, Set<UUID>> en : new ArrayList<>(owned.entrySet())) {
            Player owner = Bukkit.getPlayer(en.getKey());
            if (owner == null || !owner.isOnline()) {
                continue;
            }
            // pick the nearest valid enemy (designated)
            Map<UUID, Long> tm = targets.get(en.getKey());
            if (tm != null) {
                tm.values().removeIf(t -> t < now);
            }

            for (UUID id : new ArrayList<>(en.getValue())) {
                Entity ent = Bukkit.getEntity(id);
                if (!(ent instanceof Mob m) || !m.isValid() || isSitting(m)) {
                    continue;
                }
                LivingEntity enemy = nearestEnemy(owner, m, tm, assistRange);
                if (enemy != null) {
                    fight(owner, m, enemy, fallback, now);
                } else if (m.getWorld().equals(owner.getWorld()) && !(m instanceof Tameable)) {
                    if (m.getLocation().distanceSquared(owner.getLocation()) > followDist * followDist) {
                        m.getPathfinder().moveTo(owner.getLocation(), 1.15);
                    }
                }
            }
        }
    }

    private LivingEntity nearestEnemy(Player owner, Mob m, Map<UUID, Long> tm, double range) {
        if (tm == null || tm.isEmpty()) {
            return null;
        }
        LivingEntity best = null;
        double bestD = range * range;
        for (UUID tid : tm.keySet()) {
            Entity t = Bukkit.getEntity(tid);
            if (!(t instanceof LivingEntity le) || !le.isValid() || le.isDead() || !le.getWorld().equals(m.getWorld())) {
                continue;
            }
            if (!Combat.valid(plugin, owner, le)) {
                continue;
            }
            double d = le.getLocation().distanceSquared(m.getLocation());
            if (d < bestD) {
                bestD = d;
                best = le;
            }
        }
        return best;
    }

    private void fight(Player owner, Mob m, LivingEntity enemy, double fallbackDamage, long now) {
        if (m.getTarget() != enemy) {
            m.setTarget(enemy);
        }
        // Mobs with real attack AI hit by themselves. Others (cows, pigs...) walk over and get a simple melee hit.
        if (m.getAttribute(Attribute.ATTACK_DAMAGE) != null) {
            return;
        }
        double reach = 1.8 + m.getWidth() / 2.0 + enemy.getWidth() / 2.0;
        if (m.getLocation().distanceSquared(enemy.getLocation()) > reach * reach) {
            m.getPathfinder().moveTo(enemy.getLocation(), 1.3);
            return;
        }
        if (nextAttack.getOrDefault(m.getUniqueId(), 0L) > now) {
            return;
        }
        nextAttack.put(m.getUniqueId(), now + 1000L);
        DamageSource src = DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(m).withDirectEntity(m).build();
        Combat.damage(enemy, fallbackDamage, src);
        Vector kb = enemy.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0);
        if (kb.lengthSquared() > 0.01) {
            enemy.setVelocity(enemy.getVelocity().add(kb.normalize().multiply(0.3).setY(0.2)));
        }
        Fx.burst(enemy.getLocation().add(0, enemy.getHeight() / 2.0, 0), Particle.CRIT, 8, 0.3, 0.2);
        Fx.sound(m.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.0f);
    }
}
