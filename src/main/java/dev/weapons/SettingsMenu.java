package dev.weapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** /weapons config: every player can change how the weapon effects look for them. */
final class SettingsMenu implements Listener {

    private static final int SIZE = 45;
    private static final int[] WEAPON_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32};

    private static final class Holder implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final WeaponsPlugin plugin;
    private final PlayerSettings st;

    SettingsMenu(WeaponsPlugin plugin, PlayerSettings st) {
        this.plugin = plugin;
        this.st = st;
    }

    void open(Player p) {
        Holder h = new Holder();
        Inventory inv = plugin.getServer().createInventory(h, SIZE, Component.text("Weapon Settings", NamedTextColor.DARK_AQUA));
        h.inv = inv;
        render(p, inv);
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ drawing

    private static Component name(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack item(Material m, Component title, boolean glow, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        it.editMeta(meta -> {
            meta.displayName(title);
            meta.lore(lore);
            if (glow) {
                meta.setEnchantmentGlintOverride(true);
            }
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        });
        return it;
    }

    private ItemStack toggle(Material m, String label, boolean on, String... info) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(on ? "ON" : "OFF", on ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        for (String s : info) {
            lore.add(line(s));
        }
        lore.add(line("Click to turn " + (on ? "off" : "on")));
        return item(m, name(label, on ? NamedTextColor.GREEN : NamedTextColor.GRAY), on, lore);
    }

    private ItemStack colorItem(String label, PlayerSettings.Palette pal) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(pal.name(), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        lore.add(line("Left-click: next color"));
        lore.add(line("Right-click: previous color"));
        return item(pal.icon(), name(label, NamedTextColor.AQUA), true, lore);
    }

    private void render(Player p, Inventory inv) {
        inv.clear();
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), false, List.of());
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, pane);
        }
        inv.setItem(1, toggle(Material.FIREWORK_STAR, "All weapon particles", st.on(p, "fx.all"), "Master switch for what YOU see."));
        inv.setItem(3, toggle(Material.NOTE_BLOCK, "Weapon sounds", st.on(p, "sounds"), "Ability sounds you hear."));
        inv.setItem(5, toggle(Material.GLOWSTONE, "Flashbang screen", st.on(p, "flash.overlay"), "The white screen when flashed.", "Off = you still get the ringing and the slowdown."));
        inv.setItem(8, toggle(Material.CLOCK, "Low-FPS mode", st.lowFx(p), "Fewer and closer particles for YOU only.", "Use it if the game stutters. Off = everything as designed."));
        inv.setItem(7, item(Material.BARRIER, name("Reset everything", NamedTextColor.RED), false, List.of(line("Back to the defaults."))));

        inv.setItem(10, colorItem("Spin color", st.spin(p)));
        inv.setItem(11, colorItem("Speed trail color", st.trail(p)));
        inv.setItem(12, colorItem("Electric color", st.electric(p)));
        inv.setItem(14, toggle(Material.HEART_OF_THE_SEA, "Sonic: spin ball", st.on(p, "sonic.ball"), "You turn into a ball while spinning."));
        inv.setItem(15, toggle(Material.SUGAR, "Sonic: speed trail", st.on(p, "sonic.trail"), "Trail when you run fast."));
        inv.setItem(16, toggle(Material.LIGHTNING_ROD, "Sonic: electric sparks", st.on(p, "sonic.electric"), "Sparks around you at high speed."));

        WeaponType[] all = WeaponType.values();
        for (int i = 0; i < all.length && i < WEAPON_SLOTS.length; i++) {
            WeaponType t = all[i];
            Material icon = plugin.items().create(t).getType();
            boolean on = st.on(p, "fx." + t.id());
            inv.setItem(WEAPON_SLOTS[i], toggle(icon, t.displayName() + " particles", on, "Particles from this weapon's abilities."));
        }
        inv.setItem(40, item(Material.PAPER, name("Your settings are saved", NamedTextColor.YELLOW), false,
                List.of(line("They only change what YOU see and hear."))));
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder) || !(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof Holder)) {
            return;
        }
        int slot = e.getSlot();
        int dir = e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT ? -1 : 1;
        boolean changed = true;
        switch (slot) {
            case 1 -> st.toggle(p, "fx.all");
            case 3 -> st.toggle(p, "sounds");
            case 5 -> st.toggle(p, "flash.overlay");
            case 7 -> st.reset(p);
            case 8 -> st.setOn(p, "perf.low", !st.lowFx(p));
            case 10 -> st.cycle(p, "color.spin", "blue", dir);
            case 11 -> st.cycle(p, "color.trail", "blue", dir);
            case 12 -> st.cycle(p, "color.electric", "yellow", dir);
            case 14 -> st.toggle(p, "sonic.ball");
            case 15 -> st.toggle(p, "sonic.trail");
            case 16 -> st.toggle(p, "sonic.electric");
            default -> {
                changed = false;
                WeaponType[] all = WeaponType.values();
                for (int i = 0; i < all.length && i < WEAPON_SLOTS.length; i++) {
                    if (WEAPON_SLOTS[i] == slot) {
                        st.toggle(p, "fx." + all[i].id());
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            render(p, e.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }
}
