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

package com.mcabp.wildfire.physics;

import com.mcabp.wildfire.api.IGenderArmor;
import com.mcabp.wildfire.main.entitydata.EntityConfig;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.phys.Vec3;

public class BreastPhysics {//创建一个叫BreastiPhysics的对象

    private final RandomSource random = RandomSource.create();
    private final EntityConfig entityConfig;

    private float bounceVelX = 0,   // X 轴当前位置（积分主状态）
                  targetBounceX = 0, // X 轴的弹簧目标位置，每 tick 由受力重新计算
                  velocityX = 0,     // X 轴速度（一阶状态量）
                  positionX,         // X 轴对外输出位置，等于 bounceVelX
                  prePositionX;      // X 轴上一 tick 的输出位置，仅供渲染插值

    // =====================================================================
    //  Y 轴（纵向）状态
    //  Y 轴是主要的自由度：上下跳跃、走路起伏、重力下垂都体现在这里。
    //  在渲染时 Y 值同时被换算成"前倾俯仰角"（见 GenderLayer）。
    // =====================================================================
    private float bounceVel = 0,   // Y 轴当前位置（积分主状态），有效范围约 [-0.5, 1.5]
                  targetBounceY = 0, // Y 轴的弹簧目标位置，每 tick 由受力重新计算，最终钳制到 [-1.5, 2.5]
                  velocity = 0,     // Y 轴速度（一阶状态量）
                  positionY,        // Y 轴对外输出位置，等于被硬边界钳制后的 bounceVel
                  prePositionY;     // Y 轴上一 tick 的输出位置，仅供渲染插值

    // =====================================================================
    //  旋转状态
    //  绕 Y 轴的自旋，表现转身时的惯性甩动（例如快速转身时胸部会"甩"向一侧）。
    //  单位是角度（degree），渲染时会被转换成弧度。
    // =====================================================================
    private float bounceRotVel = 0,     // 旋转当前值（积分主状态）
                  targetRotVel = 0,     // 旋转的弹簧目标值，每 tick 由受力重新计算，最终钳制到 [-25, 25]
                  rotVelocity = 0,      // 旋转速度（一阶状态量）
                  wfg_bounceRotation,   // 旋转对外输出值，等于 bounceRotVel；wfg_ 前缀为历史命名遗留
                  wfg_preBounceRotation; // 旋转上一 tick 的输出值，仅供渲染插值

    // =====================================================================
    //  乳房大小状态
    // =====================================================================
    /**
     * 当前乳房大小（0 ~ 1 左右的归一化值）。
     * <p>它不会瞬间跳到目标值，而是每 tick 向目标靠近一半，实现"穿衣/脱衣时大小渐变"的过渡效果。
     * 该值同时影响物理量级（{@code bounceIntensity} 与它成正比）与渲染尺寸。</p>
     */
    private float breastSize = 0,   // 上一 tick 的大小存于 preBreastSize，两者共同用于渲染插值
                  preBreastSize = 0;

    // =====================================================================
    //  帧间差分用的历史状态（用于检测"事件"而非持续状态）
    // =====================================================================
    /** 上一 tick 的实体姿势，用于检测蹲下/躺下的"瞬间切换"。 */
    private Pose lastPose;

    private int lastSwingDuration = 6, // 上一 tick 的手臂挥动总时长（tick），用于判断"急迫/挖掘疲劳"状态
                lastSwingTick = 0;     // 上一 tick 的手臂挥动进度（tick），用于检测挥臂动画是否被中途打断

    /** 上一 tick 实体的世界坐标，用差分求出本 tick 的位移向量 {@code motion}。 */
    private Vec3 prePos;

    /**
     * MCA 村民的典型行走速度参考值，用于把行走起伏的幅度换算到「玩家尺度」。
     *
     * <p>{@code entity.walkAnimation.speed()} 是一个归一化值，约等于实体每 tick 的水平移动距离：
     * 玩家接近 1.0，而实测 MCA 村民只有约 0.26。FGM 的行走起伏公式直接把它当幅度乘数使用，
     * 因此同一个公式在村民身上表现弱得多（约为玩家的四分之一），肉眼几乎看不出晃动。</p>
     *
     * <p>这里以村民的典型行走速度作为换算基准：村民按 0.26 的速度行走时归一化后得到 1.0，
     * 与玩家的手感对齐；走得快时晃动自动增强、走得慢时自动减弱。
     * 若日后 MCA 调整了村民移动速度，只需更新这一个常量。</p>
     */
    private static final float WALK_SPEED_REFERENCE = 0.26F;

    /**
     * 起跳/落地时胸部倾斜的随机方向，取值 {@code +1} 或 {@code -1}。
     * <p>每次检测到垂直速度由负转正（起跳）或由负转零（落地）时重新随机，
     * 避免每次跳跃都往同一个方向偏。</p>
     */
    private int randomB = 1;

    /** 上一 tick 的垂直速度，用于检测起跳（负→正）与落地（负→零）的瞬间。 */
    private double lastVerticalMoveVelocity;

