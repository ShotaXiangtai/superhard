package io.vcmc.superhard.boss;

import io.vcmc.superhard.SuperHardPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * アクティブなレイドボスを管理する。
 * 現在は天魔王のみ対応。将来のボス追加を考慮して抽象化済み。
 */
public class RaidBossManager {

    private final SuperHardPlugin plugin;
    private final Map<UUID, TenmaouBoss> activeBosses = new HashMap<>();

    public RaidBossManager(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 天魔王を指定地点にスポーンさせる。
     * すでにアクティブなボスが存在する場合は失敗する。
     */
    public boolean spawnTenmaou(Location loc) {
        if (!activeBosses.isEmpty()) {
            return false; // 同時に複数体はNG
        }
        TenmaouBoss boss = new TenmaouBoss(plugin, loc);
        activeBosses.put(boss.getEntity().getUniqueId(), boss);
        plugin.getLogger().info("天魔王をスポーンしました: " + loc);
        return true;
    }

    /** エンティティがアクティブなボスかどうか確認する */
    public TenmaouBoss getBoss(Entity entity) {
        return activeBosses.get(entity.getUniqueId());
    }

    public boolean hasActiveBoss() {
        return !activeBosses.isEmpty();
    }

    /** ボス戦終了時（死亡・強制終了）にコールバックされる */
    public void onBossFinished(UUID bossId) {
        activeBosses.remove(bossId);
    }

    /** プラグイン無効化時にアクティブボスを強制クリーンアップ */
    public void shutdown() {
        activeBosses.values().forEach(boss -> {
            if (boss.getEntity().isValid()) boss.getEntity().remove();
        });
        activeBosses.clear();
    }
}
