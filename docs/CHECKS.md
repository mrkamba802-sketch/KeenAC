# KeenAC checks and configuration

The check suites are implemented as native KeenAC modules for Java 21, Paper/Purpur 1.21.4+, and PacketEvents 2.14.x.
All added signals use KeenAC's shared alert/evidence pipeline and do not kick, ban,
cancel packets, or apply standalone punishments.

## Coverage map

### Combat (14)

| Check | KeenAC integration |
| --- | --- |
| Aim | Existing `AimCheck`; moving-target accuracy and rotation history retained |
| Autoclicker | `CombatCheckSuite`; rolling attack-interval variance |
| Backtrack | `CombatCheckSuite`; recent target movement plus bounded ping review |
| Criticals | `CombatCheckSuite`; repeated airborne attacks without valid falling motion |
| Fast Heal | Legacy-only upstream check (protocols 5–107); disabled by default on 1.21.4. The optional local item-consume timing signal is not enabled for this target |
| Hitboxes | `CombatCheckSuite`; server look-ray versus target hitbox |
| Kill Aura | Existing `AuraCheck` is retained; new multi-target-per-window signal added to `CombatCheckSuite` |
| Macro | `CombatCheckSuite`; repeated ground-movement direction sequences |
| Multi Interact | `CombatCheckSuite`; distinct attack targets inside a short packet window |
| Reach | Existing `ReachCheck`; historical target hitboxes, bounded ping rewind, and a five-frame/250 ms attacker-position window are combined |
| Self Interact | `CombatCheckSuite`; self entity-id interaction |
| Sword Block | Legacy-only upstream check (protocols 5–107); disabled by default because legacy sword-block combat is not present on 1.21.4 |
| Crystal | `CombatCheckSuite`; rapid crystal place/attack timing |
| Interact Cursor | `CombatCheckSuite`; entity interaction versus server look-ray |

### Movement (34)

| Check | KeenAC integration |
| --- | --- |
| Air Liquid Break / Place | `MovementCheckSuite`; repeated actions while falling or in liquid |
| Baritone | Existing `BaritoneCheck` and `BaritoneACheck`; retained and not duplicated |
| Elytra | Existing `MotionCheck` Elytra speed measurement plus new tick-sampled hover, ascent, and kick-boost signals |
| Far Break / Place | `MovementCheckSuite`; block interaction distance |
| Fast Break | `MovementCheckSuite`; repeated abnormally fast hard-block breaks |
| Flight / Speed / Motion | Existing `MotionCheck`; horizontal speed and sustained-hover models retained |
| Gravity / Simulation | `MovementCheckSuite`; elapsed-time-aware vertical gravity/drag prediction |
| Ground Spoof / NoFall | `MovementCheckSuite`; repeated client/server ground-state disagreement during falls |
| Illegal Move | `MovementCheckSuite`; non-finite/out-of-bounds and unexplained large position discontinuities |
| Invalid Break / Place / Place Cursor | `MovementCheckSuite`; invalid dig targets, invalid placement heights/chunks, look-ray mismatch |
| Multi Break / Place | `MovementCheckSuite`; action packet burst review |
| NoSlow | Existing `NoSlowCheck` and `NoSlowMovementSpeed`; server-authoritative item-use sampling, potion/attribute allowances, friction exemptions and setback evidence are retained; item-use tracking now also includes shields, bows, crossbows, tridents, spyglasses and goat horns (the source version was a simpler packet-rate heuristic) |
| No Swing | `MovementCheckSuite`; block interactions without a recent arm-swing packet |
| Omni-Sprint | `MovementCheckSuite`; repeated backward movement while sprinting |
| Phase | `MovementCheckSuite`; movement segments crossing solid block boundaries |
| Position / Rotation Break and Place | `MovementCheckSuite`; target distance and look-angle review |
| Scaffold | Existing `ScaffoldCheck` retained; `Scaffold Support` adds support-block placement review |
| Step | `MovementCheckSuite`; repeated unusually high grounded step deltas |
| Timer | `MovementCheckSuite`; sustained movement packet-rate review |
| Velocity | `MovementCheckSuite`; server velocity impulse versus subsequent displacement |
| Wrong Break | `MovementCheckSuite`; server break event compared with the dig target |

### Packet (11) and Inventory (1)

These checks are implemented in `PacketCheckSuite`:

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

New check-specific toggles are under `checks.combat`,
`checks.movement`, and `checks.packet` in `config.yml`.
Existing KeenAC checks continue to use their existing configuration sections.

## Validation notes

CI compilation and the repository's automated test task are run for each stage.
The archive itself includes an audit caveat that the source project's checks were not
validated on a live server in that audit. Consequently, this port still requires
live server observation and threshold tuning before using alerts as enforcement
evidence. The new checks are advisory only by default.
