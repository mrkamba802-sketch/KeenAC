# KeenAC

Lightweight AirStuck detection for Paper and Purpur servers.

## Compatibility

- Paper / Purpur 1.21.4 and newer, including 26.x builds that retain the Bukkit/Paper API used by this plugin.
- Java 21 bytecode. Run 26.x servers with the Java version required by that server (Java 25 for current 26.x Paper builds).

## Build

Run `gradle build` with JDK 21. The plugin JAR is written to `build/libs/KeenAC-1.0.0.jar`.

A GitHub Actions workflow builds the JAR on each push and publishes it as a workflow artifact.

## Detection

AirStuck reports a player who remains effectively motionless in mid-air for the configured number of ticks. Detection is reset by movement, landing, teleportation, world changes, or exempt movement states. A suspected player has relevant gameplay interactions cancelled until the suspicion clears.

Alerts use the format `&bKeenAC &8» &f<player> &7failed &eAirStuck &8(VL: <level>)` and are rate-limited to one per player every 3 seconds.

Operators are not exempt from checks. The `keenac.alerts` permission controls who receives alerts; it does not bypass detection.
