package dev.weapons;

import java.util.Locale;

enum WeaponType {
    DURANDAL("durandal", "Durandal", "IRON_SWORD"),
    ELDRITCH_MACE("eldritch_mace", "Eldritch Mace", "MACE"),
    HALBERD_OF_DESPAIR("halberd_of_despair", "Halberd of Despair", "NETHERITE_SPEAR"),
    PRISMATIC_BLADE("prismatic_blade", "Prismatic Blade", "DIAMOND_SWORD"),
    WARDEN_WHIP("warden_whip", "Warden Whip", "WOODEN_SWORD"),
    MEDUSAS_REVOLVER("medusas_revolver", "Medusa's Revolver", "CROSSBOW"),
    MINERS_HANDY_TOOL("miners_handy_tool", "Miner's Handy Tool", "DIAMOND_PICKAXE"),
    EXCALIBUR("excalibur", "Excalibur", "IRON_SWORD"),
    FANTASY_TAMER("fantasy_tamer", "Fantasy Tamer", "STICK"),
    GREAT_AXE("great_axe", "Great Axe", "NETHERITE_AXE"),
    SEA_TRUNCHEON("sea_truncheon", "Sea Truncheon", "TRIDENT"),
    SONIC_SHOES("sonic_shoes", "Sonic's Shoes", "NETHERITE_BOOTS"),
    SMOKE_GRENADE("smoke_grenade", "Smoke Grenade", "EGG"),
    AK47("ak47", "AK-47", "CROSSBOW"),
    ROCKET_LAUNCHER("rocket_launcher", "Rocket Launcher", "CROSSBOW");

    private final String id;
    private final String displayName;
    private final String defaultMaterial;

    WeaponType(String id, String displayName, String defaultMaterial) {
        this.id = id;
        this.displayName = displayName;
        this.defaultMaterial = defaultMaterial;
    }

    /** These weapons can never receive (more) enchantments. */
    boolean sealed() {
        return this == DURANDAL || this == ELDRITCH_MACE || this == HALBERD_OF_DESPAIR || this == SEA_TRUNCHEON || this == AK47 || this == ROCKET_LAUNCHER;
    }

    /** Never breaks. (The Sea Truncheon has Mending and Unbreaking instead.) */
    boolean unbreakable() {
        return this == DURANDAL || this == ELDRITCH_MACE || this == HALBERD_OF_DESPAIR || this == SONIC_SHOES || this == AK47 || this == ROCKET_LAUNCHER;
    }

    String id() {
        return id;
    }

    String displayName() {
        return displayName;
    }

    String defaultMaterial() {
        return defaultMaterial;
    }

    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static WeaponType byId(String id) {
        for (WeaponType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    /** Accepts the id ("miners_handy_tool"), the display name ("Miner's Handy Tool") or similar spellings. */
    static WeaponType parse(String input) {
        String n = normalize(input);
        for (WeaponType t : values()) {
            if (normalize(t.id).equals(n) || normalize(t.displayName).equals(n)) {
                return t;
            }
        }
        return null;
    }
}
