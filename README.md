# KeenAC

AirStuck detection for Paper and Purpur powered by PacketEvents.

## Dependencies

- PacketEvents 2.x is a required server plugin (hard dependency).
- Gradle compiles against PacketEvents Spigot module 2.14.0 with `compileOnly`; PacketEvents is not shaded into KeenAC.
- Install PacketEvents separately in the server's `plugins` folder before KeenAC.

## Compatibility

- Compiled against the Paper 1.21.4 API and Java 21 bytecode.
- Intended for Paper / Purpur 1.21.4 and newer, with PacketEvents 2.x supporting the target protocol versions.
- Use the Java runtime required by your server version.

## Build

Run `gradle test` to execute the regression tests, or `gradle build` with JDK 21 to run tests and build the plugin. The plugin JAR is written to `build/libs/KeenAC-1.2.2.jar`.

GitHub Actions builds the JAR on each push and publishes it as a workflow artifact.

## Detection

AirStuck position samples are decoded from incoming client movement packets through PacketEvents. Bukkit's main thread evaluates server-side collision support and advances the stationary-in-air counter. Client-provided on-ground flags are not trusted as proof of support.

Detection resets on movement, landing, teleportation, world changes, and exempt movement states. A suspected player has common gameplay interactions cancelled until the suspicion clears.

Alerts use the configured format and a shared real-time cooldown per player and check. The default format displays the escalation as `x1`, `x2`, `x3`, and so on.

Operators are not exempt from checks. The `keenac.alerts` permission controls who receives alerts; it does not bypass detection.


## Evidence and staff review

Every check signal is recorded with the check name, violation level, reason, and a shared player-context snapshot (ping, TPS, world/position, velocity, movement state, active potion effects, and the age of the last observed teleport and velocity event). The bounded in-memory history is configured under `evidence`; defaults are 512 tracked players and 40 records per player. History is not persisted across server restarts. Set `evidence.enabled: false` to disable recording.

Staff with `keenac.review` can inspect the latest records using `/keenac review <player>`. The permission defaults to operators and can be changed with `commands.review-permission`. Alert notifications can be disabled independently; evidence recording remains active while the plugin is enabled.

The `checks.no-slow.potion-effects` option controls whether potion-driven movement attribute changes and Dolphin's Grace are included in the NoSlow movement allowance. When disabled, the check uses the base movement attribute and does not apply the Dolphin's Grace bonus.

## Motion diagnostics

The Motion module exposes `KeenAC#getMotionCheck().recordSuspicion(player, reason)`
for trusted movement-analysis components to submit diagnostic signals. Five
signals by default inside the configured rolling window start a staff review; further
signals refresh the review window. Console diagnostics include active potion
effects, the effective movement-speed attribute, walk/fly speed settings,
recent velocity events, and movement states such as flying, gliding, swimming,
riptiding, and vehicles.

This module is intentionally advisory: it does not calculate movement
violations on its own and never teleports, freezes, or otherwise restricts a
player. Signals must be supplied by another component.
