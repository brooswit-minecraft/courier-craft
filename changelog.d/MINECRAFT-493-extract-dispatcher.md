bump: minor

### Added
- **Dispatcher villager and delivery contracts**, extracted from Dynamic Vehicles into this new standalone mod: the Dispatch Board job site, destination offers, delivery contracts, pillager ambushes, and the contract HUD/waypoint marker. Registry names and resource locations keep the `dynamicvehicles` namespace they shipped under in Dynamic Vehicles, so an existing save with a placed Dispatch Board, a spawned Dispatcher, or a saved contract keeps resolving once Courier Craft is installed in its place.
- Runs standalone. The one exception: the contract HUD/waypoint overlay requires Dynamic Vehicles (client-side) too, since it deliberately keeps sharing that mod's existing mission-waypoint marker coexistence with Pathfinder rather than forking an independent copy that would silently stop coordinating with it when both mods are installed.
