# courier-craft

Dispatcher-driven delivery contracts, extracted from Dynamic Vehicles into their own NeoForge mod.

NeoForge 1.21.1, Java 21. Mod ID: `couriercraft`. MIT licensed.

Ships the Dispatcher villager and delivery-contract gameplay, extracted from
[Dynamic Vehicles](https://github.com/brooswit-minecraft/dynamic-vehicles)
(MINECRAFT-493): the Dispatch Board job site, destination offers, delivery
contracts, pillager ambushes, and the contract HUD/waypoint.

## Install / upgrade pairing

Runs fully standalone: nothing here requires Dynamic Vehicles. When Dynamic
Vehicles IS also installed (an optional, client-only `neoforge.mods.toml`
dependency), the Dispatcher HUD/waypoint overlay (`ContractWaypointOverlay`)
detects it at runtime and delegates to its real mission-waypoint
coexistence kernel instead of this mod's own standalone copy, so
Dispatcher's and Pathfinder's markers keep coordinating overlap avoidance
exactly as before this mod existed. Only `ContractWaypointOverlay`'s own
compile-time reference to Dynamic Vehicles' classes is ever resolved, and
only when that mod is actually present (`ModList.isLoaded`) — Dynamic
Vehicles is compileOnly, never on this mod's own runtime classpath, so this
mod's build and tests never attempt to load it as a mod at all (and so
never need ITS OWN required dependencies, a licensed physics companion and
a separately-released terrain mod, which is what failed when an earlier
version of this made the dependency required instead of optional).

An existing world that placed a Dispatch Board, spawned a Dispatcher
villager, or saved a delivery contract under the pre-split Dynamic Vehicles
keeps resolving all three once upgraded to a Dynamic Vehicles release past
this split **with Courier Craft installed alongside it** — every moved
registry object kept its pre-split `dynamicvehicles` registry name, and
`DeliveryContractStorage` reads the exact same `dynamicvehicles_contracts`
NBT schema the old, not-yet-split Dynamic Vehicles wrote. Upgrading Dynamic
Vehicles past this split *without* installing Courier Craft is not a
supported pairing: the Dispatcher simply doesn't come back, the same as
removing any other mod. `DispatcherGameTests`, `SettlementFounderGameTests`,
and `UpgradeCompatibilityGameTests` (registry-name resolution and the saved
contract NBT) express this mod's behavioral/compatibility test coverage,
run in CI via `gradlew runGameTestServer` (see `scripts/check_gametest_count.py`
for the fail-closed guard, including an explicit check that both
`UpgradeCompatibilityGameTests` methods specifically ran).

Root cause of the earlier "zero `@GameTest` methods discovered" CI failure,
for the record: NeoForge selects a `@GameTest` by its *effective*
namespace — the enclosing `@GameTestHolder`'s, UNLESS the test's own
`templateNamespace` overrides it, which changes which namespace the test is
*selected* under too, not just where its structure is looked up. The three
GameTest classes above all set `templateNamespace = "dynamicvehicles"` (the
structures they reuse kept that legacy namespace), so `build.gradle`'s
`enabledGameTestNamespaces` must include `dynamicvehicles` alongside this
mod's own `couriercraft`, or every one of them is silently filtered out
before registration ever happens.

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
