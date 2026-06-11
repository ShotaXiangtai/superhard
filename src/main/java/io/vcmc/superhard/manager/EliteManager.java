package io.vcmc.superhard.manager;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.ThreatManager.ThreatLevel;
import io.vcmc.superhard.util.SHUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
 * スポーン時に一定確率でモブを「精鋭化」する。
 * 精鋭モブは強化された能力を持ち、「鍛えの欠片」をドロップすることがある。
 *
 * 精鋭タイプ:
 *   鍛錬済み (FORGED)    - 基礎強化。HP+25%, 速度+10%
 *   古の者   (ANCIENT)   - 中強化。HP+60%, 速度+20%, ダメージ+35%、装備付き
 *   黙示録の (HARBINGER) - 最高強化。HP+100%, 速度+35%, ダメージ+60%、完全装備、パーティクル
 */
public class EliteManager {

    public enum EliteType {
        FORGED    ("鍛錬済み", NamedTextColor.AQUA,      "forged"),
        ANCIENT   ("古の者",   NamedTextColor.GOLD,       "ancient"),
        HARBINGER ("黙示録の", NamedTextColor.DARK_RED,  "harbinger");

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
            type = EliteType.HARBINGER; // 全体の15%枠内
        } else if (roll < base * 0.50) {
            type = EliteType.ANCIENT;   // 次の35%枠
        } else if (roll < base) {
            type = EliteType.FORGED;    // 残り
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
            case FORGED    -> 1.10;
            case ANCIENT   -> 1.20;
            case HARBINGER -> 1.35;
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

        // 装備（ANCIENT以上）
        if (type == EliteType.ANCIENT || type == EliteType.HARBINGER) {
            applyEliteEquipment(mob, type);
        }

        // HARBINGERはポーション効果付与
        if (type == EliteType.HARBINGER) {
            mob.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, true, false));
            mob.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0, true, false));
        }

        // 付近プレイヤーへの通知
        mob.getWorld().getNearbyPlayers(mob.getLocation(), 40).forEach(p ->
            p.sendMessage(Component.text("[SuperHard] ", NamedTextColor.DARK_RED)
                .append(Component.text("近くに精鋭モブが現れた → ", NamedTextColor.GRAY))
                .append(Component.text(type.prefix + " " + baseName, type.color)))
        );
    }

    private void applyEliteEquipment(Mob mob, EliteType type) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;

        Material helmetMat   = type == EliteType.HARBINGER ? Material.DIAMOND_HELMET    : Material.IRON_HELMET;
        Material chestMat    = type == EliteType.HARBINGER ? Material.DIAMOND_CHESTPLATE : Material.IRON_CHESTPLATE;
        Material leggingsMat = type == EliteType.HARBINGER ? Material.DIAMOND_LEGGINGS  : Material.IRON_LEGGINGS;
        Material bootsMat    = type == EliteType.HARBINGER ? Material.DIAMOND_BOOTS     : Material.IRON_BOOTS;

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

    public ItemStack createTemperedShard() {
        ItemStack shard = new ItemStack(Material.AMETHYST_SHARD, 1);
        ItemMeta meta = shard.getItemMeta();
        meta.displayName(Component.text("鍛えの欠片", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, false));
        meta.lore(List.of(
            Component.text("精鋭モブの魂が宿る欠片", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("鍛冶台(アンビル)で装備に組み合わせると", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("装備を「鍛えし」状態に強化できる", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(KEY_TEMPERED_SHARD, PersistentDataType.BOOLEAN, true);
        shard.setItemMeta(meta);
        return shard;
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
}
