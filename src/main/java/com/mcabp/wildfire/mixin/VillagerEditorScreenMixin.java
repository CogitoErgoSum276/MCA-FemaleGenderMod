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

package com.mcabp.wildfire.mixin;

import com.mcabp.wildfire.client.gui.ValueShowingSlider;
import com.mcabp.wildfire.main.config.GeneralClientConfig;
import com.mcabp.wildfire.main.entitydata.EntityConfig;
import com.mcabp.wildfire.main.entitydata.MCAVillagerGenes;
import java.util.Locale;
import java.util.UUID;
import java.util.function.DoubleFunction;
import net.conczin.mca.client.gui.VillagerEditorScreen;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.ai.Genetics;
import net.conczin.mca.util.compat.ButtonWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <h2>给 MCA 的村民编辑器补一个「更多胸部设定」页</h2>
 *
 * <p>MCA 的村民编辑器（{@link VillagerEditorScreen}）负责调整村民的全部外观与性格，
 * 但它不认识本模组追加的 4 个胸部参数。本类通过 Mixin 在<b>不修改 MCA 源码</b>的前提下，
 * 在主页面签栏的「身体」之后插入一个新页。</p>
 *
 * <p>之所以新开一页、而不是塞进「身体」页：身体页在乳房滑块下方紧接着就是一个占满屏幕的
 * 颜色选择器，纵向已无空位；横向也被「预设 / 导出」按钮占了左侧。新开一页既不动 MCA 的
 * 原有布局，也让这 4 个参数有充足的展示空间。</p>
 *
 * <h3>两处注入</h3>
 * <ol>
 *   <li>{@code getPages} —— 在返回的页面列表里把 {@link #BREAST_PAGE} 插到 {@code body} 之后。
 *       页签宽度由 MCA 按页面数量均分，所以页数增加不会溢出屏幕，只是每个页签变窄。</li>
 *   <li>{@code setPage} —— 该方法在每次切页时被调用（且会先清空全部控件再重建）。
 *       切到这个新页时，MCA 自己的 {@code switch} 不认识页名、内容区是空的，
 *       正好用来摆放 4 个滑块。</li>
 * </ol>
 *
 * <p>不需要额外处理页签高亮：MCA 用 {@code mainPage.equals(page)} 判断主页签状态，
 * 我们这一页的名字与页签名一致，天然就是选中态。</p>
 *
 * <h3>为什么要 extends Screen</h3>
 * <p>Mixin 类要调用目标类从父类继承来的 {@code addRenderableWidget}，以及读取
 * {@code width} / {@code height} 字段，最省事的做法就是让本类继承同一个父类。
 * 下面的构造器只是为了满足 Java 的语法要求，Mixin 不会真的用它来实例化
 * —— 目标对象仍然由 MCA 用 {@code VillagerEditorScreen} 自己的构造器创建。</p>
 *
 * <h3>滑块改动如何生效</h3>
 * <p>滑块回调调用 {@code Genetics#setGene}，写入的是村民实体的同步数据。
 * MCA 的编辑器在点击「完成」时会把这个实体的 NBT 整体发给服务端
 * （{@code VillagerEntityMCA#addAdditionalSaveData} 里含 {@code getTypeDataManager().save}），
 * 而这些基因正好会被一并写进 NBT，因此<b>无需额外的网络代码</b>。</p>
 *
 * <p>另外，编辑器的预览模型拖滑块时不会立刻刷新 —— 这是 MCA 原有的行为
 * （预览用的是一份独立实体，只在切页时同步），MCA 自己的罩杯滑块也是如此。</p>
 */
@Mixin(VillagerEditorScreen.class)
public abstract class VillagerEditorScreenMixin extends Screen {

	/** 新增页面的内部名，同时用作翻译键后缀：{@code gui.villager_editor.page.breast_extra}。 */
	private static final String BREAST_PAGE = "breast_extra";

	/** 新页面插在哪个主页签之后。 */
	private static final String ANCHOR_PAGE = "body";

	/** 编辑器内容区的宽度，与 MCA 的 {@code DATA_WIDTH} 一致（该常量在目标类中是 protected static final，直接写字面量更省事）。 */
	private static final int PANEL_WIDTH = 175;

	private static final int SLIDER_HEIGHT = 20;
	/**
	 * 控件的行距，比控件高度略大以留出间隙。
	 *
	 * <p>本页一共要排 7 行控件：起点受上方页签栏（{@code height/2 - 105}）限制，
	 * 行距再大会顶出屏幕下方，所以压到 22。</p>
	 */
	private static final int ROW_GAP = 22;
	/** 两列控件之间的水平间隔。 */
	private static final int COLUMN_GAP = 5;

	/** 目标类里的村民实体，编辑器的一切修改都作用在它身上。 */
	@Shadow protected VillagerEntityMCA villager;

	/** 被编辑对象的 UUID。与 {@link #playerUUID} 相等即代表「玩家在编辑自己」。 */
	@Shadow private UUID villagerUUID;

	/** 操作者的 UUID。 */
	@Shadow private UUID playerUUID;

	/** 仅为满足 Java 语法，Mixin 不会调用它。 */
	protected VillagerEditorScreenMixin(Component title) {
		super(title);
	}

	/**
	 * 把「更多胸部设定」插到「身体」页签之后。
	 *
	 * <p>玩家与非玩家（NPC）的页面列表长度不同（3 个 vs 5 个），所以这里不写死位置，
	 * 而是先找到 {@code body} 再插到它后面，这样 MCA 以后增删页面也不会串位。</p>
	 */
	@Inject(method = "getPages", at = @At("RETURN"), cancellable = true)
	private void mcabp$insertBreastPage(CallbackInfoReturnable<String[]> cir) {
		// 玩家在编辑自己（针线、梳子等）时跳过 —— 本模组只服务 MCA 的 NPC。
		// 玩家的胸部归 Female Gender Mod 本体管，这一页的参数对玩家完全不生效，
		// 页签留着只会让人以为「设了却没反应」。
		//
		// 另外注意：新世界创建时的 DestinyScreen 自己覆写了 getPages，压根走不到这里。
		if (villagerUUID.equals(playerUUID)) {
			return;
		}

		String[] original = cir.getReturnValue();
		int anchor = -1;
		for (int i = 0; i < original.length; i++) {
			if (ANCHOR_PAGE.equals(original[i])) {
				anchor = i;
				break;
			}
		}
		// 兜底：万一将来 MCA 改名导致找不到锚点，就追加到末尾，
		// 保证页签一定出现，而不是静默失效。
		if (anchor < 0) {
			anchor = original.length - 1;
		}

		String[] extended = new String[original.length + 1];
		System.arraycopy(original, 0, extended, 0, anchor + 1);
		extended[anchor + 1] = BREAST_PAGE;
		System.arraycopy(original, anchor + 1, extended, anchor + 2, original.length - anchor - 1);
		cir.setReturnValue(extended);
	}

	/**
	 * 切入本页时挂上 4 个参数滑块。
	 *
	 * <p>注入点是 {@code setPage} 末尾：此时 MCA 已经完成清空控件、绘制页签栏、
	 * 放置「预设 / 导出 / 完成」按钮等全部工作，内容区是空的，可以放心摆放自己的控件。</p>
	 */
	@Inject(method = "setPage", at = @At("TAIL"))
	private void mcabp$addBreastSliders(String page, CallbackInfo ci) {
		if (!BREAST_PAGE.equals(page)) {
			return;
		}

		// 大部分控件排成两列（每列约 85 像素，和 MCA 自己的身高/体宽滑块同宽），
		// 几个重点项（物理总开关、两个物理手感）独占整行，看得更清楚。
		//
		// 注意右侧这半片屏幕没有 MCA 的按钮 ——「预设 / 导出」和「完成」都在中线左侧
		// （x 从 width/2 - 165 到 width/2 - 20），所以纵向不必为它们让位，
		// 只有上方横跨全宽的页签栏限制了起点。
		int columnWidth = (PANEL_WIDTH - COLUMN_GAP) / 2;
		int x1 = this.width / 2;
		int x2 = x1 + columnWidth + COLUMN_GAP;

		// 纵向排 7 行：从页签栏下方 -62 开始，到 +70 结束（底边 +90）。
		int y = this.height / 2 - 62;

		// ---- 行 1~2：这个村民自己的 4 个外观参数 ----
		addGeneSlider(x1, y, columnWidth, MCAVillagerGenes.SEPARATION, this::mcabp$formatSeparation);
		addGeneSlider(x2, y, columnWidth, MCAVillagerGenes.HEIGHT, this::mcabp$formatHeight);
		y += ROW_GAP;
		addGeneSlider(x1, y, columnWidth, MCAVillagerGenes.DEPTH, this::mcabp$formatDepth);
		addGeneSlider(x2, y, columnWidth, MCAVillagerGenes.ROTATION, this::mcabp$formatRotation);

		// ---- 行 3：物理总开关，独占整行 ----
		y += ROW_GAP;
		addToggle(x1, y, PANEL_WIDTH, "mcabp.config.physics_enabled",
			  GeneralClientConfig.INSTANCE.physicsEnabled);

		// ---- 行 4~5：其余本机开关 ----
		// 与上面的滑块性质不同：滑块改的是这个村民的基因（会同步给其他玩家），
		// 开关与下面的物理手感改的是自己的客户端配置（只影响自己）。
		// 它们并不存在村民身上，只是把入口放在这里，方便和外观参数一起调。
		y += ROW_GAP;
		addToggle(x1, y, columnWidth, "mcabp.config.rendering_enabled",
			  GeneralClientConfig.INSTANCE.renderingEnabled);
		addToggle(x2, y, columnWidth, "mcabp.config.dual_physics",
			  GeneralClientConfig.INSTANCE.dualPhysicsEnabled);
		y += ROW_GAP;
		addToggle(x1, y, columnWidth, "mcabp.config.show_in_armor",
			  GeneralClientConfig.INSTANCE.showInArmor);
		addToggle(x2, y, columnWidth, "mcabp.config.armor_physics_override",
			  GeneralClientConfig.INSTANCE.armorPhysicsOverride);

		// ---- 行 6~7：物理手感，各占整行（滑块越长越好微调）----
		y += ROW_GAP;
		addConfigSlider(x1, y, PANEL_WIDTH, "mcabp.config.bounce_multiplier",
			  GeneralClientConfig.INSTANCE.bounceMultiplier, 0.0D, 1.0D);
		y += ROW_GAP;
		addConfigSlider(x1, y, PANEL_WIDTH, "mcabp.config.floppy_multiplier",
			  GeneralClientConfig.INSTANCE.floppyMultiplier, 0.25D, 1.0D);
	}

	/**
	 * 创建一个开关按钮：点击即在「开 / 关」之间切换，并立刻写入客户端配置文件。
	 *
	 * <p>按钮文字直接复用配置项自己的翻译键（{@code mcabp.config.*}），
	 * 开/关状态用原版内置的 {@code options.on} / {@code options.off}，
	 * 这样中英文环境下都能正确显示。</p>
	 *
	 * <p>点击后调用 {@link #init()} 重建整个界面 —— 这是 MCA 编辑器里刷新控件文字的惯用做法，
	 * 它内部会走到 {@code setPage}，本类的注入也会随之重跑。</p>
	 */
	private void addToggle(int x, int y, int width, String nameKey, ModConfigSpec.BooleanValue config) {
		Component label = Component.translatable(nameKey)
			  .append(Component.literal(" "))
			  .append(Component.translatable(config.get() ? "options.on" : "options.off"));
		addRenderableWidget(new ButtonWidget(x, y, width, SLIDER_HEIGHT, label, button -> {
			config.set(!config.get());
			GeneralClientConfig.INSTANCE.save();
			init();
		}));
	}

	/**
	 * 创建一个对应客户端配置项的滑块。
	 *
	 * <p>与 {@link #addGeneSlider} 的区别在于「值存在哪」：基因滑块的改动写进村民实体的同步数据，
	 * 会随编辑器一起发给服务端；这里的改动只写进本机配置文件（{@code mcabp-client.toml}），
	 * 立即生效、不影响别人。</p>
	 *
	 * <p>滑块内部统一用 0~1 的归一化值（{@code AbstractSliderButton} 的规定），
	 * 所以配置项的 {@code [min, max]} 区间要来回换算一遍。</p>
	 */
	private void addConfigSlider(int x, int y, int width, String nameKey, ModConfigSpec.DoubleValue config,
								 double min, double max) {
		double span = max - min;
		addRenderableWidget(new ValueShowingSlider(x, y, width, SLIDER_HEIGHT,
			  Component.translatable(nameKey),
			  (config.get() - min) / span,
			  v -> mcabp$format("%.2f", min + v * span),
			  v -> {
				  config.set(min + v * span);
				  GeneralClientConfig.INSTANCE.save();
			  }));
	}

	/**
	 * 给 MCA 原版「身体」页的罩杯滑块也换上会显示数值的版本。
	 *
	 * <p>{@code addGeneSlider} 是目标类的私有方法，被身高、体宽、罩杯、语音等多个滑块共用。
	 * 这里只接管罩杯那一个，其余原样交回 MCA 自己处理。</p>
	 */
	@Inject(method = "addGeneSlider", at = @At("HEAD"), cancellable = true)
	private void mcabp$showBustValue(int x, int y, int widgetWidth, Genetics.GeneType gene, CallbackInfo ci) {
		if (gene != Genetics.BREAST) {
			return;
		}
		Genetics genetics = villager.getGenetics();
		addRenderableWidget(new ValueShowingSlider(x, y, widgetWidth, SLIDER_HEIGHT,
			  Component.translatable(gene.getTranslationKey()),
			  genetics.getGene(gene),
			  this::mcabp$formatBustSize,
			  value -> genetics.setGene(gene, value.floatValue())));
		ci.cancel();
	}

	/**
	 * 创建一个基因滑块，并实时显示它折算出的实际数值。
	 *
	 * <p>显示的不是基因本身的 0~1 抽象值 —— 那个数字看不出差别有多大，
	 * 而是换算成物理量之后的结果（角度、偏移量）。换算全部复用
	 * {@link EntityConfig} 的映射方法，与渲染层走的是同一套公式。</p>
	 */
	private void addGeneSlider(int x, int y, int width, Genetics.GeneType gene, DoubleFunction<String> formatter) {
		Genetics genetics = villager.getGenetics();
		addRenderableWidget(new ValueShowingSlider(x, y, width, SLIDER_HEIGHT,
			  Component.translatable(gene.getTranslationKey()),
			  genetics.getGene(gene),
			  formatter,
			  value -> genetics.setGene(gene, value.floatValue())));
	}

	// ==================== 滑块上显示的数值 ====================

	/** 罩杯大小：显示 FGM 的罩杯值（0 ~ 1.0），已计入二次发育的倍率。 */
	private String mcabp$formatBustSize(double gene) {
		return mcabp$format("%.2f", EntityConfig.mapBustSize((float) gene, mcabp$growthMultiplier()));
	}

	/** 当前村民的二次发育倍率（1.0 ~ 1.25）。 */
	private float mcabp$growthMultiplier() {
		return EntityConfig.growthMultiplier(villager.getGenetics().getGene(MCAVillagerGenes.BREAST_GROWTH));
	}

	/** 分离：显示 X 偏移量，负值代表分开、正值代表靠拢。 */
	private String mcabp$formatSeparation(double gene) {
		return mcabp$format("%+.2f", EntityConfig.mapSeparation((float) gene));
	}

	/** 高度：显示 Y 偏移量，正值向上、负值向下。 */
	private String mcabp$formatHeight(double gene) {
		return mcabp$format("%+.2f", EntityConfig.mapHeight((float) gene));
	}

	/** 深度：显示 Z 偏移量，0 表示完全不缩、越负缩得越深。 */
	private String mcabp$formatDepth(double gene) {
		return mcabp$format("%.2f", EntityConfig.mapDepth((float) gene));
	}

	/** 旋转：显示向外张开的角度。乳沟参数在渲染时会乘以 100 换算成 0~10 度，这里用同一个换算。 */
	private String mcabp$formatRotation(double gene) {
		return mcabp$format("%.1f°", EntityConfig.mapCleavage((float) gene) * 100F);
	}

	/** 固定用 ROOT 区域格式化，避免某些语言环境下小数点被写成逗号。 */
	private static String mcabp$format(String pattern, double value) {
		return String.format(Locale.ROOT, pattern, value);
	}
}
