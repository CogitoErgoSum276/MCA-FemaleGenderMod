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
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.entity.ai.Genetics;
import net.conczin.mca.entity.ai.Pregnancy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <h2>怀孕带来的乳房二次发育</h2>
 *
 * <p>每次成功怀孕都会让村民的罩杯再涨一点，最多可以累积到自然生成上限的
 * {@link EntityConfig#MAX_GROWTH} 倍（也就是 0.8 → 1.0）。</p>
 *
 * <h3>增长方式</h3>
 * <pre>
 *   新倍率 = min(当前倍率 × random(1.0, 1.25), 1.25)
 * </pre>
 *
 * <p>每次乘的是一个<b>随机</b>系数而不是固定的 1.25 —— 所以单次怀孕可能只涨一点点，
 * 也可能一次就接近上限；但由于每次乘数都不小于 1，倍率单调不减，
 * <b>怀孕次数足够多时必然收敛到 1.25 的上限</b>。</p>
 *
 * <p>产后不回落，这个倍率会一直保留在村民身上。但它<b>不会传给子代</b> ——
 * 繁衍时的基因混合结束后会把它抹回基准值，所以每个新生儿都从「未发育」起步，
 * 只有自己成年后怀孕才会增长（见 {@code GeneticsMixin#mcabp$resetGrowthOnInherit}）。</p>
 *
 * <h3>为什么注入 tryStartGestation</h3>
 * <p>{@code Pregnancy#tryStartGestation()} 是怀孕的<b>唯一入口</b> ——
 * 它内部会在校验通过后调用 {@code setPregnant(true)}。挂在它的返回值上，
 * 就能精确捕获「这一次真的怀上了」这个时刻，而不必每 tick 去比对怀孕状态。</p>
 *
 * <h3>为什么只认返回值 true</h3>
 * <p>该方法在几种情况下会返回 false 而不怀孕：已经怀着、任一方不育、没有伴侣等。
 * 这些都不该触发生育发育，所以直接看返回值即可。</p>
 */
@Mixin(Pregnancy.class)
public abstract class PregnancyMixin {

	/** 怀孕状态的持有者，也就是母体。 */
	@Shadow private VillagerEntityMCA mother;

	/**
	 * 成功怀孕时提升一次发育度。
	 *
	 * <p>只在服务端执行：基因是同步数据，服务端才是权威来源，
	 * 客户端自行修改会在下一次同步时被覆盖掉。</p>
	 */
	@Inject(method = "tryStartGestation", at = @At("RETURN"))
	private void mcabp$growBreasts(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() || mother.level().isClientSide) {
			return;
		}

		Genetics genetics = mother.getGenetics();
		float current = EntityConfig.growthMultiplier(genetics.getGene(MCAVillagerGenes.BREAST_GROWTH));
		// 乘一个 [1.0, MAX_GROWTH] 的随机数，再封顶
		float rolled = 1F + mother.getRandom().nextFloat() * (EntityConfig.MAX_GROWTH - 1F);
		float next = Math.min(current * rolled, EntityConfig.MAX_GROWTH);
		genetics.setGene(MCAVillagerGenes.BREAST_GROWTH, EntityConfig.growthGene(next));
	}
}
