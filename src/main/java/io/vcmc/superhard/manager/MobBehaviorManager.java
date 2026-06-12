package io.vcmc.superhard.manager;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.ThreatManager.ThreatLevel;
import io.vcmc.superhard.util.SHUtil;
import io.vcmc.superhard.util.SHUtil.BehaviorKey;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * モブAI強化の中枢。
 * 10tickごとにモブをスキャンし、モブタイプと脅威レベルに応じて行動を発動する。
 *
 * 真のクラフターモードにインスパイアされた独自実装:
 *   - 脅威スコアとの連動（固定難易度ではなく動的）
 *   - パック戦術: 同種モブが協調して行動
 *   - クールダウン管理で連続発動を防ぐ
 */
public class MobBehaviorManager {

    private final SuperHardPlugin plugin;
    private BukkitTask tickTask;

    // モブごとのクールダウン管理: UUID -> (BehaviorKey -> lastTimeMs)
    private final Map<UUID, EnumMap<BehaviorKey, Long>> cooldowns = new HashMap<>();

    // パックアルファ追跡: アルファモブのUUID
    private final Set<UUID> alphas = new HashSet<>();

    public MobBehaviorManager(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // 10tickごとにAIを更新
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    public void stop() {
        if (tickTask != null && !tickTask.isCancelled()) tickTask.cancel();
        cooldowns.clear();
        alphas.clear();
    }

    /** エンティティが死んだときにクールダウンデータを掃除 */
    public void onMobDeath(UUID id) {
        cooldowns.remove(id);
        alphas.remove(id);
    }

    // ---- メインティック ----

    private void tick() {
        if (!plugin.getSHConfig().isBehaviorEnabled()) return;

        int processed = 0;
        int maxPerTick = plugin.getSHConfig().getMaxMobsPerTick();

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getLivingEntities()) {
                if (processed >= maxPerTick) return;
                if (!(entity instanceof Mob mob)) continue;
                if (mob.isDead() || !mob.isValid()) continue;

                LivingEntity target = mob.getTarget();
                if (!(target instanceof Player player)) continue;
                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) continue;

                ThreatLevel threat = plugin.getThreatManager().getThreatLevel(player);
                dispatchBehavior(mob, player, threat);
                processed++;
            }
        }
    }

    private void dispatchBehavior(Mob mob, Player player, ThreatLevel threat) {
        switch (mob.getType()) {
            case ZOMBIE, HUSK, DROWNED -> tickZombie((Zombie) mob, player, threat);
            case SKELETON, STRAY       -> tickSkeleton((AbstractSkeleton) mob, player, threat);
            case CREEPER               -> tickCreeper((Creeper) mob, player, threat);
            case SPIDER, CAVE_SPIDER   -> tickSpider((Spider) mob, player, threat);
            case WITCH                 -> tickWitch((Witch) mob, player, threat);
            case ENDERMAN              -> tickEnderman((Enderman) mob, player, threat);
            default                    -> {} // 汎用パック戦術のみ
        }
        // 全モブ共通: パック警報
        if (plugin.getSHConfig().isPackTacticsEnabled()) {
            tickPackAlert(mob, player, threat);
        }
    }

    // ============================================================
    //  ゾンビ行動
    // ============================================================

    private void tickZombie(Zombie zombie, Player player, ThreatLevel threat) {
        double distSq = zombie.getLocation().distanceSquared(player.getLocation());

        // 跳躍攻撃: 8ブロック以内、AGITATED以上
        if (threat.ordinal() >= ThreatLevel.AGITATED.ordinal()
                && distSq < 64 && distSq > 4
                && isReady(zombie, BehaviorKey.LEAP, getCooldownMs(zombie, "zombie", "leap"))) {
            doLeap(zombie, player, 0.55, 0.75);
            setCooldown(zombie, BehaviorKey.LEAP);
        }

        // ブロック破壊: 正面がふさがっている場合
        if (threat.ordinal() >= ThreatLevel.AGITATED.ordinal()
                && plugin.getSHConfig().isBehaviorEnabled("zombie", "block-break")
                && isReady(zombie, BehaviorKey.BLOCK_BREAK, 2000L)) {
            tryBreakAheadBlock(zombie);
            setCooldown(zombie, BehaviorKey.BLOCK_BREAK);
        }

        // 足場構築: プレイヤーが高い位置にいる
        if (threat.ordinal() >= ThreatLevel.HOSTILE.ordinal()
                && plugin.getSHConfig().isBehaviorEnabled("zombie", "scaffold")
                && player.getLocation().getY() > zombie.getLocation().getY() + 2.5
                && isReady(zombie, BehaviorKey.SCAFFOLD, getCooldownMs(zombie, "zombie", "scaffold"))) {
            tryScaffold(zombie);
            setCooldown(zombie, BehaviorKey.SCAFFOLD);
        }

        // 仲間召喚は削除 — レイド外で湧き数を増やさないため
    }

    private void tryBreakAheadBlock(Zombie zombie) {
        if (!SHUtil.isBlockedAhead(zombie, 1.5)) return;
        Location fwd = zombie.getLocation().add(zombie.getLocation().getDirection().setY(0).normalize());
        Block block = fwd.getBlock();
        if (block.getType().isSolid() && !isUnbreakable(block.getType())) {
            block.breakNaturally();
        }
    }

    private void tryScaffold(Zombie zombie) {
        if (!(zombie.getTarget() instanceof Player player)) return;
        double heightDiff = player.getLocation().getY() - zombie.getLocation().getY();
        if (heightDiff < 1.5) return;

        // 足元が地面でなければスキップ
        if (!zombie.getLocation().clone().subtract(0, 0.1, 0).getBlock().getType().isSolid()) return;

        Vector dir     = SHUtil.horizontalDirection(zombie.getLocation(), player.getLocation());
        Location fwdLoc = zombie.getLocation().clone().add(dir.getX(), 0, dir.getZ());
        Block fwdBlock  = fwdLoc.getBlock();

        if (!fwdBlock.getType().isSolid()) {
            // 前方が空き: 前方ブロック位置に石材を1段置いてステップアップさせる
            // (設置した cobblestone の上面 = ゾンビの足元 +1 → ゾンビが1段上れる)
            Block above1 = fwdBlock.getRelative(0, 1, 0);
            Block above2 = fwdBlock.getRelative(0, 2, 0);
            if (fwdBlock.getType().isAir()
                    && !above1.getType().isSolid()
                    && !above2.getType().isSolid()) {
                fwdBlock.setType(Material.COBBLESTONE);
            }
        } else {
            // 前方が壁: ゾンビの足元位置に石材を積んで自身を1段押し上げる
            // これを繰り返すことで壁を超える高さまで達する
            Block feetBlock = zombie.getLocation().getBlock();
            Block headTop   = feetBlock.getRelative(0, 2, 0);
            if (feetBlock.getType().isAir() && !headTop.getType().isSolid()) {
                feetBlock.setType(Material.COBBLESTONE);
            }
        }
    }


    // ============================================================
    //  スケルトン行動
    // ============================================================

    private void tickSkeleton(AbstractSkeleton skeleton, Player player, ThreatLevel threat) {
        double dist = skeleton.getLocation().distance(player.getLocation());

        // 近距離武器切り替え: 4ブロック以内で斧に変える
        if (dist < 4 && plugin.getSHConfig().isBehaviorEnabled("skeleton", "weapon-switch")
                && isReady(skeleton, BehaviorKey.WEAPON_SWITCH, 3000L)) {
            switchToMelee(skeleton, threat);
            setCooldown(skeleton, BehaviorKey.WEAPON_SWITCH);
        }

        // ストレイフ（横移動）: AGITATED以上
        if (threat.ordinal() >= ThreatLevel.AGITATED.ordinal()
                && plugin.getSHConfig().isBehaviorEnabled("skeleton", "strafe")
                && isReady(skeleton, BehaviorKey.STRAFE, getCooldownMs(skeleton, "skeleton", "strafe"))) {
            doStrafe(skeleton, player);
            setCooldown(skeleton, BehaviorKey.STRAFE);
        }

        // バックステップ: HOSTILE以上で近距離被ダメ時（確率で発動）
        if (threat.ordinal() >= ThreatLevel.HOSTILE.ordinal()
                && dist < 3 && SHUtil.chance(0.15)
                && plugin.getSHConfig().isBehaviorEnabled("skeleton", "backstep")
                && isReady(skeleton, BehaviorKey.BACKSTEP, getCooldownMs(skeleton, "skeleton", "backstep"))) {
            doBackstep(skeleton, player);
            setCooldown(skeleton, BehaviorKey.BACKSTEP);
        }
    }

    private void switchToMelee(AbstractSkeleton skeleton, ThreatLevel threat) {
        EntityEquipment eq = skeleton.getEquipment();
        if (eq == null) return;
        ItemStack current = eq.getItemInMainHand();
        // 現在弓・クロスボウを持っているなら斧に切り替え
        if (current.getType() == Material.BOW || current.getType() == Material.CROSSBOW) {
            Material axeMat = threat.ordinal() >= ThreatLevel.HOSTILE.ordinal()
                ? Material.STONE_AXE : Material.WOODEN_AXE;
            eq.setItemInMainHand(new ItemStack(axeMat));
        }
    }

    private void doStrafe(AbstractSkeleton skeleton, Player player) {
        Vector toPlayer = SHUtil.horizontalDirection(skeleton.getLocation(), player.getLocation());
        // 垂直方向に回避
        Vector perp = new Vector(-toPlayer.getZ(), 0, toPlayer.getX());
        if (SHUtil.chance(0.5)) perp.multiply(-1);
        skeleton.setVelocity(perp.normalize().multiply(0.35));
    }

    // ============================================================
    //  クリーパー行動
    // ============================================================

    private void tickCreeper(Creeper creeper, Player player, ThreatLevel threat) {
        double dist = creeper.getLocation().distance(player.getLocation());

        // 透明化: プレイヤーを見つけたが8ブロック以上離れている場合にステルス接近
        if (plugin.getSHConfig().isBehaviorEnabled("creeper", "invisible-approach")
                && dist > 8 && !creeper.isInvisible()
                && isReady(creeper, BehaviorKey.INVISIBLE_SET, 5000L)) {
            creeper.setInvisible(true);
            // ヒューズ開始時に可視状態に戻す
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (creeper.isValid()) creeper.setInvisible(false);
            }, 20L * 3);
            setCooldown(creeper, BehaviorKey.INVISIBLE_SET);
        }

        // ヒューズ中は可視状態に戻す（getFuseTicks が max より小さければ起爆中）
        if (creeper.isInvisible() && creeper.getFuseTicks() < creeper.getMaxFuseTicks()) {
            creeper.setInvisible(false);
        }
    }

    // ---- 爆発後の炎: CombatListenerのEntityExplodeEventから呼ぶ ----
    public void onCreeperExplosion(Location loc, ThreatLevel nearestThreat) {
        if (!plugin.getSHConfig().isBehaviorEnabled("creeper", "fire-trail")) return;
        World world = loc.getWorld();
        int radius = 3;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (!SHUtil.chance(0.35)) continue;
                Location fireLoc = loc.clone().add(x, 0, z);
                Block ground = fireLoc.getBlock();
                if (ground.getType().isSolid()) {
                    Block above = ground.getRelative(0, 1, 0);
                    if (above.getType().isAir()) {
                        above.setType(Material.FIRE);
                    }
                }
            }
        }
    }

    // ============================================================
    //  スパイダー行動
    // ============================================================

    private void tickSpider(Spider spider, Player player, ThreatLevel threat) {
        double dist = spider.getLocation().distance(player.getLocation());

        // 蜘蛛の巣設置: 接近中
        if (dist < 8 && plugin.getSHConfig().isBehaviorEnabled("spider", "web")
                && isReady(spider, BehaviorKey.WEB_PLACE, getCooldownMs(spider, "spider", "web"))) {
            placeWeb(spider);
            setCooldown(spider, BehaviorKey.WEB_PLACE);
        }

        // スライム弾: AGITATED以上で中距離から
        if (threat.ordinal() >= ThreatLevel.AGITATED.ordinal()
                && dist > 3 && dist < 12
                && plugin.getSHConfig().isBehaviorEnabled("spider", "projectile")
                && isReady(spider, BehaviorKey.SLIME_PROJECTILE, getCooldownMs(spider, "spider", "projectile"))) {
            launchSlimeProjectile(spider, player);
            setCooldown(spider, BehaviorKey.SLIME_PROJECTILE);
        }
    }

    private void placeWeb(Spider spider) {
        Block block = spider.getLocation().getBlock();
        if (!block.getType().isAir()) return;
        Block below = block.getRelative(0, -1, 0);
        if (!below.getType().isSolid()) return;

        block.setType(Material.COBWEB);
        int lifetime = plugin.getSHConfig().getWebLifetimeTicks();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (block.getType() == Material.COBWEB) block.setType(Material.AIR);
        }, lifetime);
    }

    private void launchSlimeProjectile(Spider spider, Player player) {
        // スライムは飛び道具として使えないのでSnowballで代用し、エフェクトで表現
        Snowball projectile = spider.launchProjectile(Snowball.class);
        projectile.setVelocity(SHUtil.direction3D(spider.getEyeLocation(), player.getEyeLocation()).multiply(1.2));
        // 当たった時の効果はCombatListenerのProjectileHitEventで処理
        projectile.getPersistentDataContainer().set(
            new NamespacedKey("superhard", "slime_ball"), PersistentDataType.BOOLEAN, true
        );
    }

    // ============================================================
    //  ウィッチ行動
    // ============================================================

    private void tickWitch(Witch witch, Player player, ThreatLevel threat) {
        double dist = witch.getLocation().distance(player.getLocation());

        // バフオーラ: 近くの敵対モブに速度・耐性付与
        if (plugin.getSHConfig().isBehaviorEnabled("witch", "buff-aura")
                && isReady(witch, BehaviorKey.BUFF_AURA, getCooldownMs(witch, "witch", "buff"))) {
            buffNearbyMobs(witch, threat);
            setCooldown(witch, BehaviorKey.BUFF_AURA);
        }

        // テレポート: 近づきすぎたら後退、離れすぎたら前進
        if (plugin.getSHConfig().isBehaviorEnabled("witch", "teleport")
                && isReady(witch, BehaviorKey.WITCH_TELEPORT, getCooldownMs(witch, "witch", "teleport"))) {
            if (dist < 4) {
                teleportAwayFrom(witch, player, 8);
                setCooldown(witch, BehaviorKey.WITCH_TELEPORT);
            } else if (dist > 20) {
                teleportToward(witch, player, 12);
                setCooldown(witch, BehaviorKey.WITCH_TELEPORT);
            }
        }
    }

    private void buffNearbyMobs(Witch witch, ThreatLevel threat) {
        int amplifier = threat.ordinal() >= ThreatLevel.HOSTILE.ordinal() ? 1 : 0;
        witch.getWorld().getNearbyEntities(witch.getLocation(), 15, 8, 15).stream()
            .filter(e -> e instanceof Monster && !(e instanceof Witch) && e instanceof LivingEntity)
            .forEach(e -> {
                LivingEntity mob = (LivingEntity) e;
                mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, amplifier, true, false));
                if (threat.ordinal() >= ThreatLevel.HOSTILE.ordinal()) {
                    mob.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 0, true, false));
                }
            });
    }

    private void teleportAwayFrom(Witch witch, Player player, double dist) {
        Vector dir = SHUtil.horizontalDirection(player.getLocation(), witch.getLocation());
        Location target = witch.getLocation().add(dir.multiply(dist));
        target.setY(target.getWorld().getHighestBlockYAt(target) + 1);
        witch.teleport(target);
    }

    private void teleportToward(Witch witch, Player player, double dist) {
        Vector dir = SHUtil.horizontalDirection(witch.getLocation(), player.getLocation());
        Location target = witch.getLocation().add(dir.multiply(dist));
        target.setY(target.getWorld().getHighestBlockYAt(target) + 1);
        witch.teleport(target);
    }

    // ============================================================
    //  エンダーマン行動
    // ============================================================

    private void tickEnderman(Enderman enderman, Player player, ThreatLevel threat) {
        // 自動アグロ: 見つめなくても敵対化
        if (plugin.getSHConfig().isBehaviorEnabled("enderman", "auto-aggro")) {
            int range = plugin.getSHConfig().getAutoAggroRange();
            if (enderman.getTarget() == null) {
                SHUtil.nearestPlayer(enderman.getLocation(), range).ifPresent(enderman::setTarget);
            }
        }

        // 近くの水を氷にする
        if (threat.ordinal() >= ThreatLevel.HOSTILE.ordinal()
                && plugin.getSHConfig().isBehaviorEnabled("enderman", "freeze-water")
                && isReady(enderman, BehaviorKey.FREEZE_WATER, 3000L)) {
            freezeNearbyWater(enderman);
            setCooldown(enderman, BehaviorKey.FREEZE_WATER);
        }
    }

    // ---- ダメージ時に呼ばれる（CombatListenerから） ----
    public void onEndermanDamaged(Enderman enderman, ThreatLevel threat) {
        if (!plugin.getSHConfig().isBehaviorEnabled("enderman", "minion-summon")) return;
        if (!isReady(enderman, BehaviorKey.ENDERMAN_MINION,
                getCooldownMs(enderman, "enderman", "minion-summon"))) return;

        int count = threat.ordinal() >= ThreatLevel.INFURIATED.ordinal() ? 3 : 2;
        LivingEntity target = enderman.getTarget();
        for (int i = 0; i < count; i++) {
            Location spawnLoc = SHUtil.safeSpawnNear(enderman.getLocation(), 1, 3);
            Vex vex = enderman.getWorld().spawn(spawnLoc, Vex.class);
            if (target != null) vex.setTarget(target);
            // 30秒後に消滅
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (vex.isValid()) vex.remove();
            }, 20L * 30);
        }
        setCooldown(enderman, BehaviorKey.ENDERMAN_MINION);
    }

    private void freezeNearbyWater(Enderman enderman) {
        Location center = enderman.getLocation();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                Block block = center.clone().add(x, 0, z).getBlock();
                if (block.getType() == Material.WATER) {
                    block.setType(Material.ICE);
                }
            }
        }
    }

    // ============================================================
    //  パック戦術（全モブ共通）
    // ============================================================

    private void tickPackAlert(Mob mob, Player player, ThreatLevel threat) {
        if (!isReady(mob, BehaviorKey.PACK_ALERT,
                plugin.getSHConfig().getBehaviorCooldownMs("pack-tactics", "alert"))) return;

        double alertRange = plugin.getSHConfig().getPackAlertRange();
        // 近くの同種モブに同じプレイヤーをターゲットさせる
        mob.getWorld().getNearbyEntities(mob.getLocation(), alertRange, alertRange, alertRange).stream()
            .filter(e -> e.getType() == mob.getType() && e != mob && e instanceof Mob m && m.getTarget() == null)
            .forEach(e -> ((Mob) e).setTarget(player));

        setCooldown(mob, BehaviorKey.PACK_ALERT);
    }

    // ============================================================
    //  共通ユーティリティ
    // ============================================================

    private void doLeap(Mob mob, LivingEntity target, double horizontalPower, double verticalPower) {
        Vector dir = SHUtil.horizontalDirection(mob.getLocation(), target.getLocation());
        dir.setY(verticalPower);
        mob.setVelocity(dir.multiply(horizontalPower));
    }

    private void doBackstep(Mob mob, LivingEntity attacker) {
        Vector dir = SHUtil.horizontalDirection(attacker.getLocation(), mob.getLocation());
        dir.setY(0.4);
        mob.setVelocity(dir.multiply(0.65));
    }

    /** クリーパーの爆発を除く軽量クールダウン呼び出し（ダメージイベントから） */
    public void doBackstepPublic(Mob mob, LivingEntity attacker) {
        doBackstep(mob, attacker);
    }

    private boolean isUnbreakable(Material mat) {
        return mat == Material.BEDROCK || mat == Material.BARRIER
            || mat == Material.OBSIDIAN || mat == Material.CRYING_OBSIDIAN
            || mat == Material.END_PORTAL_FRAME || mat == Material.REINFORCED_DEEPSLATE;
    }

    // ---- クールダウン管理 ----

    private boolean isReady(Mob mob, BehaviorKey key, long cooldownMs) {
        long last = cooldowns
            .computeIfAbsent(mob.getUniqueId(), k -> new EnumMap<>(BehaviorKey.class))
            .getOrDefault(key, 0L);
        return System.currentTimeMillis() - last >= cooldownMs;
    }

    private void setCooldown(Mob mob, BehaviorKey key) {
        cooldowns.computeIfAbsent(mob.getUniqueId(), k -> new EnumMap<>(BehaviorKey.class))
            .put(key, System.currentTimeMillis());
    }

    private long getCooldownMs(Mob mob, String mobType, String behavior) {
        return plugin.getSHConfig().getBehaviorCooldownMs(mobType, behavior);
    }
}
