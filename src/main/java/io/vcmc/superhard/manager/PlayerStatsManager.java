package io.vcmc.superhard.manager;

import io.vcmc.superhard.SuperHardPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤー統計を管理する。
 * 記録項目: モブ撃破数・死亡回数・レイド参加回数・ボス討伐回数
 */
public class PlayerStatsManager {

    private static final String DATA_FILE = "stats_data.yml";

    private static final int IDX_MOB_KILLS  = 0;
    private static final int IDX_DEATHS     = 1;
    private static final int IDX_RAIDS      = 2;
    private static final int IDX_BOSS_KILLS = 3;
    private static final int STAT_COUNT     = 4;

    private final SuperHardPlugin plugin;
    private final File dataFile;
    private final Map<UUID, int[]> stats = new HashMap<>();

    public PlayerStatsManager(SuperHardPlugin plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), DATA_FILE);
        load();
    }

    // ---- 加算 ----

    public void addMobKill(UUID id)  { get(id)[IDX_MOB_KILLS]++; }
    public void addDeath(UUID id)    { get(id)[IDX_DEATHS]++; }
    public void addRaid(UUID id)     { get(id)[IDX_RAIDS]++; }
    public void addBossKill(UUID id) { get(id)[IDX_BOSS_KILLS]++; }

    // ---- 取得 ----

    public int getMobKills(UUID id)  { return get(id)[IDX_MOB_KILLS]; }
    public int getDeaths(UUID id)    { return get(id)[IDX_DEATHS]; }
    public int getRaids(UUID id)     { return get(id)[IDX_RAIDS]; }
    public int getBossKills(UUID id) { return get(id)[IDX_BOSS_KILLS]; }

    private int[] get(UUID id) {
        return stats.computeIfAbsent(id, k -> new int[STAT_COUNT]);
    }

    // ---- セーブ / ロード ----

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : stats.entrySet()) {
            String base = "stats." + entry.getKey();
            int[]  s    = entry.getValue();
            yaml.set(base + ".mob-kills",  s[IDX_MOB_KILLS]);
            yaml.set(base + ".deaths",     s[IDX_DEATHS]);
            yaml.set(base + ".raids",      s[IDX_RAIDS]);
            yaml.set(base + ".boss-kills", s[IDX_BOSS_KILLS]);
        }
        try { yaml.save(dataFile); }
        catch (IOException e) {
            plugin.getLogger().warning("stats_data.yml の保存に失敗: " + e.getMessage());
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        if (!yaml.contains("stats")) return;
        for (String key : yaml.getConfigurationSection("stats").getKeys(false)) {
            try {
                UUID  id = UUID.fromString(key);
                int[] s  = new int[STAT_COUNT];
                String base = "stats." + key;
                s[IDX_MOB_KILLS]  = yaml.getInt(base + ".mob-kills",  0);
                s[IDX_DEATHS]     = yaml.getInt(base + ".deaths",     0);
                s[IDX_RAIDS]      = yaml.getInt(base + ".raids",      0);
                s[IDX_BOSS_KILLS] = yaml.getInt(base + ".boss-kills", 0);
                stats.put(id, s);
            } catch (IllegalArgumentException ignored) {}
        }
        plugin.getLogger().info("プレイヤー統計を読み込みました: " + stats.size() + "件");
    }
}
