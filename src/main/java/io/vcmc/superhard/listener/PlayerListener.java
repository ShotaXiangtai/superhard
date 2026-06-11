package io.vcmc.superhard.listener;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.ThreatManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * プレイヤーの接続・切断時の脅威データ管理。
 */
public class PlayerListener implements Listener {

    private final SuperHardPlugin plugin;

    public PlayerListener(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        plugin.getThreatManager().initPlayer(player.getUniqueId());

        if (player.hasPermission("superhard.bypass")) return;

        ThreatManager.ThreatLevel level = plugin.getThreatManager().getThreatLevel(player);
        int points = plugin.getThreatManager().getThreat(player);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage(Component.text(
                "[SuperHard] 現在の脅威スコア: ", NamedTextColor.GRAY
            ).append(Component.text(
                points + " pt", NamedTextColor.YELLOW
            )).append(Component.text(
                " / レベル: ", NamedTextColor.GRAY
            )).append(Component.text(
                level.displayName, level.color
            )));

            if (plugin.getSiegeManager().isSiegeActive()) {
                player.sendMessage(Component.text(
                    "[SuperHard] 警告: 現在包囲戦が進行中！ 第" + plugin.getSiegeManager().getCurrentWave() + "ウェーブ",
                    NamedTextColor.DARK_RED
                ));
            }
        }, 40L); // ログイン2秒後に表示
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getThreatManager().removePlayer(event.getPlayer().getUniqueId());
    }
}
