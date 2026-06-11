package io.vcmc.superhard.manager;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.ThreatManager.ThreatLevel;
import io.vcmc.superhard.util.SHUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.attribute.Attribute;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;

/**
 * 包囲戦（Siege Night）システム。
 * ランダムな深夜にモブの群れがプレイヤーの拠点（ベッド地点）を一斉に攻撃する。
 *
 * TCMのような単純な強化とは異なり、ウェーブ制の組織的な攻撃を演出する。
 * 真に強い者だけが包囲戦を生き抜くことができる。
 */
public class SiegeManager {

    private final SuperHardPlugin plugin;
    private BukkitTask nightCheckTask;
    private BukkitTask waveTask;

    private boolean siegeActive = false;
    private int currentWave = 0;
    private long lastSiegeDay = -1;

    // 包囲戦中にスポーンしたモブの追跡（撃退報酬用）
    private final Set<UUID> siegeMobs = new HashSet<>();

    public SiegeManager(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // 10秒ごとに深夜かチェック（ゲーム内時刻 18000 付近）
        nightCheckTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkNight, 20L * 10, 20L * 10);
    }

    public void stop() {
        if (nightCheckTask != null && !nightCheckTask.isCancelled()) nightCheckTask.cancel();
        if (waveTask != null && !waveTask.isCancelled()) waveTask.cancel();
    }

    // ---- 深夜チェック ----

    private void checkNight() {
        if (!plugin.getSHConfig().isSiegeEnabled()) return;
        if (siegeActive) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        World overworld = getOverworld();
        if (overworld == null) return;

        long time = overworld.getTime();
        if (time < 18000 || time > 18100) return; // 深夜ウィンドウ

        long currentDay = SHUtil.worldDayCount(overworld);
        long minGap = plugin.getSHConfig().getMinDaysBetweenSieges();
        if (lastSiegeDay >= 0 && currentDay - lastSiegeDay < minGap) return;

        if (SHUtil.chance(plugin.getSHConfig().getSiegeTriggerChance())) {
            startSiege(currentDay);
        }
    }

    // ---- 包囲戦開始 ----

    private void startSiege(long day) {
        siegeActive = true;
        currentWave = 0;
        lastSiegeDay = day;
        siegeMobs.clear();

        // 全プレイヤーに通知
        Title title = Title.title(
            Component.text(plugin.getSHConfig().getMessage("siege-start-title"), NamedTextColor.DARK_RED),
            Component.text(plugin.getSHConfig().getMessage("siege-start-subtitle"), NamedTextColor.GRAY),
            Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000))
        );
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1.5f, 0.8f);
        }

        // ウェーブスケジュール
        long interval = plugin.getSHConfig().getWaveIntervalTicks();
        int waveCount = plugin.getSHConfig().getWaveCount();
        for (int w = 1; w <= waveCount; w++) {
            final int wave = w;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> spawnWave(wave), 60L + interval * (wave - 1));
        }
        // 終了
        plugin.getServer().getScheduler().runTaskLater(plugin, this::endSiege, 60L + interval * waveCount);
    }

    // ---- ウェーブスポーン ----

    private void spawnWave(int wave) {
        if (!siegeActive) return;
        currentWave = wave;
        boolean isFinalWave = wave == plugin.getSHConfig().getWaveCount();

        String waveMsg = plugin.getSHConfig().getWaveMessage(wave);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(Component.text(waveMsg, NamedTextColor.DARK_RED));
            if (wave == 1) {
                p.sendMessage(Component.text(
                    "[SuperHard] 拠点周辺にモブが集結している！", NamedTextColor.RED));
            }
        }

        // 拠点を重複排除してからスポーン（人数に比例せずエリアベース）
        List<Location> bases = collectUniqueBases();
        int serverCap  = plugin.getSHConfig().getSiegeServerMobCap();
        int totalSpawned = 0;

        for (Location base : bases) {
            if (totalSpawned >= serverCap) break;
            int remaining = serverCap - totalSpawned;
            totalSpawned += spawnWaveAtBase(base, wave, isFinalWave, remaining);
        }
    }

    /**
     * プレイヤーのベッド地点を収集し、近すぎる拠点を1つにまとめる。
     * 同じ拠点に複数プレイヤーが住んでいる場合でも1か所としてカウント。
     */
    private List<Location> collectUniqueBases() {
        double mergeRadius = plugin.getSHConfig().getSiegeBaseMergeRadius();
        List<Location> bases = new ArrayList<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("superhard.bypass")) continue;
            Location base = player.getBedSpawnLocation();
            if (base == null) base = player.getLocation();
            if (base.getWorld() == null) continue;

            final Location candidate = base;
            boolean duplicate = bases.stream().anyMatch(existing ->
                existing.getWorld() != null
                && existing.getWorld().equals(candidate.getWorld())
                && existing.distance(candidate) < mergeRadius
            );
            if (!duplicate) bases.add(candidate);
        }
        return bases;
    }

    /**
     * 1拠点にウェーブモブをスポーンし、スポーンした数を返す。
     * 近くにいるプレイヤーを優先ターゲットにする。
     */
    private int spawnWaveAtBase(Location base, int wave, boolean isFinalWave, int cap) {
        if (base.getWorld() == null) return 0;

        int perBase = plugin.getSHConfig().getWaveMobsPerBase(wave);
        int count   = Math.min(perBase, cap);

        // 拠点周辺で最も脅威の高いプレイヤーを代表プレイヤーとして採用
        Player rep = base.getWorld().getNearbyPlayers(base, 60).stream()
            .filter(p -> !p.hasPermission("superhard.bypass"))
            .max((a, b) -> Integer.compare(
                plugin.getThreatManager().getThreat(a),
                plugin.getThreatManager().getThreat(b)))
            .orElse(null);
        if (rep == null) return 0;

        ThreatLevel threat = plugin.getThreatManager().getThreatLevel(rep);

        List<EntityType> waveTypes = switch (wave) {
            case 1  -> List.of(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.SKELETON);
            case 2  -> List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.PILLAGER);
            default -> List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER,
                               EntityType.PILLAGER, EntityType.WITCH);
        };

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            EntityType type = waveTypes.get(i % waveTypes.size());
            Location spawnLoc = SHUtil.safeSpawnNear(base, 15, 30);
            spawnLoc.setWorld(base.getWorld());

            Entity entity = base.getWorld().spawn(spawnLoc, type.getEntityClass());
            if (entity instanceof Mob mob) {
                mob.setTarget(rep);
                scaleSiegeMob(mob, threat);
                siegeMobs.add(mob.getUniqueId());
                spawned++;
            }
        }

        // 最終ウェーブ: 拠点ごとに精鋭を1体
        if (isFinalWave && plugin.getSHConfig().isFinalWaveElite()) {
            Location eliteLoc = SHUtil.safeSpawnNear(base, 12, 20);
            eliteLoc.setWorld(base.getWorld());
            Zombie elite = base.getWorld().spawn(eliteLoc, Zombie.class);
            elite.setTarget(rep);
            plugin.getEliteManager().applyElite(elite, EliteManager.EliteType.ANCIENT);
            siegeMobs.add(elite.getUniqueId());
            spawned++;

            base.getWorld().getNearbyPlayers(base, 60).forEach(p ->
                p.sendMessage(Component.text("[SuperHard] 包囲戦の首領が現れた！", NamedTextColor.GOLD)));
        }

        return spawned;
    }

    private void scaleSiegeMob(Mob mob, ThreatLevel threat) {
        var attr = mob.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        double mult = plugin.getSHConfig().getMobHpMultiplier(threat) * 1.15; // 包囲戦ボーナス
        attr.setBaseValue(attr.getBaseValue() * mult);
        mob.setHealth(attr.getValue());
    }

    // ---- 包囲戦終了 ----

    private void endSiege() {
        siegeActive = false;

        // 残存した包囲戦モブを撤退（消滅）させる
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (siegeMobs.contains(entity.getUniqueId())) {
                    entity.remove();
                }
            }
        }
        siegeMobs.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(Component.text(
                plugin.getSHConfig().getMessage("siege-end-message"), NamedTextColor.GOLD
            ));
        }
    }

    // ---- ユーティリティ ----

    public boolean isSiegeActive() { return siegeActive; }
    public int getCurrentWave()    { return currentWave; }

    public void onSiegeMobDeath(UUID mobId) {
        siegeMobs.remove(mobId);
    }

    private World getOverworld() {
        return Bukkit.getWorlds().stream()
            .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
            .findFirst().orElse(null);
    }
}
