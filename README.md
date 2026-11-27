# KeenAC 1.0.0

KeenAC is currently a clean core shell for a future anti-cheat integration. **No detection checks are included or registered.** This keeps the alert, evidence and player-context systems available while check implementations are being replaced.

## Core features

- Staff alert preferences and configurable alert formatting/cooldowns.
- Bounded in-memory evidence history with player context snapshots.
- `/keenac alerts` — toggle staff notifications.
- `/keenac review <player>` — review the latest evidence records.
- `/keenac reload` — reload core configuration.

The alert API remains available through `KeenAC#getAlertManager().send(checkName, player, violationLevel, reason)`. Evidence can also be added through `KeenAC#getEvidenceManager()`. No automatic punishment or detection logic is part of this core.

## Configuration

Only the core systems remain in `config.yml`:

- `alerts` — enablement, cooldown, console output, message format and automatic staff opt-in.
- `evidence` — enablement and in-memory history limits.
- `commands` — permission nodes.

## Compatibility and build

- Paper / Purpur 1.21.4+.
- Java 21.
- Run `gradle test` for regression tests or `gradle build` to build `build/libs/KeenAC-1.0.0.jar`.

The project no longer requires PacketEvents while it contains no packet-based checks. Add the dependency back when the next check implementation requires it.
