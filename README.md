# KeenAC 1.0.0

KeenAC includes 60 combat, movement, packet and inventory checks backed by shared player/world state, version-aware movement models and latency compensation.

## Core detection architecture

- **Player tracking:** one per-player state model tracks positions, velocities, ground state, actions, attributes and potion effects.
- **World tracking:** compensated block/entity snapshots, world transitions, ghost-block resolution and deferred changes keep checks aligned with the server timeline.
- **Latency compensation:** modern Ping/Pong transaction probes estimate RTT and one-way delay; bounded history is used for target and world rewind.
- **Version mechanics:** physics and attribute snapshots are selected for the connected protocol and server version, including movement, Elytra, sprint, mining and reach-related attributes.
- **Compatibility:** protocol, server-fork, plugin and Bedrock compatibility gates can disable checks where the engine cannot make a reliable comparison.

All detections are sent through KeenAC's existing alert and evidence services. Automatic punishments and setback teleports are disabled in this integration. The engine is not capable of guaranteeing zero false positives; production thresholds still require monitoring and tuning on the target server.

## Core commands

- `/keenac alerts` — toggle staff notifications.
- `/keenac review <player>` — review recent evidence and player context.
- `/keenac reload` — reload check enablement and per-check settings.

## Configuration

Core notifications and evidence remain in `config.yml` under `alerts` and `evidence`. Individual checks have independent enablement, max-VL, punishment (forced off) and optional decay/setback thresholds under `checks.keenac`. Shared check defaults are under `checks.default`. Additional engine settings include latency, adaptive thresholds, Bedrock compatibility and check behavior.

## Compatibility and build

- Paper / Purpur 1.21.4+.
- Java 21.
- PacketEvents 2.14.x is required.
- Run `gradle test` or `gradle build` with JDK 21. Output: `build/libs/KeenAC-1.0.0.jar`.

Folia is explicitly detected and this build disables itself safely rather than reading Bukkit state from unsupported scheduler contexts.
