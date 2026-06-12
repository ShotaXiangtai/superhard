package io.vcmc.superhard;

import io.vcmc.superhard.boss.RaidBossManager;
import io.vcmc.superhard.command.SuperHardCommand;
import io.vcmc.superhard.config.SHConfig;
import io.vcmc.superhard.integration.AuraSkillsIntegration;
import io.vcmc.superhard.listener.BossListener;
import io.vcmc.superhard.listener.CombatListener;
import io.vcmc.superhard.listener.CraftingListener;
import io.vcmc.superhard.listener.MobSpawnListener;
import io.vcmc.superhard.listener.PlayerListener;
import io.vcmc.superhard.manager.BountyManager;
import io.vcmc.superhard.manager.CursedLocationManager;
import io.vcmc.superhard.manager.EliteManager;
import io.vcmc.superhard.manager.FieldBossManager;
import io.vcmc.superhard.manager.MobBehaviorManager;
import io.vcmc.superhard.manager.PlayerStatsManager;
import io.vcmc.superhard.manager.ScoreboardManager;
import io.vcmc.superhard.manager.SiegeManager;
import io.vcmc.superhard.manager.ThreatManager;
import io.vcmc.superhard.util.DiscordWebhook;
import org.bukkit.plugin.java.JavaPlugin;

public final class SuperHardPlugin extends JavaPlugin {

    private static SuperHardPlugin instance;

    private SHConfig shConfig;
    private ThreatManager threatManager;
    private EliteManager eliteManager;
    private SiegeManager siegeManager;
    private MobBehaviorManager behaviorManager;
    private RaidBossManager raidBossManager;
    private AuraSkillsIntegration auraSkillsIntegration;
    private CursedLocationManager cursedLocationManager;
    private BountyManager bountyManager;
    private DiscordWebhook discordWebhook;
    private PlayerStatsManager statsManager;
    private ScoreboardManager scoreboardManager;
    private FieldBossManager fieldBossManager;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        shConfig = new SHConfig(this);

        discordWebhook        = new DiscordWebhook(this);
        cursedLocationManager = new CursedLocationManager(this);
        bountyManager         = new BountyManager(this);
        threatManager         = new ThreatManager(this);
        eliteManager          = new EliteManager(this);
        siegeManager          = new SiegeManager(this);
        behaviorManager       = new MobBehaviorManager(this);
        raidBossManager       = new RaidBossManager(this);

        // AuraSkills ソフト連携（入っている場合のみ有効化）
        if (getServer().getPluginManager().isPluginEnabled("AuraSkills")) {
            auraSkillsIntegration = new AuraSkillsIntegration(this);
        }

        var pm = getServer().getPluginManager();
        pm.registerEvents(new MobSpawnListener(this), this);
        pm.registerEvents(new CombatListener(this),   this);
        pm.registerEvents(new PlayerListener(this),   this);
        pm.registerEvents(new CraftingListener(this), this);
        pm.registerEvents(new BossListener(this),     this);

        var cmd = getCommand("superhard");
        if (cmd != null) {
            var handler = new SuperHardCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        statsManager        = new PlayerStatsManager(this);
        scoreboardManager   = new ScoreboardManager(this);
        fieldBossManager    = new FieldBossManager(this);

        threatManager.load();
        behaviorManager.start();
        siegeManager.start();
        raidBossManager.start();
        scoreboardManager.start();
        fieldBossManager.start();

        getLogger().info("SuperHard が有効化されました！ Hard SMPへようこそ。");
        getLogger().info("バージョン: " + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        if (raidBossManager   != null) raidBossManager.shutdown();
        if (behaviorManager   != null) behaviorManager.stop();
        if (threatManager     != null) { threatManager.save(); threatManager.stop(); }
        if (siegeManager      != null) siegeManager.stop();
        if (scoreboardManager != null) scoreboardManager.stop();
        if (fieldBossManager  != null) fieldBossManager.stop();
        if (statsManager      != null) statsManager.save();
        getLogger().info("SuperHard が無効化されました。");
    }

    public static SuperHardPlugin getInstance() { return instance; }
    public SHConfig getSHConfig()               { return shConfig; }
    public ThreatManager getThreatManager()     { return threatManager; }
    public EliteManager getEliteManager()       { return eliteManager; }
    public SiegeManager getSiegeManager()       { return siegeManager; }
    public MobBehaviorManager getBehaviorManager()          { return behaviorManager; }
    public RaidBossManager getRaidBossManager()             { return raidBossManager; }
    public AuraSkillsIntegration getAuraSkillsIntegration() { return auraSkillsIntegration; }
    public CursedLocationManager getCursedLocationManager() { return cursedLocationManager; }
    public BountyManager getBountyManager()                 { return bountyManager; }
    public DiscordWebhook getDiscordWebhook()               { return discordWebhook; }
    public PlayerStatsManager getStatsManager()             { return statsManager; }
    public ScoreboardManager getScoreboardManager()         { return scoreboardManager; }
    public FieldBossManager getFieldBossManager()           { return fieldBossManager; }
}
