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

import com.mcabp.wildfire.main.entitydata.EntityConfig;
import com.mcabp.wildfire.main.entitydata.MCAVillagerGenes;
import net.conczin.mca.entity.ai.Genetics;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <h2>为本模组追加的 4 个胸部基因指定随机分布</h2>
 *
 * <p>MCA 的 {@code Genetics#randomize()} 对所有基因一视同仁地取 {@code [0,1]} 均匀随机数
 * （身高、体宽例外）。「均匀」意味着极端值和中间值一样常见，这对胸部的外观参数来说效果并不好，
 * 所以本模组按各自的语义重新安排了分布。</p>
 *
 * <p><b>只调整本模组自己追加的基因，不干预 MCA 原有的任何基因</b> ——
 * 尤其是罩杯（{@code Genetics.BREAST}），MCA 原本就给它用 {@code [0,1]} 均匀分布，
 * 正好符合需要，所以这里完全不碰。</p>
 *
 * <p>四个参数的分布表、分布形状的说明与具体公式，全部收在
 * {@link MCAVillagerGenes#randomizeAppearance} ——「给老村民补随机」用的是同一份实现，
 * 刻意不在这里另写一遍。</p>
 *
 * <h3>为什么要注入在 TAIL</h3>
 * <p>本方法在 {@code randomize()} 全部执行完之后才介入，因此不会打乱 MCA 原有的随机序列
 * （肤色、脸型、身高都还按原样生成）。如果注入在 {@code HEAD}，多消耗的随机数会让
 * 同一次随机的其它基因全部错位。</p>
 *
 * <h3>与繁殖的关系</h3>
 * <p>只有自然生成与 {@code /summon} 会走 {@code randomize()}，这里负责给 4 个外观基因
 * 安排分布、并把发育度设到基准。繁殖走的是 {@code Genetics#combine}
 * （父母基因加权混合 + 小突变），本类<b>只在其末尾把发育度抹回基准值</b> ——
 * 也就是发育度不参与继承，每个新生儿都从「未发育」起步。</p>
 *
 * <h3>装模组之前就存在的村民</h3>
 * <p>它们不走 {@code randomize()} —— 实体从存档加载时不会重新生成，
 * 于是 4 个外观基因读出来一律是默认值 0.5，全村长得一模一样。这部分由
 * {@link MCAVillagerGenes#rerollLegacyAppearance} 在实体加入世界时补一次随机。</p>
 */
@Mixin(Genetics.class)
public abstract class GeneticsMixin {

	/** 目标类的随机数源。 */
	@Shadow private RandomSource random;

	/**
	 * 在 {@code randomize()} 完成后，为本模组追加的 4 个胸部基因安排分布。
	 *
	 * <p>这 4 个基因由本模组追加（见 {@link MCAVillagerGenes}），MCA 不认识它们，
	 * 需要在这里赋初值。罩杯（{@code Genetics.BREAST}）不在其中 —— 它由 MCA 自己管。</p>
	 */
	@Inject(method = "randomize", at = @At("TAIL"))
	private void mcabp$applyChestDistributions(CallbackInfo ci) {
		// Mixin 里访问不了目标类的私有方法，所以直接调用 self 的公开 setGene
		Genetics self = (Genetics) (Object) this;

		// 4 个外观基因的分布公式与「给老村民补随机」共用同一份实现
		MCAVillagerGenes.randomizeAppearance(self, random);

		// 发育度：初始为基准值，也就是「未经二次发育」的 1.0 倍。
		// 之后由怀孕推动增长，见 PregnancyMixin。
		self.setGene(MCAVillagerGenes.BREAST_GROWTH, EntityConfig.GROWTH_BASELINE);
	}

	/**
	 * 繁殖时把发育度抹回基准值，让它不参与继承。
	 *
	 * <p>MCA 的 {@code combine()} 会把父母的<b>所有</b>基因加权混合给子代，
	 * 其中就包括本模组追加的发育度。那样一来母亲怀孕累积的发育会部分传给子代，
	 * 繁衍几代之后整个村子的发育度基线会被慢慢抬高。</p>
	 *
	 * <p>按需求这里把它抹掉：<b>每个新生儿都从「未发育」的 1.0 倍起步</b>，
	 * 只有自己成年后怀孕才会增长。基础罩杯仍然照常继承，不受影响。</p>
	 *
	 * <p>只注入 2 参数版本即可 —— 3 参数的 {@code combine(mother, father, seed)}
	 * 内部就会调用它，所以自然生产与婴儿物品两条路径都会经过这里。</p>
	 */
	@Inject(method = "combine(Lnet/conczin/mca/entity/ai/Genetics;Lnet/conczin/mca/entity/ai/Genetics;)V", at = @At("TAIL"))
	private void mcabp$resetGrowthOnInherit(Genetics mother, Genetics father, CallbackInfo ci) {
		((Genetics) (Object) this).setGene(MCAVillagerGenes.BREAST_GROWTH, EntityConfig.GROWTH_BASELINE);
	}
}
