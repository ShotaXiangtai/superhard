package io.vcmc.superhard.command;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.EliteManager;
import io.vcmc.superhard.manager.ThreatManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /superhard (エイリアス: /sh) コマンド
 *
 * サブコマンド:
 *   status          - プレイヤー/サーバーの状態表示
 *   threat [player] - 指定プレイヤーの脅威スコア確認
 *   setlevel <player> <level> - 脅威レベルを強制設定（デバッグ）
 *   siege           - 包囲戦の手動開始/停止
 *   reload          - コンフィグ再読み込み
 *   elite <mob>     - ターゲットのモブを精鋭化（デバッグ）
 */
public class SuperHardCommand implements CommandExecutor, TabCompleter {

    private final SuperHardPlugin plugin;

    public SuperHardCommand(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("superhard.admin")) {
            sender.sendMessage(Component.text("[SuperHard] 権限がありません。", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "status"   -> cmdStatus(sender);
            case "threat"   -> cmdThreat(sender, args);
            case "setlevel" -> cmdSetLevel(sender, args);
            case "siege"    -> cmdSiege(sender, args);
            case "reload"   -> cmdReload(sender);
            case "elite"    -> cmdElite(sender, args);
            case "boss"     -> cmdBoss(sender, args);
            case "raid"     -> cmdRaid(sender, args);
            case "top"      -> cmdTop(sender);
            default -> { sendHelp(sender); yield true; }
        };
    }

    // ---- status ----

    private boolean cmdStatus(CommandSender sender) {
        sender.sendMessage(header("SuperHard ステータス"));
        sender.sendMessage(line("バージョン", plugin.getDescription().getVersion()));
        sender.sendMessage(line("有効", String.valueOf(plugin.getSHConfig().isEnabled())));
        sender.sendMessage(line("包囲戦", plugin.getSiegeManager().isSiegeActive()
            ? "進行中 (第" + plugin.getSiegeManager().getCurrentWave() + "ウェーブ)" : "待機中"));
        sender.sendMessage(line("オンラインプレイヤー", String.valueOf(plugin.getServer().getOnlinePlayers().size())));

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            int pts = plugin.getThreatManager().getThreat(p);
            ThreatManager.ThreatLevel lvl = plugin.getThreatManager().getThreatLevel(p);
            sender.sendMessage(Component.text("  " + p.getName() + ": ", NamedTextColor.GRAY)
                .append(Component.text(pts + "pt ", NamedTextColor.YELLOW))
                .append(Component.text("[" + lvl.displayName + "]", lvl.color)));
        }
        return true;
    }

    // ---- threat ----

    private boolean cmdThreat(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("[SuperHard] プレイヤーが見つかりません: " + args[1], NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(Component.text("[SuperHard] コンソールからはプレイヤー名を指定してください。", NamedTextColor.RED));
            return true;
        }

        int pts = plugin.getThreatManager().getThreat(target);
        ThreatManager.ThreatLevel lvl = plugin.getThreatManager().getThreatLevel(target);
        sender.sendMessage(header(target.getName() + " の脅威情報"));
        sender.sendMessage(line("スコア", pts + " pt"));
        sender.sendMessage(Component.text("  脅威レベル: ", NamedTextColor.GRAY)
            .append(Component.text(lvl.displayName, lvl.color)));
        sender.sendMessage(line("次のレベルまで",
            lvl.ordinal() < ThreatManager.ThreatLevel.values().length - 1
                ? (ThreatManager.ThreatLevel.values()[lvl.ordinal() + 1].min - pts) + " pt"
                : "最大レベル"));
        return true;
    }

    // ---- setlevel ----

