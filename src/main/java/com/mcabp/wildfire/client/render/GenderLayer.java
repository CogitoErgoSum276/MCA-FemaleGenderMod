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

package com.mcabp.wildfire.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mcabp.wildfire.api.IBreastArmorTexture;
import com.mcabp.wildfire.api.IGenderArmor;
import com.mcabp.wildfire.api.impl.BreastArmorTexture;
import com.mcabp.wildfire.main.entitydata.Breasts;
import com.mcabp.wildfire.main.WildfireHelper;
import com.mcabp.wildfire.main.config.GeneralClientConfig;
import com.mcabp.wildfire.main.entitydata.EntityConfig;
import com.mcabp.wildfire.physics.BreastPhysics;
import com.mcabp.wildfire.client.render.WildfireModelRenderer.BreastModelBox;
import com.mcabp.wildfire.client.render.WildfireModelRenderer.OverlayModelBox;
import com.mcabp.wildfire.client.render.WildfireModelRenderer.PositionTextureVertex;
import java.util.Objects;
import net.conczin.mca.client.render.DynamicSkinCache;
import net.conczin.mca.entity.VillagerLike;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import com.mcabp.wildfire.main.WildfireGender;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.*;

import net.minecraft.world.item.ArmorMaterial.Layer;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.ClientHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2ic;
import org.joml.Vector3f;

/**
 * <h2>胸部渲染层（Breast Render Layer）</h2>
 *
 * <p>本类是整个模组「物理 → 画面」的最后一环。它<b>不做任何物理计算</b>，只负责把
 * {@link BreastPhysics} 每 tick 算出的数值（大小、位移、倾角）翻译成矩阵变换，
 * 再把胸部几何体画到实体身上。</p>
 *
 * <p>职责可以拆成三块：</p>
 * <ol>
 *   <li><b>决策</b>：判断这个实体这一帧到底该不该画——旁观者、隐身、护甲遮挡、
 *       全局渲染开关等，见 {@link #render}。不该画时尽早 return，省掉全部后续计算。</li>
 *   <li><b>取数</b>：从 {@link EntityConfig} 读配置和物理实例，并按 {@code partialTicks}
 *       在「上一 tick」与「本 tick」之间做线性插值。逻辑每秒只跑 20 次（20Hz），
 *       而画面每秒渲染 60+ 帧，不插值就会看到阶梯状跳变。</li>
 *   <li><b>变换与绘制</b>：把插值结果施加到 {@link PoseStack} 上，再写出顶点数据，
 *       见 {@link #renderBreastWithTransforms} 和 {@link #renderBreast}。</li>
 * </ol>
 *
 * <p><b>关于坐标系</b>：Minecraft 模型空间的单位是 1/16 格（俗称一个「像素」），
 * 所以代码里到处出现 {@code * 0.0625f}（即 /16）和 {@code / 16f} 这类换算。
 * 另外模型空间中 <b>+Y 轴朝下</b>，阅读时看到负号要留意方向。</p>
 *
 * <p><b>关于本类与 {@link RenderLayer}</b>：{@code RenderLayer} 是 Minecraft 的「附加渲染层」
 * 机制——主模型画完之后，游戏会依次回调每个挂上去的 layer，让它们往同一个
 * {@link PoseStack} 上追加内容。本类就是通过 {@code renderer.addLayer(...)} 挂到
 * MCA 村民渲染器上的（见 {@code WildfireGenderClient#entityLayers}）。</p>
 *
 * @param <ENTITY> 被渲染的实体类型，实际使用中是 MCA 的 {@link VillagerLike} 村民
 * @param <MODEL>  对应的模型类型，必须是 {@link HumanoidModel} 的子类
 *                 （因为它依赖模型的 {@code body} 部件来定位胸部的基准位置）
 *
 * @see BreastPhysics
 * @see WildfireModelRenderer
 */
public class GenderLayer<ENTITY extends LivingEntity, MODEL extends HumanoidModel<ENTITY>> extends RenderLayer<ENTITY, MODEL> {

	// 左右两侧的「外套层」盒子：对应玩家皮肤上比身体大一圈的夹克/衬衫层。
	// 尺寸固定不变，所以做成 static final 常量；渲染时直接叠在乳房盒子外面即可。
	private static final OverlayModelBox lBreastWear = new OverlayModelBox(true,64, 64, 17, 34, -4F, 0.0F, 0F, 4, 5, 3, 0.0F, false);
	private static final OverlayModelBox rBreastWear = new OverlayModelBox(false,64, 64, 21, 34, 0, 0.0F, 0F, 4, 5, 3, 0.0F, false);

	// 盔甲纹饰（Armor Trim）图集，画胸甲上的纹饰图案时按 sprite 取用
	private final TextureAtlas armorTrimAtlas;

	// 乳房本体的几何盒（左右各一）。不能声明为 final——罩杯大小变化时必须重建。
	private BreastModelBox lBreast, rBreast;
	// 胸甲「鼓包」的几何盒（左右各一），即胸甲盖在乳房上的那一块
	private BreastModelBox lBoobArmor, rBoobArmor;
	// 上次重建盒子时使用的尺寸与 Z 偏移，用来判断是否真的需要重建（避免每帧都 new 出新对象）
	private float preBreastSize, preBreastOffsetZ;
	// 当前生效的胸甲贴图配置；仅当它发生变化时才重建 lBoobArmor / rBoobArmor
	@NotNull
	private IBreastArmorTexture textureData = BreastArmorTexture.DEFAULT;

	/**
	 * 构造渲染层。这里只做一次性初始化，把盒子的「初始尺寸」建好；
	 * 实际每帧的尺寸由 {@link #resizeBox} 在渲染时动态调整。
	 *
	 * @param renderer     被挂载的目标渲染器（玩家或盔甲架），{@link RenderLayer} 需要它来拿主模型
	 * @param modelManager 模型管理器，用于取得盔甲纹饰图集
	 */
	public GenderLayer(RenderLayerParent<ENTITY, MODEL> renderer, ModelManager modelManager) {
		super(renderer);

		armorTrimAtlas = modelManager.getAtlas(Sheets.ARMOR_TRIMS_SHEET);
		// this can't be static or final as we need the ability to resize this during render time
		// 乳房本体的默认盒子：贴图 64x64，UV 起点 (16,17)/(20,17)，深度 4
		lBreast = new BreastModelBox(64, 64, 16, 17, -4F, 0.0F, 0F, 4, 5, 4, 0.0F, false);
		rBreast = new BreastModelBox(64, 64, 20, 17, 0, 0.0F, 0F, 4, 5, 4, 0.0F, false);

		// 胸甲覆盖层的盒子：深度只用 3，比乳房薄一点，避免和乳房本体穿模
		lBoobArmor = new BreastModelBox(64, 32, 16, 17, -4F, 0.0F, 0F, 4, 5, 3, 0.0F, false);
		rBoobArmor = new BreastModelBox(64, 32, 20, 17, 0, 0.0F, 0F, 4, 5, 3, 0.0F, false);
	}

