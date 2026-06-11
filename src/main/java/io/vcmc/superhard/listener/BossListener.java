package io.vcmc.superhard.listener;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.boss.TenmaouBoss;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * レイドボス関連イベント処理。
 * - ダメージ → 参加者登録
 * - 死亡    → 討伐処理コール
 */
public class BossListener implements Listener {

    private final SuperHardPlugin plugin;

    public BossListener(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    /** プレイヤーがボスにダメージを与えた → 参加者登録 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamaged(EntityDamageByEntityEvent event) {
        TenmaouBoss boss = plugin.getRaidBossManager().getBoss(event.getEntity());
        if (boss == null) return;
        if (!(event.getDamager() instanceof Player player)) return;

        boss.addParticipant(player.getUniqueId());

        // AuraSkills 連携: ボスへのダメージはFighting XP自然加算（プラグイン側で別途処理）
    }

    /** ボスが死亡 → 討伐演出・報酬 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        TenmaouBoss boss = plugin.getRaidBossManager().getBoss(event.getEntity());
        if (boss == null) return;

        // ドロップは報酬システムで個別付与するため通常ドロップは消す
        event.getDrops().clear();
        event.setDroppedExp(0);

        boss.onDeath();
    }
}
