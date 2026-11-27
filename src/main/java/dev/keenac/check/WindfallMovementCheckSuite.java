package dev.keenac.check;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import dev.keenac.KeenAC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Windfall movement and block-interaction adaptations.
 *
 * The suite deliberately reports advisory evidence only. Existing KeenAC Motion,
 * Elytra, NoSlow, Scaffold and Baritone checks remain authoritative for their
 * overlapping areas and are not duplicated here.
 */
public final class WindfallMovementCheckSuite extends PacketListenerAbstract implements Listener {
    private static final long ACTION_WINDOW_NANOS = 50_000_000L;
    private static final long SWING_GRACE_NANOS = 350_000_000L;

    private final KeenAC plugin;
    private final Map<UUID, MovementState> states = new ConcurrentHashMap<>();

    public WindfallMovementCheckSuite(KeenAC plugin) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying packet = new WrapperPlayClientPlayerFlying(event);
            if (!packet.hasPositionChanged()) return;
            var location = packet.getLocation();
            if (location == null) return;
            double x = location.getX();
            double y = location.getY();
            double z = location.getZ();
            boolean onGround = packet.isOnGround();
            Bukkit.getScheduler().runTask(plugin,
                    () -> recordPacketMovement(id, x, y, z, onGround));
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
            DiggingAction action = packet.getAction();
            var position = packet.getBlockPosition();
            if (position == null) return;
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            Bukkit.getScheduler().runTask(plugin,
                    () -> recordDigging(id, action, x, y, z));
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement packet =
                    new WrapperPlayClientPlayerBlockPlacement(event);
            var position = packet.getBlockPosition();
            if (position == null) return;
            int x = position.getX();
            int y = position.getY();
            int z = position.getZ();
            Bukkit.getScheduler().runTask(plugin, () -> recordPlacement(id, x, y, z));
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(id);
                if (online != null) state(online).lastSwingNanos = System.nanoTime();
            });
        }
    }

    private void recordPacketMovement(UUID id, double x, double y, double z, boolean clientGround) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) return;
        MovementState state = state(player);
        long now = System.nanoTime();

        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || y < player.getWorld().getMinHeight() - 64.0
                || y > player.getWorld().getMaxHeight() + 64.0) {
            if (enabled("illegal-move")) {
                flag(player, "Illegal Move", "Movement packet contained non-finite or out-of-world coordinates.");
            }
            return;
        }

        if (state.hasPacketPosition) {
            double dx = x - state.lastPacketX;
            double dy = y - state.lastPacketY;
            double dz = z - state.lastPacketZ;
            reviewGravitySimulation(player, state, dy, Math.hypot(dx, dz), clientGround, now);
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            long teleportAge = plugin.getPlayerContextManager().snapshot(player).teleportAgeMs();
            if (enabled("illegal-move") && distance > 8.0
                    && (teleportAge < 0L || teleportAge > 1_500L)) {
                state.illegalMoveSamples++;
                if (state.illegalMoveSamples >= 2) {
                    flag(player, "Illegal Move", String.format(java.util.Locale.ROOT,
                            "Large packet position discontinuity of %.2f blocks without a recent server teleport.",
                            distance));
                    state.illegalMoveSamples = 0;
                }
            } else {
                state.illegalMoveSamples = Math.max(0, state.illegalMoveSamples - 1);
            }

            // Timer A: count movement packets in a rolling second. Require
            // multiple over-limit windows to reduce sensitivity to burst delivery.
            if (enabled("timer")) {
                state.movementPackets.addLast(now);
                while (!state.movementPackets.isEmpty()
                        && now - state.movementPackets.peekFirst() > 1_000_000_000L) {
                    state.movementPackets.removeFirst();
                }
                if (state.movementPackets.size() > 28) {
                    state.timerWindows++;
                    if (state.timerWindows >= 3) {
                        flag(player, "Timer", "Sustained movement packet rate exceeded the conservative 28 packets/second review threshold.");
                        state.timerWindows = 0;
                        state.movementPackets.clear();
                    }
                } else if (state.timerWindows > 0) {
                    state.timerWindows--;
                }
            }
        }

        state.lastPacketX = x;
        state.lastPacketY = y;
        state.lastPacketZ = z;
        state.lastPacketNanos = now;
        state.hasPacketPosition = true;

        // Ground Spoof / NoFall: require repeated client-ground claims while
        // the server still considers the player airborne and descending.
        if (clientGround && !player.isOnGround() && !player.isInsideVehicle()
                && !player.isFlying() && !player.isGliding() && !player.isSwimming()) {
            state.groundSpoofSamples++;
            if (state.airborneStartY == 0.0) state.airborneStartY = player.getLocation().getY();
            if (enabled("ground-spoof") && state.groundSpoofSamples >= 4) {
                flag(player, "Ground Spoof", "Repeated client on-ground claims disagreed with the server's airborne state.");
                state.groundSpoofSamples = 0;
            }
            double fallDistance = state.airborneStartY - y;
            if (enabled("no-fall") && fallDistance >= 3.0 && state.groundSpoofSamples >= 2) {
                flag(player, "NoFall", String.format(java.util.Locale.ROOT,
                        "Client reported ground during a %.2f-block airborne descent.",
                        fallDistance));
                state.groundSpoofSamples = 0;
            }
        } else if (!clientGround) {
            state.groundSpoofSamples = Math.max(0, state.groundSpoofSamples - 1);
        } else {
            state.groundSpoofSamples = 0;
            state.airborneStartY = 0.0;
        }
    }

    private void reviewGravitySimulation(Player player, MovementState state,
                                          double deltaY, double horizontalDelta,
                                          boolean clientGround, long now) {
        if (clientGround || player.isOnGround() || isGravityExempt(player)) {
            state.previousVerticalDelta = 0.0;
            state.gravitySamples = 0;
            state.simulationSamples = 0;
            return;
        }
        if (now - state.lastPacketNanos > 300_000_000L) {
            state.previousVerticalDelta = deltaY;
            state.gravitySamples = 0;
            state.simulationSamples = 0;
            return;
        }

        var context = plugin.getPlayerContextManager().snapshot(player);
        if ((context.teleportAgeMs() >= 0L && context.teleportAgeMs() < 500L)
                || (context.velocityAgeMs() >= 0L && context.velocityAgeMs() < 500L)
                || horizontalDelta < 0.04) {
            state.previousVerticalDelta = deltaY;
            state.gravitySamples = 0;
            state.simulationSamples = 0;
            return;
        }

        // Modern vanilla vertical motion: gravity followed by air drag.
        double predicted = (state.previousVerticalDelta - 0.08) * 0.98;
        double deviation = Math.abs(deltaY - predicted);

        if (enabled("gravity")) {
            if (deviation > 0.26) {
                state.gravitySamples++;
                if (state.gravitySamples >= 8) {
                    flag(player, "Gravity", String.format(java.util.Locale.ROOT,
                            "Repeated vertical acceleration differed from the vanilla gravity model by %.3f blocks/tick.", deviation));
                    state.gravitySamples = 0;
                }
            } else {
                state.gravitySamples = Math.max(0, state.gravitySamples - 1);
            }
        }

        if (enabled("simulation")) {
            if (deviation > 0.18) {
                state.simulationSamples++;
                if (state.simulationSamples >= 12) {
                    flag(player, "Simulation", String.format(java.util.Locale.ROOT,
                            "Repeated movement samples differed from the server-side vertical simulation by %.3f blocks/tick.", deviation));
                    state.simulationSamples = 0;
                }
            } else {
                state.simulationSamples = Math.max(0, state.simulationSamples - 1);
            }
        }
        state.previousVerticalDelta = deltaY;
    }

    private boolean isGravityExempt(Player player) {
        return player.isInsideVehicle() || player.isFlying() || player.isGliding()
                || player.isSwimming() || player.isInWater() || player.isClimbing()
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    private void recordDigging(UUID id, DiggingAction action, int x, int y, int z) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) return;
        MovementState state = state(player);
        long now = System.nanoTime();

        if (action == DiggingAction.START_DIGGING) {
            state.lastDigPosition = new BlockPos(x, y, z);
            state.lastDigStartNanos = now;
            if (enabled("multi-break")) {
                if (now - state.breakWindowStart > ACTION_WINDOW_NANOS) {
                    state.breakWindowStart = now;
                    state.breakPackets = 0;
                }
                state.breakPackets++;
                if (state.breakPackets > 2) {
                    flag(player, "Multi Break", "More than two block-dig start packets arrived within one 50 ms window.");
                    state.breakPackets = 0;
                }
            }

            Block block = player.getWorld().getBlockAt(x, y, z);
            if (enabled("invalid-break") && (block.getType().isAir()
                    || block.isLiquid() || block.getType() == Material.BEDROCK)) {
                state.invalidBreakSamples++;
                if (state.invalidBreakSamples >= 3) {
                    flag(player, "Invalid Break", "Repeated digging packets targeted air, liquid, or an unbreakable block.");
                    state.invalidBreakSamples = 0;
                }
            }

            reviewBreakGeometry(player, block, state);
        }
    }

    private void recordPlacement(UUID id, int x, int y, int z) {
        Player player = Bukkit.getPlayer(id);
        if (player == null || !player.isOnline()) return;
        MovementState state = state(player);
        long now = System.nanoTime();
        state.lastPlacePosition = new BlockPos(x, y, z);
        state.lastPlacePacketNanos = now;

        if (enabled("multi-place")) {
            if (now - state.placeWindowStart > ACTION_WINDOW_NANOS) {
                state.placeWindowStart = now;
                state.placePackets = 0;
            }
            state.placePackets++;
            if (state.placePackets > 2) {
                flag(player, "Multi Place", "More than two block-placement packets arrived within one 50 ms window.");
                state.placePackets = 0;
            }
        }

        if (enabled("invalid-place")
                && (y < player.getWorld().getMinHeight() || y >= player.getWorld().getMaxHeight()
                || !player.getWorld().isChunkLoaded(x >> 4, z >> 4))) {
            state.invalidPlaceSamples++;
            if (state.invalidPlaceSamples >= 2) {
                flag(player, "Invalid Place", "Repeated placement packets targeted an invalid height or unloaded chunk.");
                state.invalidPlaceSamples = 0;
            }
        }
    }

    private void reviewBreakGeometry(Player player, Block block, MovementState state) {
        Location eye = player.getEyeLocation();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        double centerDistance = eye.distance(center);
        double boxDistance = distanceToBlock(eye, block);

        if (enabled("far-break") && centerDistance > 6.0 && centerDistance < 12.0) {
            state.farBreakSamples++;
            if (state.farBreakSamples >= 2) {
                flag(player, "Far Break", String.format(java.util.Locale.ROOT,
                        "Dig target center was %.2f blocks from the player's eye position.",
                        centerDistance));
                state.farBreakSamples = 0;
            }
        }

        if (enabled("position-break") && boxDistance > 5.2 && boxDistance < 8.0) {
            state.positionBreakSamples++;
            if (state.positionBreakSamples >= 2) {
                flag(player, "Position Break", String.format(java.util.Locale.ROOT,
                        "Nearest point on the dig target was %.2f blocks away.",
                        boxDistance));
                state.positionBreakSamples = 0;
            }
        }

        if (enabled("rotation-break") && centerDistance < 5.5
                && angleTo(player.getEyeLocation(), center) > 72.0) {
            state.rotationBreakSamples++;
            if (state.rotationBreakSamples >= 3) {
                flag(player, "Rotation Break", "Repeated digging targets were far outside the player's look direction.");
                state.rotationBreakSamples = 0;
            }
        }

        if (enabled("air-liquid-break")
                && (!player.isOnGround() || player.isInWater() || player.isSwimming())) {
            state.airBreakSamples++;
            if (state.airBreakSamples >= 3) {
                flag(player, "Air Liquid Break", "Repeated block-dig actions occurred while airborne or in liquid.");
                state.airBreakSamples = 0;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        MovementState state = state(player);
        long now = System.nanoTime();
        Block block = event.getBlock();
        BlockPos actual = new BlockPos(block.getX(), block.getY(), block.getZ());

        if (enabled("wrong-break") && state.lastDigPosition != null
                && now - state.lastDigStartNanos < 500_000_000L
                && !state.lastDigPosition.equals(actual)) {
            flag(player, "Wrong Break", "Server block-break event did not match the most recent client dig target.");
        }

        if (enabled("no-swing")
                && (state.lastSwingNanos == 0L || now - state.lastSwingNanos > SWING_GRACE_NANOS)) {
            state.noSwingSamples++;
            if (state.noSwingSamples >= 3) {
                flag(player, "No Swing", "Repeated block breaks had no matching recent arm-swing packet.");
                state.noSwingSamples = 0;
            }
        } else {
            state.noSwingSamples = Math.max(0, state.noSwingSamples - 1);
        }

        if (enabled("fast-break") && isHardBlock(block.getType())
                && player.getGameMode() != org.bukkit.GameMode.CREATIVE
                && state.lastDigStartNanos > 0L) {
            long elapsedMs = (now - state.lastDigStartNanos) / 1_000_000L;
            if (elapsedMs < 90L) {
                state.fastBreakSamples++;
                if (state.fastBreakSamples >= 2) {
                    flag(player, "Fast Break", "Repeated hard-block breaks completed in under 90 ms.");
                    state.fastBreakSamples = 0;
                }
            } else {
                state.fastBreakSamples = Math.max(0, state.fastBreakSamples - 1);
            }
        }

        if (enabled("scaffold-support") && player.isOnGround()
                && block.getY() >= player.getLocation().getBlockY() - 1) {
            state.supportPlacements = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        MovementState state = state(player);
        long now = System.nanoTime();
        Block against = event.getBlockAgainst();

        if (enabled("far-place") || enabled("position-place") || enabled("rotation-place")
                || enabled("invalid-place-cursor")) {
            Location eye = player.getEyeLocation();
            Location center = event.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5);
            double distance = distanceToBlock(eye, against);

            if (enabled("far-place") && eye.distance(center) > 6.0) {
                state.farPlaceSamples++;
                if (state.farPlaceSamples >= 2) {
                    flag(player, "Far Place", "Repeated placement targets were beyond the expected block interaction range.");
                    state.farPlaceSamples = 0;
                }
            }

            if (enabled("position-place") && distance > 5.2 && distance < 8.0) {
                state.positionPlaceSamples++;
                if (state.positionPlaceSamples >= 2) {
                    flag(player, "Position Place", String.format(java.util.Locale.ROOT,
                            "Nearest point on the placement support block was %.2f blocks away.", distance));
                    state.positionPlaceSamples = 0;
                }
            }

            if (enabled("rotation-place") && eye.distance(center) < 5.5
                    && angleTo(eye, against.getLocation().add(0.5, 0.5, 0.5)) > 72.0) {
                state.rotationPlaceSamples++;
                if (state.rotationPlaceSamples >= 3) {
                    flag(player, "Rotation Place", "Repeated block placements were far outside the player's look direction.");
                    state.rotationPlaceSamples = 0;
                }
            }

            if (enabled("invalid-place-cursor")) {
                RayTraceResult hit = player.getWorld().rayTraceBlocks(
                        eye, eye.getDirection(), Math.min(6.0, eye.distance(against.getLocation().add(0.5, 0.5, 0.5))));
                if (hit != null && hit.getHitBlock() != null && !hit.getHitBlock().equals(against)) {
                    state.invalidCursorSamples++;
                    if (state.invalidCursorSamples >= 3) {
                        flag(player, "Invalid Place Cursor", "Placement support block did not match the first block intersected by the look ray.");
                        state.invalidCursorSamples = 0;
                    }
                }
            }
        }

        if (enabled("air-liquid-place")
                && (!player.isOnGround() || player.isInWater() || player.isSwimming())) {
            state.airPlaceSamples++;
            if (state.airPlaceSamples >= 3) {
                flag(player, "Air Liquid Place", "Repeated block placements occurred while airborne or in liquid.");
                state.airPlaceSamples = 0;
            }
        }

        if (enabled("no-swing")
                && (state.lastSwingNanos == 0L || now - state.lastSwingNanos > SWING_GRACE_NANOS)) {
            state.noSwingSamples++;
            if (state.noSwingSamples >= 3) {
                flag(player, "No Swing", "Repeated block placements had no matching recent arm-swing packet.");
                state.noSwingSamples = 0;
            }
        }

        if (enabled("scaffold-support")) {
            double feetGap = player.getLocation().getY() - event.getBlockPlaced().getY() - 1.0;
            if (!player.isOnGround() && feetGap >= -0.15 && feetGap <= 1.25) {
                state.supportPlacements++;
                if (state.supportPlacements >= 5) {
                    flag(player, "Scaffold Support", "Repeated support blocks were placed directly beneath the player during ascent.");
                    state.supportPlacements = 0;
                }
            } else {
                state.supportPlacements = Math.max(0, state.supportPlacements - 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        Player player = event.getPlayer();
        MovementState state = state(player);
        long now = System.nanoTime();
        reviewVelocityResponse(player, state, to.clone().subtract(from).toVector());
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        reviewElytra(player, state, dy, horizontal, now);

        if (enabled("omni-sprint") && player.isSprinting()
                && !player.isInsideVehicle() && !player.isFlying()
                && !player.isGliding() && !player.isSwimming() && horizontal > 0.08) {
            Vector forward = to.getDirection().setY(0.0);
            Vector movement = new Vector(dx, 0.0, dz);
            if (forward.lengthSquared() > 0.0001 && movement.dot(forward.normalize()) < -0.35) {
                state.omniSprintSamples++;
                if (state.omniSprintSamples >= 4) {
                    flag(player, "Omni-Sprint", "Repeated backward movement was observed while the player remained in sprint state.");
                    state.omniSprintSamples = 0;
                }
            } else {
                state.omniSprintSamples = Math.max(0, state.omniSprintSamples - 1);
            }
        }

        if (enabled("step") && dy > 0.85 && dy < 1.6
                && player.isOnGround() && !player.isInsideVehicle()
                && !player.isFlying() && !player.isGliding()
                && Math.abs(dx) + Math.abs(dz) > 0.05) {
            long velocityAge = plugin.getPlayerContextManager().snapshot(player).velocityAgeMs();
            if (velocityAge < 0L || velocityAge > 500L) {
                state.stepSamples++;
                if (state.stepSamples >= 2) {
                    flag(player, "Step", String.format(java.util.Locale.ROOT,
                            "Repeated grounded vertical movement of %.2f blocks exceeded the normal step review limit.", dy));
                    state.stepSamples = 0;
                }
            }
        }

        if (enabled("phase") && horizontal > 0.65 && horizontal < 2.5
                && !player.isInsideVehicle() && !player.isFlying() && !player.isGliding()) {
            Location start = from.clone().add(0.0, 0.9, 0.0);
            Vector path = to.toVector().subtract(from.toVector());
            RayTraceResult hit = player.getWorld().rayTraceBlocks(start, path, path.length());
            if (hit != null && hit.getHitBlock() != null
                    && hit.getHitPosition() != null
                    && hit.getHitPosition().distance(start.toVector()) < path.length() - 0.15) {
                state.phaseSamples++;
                if (state.phaseSamples >= 2) {
                    flag(player, "Phase", "Repeated movement segments crossed a solid block collision boundary.");
                    state.phaseSamples = 0;
                }
            } else {
                state.phaseSamples = Math.max(0, state.phaseSamples - 1);
            }
        }

        if (player.isOnGround()) {
            state.airborneStartY = 0.0;
            state.groundSpoofSamples = 0;
        } else if (state.airborneStartY == 0.0) {
            state.airborneStartY = from.getY();
        }
        state.lastMoveNanos = now;
    }

    private void reviewElytra(Player player, MovementState state,
                              double deltaY, double horizontalDelta, long now) {
        if (!enabled("elytra")) return;
        if (!player.isGliding()) {
            state.elytraTicks = 0;
            state.elytraHoverTicks = 0;
            state.elytraAscentSamples = 0;
            return;
        }

        state.elytraTicks++;
        if (Math.abs(deltaY) <= 0.005) {
            state.elytraHoverTicks++;
            if (state.elytraHoverTicks >= 40) {
                flag(player, "Elytra", "Sustained near-zero vertical movement was observed while gliding.");
                state.elytraHoverTicks = 0;
            }
        } else {
            state.elytraHoverTicks = Math.max(0, state.elytraHoverTicks - 2);
        }

        boolean recentFirework = state.lastFireworkNanos != 0L
                && now - state.lastFireworkNanos < 2_500_000_000L;
        if (state.elytraTicks > 5 && deltaY > 0.12 && !recentFirework
                && horizontalDelta > 0.10) {
            state.elytraAscentSamples++;
            if (state.elytraAscentSamples >= 4) {
                flag(player, "Elytra", "Repeated sustained ascent while gliding had no recent firework boost.");
                state.elytraAscentSamples = 0;
            }
        } else {
            state.elytraAscentSamples = Math.max(0, state.elytraAscentSamples - 1);
        }

        // MotionCheck already owns horizontal ElytraSpeed measurement; this class
        // only adds the hover/ascent signals to avoid duplicate speed flags.
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFireworkUse(PlayerInteractEvent event) {
        if (event.getItem() == null
                || event.getItem().getType() != Material.FIREWORK_ROCKET) return;
        state(event.getPlayer()).lastFireworkNanos = System.nanoTime();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        if (!enabled("velocity")) return;
        if (player.isInsideVehicle() || player.isFlying() || player.isGliding()
                || player.isSwimming() || player.isInWater()) return;
        Vector velocity = event.getVelocity().clone();
        double horizontal = Math.hypot(velocity.getX(), velocity.getZ());
        if (horizontal < 0.32) return;

        MovementState state = state(player);
        state.expectedKnockback = velocity;
        state.knockbackDirection = new Vector(velocity.getX(), 0.0, velocity.getZ()).normalize();
        state.knockbackProjection = 0.0;
        state.knockbackSamples = 0;
        state.knockbackStartedNanos = System.nanoTime();
    }

    private void reviewVelocityResponse(Player player, MovementState state, Vector delta) {
        if (!enabled("velocity") || state.expectedKnockback == null) return;
        long age = System.nanoTime() - state.knockbackStartedNanos;
        if (age > 450_000_000L || player.isInsideVehicle() || player.isFlying()
                || player.isGliding() || player.isSwimming() || player.isInWater()) {
            finishVelocityReview(player, state);
            return;
        }
        if (plugin.getPlayerContextManager().snapshot(player).pingMs() > 160) {
            state.expectedKnockback = null;
            return;
        }

        Location origin = player.getLocation().add(0.0, 0.9, 0.0);
        RayTraceResult obstruction = player.getWorld().rayTraceBlocks(
                origin, state.knockbackDirection, 0.55);
        if (obstruction != null && obstruction.getHitBlock() != null) {
            state.expectedKnockback = null;
            return;
        }

        state.knockbackProjection += delta.getX() * state.knockbackDirection.getX()
                + delta.getZ() * state.knockbackDirection.getZ();
        state.knockbackSamples++;
        if (state.knockbackSamples >= 4) finishVelocityReview(player, state);
    }

    private void finishVelocityReview(Player player, MovementState state) {
        if (state.expectedKnockback == null) return;
        double expected = Math.hypot(state.expectedKnockback.getX(),
                state.expectedKnockback.getZ()) * 0.85;
        if (state.knockbackSamples >= 3 && expected > 0.35
                && state.knockbackProjection < expected * 0.20) {
            state.velocityRejects++;
            if (state.velocityRejects >= 2) {
                flag(player, "Velocity", "Repeated server velocity impulses produced substantially less movement than expected.");
                state.velocityRejects = 0;
            }
        } else {
            state.velocityRejects = Math.max(0, state.velocityRejects - 1);
        }
        state.expectedKnockback = null;
        state.knockbackDirection = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        MovementState state = state(event.getPlayer());
        state.lastJumpNanos = System.nanoTime();
        state.airborneStartY = event.getFrom().getY();
        state.groundSpoofSamples = 0;
    }

    private double distanceToBlock(Location eye, Block block) {
        double x = clamp(eye.getX(), block.getX(), block.getX() + 1.0);
        double y = clamp(eye.getY(), block.getY(), block.getY() + 1.0);
        double z = clamp(eye.getZ(), block.getZ(), block.getZ() + 1.0);
        return eye.toVector().distance(new Vector(x, y, z));
    }

    private double angleTo(Location eye, Location target) {
        Vector direction = eye.getDirection().normalize();
        Vector toward = target.toVector().subtract(eye.toVector()).normalize();
        double dot = clamp(direction.dot(toward), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

    private boolean isHardBlock(Material type) {
        return switch (type) {
            case OBSIDIAN, CRYING_OBSIDIAN, ANCIENT_DEBRIS, NETHERITE_BLOCK,
                    REINFORCED_DEEPSLATE, ENDER_CHEST -> true;
            default -> false;
        };
    }

    private boolean enabled(String check) {
        return plugin.getConfig().getBoolean("checks.windfall.movement." + check + ".enabled", true);
    }

    private void flag(Player player, String check, String reason) {
        if (player == null || !player.isOnline() || !plugin.isEnabled()) return;
        MovementState state = state(player);
        int vl = state.violationLevels.merge(check, 1, (old, one) ->
                old == Integer.MAX_VALUE ? Integer.MAX_VALUE : old + 1);
        plugin.getAlertManager().send(check, player, vl, reason);
    }

    private MovementState state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), ignored -> new MovementState());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    public void clearAll() {
        states.clear();
    }

    private record BlockPos(int x, int y, int z) { }

    private static final class MovementState {
        private final Map<String, Integer> violationLevels = new HashMap<>();
        private final Deque<Long> movementPackets = new ArrayDeque<>();
        private BlockPos lastDigPosition;
        private BlockPos lastPlacePosition;
        private long lastDigStartNanos;
        private long lastPlacePacketNanos;
        private long lastSwingNanos;
        private long breakWindowStart;
        private long placeWindowStart;
        private long lastJumpNanos;
        private long lastMoveNanos;
        private long lastPacketNanos;
        private long knockbackStartedNanos;
        private double previousVerticalDelta;
        private Vector expectedKnockback;
        private Vector knockbackDirection;
        private double knockbackProjection;
        private int knockbackSamples;
        private int velocityRejects;
        private int elytraTicks;
        private int elytraHoverTicks;
        private int elytraAscentSamples;
        private long lastFireworkNanos;
        private int gravitySamples;
        private int simulationSamples;
        private double airborneStartY;
        private double lastPacketX;
        private double lastPacketY;
        private double lastPacketZ;
        private boolean hasPacketPosition;
        private int breakPackets;
        private int placePackets;
        private int invalidBreakSamples;
        private int invalidPlaceSamples;
        private int illegalMoveSamples;
        private int groundSpoofSamples;
        private int timerWindows;
        private int farBreakSamples;
        private int positionBreakSamples;
        private int rotationBreakSamples;
        private int farPlaceSamples;
        private int positionPlaceSamples;
        private int rotationPlaceSamples;
        private int invalidCursorSamples;
        private int airBreakSamples;
        private int airPlaceSamples;
        private int noSwingSamples;
        private int fastBreakSamples;
        private int supportPlacements;
        private int omniSprintSamples;
        private int stepSamples;
        private int phaseSamples;
    }
}