    /**
     * @param entityConfig 该物理实例所属实体的配置对象，提供大小/性别/弹力/松软度等参数
     */
    public BreastPhysics(EntityConfig entityConfig) {
        this.entityConfig = entityConfig;
    }

    /**
     * 推进一个 tick 的物理模拟。这是本类唯一的对外入口。
     *
     * <p>整个方法可以拆成五个阶段：<b>快照 → 受力累加 → 边界回弹 → 阻尼积分 → 硬边界钳制</b>。
     * 方法内所有对 {@code targetBounceY / targetBounceX / targetRotVel} 的写入都发生在
     * 受力累加与边界回弹阶段，积分阶段只读取这些目标值。</p>
     *
     * <p>关于三个自由度的分工：</p>
     * <ul>
     *   <li><b>Y 轴</b>：受力来源最多（位移、重力、走路、姿势、载具、挥臂），是晃动的主要表现。</li>
     *   <li><b>X 轴</b>：主要由转身角速度的 1/10 以及挥臂抖动驱动，表现左右甩动。</li>
     *   <li><b>旋转</b>：由转身角速度、起跳方向随机量、挥臂反向补偿共同驱动。</li>
     * </ul>
     *
     * @param entity 被模拟的实体。注意本方法<b>只在客户端调用</b>，且应保证该实体的
     *               {@link EntityConfig#isSupportedEntity} 返回 {@code true}
     * @param armor  实体胸甲槽位对应的性别盔甲配置，用于按护甲紧度/抗性削弱晃动
     *
     * @apiNote Only call on the client
     */
    public void update(LivingEntity entity, IGenderArmor armor) {
        // TODO there's a lot of unused code here; this function should also ideally be broken up to make reading
        // 		through it not as much of a chore (especially the vehicle physics)

        // =================================================================
        //  阶段 0：盔甲架特例——不做任何动态模拟，直接给出一组静态值并返回
        //  盔甲架不会自己移动，配置完全来自胸甲上附带的 NBT（见 EntityConfig#readFromStack）。
        // =================================================================
        //always suppress the full physics calculations on armor stands
        if (entity instanceof ArmorStand) {
            if (entityConfig.getGender().canHaveBreasts()) {
                // 有胸部的性别：使用配置中的大小，并按护甲紧度最多缩小 15%
                this.breastSize = entityConfig.getBustSize();
                if (!entityConfig.getArmorPhysicsOverride() && armor.coversBreasts()) {
                    float tightness = Mth.clamp(armor.tightness(), 0, 1);
                    this.breastSize *= 1 - 0.15F * tightness;
                }
                // pre 与当前值保持一致，渲染插值时不会产生任何过渡动画
                this.preBreastSize = this.breastSize;
            } else {
                // 不该有胸部的性别（如 MALE）：直接置零
                this.preBreastSize = this.breastSize = 0f;
            }
            return;
        }

        // =================================================================
        //  阶段 1：快照
        //  把上一 tick 的结果保存到 pre* 字段，供渲染阶段按 partialTicks 做线性插值。
        //  逻辑每秒只跑 20 次，而渲染帧率通常远高于此，插值可避免画面卡顿/抖动。
        // =================================================================
        this.prePositionY = this.positionY;
        this.prePositionX = this.positionX;
        this.wfg_preBounceRotation = this.wfg_bounceRotation;
        this.preBreastSize = this.breastSize;

        // 首次调用时没有历史坐标，无法计算位移；先记录初始位置并跳过本 tick
        if (this.prePos == null) {
            this.prePos = entity.position();
            return;
        }

        // =================================================================
        //  阶段 2：受力累加
        // =================================================================

        // --- 2.1 基础量：重量与目标大小 -----------------------------------
        // breastWeight 代表乳房自身的"重量"，作为一个恒定的向下偏移量叠加到目标值上，
        // 使胸部在静止时也呈现自然下垂，而不是完全贴平。
        // 系数 1.25 让重量对晃动的影响略大于大小本身。
        float breastWeight = entityConfig.getBustSize() * 1.25f;
        float targetBreastSize = entityConfig.getBustSize();

        // --- 2.2 按性别与护甲修正目标大小 --------------------------------
        if (!entityConfig.getGender().canHaveBreasts()) {
            // 该性别不应有胸部，目标大小直接归零
            targetBreastSize = 0;
        } else if (!entityConfig.getArmorPhysicsOverride() && armor.coversBreasts()) { //skip resistance if physics is overridden
            // 护甲覆盖胸部且未开启"物理覆盖"时：护甲越紧，视觉尺寸越小（最多缩小 15%）
            float tightness = Mth.clamp(armor.tightness(), 0, 1);
            //Scale breast size by how tight the armor is, clamping at a max adjustment of shrinking by 0.15
            targetBreastSize *= 1 - 0.15F * tightness;
        }

        // --- 2.3 大小平滑过渡 --------------------------------------------
        // 每 tick 向目标值靠近"差值的一半"，形成类似指数缓动的渐变效果。
        // 这样玩家换装（穿上/脱下护甲）或改设置时，尺寸是平滑变化的而非瞬间跳变。
        // 三元表达式分别处理"变大"和"变小"两种情况，确保方向正确。
        breastSize += (breastSize < targetBreastSize) ? Math.abs(breastSize - targetBreastSize) / 2f : -Math.abs(breastSize - targetBreastSize) / 2f;

        // --- 2.4 求本 tick 的世界坐标位移 ---------------------------------
        // motion 是实体位置的一阶差分，即"这一 tick 移动了多远"。
        // 注意这里用的是位置差而非 getDeltaMovement()，因此包含传送、推挤等被动位移。
        Vec3 motion = entity.position().subtract(this.prePos);
        this.prePos = entity.position();

        // --- 2.5 晃动强度 bounceIntensity --------------------------------
        // 晃动强度与乳房大小成正比（越大的胸部晃动越明显），再乘以玩家设置的弹力倍率。
        // 倍率被 round 到小数点后两位，避免浮点误差造成每 tick 的微小抖动。
        float bounceIntensity = (targetBreastSize * 3f) * Math.round((entityConfig.getBounceMultiplier() * 3) * 100) / 100f;
        if (!entityConfig.getArmorPhysicsOverride() && armor.coversBreasts()) {
            //skip resistance if physics is overridden or the breasts are not covered
            // 护甲的抗性（physicsResistance）线性削弱晃动强度：抗性 1 表示完全固定不动
            float resistance = Mth.clamp(armor.physicsResistance(), 0, 1);
            //Adjust bounce intensity by physics resistance of the worn armor
            bounceIntensity *= 1 - resistance;
        }

        if (!entityConfig.getBreasts().isUniboob()) {
            //TODO - 1.21: Do we want to try and make it so that the max is inclusive?
            // 左右独立物理时，给强度乘一个 [0.5, 1.5) 的随机倍率。
            // 这使两侧的晃动幅度与相位产生差异，看起来更自然；单胸模式则跳过，保证完全同步。
            bounceIntensity = bounceIntensity * Mth.randomBetween(random, 0.5f, 1.5f);
        }

        // --- 2.6 起跳/落地时随机选择倾斜方向 ------------------------------
        double vertVelocity = entity.getDeltaMovement().y;
        // Randomize which side the breast will angle toward when the player jumps/has upward velocity applied to them,
        // or stops falling
        // 两种触发条件：
        //   ① 上一 tick 未上升(<=0) 而本 tick 上升(>0) —— 起跳/被击飞
        //   ② 上一 tick 在下落(<0) 而本 tick 垂直速度归零 —— 落地
        if ((lastVerticalMoveVelocity <= 0 && vertVelocity > 0) || (lastVerticalMoveVelocity < 0 && vertVelocity == 0)) {
            randomB = random.nextBoolean() ? -1 : 1;
        }
        lastVerticalMoveVelocity = vertVelocity;

        // --- 2.7 累加各受力源到目标值 ------------------------------------

        // ① 身体上下位移：注意模型空间中 +Y 朝下，且这里模拟的是"惯性滞后"——
        //    身体向上移动（motion.y > 0）时胸部跟不上，相对身体下沉，因此目标值取正值
        this.targetBounceY = (float) motion.y * bounceIntensity;
        // ② 自身重量：恒定正值（即向下）偏移，使静止时也保持自然下垂
        this.targetBounceY += breastWeight;

        // ③ 旋转：由转身角速度决定（calcRotation 返回负值，表示"往回转"）
        this.targetRotVel = calcRotation(entity, bounceIntensity);
        // ④ 旋转补偿：上下位移时额外施加一个随机方向的倾斜，让跳跃动作更生动
        this.targetRotVel += (float) motion.y * bounceIntensity * randomB;

        // ⑤ X 轴：转身惯性很小，取旋转量的 1/10 并取反
        this.targetBounceX = -calcRotation(entity, bounceIntensity) / 10f;

        // --- 2.8 走路起伏 -------------------------------------------------
        // 用行走动画的相位（cos 波形）产生周期性上下摆动。
        // walkAnimation.position() 是累计行走距离，乘 0.6662 后与腿摆动频率对齐；加 π 使其与腿部反相。
        // 分母 f 是"速度衰减因子"：实体移动越快，f 越大，摆动幅度越小——
        // 避免快速移动时叠加上剧烈晃动导致视觉混乱。
        float f = (float) entity.getDeltaMovement().lengthSqr() / 0.2F;
        f = f * f * f;

        if (f < 1.0F) {
            f = 1.0F;
        }

        // 行走起伏的幅度与行走速度成正比：把 speed() 按 {@link #WALK_SPEED_REFERENCE} 归一化，
        // 使 MCA 村民与玩家的晃动观感一致（详见该常量的说明）。
        this.targetBounceY += Mth.cos(entity.walkAnimation.position() * 0.6662F + (float) Math.PI)
                * 0.5F * (entity.walkAnimation.speed() / WALK_SPEED_REFERENCE) * 0.5F / f;
        //WildfireGender.logger.debug("Rotation yaw: {}", plr.rotationYaw);

        // --- 2.9 姿势切换（蹲下 / 躺下）----------------------------------
        // 只在姿势发生"变化的那一 tick"施加冲量，而不是持续施加，
        // 否则长时间蹲着会让目标值一直被推高，位置长期贴在边界上。
        Pose pose = entity.getPose();
        if (pose != lastPose) {
            if (pose == Pose.CROUCHING || lastPose == Pose.CROUCHING) {
                // 蹲下或站起：向下压一下（正方向）
                this.targetBounceY += bounceIntensity;
            } else if (pose == Pose.SLEEPING || lastPose == Pose.SLEEPING) {
                // 躺下或起身：直接设为固定值（而非叠加），避免与其它受力源冲突
                this.targetBounceY = bounceIntensity;
            }
            lastPose = pose;
        }

        // --- 2.10 载具特化物理 --------------------------------------------
        // 不同载具的运动特征差异极大（划船是周期性划桨、矿车是随机颠簸、
        // 骑猪/马是步伐节奏、炽足兽是身体起伏），因此各自特化处理。
        // 这些分支会覆盖或叠加前面算出的 targetBounceY。
        //button option for extra entities
        switch (entity.getVehicle()) {
            case Boat boat -> {
                // 划船：用两侧船桨的划水相位判断何时处于"划桨下压"时刻
                int rowTime = (int) boat.getRowingTime(0, entity.walkAnimation.position());
                int rowTime2 = (int) boat.getRowingTime(1, entity.walkAnimation.position());

                // 把桨的相位映射成两个旋转角度：左桨在 [-60°, -15°]、右桨在 [-45°, 45°] 之间往复
                float rotationL = (float) Mth.clampedLerp(-(float) Math.PI / 3F, -0.2617994F, (double) ((Mth.sin(-rowTime2) + 1.0F) / 2.0F));
                float rotationR = (float) Mth.clampedLerp(-(float) Math.PI / 4F, (float) Math.PI / 4F, (double) ((Mth.sin(-rowTime + 1.0F) + 1.0F) / 2.0F));
                //WildfireGender.logger.debug("{}, {}", rotationL, rotationR);
                // 任一桨划到低位时，胸部被向上顶起（除以 3.25 削弱幅度）
                if (rotationL < -1 || rotationR < -0.6f) {
                    this.targetBounceY = bounceIntensity / 3.25f;
                }
            }
            case Minecart cart -> {
                // 矿车：在铁轨上随机颠簸。速度越快，触发颠簸的概率越高
                float speed = (float) cart.getDeltaMovement().lengthSqr();
                // random.nextDouble() * speed < 0.5 表示触发概率随速度上升；speed > 0.2 才启动
                if (random.nextDouble() * speed < 0.5f && speed > 0.2f) {
                    this.targetBounceY = bounceIntensity / 6f;
                    // 随机决定这次颠簸是向上还是向下
                    if (random.nextBoolean()) {
                        this.targetBounceY = -this.targetBounceY;
                    }
                    this.targetBounceY += breastWeight;
                }
            }
            case AbstractHorse horse -> {
                // 骑乘马/驴等：按步伐节奏周期性颠簸。
                // clampMovement 把移动速度映射成"每 N tick 触发一次"，速度越快 N 越小（颠簸越频繁）
                float movement = (float) horse.getDeltaMovement().length();
                if (horse.tickCount % clampMovement(movement) == 5 && movement > 0.05f) {
                    this.targetBounceY = bounceIntensity / 4f;
                    this.targetBounceY += breastWeight;
                }
            }
            case Pig pig -> {
                // 骑猪：与马类似，但触发阈值更低（猪通常移动较慢），
                // 且幅度额外乘以一个随速度增长的系数，让快跑时的颠簸更明显
                float movement = (float) pig.getDeltaMovement().length();
                if (pig.tickCount % clampMovement(movement) == 5 && movement > 0.002f) {
                    this.targetBounceY = (bounceIntensity * Mth.clamp(movement * 75, 0.1f, 1f)) / 4f;
                    this.targetBounceY += breastWeight;
                }
            }
            case Strider strider -> {
                // 炽足兽：身体本身会上下起伏，因此这里不做"脉冲"而是持续跟随其实际高度偏移。
                // heightOffset 大致等于 (碰撞箱高度 - 0.19) 再加上行走动画带来的波动
                double heightOffset = (double) strider.getBbHeight() - 0.19
                                      + (double) (0.12F * Mth.cos(strider.walkAnimation.position() * 1.5f)
                                                  * 2F * Math.min(0.25F, strider.walkAnimation.speed()));
                // 减去 4.5 是为了把基准点对齐到正常站立高度，差值再乘以强度
                this.targetBounceY += ((float) (heightOffset * 3f) - 4.5f) * bounceIntensity;
            }
            case null, default -> {
                // 未骑乘任何载具，或是不支持的载具类型：不做额外处理
            }
        }

        // --- 2.11 挥动手臂（挖掘/攻击）引起的抖动 -------------------------
        // 这是最复杂的一段：手臂挥动会带动上半身晃动，而挥动速度又受"急迫/挖掘疲劳"影响，
        // 因此需要先根据挥动时长推导出一个"放大系数"。
        int swingDuration = entity.getCurrentSwingDuration();
        // Require that either the current swing duration is 2 ticks, or the swing duration from the previous tick is,
        // as any faster and the arm effectively doesn't swing at all; we check the previous tick's swing duration for
        // reasons explained later on in this block
        // 挥动时长 <= 1 tick 时手臂实际上几乎不动，跳过；躺下时也不处理。
        // 同时检查上一 tick 的时长，是为了处理"挥动被中途重置"的边界情况。
        if ((swingDuration > 1 || lastSwingDuration > 1) && pose != Pose.SLEEPING) {
            // rawAmplifier：以 6 tick 为基准的偏差量。
            // 挥动越快（< 6 tick，如急迫效果）为正，越慢（> 6 tick，如挖掘疲劳）为负。
            float rawAmplifier = 0f;
            if (swingDuration < 6) {
                rawAmplifier = 0.15f * (6 - swingDuration);
            } else if (swingDuration > 6) {
                rawAmplifier = -0.055f * (swingDuration - 6);
            }
            // Cap our amplifier at the swing durations of Mining Fatigue IV/Haste II
            // 把放大系数限制在 [0.6, 1.3]，对应"挖掘疲劳 IV ~ 急迫 II"这一合理区间
            float amplifier = Mth.clamp(1 + rawAmplifier, 0.6f, 1.3f);

            // 判断当前挥动的是哪只手（主手挥动则取主手，否则取另一侧）
            HumanoidArm swingingArm = entity.swingingArm == InteractionHand.MAIN_HAND ? entity.getMainArm() : entity.getMainArm().getOpposite();
            // 挥动进度相对上一 tick 的变化量；为负说明挥动动画被重置（打断）
            int swingTickDelta = entity.swingTime - lastSwingTick;
            // 用"距区间中点的距离"衡量上一 tick 处在挥动动画的前半段还是后半段：
            // 正值 = 后半段（手臂正在回摆），负值 = 前半段（手臂正在挥出）
            float swingProgress = distanceFromMedian(0, lastSwingDuration, Mth.clamp(lastSwingTick, 0, lastSwingDuration));
            // 由此推断手臂当前的运动方向，用于决定旋转补偿的符号
            HumanoidArm swingingToward = swingProgress > -0.2f ? swingingArm.getOpposite() : swingingArm;

            // consistently apply even with short swing durations, such as with haste
            // 每 N tick 施加一次抖动，N 由挥动时长决定，保证高攻速下也能稳定触发
            int everyNthTick = Mth.clamp(swingDuration - 1, 1, 5);
            if (entity.swinging && entity.tickCount % everyNthTick == 0) {
                // Y 轴抖动：方向随机，避免连续挥动看起来像固定抖动
                float amplifiedBounce = 0.25f * amplifier * bounceIntensity;
                if (random.nextBoolean()) {
                    this.targetBounceY -= amplifiedBounce;
                } else {
                    this.targetBounceY += amplifiedBounce;
                }
                // The regular amplifier here makes this look relatively unnatural at high levels of mining fatigue,
                // so instead we're increasing the potency of negative amplifiers (and decreasing positive amplifiers),
                // and clamping this at a lower range than normal.
                // The effective range of these numbers is around the swing durations of Mining Fatigue V to Haste II.
                // X 轴抖动：负向系数（挖掘疲劳）被放大 1.625 倍，正向（急迫）被压缩到 0.8 倍。
                // 原因是直接用 amplifier 在高挖掘疲劳下会显得很违和，因此单独做了非对称处理并收紧钳制范围。
                var xAmp = Mth.clamp(1 + (rawAmplifier * (rawAmplifier < 0 ? 1.625f : 0.8f)), 0.25f, 1.225f);
                // 往主手相反方向甩，因此右手挥动时取负值
                this.targetBounceX = (0.325f * xAmp * bounceIntensity) * (swingingArm == HumanoidArm.RIGHT ? -1f : 1f);
            }

            if (swingTickDelta < 0 && lastSwingTick != lastSwingDuration - 1) {
                // Add a bit of counter-rotation back toward the currently swinging arm if the previous arm swing
                // animation is interrupted
                // Note that we don't check if the player's arm is currently swinging here to account for cases like
                // haste being used to reset a player's swing; one notable example of this is Wynncraft's spell casting,
                // which applies haste to the player when a spell is successfully cast.
                // 挥动被中途打断（例如服务器重置了挥动进度）时，补一个回正的旋转量，
                // 避免胸部停在偏斜状态。这里刻意不检查 entity.swinging，
                // 因为"急迫重置挥动"（如某些服务器的施法机制）也属于这类情况。
                this.targetRotVel += (swingingArm == HumanoidArm.RIGHT ? -4f : 4f) * Math.abs(swingProgress) * bounceIntensity;
            } else if (entity.swinging && swingDuration > 1) {
                // Otherwise if the swing animation isn't interrupted, attempt to rotate slightly counter to the
                // direction that the body is currently moving
                // 正常挥动时，让旋转轻微反向于身体当前的运动方向，产生"对抗感"
                this.targetRotVel += (swingingToward == HumanoidArm.RIGHT ? -0.2f : 0.2f) * amplifier * bounceIntensity;
            }
            lastSwingTick = entity.swingTime;
        }
        if (!entity.swinging) {
            // 停止挥动后重置进度基准，下次挥动重新开始计数
            lastSwingTick = 0;
        }
        // 记录本 tick 的挥动时长（至少为 1，避免除零）
        lastSwingDuration = Math.max(swingDuration, 1);

		/*if (plr.getPose() == EntityPose.SWIMMING) {
			//WildfireGender.logger.debug(1 - plr.getRotationVec(tickDelta).getY());
			rotationMultiplier = 1 - (float) plr.getRotationVec(tickDelta).getY();
		}
		*/

        // =================================================================
        //  阶段 3：阻尼系数与边界回弹
        // =================================================================

        // --- 3.1 由"晃动时长"推导阻尼系数 ---------------------------------
        // percent（floppiness）：配置里的值，0.25（晃得短）~ 1（晃得久）。
        // 界面上叫「晃动时长」，内部沿用 FGM 的 floppiness 命名。
        // bounceAmount：阻尼系数，与该值反比——值越大阻尼越小，晃动持续越久。
        //   1.00 → bounceAmount = 0.15（几乎无阻尼，晃很久）
        //   0.25 → bounceAmount = 0.4875（阻尼较大，很快停下）
        float percent = entityConfig.getFloppiness();
        float bounceAmount = 0.45f * (1f - percent) + 0.15f; //0.6f * percent - 0.15f;
        bounceAmount = Mth.clamp(bounceAmount, 0.15f, 0.6f);
        // delta：弹簧刚度。阻尼越大，刚度越小，整体反应越"迟钝"。
        // 两者之和恒为 2.25，保证系统在不同松软度下的响应速度大致可比。
        float delta = 2.25f - bounceAmount;
        //if (plr.isInWater()) delta = 0.75f - (1f * bounceAmount); //water resistance

        // --- 3.2 边界回弹 -------------------------------------------------
        // 当质点已经越过上下限时，把目标值往反方向推，制造"撞到底被弹回来"的效果。
        // 距离越远，修正量越大（线性关系）。
        // 注意这里的输入是当前位置 bounceVel 而非目标值，因此构成了一个闭环反馈。
        float distanceFromMin = Math.abs(bounceVel + 0.5f) * 0.5f;
        float distanceFromMax = Math.abs(bounceVel - 2.65f) * 0.5f;

        if (bounceVel < -0.5f) {
            // 低于下限（-0.5）：把目标值往上抬
            targetBounceY += distanceFromMin;
        }
        if (bounceVel > 2.5f) {
            // 高于上限（2.5）：把目标值往下压
            targetBounceY -= distanceFromMax;
        }
        // 目标值的安全区间比输出区间略宽，留出"回弹"的余量
        targetBounceY = Mth.clamp(targetBounceY, -1.5f, 2.5f);
        targetRotVel = Mth.clamp(targetRotVel, -25f, 25f);

        // =================================================================
        //  阶段 4：阻尼积分（三个自由度各跑一遍同样的公式）
        //
        //    velocity = lerp(bounceAmount, velocity, (target − current) · delta)
        //    current += velocity · percent · 系数
        //
        //  第一行：把速度"拉向"由位置误差决定的理想速度。bounceAmount 越大，
        //          拉得越狠，速度越快贴合理想值，系统越稳定。
        //  第二行：用速度做显式欧拉积分更新位置。
        // =================================================================

        // --- Y 轴（系数 1.1625 用于补偿较弱的阻尼，使纵向晃动更明显）------
        this.velocity = Mth.lerp(bounceAmount, this.velocity, (this.targetBounceY - this.bounceVel) * delta);
        //this.preY = MathHelper.lerp(0.5f, this.preY, (this.targetBounce - this.bounceVel) * 1.25f);
        this.bounceVel += this.velocity * percent * 1.1625f;

        //X
        // --- X 轴 --------------------------------------------------------
        this.velocityX = Mth.lerp(bounceAmount, this.velocityX, (this.targetBounceX - this.bounceVelX) * delta);
        this.bounceVelX += this.velocityX * percent;

        // --- 旋转 --------------------------------------------------------
        this.rotVelocity = Mth.lerp(bounceAmount, this.rotVelocity, (this.targetRotVel - this.bounceRotVel) * delta);
        this.bounceRotVel += this.rotVelocity * percent;

        // =================================================================
        //  阶段 5：输出与硬边界钳制
        //  积分主状态 bounceVel* 直接赋给对外输出 position*；
        //  Y 轴额外做一次强制钳制，作为数值发散的最后防线。
        // =================================================================
        this.wfg_bounceRotation = this.bounceRotVel;
        this.positionX = this.bounceVelX;
        this.positionY = this.bounceVel;

        if (this.positionY < -0.5f) {
            // 下限钳制：只改位置，不清零速度，让质点自然地被"顶"回去
            this.positionY = -0.5f;
        }
        if (this.positionY > 1.5f) {
            // 上限钳制：同时清零速度，避免在顶部位持续抖动
            this.positionY = 1.5f;
            this.velocity = 0;
        }
    }

