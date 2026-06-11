package io.vcmc.superhard;

import io.vcmc.superhard.command.SuperHardCommand;
import io.vcmc.superhard.config.SHConfig;
import io.vcmc.superhard.listener.CombatListener;
import io.vcmc.superhard.listener.CraftingListener;
import io.vcmc.superhard.listener.MobSpawnListener;
import io.vcmc.superhard.listener.PlayerListener;
import io.vcmc.superhard.manager.EliteManager;
import io.vcmc.superhard.manager.MobBehaviorManager;
import io.vcmc.superhard.manager.SiegeManager;
import io.vcmc.superhard.manager.ThreatManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class SuperHardPlugin extends JavaPlugin {

    private static SuperHardPlugin instance;

    private SHConfig shConfig;
    private ThreatManager threatManager;
    private EliteManager eliteManager;
    private SiegeManager siegeManager;
    private MobBehaviorManager behaviorManager;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        shConfig = new SHConfig(this);

        threatManager   = new ThreatManager(this);
        eliteManager    = new EliteManager(this);
        siegeManager    = new SiegeManager(this);
        behaviorManager = new MobBehaviorManager(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new MobSpawnListener(this), this);
        pm.registerEvents(new CombatListener(this),   this);
        pm.registerEvents(new PlayerListener(this),   this);
        pm.registerEvents(new CraftingListener(this), this);

        var cmd = getCommand("superhard");
        if (cmd != null) {
            var handler = new SuperHardCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        threatManager.load();
        behaviorManager.start();
        siegeManager.start();

        getLogger().info("SuperHard が有効化されました！ Hard SMPへようこそ。");
        getLogger().info("バージョン: " + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        if (behaviorManager != null) behaviorManager.stop();
        if (threatManager   != null) { threatManager.save(); threatManager.stop(); }
        if (siegeManager    != null) siegeManager.stop();
        getLogger().info("SuperHard が無効化されました。");
    }

    public static SuperHardPlugin getInstance() { return instance; }
    public SHConfig getSHConfig()               { return shConfig; }
    public ThreatManager getThreatManager()     { return threatManager; }
    public EliteManager getEliteManager()       { return eliteManager; }
    public SiegeManager getSiegeManager()       { return siegeManager; }
    public MobBehaviorManager getBehaviorManager() { return behaviorManager; }
}
