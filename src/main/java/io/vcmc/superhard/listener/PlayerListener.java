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

public class PlayerListener implements Listener {

    private final SuperHardPlugin plugin;

    public PlayerListener(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        plugin.getThreatManager().initPlayer(player.getUniqueId());

        // pending スポーンのチェック（オフライン中に時刻を過ぎていた場合）
        plugin.getRaidBossManager().onPlayerJoin(player);

        if (player.hasPermission("superhard.bypass")) return;

        ThreatManager.ThreatLevel level  = plugin.getThreatManager().getThreatLevel(player);
        int points = plugin.getThreatManager().getThreat(player);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            // RAGE スコア
            player.sendMessage(
                Component.text("[SuperHard] ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("RAGE: ", NamedTextColor.GRAY))
                    .append(Component.text(points + " pt ", NamedTextColor.YELLOW))
                    .append(Component.text("/ ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(level.displayName, level.color))
            );

            // レイドボス降臨カウントダウン
            player.sendMessage(plugin.getRaidBossManager().getLoginStatusComponent());

            // レイド中なら警告
            if (plugin.getSiegeManager().isSiegeActive()) {
                player.sendMessage(Component.text(
                    "[SuperHard] ⚠ レイド進行中！ Wave " + plugin.getSiegeManager().getCurrentWave(),
                    NamedTextColor.DARK_RED
                ));
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getThreatManager().removePlayer(event.getPlayer().getUniqueId());
    }
}
