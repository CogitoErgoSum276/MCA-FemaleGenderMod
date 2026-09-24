/*
 * Wildfire's Female Gender Mod is a female gender mod created for Minecraft.
 * Copyright (C) 2023-present WildfireRomeo
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mcabp.wildfire.client;

import com.mcabp.wildfire.client.render.GenderLayer;
import com.mcabp.wildfire.client.resources.GenderArmorResourceManager;
import com.mcabp.wildfire.main.WildfireGender;
import com.mcabp.wildfire.main.config.GeneralClientConfig;
import com.mcabp.wildfire.main.entitydata.EntityConfig;
import net.conczin.mca.Config;
import net.conczin.mca.client.model.VillagerEntityModelMCA;
import net.conczin.mca.client.render.VillagerEntityMCARenderer;
import net.conczin.mca.client.render.ZombieVillagerEntityMCARenderer;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.ZombieVillagerEntityMCA;
import net.conczin.mca.registry.EntitiesMCA;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * <h2>模组客户端主类</h2>
 *
 * <p>{@code @Mod} 注解把它标记为模组的入口之一，{@code dist = Dist.CLIENT} 表示
 * <b>只在客户端加载</b>——服务端根本不会实例化这个类。这样安排是因为渲染、
 * 界面这些功能全是纯客户端行为，放服务端只会白白增加同步负担。</p>
 *
 * <p>本类主要承担「事件注册中心」的角色：构造时把所有关心的游戏事件挂到事件总线上，
 * 具体逻辑分散在下面各个 {@code onXxx} 方法里。对理解胸部功能来说，
 * 最关键的两个方法是：</p>
 * <ul>
 *   <li>{@link #onEntityTick} —— 每 tick 驱动物理模拟的<b>唯一入口</b>；</li>
 *   <li>{@link #entityLayers} —— 把胸部渲染层挂到实体渲染器上的地方。</li>
 * </ul>
 */
@Mod(value = WildfireGender.MODID, dist = Dist.CLIENT)
public class WildfireGenderClient {

    // 单例引用，方便在任何地方访问（例如按键回调里修改 renderBreasts）
    public static WildfireGenderClient INSTANCE;

    //Note: this option is not intended to be saved in any persistent manner
    //TODO - 1.21: Figure out where fabric uses this option if anywhere?
    // 是否渲染胸部。刻意不做持久化——每次启动都恢复为 true
    private boolean renderBreasts = true;
    // tick 计数器，用来做「每 N tick 执行一次」的定时任务
    private int timer = 0;

    /**
     * 构造时完成两件事：注册配置文件，以及把所有事件监听器挂到事件总线上。
     *
     * <p>这里出现了两个不同的事件总线，含义不同：</p>
     * <ul>
     *   <li>{@code modEventBus}：模组生命周期事件，只在加载阶段触发一次
     *       （注册渲染层、重载监听器等）。</li>
     *   <li>{@code NeoForge.EVENT_BUS}：游戏事件，运行时持续触发
     *       （每 tick、进出世界等）。</li>
     * </ul>
     *
     * @param modContainer 模组容器，用来注册配置
     * @param modEventBus  模组生命周期事件总线
     */
    public WildfireGenderClient(ModContainer modContainer, IEventBus modEventBus) {
        INSTANCE = this;
        disableMcaBreasts();
        // 注册客户端配置，最终落在 config/mcabp-client.toml。
        // 注意文件名必须使用本模组自己的命名空间：若沿用 FGM 的 "WildfireGender/client.toml"，
        // 会在两个模组共存时因抢占同一配置文件而注册冲突，导致本模组直接加载失败。
        modContainer.registerConfig(Type.CLIENT, GeneralClientConfig.INSTANCE.configSpec, "mcabp-client.toml");
        // 注册 NeoForge 的内置配置界面。
        // 注册之后，"模组"菜单里本模组条目上会出现"配置"按钮，界面会依据 ModConfigSpec
        // 自动为每一项生成对应控件（开关 / 滑块），玩家不必手动编辑 toml 文件。
        // 若不注册这个扩展点，配置文件依然会生成，但游戏内没有调整入口。
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // ---- 加载期事件（只触发一次）----
        modEventBus.addListener(this::entityLayers);            // 挂载渲染层
        modEventBus.addListener(this::registerReloadListeners); // 注册资源重载监听
        // ---- 运行期事件 ----
        NeoForge.EVENT_BUS.addListener(this::onClientTick);   // 客户端每 tick
        NeoForge.EVENT_BUS.addListener(this::onEntityTick);   // 实体每 tick（驱动物理）
        NeoForge.EVENT_BUS.addListener(this::connect);        // 登录服务器
        NeoForge.EVENT_BUS.addListener(this::onEntityLeave);  // 实体离开世界
    }

