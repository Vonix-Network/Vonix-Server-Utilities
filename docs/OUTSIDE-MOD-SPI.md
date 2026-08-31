# Outside-mod SPI (VSU 2.0.2)

VSU is a **standalone** public core. It does not ship, require, or call a website, Venary HTTP client, API key, or companion mod.

Optional companions (for example a website Player Panel) must live in a **separate** repository and compile only against the public package:

`network.vonix.serverutilities.api`

## Discoverability

Call `VonixPanels.current()`. The optional is empty until VSU finishes server start. Companion presence does not enable any feature gate.

## Allowed surface

- Immutable player/server projections (`HomeSnapshot`, `LastDeathSnapshot`, `PlayerStateSnapshot`, `InventorySnapshot`, `ServerSnapshot`)
- Capability reporting (`PanelCapabilities`, `PanelFeatureKeys`)
- Permission-checked teleport to spawn or a named existing home (`PanelTeleportRequest` / `PanelTeleportResult`)
- Bounded lifecycle events (`registerListener`) with a listener cap of 8 and a 64-event buffer that drops oldest

## Rejected

- JDBC/SQLite, SQL, database paths, live entities/worlds/servers
- Arbitrary commands, free coordinates, console execution, permission bypass
- Credentials, API keys, JWTs, cookies, authorization headers, Venary/website URLs, HTTP clients

## Compatibility

- SemVer of this package follows the VSU release line (`2.0.2`).
- Feature keys default fail-closed except last-death projection.
- Teleport requests must run on the Minecraft server thread; off-thread calls are handed off when a server is bound, otherwise rejected.
- Actor UUID must equal the target player UUID.
