package com.refuge.refugecore;

import com.google.gson.Gson;                                  // Gson：Java 对象 <-> JSON 互转（Minecraft 自带，无需额外依赖）
import com.google.gson.GsonBuilder;                          // Gson 构造器，用于开启格式化（缩进）输出
import com.google.gson.reflect.TypeToken;                    // 运行时保留泛型信息（此处用于 Map<String,int[]>）

import net.minecraft.core.BlockPos;                          // 方块坐标 (x,y,z)，放置方块时必须
import net.minecraft.server.MinecraftServer;                 // 服务端主对象，可拿存档路径
import net.minecraft.server.level.ServerLevel;               // 服务端的某个维度世界（可写方块）
import net.minecraft.world.level.block.Blocks;               // 方块常量表（Blocks.GLOWSTONE 等）
import net.minecraft.world.level.block.state.BlockState;     // 方块"状态"（含朝向/含水等数据）
import net.minecraft.world.level.storage.LevelResource;      // 存档内资源路径枚举（ROOT=世界根目录）

import java.lang.reflect.Type;                               // 反射类型，配合 TypeToken 使用
import java.nio.file.Files;                                  // 文件读写工具
import java.nio.file.Path;                                   // 文件路径
import java.util.HashMap;                                    // 分配表的运行时容器
import java.util.Map;                                        // Map 接口
import java.util.UUID;                                       // 玩家唯一 ID

/**
 * 地块管理器（Stage1 核心类）
 * 职责：
 *   1) 给每个玩家分配 refugecore:home 维度里的一块地（坐标 i,j）；
 *   2) 把「谁 -> 哪块地」持久化到存档文件，服务端重启不丢；
 *   3) 首次分配时，在地块上生成萤石边框 + 中心模板建筑。
 *
 * 关键参数：
 *   - 每块地 12x12 区块 = 192x192 格；
 *   - 地块之间留 1 区块(16格)缓冲，相邻地块原点间距 PITCH=208 格；
 *   - 分配用「从原点向外逐环找第一个空位」，不用 UUID 取模，避免多人撞车。
 */
public class PlotManager {
    // 单块可建造区域的边长（格）。12 区块 * 16 格/区块 = 192 格
    public static final int PLOT_BLOCKS = 192;
    // 相邻地块原点之间的间距（格）。192 可建造 + 16 缓冲 = 208
    public static final int PITCH = 208;
    // 边框方块放置的 Y 高度。地面 grass_block 在 y=3，边框放在 y=4
    public static final int BORDER_Y = 4;

    // 单例：整个服务端只有一个 PlotManager 实例
    private static PlotManager INSTANCE;

    // 服务端引用（用于拿存档路径）
    private final MinecraftServer server;
    // 分配表存档路径：<世界>/refugecore/plots.json
    private final Path file;
    // 内存中的分配表：玩家UUID字符串 -> [i, j] 地块坐标
    private final Map<String, int[]> allocations = new HashMap<>();
    // Gson 实例，开启漂亮打印（缩进）便于人读
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    // 分配表的 JSON 类型描述，反序列化时告诉 Gson 目标是 Map<String,int[]>
    private static final Type MAP_TYPE = new TypeToken<Map<String, int[]>>() {}.getType();

    // 私有构造：只能通过 init() 创建
    private PlotManager(MinecraftServer server) {
        this.server = server;
        // 拼出存档路径：世界根目录 / refugecore / plots.json
        this.file = server.getWorldPath(LevelResource.ROOT).resolve("refugecore").resolve("plots.json");
    }

    // 服务端启动时调用一次：加载已有分配表 + 建好目录
    public static void init(MinecraftServer server) {
        PlotManager mgr = new PlotManager(server);           // 创建实例
        try {
            if (Files.exists(mgr.file)) {                    // 若存档文件已存在（之前分配过）
                // 读出 JSON 并反序列化为 Map<String,int[]>
                Map<String, int[]> loaded = mgr.gson.fromJson(Files.readString(mgr.file), MAP_TYPE);
                if (loaded != null) mgr.allocations.putAll(loaded);  // 合并进内存表
            }
            Files.createDirectories(mgr.file.getParent());   // 确保 refugecore/ 目录存在
        } catch (Exception e) {
            // 读取失败不要崩服，只记日志
            RefugeCore.LOGGER.error("Failed to load plot allocations", e);
        }
        INSTANCE = mgr;                                       // 存为单例
        // 日志：本次加载了多少条分配记录
        RefugeCore.LOGGER.info("PlotManager loaded {} plot allocations", mgr.allocations.size());
    }

    // 取单例
    public static PlotManager get() {
        return INSTANCE;
    }

    // 该玩家是否已有地块
    public boolean has(UUID uuid) {
        return allocations.containsKey(uuid.toString());
    }

