package io.vcmc.superhard.listener;

import io.vcmc.superhard.SuperHardPlugin;
import io.vcmc.superhard.manager.EliteManager;
import io.vcmc.superhard.util.SHUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * カスタムクラフトの処理:
 *   1. [TEMPERED] 装備: アンビル + 鋼 (ソウルシャード)
 *   2. 鋼ブロック: 3×3 鋼 → 鋼ブロック
 *   3. 鋼ブロック着火: 設置した鋼ブロックに火打ち石 → レイド強制起動
 */
public class CraftingListener implements Listener {

    private static final NamespacedKey KEY_TEMPERED      = EliteManager.KEY_TEMPERED;
    private static final NamespacedKey KEY_SHARD         = EliteManager.KEY_TEMPERED_SHARD;
    private static final NamespacedKey KEY_STEEL_BLOCK   = new NamespacedKey("superhard", "steel_block");
    private static final NamespacedKey STEEL_BLOCK_RECIPE= new NamespacedKey("superhard", "steel_block_recipe");

    // 設置された鋼ブロックの場所を追跡（再起動でリセット）
    private final Set<Location> placedSteelBlocks = new HashSet<>();

    private final SuperHardPlugin plugin;

    public CraftingListener(SuperHardPlugin plugin) {
        this.plugin = plugin;
        registerSteelBlockRecipe();
    }

    // ============================================================
    //  鋼ブロック レシピ登録
    // ============================================================

    private void registerSteelBlockRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(STEEL_BLOCK_RECIPE, createSteelBlockItem());
        recipe.shape("SSS", "SSS", "SSS");
        recipe.setIngredient('S', Material.AMETHYST_SHARD);
        plugin.getServer().addRecipe(recipe);
    }

    public ItemStack createSteelBlockItem() {
        ItemStack block = new ItemStack(Material.IRON_BLOCK);
        ItemMeta meta = block.getItemMeta();
        meta.displayName(Component.text("鋼ブロック", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
            Component.text("地面に置いて火打ち石で着火すると", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("強制的にレイドを召喚できる", NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(KEY_STEEL_BLOCK, PersistentDataType.BOOLEAN, true);
        block.setItemMeta(meta);
        return block;
    }

    // ============================================================
    //  クラフトイベント: 鋼ブロック レシピの入力検証
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraftSteelBlock(CraftItemEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof ShapedRecipe sr)) return;
        if (!sr.getKey().equals(STEEL_BLOCK_RECIPE)) return;

        // 入力が実際の 鋼 (ソウルシャード NBT) かどうか検証
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (item == null || item.getType() == Material.AIR) continue;
            if (!isSteelShard(item)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player p) {
                    p.sendMessage(Component.text(
                        "[SuperHard] 鋼ブロックには 鋼 が必要です（精鋭モブのドロップ）",
                        NamedTextColor.RED));
                }
                return;
            }
        }
    }

    // ============================================================
    //  ブロック設置・破壊: 鋼ブロックの位置追跡
    // ============================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isSteelBlockItem(event.getItemInHand())) return;
        placedSteelBlocks.add(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        placedSteelBlocks.remove(event.getBlock().getLocation());
    }

    // ============================================================
    //  着火イベント: 鋼ブロック + 火打ち石 → レイド召喚
    // ============================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (event.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) return;

        Block fireBlock = event.getBlock();

        // 着火ブロックの隣6面をチェック
        for (BlockFace face : new BlockFace[]{
                BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST, BlockFace.UP}) {

            Block adj = fireBlock.getRelative(face);
            if (!placedSteelBlocks.contains(adj.getLocation())) continue;

            // 鋼ブロック発見
            event.setCancelled(true); // 通常の火は付けない

            if (plugin.getSiegeManager().isSiegeActive()) {
                if (event.getIgnitingEntity() instanceof Player p)
                    p.sendMessage(Component.text("[SuperHard] すでにレイドが進行中です。", NamedTextColor.YELLOW));
                return;
            }

            // ブロックを消費
            placedSteelBlocks.remove(adj.getLocation());
            adj.setType(Material.AIR);

            // 演出
            adj.getWorld().strikeLightningEffect(adj.getLocation());
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                adj.getWorld().strikeLightningEffect(adj.getLocation().add(
                    SHUtil.randomDouble(-2, 2), 0, SHUtil.randomDouble(-2, 2))), 5L);

            if (event.getIgnitingEntity() instanceof Player p) {
                p.sendMessage(Component.text(
                    "[SuperHard] 鋼ブロックの力でレイドを召喚した！", NamedTextColor.RED)
                    .decoration(TextDecoration.BOLD, true));
            }

            plugin.getSiegeManager().manualStart();
            return;
        }
    }

    // ============================================================
    //  アンビル: [TEMPERED] 装備強化
    // ============================================================

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
        if (isAlreadyTempered(first)) { event.setResult(null); return; }

        event.setResult(createTemperedGear(first));
    }

    // ============================================================
    //  ヘルパー
    // ============================================================

    private boolean isSteelShard(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer()
                   .has(KEY_SHARD, PersistentDataType.BOOLEAN);
    }

    private boolean isSteelBlockItem(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer()
                   .has(KEY_STEEL_BLOCK, PersistentDataType.BOOLEAN);
    }

    private boolean isAlreadyTempered(ItemStack item) {
        return item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer()
                   .has(KEY_TEMPERED, PersistentDataType.BOOLEAN);
    }

    private ItemStack createTemperedGear(ItemStack original) {
        ItemStack result = original.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta == null) return result;

        Component baseName = meta.hasDisplayName()
            ? meta.displayName()
            : Component.translatable(original.getType().getItemTranslationKey())
                .decoration(TextDecoration.ITALIC, false);

        meta.displayName(
            Component.text("[TEMPERED] ", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false)
                .append(baseName != null
                    ? baseName.colorIfAbsent(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                    : Component.text(original.getType().name().replace("_", " ").toLowerCase(), NamedTextColor.WHITE))
        );

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("◆ 鋼で鍛えられた装備", NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false));
        if (meta.lore() != null) lore.addAll(meta.lore());
        meta.lore(lore);

        result.addUnsafeEnchantment(Enchantment.UNBREAKING, plugin.getSHConfig().getTemperedUnbreakingLevel());
        meta.getPersistentDataContainer().set(KEY_TEMPERED, PersistentDataType.BOOLEAN, true);
        result.setItemMeta(meta);
        return result;
    }
}
