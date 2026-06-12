package io.vcmc.superhard.manager;

import io.vcmc.superhard.SuperHardPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーごとのサイドバーを管理する。
 * 5秒ごとに RAGE / 次レイド / 次ボス降臨 を更新して表示する。
 */
public class ScoreboardManager {

    private final SuperHardPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private BukkitTask updateTask;

    public ScoreboardManager(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        updateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, 20L, 100L);
    }

    public void stop() {
        if (updateTask != null) updateTask.cancel();
    }

    public void initPlayer(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        updateBoard(player, board);
    }

    public void removePlayer(UUID id) {
        boards.remove(id);
    }

    // ---- 全プレイヤー更新 ----

    private void updateAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard board = boards.computeIfAbsent(p.getUniqueId(), id -> {
                Scoreboard b = Bukkit.getScoreboardManager().getNewScoreboard();
                p.setScoreboard(b);
                return b;
            });
            updateBoard(p, board);
        }
    }

    private void updateBoard(Player player, Scoreboard board) {
        Objective old = board.getObjective("sh");
        if (old != null) old.unregister();

        Objective obj = board.registerNewObjective("sh", Criteria.DUMMY,
            Component.text("SuperHard", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        ThreatManager.ThreatLevel level = plugin.getThreatManager().getThreatLevel(player);
        int pts      = plugin.getThreatManager().getThreat(player);
        String raid  = plugin.getSiegeManager().getCountdownString();
        String boss  = plugin.getRaidBossManager().getCountdownString();

        // 行数は多い方が上に表示される
        int s = 10;
        setLine(obj, s--, "§r");
        setLine(obj, s--, "§e§lRAGE");
        setLine(obj, s--, " " + color(level) + level.displayName + " §7(" + pts + "pt)");
        setLine(obj, s--, "§r§r");
        setLine(obj, s--, "§b§lレイド");
        setLine(obj, s--, " §f" + shorten(raid));
        setLine(obj, s--, "§r§r§r");
        setLine(obj, s--, "§d§lボス");
        setLine(obj, s--, " §f" + shorten(boss));
        setLine(obj, s,   "§r§r§r§r");
    }

    private void setLine(Objective obj, int score, String text) {
        obj.getScore(text).setScore(score);
    }

    /** ThreatLevel → 旧式カラーコード文字列 */
    private String color(ThreatManager.ThreatLevel level) {
        return switch (level) {
            case CALM       -> "§a";
            case AGITATED   -> "§e";
            case HOSTILE    -> "§6";
            case INFURIATED -> "§c";
            case WRATHFUL   -> "§4";
        };
    }

    /** 長い文字列を20文字でカット（サイドバー幅対策） */
    private String shorten(String s) {
        return s.length() > 20 ? s.substring(0, 19) + "…" : s;
    }
}