    /**
     * <h3>关闭 MCA 自带的静态胸部模型</h3>
     *
     * <p>MCA 原本就给村民做了一对胸部几何体（见 {@code CommonVillagerModel}），
     * 它是<b>静态</b>的——靠固定的前倾角加非均匀缩放来表现大小，不会晃动。</p>
     *
     * <p>两套胸部不能并存，根本原因是它们<b>抢同一块贴图区域</b>：MCA 取
     * {@code texOffs(18, 21)}，本模组移植的几何体取 {@code (16,17)} / {@code (20,17)}，
     * 都落在 body 贴图的胸部位，同时渲染必然互相穿插、贴图错乱。</p>
     *
     * <p>关闭方式是令 MCA 的 {@code Config.enableBoobs} 为 {@code false}。该字段控制
     * MCA 建模时是否生成乳房盒子（{@code VillagerEntityBaseModelMCA#newBreasts}），
     * 而 MCA 的实体模型要到 {@code EntityRenderersEvent.RegisterRenderers} 阶段才创建；
     * 本方法在模组构造阶段被调用，早于那一刻，所以几何体压根不会被建出来，
     * 比“先建好再隐藏”更干净也更省性能。</p>
     *
     * <p>副作用：MCA 的玩家模型（{@code PlayerEntityExtendedModel}）走的是同一套
     * {@code bodyData}，它的静态胸部也会一并关闭——这正是期望行为，玩家的胸部
     * 交给 Female Gender Mod 本体负责。</p>
     */
    private void disableMcaBreasts() {
        Config.getInstance().enableBoobs = false;
    }

    /**
     * <h3>把胸部渲染层挂到实体渲染器上（关键接入点）</h3>
     *
     * <p>渲染器本身不知道胸部这回事，必须由模组在加载期把自己的
     * {@link GenderLayer} 追加进去。这个方法就是做这件事的地方——整个模组里
     * {@code renderer.addLayer(...)} 只出现在此处。</p>
     *
     * <p>MCA 有 4 个村民实体类型，每个实体类型的渲染器实例彼此独立，必须逐个挂载，
     * 漏掉任何一个都会导致那一类村民没有胸部：</p>
     * <ul>
     *   <li>{@code mca:male_villager} / {@code mca:female_villager}（普通村民）</li>
     *   <li>{@code mca:male_zombie_villager} / {@code mca:female_zombie_villager}（僵尸村民）</li>
     * </ul>
     *
     * <p>这里刻意不按性别区分：渲染时由 {@code Genetics} 的性别基因决定要不要画
     * （见 {@link EntityConfig} 的尺寸同步——非女性的罩杯会被算成 0，自然不可见），
     * 所以男性村民复用同一套渲染层即可，无需额外的渲染器。</p>
     *
     * <p>另注意 {@link GenderLayer} 的泛型约束：目标渲染器的模型类型必须是
     * {@code HumanoidModel} 的子类，否则它无法通过 {@code model.body} 定位胸部位置。
     * MCA 的 {@code VillagerEntityModelMCA} 满足这一点。</p>
     */
    private void entityLayers(EntityRenderersEvent.AddLayers event) {
        ModelManager modelManager = event.getContext().getModelManager();

        addVillagerLayer(event, EntitiesMCA.MALE_VILLAGER, modelManager);
        addVillagerLayer(event, EntitiesMCA.FEMALE_VILLAGER, modelManager);
        addZombieVillagerLayer(event, EntitiesMCA.MALE_ZOMBIE_VILLAGER, modelManager);
        addZombieVillagerLayer(event, EntitiesMCA.FEMALE_ZOMBIE_VILLAGER, modelManager);
    }