    /**
     * 获取经过帧间插值的乳房大小。
     *
     * <p>逻辑 tick 频率（20Hz）低于渲染帧率，若直接使用 {@link #breastSize}，
     * 尺寸变化时会看到阶梯状跳变。这里按渲染帧在一 tick 内的进度做线性插值。</p>
     *
     * @param partialTicks 当前渲染帧在两次 tick 之间的进度，取值 [0, 1)
     * @return 插值后的乳房大小
     */
    public float getBreastSize(float partialTicks) {
        return Mth.lerp(partialTicks, preBreastSize, breastSize);
    }

    /**
     * @return 上一 tick 的 Y 轴位置，供渲染插值使用
     */
    public float getPrePositionY() {
        return this.prePositionY;
    }

    /**
     * @return 本 tick 的 Y 轴位置（已做过硬边界钳制），约在 [-0.5, 1.5] 之间
     */
    public float getPositionY() {
        return this.positionY;
    }

    /**
     * @return 上一 tick 的 X 轴位置，供渲染插值使用
     */
    public float getPrePositionX() {
        return this.prePositionX;
    }

    /**
     * @return 本 tick 的 X 轴位置
     */
    public float getPositionX() {
        return this.positionX;
    }

    /**
     * @return 本 tick 的旋转值，单位为角度，约在 [-25, 25] 之间
     */
    public float getBounceRotation() {
        return this.wfg_bounceRotation;
    }

