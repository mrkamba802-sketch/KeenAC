package dev.keenac.engine.core.platform;
import dev.keenac.engine.core.version.ServerFork;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
public final class PurpurCompat {
    private volatile boolean isPurpur;
    private volatile boolean customKnockbackEnabled;
    private volatile double attackKnockbackMultiplier = 1.0;
    private volatile double knockbackVerticalMultiplier = 1.0;
    public void init(ServerFork fork, Logger logger) {
        this.isPurpur = fork.isPurpur();
        if (!isPurpur) return;
        try {
            File purpurConfig = new File("purpur.yml");
            if (!purpurConfig.exists()) {
                logger.warning("[KeenAC] purpur.yml not found — using vanilla knockback values");
                return;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(purpurConfig);
            double attackKB = config.getDouble("knockback.attack-knockback", 1.0);
            double verticalKB = config.getDouble("knockback.knockback-vertical", 1.0);
            if (attackKB != 1.0 || verticalKB != 1.0) {
                customKnockbackEnabled = true;
                attackKnockbackMultiplier = attackKB;
                knockbackVerticalMultiplier = verticalKB;
                logger.info("[KeenAC] Purpur custom knockback detected: attack=" + attackKB + ", vertical=" + verticalKB);
            } else {
                logger.info("[KeenAC] Purpur detected but using vanilla knockback values");
            }
        } catch (Exception e) {
            logger.info("[KeenAC] Could not read purpur.yml — using vanilla knockback values");
        }
    }
    public double adjustHorizontalKB(double vanillaKB) {
        if (!customKnockbackEnabled) return vanillaKB;
        return vanillaKB * attackKnockbackMultiplier;
    }
    public double adjustVerticalKB(double vanillaKB) {
        if (!customKnockbackEnabled) return vanillaKB;
        return vanillaKB * knockbackVerticalMultiplier;
    }
    public boolean isPurpur() { return isPurpur; }
    public boolean isCustomKnockbackEnabled() { return customKnockbackEnabled; }
    public double getAttackKnockbackMultiplier() { return attackKnockbackMultiplier; }
    public double getKnockbackVerticalMultiplier() { return knockbackVerticalMultiplier; }
}