	/**
	 * <h3>渲染入口（每帧调用一次）</h3>
	 *
	 * <p>执行顺序：</p>
	 * <ol>
	 *   <li>排除不该渲染的实体（全局渲染开关关闭、旁观者模式）</li>
	 *   <li>取出实体配置与胸甲配置，处理「盔甲强制遮挡」「玩家选择穿甲时隐藏」两种提前退出</li>
	 *   <li>按实体可见性挑选 {@link RenderType}（判断逻辑照抄原版 {@code LivingEntityRenderer#getRenderType}）</li>
	 *   <li>读取配置偏移量与物理插值结果</li>
	 *   <li>左右各调用一次 {@link #renderBreastWithTransforms} 完成实际绘制</li>
	 * </ol>
	 *
	 * @param matrixStack    当前实体已经累积好的姿态矩阵栈，本层只在其基础上继续叠加变换
	 * @param bufferSource   顶点缓冲来源，用于按 {@link RenderType} 取得 {@link VertexConsumer}
	 * @param light          打包好的光照值（由实体所在位置计算得出）
	 * @param entity         被渲染的实体
	 * @param limbAngle      【原版参数，本类未使用】四肢摆动角度
	 * @param limbDistance   【原版参数，本类未使用】四肢摆动幅度
	 * @param partialTicks   当前渲染帧在两次逻辑 tick 之间的进度，取值 [0,1)，用于插值
	 * @param animationProgress 【原版参数，本类未使用】动画进度
	 * @param headYaw        【原版参数，本类未使用】头部偏航角
	 * @param headPitch      【原版参数，本类未使用】头部俯仰角
	 */
	@Override
	public void render(@NotNull PoseStack matrixStack, @NotNull MultiBufferSource bufferSource, int light, @NotNull ENTITY entity, float limbAngle,
		float limbDistance, float partialTicks, float animationProgress, float headYaw, float headPitch) {
		// 总开关被关闭，或实体处于旁观者模式。
		// 旁观者模式只渲染头部，身体部件根本不画，所以胸部也没有渲染的必要。
		if (!GeneralClientConfig.INSTANCE.renderingEnabled.get() || entity.isSpectator()) {
			//Rendering is disabled client side, or the entity is in spectator so only the head will be rendered
			return;
		}
		//Surround with a try/catch to fix for essential mod.
		// 整个方法体用 try 包住：渲染异常绝不能让游戏崩溃，出问题只记日志。
		// （注释里提到的 essential mod 指该模组会在这个回调中抛异常）
		try {
            // 取得实体的配置对象：玩家返回 PlayerConfig，其他实体返回缓存中的 EntityConfig
            EntityConfig entityConfig = EntityConfig.getEntity(entity);
			if(entityConfig == null) return;
			// 编辑器里的预览实体由 MCA 单独创建、并不在世界中，收不到 EntityTickEvent，
			// 基因与物理都停在初值（罩杯为 0，于是整块胸部被"尺寸过小"的判断拦掉）。
			// 检测到物理从未跑过时补一次同步，让预览也能正常显示。
			if (entityConfig.getLeftBreastPhysics().getBreastSize(0F) <= 0F) {
				entityConfig.refreshForRender(entity);
			}
			// 胸甲槽位上的物品
			ItemStack armorStack = entity.getItemBySlot(EquipmentSlot.CHEST);
			//Note: When the stack is empty the helper will fall back to an implementation that returns the proper data
			// 查这件胸甲对应的性别盔甲配置（可能来自资源包 JSON、物品 capability，或原版默认值兜底）
			IGenderArmor genderArmor = WildfireHelper.getArmorConfig(armorStack);
			// 该胸甲是否「覆盖」胸部。像鞘翅那样前方敞开的会返回 false
			final boolean isChestplateOccupied = genderArmor.coversBreasts();
			if (genderArmor.alwaysHidesBreasts() || !entityConfig.showBreastsInArmor() && isChestplateOccupied) {
				//If the armor always hides breasts or there is armor and the player configured breasts
				// to be hidden when wearing armor, we can just exit early rather than doing any calculations
				// 两种提前退出：① 该盔甲强制隐藏胸部；
				//              ② 穿着胸甲，且玩家设置了「穿盔甲时隐藏胸部」
				// 这两种情况下不可能画出胸部，早点返回省掉后面所有计算
				return;
			}
            // 本帧用来绘制乳房的渲染类型。为 null 表示「不画乳房本体」，只画胸甲。
            RenderType breastRenderType = null;
            // 乳房的贴图：原版实现只对玩家返回皮肤贴图，其他实体一律 null
            ResourceLocation entityTexture = getBreastTexture(entity);
            if (entityTexture != null) {
                //RenderType selection copied from LivingEntityRenderer#getRenderType
                // 下面这段可见性判断与渲染类型的对应关系，完全照抄原版 LivingEntityRenderer，
                // 目的是让胸部的透明度/发光表现和实体本体保持一致，避免出现"人隐身了胸还在"之类的穿帮。
                boolean bodyVisible = !entity.isInvisible();
                Minecraft minecraft = Minecraft.getInstance();
                // 本体不可见，但对本地玩家仍可见 → 用半透明渲染（例如喝了隐身药水的其他玩家）
                boolean translucent = !bodyVisible && minecraft.player != null && !entity.isInvisibleTo(minecraft.player);
                if (translucent) {
                    breastRenderType = RenderType.itemEntityTranslucentCull(entityTexture);
                } else if (bodyVisible) {
                    // 正常可见 → 用 cutout 渲染。
                    // FGM 原版此处是 entityTranslucent（半透明），但半透明属于延迟批次，
                    // 在 MCA 这种「主模型不渲染、外观全靠 layer 叠加」的管线下容易出现深度错位；
                    // 而 MCA 的拼合贴图本身不透明（alpha 恒为 255），用 cutout 效果一致且更稳。
                    breastRenderType = RenderType.entityCutoutNoCull(entityTexture);
                } else if (minecraft.shouldEntityAppearGlowing(entity)) {
                    // 完全不可见但需要发光描边（例如被发光效果标记）→ 只画轮廓
                    breastRenderType = RenderType.outline(entityTexture);
                } else {
                    if (!isChestplateOccupied) {
                        //Exit early if we don't need to render the breasts, and we don't need to render the armor
                        // 实体完全不可见、也不需要描边，此时唯一还有必要画的就是胸甲鼓包。
                        // 若连胸甲都没覆盖胸部，那就彻底没事可做，直接返回。
                        return;
                    }
                }
            } else if (!isChestplateOccupied) {
                //Exit early if we don't need to render the breasts, and we don't need to render the armor
                // 没有贴图（非玩家实体）且没有胸甲要画 → 同样直接返回。
                // 注意：这说明本类默认只为「有皮肤贴图的玩家」渲染乳房本体；
                //      要让自定义实体（如 NPC）也能渲染，必须让它走到 entityTexture != null 这条分支。
                return;
            }

			// ---------- 取配置：三个位置偏移 ----------
			Breasts breasts = entityConfig.getBreasts();
			// 偏移量先四舍五入到小数点后两位、再规整到一位小数。
			// 双重取整是为了抹掉浮点误差，保证同一个配置每次算出来的值完全一致，
			// 否则渲染时可能出现极细微的每帧抖动。
			// Y/Z 取负号是因为配置面向用户的「正向」与模型空间方向相反（模型空间 +Y 朝下）。
			float breastOffsetX = Math.round((Math.round(breasts.getXOffset() * 100f) / 100f) * 10) / 10f;
			float breastOffsetY = -Math.round((Math.round(breasts.getYOffset() * 100f) / 100f) * 10) / 10f;
			float breastOffsetZ = -Math.round((Math.round(breasts.getZOffset() * 100f) / 100f) * 10) / 10f;

			// ---------- 取物理结果：左胸（右胸稍后按需取） ----------
			BreastPhysics leftBreastPhysics = entityConfig.getLeftBreastPhysics();
			// 插值后的左胸尺寸。后续所有尺寸相关计算都以它（bSize）为基准
			// 物理实例尚未跑过时（编辑器预览实体）它是 0，此时直接用基因解析出的罩杯兜底，
			// 否则会被下面"尺寸过小就不渲染"的判断直接拦掉。
			float bSize = leftBreastPhysics.getBreastSize(partialTicks);
			if (bSize <= 0F) {
				bSize = entityConfig.getBustSize();
			}
			// 乳沟参数（配置范围 0~0.1）线性放大 100 倍 → 0~10，单位是「度」，
			// 表示两侧胸部各自向外旋转的角度，值越大分得越开。
			float outwardAngle = (Math.round(breasts.getCleavage() * 100f) / 100f) * 100f;
			// 上限兜底：即使配置被外部改坏，也不会超过 10 度
			outwardAngle = Math.min(outwardAngle, 10);

			// 按当前尺寸重建几何盒（内部有缓存，尺寸没变时不会重复 new）
			resizeBox(genderArmor, bSize, breastOffsetZ);

			//Note: We only render if the entity is not visible to the player, so we can assume it is visible to the player
			// 隐身时不能完全不画，否则会露出"没有胸的空壳"，所以用 15% 透明度画一层淡淡的轮廓
			float overlayAlpha = entity.isInvisible() ? 0.15F : 1;

			// 重置着色器颜色，避免受上一帧其他渲染遗留的颜色影响
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

			// ---------- 物理状态插值（20Hz 逻辑 → 60+fps 画面）----------
			// Mth.lerp(partialTicks, pre, cur) = pre + (cur - pre) * partialTicks，
			// 即"上一 tick 的值"和"本 tick 的值"之间按帧进度取中间值。
			float lPhysPositionY = Mth.lerp(partialTicks, leftBreastPhysics.getPrePositionY(), leftBreastPhysics.getPositionY());
			float lPhysPositionX = Mth.lerp(partialTicks, leftBreastPhysics.getPrePositionX(), leftBreastPhysics.getPositionX());
			float leftBounceRotation = Mth.lerp(partialTicks, leftBreastPhysics.getPreBounceRotation(), leftBreastPhysics.getBounceRotation());
			float rPhysPositionY;
			float rPhysPositionX;
			float rightBounceRotation;
			if (breasts.isUniboob()) {
				// 单胸模式：两半边必须严格同步，否则连成一体的形状会撕裂，
				// 所以右侧直接复制左侧的值，不再单独取一套物理数据。
				rPhysPositionY = lPhysPositionY;
				rPhysPositionX = lPhysPositionX;
				rightBounceRotation = leftBounceRotation;
			} else {
				// 双胸模式：右胸有自己的 BreastPhysics 实例，独立插值
				BreastPhysics rightBreastPhysics = entityConfig.getRightBreastPhysics();
				rPhysPositionY = Mth.lerp(partialTicks, rightBreastPhysics.getPrePositionY(), rightBreastPhysics.getPositionY());
				rPhysPositionX = Mth.lerp(partialTicks, rightBreastPhysics.getPrePositionX(), rightBreastPhysics.getPositionX());
				rightBounceRotation = Mth.lerp(partialTicks, rightBreastPhysics.getPreBounceRotation(), rightBreastPhysics.getBounceRotation());
			}
			// ---------- 尺寸映射 ----------
			// 注意：下面这几行赋值只服务于「尺寸过小就直接不渲染」这个提前退出判断，
			// 最终真正用于绘制的 breastSize 会在后面被重新计算（见本段末尾）。
			float breastSize = bSize * 1.5f;
			if (breastSize > 0.7f) breastSize = 0.7f;
			if (bSize > 0.7f) {
				breastSize = bSize;
			}

			// 尺寸太小（例如罩杯几乎为 0）时没有任何渲染意义，直接跳过，
			// 也顺便省掉后面重建盒子、写顶点等全部开销。
			if (breastSize < 0.02f) return;

			// zOff：胸部相对身体表面的 Z 基准偏移。
			// 公式等价于 (1 - bSize) / 16，即罩杯越大、往外推的基准量越小
			// （罩杯大时由几何盒自身的深度撑开，不需要额外的基准偏移）。
			float zOff = 0.0625f - (bSize * 0.0625f);
			// 最终尺寸映射：bSize + |bSize - 0.7|，是一个以 0.7 为拐点的分段函数：
			//   bSize <= 0.7 时 → 恒等于 0.7（小罩杯统一按 0.7 渲染，避免过小看不出起伏）
			//   bSize >  0.7 时 → 2 * bSize - 0.7（大罩杯以两倍斜率放大，差异更明显）
			breastSize = bSize + 0.5f * Math.abs(bSize - 0.7f) * 2f;

			//If the armor physics is overridden ignore resistance
			// 胸甲对晃动的「抗性」：0 表示完全不阻碍（正常晃），1 表示完全固定。
			// 两种情况直接取 0（即视为无抗性）：
			//   ① 玩家开启了"忽略护甲物理"；② 当前穿的盔甲不覆盖胸部。
			float resistance = entityConfig.getArmorPhysicsOverride() || !isChestplateOccupied ? 0 : Mth.clamp(genderArmor.physicsResistance(), 0, 1);
			//Note: We only check if the breathing animation should be enabled if the chestplate's physics resistance
			// is less than or equal to 0.5 so that if we won't be rendering it we can avoid doing extra calculations
			// 是否播放「呼吸起伏」动画。整段条件的意思是：这个实体能正常呼吸，
			// 并且胸甲没有把胸部固定得太死。
			//   · canBreathe()：只有玩家返回 true（基类 EntityConfig 返回 false），
			//     所以 NPC / 盔甲架不会做呼吸动画。
			//   · resistance <= 0.5F：抗性大于 0.5 时胸部几乎不动，呼吸起伏根本看不出来，
			//     于是干脆不进后面那串判断，省掉不必要的计算。
			//   · 后面三个条件等价于「能呼吸」：不在水下，或在水下有水下呼吸效果，
			//     或正处于气泡柱里（气泡柱会补充氧气）。
			// 也就是说憋气状态（水下且没氧气）不播放呼吸动画。
			boolean breathingAnimation = entityConfig.canBreathe() && resistance <= 0.5F &&
                                         (!entity.isUnderWater() || MobEffectUtil.hasWaterBreathing(entity) ||
										  entity.level().getBlockState(BlockPos.containing(entity.getX(), entity.getEyeY(), entity.getZ())).is(Blocks.BUBBLE_COLUMN));
			// 是否启用晃动。resistance == 1 意味着胸甲把胸部完全固定，
			// 此时不需要施加任何位移/旋转，直接按静止姿态绘制。
			boolean bounceEnabled = entityConfig.hasBreastPhysics() && resistance < 1; //oh, you found this?

			// 覆盖层坐标：用于受击变红等效果。第二个参数 0 表示不带任何伤害覆盖层
			int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0);
			// 主模型。胸部的位置需要挂在模型的 body（躯干）部件上，所以必须拿到它
			HumanoidModel<ENTITY> model = getParentModel();
			// 是否需要画「外套层」（皮肤上那层夹克/衬衫，比身体大一圈）。
			// 玩家读设置里的 JACKET 模型部件开关；非玩家（盔甲架）读配置里记录的 NBT 值
			boolean hasJacketLayer = entity instanceof Player player ? player.isModelPartShown(PlayerModelPart.JACKET) : entityConfig.hasJacketLayer();
			// 左胸。倒数第三个参数 true 表示「这是左半边」
			renderBreastWithTransforms(entity, model, armorStack, matrixStack, bufferSource, breastRenderType, light, overlay, overlayAlpha, bounceEnabled,
				lPhysPositionX, lPhysPositionY, leftBounceRotation, breastSize, breastOffsetX, breastOffsetY, breastOffsetZ, zOff, outwardAngle, breasts.isUniboob(),
				isChestplateOccupied, breathingAnimation, true, hasJacketLayer);
			// 右胸。只用 X 方向偏移取负号完成镜像。
			// 注意「向外张开的角度」这里不能再取负：方法内部已经按 left 参数区分了左右符号，
			// 两边都传正值即可；重复取负会让两个胸朝同一侧旋转（此写法取自 FGM，实测有误）。
			// 最后一个参数 false 表示「这是右半边」
			renderBreastWithTransforms(entity, model, armorStack, matrixStack, bufferSource, breastRenderType, light, overlay, overlayAlpha, bounceEnabled,
				rPhysPositionX, rPhysPositionY, rightBounceRotation, breastSize, -breastOffsetX, breastOffsetY, breastOffsetZ, zOff, outwardAngle, breasts.isUniboob(),
				isChestplateOccupied, breathingAnimation, false, hasJacketLayer);
			// 恢复着色器颜色，避免影响后续其他渲染层
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		} catch(Exception e) {
			WildfireGender.LOGGER.error("Failed to render gender layer", e);
		}
	}

	/**
	 * 按当前尺寸重建几何盒。
	 *
	 * <p>两类盒子的更新条件不同：</p>
	 * <ul>
	 *   <li>乳房本体盒子：尺寸或 Z 偏移发生变化时才重建（用 {@code preBreastSize}/{@code preBreastOffsetZ} 做缓存判断）。</li>
	 *   <li>胸甲覆盖盒子：贴图配置发生变化时才重建。</li>
	 * </ul>
	 *
	 * <p>重建是有成本的（要重新生成全部顶点和 UV），所以这里必须做缓存判断，
	 * 不能每帧无脑 new。</p>
	 *
	 * @param genderArmor    当前胸甲的性别盔甲配置，提供贴图的 UV 与尺寸
	 * @param breastSize     映射后的最终尺寸（见 {@link #render} 中的分段映射）
	 * @param breastOffsetZ  Z 方向偏移，用于微调几何盒深度
	 */
	protected void resizeBox(IGenderArmor genderArmor, float breastSize, float breastOffsetZ) {
		// reducer 是一个「尺寸档位」修正量，初始 -1：
		//   尺寸 >= 0.84        → reducer 保持 -1
		//   0.72 <= 尺寸 < 0.84 → reducer 变成 0
		//   尺寸 <  0.72        → reducer 变成 1
		// 它在下面被减进深度里，效果就是「罩杯越小，几何盒越薄」，
		// 避免小胸时盒子太厚显得突兀。
		float reducer = -1;
		if (breastSize < 0.84f) reducer++;
		if (breastSize < 0.72f) reducer++;

		// 只有尺寸或 Z 偏移真的变了才重建，否则复用上一帧的盒子
		if (preBreastSize != breastSize || preBreastOffsetZ != breastOffsetZ) {
			// 深度 dz = 4 - breastOffsetZ - reducer（最终落在 2~5 这个量级）
			lBreast = new BreastModelBox(64, 64, 16, 17, -4F, 0.0F, 0F, 4, 5, (int) (4 - breastOffsetZ - reducer), 0.0F, false);
			rBreast = new BreastModelBox(64, 64, 20, 17, 0, 0.0F, 0F, 4, 5, (int) (4 - breastOffsetZ - reducer), 0.0F, false);
			preBreastSize = breastSize;
			preBreastOffsetZ = breastOffsetZ;
		}

        // 胸甲覆盖盒子：仅当「胸甲覆盖胸部」且「贴图配置变了」时重建。
        // UV 坐标和尺寸全部来自 IBreastArmorTexture，因此资源包可以为不同盔甲
        // 指定不同的贴图区域，而不用改代码。
        if (genderArmor.coversBreasts() && !Objects.equals(textureData, genderArmor.texture())) {
            textureData = genderArmor.texture();
            Vector2ic texSize = textureData.textureSize();
			Vector2ic lUV = textureData.leftUv();
			Vector2ic dim = textureData.dimensions();
            // 左半边胸甲：深度固定为 3，比乳房本体（4~5）薄，避免正反面穿模
            lBoobArmor = new BreastModelBox(texSize.x(), texSize.y(), lUV.x(), lUV.y(), -4F, 0.0F, 0F, dim.x(), dim.y(), 3, 0.0F, false);
			Vector2ic rUV = textureData.rightUv();
            rBoobArmor = new BreastModelBox(texSize.x(), texSize.y(), rUV.x(), rUV.y(), 0, 0.0F, 0F, dim.x(), dim.y(), 3, 0.0F, false);
        }
    }

	/**
	 * <h3>把物理数值翻译成矩阵变换，然后绘制一侧胸部</h3>
	 *
	 * <p>本方法左右两侧各调用一次（{@code left} 参数区分），完整变换链按顺序如下。
	 * <b>顺序非常重要</b>，因为矩阵变换是「后写的先作用」：</p>
	 *
	 * <pre>
	 *  1. 如果是幼年实体        → 先做一次整体缩放，把坐标换算回成人比例
	 *  2. 平移到躯干(body)位置   → 得到胸部在身体上的基准原点
	 *  3. 应用躯干自身的旋转     → 身体转身/前倾时胸部跟着走
	 *  4. 叠加物理位移           → 上下左右的惯性滞后（晃动）
	 *  5. 叠加用户配置的偏移      → X/Y/Z 微调 + zOff 基准前推
	 *  6. 单胸模式下先临时左移    → 为绕"自己的轴"旋转做准备（见下方注释）
	 *  7. 叠加晃动的左右倾角      → bounceRotation
	 *  8. 单胸模式下把临时左移还原
	 *  9. 下垂与前倾              → 按尺寸下移，并绕 X 轴前倾
	 * 10. 向外张开 + 呼吸起伏     → rotationY / rotateX
	 * 11. 1/1000 的 X 轴微缩     → 消除与身体表面的 z-fighting（深度冲突闪烁）
	 * </pre>
	 *
	 * <p>第 6~8 步的「先移开、转完再移回」是矩阵变换里常见的手法：<br>
	 * 旋转变换默认是绕原点进行的。若想让左右两半各自绕自己的中心旋转，
	 * 就得先把它挪到原点附近，旋转完再挪回去。作用在单侧时用不到，
	 * 所以只在 {@code !uniboob}（双胸分离模式）时才执行。</p>
	 *
	 * @param entity              被渲染的实体（用于幼年判断与呼吸计时）
	 * @param model               主模型，提供 {@code body} 部件的位置与旋转
	 * @param armorStack          胸甲物品，后面画胸甲时要用
	 * @param matrixStack         当前姿态矩阵栈
	 * @param bufferSource        顶点缓冲来源
	 * @param breastRenderType    乳房本体的渲染类型；为 {@code null} 表示不画乳房本体（只画胸甲）
	 * @param light               光照值
	 * @param overlay             覆盖层坐标
	 * @param alpha               透明度（隐身时为 0.15）
	 * @param bounceEnabled       是否施加物理位移与晃动旋转
	 * @param physPositionX       物理算出的 X 位移，除以 32 后换算成模型单位
	 * @param physPositionY       物理算出的 Y 位移，除以 32 后换算成模型单位
	 * @param bounceRotation      晃动的左右倾角，单位是<b>度</b>（内部会转成弧度）
	 * @param breastSize          映射后的最终尺寸，也决定前倾程度
	 * @param breastOffsetX       用户配置的 X 偏移（右胸传入的是负值）
	 * @param breastOffsetY       用户配置的 Y 偏移
	 * @param breastOffsetZ       用户配置的 Z 偏移
	 * @param zOff                Z 方向基准前推量
	 * @param outwardAngle        向外张开的角度，单位是度。左右两半都传正值，实际方向由 {@code left} 决定
	 * @param uniboob             是否为单胸模式（两半连成一体）
	 * @param isChestplateOccupied 是否有胸甲覆盖胸部
	 * @param breathingAnimation  是否播放呼吸起伏
	 * @param left                {@code true} 画左半边，{@code false} 画右半边
	 * @param hasJacketLayer      是否额外画外套层
	 */
	private void renderBreastWithTransforms(ENTITY entity, HumanoidModel<ENTITY> model, ItemStack armorStack, PoseStack matrixStack, MultiBufferSource bufferSource,
		@Nullable RenderType breastRenderType, int light, int overlay, float alpha, boolean bounceEnabled, float physPositionX, float physPositionY, float bounceRotation,
		float breastSize, float breastOffsetX, float breastOffsetY, float breastOffsetZ, float zOff, float outwardAngle, boolean uniboob, boolean isChestplateOccupied,
		boolean breathingAnimation, boolean left, boolean hasJacketLayer) {
		// pushPose 保存当前矩阵状态；方法末尾配对 popPose 还原，
		// 保证本层的变换不会"泄漏"给之后渲染的其他部件。
		matrixStack.pushPose();
		//Surround with a try/catch to fix for essential mod.
		try {
			// 【MCA 适配】这里刻意没有移植 FGM 的「幼年实体补偿」代码。
			//
			// FGM 原本在这个位置会做：
			//     matrixStack.scale(1 / model.babyBodyScale, ...);
			//     matrixStack.translate(0, model.bodyYOffset / 16f, 0);
			// 它是给原版幼年生物用的 —— 那些模型会在 renderToBuffer 里被真正缩小
			// （AgeableListModel 的 baby 分支：先缩到二分之一，再向下平移 24/16 格，
			// 这两个数值就是 babyBodyScale 的默认 2.0 和 bodyYOffset 的默认 24.0），
			// 而胸部坐标是按成人比例写死的，所以要反向补偿回去。
			//
			// MCA 村民不属于这种情况，两个原因：
			//   1. MCA 覆写了模型的 renderToBuffer（走 CommonVillagerModel#renderCommon），
			//      完全绕过原版的 baby 分支，模型本身从不缩小；
			//   2. 村民的体型改由渲染器统一缩放（VillagerLikeEntityMCARenderer#scale 里按
			//      getRawVerticalScaleFactor / getRawHorizontalScaleFactor 缩放整个实体），
			//      那一步在本 layer 之前就已作用到矩阵栈上，胸部坐标自然跟着一起缩放。
			// 所以这里既不需要、也不应该再补偿一次。
			//
			// 特别注意 MCA 把「青春期」也算作幼年：AgeState.TEEN.toAge() 是负数，
			// 于是原版 AgeableMob#isBaby() 会返回 true。若保留那段补偿，
			// 青春期村民会被额外缩小一半、并整体下移约 10 像素 —— 看上去就是胸部掉到了裆部。
			// 而青春期恰是未成年阶段里唯一会渲染胸部的一档（AgeState.TEEN 的 breasts 系数是 0.5，
			// 更小的三档都是 0），所以这个错位只在青春期暴露出来。
			// 取躯干部件，把原点移到躯干中心（body.x/y/z 单位是模型像素，所以乘 0.0625 = /16）
			ModelPart body = model.body;
			matrixStack.translate(body.x * 0.0625f, body.y * 0.0625f, body.z * 0.0625f);
			// 躯干自身如果带有旋转（例如潜行时前倾、死亡时翻倒），
			// 胸部必须跟着一起转，否则会脱离身体浮在空中。
			if(body.zRot != 0.0F || body.yRot != 0.0F || body.xRot != 0.0F) {
				matrixStack.mulPose(new Quaternionf().rotationZYX(body.zRot, body.yRot, body.xRot));
			}

			// 叠加物理位移。数值来自 BreastPhysics，除以 32 换算成模型单位
			// （物理那边用的量纲与模型像素不同，32 是两者之间的换算系数）。
			if (bounceEnabled) {
				matrixStack.translate(physPositionX / 32f, 0, 0);
				matrixStack.translate(0, physPositionY / 32f, 0);
			}

			// 叠加位置偏移，把几何盒摆到胸部应该在的地方：
			//   X：用户配置的左右偏移
			//   Y：0.05625 是一个固定的向下基准量（0.05625 * 16 = 0.9 像素），再加上用户偏移
			//   Z：先减去一个固定的 2 像素基准量，再叠加 zOff 和用户偏移。
			//      zOff = (1 - bSize) / 16，罩杯越大它越小，也就是总偏移随尺寸单调变化，
			//      保证不同罩杯下盒子都贴着躯干表面；盒子自身 3~5 像素的深度提供凸起的厚度。
			// MCA 适配：额外往前推 0.006 格，用于穿出 MCA 村民的衣服层。
			//
			// 原理：FGM 并不"计算"该露出多少，而是让盒子穿入躯干、靠深度遮挡自动裁切。
			//   玩家      → 衣服层 jacket 只外扩 0.25 像素，盒子前端 -1.7 就几乎贴着表面
			//   MCA 村民  → 衣服层 ClothingLayer 外扩 1 像素，同样位置会被整个盖住
			// 因此村民需要额外前推。该数值由游戏内实测确定，可按需微调：
			//   调大 → 胸部更凸出，过大则明显悬浮在身体外
			//   调到 0 → 被衣服层完全遮住，看不见
			matrixStack.translate(breastOffsetX * 0.0625f, 0.05625f + (breastOffsetY * 0.0625f), zOff - 0.0625f * 2f - 0.006f + (breastOffsetZ * 0.0625f)); //shift down to correct position

			// 双胸模式：先把这一半往自己那一侧挪 2 像素。
			// 原因见方法注释——旋转是绕原点进行的，需要先把"旋转中心"搬到这一半自己的位置上。
			if (!uniboob) {
				matrixStack.translate(-0.0625f * 2 * (left ? 1 : -1), 0, 0);
			}
			// 叠加物理算出的左右倾角。bounceRotation 是角度制，乘 PI/180 转成弧度。
			// 绕 Y 轴旋转：表现为胸部被"甩"向一侧。
			if (bounceEnabled) {
				matrixStack.mulPose(new Quaternionf().rotationXYZ(0, (float) (bounceRotation * (Math.PI / 180f)), 0));
			}
			// 旋转完毕，把刚才那 2 像素的临时位移还原，让盒子回到原本的位置
			if (!uniboob) {
				matrixStack.translate(0.0625f * 2 * (left ? 1 : -1), 0, 0);
			}

			// 基础前倾角：始终以罩杯尺寸为基准，罩杯越大垂得越自然。
			// 参考 FGM 原设计，罩杯 0.7 时约 -24.5 度；MCA 原版则是固定 54 度。
			float rotation = breastSize;
			if (bounceEnabled) {
				// 按尺寸微微下移，补偿几何盒旋转后视觉重心的偏移
				matrixStack.translate(0, -0.035f * breastSize, 0); //shift down to correct position
				// 物理位移作为「叠加量」，而不是整体替换基础前倾。
				// FGM 原实现是 rotation = -physPositionY / 12f（完全由物理接管），
				// 但实体静止时 physPositionY 接近 0（实测 NPC 约 0.37），
				// 会让前倾角归零、胸部完全直立，导致朝内的 SOUTH 面暴露在最上方。
				rotation = breastSize - physPositionY / 12f;
			}
			// 两级上限保护：先限制不超过"尺寸 + 0.2"，再限制绝对上限 1。
			// 没有这两行的话，位移数值异常时可能出现胸部翻转的夸张效果。
			rotation = Math.min(rotation, breastSize + 0.2f);
			rotation = Math.min(rotation, 1); //hard limit for MAX

			// 有胸甲时把胸部略微往前推 0.01，避免乳房和胸甲盒子共面产生闪烁
			if (isChestplateOccupied) {
				matrixStack.translate(0, 0, 0.01f);
			}

			// -------------------- 最终的两个旋转 --------------------
			// 向外张开角，由度转弧度。
			// 符号在这里按左右决定，而不是靠调用方传负值：
			// 胸部几何盒的 x 范围是 [-4,0]（left=true，位于 -X 半边）与 [0,4]（left=false，+X 半边），
			// 绕 Y 轴旋转时正角会让朝前方向（-Z）偏向 -X，因此 -X 半边取正、+X 半边取负，
			// 两半才会各自向外张开。若调用方也取一次负，两次负号抵消，两个胸会朝同一侧转。
			float outwardRad = outwardAngle * Mth.DEG_TO_RAD;
			// JOML 的链式调用是「右乘」，也就是局部旋转，所以实际生效顺序与书写顺序相反：
			//   先绕 X 轴前倾（-35 度 × rotation，rotation 越大垂得越狠）
			//   再绕 Y 轴向外张开（张角由乳沟参数决定）
			// 这个顺序很关键：交换两者会让"向外张"变成"绕世界 Y 轴旋转"，姿势立刻失真。
			Quaternionf rotationTransform = new Quaternionf()
				.rotationY(left ? outwardRad : -outwardRad)
				.rotateX(-35F * rotation * Mth.DEG_TO_RAD);

			// 呼吸起伏：用一条余弦波驱动额外的前倾角。
			//   tickCount * 0.09 是相位，周期 = 2π/0.09 ≈ 70 tick ≈ 3.5 秒一次呼吸
			//   振幅被限制在 [0, 0.9] 度，所以起伏非常轻微，只是让静止时不死板
			if (breathingAnimation) {
				float f5 = -Mth.cos(entity.tickCount * 0.09F) * 0.45F + 0.45F;
				rotationTransform.rotateX(f5 * Mth.DEG_TO_RAD);
			}

			matrixStack.mulPose(rotationTransform);
			// X 轴缩到 99.95%：把几何盒横向"收"进一点点，避免与躯干表面处于同一深度
			// 造成 z-fighting（两个面互相争抢前后顺序，表现为持续闪烁）
			matrixStack.scale(0.9995f, 1f, 1f); //z-fighting FIXXX

			// 变换准备完毕，开始真正写顶点（乳房本体 + 外套层 + 胸甲 + 纹饰 + 附魔光效）
			renderBreast(entity, armorStack, matrixStack, bufferSource, breastRenderType, light, overlay, alpha, left, hasJacketLayer);
		} catch(Exception e) {
			WildfireGender.LOGGER.error("Failed to render breast", e);
		}
		// 与开头的 pushPose 配对，还原矩阵，避免影响后续渲染
		matrixStack.popPose();
	}

	/**
	 * 取实体的贴图，用作胸部的贴图来源。
	 *
	 * <p>MCA 村民没有一张现成的静态皮肤文件——它的外观（身体 + 脸 + 衣服 + 头发）
	 * 由 {@link DynamicSkinCache} 在运行时多层拼合成一张 64×64 贴图，布局与原版
	 * 玩家皮肤一致，因此 {@code GenderLayer} 里那套 UV 常量可以直接对上。</p>
	 *
	 * <p>这一步是 {@link #render} 里那个「{@code entityTexture != null}」分支的判定依据：
	 * 返回 {@code null} 就只画胸甲、不画乳房本体。</p>
	 *
	 * @return 该实体的拼合贴图；非 MCA 村民返回 {@code null}
	 */
	@Nullable
	private ResourceLocation getBreastTexture(ENTITY entity) {
		return entity instanceof VillagerLike<?> ? DynamicSkinCache.getOrCreateStitchedSkin(entity) : null;
	}

	/**
	 * 把当前坐标系调整成「外套层」的坐标系：整体放大 5% 并往前挪一点。
	 *
	 * <p>外套层是皮肤上比身体大一圈的夹克/衬衫，尺寸本就略大于身体，
	 * 所以这里先放大再画，让它能包住胸部而不穿模。</p>
	 */
	private void shiftForJacket(PoseStack matrixStack) {
		matrixStack.translate(0, 0, -0.015f);
		matrixStack.scale(1.05f, 1.05f, 1.05f);
	}

	/**
	 * 真正写出顶点数据的环节，按「一层压一层」的顺序绘制：
	 *
	 * <ol>
	 *   <li><b>乳房本体</b>（{@code lBreast}/{@code rBreast}）—— 用实体自己的皮肤贴图</li>
	 *   <li><b>外套层</b>（{@code lBreastWear}/{@code rBreastWear}）—— 皮肤上那层夹克/衬衫，
	 *       需要在放大 5% 的坐标系里再画一遍</li>
	 *   <li><b>胸甲鼓包</b>（{@code lBoobArmor}/{@code rBoobArmor}）—— 让胸甲看起来也被撑起</li>
	 *   <li><b>盔甲纹饰</b>（Armor Trim）—— 与胸甲同形状，叠一层纹饰贴图</li>
	 *   <li><b>附魔光效</b>（hasFoil）—— 附魔物品特有的紫色流光</li>
	 * </ol>
	 *
	 * <p>调用本方法时，{@link PoseStack} 已经由 {@link #renderBreastWithTransforms}
	 * 调整到胸部的位置和朝向，所以这里只需要处理"层与层之间"的微小偏移。</p>
	 *
	 * @param breastRenderType 为 {@code null} 时跳过乳房本体与外套层，只画胸甲部分
	 * @param alpha            透明度，最终会写进顶点的颜色里
	 * @param left             画左半边还是右半边
	 * @param hasJacketLayer   是否需要额外画外套层
	 */
	private void renderBreast(ENTITY entity, ItemStack armorStack, PoseStack matrixStack, MultiBufferSource bufferSource,
		@Nullable RenderType breastRenderType, int light, int overlay, float alpha, boolean left, boolean hasJacketLayer) {
		if (breastRenderType != null) {
			//Only render the breasts if we have a render type for them
			VertexConsumer vertexConsumer = bufferSource.getBuffer(breastRenderType);
			// 顶点颜色 = 白色 + 透明度。FastColor.as8BitChannel 把 0~1 的 float 量化成 0~255 的整数通道
			int color = FastColor.ARGB32.color(FastColor.as8BitChannel(alpha), 0xFFFFFFFF);
			// 先画乳房本体
			renderBox(left ? lBreast : rBreast, matrixStack, vertexConsumer, light, overlay, color);
			if (hasJacketLayer) {
				// 再画外套层：先做 shiftForJacket 的放大+位移，让外套包在乳房外面
				shiftForJacket(matrixStack);
				renderBox(left ? lBreastWear : rBreastWear, matrixStack, vertexConsumer, light, overlay, color);
			}
		} else if (hasJacketLayer) {//Copy exact size
			// 不画乳房本体，但外套层仍然要画，而且尺寸/位置必须与画了乳房时完全一致
			// （否则会出现"脱下/穿上胸甲时外套忽然缩小"的观感），所以照样执行一次同样的变换。
			shiftForJacket(matrixStack);
		}
		//TODO: Eventually we may want to expose a way via the API for mods to be able to override rendering
		// be it because they are not an armor item or the way they render their armor item is custom
		//Render Breast Armor
		// 画胸甲「鼓包」：拿胸甲的贴图，按乳房盒子的形状再画一份，
		// 这样胸甲看起来就是被胸部撑起来的，而不是贴着平胸的板子。
		if (!armorStack.isEmpty() && armorStack.getItem() instanceof ArmorItem armorItem) {
			// 单独保存/恢复矩阵，因为胸甲需要一组自己的微调
			matrixStack.pushPose();
			// 左胸往右偏、右胸往左偏各 0.001（避免左右两半完全共面），
			// 再整体往下 0.015、往身前挪 0.015，贴合身体轮廓
			matrixStack.translate(left ? 0.001f : -0.001f, 0.015f, -0.015f);
			// 横向放大 5%，让胸甲能"包"住乳房，而不是嵌进去
			matrixStack.scale(1.05f, 1, 1);
			WildfireModelRenderer.BreastModelBox armor = left ? lBoobArmor : rBoobArmor;

			Holder<ArmorMaterial> material = armorItem.getMaterial();
			// 可染色盔甲（皮革）取染料颜色，没有染料时用默认的棕色 0xFFA06540；
			// 不可染色的盔甲用纯白 0xFFFFFFFF（贴图本身带颜色，白色表示不额外着色）
			int color = armorStack.is(ItemTags.DYEABLE) ? DyedItemColor.getOrDefault(armorStack, 0xFFA06540) : 0xFFFFFFFF;
			// 一种盔甲材质可能有多层贴图（例如皮革底层 + 染色层），逐层叠着画
			for (Layer layer : material.value().layers()) {
				// 通过 NeoForge 的钩子取这一层的贴图，便于其他模组自定义盔甲外观
				ResourceLocation armorTexture = ClientHooks.getArmorTexture(entity, armorStack, layer, false, EquipmentSlot.CHEST);

				// armorCutoutNoCull：盔甲专用渲染类型，不做背面剔除（盔甲是薄壳，剔除会露洞）
				RenderType armorType = RenderType.armorCutoutNoCull(armorTexture);
				VertexConsumer armorVertexConsumer = bufferSource.getBuffer(armorType);
				// 只有「可染色」的层才用染料颜色，其余层一律纯白
				renderBox(armor, matrixStack, armorVertexConsumer, light, OverlayTexture.NO_OVERLAY, layer.dyeable() ? color : 0xFFFFFFFF);
			}

			// 盔甲纹饰（锻造台上加的图案）：与胸甲同形状，额外叠一层纹饰贴图
			ArmorTrim trim = armorStack.get(DataComponents.TRIM);
			if (trim != null) {
				// 从图集里取出纹饰对应的 sprite 区域，并包成 VertexConsumer
				TextureAtlasSprite sprite = this.armorTrimAtlas.getSprite(trim.outerTexture(material));
				VertexConsumer trimVertexConsumer = sprite.wrap(bufferSource.getBuffer(Sheets.armorTrimsSheet(trim.pattern().value().decal())));
				renderBox(armor, matrixStack, trimVertexConsumer, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
			}

			// 附魔光效：附魔过的胸甲会有一层紫色流光
			if (armorStack.hasFoil()) {
				renderBox(armor, matrixStack, bufferSource.getBuffer(RenderType.armorEntityGlint()), light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
			}

			matrixStack.popPose();
		}
	}

	/**
	 * 把一个几何盒（{@link WildfireModelRenderer.ModelBox}）的所有顶点写入缓冲。
	 *
	 * <p>这是整条渲染链最底层的一步，四个要点：</p>
	 * <ul>
	 *   <li>{@code vertex.x() / 16.0F}：模型盒里的坐标单位是"模型像素"，
	 *       而顶点缓冲期望的单位是格，所以要除以 16。</li>
	 *   <li>{@code matrix3f}：只取变换矩阵中<b>不含平移</b>的 3×3 部分来变换法线。
	 *       法线是方向向量，平移对它没有意义；若拿 4×4 一起乘会导致光照错乱。</li>
	 *   <li>每个顶点都要同时提供坐标、颜色、UV、覆盖层、光照和法线，缺一不可。</li>
	 *   <li>一个盒子有 5 个面（{@link WildfireModelRenderer.ModelBox} 的 quads），
	 *       每面 4 个顶点——所以这里是一个「5 个面 × 4 个顶点」的双层循环。</li>
	 * </ul>
	 */
	private static void renderBox(WildfireModelRenderer.ModelBox model, PoseStack matrixStack, VertexConsumer bufferIn, int light, int overlay, int color) {
		Matrix4f matrix4f = matrixStack.last().pose();
		Matrix3f matrix3f =	matrixStack.last().normal();
		for (WildfireModelRenderer.TexturedQuad quad : model.quads) {
			// 把法线从模型空间变换到世界空间，否则光照会算错
			Vector3f vector3f = new Vector3f(quad.normal.getX(), quad.normal.getY(), quad.normal.getZ());
			vector3f.mul(matrix3f);
			for (PositionTextureVertex vertex : quad.vertexPositions) {
				bufferIn.addVertex(matrix4f, vertex.x() / 16.0F, vertex.y() / 16.0F, vertex.z() / 16.0F)
					.setColor(color)
					.setUv(vertex.texturePositionX(), vertex.texturePositionY())
					.setOverlay(overlay)
					.setLight(light)
					.setNormal(vector3f.x(), vector3f.y(), vector3f.z());
			}
		}
	}
}
