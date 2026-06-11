package io.vcmc.superhard.manager;

import io.vcmc.superhard.SuperHardPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーごとの「脅威スコア」を管理する。
 * スコアが高いほど周囲のモブが強くなり、精鋭の出現率も上がる。
 * TCMのような固定難易度選択ではなく、プレイスタイルに応じて有機的に変動する。
 */
public class ThreatManager {

    public enum ThreatLevel {
        CALM       (0,    99,   "穏やか",   NamedTextColor.GREEN),
        AGITATED   (100,  299,  "不穏",     NamedTextColor.YELLOW),
        HOSTILE    (300,  699,  "敵対",     NamedTextColor.GOLD),
        INFURIATED (700,  1499, "激怒",     NamedTextColor.RED),
        WRATHFUL   (1500, Integer.MAX_VALUE, "天罰", NamedTextColor.DARK_RED);

        public final int min, max;
        public final String displayName;
        public final NamedTextColor color;

        ThreatLevel(int min, int max, String displayName, NamedTextColor color) {
            this.min = min; this.max = max;
            this.displayName = displayName;
            this.color = color;
        }

        public static ThreatLevel fromPoints(int points) {
            for (ThreatLevel lvl : values()) {
                if (points >= lvl.min && points <= lvl.max) return lvl;
            }
            return WRATHFUL;
        }
    }

    private final SuperHardPlugin plugin;
    private final Map<UUID, Integer> threatMap = new HashMap<>();
    private final Map<UUID, ThreatLevel> levelCache = new HashMap<>();
    private BukkitTask passiveTask;
    private BukkitTask dayCycleTask;

    private File dataFile;
    private static final String DATA_FILE = "threat_data.yml";

    public ThreatManager(SuperHardPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), DATA_FILE);
        startPassiveTasks();
    }

    // ---- データ操作 ----

    public int getThreat(UUID playerId) {
        return threatMap.getOrDefault(playerId, 0);
    }

    public int getThreat(Player player) {
        return getThreat(player.getUniqueId());
    }

    public ThreatLevel getThreatLevel(UUID playerId) {
        return levelCache.computeIfAbsent(playerId, id -> ThreatLevel.fromPoints(getThreat(id)));
    }

    public ThreatLevel getThreatLevel(Player player) {
        return getThreatLevel(player.getUniqueId());
    }

    public void addThreat(Player player, int amount) {
        if (amount <= 0) return;
        UUID id = player.getUniqueId();
        int prev = getThreat(id);
        int newVal = Math.min(prev + amount, 9999);
        threatMap.put(id, newVal);

        ThreatLevel prevLevel = ThreatLevel.fromPoints(prev);
        ThreatLevel newLevel  = ThreatLevel.fromPoints(newVal);
        levelCache.put(id, newLevel);

        if (newLevel != prevLevel) {
            notifyLevelChange(player, newLevel);
        }
    }

    public void reduceThreat(Player player, int amount) {
        UUID id = player.getUniqueId();
        int newVal = Math.max(0, getThreat(id) - amount);
        threatMap.put(id, newVal);
        levelCache.put(id, ThreatLevel.fromPoints(newVal));
    }

    public void applyDeathPenalty(Player player) {
        int percent = plugin.getSHConfig().getDeathPenaltyPercent();
        int current = getThreat(player);
        int loss = (int) (current * (percent / 100.0));
        reduceThreat(player, loss);
        player.sendMessage(Component.text(
            "[SuperHard] 死亡ペナルティ: 脅威スコア -" + loss + " (現在: " + getThreat(player) + ")",
            NamedTextColor.GRAY
        ));
    }

    // ---- 内部: 定期タスク ----

    private void startPassiveTasks() {
        // 1分ごとにオンラインプレイヤーの脅威スコアを加算
        long passiveInterval = 20L * 60; // 1分
        passiveTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int gain = plugin.getSHConfig().getPassiveThreatGain();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.hasPermission("superhard.bypass")) continue;
                addThreat(p, gain);
            }
        }, passiveInterval, passiveInterval);

        // 1ゲーム内日（24000 ticks = 20分）ごとに生存報酬を付与
        dayCycleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long time = plugin.getServer().getWorlds().get(0).getTime();
            // ゲーム内朝6時 (時刻 = 0) 付近でのみ実行
            if (time > 100) return;
            int reward = plugin.getSHConfig().getDaySurvivalReward();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.hasPermission("superhard.bypass")) continue;
                reduceThreat(p, reward);
                if (reward > 0) {
                    p.sendMessage(Component.text(
                        "[SuperHard] 1日生存ボーナス: 脅威スコア -" + reward,
                        NamedTextColor.GREEN
                    ));
                }
            }
        }, 20L * 60, 20L * 10); // 10秒ごとにチェック（時刻0付近か確認）
    }

    private void notifyLevelChange(Player player, ThreatLevel newLevel) {
        player.sendMessage(Component.text(
            "[SuperHard] 脅威レベル上昇 → ", NamedTextColor.DARK_RED
        ).append(Component.text(
            newLevel.displayName, newLevel.color
        )));
        player.sendActionBar(Component.text(
            "⚠ 脅威: " + newLevel.displayName + " ⚠", newLevel.color
        ));
    }

    // ---- セーブ / ロード ----

    public void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        if (yaml.contains("threats")) {
            for (String key : yaml.getConfigurationSection("threats").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    int points = yaml.getInt("threats." + key, 0);
                    threatMap.put(id, points);
                    levelCache.put(id, ThreatLevel.fromPoints(points));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        plugin.getLogger().info("脅威データを読み込みました: " + threatMap.size() + "件");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        threatMap.forEach((id, points) -> yaml.set("threats." + id.toString(), points));
        try {
            yaml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("脅威データの保存に失敗しました: " + e.getMessage());
        }
    }

    public void stop() {
        if (passiveTask != null && !passiveTask.isCancelled()) passiveTask.cancel();
        if (dayCycleTask != null && !dayCycleTask.isCancelled()) dayCycleTask.cancel();
    }

    // ---- プレイヤー接続時の初期化 ----

    public void initPlayer(UUID id) {
        threatMap.putIfAbsent(id, 0);
        levelCache.put(id, ThreatLevel.fromPoints(threatMap.get(id)));
    }

    public void removePlayer(UUID id) {
        // メモリから除去（セーブはshutdown時）
        levelCache.remove(id);
    }
}
