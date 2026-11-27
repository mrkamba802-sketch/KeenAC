# Windfall checks adapted to KeenAC

This is a feature-level port, not a bulk copy of Windfall's plugin architecture.
The implementation is adapted for Java 21, Paper/Purpur 1.21.4+, and PacketEvents 2.14.x.
All added signals use KeenAC's shared alert/evidence pipeline and do not kick, ban,
cancel packets, or apply standalone punishments.

## Coverage map

### Combat (14)

| Windfall check | KeenAC integration |
| --- | --- |
| Aim | Existing `AimCheck`; moving-target accuracy and rotation history retained |
| Autoclicker | `WindfallCombatCheckSuite`; rolling attack-interval variance |
| Backtrack | `WindfallCombatCheckSuite`; recent target movement plus bounded ping review |
| Criticals | `WindfallCombatCheckSuite`; repeated airborne attacks without valid falling motion |
| Fast Heal | `WindfallCombatCheckSuite`; repeated item-consume completions at implausible intervals |
| Hitboxes | `WindfallCombatCheckSuite`; server look-ray versus target hitbox |
| Kill Aura | Existing `AuraCheck` is retained; new multi-target-per-window signal added to `WindfallCombatCheckSuite` |
| Macro | `WindfallCombatCheckSuite`; repeated ground-movement direction sequences |
| Multi Interact | `WindfallCombatCheckSuite`; distinct attack targets inside a short packet window |
| Reach | Existing `ReachCheck`; historical target hitboxes, bounded ping rewind, and a five-frame/250 ms attacker-position window are combined |
| Self Interact | `WindfallCombatCheckSuite`; self entity-id interaction |
| Sword Block | Disabled by default: legacy sword-block combat is not present on 1.21.4 |
| Crystal | `WindfallCombatCheckSuite`; rapid crystal place/attack timing |
| Interact Cursor | `WindfallCombatCheckSuite`; entity interaction versus server look-ray |

### Movement (34)

| Windfall check | KeenAC integration |
| --- | --- |
| Air Liquid Break / Place | `WindfallMovementCheckSuite`; repeated actions while falling or in liquid |
| Baritone | Existing `BaritoneCheck` and `BaritoneACheck`; retained and not duplicated |
| Elytra | Existing `MotionCheck` Elytra speed measurement plus new tick-sampled hover, ascent, and kick-boost signals |
| Far Break / Place | `WindfallMovementCheckSuite`; block interaction distance |
| Fast Break | `WindfallMovementCheckSuite`; repeated abnormally fast hard-block breaks |
| Flight / Speed / Motion | Existing `MotionCheck`; horizontal speed and sustained-hover models retained |
| Gravity / Simulation | `WindfallMovementCheckSuite`; elapsed-time-aware vertical gravity/drag prediction |
| Ground Spoof / NoFall | `WindfallMovementCheckSuite`; repeated client/server ground-state disagreement during falls |
| Illegal Move | `WindfallMovementCheckSuite`; non-finite/out-of-bounds and unexplained large position discontinuities |
| Invalid Break / Place / Place Cursor | `WindfallMovementCheckSuite`; invalid dig targets, invalid placement heights/chunks, look-ray mismatch |
| Multi Break / Place | `WindfallMovementCheckSuite`; action packet burst review |
| NoSlow | Existing `NoSlowCheck` and `NoSlowMovementSpeed`; server-authoritative item-use sampling, potion/attribute allowances, friction exemptions and setback evidence are retained (the Windfall version was a simpler packet-rate heuristic) |
| No Swing | `WindfallMovementCheckSuite`; block interactions without a recent arm-swing packet |
| Omni-Sprint | `WindfallMovementCheckSuite`; repeated backward movement while sprinting |
| Phase | `WindfallMovementCheckSuite`; movement segments crossing solid block boundaries |
| Position / Rotation Break and Place | `WindfallMovementCheckSuite`; target distance and look-angle review |
| Scaffold | Existing `ScaffoldCheck` retained; `Scaffold Support` adds support-block placement review |
| Step | `WindfallMovementCheckSuite`; repeated unusually high grounded step deltas |
| Timer | `WindfallMovementCheckSuite`; sustained movement packet-rate review |
| Velocity | `WindfallMovementCheckSuite`; server velocity impulse versus subsequent displacement |
| Wrong Break | `WindfallMovementCheckSuite`; server break event compared with the dig target |

### Packet (11) and Inventory (1)

These checks are implemented in `WindfallPacketCheckSuite`:

- Bad Packets, Chat, Chest Stealer, Client Brand, Crash, Creative, Exploit,
  Packet Order, Sprint, Transaction, Vehicle, and Inventory.

The Transaction signal uses modern server Ping/client Pong probes. Client-brand
strings are self-reported metadata and are only a review signal, not proof of cheating.

## Existing KeenAC checks preserved

The port does not replace or remove KeenAC's existing AirStuck, NoWeb, GhostMenu,
InvHit, WallHit, WallInteract, SilentSwap, InventoryAutoSwap, or other checks.
Aim, Reach, NoSlow, Baritone, Motion/Elytra, Aura, and Scaffold remain single
integrated areas rather than being duplicated by parallel check classes.

## Configuration

New Windfall-derived toggles are under `checks.windfall.combat`,
`checks.windfall.movement`, and `checks.windfall.packet` in `config.yml`.
Existing KeenAC checks continue to use their existing configuration sections.

## Validation notes

CI compilation and the repository's automated test task are run for each stage.
The archive itself includes an audit caveat that Windfall's checks were not
validated on a live server in that audit. Consequently, this port still requires
live server observation and threshold tuning before using alerts as enforcement
evidence. The new checks are advisory only by default.
