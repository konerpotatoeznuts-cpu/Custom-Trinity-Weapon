# CustomWeapons (Paper 26.2, Java 25)

Ten custom weapons with abilities. Everyone can use the abilities of a weapon they hold; weapons only come from `/weapon give`. Optional WorldGuard support: abilities can never hurt players where the `pvp` flag is `deny`.

## Commands (OP / `customweapons.admin`)

```
/weapon give <player> <weapon>     e.g. /weapon give Steve excalibur
/weapon list
/weapon reload                     reloads config.yml
/weapon check [player]             shows why abilities might not work for someone
```

Weapon ids: `durandal`, `eldritch_mace`, `halberd_of_despair`, `prismatic_blade`, `warden_whip`,
`medusas_revolver`, `miners_handy_tool`, `excalibur`, `fantasy_tamer`, `great_axe`.
Abilities only work on items given with `/weapon give` (they carry a hidden tag). Weapons cannot be renamed at an anvil. Durandal, Eldritch Mace and Halberd of Despair are unbreakable and cannot be enchanted; the Revolver accepts only Unbreaking and Mending. (`/enchant` by OPs is not blocked.)

**Old items must be re-given** after this update to get the new stats and abilities.

## Weapons

| Weapon | Base item | Abilities |
|---|---|---|
| Durandal | iron sword | Hit: lifesteal + lightning (15s). Right-click: Heavenly Beam, 100 block range, 10 block radius, 5s, 1 heart/s, stuns, breaks blocks above the target (60s). Hidden Fire Aspect II + Sharpness I |
| Eldritch Mace | mace | Smash attack: 40% shockwave + crater. Right-click: dash (20s). Sneak + right-click: 3D pull (60s). Hidden Wind Burst/Density/Breach |
| Halberd of Despair | netherite spear | Hit: hotbar scramble, stun, 4 wither skeletons (40s); disables shields. Sneak + right-click: Reaping Spin, 4.5 blocks, 5s, 1 heart/s + Wither + slow pull (30s) |
| Prismatic Blade | diamond sword | Sneak-charge + right-click: flashbang (15s). Players get a white screen flash, mobs Blindness. Heavy: more damage, slower swing, Slowness II |
| Warden Whip | wooden sword | Right-click: sonic boom (30s). 4.5 block reach, hidden Knockback II |
| Medusa's Revolver | crossbow | 6 bullets. Reload time depends on bullets loaded: 1=1s, 2=2.4s, 3=3s, 4=3.8s, 5=4.5s, 6=5s |
| Miner's Handy Tool | diamond pickaxe | Diamond speed on all blocks, vein miner, auto-smelt |
| Excalibur | iron sword | Sneak + right-click: slash dash (15s). Right-click: rising slash, 2 block fan, 3 hearts, launches (15s). Hidden Fire Aspect II + Sharpness I |
| Fantasy Tamer | stick | Hit a wild mob to start a timing minigame (floating circle + shrinking ring, one round per difficulty point; early clicks cost lives) and tame it (not the Ender Dragon). Right-click a pet: sit/follow. Pets fight what you hit and what hits you. Sneak to charge, right-click: recall all pets within 20 blocks (60s) |
| Great Axe | netherite axe | 11 damage, slow, Slowness I. Right-click: throw. Hits a block: grapple (20s). Hits a mob/player: pulled to you, 3.5 hearts (25s) |
| Sea Truncheon | trident | Hidden Sharpness 5, Impaling 5, Unbreaking 5, Mending. Hold right-click and release like a vanilla trident throw: riptide dash anywhere (no cooldown); hitting a mob does a mace-style smash (stronger with Strength). Quick tap right-click: summon rain (20% thunderstorm, 10 min). Sneak-charge + right-click: tsunami (10s charge; 15s rain, 25s lake, 35s both). Dolphin's Grace II in water. Dead in desert/savanna/badlands |
| Sonic's Shoes | netherite boots (worn) | Run to build momentum (faster downhill), auto-step blocks. Fast enough: run on water, up walls, across ceilings. Sneak while running fast = roll. Jump = spin ball, land on a mob to hurt it and bounce. Sneak + right-click (up to 5) then stop sneaking = Spin Dash |
| Smoke Grenade | egg | Right-click: full throw. Sneak + right-click: low throw. Counter-Strike 2 throw speed, gravity and bounces; pops when it stops into a smoke cloud that fills the room (not through walls), lasts 18s, puts out fires. Arrows, bullets, throwables, explosions and every weapon ability punch holes in it |
| AK-47 | crossbow | Hold right-click: full auto (600 RPM, 30 rounds), CS2 spray pattern, headshots x4, wallbang through thin blocks. F / right-click empty = reload (arrows) |
| Rocket Launcher | crossbow | Right-click: fire a rocket. CS2 HE-grenade blast (8.75 blocks, no damage through walls, hurts you too). F = reload (firework rocket) |
| Cryo Tempest | bow | Frost arrows slow and freeze. Shoot while sneaking (aim up): the arrow flies on, let go of sneak and frost arrows rain where it is (25s). Shoot, then double-sneak mid-flight: pilot the arrow by looking, it explodes on the first hit; harder draw = faster, stronger, bigger blast, quicker turning (12s). Ammo: arrow |

