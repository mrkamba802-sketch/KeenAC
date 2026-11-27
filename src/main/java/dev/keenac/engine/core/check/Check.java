package dev.keenac.engine.core.check;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.keenac.KeenAC;
import dev.keenac.engine.compat.trust.TrustFactorModel;
import dev.keenac.engine.core.player.TrackedPlayer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
public abstract class Check {
    protected final String name;
    protected final String stableKey;
    protected volatile boolean enabled;
    protected volatile boolean punishable;
    protected volatile double decay;
    protected volatile int maxVl;
    protected volatile int setbackVl;
    protected volatile int minVersion;
    protected volatile int maxVersion;
    protected final CompatFlag[] compatFlags;
    protected final double relaxMultiplier;
    protected final boolean disableOnFolia;
    protected final boolean disableOnPurpur;
    private final Map<UUID, Long> lastVlDecayMs = new ConcurrentHashMap<>();
    public Check() {
        CheckData data = getClass().getAnnotation(CheckData.class);
        if (data == null) throw new IllegalStateException(
                "Check " + getClass().getSimpleName() + " is missing @CheckData annotation");
        name = data.name();
        stableKey = data.stableKey();
        decay = data.decay();
        setbackVl = data.setbackVl();
        minVersion = data.minVersion();
        maxVersion = data.maxVersion();
        compatFlags = data.compat();
        relaxMultiplier = data.relaxMultiplier();
        disableOnFolia = data.disableOnFolia();
        disableOnPurpur = data.disableOnPurpur();
        KeenAC plugin = KeenAC.getInstance();
        var config = plugin.getKeenACConfig();
        enabled = config.isCheckEnabled(stableKey);
        maxVl = config.getCheckMaxVl(stableKey);
        punishable = false;
        Integer explicitSetback = config.getExplicitCheckSetbackVl(stableKey);
        if (explicitSetback != null) setbackVl = explicitSetback;
        Double explicitDecay = config.getExplicitCheckDecay(stableKey);
        if (explicitDecay != null) decay = explicitDecay;
    }
    public abstract void onPacketReceive(TrackedPlayer player, PacketReceiveEvent event);
    public abstract void onPacketSend(TrackedPlayer player, PacketSendEvent event);
    public void removePlayer(UUID uuid) {
        lastVlDecayMs.remove(uuid);
    }
    public void flag(TrackedPlayer player) {
        if (!enabled || player == null || !player.isValid()) return;
        TrustFactorModel trust = player.getTrustFactor();
        if (trust != null) trust.decreaseTrust();
        KeenAC plugin = KeenAC.getInstance();
        int increment = plugin.getSeverityManager().getScaledVlIncrement(player);
        int vl = player.getViolationLevels().merge(stableKey, Math.max(1, increment), Integer::sum);
        if (vl > maxVl) {
            player.getViolationLevels().put(stableKey, maxVl);
            vl = maxVl;
        }
        lastVlDecayMs.put(player.getUuid(), System.currentTimeMillis());
        if (plugin.getCheckManager() != null
                && plugin.getCheckManager().getViolationPattern() != null) {
            plugin.getCheckManager().getViolationPattern().recordViolation(
                    player.getUuid(), stableKey, vl, plugin.getCheckManager().getTickCounter());
        }
        if (plugin.getAlertManager() != null && player.getPlayer() != null) {
            plugin.getAlertManager().send(name, player.getPlayer(), vl,
                    "Detection signal recorded (VL=" + vl + ", key=" + stableKey + ").");
        }
    }
    public void flagWithSetback(TrackedPlayer player) { flag(player); }
    protected void performSetback(TrackedPlayer player) {
    }
    public void reward(TrackedPlayer player) {
        if (player == null) return;
        double buffer = player.getBuffers().getOrDefault(stableKey, 0.0);
        if (buffer > 0.0) player.getBuffers().put(stableKey, Math.max(0.0, buffer - decay));
        int vl = player.getViolationLevels().getOrDefault(stableKey, 0);
        if (vl <= 0) return;
        long now = System.currentTimeMillis();
        Long last = lastVlDecayMs.get(player.getUuid());
        if (last == null || now - last < 5_000L) return;
        if (!lastVlDecayMs.replace(player.getUuid(), last, now)) return;
        player.getViolationLevels().put(stableKey, Math.max(0, vl - 1));
    }
    public int getViolationLevel(TrackedPlayer player) {
        return player.getViolationLevels().getOrDefault(stableKey, 0);
    }
    public double getBuffer(TrackedPlayer player) {
        return player.getBuffers().getOrDefault(stableKey, 0.0);
    }
    public void increaseBuffer(TrackedPlayer player, double amount) {
        if (Double.isFinite(amount) && amount > 0.0) player.getBuffers().merge(stableKey, amount, Double::sum);
    }
    public void decreaseBuffer(TrackedPlayer player, double amount) {
        player.getBuffers().merge(stableKey, -Math.max(0.0, amount),
                (current, delta) -> Math.max(0.0, current + delta));
    }
    public void resetBuffer(TrackedPlayer player) { player.getBuffers().put(stableKey, 0.0); }
    public String getName() { return name; }
    public String getStableKey() { return stableKey; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isPunishable() { return punishable; }
    public void setPunishable(boolean punishable) { this.punishable = false; }
    public double getDecay() { return decay; }
    public void setDecay(double decay) { this.decay = decay; }
    public int getMaxVl() { return maxVl; }
    public void setMaxVl(int maxVl) { this.maxVl = maxVl; }
    public int getSetbackVl() { return setbackVl; }
    public void setSetbackVl(int setbackVl) { this.setbackVl = setbackVl; }
    public int getMinVersion() { return minVersion; }
    public int getMaxVersion() { return maxVersion; }
    public CompatFlag[] getCompatFlags() { return compatFlags; }
    public double getRelaxMultiplier() { return relaxMultiplier; }
    public boolean isDisableOnFolia() { return disableOnFolia; }
    public boolean isDisableOnPurpur() { return disableOnPurpur; }
    public boolean hasCompatFlag(CompatFlag flag) {
        for (CompatFlag value : compatFlags) if (value == flag) return true;
        return false;
    }
    public void flagIfAboveThreshold(TrackedPlayer player, double value, double threshold) {
        if (!(threshold > 0.0) || !Double.isFinite(value)) return;
        if (value > threshold) {
            increaseBuffer(player, (value - threshold) / threshold);
            if (getBuffer(player) > 2.0) {
                flag(player);
                resetBuffer(player);
            }
        } else {
            decreaseBuffer(player, 0.1);
        }
    }
    protected double trustAdjustedThreshold(TrackedPlayer player, double base) {
        TrustFactorModel trust = player != null ? player.getTrustFactor() : null;
        if (trust == null) return base;
        switch (trust.getRank()) {
            case SUPER_UNTRUSTWORTHY: return base * 0.5D;
            case UNTRUSTWORTHY: return base * 0.75D;
            case TRUSTED: return base * 1.1D;
            case LEGIT: return base * 1.3D;
            default: return base;
        }
    }
}
