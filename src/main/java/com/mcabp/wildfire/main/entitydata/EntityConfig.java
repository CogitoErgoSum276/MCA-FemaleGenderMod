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

package com.mcabp.wildfire.main.entitydata;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.mcabp.wildfire.api.IGenderArmor;
import com.mcabp.wildfire.main.Gender;
import com.mcabp.wildfire.main.WildfireHelper;
import com.mcabp.wildfire.main.config.ClientConfiguration;
import com.mcabp.wildfire.main.config.GeneralClientConfig;
import com.mcabp.wildfire.physics.BreastPhysics;
import java.time.Duration;
import java.util.UUID;
import net.conczin.mca.entity.VillagerLike;
import net.conczin.mca.entity.ai.Genetics;
import net.conczin.mca.entity.ai.relationship.AgeState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * <h2>实体配置：胸部数据的载体</h2>
 *
 * <p>所有实体（玩家、盔甲架，以及后续接入的模组 NPC）的胸部配置都存放在本类里，
 * 由 {@link #CACHE} 按实体 UUID 统一管理。玩家可调的附加选项（受伤音效、
 * 护甲物理覆盖等）在本模组中已随玩家专用功能移除，因此本类不再有子类。</p>
 *
 * <p><b>本类在整体架构中的位置</b>：它是「配置数据」与「物理模拟」的交汇点。
 * 每实体持有两个 {@link BreastPhysics} 实例（左右各一）和一个 {@link Breasts}
 * 形状参数对象；{@link #tickBreastPhysics} 是物理更新的入口，
 * 渲染层则通过各个 getter 读取数据。</p>
 */
public class EntityConfig {

    /**
     * 实体配置缓存，以实体 UUID 为键。
     *
     * <p>使用 Guava 的 {@link LoadingCache}：取不存在的键时会自动调用
     * {@code load} 创建新实例，调用方不需要处理「为空」的情况。</p>
     *
     * <p>{@code expireAfterAccess(5分钟)} 表示某个条目 5 分钟内没被访问过就自动清除，
     * 避免实体离开世界后配置对象一直占着内存。</p>
     */
    public static final LoadingCache<UUID, EntityConfig> CACHE = CacheBuilder.newBuilder()
          .expireAfterAccess(Duration.ofMinutes(5))
          .build(new CacheLoader<>() {
              @Override
              public @NotNull EntityConfig load(@NotNull UUID key) {
                  return new EntityConfig(key);
              }
          });

    /** MCA 的罩杯基因（0~1）映射到 FGM 罩杯范围（0~0.8）时使用的上限。 */
    private static final float MAX_BUST_SIZE = 0.8F;

    /**
     * 怀孕二次发育的倍率上限：自然生成的最大值还能再涨 25%。
     *
     * <p>所以罩杯的实际上限是 {@code MAX_BUST_SIZE × MAX_GROWTH = 1.0}，
     * 比 FGM 自己允许的 0.8 更大 —— 这正是「二次发育能突破自然上限」的体现。</p>
     */
    public static final float MAX_GROWTH = 1.25F;

    /**
     * 发育度基因的「未发育」基准值。
     *
     * <p>取 0.5 而不是 0，是因为 MCA 给新同步字段的默认值正是 0.5：
     * 旧存档里没有这个字段的村民会读到 0.5，正好对应 1.0 倍（未发育），
     * 不会因为本模组新增了字段而凭空变大。</p>
     */
    public static final float GROWTH_BASELINE = 0.5F;

    /**
     * 深度基因「完全不缩」的基准值。
     *
     * <p>深度基因的取值偏向 1（见 GeneticsMixin），这里取 0.8 作为基准：
     * 基因到达 0.8 时 Z 偏移量正好为 0 —— 完全不往身体里缩，
     * 此时几何盒也能保持最厚的 3 像素（见 GenderLayer#resizeBox 的厚度取整）。
     * 由于分布偏向 1，约有一半村民落在这条线以上，也就是完全不用缩。</p>
     *
     * <p>基准若取 1.0，则只有基因精确等于 1 时才不缩 —— 那是几乎不可能出现的取值，
     * 结果就是所有村民的盒子厚度被卡死在 2 像素、深度这一维完全拉不开差距。</p>
     */
    private static final float DEPTH_NO_INSET = 0.8F;

    /**
     * MCA 的女性性别枚举值。
     *
     * <p>两个模组各有一个同名的 {@code Gender} 类（本模组在
     * {@code com.mcabp.wildfire.main}，MCA 在 {@code net.conczin.mca.entity.ai.relationship}），
     * 本文件已 import 了前者，所以这里用全限定名缓存一份 MCA 的。</p>
     */
    private static final net.conczin.mca.entity.ai.relationship.Gender MCA_FEMALE =
            net.conczin.mca.entity.ai.relationship.Gender.FEMALE;

    // 实体唯一标识，同时也是缓存里的键
    public final UUID uuid;
    // 以下字段的初值都取自 ClientConfiguration 的默认值，
    // 这样即便从未被显式设置过，行为也与配置系统声明的默认值一致
    protected Gender gender = ClientConfiguration.GENDER.getDefault();
    // 罩杯大小，范围 0~0.8。MCA 村民由 syncFromVillager 每 tick 从基因同步，
    // 这里的初值只用于"尚未同步过"的短暂时刻
    protected float pBustSize = ClientConfiguration.BUST_SIZE.getDefault();

    // 注意：物理开关、弹跳强度、松软度属于「客户端本地参数」，
    // 存放在 GeneralClientConfig（config/mcabp-client.toml）里，由下面的 getter 直接读取，
    // 因此本类不再为它们保留字段——这样玩家在游戏内改设置能立即生效，无需重建实体配置。

    // 说话音高，用于替换受伤音效时的音调偏移
    protected float voicePitch = ClientConfiguration.VOICE_PITCH.getDefault();
    // 左右胸各持有一个独立的物理模拟实例。
    // 这一点很关键：两个实例各自维护位置、速度等状态，还有各自独立的随机数源，
    // 所以左右两侧的晃动天然可以不同步（双胸模式下更自然）。
    protected final BreastPhysics lBreastPhysics, rBreastPhysics;
    // 胸部的形状参数：单胸/双胸、乳沟、三个方向的偏移
    protected final Breasts breasts;
    // 是否渲染外套层（皮肤上那层夹克/衬衫）
    protected boolean jacketLayer = true;

    /**
     * 构造函数。声明为 {@code protected} 是刻意的：本类不允许被外部直接 new，
     * 只能通过 {@link #CACHE} 获取实例。
     */
    protected EntityConfig(UUID uuid) {
        this.uuid = uuid;
        this.breasts = new Breasts();
        // 注意把 this 传进去：物理实例需要反向读取本配置里的性别、罩杯等参数
        lBreastPhysics = new BreastPhysics(this);
        rBreastPhysics = new BreastPhysics(this);
    }

    /**
     * <h3>哪些实体享有胸部功能</h3>
     *
     * <p>这是整个模组的「准入开关」：只有返回 {@code true} 的实体，才会每 tick 被送去
     * 更新物理（见 {@code WildfireGenderClient#onEntityTick}），也才有机会被渲染层处理。</p>
     *
     * <p><b>这里只认 MCA（凡家物语）的村民</b>，判定依据是 MCA 自有的
     * {@link VillagerLike} 接口——普通村民与僵尸村民都实现了它。</p>
     *
     * <p>注意<b>不要</b>换成 {@code instanceof Villager} 之类的原版判断：MCA 的
     * {@code VillagerEntityMCA} 正是原版 {@code Villager} 的子类，那样会把原版村民
     * 一并放进来。同时玩家被有意排除——玩家的胸部由 Female Gender Mod 本体负责，
     * 两个模组共存时各管各的，避免同一个玩家被驱动两遍。</p>
     *
     * @return 该实体是否受支持
     */
    public static boolean isSupportedEntity(LivingEntity entity) {
        return entity instanceof VillagerLike<?>;
    }

    /**
     * 取得指定实体的配置对象，不存在时自动创建。
     *
     * <p>所有受支持的实体（玩家、盔甲架、以及后续接入的模组 NPC）统一走本类的
     * {@link #CACHE}。玩家专用的配置体系（落盘、云同步）已随玩家功能一并移除，
     * 因此不再区分两套缓存。</p>
     *
     * @return 该实体对应的 {@link EntityConfig}
     */
    @NotNull
    public static EntityConfig getEntity(@NotNull LivingEntity entity) {
        // getUnchecked：键不存在时直接创建，不抛 Guava 的 ExecutionException，
        // 因为 load() 里的实现只是简单 new，不会失败
        return CACHE.getUnchecked(entity.getUUID());
    }

    // ========== 以下是一组只读访问器，供物理层与渲染层读取配置 ==========

    public @NotNull Gender getGender() {
        return gender;
    }

    public @NotNull Breasts getBreasts() {
        return breasts;
    }

    public float getBustSize() {
        return pBustSize;
    }

    /** 胸部物理是否启用。取自客户端配置，玩家可在游戏内切换。 */
    public boolean hasBreastPhysics() {
        return GeneralClientConfig.INSTANCE.physicsEnabled.get();
    }

    // ---------- 物理与显示相关的开关，多数取自客户端配置 ----------

    /**
     * 是否忽略盔甲对物理的影响。
     *
     * <p>取自客户端配置：开启后无视胸甲的物理抗性，穿甲也照常晃。</p>
     */
    public boolean getArmorPhysicsOverride() {
        return GeneralClientConfig.INSTANCE.armorPhysicsOverride.get();
    }

    /**
     * 是否可以「呼吸」（决定要不要播放胸部的呼吸起伏动画）。
     *
     * <p>基类恒定返回 {@code false}，即只有玩家才会有呼吸动画。
     * 这也是为什么盔甲架和模组 NPC 的胸部是完全静止的。</p>
     */
    public boolean canBreathe() {
        return false;
    }

    /**
     * 是否允许穿着盔甲时也显示胸部。
     *
     * <p>取自客户端配置：关闭后，穿着会覆盖胸部的胸甲时胸部不再渲染。</p>
     */
    public boolean showBreastsInArmor() {
        return GeneralClientConfig.INSTANCE.showInArmor.get();
    }

    /** 晃动幅度。取自客户端配置，影响受击/跳跃等外力造成的晃动有多剧烈。 */
    public float getBounceMultiplier() {
        return GeneralClientConfig.INSTANCE.bounceMultiplier.get().floatValue();
    }

    /**
     * 晃动时长。取自客户端配置 —— 值越大阻尼越小、晃动衰减越慢。
     *
     * <p>方法名沿用 FGM 的 floppiness，界面上显示为「晃动时长」。</p>
     */
    public float getFloppiness() {
        return GeneralClientConfig.INSTANCE.floppyMultiplier.get().floatValue();
    }

    public float getVoicePitch() {
        return this.voicePitch;
    }

    public @NotNull BreastPhysics getLeftBreastPhysics() {
        return lBreastPhysics;
    }

    public @NotNull BreastPhysics getRightBreastPhysics() {
        return rBreastPhysics;
    }

    /**
     * 仅在 {@link ArmorStand 盔甲架}上使用：返回给盔甲架穿上胸甲的那个玩家，
     * 当时是否开启了皮肤的外套层（夹克/衬衫）。
     */
    public boolean hasJacketLayer() {
        return jacketLayer;
    }

    /**
     * <h3>物理更新的入口（每 tick 调用一次）</h3>
     *
     * <p>抛去盔甲查找的部分，本方法只有两行：分别更新左右两侧的物理实例。
     * 之所以要分两次，是因为左右胸各有独立的 {@link BreastPhysics}，
     * 各自持有自己的速度、位置和随机数源，这样双胸模式下两侧的晃动才能不同步。</p>
     *
     * <p>调用来源：{@code WildfireGenderClient#onEntityTick}，
     * 且该方法只在<b>客户端</b>触发（渲染是纯客户端行为，不需要服务端参与）。</p>
     *
     * @param entity 要更新物理的实体
     */
    public void tickBreastPhysics(@NotNull LivingEntity entity) {
        // MCA 村民的外观参数存放在它自己的基因系统里，每 tick 同步过来
        if (entity instanceof VillagerLike<?> villager) {
            syncFromVillager(villager);
        }
        // 从胸甲槽位取物品，查出它的性别盔甲配置（紧度、抗性、贴图等）
        IGenderArmor armor = WildfireHelper.getArmorConfig(entity.getItemBySlot(EquipmentSlot.CHEST));

        getLeftBreastPhysics().update(entity, armor);
        getRightBreastPhysics().update(entity, armor);
    }

    /**
     * 把 MCA 村民的性别与各项胸部基因同步到本配置对象。
     *
     * <p>MCA 的 {@code Genetics.BREAST} 基因是一个 {@code [0,1]} 的浮点数，
     * 并且 {@code Genetics#getBreastSize()} 在非女性时直接返回 0；而 FGM 的罩杯参数
     * 范围是 {@code [0, 0.8]}（见 {@code ClientConfiguration.BUST_SIZE}），
     * 因此这里乘 {@link #MAX_BUST_SIZE} 做一次归一化。</p>
     *
     * <p>再乘上年龄成长系数（见 {@link #chestGrowthByAge}），把「什么时候开始发育」
     * 这件事交给年龄段控制：儿童及以前完全不发育，进入青春期后才从零开始长。</p>
     *
     * <p>另外 4 个外观参数（分离 / 高度 / 深度 / 旋转）由 {@link MCAVillagerGenes}
     * 提供，同样是 {@code [0,1]} 的基因值，需要线性映射到 FGM 各自的合法区间，
     * 映射表见 {@link MCAVillagerGenes} 的类注释。</p>
     *
     * <p>方向是单向的——只从 MCA 读、不写回 MCA。基因是服务端权威数据，
     * 客户端若反向写入会造成多人游戏中的不同步。</p>
     */
    private void syncFromVillager(@NotNull VillagerLike<?> villager) {
        Genetics genetics = villager.getGenetics();
        this.gender = genetics.getGender() == MCA_FEMALE ? Gender.FEMALE : Gender.MALE;
        float gene = genetics.getBreastSize();
        float ageFactor = chestGrowthByAge(villager);
        // 怀孕带来的二次发育倍率：未发育时是 1.0，最多可累积到 MAX_GROWTH
        float growth = growthMultiplier(genetics.getGene(MCAVillagerGenes.BREAST_GROWTH));
        this.pBustSize = Mth.clamp(gene * ageFactor * MAX_BUST_SIZE * growth, 0F, MAX_BUST_SIZE * MAX_GROWTH);

        // 4 个外观参数各自独立映射到 FGM 的偏移量区间，彼此之间没有关联。
        // 映射逻辑全部收在下面那组静态方法里，编辑器界面显示数值时复用同一套公式，
        // 避免 GUI 和渲染各写一份、日后改公式时对不上。
        breasts.updateXOffset(mapSeparation(genetics.getGene(MCAVillagerGenes.SEPARATION)));
        breasts.updateYOffset(mapHeight(genetics.getGene(MCAVillagerGenes.HEIGHT)));
        breasts.updateZOffset(mapDepth(genetics.getGene(MCAVillagerGenes.DEPTH)));
        breasts.updateCleavage(mapCleavage(genetics.getGene(MCAVillagerGenes.ROTATION)));

        // 左右独立晃动：开启时两侧各自独立模拟（uniboob = false），关闭时共用同一套物理数据。
        // 注意与 FGM 的命名是反的——那边的 uniboob 表示「合成一坨」，所以这里要取反。
        breasts.updateUniboob(!GeneralClientConfig.INSTANCE.dualPhysicsEnabled.get());
    }

    /**
     * <h3>年龄成长系数：什么时候开始发育</h3>
     *
     * <p>返回一个 {@code [0,1]} 的倍率，乘在罩杯上，可以把它理解成「发育进度」。</p>
     *
     * <table border="1">
     *   <caption>各年龄段的系数</caption>
     *   <tr><th>年龄段</th><th>系数</th></tr>
     *   <tr><td>婴儿 / 幼儿 / 儿童</td><td>0（完全不发育）</td></tr>
     *   <tr><td>青春期</td><td>从 0 平滑长到 1</td></tr>
     *   <tr><td>成年</td><td>1</td></tr>
     * </table>
     *
     * <h4>为什么不直接用 VillagerDimensions#getBreasts()</h4>
     * <p>MCA 自己也算了一个胸部系数，但它是<b>插值</b>过的：{@code VillagerEntityMCA} 每 tick 调用
     * {@code dimensions.interpolate(当前档, 下一档, 段内进度)}，而 {@code AgeState} 各档的值是
     * 婴儿/幼儿/儿童 0、青春期 0.5、成年 1 —— 于是儿童段的系数算出来是
     * {@code lerp(进度, 0, 0.5)}，<b>儿童期还没结束就已经涨到 0.5 了</b>，
     * 表现就是「儿童就开始发育」。这不是想要的效果。</p>
     *
     * <p>MCA 这么做是为了让村民的体型（身高、宽度）连续变化，对胸部而言却把起点提前了。
     * 所以这里绕开它，按年龄段自己给系数：儿童及以前一律 0，进入青春期才开始长。</p>
     *
     * <h4>青春期那一段怎么算</h4>
     * <p>直接取档位值（0.5）会让刚进入青春期的村民胸部突然出现，所以仍用 MCA 插值出来的原始值，
     * 把 {@code [青春期档, 成年档]} 这一段<b>重新归一化</b>成 {@code [0, 1]} ——
     * 相当于把 MCA 的曲线整体后移，起点落在青春期开头，终点仍在成年。</p>
     *
     * <p>这样三段之间都是连续的：儿童期恒为 0，青春期从 0 涨到 1，成年接住这个 1。</p>
     */
    private static float chestGrowthByAge(@NotNull VillagerLike<?> villager) {
        AgeState state = villager.getAgeState();

        // 婴儿 / 幼儿 / 儿童（以及年龄还没分配的）一律不发育
        if (state.ordinal() <= AgeState.CHILD.ordinal()) {
            return 0F;
        }

        // 成年：满值
        if (state != AgeState.TEEN) {
            return state.getBreasts();
        }

        // 青春期：把原始插值结果从 [青春期档, 成年档] 映射回 [0, 1]
        float raw = villager.getVillagerDimensions().getBreasts();
        return Mth.clamp(Mth.inverseLerp(raw, AgeState.TEEN.getBreasts(), AgeState.ADULT.getBreasts()), 0F, 1F);
    }

    // ==================== 基因 → 物理量的映射 ====================
    // 下面这组方法把基因值换算成 FGM 实际使用的参数。5 个参数彼此独立，没有任何关联。
    // 渲染流程与编辑器界面（显示数值）共用同一套公式，保证两边永远不会对不上。

    /**
     * 罩杯大小：基因（0~1）乘上 FGM 的上限，再乘二次发育的倍率。
     *
     * <p>这里不含年龄系数 —— 年龄是独立的一维，渲染时才会额外乘上去。</p>
     *
     * @param growth 发育倍率，由 {@link #growthMultiplier} 从发育度基因换算而来；
     *               未发育时传 1.0
     */
    public static float mapBustSize(float bustGene, float growth) {
        return Mth.clamp(bustGene * MAX_BUST_SIZE * growth, 0F, MAX_BUST_SIZE * MAX_GROWTH);
    }

    /**
     * 分离 → FGM 的 X 偏移量。
     *
     * <p>映射时取了反号：FGM 的 X 偏移语义是「正值让两胸靠得更近、负值让它们分开」，
     * 而滑块叫「分离」，所以往右拖（基因变大）必须映射成负值，才符合名字的直觉。</p>
     */
    public static float mapSeparation(float gene) {
        return Mth.clamp((0.5F - gene) * 2F, -1F, 1F);
    }

    /**
     * 高度 → FGM 的 Y 偏移量（0.5 对应默认高度）。
     */
    public static float mapHeight(float gene) {
        return Mth.clamp((gene - 0.5F) * 2F, -1F, 1F);
    }

    /**
     * 深度 → FGM 的 Z 偏移量。
     *
     * <p>FGM 的约定是 0 表示不缩、负值表示往身体里缩，所以基因越大偏移量越接近 0。
     * 基准点取 {@link #DEPTH_NO_INSET}（基因的上界）而不是 1，
     * 这样基因到顶时正好落在 0，也就是完全不缩。</p>
     */
    public static float mapDepth(float gene) {
        return Mth.clamp(gene - DEPTH_NO_INSET, -1F, 0F);
    }

    /**
     * 旋转 → FGM 的乳沟参数（0~0.1）。
     *
     * <p>渲染时这个值会再乘以 100 换算成 0~10 度，编辑器里显示度数用的是同一个换算。</p>
     */
    public static float mapCleavage(float gene) {
        return Mth.clamp(gene * 0.1F, 0F, 0.1F);
    }

    /**
     * 发育度基因 → 倍率。
     *
     * <p>基准值 {@link #GROWTH_BASELINE} 对应 1.0 倍（未经二次发育），基因到 1.0 时达到
     * {@link #MAX_GROWTH}。低于基准的部分一律按 1.0 处理 —— 繁衍时的基因混合有可能把值
     * 拉到基准以下，那种情况不该让胸部反而缩小。</p>
     */
    public static float growthMultiplier(float growthGene) {
        float above = Math.max(0F, growthGene - GROWTH_BASELINE);
        return 1F + above / (1F - GROWTH_BASELINE) * (MAX_GROWTH - 1F);
    }

    /**
     * 倍率 → 发育度基因，{@link #growthMultiplier} 的逆运算。
     * 供「怀孕时增长」的逻辑把算好的新倍率写回基因。
     */
    public static float growthGene(float multiplier) {
        float clamped = Mth.clamp(multiplier, 1F, MAX_GROWTH);
        return GROWTH_BASELINE + (clamped - 1F) / (MAX_GROWTH - 1F) * (1F - GROWTH_BASELINE);
    }

    /**
     * <h3>按需刷新（供渲染层使用）</h3>
     *
     * <p>正常在世界里的村民每 tick 都会被 {@code WildfireGenderClient#onEntityTick} 驱动，
     * 基因与物理始终是最新的。但有一类实体收不到 tick —— 典型例子是村民编辑器里的
     * <b>预览实体</b>：它由 MCA 单独创建、并不在世界中，{@code EntityTickEvent} 永远不会轮到它，
     * 于是基因读不到、物理停在初值，表现就是「编辑器里看不到胸部」。</p>
     *
     * <p>渲染层在发现该实体的物理从未跑过时会调用本方法补一次同步，代价只是几次基因读取。</p>
     */
    public void refreshForRender(@NotNull LivingEntity entity) {
        if (entity instanceof VillagerLike<?> villager) {
            syncFromVillager(villager);
        }
    }

    @Override
    public String toString() {
        return "%s(uuid=%s, gender=%s)".formatted(getClass().getCanonicalName(), uuid, gender);
    }
}