    /** 给普通村民的渲染器挂上胸部图层。 */
    private void addVillagerLayer(EntityRenderersEvent.AddLayers event, EntityType<VillagerEntityMCA> type, ModelManager modelManager) {
        if (event.getRenderer(type) instanceof VillagerEntityMCARenderer renderer) {
            // 显式写出泛型参数：instanceof 的 pattern 变量是 raw type，钻石操作符无法完成推断
            renderer.addLayer(new GenderLayer<VillagerEntityMCA, VillagerEntityModelMCA<VillagerEntityMCA>>(renderer, modelManager));
        } else {
            // 渲染器类型不符通常意味着 MCA 换了渲染器实现（例如开启 useSquidwardModels 用原版村民模型），
            // 此时胸部无法挂载，留下一条警告便于定位。
            WildfireGender.LOGGER.warn("[mcabp] 渲染器类型不匹配，未挂载：{} 的渲染器是 {}", type, event.getRenderer(type));
        }
    }

    /** 给僵尸村民的渲染器挂上胸部图层。 */
    private void addZombieVillagerLayer(EntityRenderersEvent.AddLayers event, EntityType<ZombieVillagerEntityMCA> type, ModelManager modelManager) {
        if (event.getRenderer(type) instanceof ZombieVillagerEntityMCARenderer renderer) {
            renderer.addLayer(new GenderLayer<ZombieVillagerEntityMCA, VillagerEntityModelMCA<ZombieVillagerEntityMCA>>(renderer, modelManager));
        } else {
            WildfireGender.LOGGER.warn("[mcabp] 渲染器类型不匹配，未挂载：{} 的渲染器是 {}", type, event.getRenderer(type));
        }
    }

    /**
     * 注册资源重载监听器。
     *
     * <p>{@link GenderArmorResourceManager} 负责扫描并解析
     * {@code assets/<命名空间>/wildfire_gender_data/} 下的盔甲配置文件，
     * 该目录会随资源包重新加载，所以必须注册成重载监听器而不是只在启动时读一次。</p>
     */
    private void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(GenderArmorResourceManager.INSTANCE);
    }

    /**
     * 客户端每 tick 执行一次的杂务。玩家配置同步与云同步相关逻辑已随玩家功能一并移除，
     * 这里只保留 tick 计数，供后续「每 N tick 执行一次」的逻辑使用。
     */
    private void onClientTick(ClientTickEvent.Post evt) {
        if (Minecraft.getInstance().level == null || Minecraft.getInstance().player == null) {
            return;
        }
        timer++;
    }

    /**
     * <h3>物理模拟的驱动入口（整条链路的第一步）</h3>
     *
     * <p>这是胸部能晃动起来的源头。每 tick 每个实体都会经过这里，通过筛选的实体会被送进
     * {@link EntityConfig#tickBreastPhysics}，之后依次是：</p>
     *
     * <pre>
     *   本方法 → EntityConfig#tickBreastPhysics → BreastPhysics#update（左右各一次）
     * </pre>
     *
     * <p>渲染阶段则是由 {@link GenderLayer} 在<b>每一帧</b>读取物理结果，
     * 两者频率不同（这里 20Hz，渲染 60+fps），靠插值衔接。</p>
     *
     * <p>三道筛选条件缺一不可：</p>
     * <ol>
     *   <li>{@code isClientSide}：只在客户端算。物理结果纯属视觉表现，
     *       服务端算它毫无意义，还会造成客户端与服务端的无谓差异。</li>
     *   <li>{@code instanceof LivingEntity}：必须是活体实体（有身体模型）。</li>
     *   <li>{@link EntityConfig#isSupportedEntity}：只有玩家和盔甲架通过。
     *       这是模组 NPC 默认不会晃的根本原因。</li>
     * </ol>
     */
    private void onEntityTick(EntityTickEvent.Post evt) {
        Entity entity = evt.getEntity();
        if (entity.level().isClientSide && entity instanceof LivingEntity living && EntityConfig.isSupportedEntity(living)) {
            EntityConfig cfg = EntityConfig.getEntity(living);
            // 真正的物理更新：左右胸各算一次
            cfg.tickBreastPhysics(living);
        }
    }

    private void connect(ClientPlayerNetworkEvent.LoggingIn evt) {
        // 玩家配置体系已移除，实体数据改为在首次访问时由 EntityConfig.CACHE 按需创建
    }

    /**
     * 实体离开世界时，把它从配置缓存里移除，避免缓存无限增长。
     */
    private void onEntityLeave(EntityLeaveLevelEvent evt) {
        if (evt.getLevel().isClientSide) {
            EntityConfig.CACHE.invalidate(evt.getEntity().getUUID());
        }
    }
}
