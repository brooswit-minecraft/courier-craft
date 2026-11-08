# courier-craft

Dispatcher-driven delivery contracts, extracted from Dynamic Vehicles into their own NeoForge mod.

NeoForge 1.21.1, Java 21. Mod ID: `couriercraft`. MIT licensed.

Ships the Dispatcher villager and delivery-contract gameplay, extracted from
[Dynamic Vehicles](https://github.com/brooswit-minecraft/dynamic-vehicles)
(MINECRAFT-493): the Dispatch Board job site, destination offers, delivery
contracts, pillager ambushes, and the contract HUD/waypoint.

## Install / upgrade pairing

Runs standalone for gameplay. The contract HUD/waypoint overlay alone
requires Dynamic Vehicles `[0.82.0,)` installed too (client-only), since it
deliberately keeps sharing that mod's existing mission-waypoint coexistence
with Pathfinder instead of forking an independent copy.

An existing world that placed a Dispatch Board, spawned a Dispatcher
villager, or saved a delivery contract under the pre-split Dynamic Vehicles
keeps resolving all three once upgraded to a Dynamic Vehicles release past
this split **with Courier Craft installed alongside it** — every moved
registry object kept its pre-split `dynamicvehicles` registry name, and
`DeliveryContractStorage` reads the exact same `dynamicvehicles_contracts`
NBT schema the old, not-yet-split Dynamic Vehicles wrote. Upgrading Dynamic
Vehicles past this split *without* installing Courier Craft is not a
supported pairing: the Dispatcher simply doesn't come back, the same as
removing any other mod. `UpgradeCompatibilityGameTests` is the automated
regression guard for both of those (registry-name resolution and the saved
contract NBT), run in CI via `gameTestServer`.

## Building

Requires a JDK 21 with `javac` on `JAVA_HOME`.

```sh
./gradlew build
```

## Releasing

Every PR that touches `src/` must add exactly one fragment under
`changelog.d/` (see `changelog.d/README.md`). Pushing to `main` with new
fragments tags a GitHub release automatically; nothing is committed back by
hand.
