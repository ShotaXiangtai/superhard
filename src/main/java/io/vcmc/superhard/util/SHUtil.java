package io.vcmc.superhard.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.Optional;
import java.util.Random;

public final class SHUtil {

    private static final Random RANDOM = new Random();

    private SHUtil() {}

    public static boolean chance(double probability) {
        return RANDOM.nextDouble() < probability;
    }

    public static int randomInt(int min, int max) {
        return min + RANDOM.nextInt(max - min + 1);
    }

    public static double randomDouble(double min, double max) {
        return min + RANDOM.nextDouble() * (max - min);
    }

    /** 対象に向かう正規化ベクトルを返す（高さ成分を除いた水平方向） */
    public static Vector horizontalDirection(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector());
        v.setY(0);
        if (v.lengthSquared() < 0.001) return new Vector(1, 0, 0);
        return v.normalize();
    }

    /** from から to へのベクトル（高さ込み、正規化） */
    public static Vector direction3D(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector());
        if (v.lengthSquared() < 0.001) return new Vector(0, 1, 0);
        return v.normalize();
    }

    /** 指定半径内の最近接プレイヤーを返す（ゲームモード・不可視無視） */
    public static Optional<Player> nearestPlayer(Location center, double radius) {
        Collection<Entity> entities = center.getWorld().getNearbyEntities(center, radius, radius, radius);
        Player nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Entity e : entities) {
            if (!(e instanceof Player p)) continue;
            if (p.isInvisible() || p.getGameMode() == org.bukkit.GameMode.CREATIVE
                    || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double d = p.getLocation().distanceSquared(center);
            if (d < minDist) { minDist = d; nearest = p; }
        }
        return Optional.ofNullable(nearest);
    }

    /**
     * 指定地点から水平方向にランダムオフセットを加えた安全なスポーン地点を返す。
     * ワールドの地面高さに合わせる。
     */
    public static Location safeSpawnNear(Location base, double minRadius, double maxRadius) {
        World world = base.getWorld();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2;
            double dist  = randomDouble(minRadius, maxRadius);
            double x = base.getX() + Math.cos(angle) * dist;
            double z = base.getZ() + Math.sin(angle) * dist;
            int y = world.getHighestBlockYAt((int) x, (int) z) + 1;
            Location loc = new Location(world, x, y, z);
            Block block = loc.getBlock();
            if (block.getType().isAir() && loc.clone().subtract(0, 1, 0).getBlock().getType().isSolid()) {
                return loc;
            }
        }
        // フォールバック: そのまま返す
        return base.clone().add(
            RANDOM.nextGaussian() * 5, 0, RANDOM.nextGaussian() * 5
        );
    }

    /** アイテムに特定の NamespacedKey が設定されているか確認 */
    public static boolean hasKey(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(key, PersistentDataType.BOOLEAN);
    }

    /** 装備可能なアイテムか（武器・防具・道具） */
    public static boolean isTemperable(ItemStack item) {
        if (item == null) return false;
        String name = item.getType().name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE")
            || name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL")
            || name.endsWith("_HOE") || name.endsWith("_HELMET")
            || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
            || name.endsWith("_BOOTS") || name.endsWith("_BOW")
            || name.endsWith("_CROSSBOW") || item.getType() == Material.TRIDENT
            || item.getType() == Material.MACE;
    }

    /** エンティティのルートで向いている方向の1ブロック先 */
    public static Location oneTileAhead(LivingEntity entity) {
        return entity.getEyeLocation().add(entity.getLocation().getDirection());
    }

    /** 指定した距離内に固体ブロックがあるか（経路チェック用） */
    public static boolean isBlockedAhead(LivingEntity entity, double distance) {
        Location eye = entity.getEyeLocation();
        Vector dir   = entity.getLocation().getDirection().setY(0).normalize();
        for (double d = 0.5; d <= distance; d += 0.5) {
            Block b = eye.clone().add(dir.clone().multiply(d)).getBlock();
            if (b.getType().isSolid()) return true;
        }
        return false;
    }

    /** ゲーム内日数をワールドの全Tickから計算（1日 = 24000 ticks） */
    public static long worldDayCount(World world) {
        return world.getFullTime() / 24000L;
    }

    /** BehaviorKey: モブ行動のクールダウン管理用キー */
    public enum BehaviorKey {
        LEAP, SCAFFOLD, SUMMON_ALLIES, BLOCK_BREAK,
        STRAFE, BACKSTEP, WEAPON_SWITCH,
        INVISIBLE_SET, FIRE_TRAIL,
        WEB_PLACE, SLIME_PROJECTILE,
        BUFF_AURA, WITCH_TELEPORT,
        ENDERMAN_MINION, FREEZE_WATER,
        PACK_ALERT, ALPHA_ASSIGNED
    }
}
