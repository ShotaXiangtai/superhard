package io.vcmc.superhard.listener;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.EliteManager;
import io.vcmc.superhard.manager.ThreatManager.ThreatLevel;
import io.vcmc.superhard.util.SHUtil;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.Optional;

/**
 * モブのスポーン時にHP倍率と精鋭化処理を適用する。
 * 呪われた場所に近い場合は追加ボーナスを付与。
 */
public class MobSpawnListener implements Listener {

    private final SuperHardPlugin plugin;

    public MobSpawnListener(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!plugin.getSHConfig().isEnabled()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;

        // 敵対モブ (Monster) のみ対象。魚・動物・ゴーレム等は除外
        if (!(mob instanceof Monster)) return;

        // プラグイン自身のスポーンには多重適用しない
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.COMMAND) return;

        // 近くのプレイヤーの脅威レベルを取得
        Optional<Player> nearestOpt = SHUtil.nearestPlayer(mob.getLocation(), 64);
        if (nearestOpt.isEmpty()) return;
        Player nearest = nearestOpt.get();
        if (nearest.hasPermission("superhard.bypass")) return;

        ThreatLevel threat = plugin.getThreatManager().getThreatLevel(nearest);

        // HP倍率を適用
        applyHpMultiplier(mob, threat);

        // 呪われた場所チェック
        applyCursedBonus(mob);

        // 精鋭化ロール
        plugin.getEliteManager().tryElite(mob, threat);
    }

    private void applyHpMultiplier(Mob mob, ThreatLevel threat) {
        AttributeInstance attr = mob.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        double mult = plugin.getSHConfig().getMobHpMultiplier(threat);
        if (mult <= 1.0) return;
        double newHp = attr.getBaseValue() * mult;
        attr.setBaseValue(newHp);
        mob.setHealth(newHp);
    }

    private void applyCursedBonus(Mob mob) {
        if (!plugin.getSHConfig().isCursedLocationsEnabled()) return;
        // CursedLocationManagerへの呼び出しは将来実装
        // 現在: 呪われた場所判定をSiegeManagerのデータと統合予定
    }
}
