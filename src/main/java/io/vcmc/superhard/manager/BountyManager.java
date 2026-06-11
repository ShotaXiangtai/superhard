package io.vcmc.superhard.manager;

import io.vcmc.superhard.SuperHardPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * モブバウンティシステム。
 * 一定確率で精鋭モブが「賞金首」になる。
 * 倒すと追加の 鋼 をドロップし、サーバー全体に通知が流れる。
 */
public class BountyManager {

    private final SuperHardPlugin plugin;
    private final Set<UUID> bountyMobs = new HashSet<>();

    public BountyManager(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 精鋭化直後に呼ぶ。確率でバウンティを付与する。
     * HASHA / TENMA のみ対象。
     */
    public void tryAssignBounty(Mob mob, EliteManager.EliteType type) {
        if (!plugin.getSHConfig().isBountyEnabled()) return;
        if (type == EliteManager.EliteType.SHURA) return; // Lv.1 は対象外
        if (Math.random() >= plugin.getSHConfig().getBountyChance()) return;

        bountyMobs.add(mob.getUniqueId());

        // 名前に ★ を追加
        Component current = mob.customName();
        Component bountyName = Component.text("★ ", NamedTextColor.YELLOW)
            .decoration(TextDecoration.BOLD, true)
            .append(current != null ? current : Component.text(mob.getType().name(), NamedTextColor.WHITE))
            .append(Component.text(" [賞金首]", NamedTextColor.YELLOW)
                .decoration(TextDecoration.BOLD, false));
        mob.customName(bountyName);

        // 近くのプレイヤーに通知
        mob.getWorld().getNearbyPlayers(mob.getLocation(), 48).forEach(p ->
            p.sendMessage(Component.text("[SuperHard] ", NamedTextColor.YELLOW)
                .append(Component.text("★ 賞金首が現れた！倒せば追加報酬あり", NamedTextColor.GOLD)))
        );
    }

    public boolean isBounty(UUID mobId) {
        return bountyMobs.contains(mobId);
    }

    /** バウンティモブ討伐時の報酬処理 */
    public void onBountyKill(Mob mob, Player killer) {
        if (!bountyMobs.remove(mob.getUniqueId())) return;

        int count = plugin.getSHConfig().getBountyExtraShards();
        for (int i = 0; i < count; i++) {
            mob.getWorld().dropItemNaturally(mob.getLocation(),
                plugin.getEliteManager().createTemperedShard());
        }

        killer.sendMessage(Component.text(
            "[SuperHard] 賞金首を倒した！ 鋼 +" + count + " ボーナス", NamedTextColor.GOLD)
            .decoration(TextDecoration.BOLD, true));

        // サーバー全体通知
        plugin.getServer().broadcast(
            Component.text("[SuperHard] ", NamedTextColor.YELLOW)
                .append(Component.text(killer.getName(), NamedTextColor.WHITE))
                .append(Component.text(" が賞金首を討伐した！", NamedTextColor.GOLD))
        );

        plugin.getDiscordWebhook().send(
            "🏅 **" + killer.getName() + "** が賞金首を討伐した！ (+" + count + " 鋼)");
    }

    /** モブ死亡時のクリーンアップ */
    public void onMobDeath(UUID id) {
        bountyMobs.remove(id);
    }
}
