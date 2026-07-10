package com.refuge.refugecore;

import net.minecraft.client.Minecraft;                       // 客户端 Minecraft 实例（仅客户端存在）
import net.neoforged.api.distmarker.Dist;                    // 标记代码运行侧（CLIENT / SERVER）
import net.neoforged.bus.api.SubscribeEvent;                 // 事件监听注解
import net.neoforged.fml.ModContainer;                       // 模组容器
import net.neoforged.fml.common.EventBusSubscriber;          // 自动注册本类静态事件方法
import net.neoforged.fml.common.Mod;                         // 模组注解
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent; // 客户端初始化事件
import net.neoforged.neoforge.client.gui.ConfigurationScreen; // NeoForge 配置界面
import net.neoforged.neoforge.client.gui.IConfigScreenFactory; // 配置界面工厂接口

// @Mod(..., dist = Dist.CLIENT)：声明这是「仅客户端」入口，专用服务器不会加载本类
@Mod(value = RefugeCore.MODID, dist = Dist.CLIENT)
// 自动把本类里 @SubscribeEvent 静态方法注册到客户端事件总线
@EventBusSubscriber(modid = RefugeCore.MODID, value = Dist.CLIENT)
public class RefugeCoreClient {
    // 客户端模组构造器：注册配置界面入口
    public RefugeCoreClient(ModContainer container) {
        // 让 NeoForge 在「模组列表 > 配置」里能打开本模组的配置界面
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    // 客户端初始化：仅打日志，无游戏逻辑（核心逻辑全在服务端）
    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        RefugeCore.LOGGER.info("RefugeCore client setup");
        // 打印当前登录的 Minecraft 账号名（仅客户端可知）
        RefugeCore.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
