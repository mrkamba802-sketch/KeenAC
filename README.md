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

Run `gradle build` with JDK 21. The plugin JAR is written to `build/libs/KeenAC-1.1.0.jar`.

GitHub Actions builds the JAR on each push and publishes it as a workflow artifact.

## Detection

AirStuck position samples are decoded from incoming client movement packets through PacketEvents. Bukkit's main thread evaluates server-side collision support and advances the stationary-in-air counter. Client-provided on-ground flags are not trusted as proof of support.

Detection resets on movement, landing, teleportation, world changes, and exempt movement states. A suspected player has common gameplay interactions cancelled until the suspicion clears.

Alerts use `&bKeenAC &8» &f<player> &7failed &eAirStuck &8(VL: <level>)`, rate-limited to one per player every 3 seconds.

Operators are not exempt from checks. The `keenac.alerts` permission controls who receives alerts; it does not bypass detection.
