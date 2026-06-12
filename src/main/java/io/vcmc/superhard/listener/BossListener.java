package io.vcmc.superhard.listener;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.boss.TenmaouBoss;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * レイドボス・フィールドボス関連イベント処理。
 * - ダメージ → 参加者登録
 * - 死亡    → 討伐処理コール
 */
public class BossListener implements Listener {

    private final SuperHardPlugin plugin;

    public BossListener(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    /** プレイヤーがレイドボスにダメージを与えた → 参加者登録 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamaged(EntityDamageByEntityEvent event) {
        TenmaouBoss boss = plugin.getRaidBossManager().getBoss(event.getEntity());
        if (boss == null) return;
        if (!(event.getDamager() instanceof Player player)) return;

        boss.addParticipant(player.getUniqueId());
    }

    /** ボス死亡 → 討伐演出・報酬 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(EntityDeathEvent event) {
        // レイドボス
        TenmaouBoss boss = plugin.getRaidBossManager().getBoss(event.getEntity());
        if (boss != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            boss.onDeath();
            return;
        }

        // フィールドボス
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!plugin.getFieldBossManager().isFieldBoss(mob)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        plugin.getFieldBossManager().onFieldBossDeath(mob, mob.getKiller());
    }
}