    private boolean cmdSetLevel(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("[SuperHard] 使い方: /sh setlevel <player> <0-9999>", NamedTextColor.RED));
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("[SuperHard] プレイヤーが見つかりません: " + args[1], NamedTextColor.RED));
            return true;
        }
        try {
            int value = Integer.parseInt(args[2]);
            if (value < 0 || value > 9999) throw new NumberFormatException();
            int current = plugin.getThreatManager().getThreat(target);
            if (value > current) {
                plugin.getThreatManager().addThreat(target, value - current);
            } else {
                plugin.getThreatManager().reduceThreat(target, current - value);
            }
            sender.sendMessage(Component.text("[SuperHard] " + target.getName() + " の脅威スコアを " + value + " に設定しました。", NamedTextColor.GREEN));
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("[SuperHard] 0〜9999 の数値を指定してください。", NamedTextColor.RED));
        }
        return true;
    }

    // ---- siege ----

    private boolean cmdSiege(CommandSender sender, String[] args) {
        if (plugin.getSiegeManager().isSiegeActive()) {
            sender.sendMessage(Component.text("[SuperHard] 包囲戦はすでに進行中です。", NamedTextColor.YELLOW));
        } else {
            // 手動開始: SiegeManagerの内部メソッドを直接呼ぶ代わりにリフレクション回避のため
            // startSiegeをpublicに変更済み想定だが、コンフィグ経由で強制トリガー
            sender.sendMessage(Component.text("[SuperHard] 包囲戦を強制開始します...", NamedTextColor.GOLD));
            // SiegeManagerのstartSiegeを呼ぶには可視性が必要 - publicメソッドとして公開
            // 現在の実装では checkNight() 経由のみ。この改善は将来対応。
            sender.sendMessage(Component.text("[SuperHard] (注: 強制開始APIは次バージョンで実装予定)", NamedTextColor.GRAY));
        }
        return true;
    }

    // ---- reload ----

    private boolean cmdReload(CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage(Component.text("[SuperHard] コンフィグを再読み込みしました。", NamedTextColor.GREEN));
        return true;
    }

    // ---- elite ----

    private boolean cmdElite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[SuperHard] このコマンドはゲーム内でのみ使用できます。", NamedTextColor.RED));
            return true;
        }

        // プレイヤーの視線先のモブを精鋭化（8ブロック以内）
        Mob found = player.getWorld()
            .getNearbyEntities(player.getLocation(), 8, 8, 8,
                e -> e instanceof Mob && player.hasLineOfSight(e))
            .stream()
            .filter(e -> e instanceof Mob)
            .min((a, b) -> Double.compare(
                a.getLocation().distanceSquared(player.getLocation()),
                b.getLocation().distanceSquared(player.getLocation())))
            .map(e -> (Mob) e)
            .orElse(null);

        if (found == null) {
            player.sendMessage(Component.text("[SuperHard] 視線先にモブが見つかりません (8ブロック以内)。", NamedTextColor.RED));
            return true;
        }

        EliteManager.EliteType type = EliteManager.EliteType.HASHA;
        if (args.length >= 2) {
            try { type = EliteManager.EliteType.valueOf(args[1].toUpperCase()); }
            catch (IllegalArgumentException e) {
                player.sendMessage(Component.text("[SuperHard] 精鋭タイプ: SHURA, HASHA, TENMA", NamedTextColor.YELLOW));
                return true;
            }
        }

        plugin.getEliteManager().applyElite(found, type);
        player.sendMessage(Component.text("[SuperHard] モブを精鋭化しました: " + type.prefix, NamedTextColor.GREEN));
        return true;
    }

    // ---- raid ----

    private boolean cmdRaid(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("[SuperHard] 使い方: /sh raid <start|status>", NamedTextColor.RED));
            return true;
        }
        switch (args[1].toLowerCase()) {
            case "start" -> {
                boolean ok = plugin.getSiegeManager().manualStart();
                sender.sendMessage(ok
                    ? Component.text("[SuperHard] レイドを開始しました。", NamedTextColor.GREEN)
                    : Component.text("[SuperHard] すでにレイドが進行中です。", NamedTextColor.YELLOW));
            }
            case "cancel" -> {
                boolean ok = plugin.getSiegeManager().cancelRaid();
                sender.sendMessage(ok
                    ? Component.text("[SuperHard] レイドをキャンセルしました。", NamedTextColor.GREEN)
                    : Component.text("[SuperHard] 現在レイドは進行していません。", NamedTextColor.YELLOW));
            }
            case "skip" -> {
                boolean ok = plugin.getSiegeManager().skipWave();
                sender.sendMessage(ok
                    ? Component.text("[SuperHard] 次のウェーブにスキップしました。", NamedTextColor.GREEN)
                    : Component.text("[SuperHard] 現在レイドは進行していません。", NamedTextColor.YELLOW));
            }
            case "status" -> sender.sendMessage(
                Component.text("[SuperHard] レイド状況: ", NamedTextColor.GRAY)
                    .append(Component.text(plugin.getSiegeManager().getCountdownString(), NamedTextColor.YELLOW)));
            default -> sender.sendMessage(
                Component.text("[SuperHard] 使い方: /sh raid <start|cancel|skip|status>", NamedTextColor.RED));
        }
        return true;
    }

    // ---- boss ----

    private boolean cmdBoss(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("[SuperHard] 使い方: /sh boss <spawn|cancel>", NamedTextColor.RED));
            return true;
        }
        switch (args[1].toLowerCase()) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("[SuperHard] ゲーム内でのみ使用できます。", NamedTextColor.RED));
                    return true;
                }
                boolean ok = plugin.getRaidBossManager().manualSpawn(player.getLocation());
                sender.sendMessage(ok
                    ? Component.text("[SuperHard] レイドボスをスポーンしました。", NamedTextColor.GREEN)
                    : Component.text("[SuperHard] すでにレイドボスが出現中です。", NamedTextColor.YELLOW));
            }
            case "cancel" -> {
                boolean ok = plugin.getRaidBossManager().cancelBoss();
                sender.sendMessage(ok
                    ? Component.text("[SuperHard] レイドボスをキャンセルしました。", NamedTextColor.GREEN)
                    : Component.text("[SuperHard] 現在レイドボスは出現していません。", NamedTextColor.YELLOW));
            }
            default -> sender.sendMessage(
                Component.text("[SuperHard] 使い方: /sh boss <spawn|cancel>", NamedTextColor.RED));
        }
        return true;
    }

    // ---- top ----

    private boolean cmdTop(CommandSender sender) {
        sender.sendMessage(header("RAGE ランキング TOP 10"));
        var entries = plugin.getThreatManager().getAllThreatsSorted();
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("  データがありません", NamedTextColor.GRAY));
            return true;
        }
        int rank = 1;
        for (var entry : entries) {
            if (rank > 10) break;
            String name = plugin.getServer().getOfflinePlayer(entry.getKey()).getName();
            if (name == null) name = entry.getKey().toString().substring(0, 8);
            var level = ThreatManager.ThreatLevel.fromPoints(entry.getValue());
            sender.sendMessage(Component.text("  #" + rank + " " + name + ": ", NamedTextColor.GRAY)
                .append(Component.text(entry.getValue() + "pt ", NamedTextColor.YELLOW))
                .append(Component.text("[" + level.displayName + "]", level.color)));
            rank++;
        }
        return true;
    }

    // ---- ヘルプ ----

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(header("SuperHard コマンド"));
        sender.sendMessage(cmd("/sh status", "サーバー状態を表示"));
        sender.sendMessage(cmd("/sh threat [player]", "脅威スコアを確認"));
        sender.sendMessage(cmd("/sh setlevel <player> <0-9999>", "脅威スコアを設定"));
        sender.sendMessage(cmd("/sh siege", "包囲戦の状態確認"));
        sender.sendMessage(cmd("/sh reload", "コンフィグ再読み込み"));
        sender.sendMessage(cmd("/sh elite [SHURA|HASHA|TENMA]", "視線先のモブを精鋭化"));
        sender.sendMessage(cmd("/sh top",                        "RAGE ランキング TOP10"));
        sender.sendMessage(cmd("/sh raid <start|cancel|skip|status>", "レイド管理"));
        sender.sendMessage(cmd("/sh boss <spawn|cancel>",      "レイドボス管理"));
    }

    // ---- フォーマットヘルパー ----

    private Component header(String text) {
        return Component.text("─── " + text + " ───", NamedTextColor.GOLD);
    }

    private Component line(String key, String value) {
        return Component.text("  " + key + ": ", NamedTextColor.GRAY)
            .append(Component.text(value, NamedTextColor.WHITE));
    }

    private Component cmd(String command, String desc) {
        return Component.text("  ", NamedTextColor.GRAY)
            .append(Component.text(command, NamedTextColor.AQUA))
            .append(Component.text(" - " + desc, NamedTextColor.GRAY));
    }

    // ---- TabCompleter ----

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("superhard.admin")) return List.of();

        if (args.length == 1) {
            return Arrays.asList("status", "threat", "setlevel", "reload", "elite", "raid", "boss", "top")
                .stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("boss")) {
            return List.of("spawn");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("raid")) {
            return List.of("start", "cancel", "skip", "status");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("boss")) {
            return List.of("spawn", "cancel");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("elite")) {
            return Arrays.stream(EliteManager.EliteType.values())
                .map(Enum::name)
                .filter(s -> s.startsWith(args[1].toUpperCase()))
                .collect(Collectors.toList());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("threat") || args[0].equalsIgnoreCase("setlevel"))) {
            return plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.startsWith(args[1]))
                .collect(Collectors.toList());
        }

        return List.of();
    }
}
