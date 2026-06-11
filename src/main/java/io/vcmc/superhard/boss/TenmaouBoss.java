package io.vcmc.superhard.boss;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.EliteManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 天魔王 — SuperHard レイドボス。
 *
 * 複数人で挑まないと倒せない仕組み:
 *   Phase 3 で 半径40ブロック内のプレイヤーが1人以下なら毎秒HP回復
 *
 * Phase 1 (HP 100% → 65%): 稲妻・シュラ召喚・突進
 * Phase 2 (HP 65% → 35%):  冥府の波動・火柱・更なる召喚
 * Phase 3 (HP 35% → 0%):   カオス化・ソロ制裁・怒り常時付与
 */
public class TenmaouBoss {

    public enum Phase { PHASE1, PHASE2, PHASE3 }

    private static final String BOSS_DISPLAY = "§5§l天魔王";
    private static final double MAX_HP = 600.0;
    private static final double SCALE  = 2.2;

    private final SuperHardPlugin plugin;
    private final Zombie entity;
    private final BossBar bossBar;
    private Phase phase = Phase.PHASE1;
    private BukkitTask tickTask;

    // 参加プレイヤー（ダメージを与えたことがある）
    private final Set<UUID> participants = new HashSet<>();
    private boolean defeated = false;

    // 攻撃クールダウン (秒カウンタ)
    private int tickCount = 0;
    private int cdLightning = 0;
    private int cdSummon    = 0;
    private int cdWave      = 0;
    private int cdFirePillar= 0;
    private int cdChaos     = 0;

