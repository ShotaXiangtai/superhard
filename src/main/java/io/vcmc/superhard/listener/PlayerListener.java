package io.vcmc.superhard.listener;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.ThreatManager;
import io.papermc.paper.event.player.AsyncChatEvent;
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
        plugin.getScoreboardManager().initPlayer(player);

        // pending チェック
        plugin.getRaidBossManager().onPlayerJoin(player);
        plugin.getSiegeManager().onPlayerJoin();

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
            // レイドカウントダウン
            player.sendMessage(plugin.getSiegeManager().getLoginStatusComponent());
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        plugin.getThreatManager().removePlayer(uuid);
        plugin.getScoreboardManager().removePlayer(uuid);
    }

    // ---- チャットプレフィックス [Lv.X] ----

    @EventHandler(priority = EventPriority.NORMAL)
    public void onChat(AsyncChatEvent event) {
        var player = event.getPlayer();
        ThreatManager.ThreatLevel level = plugin.getThreatManager().getThreatLevel(player);
        Component prefix = Component.text("[" + level.displayName + "] ", level.color);
        event.renderer((source, displayName, message, viewer) ->
            prefix.append(displayName)
                  .append(Component.text(": ", NamedTextColor.WHITE))
                  .append(message));
    }
}
