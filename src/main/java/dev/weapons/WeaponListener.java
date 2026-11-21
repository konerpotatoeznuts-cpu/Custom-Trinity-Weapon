package dev.weapons;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.entity.Trident;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Turns Bukkit events into ability calls. */
final class WeaponListener implements Listener {

    private final WeaponsPlugin plugin;
    private final java.util.Map<java.util.UUID, Integer> lastEntityClick = new java.util.HashMap<>();

    WeaponListener(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ melee hits and bullets

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (Combat.isInternal() || !(e.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Entity damager = e.getDamager();

        if (damager instanceof Arrow arrow
                && arrow.getPersistentDataContainer().has(plugin.bulletKey(), PersistentDataType.BYTE)) {
            plugin.revolver().onBulletHit(arrow, victim);
            return;
        }
        if (!(damager instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        if (plugin.stun().isStunned(p)) {
            e.setCancelled(true);
            return;
        }
        ItemStack item = p.getInventory().getItemInMainHand();
        WeaponType type = plugin.items().identify(item);
        if (type != null) {
            plugin.ability(type).onHit(p, victim, item, e);
            Animations.trigger(plugin, p, type, "hit");
            // Weapon swings also deal a share of their damage as armor-piercing damage, so full netherite doesn't blunt them
            double share = plugin.getConfig().getDouble("damage.melee-pierce-share", 0.5);
            if (share > 0 && type != WeaponType.FANTASY_TAMER) {
                final double extra = e.getDamage() * share;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (victim.isValid() && Combat.valid(plugin, p, victim)) {
                        Deaths.record(victim, p, type);
                        Combat.pierce(victim, extra, p);
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ right-click

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player p = e.getPlayer();
        if (plugin.stun().isStunned(p)) {
            e.setCancelled(true);
            return;
        }
        // Sonic's Shoes: sneak + right-click revs the spin dash (only if no weapon in hand wants the click)
        SonicShoesAbility sonic = (SonicShoesAbility) plugin.ability(WeaponType.SONIC_SHOES);
        if (sonic != null && sonic.click(p, e)) {
            return;
        }
        ItemStack item = e.getItem();
        WeaponType type = plugin.items().identify(item);
        if (type == null) {
            return;
        }
        // Opening a chest, crafting table, door... should not trigger an ability.
        if (action == Action.RIGHT_CLICK_BLOCK && !p.isSneaking()
                && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) {
            return;
        }
        try {
            plugin.ability(type).onUse(p, item, e);
            Animations.trigger(plugin, p, type, "use");
        } catch (Throwable ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in " + type.displayName() + " (right-click)", ex);
            p.sendMessage(net.kyori.adventure.text.Component.text("Something went wrong, check the server console.", net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player p = e.getPlayer();
        // One click can arrive as both an "interact" and an "interact at" event: handle it once per tick
        int tickNow = Bukkit.getCurrentTick();
        Integer last = lastEntityClick.put(p.getUniqueId(), tickNow);
        if (last != null && last == tickNow) {
            return;
        }
        if (plugin.stun().isStunned(p)) {
            e.setCancelled(true);
            return;
        }
        ItemStack item = p.getInventory().getItemInMainHand();
        WeaponType type = plugin.items().identify(item);
        if (type != null) {
            try {
                plugin.ability(type).onInteractEntity(p, e.getRightClicked(), item, e);
            } catch (Throwable ex) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in " + type.displayName() + " (entity click)", ex);
                p.sendMessage(net.kyori.adventure.text.Component.text("Something went wrong, check the server console.", net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        for (Ability a : plugin.abilities()) {
            a.onQuit(e.getPlayer());
        }
        plugin.flash().forget(e.getPlayer());
        ModelAnim.stop(e.getPlayer());
    }

    // ------------------------------------------------------------------ mining

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        WeaponType type = plugin.items().identify(item);
        if (type != null) {
            plugin.ability(type).onBreak(p, item, e);
        }
    }

    // ------------------------------------------------------------------ revolver extras

    @EventHandler(ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent e) {
        WeaponType bow = plugin.items().identify(e.getBow());
        if (bow == WeaponType.MEDUSAS_REVOLVER || bow == WeaponType.AK47 || bow == WeaponType.ROCKET_LAUNCHER) {
            e.setCancelled(true); // the revolver never fires vanilla crossbow shots
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        WeaponType inHand = plugin.items().identify(p.getInventory().getItemInMainHand());
        if (inHand == WeaponType.MEDUSAS_REVOLVER) {
            e.setCancelled(true); // F reloads the revolver
            if (plugin.protection().canUse(p)) {
                plugin.revolver().startReload(p);
            }
        } else if (inHand == WeaponType.AK47 || inHand == WeaponType.ROCKET_LAUNCHER) {
            e.setCancelled(true); // F reloads the guns
            if (plugin.protection().canUse(p) && plugin.ability(inHand) instanceof GunAbility gun) {
                gun.startReload(p);
            }
        }
    }

    /** The Sea Truncheon is never thrown (right-click is its riptide). */
    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof Trident && e.getEntity().getShooter() instanceof Player p
                && plugin.items().identify(p.getInventory().getItemInMainHand()) == WeaponType.SEA_TRUNCHEON) {
            e.setCancelled(true);
            // Vanilla throw (hold right-click, release) = riptide dash, with or without water
            ((SeaTruncheonAbility) plugin.ability(WeaponType.SEA_TRUNCHEON)).thrown(p);
            // The client already removed the trident from its hotbar (Bedrock especially): put it back
            Bukkit.getScheduler().runTask(plugin, p::updateInventory);
            Bukkit.getScheduler().runTaskLater(plugin, p::updateInventory, 2L);
            Bukkit.getScheduler().runTaskLater(plugin, p::updateInventory, 10L);
        }
    }

    /** The Smoke Grenade is thrown by the plugin, never as a vanilla egg. */
    @EventHandler(ignoreCancelled = true)
    public void onEggLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.Egg && e.getEntity().getShooter() instanceof Player p
                && plugin.items().identify(p.getInventory().getItemInMainHand()) == WeaponType.SMOKE_GRENADE) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (e.getEntity() instanceof Arrow arrow
                && arrow.getPersistentDataContainer().has(plugin.bulletKey(), PersistentDataType.BYTE)) {
            // Remove next tick so the damage from this hit is still applied
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (arrow.isValid()) {
                    arrow.remove();
                }
            });
        }
    }

    // ------------------------------------------------------------------ stun

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!plugin.stun().isStunned(e.getPlayer())) {
            return;
        }
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            e.setTo(fixed);
        }
    }

    // ------------------------------------------------------------------ dash fall damage

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL
                && e.getEntity() instanceof Player p && plugin.hasNoFall(p)) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ wither skeleton minions

    @EventHandler
    public void onMinionTarget(EntityTargetLivingEntityEvent e) {
        String victim = e.getEntity().getPersistentDataContainer().get(plugin.victimKey(), PersistentDataType.STRING);
        if (victim == null) {
            return;
        }
        LivingEntity target = e.getTarget();
        // Minions only ever fight the player's victim
        if (target == null || !target.getUniqueId().toString().equals(victim)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onMinionDeath(EntityDeathEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(plugin.ownerKey(), PersistentDataType.STRING)) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }
}
