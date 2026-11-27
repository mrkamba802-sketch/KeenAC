package dev.keenac.engine.core.config;
import dev.keenac.KeenAC;
import dev.keenac.engine.core.util.MaterialUtils;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.Collections;
import java.util.Set;
public class KeenACConfig {
    private final KeenAC plugin;
    private FileConfiguration config;
    public KeenACConfig(KeenAC plugin) {
        this.plugin = plugin;
        if (plugin == null) return;
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
        setDefaults();
        config.options().copyDefaults(true);
        plugin.saveConfig();
    }
    private void setDefaults() {
        config.addDefault("alerts.enabled", true);
        config.addDefault("alerts.prefix", "&8[&bKeenAC&8] &7");
        config.addDefault("alerts.staff-permission", "keenac.alerts");
        config.addDefault("alerts.broadcast-to-all-staff", true);
        config.addDefault("discord.enabled", false);
        config.addDefault("discord.webhook-url", "");
        config.addDefault("discord.server-name", "My Server");
        config.addDefault("discord.mention-on-high-vl", true);
        config.addDefault("discord.mention-threshold", 25);
        config.addDefault("discord.avatar-url", "");
        config.addDefault("discord.embed-color-low", 16776960);
        config.addDefault("discord.embed-color-med", 16744448);
        config.addDefault("discord.embed-color-high", 16711680);
        config.addDefault("discord.rate-limit-ms", 5000);
        config.addDefault("bedrock.enabled", true);
        config.addDefault("bedrock.geyser-plugin-name", "Geyser-Spigot");
        config.addDefault("bedrock.use-floodgate-api", true);
        config.addDefault("bedrock.bedrock-reach-multiplier", 1.5);
        config.addDefault("bedrock.bedrock-speed-tolerance", 1.15);
        config.addDefault("bedrock.bedrock-scaffold-threshold", 0.8);
        config.addDefault("bedrock.bedrock-aim-snap-threshold", 200.0);
        config.addDefault("bedrock.bedrock-controller-aim-snap", 185.0);
        config.addDefault("bedrock.bedrock-touch-aim-snap", 200.0);
        config.addDefault("bedrock.bedrock-cps-limit", 20);
        config.addDefault("bedrock.bedrock-tolerance", 1.10);
        config.addDefault("verbose", true);
        config.addDefault("severity.enabled", true);
        config.addDefault("severity.moderate-vl", 10);
        config.addDefault("severity.high-vl", 25);
        config.addDefault("severity.extreme-vl", 50);
        config.addDefault("severity.moderate-multiplier", 1.3);
        config.addDefault("severity.high-multiplier", 1.6);
        config.addDefault("severity.extreme-multiplier", 2.0);
        config.addDefault("severity.bedrock-discount", 0.6);
        config.addDefault("punishments.enabled", false);
        config.addDefault("punishments.warn-vl", 5);
        config.addDefault("punishments.kick-vl", 10);
        config.addDefault("punishments.tempban-vl", 20);
        config.addDefault("punishments.tempban-duration", "1d");
        config.addDefault("punishments.permban-vl", 30);
        config.addDefault("punishments.warn-message", "&c[KeenAC] &eWarning: further cheating will result in a kick.");
        config.addDefault("punishments.kick-message", "&c[KeenAC] Kicked for cheating.");
        config.addDefault("punishments.tempban-reason", "[KeenAC] Temporarily banned for cheating.");
        config.addDefault("punishments.permban-reason", "[KeenAC] Permanently banned for cheating.");
        config.addDefault("adaptive.enabled", true);
        config.addDefault("adaptive.tps-threshold", 19.0);
        config.addDefault("adaptive.scale-factor", 0.02);
        config.addDefault("adaptive.max-tolerance-multiplier", 2.0);
        config.addDefault("adaptive.safe-mode-threshold", 12.0);
        config.addDefault("pattern.enabled", true);
        config.addDefault("pattern.history-days", 30);
        config.addDefault("pattern.repeat-threshold", 3);
        config.addDefault("pattern.toggle-detection-window", 6000);
        config.addDefault("fingerprint.enabled", true);
        config.addDefault("fingerprint.min-severity-to-flag", 60);
        config.addDefault("fingerprint.max-fingerprint-age-ticks", 6000);
        config.addDefault("prometheus.enabled", false);
        config.addDefault("prometheus.host", "127.0.0.1");
        config.addDefault("prometheus.port", 9211);
        config.addDefault("worldguard.enabled", false);
        config.addDefault("worldguard.exempt-regions", false);
        config.addDefault("checks.default.enabled", true);
        config.addDefault("checks.default.max-vl", 100);
        config.addDefault("checks.default.setback-vl", 20);
        config.addDefault("checks.default.decay", 0.02);
        config.addDefault("checks.default.punishable", false);
        String[] allChecks = {
            "keenac.combat.aim", "keenac.combat.autoclicker", "keenac.combat.backtrack",
            "keenac.combat.criticals", "keenac.combat.crystal", "keenac.combat.fastheal",
            "keenac.combat.hitboxes", "keenac.combat.interactcursor", "keenac.combat.killaura",
            "keenac.combat.macro", "keenac.combat.multiinteract", "keenac.combat.reach",
            "keenac.combat.selfinteract", "keenac.combat.swordblock", "keenac.inventory.inventory",
            "keenac.movement.airliquidbreak", "keenac.movement.airliquidplace", "keenac.movement.baritone",
            "keenac.movement.elytra", "keenac.movement.farbreak", "keenac.movement.farplace",
            "keenac.movement.fastbreak", "keenac.movement.fly", "keenac.movement.gravity",
            "keenac.movement.groundspoof", "keenac.movement.illegalmove", "keenac.movement.invalidbreak",
            "keenac.movement.invalidplace", "keenac.movement.invalidplacecursor", "keenac.movement.motion",
            "keenac.movement.multibreak", "keenac.movement.multiplace", "keenac.movement.nofall",
            "keenac.movement.noslow", "keenac.movement.noswing", "keenac.movement.phase",
            "keenac.movement.positionbreak", "keenac.movement.positionplace", "keenac.movement.rotationbreak",
            "keenac.movement.rotationplace", "keenac.movement.scaffold", "keenac.movement.scaffoldsupport",
            "keenac.movement.simulation", "keenac.movement.speed", "keenac.movement.sprint",
            "keenac.movement.step", "keenac.movement.timer", "keenac.movement.velocity",
            "keenac.movement.wrongbreak", "keenac.packet.bad", "keenac.packet.brand",
            "keenac.packet.chat", "keenac.packet.cheststealer", "keenac.packet.crash",
            "keenac.packet.creative", "keenac.packet.exploit", "keenac.packet.order",
            "keenac.packet.sprint", "keenac.packet.transaction", "keenac.packet.vehicle"
        };
        for (String key : allChecks) {
            config.addDefault("checks." + key + ".enabled", true);
            config.addDefault("checks." + key + ".max-vl", 100);
            config.addDefault("checks." + key + ".punishable", false);
        }
    }
    public boolean isAlertsEnabled() {
        return config.getBoolean("alerts.enabled", true);
    }
    public String getAlertPrefix() {
        return config.getString("alerts.prefix", "&8[&bKeenAC&8] &7");
    }
    public String getAlertsStaffPermission() {
        return config.getString("alerts.staff-permission", "keenac.alerts");
    }
    public boolean isBroadcastToAllStaff() {
        return config.getBoolean("alerts.broadcast-to-all-staff", true);
    }
    public boolean isDiscordEnabled() {
        return config.getBoolean("discord.enabled", false);
    }
    public String getDiscordWebhookUrl() {
        return config.getString("discord.webhook-url", "");
    }
    public String getDiscordServerName() {
        return config.getString("discord.server-name", "My Server");
    }
    public boolean isDiscordMentionOnHighVl() {
        return config.getBoolean("discord.mention-on-high-vl", true);
    }
    public int getDiscordMentionThreshold() {
        return config.getInt("discord.mention-threshold", 25);
    }
    public String getDiscordAvatarUrl() {
        return config.getString("discord.avatar-url", "");
    }
    public int getDiscordEmbedColor(int vl) {
        if (vl >= getDiscordMentionThreshold()) {
            return config.getInt("discord.embed-color-high", 16711680);
        } else if (vl >= 10) {
            return config.getInt("discord.embed-color-med", 16744448);
        }
        return config.getInt("discord.embed-color-low", 16776960);
    }
    public long getDiscordRateLimitMs() {
        return config.getLong("discord.rate-limit-ms", 5000);
    }
    public boolean isBedrockEnabled() {
        return config.getBoolean("bedrock.enabled", true);
    }
    public String getBedrockGeyserPluginName() {
        return config.getString("bedrock.geyser-plugin-name", "Geyser-Spigot");
    }
    public boolean isBedrockUseFloodgateApi() {
        return config.getBoolean("bedrock.use-floodgate-api", true);
    }
    public double getBedrockReachMultiplier() {
        return config.getDouble("bedrock.bedrock-reach-multiplier", 1.5);
    }
    public double getBedrockSpeedTolerance() {
        return config.getDouble("bedrock.bedrock-speed-tolerance", 1.15);
    }
    public double getBedrockScaffoldThreshold() {
        return config.getDouble("bedrock.bedrock-scaffold-threshold", 0.8);
    }
    public double getBedrockAimSnapThreshold() {
        return config.getDouble("bedrock.bedrock-aim-snap-threshold", 200.0);
    }
    public double getBedrockControllerAimSnap() {
        return config.getDouble("bedrock.bedrock-controller-aim-snap", 185.0);
    }
    public double getBedrockTouchAimSnap() {
        return config.getDouble("bedrock.bedrock-touch-aim-snap", 200.0);
    }
    public int getBedrockCpsLimit() {
        return config.getInt("bedrock.bedrock-cps-limit", 20);
    }
    public double getBedrockTolerance() {
        return config.getDouble("bedrock.bedrock-tolerance", 1.10);
    }
    public boolean isVerboseEnabled() {
        return config.getBoolean("verbose", true);
    }
    public boolean isSeverityEnabled() {
        return config.getBoolean("severity.enabled", true);
    }
    public int getSeverityModerateVl() {
        return config.getInt("severity.moderate-vl", 10);
    }
    public int getSeverityHighVl() {
        return config.getInt("severity.high-vl", 25);
    }
    public int getSeverityExtremeVl() {
        return config.getInt("severity.extreme-vl", 50);
    }
    public double getSeverityModerateMultiplier() {
        return config.getDouble("severity.moderate-multiplier", 1.3);
    }
    public double getSeverityHighMultiplier() {
        return config.getDouble("severity.high-multiplier", 1.6);
    }
    public double getSeverityExtremeMultiplier() {
        return config.getDouble("severity.extreme-multiplier", 2.0);
    }
    public double getSeverityBedrockDiscount() {
        return config.getDouble("severity.bedrock-discount", 0.6);
    }
    public boolean isPunishmentsEnabled() {
        return config.getBoolean("punishments.enabled", false);
    }
    public int getPunishmentWarnVl() {
        return config.getInt("punishments.warn-vl", 5);
    }
    public int getPunishmentKickVl() {
        return config.getInt("punishments.kick-vl", 10);
    }
    public int getPunishmentTempbanVl() {
        return config.getInt("punishments.tempban-vl", 20);
    }
    public String getPunishmentTempbanDuration() {
        return config.getString("punishments.tempban-duration", "1d");
    }
    public int getPunishmentPermbanVl() {
        return config.getInt("punishments.permban-vl", 30);
    }
    public String getPunishmentWarnMessage() {
        return config.getString("punishments.warn-message",
            "&c[KeenAC] &eWarning: further cheating will result in a kick.");
    }
    public String getPunishmentKickMessage() {
        return config.getString("punishments.kick-message", "&c[KeenAC] Kicked for cheating.");
    }
    public String getPunishmentTempbanReason() {
        return config.getString("punishments.tempban-reason",
            "[KeenAC] Temporarily banned for cheating.");
    }
    public String getPunishmentPermbanReason() {
        return config.getString("punishments.permban-reason",
            "[KeenAC] Permanently banned for cheating.");
    }
    public boolean isAdaptiveEnabled() {
        return config.getBoolean("adaptive.enabled", true);
    }
    public double getAdaptiveTpsThreshold() {
        return config.getDouble("adaptive.tps-threshold", 19.0);
    }
    public double getAdaptiveScaleFactor() {
        return config.getDouble("adaptive.scale-factor", 0.02);
    }
    public double getAdaptiveMaxToleranceMultiplier() {
        return config.getDouble("adaptive.max-tolerance-multiplier", 2.0);
    }
    public double getAdaptiveSafeModeThreshold() {
        return config.getDouble("adaptive.safe-mode-threshold", 12.0);
    }
    public boolean isPatternEnabled() {
        return config.getBoolean("pattern.enabled", true);
    }
    public int getPatternHistoryDays() {
        return config.getInt("pattern.history-days", 30);
    }
    public int getPatternRepeatThreshold() {
        return config.getInt("pattern.repeat-threshold", 3);
    }
    public int getPatternToggleDetectionWindow() {
        return config.getInt("pattern.toggle-detection-window", 6000);
    }
    public boolean isFingerprintEnabled() {
        return config.getBoolean("fingerprint.enabled", true);
    }
    public int getFingerprintMinSeverityToFlag() {
        return config.getInt("fingerprint.min-severity-to-flag", 60);
    }
    public int getFingerprintMaxAgeTicks() {
        return config.getInt("fingerprint.max-fingerprint-age-ticks", 6000);
    }
    public boolean isPrometheusEnabled() {
        return config.getBoolean("prometheus.enabled", false);
    }
    public String getPrometheusHost() {
        return config.getString("prometheus.host", "127.0.0.1");
    }
    public int getPrometheusPort() {
        return config.getInt("prometheus.port", 9211);
    }
    public boolean isCheckEnabled(String checkKey) {
        String path = "checks." + checkKey + ".enabled";
        if (config.isSet(path)) {
            return config.getBoolean(path);
        }
        return config.getBoolean("checks.default.enabled", true);
    }
    public int getCheckMaxVl(String checkKey) {
        String path = "checks." + checkKey + ".max-vl";
        if (config.isSet(path)) {
            return config.getInt(path);
        }
        return config.getInt("checks.default.max-vl", 100);
    }
    public int getCheckSetbackVl(String checkKey) {
        Integer explicit = getExplicitCheckSetbackVl(checkKey);
        if (explicit != null) return explicit;
        return config.getInt("checks.default.setback-vl", 20);
    }
    public double getCheckDecay(String checkKey) {
        Double explicit = getExplicitCheckDecay(checkKey);
        if (explicit != null) return explicit;
        return config.getDouble("checks.default.decay", 0.02);
    }
    public Integer getExplicitCheckSetbackVl(String checkKey) {
        String path = "checks." + checkKey + ".setback-vl";
        return config.isSet(path) ? config.getInt(path) : null;
    }
    public Double getExplicitCheckDecay(String checkKey) {
        String path = "checks." + checkKey + ".decay";
        return config.isSet(path) ? config.getDouble(path) : null;
    }
    public boolean isWorldGuardEnabled() {
        return config.getBoolean("worldguard.enabled", false);
    }
    public boolean isWorldGuardExemptRegions() {
        return config.getBoolean("worldguard.exempt-regions", false);
    }
    public boolean isCheckPunishable(String checkKey) {
        String path = "checks." + checkKey + ".punishable";
        if (config.isSet(path)) {
            return config.getBoolean(path);
        }
        return config.getBoolean("checks.default.punishable", true);
    }
    public Set<String> getCheckKeys() {
        if (config.isConfigurationSection("checks")) {
            Set<String> keys = new java.util.LinkedHashSet<>(
                config.getConfigurationSection("checks").getKeys(false));
            keys.remove("default");
            return keys;
        }
        return Collections.emptySet();
    }
    public void saveCheckEnabled(String checkKey, boolean enabled) {
        config.set("checks." + checkKey + ".enabled", enabled);
        plugin.saveConfig();
    }
    public void saveCheckPunishable(String checkKey, boolean punishable) {
        config.set("checks." + checkKey + ".punishable", punishable);
        plugin.saveConfig();
    }
    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        MaterialUtils.clearCaches();
    }
}