    // 取地块坐标；没有就分配新地块并立即保存
    public int[] getOrAssign(UUID uuid) {
        String key = uuid.toString();                        // UUID 转字符串当 Map 的 key
        int[] ij = allocations.get(key);                      // 查已有
        if (ij != null) return ij;                           // 有就直接返回
        ij = nextFree();                                     // 没有就找下一个空位
        allocations.put(key, ij);                            // 写进内存表
        save();                                              // 立即持久化到磁盘
        return ij;                                           // 返回新坐标
    }

    // 判断 (i,j) 是否已被占用
    private boolean isOccupied(int i, int j) {
        for (int[] v : allocations.values()) {               // 遍历所有已分配坐标
            if (v[0] == i && v[1] == j) return true;         // 命中即占用
        }
        return false;                                        // 都没中则空闲
    }

    // 从 (0,0) 向外按「正方形环」逐层扩展，找第一个空闲地块
    private int[] nextFree() {
        for (int r = 0; r < 100000; r++) {                   // r = 环半径（第几圈）
            for (int i = -r; i <= r; i++) {                  // 遍历环上候选 i
                for (int j = -r; j <= r; j++) {              // 遍历环上候选 j
                    // 只在「当前环的边」上检查：max(|i|,|j|) 必须等于 r，否则是环内已查过的点
                    if (Math.max(Math.abs(i), Math.abs(j)) != r) continue;
                    if (!isOccupied(i, j)) return new int[]{i, j};  // 第一个空的就返回
                }
            }
        }
        return new int[]{0, 0};                              // 兜底（理论上走不到）
    }

    // 把内存分配表写回磁盘
    public void save() {
        try {
            Files.writeString(file, gson.toJson(allocations, MAP_TYPE));  // 序列化并写文件
        } catch (Exception e) {
            RefugeCore.LOGGER.error("Failed to save plot allocations", e);
        }
    }

    // ---- 坐标换算工具 ----
    // 地块 i 的原点 X（地块左下角世界 X）
    public static int originX(int i) { return i * PITCH; }
    // 地块 j 的原点 Z
    public static int originZ(int j) { return j * PITCH; }
    // 地块 i 的中心 X（可建造区中点）
    public static int centerX(int i) { return i * PITCH + PLOT_BLOCKS / 2; }
    // 地块 j 的中心 Z
    public static int centerZ(int j) { return j * PITCH + PLOT_BLOCKS / 2; }

    /**
     * 在地块上生成边界 + 中心模板。仅在首次分配时调用一次，
     * 这样玩家之后自己盖的建筑不会被覆盖。
     */
    public static void buildPlot(ServerLevel level, int i, int j) {
        int ox = originX(i);                                 // 地块原点 X
        int oz = originZ(j);                                 // 地块原点 Z
        BlockState border = Blocks.GLOWSTONE.defaultBlockState();  // 边界方块=萤石（会发光）

        // 底边与顶边：沿 X 方向铺满两排萤石（y=BORDER_Y）
        for (int x = 0; x < PLOT_BLOCKS; x++) {
            set(level, ox + x, BORDER_Y, oz, border);                     // 底边
            set(level, ox + x, BORDER_Y, oz + PLOT_BLOCKS - 1, border);   // 顶边
        }
        // 左边与右边：沿 Z 方向铺（首尾已铺过，从 1 到 size-2 避免重复）
        for (int z = 1; z < PLOT_BLOCKS - 1; z++) {
            set(level, ox, BORDER_Y, oz + z, border);                     // 左边
            set(level, ox + PLOT_BLOCKS - 1, BORDER_Y, oz + z, border);   // 右边
        }

        // 中心模板：5x5 橡木木板平台 + 四角橡木原木柱子
        int cx = centerX(i);                                // 地块中心 X
        int cz = centerZ(j);                                // 地块中心 Z
        BlockState plank = Blocks.OAK_PLANKS.defaultBlockState();   // 平台方块
        BlockState log = Blocks.OAK_LOG.defaultBlockState();       // 柱子方块
        // 平台：dx,dz 从 -2 到 2 共 5x5
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                set(level, cx + dx, BORDER_Y, cz + dz, plank);     // 铺木板
            }
        }
        // 四角柱子：dx,dz 取 {-2, 2} 组合 = 四个角，每根 2 格高
        for (int dx : new int[]{-2, 2}) {
            for (int dz : new int[]{-2, 2}) {
                set(level, cx + dx, BORDER_Y + 1, cz + dz, log);   // 柱子第 1 格
                set(level, cx + dx, BORDER_Y + 2, cz + dz, log);   // 柱子第 2 格
            }
        }
        // 日志：记录生成了哪块地、原点在哪
        RefugeCore.LOGGER.info("Built plot ({}, {}) at origin ({}, {})", i, j, ox, oz);
    }

    // 小工具：在 (x,y,z) 放置一个方块（flag=3 表示更新邻居并通知客户端）
    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, 3);
    }
}
