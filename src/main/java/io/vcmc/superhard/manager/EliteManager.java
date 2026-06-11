package io.vcmc.superhard.manager;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.ThreatManager.ThreatLevel;
import io.vcmc.superhard.util.SHUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * スポーン時に一定確率でモブを精鋭化する。
 *
 * 精鋭タイプ:
 *   修羅 (SHURA)  - 基礎強化。HP+25%, 速度+10%
 *   覇者 (HASHA)  - 中強化。HP+60%, 速度+20%, ダメージ+35%、装備付き
 *   天魔 (TENMA)  - 最高強化。HP+100%, 速度+35%, ダメージ+60%、完全装備
 */
public class EliteManager {

    public enum EliteType {
        SHURA ("《シュラ》",  NamedTextColor.YELLOW,       "shura"),
        HASHA ("《ハーシャ》", NamedTextColor.GOLD,         "hasha"),
        TENMA ("《テンマ》",  NamedTextColor.DARK_PURPLE,  "tenma");

        public final String prefix;
        public final NamedTextColor color;
        public final String configKey;

        EliteType(String prefix, NamedTextColor color, String configKey) {
            this.prefix = prefix;
            this.color = color;
            this.configKey = configKey;
        }
    }

    public static final NamespacedKey KEY_ELITE_TYPE  = new NamespacedKey("superhard", "elite_type");
    public static final NamespacedKey KEY_TEMPERED_SHARD = new NamespacedKey("superhard", "tempered_shard");
    public static final NamespacedKey KEY_TEMPERED    = new NamespacedKey("superhard", "tempered");
    public static final NamespacedKey KEY_ALPHA       = new NamespacedKey("superhard", "pack_alpha");

    private final SuperHardPlugin plugin;

