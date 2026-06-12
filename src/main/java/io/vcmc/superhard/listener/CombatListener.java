package io.vcmc.superhard.listener;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.EliteManager;
import io.vcmc.superhard.manager.ThreatManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 戦闘関連イベントを処理する。
 * - モブ撃破時の脅威スコア加算
 * - 精鋭モブの欠片ドロップ
 * - エンダーマンダメージ時のミニオン召喚
 * - クリーパー爆発後の炎設置
 * - スライム弾の命中効果
 * - バックステップ発動
 */
public class CombatListener implements Listener {

    private final SuperHardPlugin plugin;

    // スライム弾の命中検出キー
    private static final NamespacedKey SLIME_KEY = new NamespacedKey("superhard", "slime_ball");

    public CombatListener(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- モブ死亡: 脅威スコア加算 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();

        // 包囲戦モブの撃破通知
        plugin.getSiegeManager().onSiegeMobDeath(entity.getUniqueId());

        // AI行動クールダウンのクリア
        plugin.getBehaviorManager().onMobDeath(entity.getUniqueId());

        // 精鋭モブの欠片ドロップ
        if (entity instanceof Mob mob) {
            plugin.getEliteManager().handleEliteDrop(mob);
        }

        // バウンティチェック
        if (entity instanceof Mob mob) {
            Player killer = event.getEntity().getKiller();
            if (killer != null) plugin.getBountyManager().onBountyKill(mob, killer);
            plugin.getBountyManager().onMobDeath(entity.getUniqueId());
        }

        // プレイヤーによる撃破のみ脅威加算
        if (!(event.getEntity().getKiller() instanceof Player player)) return;
        if (player.hasPermission("superhard.bypass")) return;

        String typeName = entity.getType().name().toLowerCase();
        int points = plugin.getSHConfig().getKillPoints(typeName);

        // ボス判定
        if (entity.getType() == EntityType.WITHER) {
            points = plugin.getSHConfig().getBossKillPoints("wither");
        } else if (entity.getType() == EntityType.ELDER_GUARDIAN) {
            points = plugin.getSHConfig().getBossKillPoints("elder_guardian");
        } else if (entity.getType() == EntityType.ENDER_DRAGON) {
            points = plugin.getSHConfig().getBossKillPoints("ender_dragon");
        }

        // 精鋭モブはポイント2倍
        if (entity instanceof Mob mob && plugin.getEliteManager().isElite(mob)) {
            points *= 2;
        }

        plugin.getThreatManager().addThreat(player, points);

        // モブ撃破統計
        plugin.getStatsManager().addMobKill(player.getUniqueId());

        // レイド参加記録
        if (plugin.getSiegeManager().isSiegeActive()) {
            plugin.getSiegeManager().recordParticipant(player.getUniqueId());
        }
    }

    // ---- エンティティダメージ: 特殊行動のトリガー ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Entity damaged = event.getEntity();
        Entity damager = event.getDamager();

        // エンダーマンがダメージを受けたとき: ミニオン召喚
        if (damaged instanceof Enderman enderman && !(damager instanceof Enderman)) {
            ThreatManager.ThreatLevel threat = getNearestThreat(enderman);
            plugin.getBehaviorManager().onEndermanDamaged(enderman, threat);
        }

        // スケルトン/クリーパー/スパイダーがプレイヤーに近距離ダメージを与えた後のバックステップ
        if (damaged instanceof Player player && damager instanceof Mob mob) {
            if (plugin.getSHConfig().isBehaviorEnabled("skeleton", "backstep")
                    && (mob instanceof AbstractSkeleton)) {
                ThreatManager.ThreatLevel threat = plugin.getThreatManager().getThreatLevel(player);
                if (threat.ordinal() >= ThreatManager.ThreatLevel.HOSTILE.ordinal()
                        && mob.getLocation().distance(player.getLocation()) < 3) {
                    plugin.getBehaviorManager().doBackstepPublic(mob, player);
                }
            }
        }
    }

    // ---- クリーパー爆発後の炎設置 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper)) return;
        ThreatManager.ThreatLevel threat = getNearestThreat(creeper);
        plugin.getBehaviorManager().onCreeperExplosion(creeper.getLocation(), threat);
    }

    // ---- スライム弾の命中（スパイダーの発射物） ----

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)) return;
        if (!snowball.getPersistentDataContainer().has(SLIME_KEY, PersistentDataType.BOOLEAN)) return;

        if (event.getHitEntity() instanceof Player player) {
            // スライムのりをプレイヤーに付与（スロウネス）
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, true));
            player.sendMessage(Component.text(
                "[SuperHard] スパイダーのスライム弾を受けた！", NamedTextColor.DARK_GREEN
            ));
        }
        snowball.remove();
    }

    // ---- プレイヤー死亡: ペナルティと呪われた場所登録 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (player.hasPermission("superhard.bypass")) return;

        plugin.getThreatManager().applyDeathPenalty(player);
        plugin.getStatsManager().addDeath(player.getUniqueId());

        // 呪われた場所として登録
        if (plugin.getSHConfig().isCursedLocationsEnabled()) {
            plugin.getCursedLocationManager().addDeath(player.getLocation());
        }
        // RAGE Lv.4+ の死亡を Discord 通知
        if (plugin.getSHConfig().isDiscordDeathNotify()) {
            var level = plugin.getThreatManager().getThreatLevel(player);
            if (level.ordinal() >= ThreatManager.ThreatLevel.INFURIATED.ordinal()) {
                plugin.getDiscordWebhook().send("💀 **" + player.getName()
                    + "** が死亡した (RAGE " + level.displayName + ")");
            }
        }
    }

    // ---- ヘルパー ----

    private ThreatManager.ThreatLevel getNearestThreat(Mob mob) {
        return plugin.getServer().getOnlinePlayers().stream()
            .min((a, b) -> Double.compare(
                a.getLocation().distanceSquared(mob.getLocation()),
                b.getLocation().distanceSquared(mob.getLocation())
            ))
            .map(p -> plugin.getThreatManager().getThreatLevel(p))
            .orElse(ThreatManager.ThreatLevel.CALM);
    }
}
