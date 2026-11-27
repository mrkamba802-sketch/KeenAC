package dev.keenac.engine.core.plugin;
import dev.keenac.KeenAC;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
public final class PluginDetector {
    private boolean viaVersionInstalled;
    private boolean viaBackwardsInstalled;
    private boolean viaRewindInstalled;
    private boolean geyserInstalled;
    private boolean oldCombatMechanicsInstalled;
    private boolean worldGuardInstalled;
    public void init(KeenAC plugin) {
        Logger logger = plugin.getLogger();
        viaVersionInstalled = isPluginEnabled("ViaVersion");
        if (viaVersionInstalled) {
            logger.info("[KeenAC] ViaVersion detected — per-player protocol adaptation active");
        }
        viaBackwardsInstalled = isPluginEnabled("ViaBackwards");
        if (viaBackwardsInstalled) {
            logger.info("[KeenAC] ViaBackwards detected — backward protocol translation active");
        }
        viaRewindInstalled = isPluginEnabled("ViaRewind");
        if (viaRewindInstalled) {
            logger.info("[KeenAC] ViaRewind detected — legacy client support active");
        }
        geyserInstalled = isPluginEnabled("Geyser-Spigot") || isPluginEnabled("geyser");
        if (geyserInstalled) {
            logger.info("[KeenAC] Geyser detected — Bedrock player adaptation active");
            String geyserVersion = getPluginVersion("Geyser-Spigot");
            if (geyserVersion == null) geyserVersion = getPluginVersion("geyser");
            if (geyserVersion != null && isGeyserVulnerable(geyserVersion)) {
                logger.warning("[KeenAC] Geyser " + geyserVersion + " has known SSRF vulnerability (CVE-2026-42188) — update to 2.9.3+");
            }
        }
        oldCombatMechanicsInstalled = isPluginEnabled("OldCombatMechanics");
        if (oldCombatMechanicsInstalled) {
            logger.info("[KeenAC] OldCombatMechanics detected — 1.8 combat emulation active");
        }
        worldGuardInstalled = isPluginEnabled("WorldGuard");
        if (worldGuardInstalled) {
            logger.info("[KeenAC] WorldGuard detected — region-based exemptions available");
        }
    }
    private boolean isPluginEnabled(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }
    public boolean isViaVersionInstalled() { return viaVersionInstalled; }
    public boolean isViaBackwardsInstalled() { return viaBackwardsInstalled; }
    public boolean isViaRewindInstalled() { return viaRewindInstalled; }
    public boolean isGeyserInstalled() { return geyserInstalled; }
    public boolean isOldCombatMechanicsInstalled() { return oldCombatMechanicsInstalled; }
    public boolean isWorldGuardInstalled() { return worldGuardInstalled; }
    public boolean isAnyViaVersionPlugin() {
        return viaVersionInstalled || viaBackwardsInstalled || viaRewindInstalled;
    }
    private String getPluginVersion(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null ? plugin.getDescription().getVersion() : null;
    }
    private boolean isGeyserVulnerable(String version) {
        try {
            String[] parts = version.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return major < 2 || (major == 2 && minor < 9) || (major == 2 && minor == 9 && patch < 3);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