    public EliteManager(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- 精鋭化ロール ----

    /**
     * モブを精鋭化するかロールし、精鋭にする場合は属性・名前を設定する。
     * @return 精鋭化したなら true
     */
    public boolean tryElite(Mob mob, ThreatLevel threat) {
        if (!plugin.getSHConfig().isElitesEnabled()) return false;
        if (isElite(mob)) return false; // 既に精鋭

        double roll = Math.random();
        double base = plugin.getSHConfig().getEliteBaseChance(threat);

        if (roll >= base * 3) return false; // 範囲外

        EliteType type;
        if (roll < base * 0.15) {
            type = EliteType.TENMA; // 全体の15%枠内
        } else if (roll < base * 0.50) {
            type = EliteType.HASHA;   // 次の35%枠
        } else if (roll < base) {
            type = EliteType.SHURA;    // 残り
        } else {
            return false;
        }

        applyElite(mob, type);
        return true;
    }

    public void applyElite(Mob mob, EliteType type) {
        // PDCに精鋭タイプを記録
        mob.getPersistentDataContainer().set(KEY_ELITE_TYPE, PersistentDataType.STRING, type.name());

        // HP倍率適用
        double hpMult = plugin.getSHConfig().getEliteHpMultiplier(type.configKey);
        scaleAttribute(mob, Attribute.MAX_HEALTH, hpMult);
        mob.setHealth(mob.getAttribute(Attribute.MAX_HEALTH).getValue());

        // 移動速度
        double spdMult = switch (type) {
            case SHURA    -> 1.10;
            case HASHA   -> 1.20;
            case TENMA -> 1.35;
        };
        scaleAttribute(mob, Attribute.MOVEMENT_SPEED, spdMult);

        // ダメージ
        double dmgMult = plugin.getSHConfig().getEliteDamageMultiplier(type.configKey);
        scaleAttribute(mob, Attribute.ATTACK_DAMAGE, dmgMult);

        // カスタム名
        String baseName = getMobJpName(mob);
        mob.customName(Component.text(type.prefix + " ", type.color)
            .decoration(TextDecoration.BOLD, true)
            .append(Component.text(baseName, NamedTextColor.WHITE)
                .decoration(TextDecoration.BOLD, false)));
        mob.setCustomNameVisible(true);

        // 装備（HASHA以上）
        if (type == EliteType.HASHA || type == EliteType.TENMA) {
            applyEliteEquipment(mob, type);
        }

        // TEENMAはポーション効果付与
        if (type == EliteType.TENMA) {
            mob.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, true, false));
            mob.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0, true, false));
        }

        // 出現サウンド演出
        playEliteSpawnSound(mob, type);

        // 付近プレイヤーへの通知
        mob.getWorld().getNearbyPlayers(mob.getLocation(), 40).forEach(p ->
            p.sendMessage(Component.text("[SuperHard] ", NamedTextColor.DARK_RED)
                .append(Component.text(type.prefix + " ", type.color)
                    .decoration(TextDecoration.BOLD, true))
                .append(Component.text(baseName + " が出現した", NamedTextColor.GRAY)))
        );
    }

    private void applyEliteEquipment(Mob mob, EliteType type) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;

        Material helmetMat   = type == EliteType.TENMA ? Material.DIAMOND_HELMET    : Material.IRON_HELMET;
        Material chestMat    = type == EliteType.TENMA ? Material.DIAMOND_CHESTPLATE : Material.IRON_CHESTPLATE;
        Material leggingsMat = type == EliteType.TENMA ? Material.DIAMOND_LEGGINGS  : Material.IRON_LEGGINGS;
        Material bootsMat    = type == EliteType.TENMA ? Material.DIAMOND_BOOTS     : Material.IRON_BOOTS;

        eq.setHelmet(new ItemStack(helmetMat));
        eq.setChestplate(new ItemStack(chestMat));
        eq.setLeggings(new ItemStack(leggingsMat));
        eq.setBoots(new ItemStack(bootsMat));

        // ドロップしないように設定
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
    }

    // ---- 鍛えの欠片 ----

    /** 精鋭モブの死亡時に欠片をドロップするか判定する */
    public void handleEliteDrop(Mob mob) {
        if (!isElite(mob)) return;
        if (!SHUtil.chance(plugin.getSHConfig().getShardDropChance())) return;
        mob.getWorld().dropItemNaturally(mob.getLocation(), createTemperedShard());
    }

    public boolean isTemperedShard(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
            .has(KEY_TEMPERED_SHARD, PersistentDataType.BOOLEAN);
    }

    // ---- ユーティリティ ----

    public boolean isElite(Mob mob) {
        return mob.getPersistentDataContainer().has(KEY_ELITE_TYPE, PersistentDataType.STRING);
    }

    public EliteType getEliteType(Mob mob) {
        String raw = mob.getPersistentDataContainer().get(KEY_ELITE_TYPE, PersistentDataType.STRING);
        if (raw == null) return null;
        try { return EliteType.valueOf(raw); }
        catch (IllegalArgumentException e) { return null; }
    }

    private void scaleAttribute(Mob mob, Attribute attr, double multiplier) {
        AttributeInstance inst = mob.getAttribute(attr);
        if (inst == null) return;
        // 上限2048でキャップ（異常値防止）
        double newVal = Math.min(inst.getBaseValue() * multiplier, 2048.0);
        inst.setBaseValue(newVal);
    }

    private String getMobJpName(Mob mob) {
        return switch (mob.getType()) {
            case ZOMBIE            -> "ゾンビ";
            case SKELETON          -> "スケルトン";
            case CREEPER           -> "クリーパー";
            case SPIDER            -> "スパイダー";
            case WITCH             -> "ウィッチ";
            case ENDERMAN          -> "エンダーマン";
            case PILLAGER          -> "ピリジャー";
            case VINDICATOR        -> "ヴィンディケーター";
            case EVOKER            -> "エヴォーカー";
            case BLAZE             -> "ブレイズ";
            case WITHER_SKELETON   -> "ウィザースケルトン";
            case ZOMBIFIED_PIGLIN  -> "ゾンビピグリン";
            case PIGLIN_BRUTE      -> "ピグリンブルート";
            case PHANTOM           -> "ファントム";
            case GUARDIAN          -> "ガーディアン";
            default                -> mob.getType().name().replace("_", " ");
        };
    }

    private void playEliteSpawnSound(Mob mob, EliteType type) {
        var world = mob.getWorld();
        var loc   = mob.getLocation();
        switch (type) {
            case SHURA -> {
                world.playSound(loc, Sound.ENTITY_WARDEN_AMBIENT,    1.5f, 0.6f);
                world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.4f);
            }
            case HASHA -> {
                world.playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 1.2f, 0.5f);
                world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM,      0.8f, 0.7f);
            }
            case TENMA -> {
                world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT,    1.5f, 0.4f);
                world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 0.5f);
                plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 0.8f), 10L);
            }
        }
    }

    /** ソウルシャード（旧名: 鍛えの欠片）の生成 */
    public ItemStack createTemperedShard() {
        ItemStack shard = new ItemStack(Material.AMETHYST_SHARD, 1);
        var meta = shard.getItemMeta();
        meta.displayName(Component.text("ソウルシャード", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, false));
        meta.lore(java.util.List.of(
            Component.text("精鋭モブの魂が凝縮されたかけら", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("アンビルで装備と組み合わせると", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("TEMPERED 状態に強化できる", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(KEY_TEMPERED_SHARD, PersistentDataType.BOOLEAN, true);
        shard.setItemMeta(meta);
        return shard;
    }
}
