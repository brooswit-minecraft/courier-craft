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

**Coinstalled coordination, hosted proof (MINECRAFT-493 review comments
52327/52328/52330, 52345, 52552, 52567):** `ContractWaypointOverlayCoinstalledTest`
(in the `test` sourceSet) puts Dynamic Vehicles' real jar on the plain
JUnit test classpath only — a `test` task never goes through NeoForge's
mod loader at all, so this carries none of the "tries to load it as a mod,
needs Sable/dynamic-terrain" risk the earlier required-dependency attempt
hit on the main runtime classpath — and calls `ContractWaypointOverlay`'s
own bridge directly against Dynamic Vehicles' real `hud.MissionWaypointLayout`.
It proves, with the real production code path: a Dispatcher reservation
made through the bridge and a Pathfinder reservation made through Dynamic
Vehicles' own API never overlap, a shared bottom-edge placement resolves
correctly, and clearing the Dispatcher slot through the bridge releases it
for Pathfinder.

Beyond that logic-level proof, the paired dynamic-vehicles PR #212 also
runs a hosted `coinstalled-acceptance` CI job: a real NeoForge client, with
both mods' actual jars mod-loaded together (not a JUnit proxy), confirmed
green at dynamic-vehicles run
[42645429079](https://github.com/brooswit-minecraft/dynamic-vehicles/actions/runs/42645429079).
It verified `ModList` reports this mod loaded, the Dispatcher and
Pathfinder markers reserve non-overlapping, bottom-edge-stacked slots
through the real bridge, and clearing a contract releases the Dispatcher's
slot after real rendered frames.

**Version-pairing and config-upgrade handshake (review comment 52567,
findings 1–2):** a declared `neoforge.mods.toml` version range alone
cannot tell this mod whether an installed Dynamic Vehicles has actually
gone through the Dispatcher extraction — Dynamic Vehicles' real released
0.82.1 is pre-split and still registers the same `dynamicvehicles`
Dispatcher/Dispatch Board/menu/payload IDs this mod now also registers,
and the real post-split release's version number isn't known until the
external release-gate assigns it at merge time. `DynamicVehiclesCompatibility`
checks Dynamic Vehicles' own `CourierCraftBridgeSupport` marker class via
reflection at mod construction instead, and fails loudly with an
actionable message (not an opaque duplicate-registry-id crash) if it's
absent or declares an incompatible schema — see `DynamicVehiclesBridgeSchemaTest`,
which proves this against Dynamic Vehicles' real pre-split 0.82.1 jar.
`LegacyConfigMigration` similarly copies an existing
`dynamicvehicles-dispatcher-server.toml`/`delivery.showLegacyContractHud`
(pre-split filenames/keys) forward into this mod's own config on first
boot, so existing world/admin tuning survives the split instead of
silently resetting to defaults — see `LegacyConfigMigrationTest`.

**Exact supported upgrade pair:** any Dynamic Vehicles build whose jar
contains `CourierCraftBridgeSupport` with a `SCHEMA_VERSION` this mod
recognizes (currently `1`) — in practice, the first Dynamic Vehicles
release built from a tree containing PR #212 or later. Dynamic Vehicles
0.82.1 and any earlier release are pre-split and are correctly rejected at
mod construction with an actionable error, not merely a version-range
mismatch. Running without Dynamic Vehicles installed at all remains fully
supported (this mod's own standalone kernel copy renders the marker).

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
