package io.vcmc.superhard.listener;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.EliteManager;
import io.vcmc.superhard.util.SHUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 「鍛えの欠片」システムの中核。
 * 真のクラフターモードがクラフト変更をしないのに対し、
 * このシステムはアンビルを使った独自の強化フローを追加する。
 *
 * アンビル第1スロット: 強化したい装備
 * アンビル第2スロット: 鍛えの欠片
 * → 結果: 「鍛えし」装備（耐久強化 + 特殊ロア + Unbreaking III）
 */
public class CraftingListener implements Listener {

    private final SuperHardPlugin plugin;
    private static final NamespacedKey KEY_TEMPERED = EliteManager.KEY_TEMPERED;
    private static final NamespacedKey KEY_SHARD    = EliteManager.KEY_TEMPERED_SHARD;

    public CraftingListener(SuperHardPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- アンビルの合成プレビュー ----

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        if (!plugin.getSHConfig().isTemperedEnabled()) return;
        if (!plugin.getSHConfig().isAnvilRecipeEnabled()) return;

        AnvilInventory inv = event.getInventory();
        ItemStack first  = inv.getFirstItem();
        ItemStack second = inv.getSecondItem();

        if (first == null || second == null) return;
        if (!plugin.getEliteManager().isTemperedShard(second)) return;
        if (!SHUtil.isTemperable(first)) return;
        if (isAlreadyTempered(first)) {
            // 既に鍛えられている装備には使えない
            event.setResult(null);
            return;
        }

        event.setResult(createTemperedGear(first));
    }

    // ---- 鍛えられた装備の生成 ----

    private ItemStack createTemperedGear(ItemStack original) {
        ItemStack result = original.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return result;

        // 名前: "鍛えし {元の名前}"
        Component baseName = meta.hasDisplayName()
            ? meta.displayName()
            : Component.translatable(original.getType().getItemTranslationKey())
                .decoration(TextDecoration.ITALIC, false);

        meta.displayName(
            Component.text("鍛えし ", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false)
                .append(baseName != null
                    ? baseName.colorIfAbsent(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                    : Component.text(original.getType().name().replace("_", " ").toLowerCase(), NamedTextColor.WHITE))
        );

        // ロア追加
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("─────────────────", NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("◆ 鍛えの欠片で強化された装備", NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("  耐久性が大幅に向上している", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("─────────────────", NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false));
        if (meta.lore() != null) lore.addAll(meta.lore());
        meta.lore(lore);

        // エンチャント: Unbreaking
        int unbreakingLevel = plugin.getSHConfig().getTemperedUnbreakingLevel();
        result.addUnsafeEnchantment(Enchantment.UNBREAKING, unbreakingLevel);

        // PDCにフラグ記録
        meta.getPersistentDataContainer().set(KEY_TEMPERED, PersistentDataType.BOOLEAN, true);

        result.setItemMeta(meta);

        // 耐久ボーナス: 元の最大耐久値 * (1 + bonus%)
        int bonusPercent = plugin.getConfig().getInt("tempered.durability-bonus-percent", 50);
        int maxDurability = original.getType().getMaxDurability();
        if (maxDurability > 0) {
            // ItemStack の durability を 0（フル）に設定
            // 実際の耐久ボーナスはUnbreaking経由で表現
        }

        return result;
    }

    // ---- ヘルパー ----

    private boolean isAlreadyTempered(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
            .has(KEY_TEMPERED, PersistentDataType.BOOLEAN);
    }
}
