package io.vcmc.superhard.integration;

import io.vcmc.superhard.SuperHardPlugin;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * AuraSkills との連携 — リフレクション実装。
 * コンパイル時に AuraSkills API JAR は不要。
 * サーバーに AuraSkills が入っている場合のみ機能する。
 */
public class AuraSkillsIntegration {

    private final SuperHardPlugin plugin;
    private Object api;
    private Object fightingSkill;
    private Object defenseSkill;
    private Method getUserMethod;
    private Method getSkillLevelMethod;
    private Method addSkillXpMethod;
    private Method getPowerLevelMethod;
    private boolean ready = false;

    public AuraSkillsIntegration(SuperHardPlugin plugin) {
        this.plugin = plugin;
        try {
            init();
            ready = true;
            plugin.getLogger().info("AuraSkills 連携を有効化しました。");
        } catch (Exception e) {
            plugin.getLogger().warning("AuraSkills 連携の初期化に失敗: " + e.getMessage());
        }
    }

    private void init() throws Exception {
        Class<?> apiClass      = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi");
        Class<?> skillInterface = Class.forName("dev.aurelium.auraskills.api.skill.Skill");
        Class<?> skillsClass   = Class.forName("dev.aurelium.auraskills.api.skill.Skills");
        Class<?> userClass     = Class.forName("dev.aurelium.auraskills.api.user.SkillsUser");

        api            = apiClass.getMethod("get").invoke(null);
        getUserMethod  = apiClass.getMethod("getUser", UUID.class);
        fightingSkill  = skillsClass.getField("FIGHTING").get(null);
        defenseSkill   = skillsClass.getField("DEFENSE").get(null);

        getSkillLevelMethod = userClass.getMethod("getSkillLevel", skillInterface);
        addSkillXpMethod    = userClass.getMethod("addSkillXp",    skillInterface, double.class);
        getPowerLevelMethod = userClass.getMethod("getPowerLevel");
    }

    /** プレイヤーの戦闘スコア (Fighting + Defense/2) */
    public int getCombatScore(Player player) {
        if (!ready) return 0;
        try {
            Object user = getUserMethod.invoke(api, player.getUniqueId());
            int fighting = (int) getSkillLevelMethod.invoke(user, fightingSkill);
            int defense  = (int) getSkillLevelMethod.invoke(user, defenseSkill);
            return fighting + (defense / 2);
        } catch (Exception e) { return 0; }
    }

    /** スキルパワーによる追加 HP 倍率 */
    public double getMobHpBonus(Player player) {
        if (!ready) return 1.0;
        try {
            Object user  = getUserMethod.invoke(api, player.getUniqueId());
            int power    = (int) getPowerLevelMethod.invoke(user);
            return 1.0 + (power / 100) * 0.05;
        } catch (Exception e) { return 1.0; }
    }

    /** 精鋭撃破 XP */
    public void grantEliteKillXp(Player player, String eliteTypeName) {
        if (!ready) return;
        double xp = switch (eliteTypeName.toUpperCase()) {
            case "SHURA" -> 150.0;
            case "HASHA" -> 400.0;
            case "TENMA" -> 900.0;
            default      -> 100.0;
        };
        grantXp(player, fightingSkill, xp);
        grantXp(player, defenseSkill,  xp * 0.3);
    }

    /** レイドボス撃破 XP */
    public void grantRaidBossKillXp(Player player) {
        if (!ready) return;
        grantXp(player, fightingSkill, 5000.0);
        grantXp(player, defenseSkill,  2000.0);
    }

    private void grantXp(Player player, Object skill, double amount) {
        try {
            Object user = getUserMethod.invoke(api, player.getUniqueId());
            addSkillXpMethod.invoke(user, skill, amount);
        } catch (Exception ignored) {}
    }
}
