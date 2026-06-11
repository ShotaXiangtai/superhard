package io.vcmc.superhard.manager;

import io.vcmc.superhard.SuperHardPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 呪われた場所管理。
 * プレイヤーが死んだ地点を記録し、その半径内でスポーンしたモブを追加強化する。
 * 設定した日数（デフォルト7日）で自動失効。
 */
public class CursedLocationManager {

    private record CursedEntry(double x, double y, double z, String world, long timestampMs) {}

    private final SuperHardPlugin plugin;
    private final List<CursedEntry> entries = new ArrayList<>();
    private final File dataFile;

    public CursedLocationManager(SuperHardPlugin plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "cursed_locations.yml");
        load();
    }

    /** プレイヤー死亡時に場所を登録 */
    public void addDeath(Location loc) {
        if (!plugin.getSHConfig().isCursedLocationsEnabled()) return;
        int max = plugin.getSHConfig().getMaxCursedLocations();
        if (entries.size() >= max) {
            entries.sort(Comparator.comparingLong(CursedEntry::timestampMs));
            entries.remove(0); // 最古を削除
        }
        entries.add(new CursedEntry(loc.getX(), loc.getY(), loc.getZ(),
            loc.getWorld().getName(), System.currentTimeMillis()));
        save();
    }

    /**
     * 指定地点が呪われた場所の範囲内かどうか。
     * @return 呪われていれば true
     */
    public boolean isNearCursed(Location loc) {
        if (!plugin.getSHConfig().isCursedLocationsEnabled()) return false;
        purgeExpired();
        double radius = plugin.getSHConfig().getCursedRadius();
        String worldName = loc.getWorld().getName();
        for (CursedEntry e : entries) {
            if (!e.world().equals(worldName)) continue;
            double dx = e.x() - loc.getX();
            double dz = e.z() - loc.getZ();
            if (dx * dx + dz * dz <= radius * radius) return true;
        }
        return false;
    }

    private void purgeExpired() {
        long expiryMs = plugin.getSHConfig().getCursedDurationDays() * 86_400_000L;
        entries.removeIf(e -> System.currentTimeMillis() - e.timestampMs() > expiryMs);
    }

    // ---- Save / Load ----

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (int i = 0; i < entries.size(); i++) {
            CursedEntry e = entries.get(i);
            String k = "entries." + i;
            yaml.set(k + ".world", e.world());
            yaml.set(k + ".x", e.x());
            yaml.set(k + ".y", e.y());
            yaml.set(k + ".z", e.z());
            yaml.set(k + ".ts", e.timestampMs());
        }
        try { yaml.save(dataFile); } catch (IOException ex) {
            plugin.getLogger().warning("cursed_locations.yml 保存失敗: " + ex.getMessage());
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        var section = yaml.getConfigurationSection("entries");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String k = "entries." + key;
            entries.add(new CursedEntry(
                yaml.getDouble(k + ".x"), yaml.getDouble(k + ".y"), yaml.getDouble(k + ".z"),
                yaml.getString(k + ".world", "world"), yaml.getLong(k + ".ts")
            ));
        }
        purgeExpired();
        plugin.getLogger().info("呪われた場所: " + entries.size() + "件読み込み");
    }
}
