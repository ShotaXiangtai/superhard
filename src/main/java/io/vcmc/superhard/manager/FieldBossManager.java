package io.vcmc.superhard.manager;

import io.vcmc.superhard.SuperHardPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * フィールドボス（昼間に定期スポーンする中型ボス）を管理する。
 *
 * 仕様:
 *   - 昼間（ゲーム内時刻 0〜11999）に 45〜90分ごとにスポーン
 *   - 精鋭 Lv.3 (TENMA) 相当の強化モブ。4パターン用意
 *   - 討伐時: 鋼 ×6 ドロップ + 近隣の RAGE -50 + 統計カウント
 */
public class FieldBossManager {

    public static final NamespacedKey KEY_FIELD_BOSS = new NamespacedKey("superhard", "field_boss");

    // {エンティティタイプ, 名前}
    private static final Object[][] PATTERNS = {
        { Zombie.class,   "破壊の番人"   },
        { Zombie.class,   "鉄壁の侵略者" },
        { Skeleton.class, "影縫いの射手" },
        { Skeleton.class, "骸の狙撃手"   },
    };

    private static final long DAY_END_TICK = 12000L;

    private final SuperHardPlugin plugin;
    private final Set<UUID> activeBosses = new HashSet<>();
    private BukkitTask checkTask;
    private long nextSpawnMs = 0L;

    public FieldBossManager(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- ライフサイクル ----

    public void start() {
        scheduleNext();
        checkTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 600L, 600L);
        plugin.getLogger().info("フィールドボス: 次回スポーンまで " + getCountdownString());
    }

    public void stop() {
        if (checkTask != null) checkTask.cancel();
    }

    // ---- ティック (30秒ごと) ----