    /**
     * @return 上一 tick 的旋转值，供渲染插值使用
     */
    public float getPreBounceRotation() {
        return this.wfg_preBounceRotation;
    }

    /**
     * 把实体的移动速度映射成"每多少 tick 触发一次颠簸"的周期。
     *
     * <p>速度越快，返回值越小，即颠簸越频繁。下限钳制为 1，避免出现除零或负周期。</p>
     *
     * @param movement 实体当前速度向量的模长
     * @return 触发周期（tick），至少为 1
     */
    private int clampMovement(float movement) {
        return Math.max((int) (10 - 2 * movement), 1);
    }

    /**
     * 判断某个载具是否会破坏转身物理的计算。
     *
     * <p>这些载具会强制把乘客的 {@code yBodyRot} 对齐到自身朝向，导致
     * {@link #calcRotation} 算出的角速度恒为零或出现异常跳变，因此需要直接跳过旋转模拟。</p>
     *
     * @param vehicle 乘客所骑乘的载具
     * @return 若该载具会使旋转物理失效则返回 {@code true}
     */
    private static boolean vehicleSuppressesRotation(Entity vehicle) {
        // while you aren't able to normally ride chickens in vanilla, it is still possible through
        // means like /ride, and as chickens attempt to force the rider's body yaw to the same yaw
        // as the chicken (which is likely intended only for baby zombies), which results in unintended
        // behavior with what we're doing
        // 鸡：原版无法骑乘，但可通过 /ride 等方式实现。鸡会强制把乘客的朝向对齐到自己身上
        //（该逻辑本意大概只针对小僵尸），会破坏旋转物理。
        return vehicle instanceof Chicken ||
               // unsaddled horses (and llamas, which also extend AbstractDonkeyEntity?) also break rotation
               // physics, despite acting similarly to other entities where the rider's body yaw is allowed to
               // (somewhat) freely move around
               // 未上鞍的马（以及同样继承 AbstractDonkeyEntity 的羊驼）：同样会破坏旋转物理
               vehicle instanceof AbstractHorse horseLike && !horseLike.isSaddled() ||
               // camels also suffer from largely the same issue as unsaddled horses when sitting or standing up
               // 骆驼：在坐下/起立时存在与未上鞍的马相同的问题
               vehicle instanceof Camel camel && camel.refuseToMove();
    }

