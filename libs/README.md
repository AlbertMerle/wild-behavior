# Local mod jars (dev only)

Wild Behavior does **not** bundle these. They are for optional in-dev testing.

## Alex's Mobs (Fabric only)

The Fabric ports of Alex's Mobs and Citadel are CurseForge-only. Place jars here to test Alex integration in `./gradlew :fabric:runClient`.

| Save as | CurseForge |
|---------|------------|
| `citadelfabric-26.2-1.0.0.jar` | Citadel (Fabric) |
| `alexsmobsfabric-26.2-1.0.0.jar` | Alex's Mobs (Fabric) |

NeoForge Alex jars are not wired yet; Alex mixins stay optional at runtime.

## Balkon's WeaponMod: Legacy (Fabric only)

Optional gun/projectile testing for WeaponScare (musket, flintlock, mortar, cannon, etc.).

| Save as | Notes |
|---------|--------|
| `weaponmod-fabric-26.2-1.25.0.jar` | Loaded via `localRuntime` when present |

WeaponMod projectiles extend vanilla `AbstractArrow`; Wild Behavior also remembers recent shots and tracks WeaponMod `AdvancedExplosion` (mortar/cannon). Requires Architectury + Cloth Config (already on the Fabric run classpath).

## Cloth Config

Optional in-game config UI. End users install Cloth separately.

- Fabric Maven: `me.shedaniel.cloth:cloth-config-fabric:26.2.155`
- NeoForge Maven: `me.shedaniel.cloth:cloth-config-neoforge:26.2.155`

## Required runtime mods (not in this folder)

- **Architectury API 21.0.7+** on both Fabric and NeoForge
- **Fabric API** on Fabric (Architectury Fabric requires it)
- **Mod Menu** on Fabric for the Mods → Configure button
