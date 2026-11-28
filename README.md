# KeenAC

KeenAC currently includes an AirStuck movement check and the staff-alert/evidence foundation for Paper and Purpur.

## Dependencies

- PacketEvents 2.x is required at runtime.
- Gradle compiles against PacketEvents Spigot module 2.14.0 with `compileOnly`; PacketEvents is not shaded into KeenAC.
- Install PacketEvents separately in the server's `plugins` folder before KeenAC.

## Compatibility

- Compiled against the Paper 1.21.4 API and Java 21 bytecode.
- Intended for Paper / Purpur 1.21.4 and newer.

## Build

Run `gradle test` to execute the regression tests, or `gradle build` with JDK 21 to build the plugin. The plugin JAR is written to `build/libs/KeenAC-1.2.2.jar`.

GitHub Actions builds the JAR on each push and publishes it as a workflow artifact.

## AirStuck

AirStuck observes client flying, vehicle movement and teleport-confirm packets through PacketEvents. Packet decoding is queued off-thread; Bukkit player and collision-world inspection runs on the server thread.

The check has two signals:

- Position freeze: eight flying movement packets by default with unchanged vertical position and no physical support generate an alert.
- Packet freeze / Blink: after one second without flying packets while unsupported, the first two confirmed cases are quietly corrected to the last observed position; the third consecutive case generates an alert instead of another correction.

Teleport confirmations, vehicle movement, world changes, creative/spectator/flight states, levitation and slow falling reset or exempt the corresponding tracking state. Physical support is evaluated from block collision shapes and selected vehicle/entity collision bounds; unloaded chunks are treated as unknown rather than as proof of a violation.

Configuration is under `checks.air-stuck`. Set `enabled: false` to disable the check, or `setback.enabled: false` to keep alerts without the first/second packet-freeze corrections.

## Staff alerts

Staff with the `keenac.alerts` permission can toggle notifications using `/keenac alerts`. Notifications can also be enabled automatically for staff with that permission.

Alert delivery retains its configured message format, console output, hover details, per-player/per-check cooldown, and escalating alert level (`x1`, `x2`, `x3`, and so on). The AirStuck check sends signals through the existing `AlertManager`.

## Evidence and staff review

Each AirStuck signal is recorded with its check name, violation level, reason and a player-context snapshot (ping, TPS, world/position, velocity, movement state, active potion effects, and the age of the last observed teleport and velocity event). The bounded in-memory history is configured under `evidence`; defaults are 512 tracked players and 40 records per player. History is not persisted across server restarts.

Staff with `keenac.review` can inspect the latest records using `/keenac review <player>`.

## Commands

- `/keenac alerts` — toggle staff alerts.
- `/keenac review <player>` — view recent evidence for a player.
- `/keenac reload` — reload the plugin configuration.
