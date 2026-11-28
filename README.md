# KeenAC

KeenAC currently provides the staff-alert and evidence foundation for Paper and Purpur servers. Detection checks have been removed so they can be redesigned independently.

## Compatibility

- Compiled against the Paper 1.21.4 API and Java 21 bytecode.
- Intended for Paper / Purpur 1.21.4 and newer.
- No PacketEvents plugin is required by this alerts-only build.

## Build

Run `gradle test` to execute the regression tests, or `gradle build` with JDK 21 to build the plugin. The plugin JAR is written to `build/libs/KeenAC-1.2.2.jar`.

GitHub Actions builds the JAR on each push and publishes it as a workflow artifact.

## Staff alerts

Staff with the `keenac.alerts` permission can toggle notifications using `/keenac alerts`. Notifications can also be enabled automatically for staff with that permission.

Alert delivery retains its configurable message format, console output, hover details, per-player/per-check cooldown, and escalating alert level (`x1`, `x2`, `x3`, and so on). The alert manager remains available to future checks through `KeenAC#getAlertManager().send(checkName, suspect, violationLevel, reason)`.

There are currently no active detection checks, so the plugin does not generate automatic violation alerts until checks are added again.

## Evidence and staff review

When a future component submits an alert, its signal is recorded with the check name, violation level, reason, and a player-context snapshot (ping, TPS, world/position, velocity, movement state, active potion effects, and the age of the last observed teleport and velocity event). The bounded in-memory history is configured under `evidence`; defaults are 512 tracked players and 40 records per player. History is not persisted across server restarts. Set `evidence.enabled: false` to disable recording.

Staff with `keenac.review` can inspect the latest records using `/keenac review <player>`. The permission defaults to operators and can be changed with `commands.review-permission`.

## Commands

- `/keenac alerts` — toggle staff alerts.
- `/keenac review <player>` — view recent evidence for a player.
- `/keenac reload` — reload the plugin configuration.