    /**
     * 判断计算转身物理时是否应该改用"载具的朝向"而非乘客自身的朝向。
     *
     * <p>当载具由乘客操控时，乘客的朝向通常被强制对齐到载具，此时用载具的朝向变化才能算出正确的角速度。</p>
     *
     * @param rider   乘客实体
     * @param vehicle 载具实体
     * @return 若应使用载具朝向则返回 {@code true}
     */
    private static boolean shouldUseVehicleYaw(LivingEntity rider, Entity vehicle) {
        // 有操控者（玩家正在驾驶）时必然使用载具朝向
        return vehicle.hasControllingPassenger() ||
               // boats will typically be caught by the above #hasControllingPassenger() check, but still
               // special case these to catch any weird modded cases that might arise
               // 船：通常会被上面的判断覆盖，这里显式特判以兼容模组产生的异常情况
               vehicle instanceof Boat ||
               // general catch-all for other entities that force the rider's body yaw to match theirs,
               // such as horses
               // 兜底判断：若载具与乘客的视觉朝向完全一致，说明乘客朝向被载具绑定了
               vehicle.getVisualRotationYInDegrees() == rider.getVisualRotationYInDegrees();
    }

    /**
     * 计算转身产生的旋转目标值。
     *
     * <p>核心是"本 tick 与上一 tick 的躯干朝向之差"，即角速度。
     * 除以 15 是经验性缩放，把角度差转换到合适的目标值量级；取负号表示
     * 胸部倾向于"滞后于"身体转动（转身时往回转），符合惯性直觉。</p>
     *
     * <p>骑乘载具时分三种情况：</p>
     * <ol>
     *   <li>载具会破坏朝向（鸡、未上鞍的马、静止的骆驼）→ 返回 0，不做旋转模拟。</li>
     *   <li>载具主导朝向（被驾驶的载具、船、朝向绑定的载具）→ 用载具的朝向变化计算。</li>
     *   <li>其它情况 → 回退到使用乘客自身的朝向变化。</li>
     * </ol>
     *
     * @param entity          被模拟的实体
     * @param bounceIntensity 晃动强度，作为角速度的放大倍率
     * @return 旋转目标值（角度），负值表示与转身方向相反
     */
    private float calcRotation(LivingEntity entity, float bounceIntensity) {
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            if (vehicleSuppressesRotation(vehicle)) {
                return 0f;
            } else if (shouldUseVehicleYaw(entity, vehicle)) {
                // 载具若本身是生物，用它的 yBodyRotO；否则用通用实体的 yRotO
                float previous = vehicle instanceof LivingEntity living ? living.yBodyRotO : vehicle.yRotO;
                return -((vehicle.getVisualRotationYInDegrees() - previous) / 15f) * bounceIntensity;
            }
        }

