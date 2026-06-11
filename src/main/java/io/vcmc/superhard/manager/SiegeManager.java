package io.vcmc.superhard.manager;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.ThreatManager.ThreatLevel;
import io.vcmc.superhard.util.SHUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

/**
 * レイドシステム。
 *
 * スケジュール:
 *   - 前回終了から MIN〜MAX 時間後にランダムで発生
 *   - 昼間（ゲーム内時刻 0〜12000）はトリガーを保留し、夜になったら即開始
 *   - 誰もオンラインでなければログイン時に即開始
 *
 * 事前告知: 30分前 / 10分前 / 5分前 / 1分前
 *
 * レイド中: ActionBar でウェーブ進行を常時表示
 */
public class SiegeManager {

    private static final String DATA_FILE   = "siege_schedule.yml";
    private static final int[]  WARN_MINUTES = {30, 10, 5, 1};

    // ゲーム内夜の時刻範囲（これ以外は発生しない）
    private static final long NIGHT_START = 12500L;
    private static final long NIGHT_END   = 23000L;

    private final SuperHardPlugin plugin;
    private final File dataFile;

    private BukkitTask checkTask;
    private BukkitTask displayTask;

    private boolean siegeActive = false;
    private int     currentWave = 0;
    private long    nextRaidMs  = 0L;
    private boolean pendingRaid = false;
    private final Set<Integer> firedWarnings = new HashSet<>();
    private final Set<UUID>    siegeMobs     = new HashSet<>();

