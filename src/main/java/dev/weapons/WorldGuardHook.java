package dev.weapons;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Only loaded when WorldGuard is installed. Keep WorldGuard types out of every other class. */
final class WorldGuardHook {

    boolean pvpAllowed(Player player, Location loc) {
        LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(lp, BukkitAdapter.adapt(loc.getWorld()))) {
            return true;
        }
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        return query.testState(BukkitAdapter.adapt(loc), lp, Flags.PVP);
    }

    boolean canBuild(Player player, Location loc) {
        LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(lp, BukkitAdapter.adapt(loc.getWorld()))) {
            return true;
        }
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        return query.testBuild(BukkitAdapter.adapt(loc), lp);
    }
}
