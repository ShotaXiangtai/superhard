package io.vcmc.superhard.config;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.ThreatManager.ThreatLevel;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

public class SHConfig {

    private final SuperHardPlugin plugin;

    public SHConfig(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // ---- 全体 ----

    public boolean isEnabled() {
        return cfg().getBoolean("enabled", true);
    }

    // ---- 脅威スコア ----

    public int getPassiveThreatGain() {
        return cfg().getInt("threat.passive-gain-per-minute", 3);
    }

    public int getKillPoints(String mobType) {
        String key = "threat.kill-points." + mobType.toLowerCase();
        return cfg().getInt(key, cfg().getInt("threat.kill-points.default", 2));
    }

    public int getBossKillPoints(String bossType) {
        return cfg().getInt("threat.boss-kill-points." + bossType.toLowerCase(), 50);
    }

    public int getDaySurvivalReward() {
        return cfg().getInt("threat.day-survival-reward", 40);
    }

    public int getDeathPenaltyPercent() {
        return cfg().getInt("threat.death-penalty-percent", 30);
    }

    public int getThreatThreshold(String levelName) {
        return cfg().getInt("threat.thresholds." + levelName.toLowerCase(), 0);
    }

    // ---- 精鋭モブ ----

    public boolean isElitesEnabled() {
        return cfg().getBoolean("elites.enabled", true);
    }

    public double getEliteBaseChance(ThreatLevel level) {
        return cfg().getDouble("elites.base-chances." + level.name().toLowerCase(), 0.05);
    }

    public double getEliteHpMultiplier(String type) {
        return cfg().getDouble("elites.hp-multipliers." + type.toLowerCase(), 1.3);
    }

    public double getEliteDamageMultiplier(String type) {
        return cfg().getDouble("elites.damage-multipliers." + type.toLowerCase(), 1.2);
    }

    public double getShardDropChance() {
        return cfg().getDouble("elites.shard-drop-chance", 0.40);
    }

    // ---- モブ行動 ----

    public boolean isBehaviorEnabled() {
        return cfg().getBoolean("behavior.enabled", true);
    }

    public int getMaxMobsPerTick() {
        return cfg().getInt("behavior.max-mobs-per-tick", 30);
    }

    public boolean isBehaviorEnabled(String mobType, String behavior) {
        return cfg().getBoolean("behavior." + mobType + "." + behavior + "-enabled", true);
    }

    public long getBehaviorCooldownMs(String mobType, String behavior) {
        int sec = cfg().getInt("behavior." + mobType + "." + behavior + "-cooldown-sec", 5);
        return sec * 1000L;
    }

    public int getWebLifetimeTicks() {
        int sec = cfg().getInt("behavior.spider.web-lifetime-sec", 25);
        return sec * 20;
    }

    public int getAutoAggroRange() {
        return cfg().getInt("behavior.enderman.auto-aggro-range", 16);
    }

    // ---- モブHP倍率 ----

    public double getMobHpMultiplier(ThreatLevel level) {
        return cfg().getDouble("mob-hp." + level.name().toLowerCase(), 1.0);
    }

    // ---- パック戦術 ----

    public boolean isPackTacticsEnabled() {
        return cfg().getBoolean("pack-tactics.enabled", true);
    }

    public double getPackAlertRange() {
        return cfg().getDouble("pack-tactics.alert-range", 20);
    }

    public double getAlphaHpBonus() {
        return cfg().getDouble("pack-tactics.alpha-hp-bonus", 1.30);
    }

    public double getAlphaSpeedBonus() {
        return cfg().getDouble("pack-tactics.alpha-speed-bonus", 1.15);
    }

    // ---- 包囲戦 ----

    public boolean isSiegeEnabled() {
        return cfg().getBoolean("siege.enabled", true);
    }

    public double getSiegeTriggerChance() {
        return cfg().getDouble("siege.trigger-chance-per-night", 0.07);
    }

    public int getMinDaysBetweenSieges() {
        return cfg().getInt("siege.min-days-between-sieges", 3);
    }

    public int getWaveCount() {
        return cfg().getInt("siege.wave-count", 3);
    }

    public long getWaveIntervalTicks() {
        int sec = cfg().getInt("siege.wave-interval-sec", 60);
        return sec * 20L;
    }

    public int getWaveMobsPerBase(int wave) {
        return switch (wave) {
            case 1 -> cfg().getInt("siege.wave1-mobs-per-base", 6);
            case 2 -> cfg().getInt("siege.wave2-mobs-per-base", 8);
            default -> cfg().getInt("siege.wave3-mobs-per-base", 7);
        };
    }

    /** サーバー全体の包囲戦モブ上限（マルチプレイ時の過負荷防止） */
    public int getSiegeServerMobCap() {
        return cfg().getInt("siege.server-mob-cap", 40);
    }

    /** この半径内の拠点を1か所にまとめる（ブロック数） */
    public double getSiegeBaseMergeRadius() {
        return cfg().getDouble("siege.base-merge-radius", 50.0);
    }

    public boolean isFinalWaveElite() {
        return cfg().getBoolean("siege.final-wave-elite", true);
    }

    // ---- レイドボススケジュール ----

    public boolean isBossAutoSpawnEnabled() {
        return cfg().getBoolean("boss.auto-spawn", true);
    }

    public long getBossMinIntervalHours() {
        return cfg().getLong("boss.min-interval-hours", 12);
    }

    public long getBossMaxIntervalHours() {
        return cfg().getLong("boss.max-interval-hours", 24);
    }

    // ---- 鍛えの欠片 ----

    public boolean isTemperedEnabled() {
        return cfg().getBoolean("tempered.enabled", true);
    }

    public boolean isAnvilRecipeEnabled() {
        return cfg().getBoolean("tempered.anvil-recipe-enabled", true);
    }

    public int getTemperedUnbreakingLevel() {
        return cfg().getInt("tempered.unbreaking-level", 3);
    }

    // ---- 呪われた場所 ----

    public boolean isCursedLocationsEnabled() {
        return cfg().getBoolean("cursed-locations.enabled", true);
    }

    public int getCursedRadius() {
        return cfg().getInt("cursed-locations.radius", 20);
    }

    public double getCursedHpBonus() {
        return cfg().getDouble("cursed-locations.hp-bonus-multiplier", 1.20);
    }

    public int getCursedDurationDays() {
        return cfg().getInt("cursed-locations.duration-days", 7);
    }

    public int getMaxCursedLocations() {
        return cfg().getInt("cursed-locations.max-locations", 50);
    }

    // ---- メッセージ ----

    public String getMessage(String key) {
        return cfg().getString("messages." + key, "");
    }

    public String getWaveMessage(int wave) {
        return getMessage("siege-wave-message").replace("%wave", String.valueOf(wave));
    }
}
