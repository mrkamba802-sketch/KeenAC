# KeenAC

Lightweight AirStuck detection for Paper and Purpur servers.

## Compatibility

- Compiled against the Paper 1.21.4 API and Java 21 bytecode.
- Uses the shared Bukkit/Paper API surface and is intended for Paper / Purpur 1.21.4 and newer.
- Run 26.x servers with the Java version required by that server (Java 25 for current Paper 26.3 builds).

## Build

Run `gradle build` with JDK 21. The plugin JAR is written to `build/libs/KeenAC-1.0.0.jar`.

A GitHub Actions workflow builds the JAR on each push and publishes it as a workflow artifact.

## Detection

AirStuck reports a player who remains effectively motionless and unsupported by block collision geometry for the configured number of **server ticks**. Bukkit/Paper does not expose a reliable per-player client-tick counter through this API, so this implementation does not claim to measure raw client ticks.

Detection is reset by movement, landing, teleportation, world changes, or exempt movement states. A suspected player has common gameplay interactions cancelled until the suspicion clears.

Alerts use the format `&bKeenAC &8» &f<player> &7failed &eAirStuck &8(VL: <level>)` and are rate-limited to one per player every 3 seconds.

Operators are not exempt from checks. The `keenac.alerts` permission controls who receives alerts; it does not bypass detection.