    public TenmaouBoss(SuperHardPlugin plugin, Location spawnLoc) {
        this.plugin = plugin;

        // ---- エンティティ生成 ----
        entity = spawnLoc.getWorld().spawn(spawnLoc, Zombie.class, z -> {
            // HP・攻撃
            var hp = z.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(MAX_HP);
            var dmg = z.getAttribute(Attribute.ATTACK_DAMAGE);
            if (dmg != null) dmg.setBaseValue(14.0);
            var spd = z.getAttribute(Attribute.MOVEMENT_SPEED);
            if (spd != null) spd.setBaseValue(0.30);
            var arm = z.getAttribute(Attribute.ARMOR);
            if (arm != null) arm.setBaseValue(12.0);

            // スケール (2倍強の大きさ)
            var scale = z.getAttribute(Attribute.SCALE);
            if (scale != null) scale.setBaseValue(SCALE);

            // 装備
            EntityEquipment eq = z.getEquipment();
            if (eq != null) {
                eq.setHelmet(enchanted(Material.NETHERITE_HELMET));
                eq.setChestplate(enchanted(Material.NETHERITE_CHESTPLATE));
                eq.setLeggings(enchanted(Material.NETHERITE_LEGGINGS));
                eq.setBoots(enchanted(Material.NETHERITE_BOOTS));
                eq.setItemInMainHand(enchanted(Material.NETHERITE_SWORD));
                eq.setHelmetDropChance(0f);
                eq.setChestplateDropChance(0f);
                eq.setLeggingsDropChance(0f);
                eq.setBootsDropChance(0f);
                eq.setItemInMainHandDropChance(0f);
            }

            // ポーション常時付与
            z.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, true, false));

            // カスタム名
            z.customName(Component.text("天魔王", NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.BOLD, true));
            z.setCustomNameVisible(true);
            z.setShouldBurnInDay(false);
            z.setHealth(MAX_HP);
        });

        // ---- BossBar ----
        bossBar = Bukkit.createBossBar("§5§l天魔王  §7Phase I", BarColor.PURPLE, BarStyle.SEGMENTED_10);
        bossBar.setProgress(1.0);

        // ---- スポーン演出 ----
        spawnEffects(spawnLoc);

        // ---- AIタスク開始（1秒ごと） ----
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    // ============================================================
    //  メインループ（毎秒）
    // ============================================================

    private void tick() {
        if (!entity.isValid() || entity.isDead()) {
            finish();
            return;
        }

        tickCount++;
        double hp    = entity.getHealth();
        double maxHp = entity.getAttribute(Attribute.MAX_HEALTH).getValue();
        double ratio = hp / maxHp;

        // BossBar 更新
        updateBossBar(ratio);
        refreshBossBarPlayers();

        // フェーズ遷移
        if (phase == Phase.PHASE1 && ratio <= 0.65) enterPhase2();
        if (phase == Phase.PHASE2 && ratio <= 0.35) enterPhase3();

        // ---- 攻撃クールダウン減算 ----
        if (cdLightning  > 0) cdLightning--;
        if (cdSummon     > 0) cdSummon--;
        if (cdWave       > 0) cdWave--;
        if (cdFirePillar > 0) cdFirePillar--;
        if (cdChaos      > 0) cdChaos--;

        // ---- 攻撃発動 ----
        List<Player> targets = getNearbyPlayers(50);
        if (targets.isEmpty()) return;

        // 共通攻撃
        if (cdLightning <= 0) { attackLightning(targets); cdLightning = switch(phase){case PHASE1->12;case PHASE2->9;default->7;}; }
        if (cdSummon    <= 0) { attackSummon();            cdSummon    = switch(phase){case PHASE1->25;case PHASE2->20;default->15;}; }

        if (phase == Phase.PHASE2 || phase == Phase.PHASE3) {
            if (cdWave      <= 0) { attackDarkWave(targets); cdWave      = 13; }
            if (cdFirePillar<= 0) { attackFirePillar(targets); cdFirePillar = 18; }
        }
        if (phase == Phase.PHASE3) {
            if (cdChaos <= 0) { attackChaosEruption(targets); cdChaos = 8; }
            checkSoloPunishment(targets);
        }
    }

    // ============================================================
    //  フェーズ遷移
    // ============================================================

    private void enterPhase2() {
        phase = Phase.PHASE2;
        broadcastAll(Component.text("天魔王が覚醒した——第二相", NamedTextColor.RED).decoration(TextDecoration.BOLD, true));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 0, true, false));
        playPhaseTransitionSounds();
        spawnLightningRing(entity.getLocation(), 8, 12);
    }

    private void enterPhase3() {
        phase = Phase.PHASE3;
        broadcastAll(Component.text("天魔王、絶望の終相——", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true));
        broadcastAll(Component.text("ひとりでは倒せない。仲間を集めろ。", NamedTextColor.GRAY));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,    Integer.MAX_VALUE, 1, true, false));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 1, true, false));
        playPhaseTransitionSounds();
        spawnLightningRing(entity.getLocation(), 12, 20);
    }

    // ============================================================
    //  攻撃
    // ============================================================

    /** 天罰の雷撃: 近くのプレイヤー全員に雷が落ちる（ダメージは手動付与） */
    private void attackLightning(List<Player> targets) {
        for (Player p : targets) {
            Location loc = p.getLocation();
            entity.getWorld().strikeLightningEffect(loc);
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                entity.getWorld().strikeLightningEffect(loc.clone().add(SHUtil.rand(-2, 2), 0, SHUtil.rand(-2, 2))), 5L);
            p.damage(7.0, entity);
        }
        playAttackSound(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 0.8f);
    }

    /** 眷属召喚: シュラ精鋭ゾンビを呼ぶ */
    private void attackSummon() {
        int count = switch (phase) { case PHASE1 -> 3; case PHASE2 -> 4; default -> 5; };
        for (int i = 0; i < count; i++) {
            Location spawnLoc = randomNearbyGround(entity.getLocation(), 6, 15);
            if (spawnLoc == null) continue;
            Zombie summon = entity.getWorld().spawn(spawnLoc, Zombie.class);
            Player target = getNearestPlayer();
            if (target != null) summon.setTarget(target);
            plugin.getEliteManager().applyElite(summon, EliteManager.EliteType.SHURA);
        }
        playAttackSound(Sound.ENTITY_WARDEN_AMBIENT, 1.2f, 0.5f);
    }

    /** 冥府の波動: 全参加者にウィザー＋暗闇 */
    private void attackDarkWave(List<Player> targets) {
        for (Player p : targets) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,   60, 1, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0, false, true));
        }
        // 波動パーティクル
        spawnParticleRing(entity.getLocation().add(0, 1, 0), 10, Particle.SOUL_FIRE_FLAME, 40);
        playAttackSound(Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 0.4f);
    }

    /** 業火の火柱: プレイヤーの足元に遅延ダメージ */
    private void attackFirePillar(List<Player> targets) {
        for (Player p : targets) {
            Location target = p.getLocation();
            // 2秒後に着弾
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                // パーティクル柱
                for (int y = 0; y < 6; y++) {
                    entity.getWorld().spawnParticle(Particle.FLAME,
                        target.clone().add(0, y, 0), 15, 0.3, 0.05, 0.3, 0.02);
                }
                // 着弾点で爆発音
                entity.getWorld().playSound(target, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f);
                // 範囲内なら強制ダメージ
                if (p.getLocation().distance(target) < 2.5) {
                    p.damage(10.0, entity);
                    p.setFireTicks(80);
                }
            }, 40L);
            // 予告線
            entity.getWorld().spawnParticle(Particle.CRIT, target.clone().add(0, 2, 0), 20, 0.3, 1, 0.3, 0);
        }
        playAttackSound(Sound.BLOCK_FIRE_AMBIENT, 1.5f, 0.6f);
    }

    /** カオスエラプション: ランダムファイヤーボール */
    private void attackChaosEruption(List<Player> targets) {
        for (Player p : targets) {
            Fireball fb = entity.getWorld().spawn(entity.getEyeLocation(), Fireball.class);
            Vector dir  = p.getEyeLocation().subtract(entity.getEyeLocation()).toVector().normalize()
                .add(new Vector(Math.random() * 0.3 - 0.15, 0, Math.random() * 0.3 - 0.15));
            fb.setDirection(dir);
            fb.setYield(1.5f);
            fb.setIsIncendiary(true);
        }
        playAttackSound(Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.5f);
    }

    /** フェーズ3: ソロ制裁 — プレイヤー1人以下なら毎秒HP回復 */
    private void checkSoloPunishment(List<Player> nearbyPlayers) {
        if (nearbyPlayers.size() <= 1) {
            double maxHp = entity.getAttribute(Attribute.MAX_HEALTH).getValue();
            double healAmount = maxHp * 0.02; // 2%
            entity.setHealth(Math.min(entity.getHealth() + healAmount, maxHp));
            for (Player p : nearbyPlayers) {
                p.sendActionBar(Component.text("⚠ 孤独への裁き ——天魔王が回復している！ ⚠", NamedTextColor.DARK_RED));
            }
        }
    }

    // ============================================================
    //  倒した時の処理
    // ============================================================

    public void onDeath() {
        if (defeated) return;
        defeated = true;

        // 壮絶な死亡演出
        Location loc = entity.getLocation();
        for (int i = 0; i < 8; i++) {
            final int delay = i * 5;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                loc.getWorld().strikeLightningEffect(loc.clone().add(
                    Math.random() * 6 - 3, 0, Math.random() * 6 - 3));
                loc.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, loc, 3, 2, 1, 2, 0);
            }, delay);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
            loc.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_DEATH, 2.0f, 0.8f), 5L);

        // 参加者への報酬
        broadcastAll(Component.text("天魔王を討伐した！", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
        rewardParticipants();

        finish();
    }

    private void rewardParticipants() {
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;

            // ソウルシャード x12
            for (int i = 0; i < 12; i++) {
                p.getWorld().dropItemNaturally(p.getLocation(), plugin.getEliteManager().createTemperedShard());
            }

            // AuraSkills XP
            var aura = plugin.getAuraSkillsIntegration();
            if (aura != null) aura.grantRaidBossKillXp(p);

            p.sendMessage(Component.text("[SuperHard] 天魔王討伐報酬: ソウルシャード x12", NamedTextColor.GOLD));
        }
    }

    // ============================================================
    //  ユーティリティ
    // ============================================================

    private void spawnEffects(Location loc) {
        World world = loc.getWorld();

        // 稲妻の輪
        spawnLightningRing(loc, 6, 12);

        // サウンド演出 (段階的に)
        world.playSound(loc, Sound.ENTITY_WITHER_SPAWN,          2.0f, 0.5f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 0.6f);
            world.strikeLightningEffect(loc);
        }, 20L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM,  2.0f, 0.4f);
            for (int i = 0; i < 5; i++) {
                world.strikeLightningEffect(loc.clone().add(Math.random()*8-4, 0, Math.random()*8-4));
            }
        }, 40L);

        // 広告メッセージ
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
            Bukkit.getOnlinePlayers().forEach(p -> {
                p.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text("天魔王", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.BOLD, true),
                    Component.text("全員で挑め。ひとりでは死ぬだけだ。", NamedTextColor.GRAY),
                    net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(500),
                        java.time.Duration.ofMillis(4000),
                        java.time.Duration.ofMillis(1000)
                    )
                ));
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.5f, 0.5f);
            }), 45L);
    }

    private void spawnLightningRing(Location center, double radius, int count) {
        World world = center.getWorld();
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI / count) * i;
            Location target = center.clone().add(
                radius * Math.cos(angle), 0, radius * Math.sin(angle));
            world.strikeLightningEffect(target);
        }
    }

    private void spawnParticleRing(Location center, double radius, Particle particle, int density) {
        World world = center.getWorld();
        for (int i = 0; i < density; i++) {
            double angle = (2 * Math.PI / density) * i;
            world.spawnParticle(particle,
                center.clone().add(radius * Math.cos(angle), 0, radius * Math.sin(angle)),
                3, 0, 0, 0, 0);
        }
    }

    private void playPhaseTransitionSounds() {
        Location loc = entity.getLocation();
        loc.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL,    1.5f, 0.6f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
            loc.getWorld().playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 0.7f), 10L);
        plugin.getServer().getScheduler().runTaskLater(plugin, () ->
            loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM,      2.0f, 0.5f), 20L);
    }

    private void playAttackSound(Sound sound, float volume, float pitch) {
        entity.getWorld().playSound(entity.getLocation(), sound, volume, pitch);
    }

    private List<Player> getNearbyPlayers(double radius) {
        return entity.getWorld().getNearbyPlayers(entity.getLocation(), radius)
            .stream()
            .filter(p -> !p.getGameMode().equals(GameMode.SPECTATOR)
                      && !p.getGameMode().equals(GameMode.CREATIVE))
            .collect(java.util.stream.Collectors.toList());
    }

    private Player getNearestPlayer() {
        return getNearbyPlayers(60).stream()
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(entity.getLocation())))
            .orElse(null);
    }

    private Location randomNearbyGround(Location center, double min, double max) {
        World world = center.getWorld();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = Math.random() * Math.PI * 2;
            double dist  = min + Math.random() * (max - min);
            int x = (int)(center.getX() + Math.cos(angle) * dist);
            int z = (int)(center.getZ() + Math.sin(angle) * dist);
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location loc = new Location(world, x, y, z);
            if (loc.getBlock().getType().isAir()) return loc;
        }
        return null;
    }

    private void updateBossBar(double ratio) {
        bossBar.setProgress(Math.max(0, Math.min(1, ratio)));
        String phaseLabel = switch (phase) {
            case PHASE1 -> "§7Phase I";
            case PHASE2 -> "§cPhase II — 覚醒";
            case PHASE3 -> "§4Phase III — 絶望";
        };
        bossBar.setTitle("§5§l天魔王  " + phaseLabel);
        bossBar.setColor(switch (phase) {
            case PHASE1 -> BarColor.PURPLE;
            case PHASE2 -> BarColor.RED;
            case PHASE3 -> BarColor.RED;
        });
    }

    private void refreshBossBarPlayers() {
        Set<Player> near = new HashSet<>(getNearbyPlayers(80));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (near.contains(p) && !bossBar.getPlayers().contains(p)) bossBar.addPlayer(p);
            else if (!near.contains(p)) bossBar.removePlayer(p);
        }
    }

    private void broadcastAll(Component msg) {
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
    }

    private ItemStack enchanted(Material mat) {
        ItemStack item = new ItemStack(mat);
        item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 5);
        return item;
    }

    private void finish() {
        if (tickTask != null && !tickTask.isCancelled()) tickTask.cancel();
        bossBar.removeAll();
        plugin.getRaidBossManager().onBossFinished(entity.getUniqueId());
    }

    // ---- public API ----

    public Zombie getEntity()          { return entity; }
    public Phase  getPhase()           { return phase; }
    public boolean isDefeated()        { return defeated; }
    public void addParticipant(UUID id){ participants.add(id); }

    /** ヘルパーのランダム整数（static でアクセスできるようにパッケージ内公開） */
    static double SHUtil_rand(double min, double max) { return min + Math.random() * (max - min); }

    private static class SHUtil {
        static double rand(double min, double max) { return min + Math.random() * (max - min); }
    }
}
