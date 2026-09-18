# Vonix Minecraft package map — what runs where (2026-09)

Canonical delivery architecture after the VSU 2.2.0 / Companion 2.2.0 architecture split (2026-09-13 releases). This file is the short index; operator-facing setup guides live on share.vonix.network.

## Repositories

| Repo | Visibility | Role |
|---|---|---|
| `Vonix-Network/Vonix-Server-Utilities` (this repo) | public | VSU standalone essentials mod — no Venary/site/API code |
| `Vonix-Network/Vonix-Server-Utilities-Companion` | private | Vonix Website Companion — Venary transport + LuckPerms control (separate mod) |
| `Vonix-Network/Vonix-Minecraft-Player-Panel` | private | VSU Panel Bridge (optional in-game panel mod) + future web Player Panel |

## Delivery matrix

| Package | Loader cells | Installed on servers |
|---|---|---|
| **VSU** (this repo, `vsu-<mc>-<loader>-<ver>.jar`) | 1.18.2 f/f, 1.19.2 f/f, 1.20.1 f/f, 1.21.1 fabric+neoforge, 26.1.2 neoforge | all modded servers (essentials feature set) |
| **Vonix Website Companion** (`vonix_website_companion-<mc>-<loader>-<ver>.jar`) | 1.21.1 fabric+neoforge, 26.1.2 neoforge (older cells = non-release WIP branch) | 1.21.1+ / 26.1.2 servers needing donation-rank sync |
| **VSU Panel Bridge** (`vonix_panel_bridge-<loader>-<ver>.jar`) | 1.21.1 fabric+neoforge (Architectury) | alone/optional — disabled unless a secret-free `config/vonix-panel-bridge.properties` with `bridge_enabled=true` exists |

Rules of thumb:

- 1.18.2–1.20.1 servers: VSU only. No companion cells ship for those lanes; donation ranks are not mod-synced there.
- 1.21.1 / 26.1.2 servers: VSU always; add the Companion only where donation-rank sync is wanted; the Panel Bridge is a separate optional jar and never a VSU dependency.
- LuckPerms is enforced by VSU permission nodes; the Companion (and Panel Bridge) fail closed when LuckPerms or Venary is absent/misconfigured.
- Release artifacts live in each repo's GitHub Releases (tag `v<version>`) with `SHA256SUMS`; do not build from stale local trees for production.

## Version independence

VSU, Companion, and Player Panel version independently. The Companion's current release 2.2.0 is not aligned with VSU 2.2.0 by contract — check each repo's releases.