The white bar in the hotbar is the normal item cooldown (like Ender Pearls). Weapons with two abilities only show it when
both are cooling down. Medusa's Revolver shows its gaze cooldown on the action bar.

Everything is tunable in `config.yml`.

## Textures


See `resourcepack-placeholders/README.txt`.

## Build

Requires JDK 25 and Maven: `mvn clean package` gives `target/CustomWeapons.jar`.
The included GitHub workflow does this for you (Actions tab, artifact `CustomWeapons-jar`).


### Sea Truncheon notes
- The wave is made of semi-transparent block displays (no collision). Bedrock players can't see those, so they get a particle wave instead.
- "Lake" = at least `lake-min-blocks` connected water blocks around you. "Rain" = storm with open sky above you.
- Powers are disabled in desert, savanna and badlands biomes (`disabled-biomes`). Riptide is implemented by the plugin (no real Riptide enchant), so it works on land and the trident can't be thrown.
- Flashbang now also applies Slowness III and removes jumping for `immobilize-seconds`.


### Damage
`damage:` in config.yml controls the global buff: ability damage is multiplied (default x2.5), 60% of it ignores armor, and weapon swings add 50% of their damage as armor-piercing damage. Strength multiplies the halberd spin, whip, revolver, great axe, Excalibur slash + uppercut and the trident smash (Strength I = x1.5, II = x2).
The flashbang's Java screen is six huge flat-white see-through panels boxed around your camera (covers first person and third person front and back), shown only to the flashed player. Bedrock uses a real camera fade.

### Sonic's Shoes notes
- Wear them as boots. The spin dash click works with an empty hand or non-weapon items (a custom weapon in hand keeps its own sneak + right-click).
- Wall and ceiling running has no time limit by default (`wall-max-ticks: 0`), which needs `allow-flight=true` in server.properties; without it set `wall-max-ticks: 60`.
- Running on water uses a client-side glass floor under you, so nothing changes in the world.
- Bedrock sees the tsunami as a thin wall of client-side water instead of display blocks.

### /weapons config (everyone)
Opens a menu: spin / trail / electric colors (incl. rainbow), spin ball, speed trail and electric sparks on or off, particles on or off for all weapons or each weapon, weapon sounds, and the flashbang white screen. Settings are saved per player and only change what that player sees and hears. Particles from a weapon are matched by the code that spawns them, so new weapons need an entry in `Fx.SOURCES` to get their own toggle.
- Spin dash tip: hold any item (an empty hand sends no right-click in the air) and sneak, then right-click up to 5 times. Keep sneaking to keep spinning; let go of sneak to launch.

## Death messages
When an ability kills a player, chat shows a message written for that weapon ("... was flattened by X's Eldritch Mace") instead of
"was killed by magic". Edit the lines in `Deaths.java`; turn off with `death-messages.enabled: false`.

## Animation hooks
`animations:` in config.yml runs console commands on weapon events (use, hit, fire, reload, throw, spin_dash_charge,
spin_dash_launch), so a model plugin (BetterModel, ModelEngine, Animated Java datapacks) can play an animation.
Java Edition only: Bedrock players (Geyser) cannot see display-entity models.

## Ability animations
Every ability now plays a built-in animation: a ghost copy of the weapon (with its own texture) slashes, smashes, spins, thrusts,
cuts upward or rises into the air around the player. Guns flip in front of you while reloading. Java players see it; Bedrock
players keep the particles. Turn off with `animations.ghost-weapon: false` (or per player: the weapon's particles toggle in `/weapons config`).
Custom Blockbench models can still be wired through the `animations:` command hooks (`ability_<name>` events).

## BetterModel animations
Install BetterModel, put a player model (Blockbench, like the `steve.bbmodel` BetterModel creates) in
`plugins/BetterModel/players/weapons.bbmodel` and add animations named `<weapon id>_<event>`: `halberd_of_despair_spin`,
`excalibur_uppercut`, `durandal_beam`, `ak47_reload`, `ak47_fire`, `rocket_launcher_reload`, `medusas_revolver_reload`,
`sonic_shoes_spin_dash_launch`, ... The plugin plays them on the player with BetterModel's API (nothing to install on the
plugin side, it finds BetterModel by itself). Missing animation = the built-in ghost weapon plays instead.
Test one by hand (op): `/bettermodel play weapons ak47_reload`. Settings under `animations.bettermodel` in config.yml.