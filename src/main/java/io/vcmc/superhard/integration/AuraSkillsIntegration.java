package io.vcmc.superhard.integration;

import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.skill.Skills;
import dev.aurelium.auraskills.api.user.SkillsUser;
import io.vcmc.superhard.SuperHardPlugin;
import org.bukkit.entity.Player;

/**
 * AuraSkills との連携。ソフトデペンデンシー対応:
 * AuraSkills が入っていない場合はこのクラスは一切インスタンス化されない。
 *
 * 連携機能:
 *   - スキルレベルによる脅威スコア補正
 *   - モブHP追加スケール
 *   - 精鋭・レイドボス撃破時のXPボーナス
 */
public class AuraSkillsIntegration {

    private final SuperHardPlugin plugin;
    private final AuraSkillsApi api;

    public AuraSkillsIntegration(SuperHardPlugin plugin) {
        this.plugin = plugin;
        this.api    = AuraSkillsApi.get();
        plugin.getLogger().info("AuraSkills 連携を有効化しました。");
    }

    /**
     * プレイヤーの「戦闘力スコア」を返す。
     * 脅威スコアのパッシブゲイン補正に使用。
     * Fighting Lv + Defense Lv / 2 ≒ 実質戦闘力
     */
    public int getCombatScore(Player player) {
        try {
            SkillsUser user = api.getUser(player.getUniqueId());
            int fighting = user.getSkillLevel(Skills.FIGHTING);
            int defense  = user.getSkillLevel(Skills.DEFENSE);
            return fighting + (defense / 2);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * プレイヤーの総スキルパワーレベルに基づくHP追加倍率。
     * スキルが高いほど周囲のモブが強くなる。
     */
    public double getMobHpBonus(Player player) {
        try {
            SkillsUser user = api.getUser(player.getUniqueId());
            int power = user.getPowerLevel();
            // 100スキルPowerごとに+5%
            return 1.0 + (power / 100) * 0.05;
        } catch (Exception e) {
            return 1.0;
        }
    }

    /**
     * 精鋭モブ撃破時のFighting XP付与。
     */
    public void grantEliteKillXp(Player player, String eliteTypeName) {
        double xp = switch (eliteTypeName.toUpperCase()) {
            case "SHURA" -> 150.0;
            case "HASHA" -> 400.0;
            case "TENMA" -> 900.0;
            default      -> 100.0;
        };
        grantXp(player, Skills.FIGHTING, xp);
        grantXp(player, Skills.DEFENSE,  xp * 0.3);
    }

    /**
     * レイドボス撃破参加者へのXPボーナス。
     */
    public void grantRaidBossKillXp(Player player) {
        grantXp(player, Skills.FIGHTING, 5000.0);
        grantXp(player, Skills.DEFENSE,  2000.0);
    }

    private void grantXp(Player player, Skills skill, double amount) {
        try {
            api.getUser(player.getUniqueId()).addSkillXp(skill, amount);
        } catch (Exception ignored) {}
    }
}