    public SiegeManager(SuperHardPlugin plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), DATA_FILE);
    }

    // ============================================================
    //  起動 / 停止
    // ============================================================

    public void start() {
        loadSchedule();
        if (nextRaidMs == 0L) {
            scheduleNextRaid(2, 6); // 初回: 2〜6時間後
        }
        checkTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 600L, 600L);
        plugin.getLogger().info("次のレイド: " + getCountdownString());
    }

    public void stop() {
        if (checkTask   != null) checkTask.cancel();
        if (displayTask != null) displayTask.cancel();
        saveSchedule();
    }

    // ============================================================
    //  メインティック (30秒ごと)
    // ============================================================

    private void tick() {
        if (!plugin.getSHConfig().isSiegeEnabled()) return;
        checkWarnings();

        if (siegeActive) return;

        // pending 解消チェック（プレイヤーがいて夜なら即開始）
        if (pendingRaid) {
            if (!Bukkit.getOnlinePlayers().isEmpty() && isNightTime()) {
                pendingRaid = false;
                triggerRaidNow();
            }
            return;
        }

        if (System.currentTimeMillis() < nextRaidMs) return;

        // タイマー到達
        if (Bukkit.getOnlinePlayers().isEmpty()) { pendingRaid = true; return; }
        if (!isNightTime())                       { pendingRaid = true; return; } // 昼間は保留
        triggerRaidNow();
    }

    // ============================================================
    //  レイド開始
    // ============================================================

    /** 手動開始（コマンド or 鋼ブロック着火） */
    public boolean manualStart() {
        if (siegeActive) return false;
        pendingRaid = false;
        triggerRaidNow();
        return true;
    }

    /** プレイヤーログイン時に pending があれば夜なら開始 */
    public void onPlayerJoin() {
        if (pendingRaid && !siegeActive && isNightTime()) {
            pendingRaid = false;
            plugin.getServer().getScheduler().runTaskLater(plugin, this::triggerRaidNow, 60L);
        }
    }

    private void triggerRaidNow() {
        pendingRaid = false;
        firedWarnings.clear();
        siegeActive = true;
        currentWave = 0;
        siegeMobs.clear();

        // スケジュール更新
        scheduleNextRaid(
            plugin.getSHConfig().getSiegeMinIntervalHours(),
            plugin.getSHConfig().getSiegeMaxIntervalHours()
        );

        // 全員にタイトル通知
        Title title = Title.title(
            Component.text(plugin.getSHConfig().getMessage("siege-start-title"), NamedTextColor.DARK_RED),
            Component.text(plugin.getSHConfig().getMessage("siege-start-subtitle"), NamedTextColor.GRAY),
            Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000))
        );
        Bukkit.getOnlinePlayers().forEach(p -> {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT,          1.5f, 0.5f);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 0.7f);
            }, 15L);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 0.6f);
            }, 30L);
        });

        // ActionBar 表示タスク開始
        startDisplayTask();

        // ウェーブスケジュール
        long interval = plugin.getSHConfig().getWaveIntervalTicks();
        int  waves    = plugin.getSHConfig().getWaveCount();
        for (int w = 1; w <= waves; w++) {
            final int wave = w;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> spawnWave(wave), 60L + interval * (wave - 1));
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, this::endRaid, 60L + interval * waves);
    }

    // ============================================================
    //  ウェーブスポーン
    // ============================================================

    private void spawnWave(int wave) {
        if (!siegeActive) return;
        currentWave = wave;
        boolean isFinalWave = wave == plugin.getSHConfig().getWaveCount();

        String waveMsg = plugin.getSHConfig().getWaveMessage(wave);
        Bukkit.getOnlinePlayers().forEach(p -> {
            p.sendMessage(Component.text(waveMsg, NamedTextColor.DARK_RED));
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_AMBIENT, 0.8f, 0.7f);
        });

        List<Location> bases   = collectUniqueBases();
        int            cap     = plugin.getSHConfig().getSiegeServerMobCap();
        int            spawned = 0;

        for (Location base : bases) {
            if (spawned >= cap) break;
            spawned += spawnWaveAtBase(base, wave, isFinalWave, cap - spawned);
        }
    }

    private List<Location> collectUniqueBases() {
        double mergeRadius = plugin.getSHConfig().getSiegeBaseMergeRadius();
        List<Location> bases = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("superhard.bypass")) continue;
            Location base = player.getBedSpawnLocation();
            if (base == null) base = player.getLocation();
            if (base.getWorld() == null) continue;
            // ベッドが地下にある場合は地表に引き上げる
            if (SHUtil.isUnderground(base)) {
                int surfaceY = base.getWorld().getHighestBlockYAt(base.getBlockX(), base.getBlockZ()) + 1;
                base = new Location(base.getWorld(), base.getX(), surfaceY, base.getZ());
            }
            final Location candidate = base;
            boolean dup = bases.stream().anyMatch(e ->
                e.getWorld() != null && e.getWorld().equals(candidate.getWorld())
                && e.distance(candidate) < mergeRadius);
            if (!dup) bases.add(candidate);
        }
        return bases;
    }

    private int spawnWaveAtBase(Location base, int wave, boolean isFinalWave, int cap) {
        if (base.getWorld() == null) return 0;
        int count = Math.min(plugin.getSHConfig().getWaveMobsPerBase(wave), cap);

        Player rep = base.getWorld().getNearbyPlayers(base, 60).stream()
            .filter(p -> !p.hasPermission("superhard.bypass"))
            .max(Comparator.comparingInt(p -> plugin.getThreatManager().getThreat(p)))
            .orElse(null);
        if (rep == null) return 0;

        ThreatLevel threat = plugin.getThreatManager().getThreatLevel(rep);

        List<EntityType> types = switch (wave) {
            case 1  -> List.of(EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.SKELETON);
            case 2  -> List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.PILLAGER);
            default -> List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER,
                               EntityType.PILLAGER, EntityType.WITCH);
        };

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Location spawnLoc = SHUtil.safeSpawnNear(base, 15, 30);
            spawnLoc.setWorld(base.getWorld());
            Entity entity = base.getWorld().spawn(spawnLoc, types.get(i % types.size()).getEntityClass());
            if (entity instanceof Mob mob) {
                mob.setTarget(rep);
                scaleSiegeMob(mob, threat);
                siegeMobs.add(mob.getUniqueId());
                spawned++;
            }
        }

        if (isFinalWave && plugin.getSHConfig().isFinalWaveElite()) {
            Location eliteLoc = SHUtil.safeSpawnNear(base, 12, 20);
            eliteLoc.setWorld(base.getWorld());
            Zombie elite = base.getWorld().spawn(eliteLoc, Zombie.class);
            elite.setTarget(rep);
            plugin.getEliteManager().applyElite(elite, EliteManager.EliteType.HASHA);
            siegeMobs.add(elite.getUniqueId());
            spawned++;
            base.getWorld().getNearbyPlayers(base, 60).forEach(p ->
                p.sendMessage(Component.text("[SuperHard] レイドの首領が現れた！", NamedTextColor.GOLD)));
        }
        return spawned;
    }

    private void scaleSiegeMob(Mob mob, ThreatLevel threat) {
        var attr = mob.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        attr.setBaseValue(attr.getBaseValue() * plugin.getSHConfig().getMobHpMultiplier(threat) * 1.15);
        mob.setHealth(attr.getValue());
    }

    // ============================================================
    //  レイド終了
    // ============================================================

    private void endRaid() {
        siegeActive = false;
        if (displayTask != null) { displayTask.cancel(); displayTask = null; }

        for (World world : Bukkit.getWorlds()) {
            world.getEntities().stream()
                .filter(e -> siegeMobs.contains(e.getUniqueId()))
                .forEach(Entity::remove);
        }
        siegeMobs.clear();

        Bukkit.getOnlinePlayers().forEach(p ->
            p.sendMessage(Component.text(
                plugin.getSHConfig().getMessage("siege-end-message"), NamedTextColor.GOLD)));
    }

    // ============================================================
    //  ActionBar 表示タスク
    // ============================================================

    private void startDisplayTask() {
        if (displayTask != null && !displayTask.isCancelled()) displayTask.cancel();
        displayTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!siegeActive) { displayTask.cancel(); return; }
            Component bar = Component.text("⚔ RAID  Wave ", NamedTextColor.RED)
                .append(Component.text(currentWave + " / " + plugin.getSHConfig().getWaveCount(), NamedTextColor.WHITE)
                    .decoration(TextDecoration.BOLD, true))
                .append(Component.text("  ⚔", NamedTextColor.RED));
            Bukkit.getOnlinePlayers().forEach(p -> p.sendActionBar(bar));
        }, 0L, 60L); // 3秒ごとにActionBar更新
    }

    // ============================================================
    //  事前アナウンス
    // ============================================================

    private void checkWarnings() {
        if (siegeActive) return;
        long diff = nextRaidMs - System.currentTimeMillis();
        for (int warnMin : WARN_MINUTES) {
            long warnMs = (long) warnMin * 60_000L;
            if (diff <= warnMs && diff > warnMs - 30_000L && !firedWarnings.contains(warnMin)) {
                firedWarnings.add(warnMin);
                broadcastRaidWarning(warnMin);
            }
        }
    }

    private void broadcastRaidWarning(int minutesLeft) {
        Component msg = minutesLeft <= 1
            ? Component.text("[SuperHard] ", NamedTextColor.DARK_RED)
                .append(Component.text("⚔ レイドが ", NamedTextColor.RED))
                .append(Component.text("まもなく", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" 始まる！備えろ！", NamedTextColor.RED))
            : Component.text("[SuperHard] ", NamedTextColor.DARK_PURPLE)
                .append(Component.text("レイド開始まで ", NamedTextColor.GRAY))
                .append(Component.text(minutesLeft + "分", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true));

        Bukkit.getOnlinePlayers().forEach(p -> {
            p.sendMessage(msg);
            if (minutesLeft <= 5)
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.8f, 0.6f);
        });
    }

    // ============================================================
    //  スケジューリング・カウントダウン
    // ============================================================

    private void scheduleNextRaid(long minHours, long maxHours) {
        long delay = minHours * 3_600_000L
            + (long)(Math.random() * (maxHours - minHours) * 3_600_000L);
        nextRaidMs = System.currentTimeMillis() + delay;
        saveSchedule();
    }

    public String getCountdownString() {
        if (siegeActive) return "RAID 中 (Wave " + currentWave + " / " + plugin.getSHConfig().getWaveCount() + ")";
        if (pendingRaid)  return "夜になり次第開始";
        long diff = nextRaidMs - System.currentTimeMillis();
        if (diff <= 0) return "夜になり次第開始";
        long h = diff / 3_600_000L;
        long m = (diff % 3_600_000L) / 60_000L;
        if (h > 0) return h + "時間" + m + "分後 (夜限定)";
        if (m > 0) return m + "分後 (夜限定)";
        return "まもなく";
    }

    /** ログイン時に表示するコンポーネント */
    public Component getLoginStatusComponent() {
        if (siegeActive) {
            return Component.text("[SuperHard] ", NamedTextColor.DARK_RED)
                .append(Component.text("⚔ RAID 進行中！ Wave " + currentWave, NamedTextColor.RED)
                    .decoration(TextDecoration.BOLD, true));
        }
        return Component.text("[SuperHard] ", NamedTextColor.DARK_PURPLE)
            .append(Component.text("次のレイドまで ", NamedTextColor.GRAY))
            .append(Component.text(getCountdownString(), NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.BOLD, true));
    }

    // ============================================================
    //  ユーティリティ
    // ============================================================

    private boolean isNightTime() {
        World overworld = getOverworld();
        if (overworld == null) return true;
        long time = overworld.getTime();
        return time >= NIGHT_START && time <= NIGHT_END;
    }

    private World getOverworld() {
        return Bukkit.getWorlds().stream()
            .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
            .findFirst().orElse(null);
    }

    public boolean isSiegeActive()   { return siegeActive; }
    public int    getCurrentWave()   { return currentWave; }

    public void onSiegeMobDeath(UUID mobId) { siegeMobs.remove(mobId); }

    // ============================================================
    //  セーブ / ロード
    // ============================================================

    private void saveSchedule() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-raid-ms", nextRaidMs);
        try { yaml.save(dataFile); } catch (IOException e) {
            plugin.getLogger().warning("siege_schedule.yml の保存に失敗: " + e.getMessage());
        }
    }

    private void loadSchedule() {
        if (!dataFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        nextRaidMs = yaml.getLong("next-raid-ms", 0L);
    }
}