    private void tick() {
        if (!plugin.getSHConfig().isFieldBossEnabled()) return;
        if (System.currentTimeMillis() < nextSpawnMs) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            scheduleNext();
            return;
        }
        if (!isDaytime()) return; // 夜は昼まで待機
        spawnFieldBoss();
        scheduleNext();
    }

    private void scheduleNext() {
        long minMs = plugin.getSHConfig().getFieldBossMinIntervalMin() * 60_000L;
        long maxMs = plugin.getSHConfig().getFieldBossMaxIntervalMin() * 60_000L;
        nextSpawnMs = System.currentTimeMillis() + minMs + (long)(Math.random() * (maxMs - minMs));
    }

    // ---- スポーン ----

    @SuppressWarnings("unchecked")
    private void spawnFieldBoss() {
        // 最高 RAGE のプレイヤーを優先ターゲット
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers().stream()
            .filter(p -> !p.hasPermission("superhard.bypass"))
            .sorted(Comparator.comparingInt((Player p) -> plugin.getThreatManager().getThreat(p)).reversed())
            .toList());
        if (players.isEmpty()) return;

        Player target = players.get(0);
        Location spawnLoc = findSpawnNear(target.getLocation(), 30, 60);
        if (spawnLoc == null) return;

        Object[] pattern = PATTERNS[(int)(Math.random() * PATTERNS.length)];
        Class<? extends Mob> clazz = (Class<? extends Mob>) pattern[0];
        String name = (String) pattern[1];

        World world = spawnLoc.getWorld();
        Mob mob = world.spawn(spawnLoc, clazz, m -> configureFieldBoss(m, name));

        activeBosses.add(mob.getUniqueId());

        world.getNearbyPlayers(spawnLoc, 80).forEach(p ->
            p.sendMessage(Component.text("[SuperHard] ", NamedTextColor.DARK_PURPLE)
                .append(Component.text("【フィールドボス】 ", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true))
                .append(Component.text(name + " が出現した！", NamedTextColor.GRAY)))
        );
        world.playSound(spawnLoc, Sound.ENTITY_WITHER_AMBIENT, 1.5f, 0.5f);
    }

    private void configureFieldBoss(Mob mob, String name) {
        // PersistentData マーク
        mob.getPersistentDataContainer().set(KEY_FIELD_BOSS, PersistentDataType.BOOLEAN, true);

        // ステータス (TENMA 相当: HP×2.0, 速度×1.35, 攻撃×1.60)
        scaleAttr(mob, Attribute.MAX_HEALTH,      2.0);
        mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());
        scaleAttr(mob, Attribute.MOVEMENT_SPEED,  1.35);
        scaleAttr(mob, Attribute.ATTACK_DAMAGE,   1.60);

        // アーマー
        var armor = mob.getAttribute(Attribute.ARMOR);
        if (armor != null) armor.setBaseValue(Math.min(armor.getBaseValue() + 8.0, 30.0));

        // ダイヤモンド装備（ドロップなし）
        EntityEquipment eq = mob.getEquipment();
        if (eq != null) {
            eq.setHelmet(   new ItemStack(Material.DIAMOND_HELMET));
            eq.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
            eq.setLeggings( new ItemStack(Material.DIAMOND_LEGGINGS));
            eq.setBoots(    new ItemStack(Material.DIAMOND_BOOTS));
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
        }

        // ポーション
        mob.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, true, false));
        mob.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,      Integer.MAX_VALUE, 0, true, false));

        // ゾンビは昼間燃えない
        if (mob instanceof Zombie zombie) zombie.setShouldBurnInDay(false);

        // カスタム名
        mob.customName(Component.text("【Lv.4】 ", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.BOLD, true)
            .append(Component.text(name, NamedTextColor.WHITE)
                .decoration(TextDecoration.BOLD, false)));
        mob.setCustomNameVisible(true);
    }

    private void scaleAttr(Mob mob, Attribute attr, double mult) {
        var inst = mob.getAttribute(attr);
        if (inst == null) return;
        inst.setBaseValue(Math.min(inst.getBaseValue() * mult, 2048.0));
    }

    // ---- 討伐 ----

    public boolean isFieldBoss(Entity entity) {
        return entity.getPersistentDataContainer().has(KEY_FIELD_BOSS, PersistentDataType.BOOLEAN);
    }

    public void onFieldBossDeath(Mob mob, Player killer) {
        activeBosses.remove(mob.getUniqueId());

        Component nameComp = mob.customName();
        String rawName = nameComp != null ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(nameComp) : "フィールドボス";

        mob.getWorld().getNearbyPlayers(mob.getLocation(), 80).forEach(p ->
            p.sendMessage(Component.text("[SuperHard] ", NamedTextColor.GOLD)
                .append(Component.text("フィールドボス討伐！ ", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
                .append(Component.text(rawName, NamedTextColor.WHITE)))
        );

        // 鋼 ×6 ドロップ
        for (int i = 0; i < 6; i++) {
            mob.getWorld().dropItemNaturally(mob.getLocation(), plugin.getEliteManager().createTemperedShard());
        }

        // 討伐者に統計カウント
        if (killer != null) {
            plugin.getStatsManager().addBossKill(killer.getUniqueId());
        }

        // 近隣プレイヤーの RAGE -50（昼間出現のリスクに見合う報酬）
        mob.getWorld().getNearbyPlayers(mob.getLocation(), 60).forEach(p -> {
            if (p.hasPermission("superhard.bypass")) return;
            plugin.getThreatManager().reduceThreat(p, 50);
            p.sendMessage(Component.text("[SuperHard] フィールドボス討伐ボーナス — RAGE -50", NamedTextColor.GREEN));
        });
    }

    // ---- ユーティリティ ----

    private boolean isDaytime() {
        World overworld = Bukkit.getWorlds().stream()
            .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
            .findFirst().orElse(null);
        if (overworld == null) return false;
        return overworld.getTime() < DAY_END_TICK;
    }

    private Location findSpawnNear(Location center, double minR, double maxR) {
        World world = center.getWorld();
        if (world == null) return null;
        for (int i = 0; i < 8; i++) {
            double angle = Math.random() * 2 * Math.PI;
            double dist  = minR + Math.random() * (maxR - minR);
            int x = (int)(center.getX() + Math.cos(angle) * dist);
            int z = (int)(center.getZ() + Math.sin(angle) * dist);
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            if (world.getBlockAt(loc).isPassable()) return loc;
        }
        return null;
    }

    public String getCountdownString() {
        long diff = nextSpawnMs - System.currentTimeMillis();
        if (diff <= 0) return "間もなく（昼限定）";
        long h = diff / 3_600_000L;
        long m = (diff % 3_600_000L) / 60_000L;
        if (h > 0) return h + "時間" + m + "分後";
        if (m > 0) return m + "分後";
        return "間もなく";
    }
}
