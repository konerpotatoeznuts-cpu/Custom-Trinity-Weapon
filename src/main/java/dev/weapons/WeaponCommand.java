package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WeaponCommand implements CommandExecutor, TabCompleter {

    private final WeaponsPlugin plugin;

    WeaponCommand(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /weapons config: open to everybody
        if (args.length > 0 && (args[0].equalsIgnoreCase("config") || args[0].equalsIgnoreCase("settings"))) {
            if (sender instanceof Player pl) {
                plugin.menu().open(pl);
            } else {
                sender.sendMessage(Component.text("Only players can open the settings menu.", NamedTextColor.RED));
            }
            return true;
        }
        if (!sender.hasPermission("customweapons.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                sender.sendMessage(Component.text("Weapons:", NamedTextColor.GOLD));
                for (WeaponType t : WeaponType.values()) {
                    sender.sendMessage(Component.text("  " + t.displayName(), NamedTextColor.YELLOW)
                            .append(Component.text("  (" + t.id() + ")", NamedTextColor.GRAY)));
                }
                sender.sendMessage(Component.text("Items:", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("  Trinity Dihrama", NamedTextColor.YELLOW).append(Component.text("  (dihrama)", NamedTextColor.GRAY)));
                sender.sendMessage(Component.text("  AK-47 Magazine", NamedTextColor.YELLOW).append(Component.text("  (magazine)", NamedTextColor.GRAY)));
                sender.sendMessage(Component.text("  Empty AK-47 Magazine", NamedTextColor.YELLOW).append(Component.text("  (empty_magazine)", NamedTextColor.GRAY)));
            }
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(Component.text("CustomWeapons config reloaded.", NamedTextColor.GREEN));
            }
            case "give" -> give(sender, label, args);
            case "check" -> check(sender, args);
            default -> usage(sender, label);
        }
        return true;
    }

    private void give(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            usage(sender, label);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Player '" + args[1] + "' is not online.", NamedTextColor.RED));
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        // the two non-weapon items: /weapon give <player> magazine [amount]  and  /weapon give <player> dihrama [amount]
        String[] parts = name.trim().split("\\s+");
        String first = extraId(name);
        if (first != null) {
            int amount = 1;
            if (parts.length > 1) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(parts[parts.length - 1])));
                } catch (NumberFormatException ignored) {
                    // one
                }
            }
            boolean mag = first.contains("magazine");
            ItemStack stack = mag ? plugin.magazines().create(first.equals("empty_magazine") ? 0 : plugin.magazines().capacity()) : plugin.dihrama().create(amount);
            if (mag) {
                stack.setAmount(Math.min(16, amount));
            }
            for (ItemStack left : target.getInventory().addItem(stack).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), left);
            }
            String label2 = mag ? (first.equals("empty_magazine") ? "Empty AK-47 Magazine" : "AK-47 Magazine") : "Trinity Dihrama";
            sender.sendMessage(Component.text("Gave " + amount + "x " + label2 + " to " + target.getName() + ".", NamedTextColor.GREEN));
            if (!sender.equals(target)) {
                target.sendMessage(Component.text("You received " + amount + "x " + label2 + ".", NamedTextColor.GOLD));
            }
            return;
        }
        WeaponType type = WeaponType.parse(name);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown weapon '" + name + "'. Use /" + label + " list.", NamedTextColor.RED));
            return;
        }

        ItemStack item = plugin.items().create(type);
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(item);
        for (ItemStack left : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left);
        }
        sender.sendMessage(Component.text("Gave " + type.displayName() + " to " + target.getName() + ".", NamedTextColor.GREEN));
        if (!sender.equals(target)) {
            target.sendMessage(Component.text("You received " + type.displayName() + ".", NamedTextColor.GOLD));
        }
    }

    /** The non-weapon items, matched loosely ("Trinity Dihrama", "trinity_dihrama", "dihrama", "empty magazine"...). Null = not one. */
    private static String extraId(String raw) {
        String n = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 _]", "").trim();
        n = n.replaceAll("\\s+\\d+$", ""); // a trailing amount
        n = n.replace(' ', '_').replace("ak_47", "ak47");
        if (n.contains("dihrama")) {
            return "dihrama";
        }
        if (n.contains("magazine") || n.equals("mag")) {
            return n.contains("empty") ? "empty_magazine" : "magazine";
        }
        return null;
    }

    /** /weapon check [player]: shows why abilities might not work for someone. */
    private void check(CommandSender sender, String[] args) {
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player pl ? pl : null);
        if (target == null) {
            sender.sendMessage(Component.text("Usage: /weapon check <player>", NamedTextColor.RED));
            return;
        }
        Protection pr = plugin.protection();
        boolean hasPerm = target.hasPermission("customweapons.use");
        sender.sendMessage(Component.text("Ability check for " + target.getName() + ":", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  Permission required: " + pr.requiresPermission()
                + "  (customweapons.use = " + hasPerm + ")", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  WorldGuard hooked: " + pr.worldGuardActive()
                + "  |  pvp allowed where they stand: " + pr.pvpHere(target), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  Abilities blocked in no-pvp areas: " + pr.blocksInNoPvp(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  RESULT: abilities " + (pr.canUse(target) ? "WORK" : "are BLOCKED") + " for them here.",
                pr.canUse(target) ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("Usage:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  /" + label + " give <player> <weapon|dihrama|magazine|empty_magazine> [amount]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /" + label + " list", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /" + label + " reload", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /" + label + " check [player]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /" + label + " config   (everyone)", NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("customweapons.admin")) {
            if (args.length == 1 && "config".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                out.add("config");
            }
            return out;
        }
        if (args.length == 1) {
            for (String s : List.of("give", "list", "reload", "check", "config")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("check"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (WeaponType t : WeaponType.values()) {
                if (t.id().startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(t.id());
                }
            }
            for (String extra : List.of("dihrama", "trinity_dihrama", "magazine", "empty_magazine")) {
                if (extra.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(extra);
                }
            }
        }
        return out;
    }
}
