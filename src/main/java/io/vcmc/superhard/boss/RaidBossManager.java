package io.vcmc.superhard.boss;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.util.SHUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * レイドボスのスポーンスケジュールとアクティブ管理。
 *
 * スポーン仕様:
 *   - 前回討伐または初回起動から MIN〜MAX 時間後にランダムでスポーン
 *   - MAX = 24h なので最低1日1回は必ず出現
 *   - 誰もオンラインでなければ次のプレイヤーログイン時まで待機
 *   - 最高 RAGE スコアのプレイヤーの近くに出現
 *
 * 事前告知:
 *   - 30分前 / 10分前 / 5分前 / 1分前 に全体アナウンス
 */
public class RaidBossManager {

    private static final String DATA_FILE  = "boss_schedule.yml";
    private static final String KEY_NEXT   = "next-spawn-ms";

    // 告知タイミング (分)
    private static final int[] WARN_MINUTES = {30, 10, 5, 1};

    private final SuperHardPlugin plugin;
    private final Map<UUID, TenmaouBoss> activeBosses = new HashMap<>();

    private long nextSpawnMs = 0L;
    private boolean pendingSpawn = false; // オフライン中に時刻を過ぎた場合のフラグ
    private final Set<Integer> firedWarnings = new HashSet<>(); // 重複防止
    private BukkitTask checkTask;
    private File dataFile;