        // 未骑乘（或载具不主导朝向）：直接用实体自身的躯干朝向变化
        return -((entity.yBodyRot - entity.yBodyRotO) / 15f) * bounceIntensity;
    }

    /**
     * Return the distance from the median of the two provided boundary points from a given point
     *
     * @param p1    Lower boundary point (inclusive)
     * @param p2    Upper boundary point (inclusive)
     * @param point The target point within the range of {@code p1} and {@code p2} to get the distance from the median of
     *
     * @return A {@code float} indicating how far the provided {@code point} is from the median of the two boundary points, with {@code 1f} being at the median exactly,
     * and {@code 0f} being at either of the two provided boundary points.<br> If the provided point is in the latter half of the range between the two boundary points,
     * the returned float will be negative.
     *
     * @throws IllegalArgumentException If {@code p1} is equal to or greater than {@code p2}, or if {@code point} is not within the specified range.
     */
    // 中文说明：把 point 映射到 [-1, 1] 区间，表示它相对区间中点的位置。
    //   0        → 恰好在端点（p1 或 p2）
    //   1        → 恰好在区间正中
    //   负值     → 位于区间后半段（越过中点之后）
    // 用途：在挥臂逻辑中判断手臂当前处于"挥出"还是"回摆"阶段。
    @SuppressWarnings("SameParameterValue")
    private static float distanceFromMedian(final int p1, final int p2, float point) {
        // sanity checks
        // 参数校验：区间必须有效，且 point 必须落在区间内
        if (p1 >= p2) {
            throw new IllegalArgumentException("p2 must be greater than p1");
        } else if (point < p1 || point > p2) {
            throw new IllegalArgumentException(point + " is not within bounds of (" + p1 + ", " + p2 + ")");
        }
        if (point == p1 || point == p2) {
            // 恰好落在端点上，距离中点最远，返回 0
            return 0;
        }
        // subtract p1 to get the actual inner range, then divide to get the median
        // 先平移到以 0 为起点，再取区间半长作为中点位置
        float median = (p2 - p1) / 2f;
        point -= p1;
        if (point > median) {
            // invert the provided point to instead become smaller the further we are away from the median
            // in the latter half of the specified range
            // 位于后半段时做镜像并取负，使返回值随远离中点而减小并变为负数
            point = -(median - (point - median));
        }
        // 归一化到 [-1, 1]：前半段为正值，后半段为负值
        return point / median;
    }
}
