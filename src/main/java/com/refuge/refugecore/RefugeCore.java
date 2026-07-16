package com.refuge.refugecore;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;                                     // 日志接口（SLF4J）
import com.mojang.logging.LogUtils;                         // NeoForge 提供的日志工厂

import net.minecraft.commands.Commands;                     // 构建指令树的构建器
import net.minecraft.core.registries.Registries;            // 注册表（维度等在此登记）
import net.minecraft.network.chat.Component;                // 聊天栏可显示富文本
import net.minecraft.resources.ResourceKey;                 // 资源「键」，用于唯一定位维度
import net.minecraft.resources.ResourceLocation;            // 资源位置（命名空间:路径）
import net.minecraft.server.level.ServerLevel;              // 服务端维度世界
import net.minecraft.server.level.ServerPlayer;             // 服务端玩家
import net.minecraft.world.level.Level;                     // 维度世界基类（HOME_KEY 的类型参数）
import net.neoforged.bus.api.IEventBus;                     // 模组事件总线
import net.neoforged.bus.api.SubscribeEvent;                // 标记某方法为事件监听器
import net.neoforged.fml.common.Mod;                        // 声明这是模组主类
import net.neoforged.fml.config.ModConfig;                  // 模组配置类型
import net.neoforged.fml.ModContainer;                      // 模组容器（可注册配置等）
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent; // 公共初始化事件
import net.neoforged.neoforge.common.NeoForge;             // NeoForge 全局事件总线
import net.neoforged.neoforge.event.RegisterCommandsEvent; // 指令注册事件
import net.neoforged.neoforge.event.server.ServerStartingEvent; // 服务端启动事件
import net.minecraft.world.entity.Mob;                      // 生物基类（怪物+动物都继承它）
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent; // 实体进入某维度事件
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.UUID;

// @Mod 告诉 NeoForge：这是模组入口，MODID 必须和 neoforge.mods.toml 里的 modId 一致
@Mod(RefugeCore.MODID)
public class RefugeCore {
    // 模组 ID（命名空间）。数据包、指令、资源都以此前缀。
    public static final String MODID = "refugecore";
    // 全局日志器，各处在用 LOGGER.info(...) / LOGGER.error(...)
    public static final Logger LOGGER = LogUtils.getLogger();

    // 家园维度的「键」。维度本身由数据包 data/refugecore/dimension/home.json 定义，
    // 这里只是 Java 侧引用它用的「钥匙」。
    public static final ResourceKey<Level> HOME_KEY = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(MODID, "home"));

    // 模组主类构造器：模组加载时第一个执行。
    // FML 会按类型自动注入 IEventBus（模组总线）和 ModContainer（模组容器）。
    public RefugeCore(IEventBus modEventBus, ModContainer modContainer) {
        // 注册初始化监听：commonSetup 会在模组初始化阶段被调用
        modEventBus.addListener(this::commonSetup);

        // 把自己注册到 NeoForge 全局事件总线，下面 @SubscribeEvent 方法才会收到游戏事件
        NeoForge.EVENT_BUS.register(this);

        // 注册模组配置文件（Config.SPEC 来自 Config.java，对应 common 类型配置）
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    // 公共初始化（客户端+服务端都会跑一次），这里只打日志
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("RefugeCore common setup complete");
    }

    // 服务端启动时：加载地块分配表（从 plots.json 读回内存）
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        PlotManager.init(event.getServer());
        ReturnPositionManager.init(event.getServer());
    }

    // 注册指令：/refuge home
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {

        event.getDispatcher().register(Commands.literal("refuge")          // 一级指令 /refuge
                .then(Commands.literal("home").executes(ctx -> {           // 子指令 /refuge home
                    ServerPlayer player = ctx.getSource().getPlayer();     // 取执行者（玩家）
                    if (player == null) return 0;                          // 非玩家（如命令方块）忽略
                    handleHome(player);                                    // 交给核心处理逻辑
                    return 1;                                             // 指令执行成功
                }))
        );

        event.getDispatcher().register(Commands.literal("refuge")
                .then(Commands.literal("world").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (player == null) return 0;
                    handleWorld(player);
                    return 1;
                }))
        );
    }

    private void handleWorld(ServerPlayer player) {
        if (player.level().dimension() != HOME_KEY) {
            player.sendSystemMessage(Component.literal("§c你当前不在家园维度"));
            return;
        }

        ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            player.sendSystemMessage(Component.literal("§c主世界未加载"));
            return;
        }

        ReturnPositionManager rpm = ReturnPositionManager.get();
        double[] saved = (rpm != null) ? rpm.removePosition(player.getUUID()) : null;

        if (saved != null) {
            player.teleportTo(overworld, saved[0], saved[1], saved[2], (float) saved[3], (float) saved[4]);
            player.sendSystemMessage(Component.literal("§a已返回主世界"));
        } else {
            BlockPos spawn = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
            player.sendSystemMessage(Component.literal("§a已返回主世界出生点"));
        }
    }

    // 取消家园维度里的所有生物生成（玩家不是 Mob，不受影响）
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        // 进入的是「生物」且所在维度是家园维度 -> 取消这次进入（即不生成）
        if (event.getEntity() instanceof Mob && event.getLevel().dimension() == HOME_KEY) {
            event.setCanceled(true);
        }
    }

    // 把家园维度的时间锁定在正午（6000 tick）
    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        // 仅家园维度 + 且是服务端维度（避免客户端重复处理）
        if (event.getLevel().dimension() == HOME_KEY && event.getLevel() instanceof ServerLevel sl) {
            sl.setDayTime(6000L);                              // 6000 = 正午；每 tick 重写 -> 恒定白天
        }
    }

    // 核心流程：进入/领取家园地块并传送
    private void handleHome(ServerPlayer player) {
        PlotManager mgr = PlotManager.get();
        if (mgr == null) {
            player.sendSystemMessage(Component.literal("§c地块系统未就绪"));
            return;
        }

        if (player.level().dimension() == Level.OVERWORLD) {
            ReturnPositionManager rpm = ReturnPositionManager.get();
            if (rpm != null) {
                rpm.savePosition(player.getUUID(),
                        player.getX(), player.getY(), player.getZ(),
                        player.getYRot(), player.getXRot());
            }
        }

        boolean isNew = !mgr.has(player.getUUID());
        int[] ij = mgr.getOrAssign(player.getUUID());
        int i = ij[0], j = ij[1];

        ServerLevel home = player.server.getLevel(HOME_KEY);
        if (home == null) {
            player.sendSystemMessage(Component.literal("§c家园维度尚未加载，请先执行 /execute in refugecore:home run tp ~ ~ ~ 进入一次"));
            return;
        }

        // 计算传送目标：地块中心，y=5（落在地面上方），朝向/俯仰角 0
        double x = PlotManager.centerX(i) + 0.5;             // +0.5 落在方块中心而非角上
        double z = PlotManager.centerZ(j) + 0.5;
        player.teleportTo(home, x, 5, z, 0f, 0f);

        if (isNew) {
            PlotManager.buildPlot(home, i, j);
        }
        // 提示玩家传送成功及地块坐标
        player.sendSystemMessage(Component.literal("§a已传送到你的家园地块 (" + i + ", " + j + ")"));
    }
}