    public RaidBossManager(SuperHardPlugin plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), DATA_FILE);
    }

    // ============================================================
    //  起動 / 停止
    // ============================================================

    public void start() {
        loadSchedule();

        if (nextSpawnMs == 0L) {
            // 初回: 1〜4時間後にスポーン（サーバー起動直後は少し待つ）
            scheduleNextSpawn(1, 4);
        }

        // 30秒ごとにスケジュールチェック
        checkTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 600L, 600L);
        plugin.getLogger().info("レイドボス 次回降臨: " + getCountdownString());
    }

    public void shutdown() {
        if (checkTask != null) checkTask.cancel();
        saveSchedule();
        activeBosses.values().forEach(b -> { if (b.getEntity().isValid()) b.getEntity().remove(); });
        activeBosses.clear();
    }

    // ============================================================
    //  毎 30 秒ティック
    // ============================================================

    private void tick() {
        if (hasActiveBoss()) return;

        long now  = System.currentTimeMillis();
        long diff = nextSpawnMs - now;

        // 事前アナウンス
        for (int warnMin : WARN_MINUTES) {
            long warnMs = (long) warnMin * 60_000L;
            if (diff <= warnMs && diff > warnMs - 30_000L && !firedWarnings.contains(warnMin)) {
                firedWarnings.add(warnMin);
                broadcastWarning(warnMin);
            }
        }

        // スポーン時刻到達
        if (diff <= 0) {
            if (Bukkit.getOnlinePlayers().isEmpty()) {
                // 誰もいない → ログイン時に即スポーンさせるフラグだけ立てる
                pendingSpawn = true;
                return;
            }
            triggerSpawn();
        }
    }

    /** プレイヤーがログインした時に呼ぶ — pending があれば即スポーン */
    public void onPlayerJoin(Player joiner) {
        if (pendingSpawn && !hasActiveBoss()) {
            pendingSpawn = false;
            // 少し遅らせて演出が綺麗に見えるようにする
            plugin.getServer().getScheduler().runTaskLater(plugin, this::triggerSpawn, 60L);
        }
    }

    // ============================================================
    //  スポーン
    // ============================================================

    private void triggerSpawn() {
        if (hasActiveBoss()) return;
        pendingSpawn = false;

        Location loc = findSpawnLocation();
        if (loc == null) {
            // まだ誰もいない
            pendingSpawn = true;
            return;
        }

        spawnBoss(loc);
        firedWarnings.clear();
        scheduleNextSpawn(
            plugin.getSHConfig().getBossMinIntervalHours(),
            plugin.getSHConfig().getBossMaxIntervalHours()
        );
    }

    private void spawnBoss(Location loc) {
        TenmaouBoss boss = new TenmaouBoss(plugin, loc);
        activeBosses.put(boss.getEntity().getUniqueId(), boss);
        plugin.getLogger().info("レイドボスをスポーンしました: " + loc);
    }

    /**
     * 管理者コマンドから手動スポーン。
     * アクティブボスが既にいる場合は失敗。
     */
    public boolean manualSpawn(Location loc) {
        if (hasActiveBoss()) return false;
        spawnBoss(loc);
        firedWarnings.clear();
        scheduleNextSpawn(
            plugin.getSHConfig().getBossMinIntervalHours(),
            plugin.getSHConfig().getBossMaxIntervalHours()
        );
        return true;
    }

    // ============================================================
    //  スケジューリング
    // ============================================================

    private void scheduleNextSpawn(long minHours, long maxHours) {
        long range = (maxHours - minHours) * 3_600_000L;
        long delay = minHours * 3_600_000L + (long)(Math.random() * range);
        nextSpawnMs = System.currentTimeMillis() + delay;
        saveSchedule();
        plugin.getLogger().info("レイドボス 次回降臨: " + getCountdownString());
    }

    // ============================================================
    //  カウントダウン表示
    // ============================================================

    public String getCountdownString() {
        if (hasActiveBoss()) return "現在交戦中";
        if (pendingSpawn)    return "まもなく降臨";
        long diff = nextSpawnMs - System.currentTimeMillis();
        if (diff <= 0) return "まもなく降臨";
        long h = diff / 3_600_000L;
        long m = (diff % 3_600_000L) / 60_000L;
        if (h > 0) return h + "時間" + m + "分後";
        if (m > 0) return m + "分後";
        return "まもなく";
    }

    /** ログイン時に表示するコンポーネント */
    public Component getLoginStatusComponent() {
        if (hasActiveBoss()) {
            return Component.text("[SuperHard] ", NamedTextColor.DARK_RED)
                .append(Component.text("⚠ レイドボスが今まさに降臨中だ！", NamedTextColor.RED)
                    .decoration(TextDecoration.BOLD, true));
        }
        return Component.text("[SuperHard] ", NamedTextColor.DARK_PURPLE)
            .append(Component.text("レイドボス降臨まで ", NamedTextColor.GRAY))
            .append(Component.text(getCountdownString(), NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true));
    }

    // ============================================================
    //  アナウンス
    // ============================================================

    private void broadcastWarning(int minutesLeft) {
        Component msg;
        if (minutesLeft <= 1) {
            msg = Component.text("[SuperHard] ", NamedTextColor.DARK_RED)
                .append(Component.text("⚠ レイドボスが ", NamedTextColor.RED))
                .append(Component.text("まもなく", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" 降臨する！集まれ！", NamedTextColor.RED));
        } else {
            msg = Component.text("[SuperHard] ", NamedTextColor.DARK_PURPLE)
                .append(Component.text("レイドボス降臨まで ", NamedTextColor.GRAY))
                .append(Component.text(minutesLeft + "分", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true));
        }

        Bukkit.getOnlinePlayers().forEach(p -> {
            p.sendMessage(msg);
            if (minutesLeft <= 5) {
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 1.0f, 0.6f);
            }
        });
    }

    // ============================================================
    //  ユーティリティ
    // ============================================================

    public TenmaouBoss getBoss(Entity entity) {
        return activeBosses.get(entity.getUniqueId());
    }

    public boolean hasActiveBoss() {
        return !activeBosses.isEmpty();
    }

    public void onBossFinished(UUID bossId) {
        activeBosses.remove(bossId);
        firedWarnings.clear();
        scheduleNextSpawn(
            plugin.getSHConfig().getBossMinIntervalHours(),
            plugin.getSHConfig().getBossMaxIntervalHours()
        );
        plugin.getLogger().info("レイドボス討伐。次回降臨: " + getCountdownString());
    }

    /** 最高 RAGE スコアのプレイヤーの近くを返す */
    private Location findSpawnLocation() {
        Player target = Bukkit.getOnlinePlayers().stream()
            .filter(p -> !p.hasPermission("superhard.bypass")
                      && p.getGameMode() == org.bukkit.GameMode.SURVIVAL)
            .max(Comparator.comparingInt(p -> plugin.getThreatManager().getThreat(p)))
            .orElse(null);
        if (target == null) target = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (target == null) return null;
        return SHUtil.safeSpawnNear(target.getLocation(), 20, 40);
    }

    // ============================================================
    //  セーブ / ロード
    // ============================================================

    private void saveSchedule() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(KEY_NEXT, nextSpawnMs);
        try { yaml.save(dataFile); } catch (IOException e) {
            plugin.getLogger().warning("boss_schedule.yml の保存に失敗: " + e.getMessage());
        }
    }

    private void loadSchedule() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        nextSpawnMs = yaml.getLong(KEY_NEXT, 0L);
    }
}
