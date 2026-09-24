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

import net.conczin.mca.entity.VillagerLike;
import net.conczin.mca.entity.ai.Genetics;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * <h2>本模组为 MCA 村民追加的胸部基因</h2>
 *
 * <p>MCA 原本只有 {@code Genetics.BREAST} 一个与胸部相关的基因，控制「罩杯大小」。
 * 本模组另外补上 4 个外观参数，让村民的胸部不再只有大小一个维度：</p>
 *
 * <table border="1">
 *   <caption>参数与映射</caption>
 *   <tr><th>基因</th><th>含义</th><th>基因值 0~1 映射到</th></tr>
 *   <tr><td>{@link #SEPARATION}</td><td>分离（两胸间距）</td><td>[-1, 1]，0.5 为居中</td></tr>
 *   <tr><td>{@link #HEIGHT}</td><td>高度（上下位置）</td><td>[-1, 1]，0.5 为居中</td></tr>
 *   <tr><td>{@link #DEPTH}</td><td>深度（前后位置）</td><td>[-1, 0]，1.0 为最靠外</td></tr>
 *   <tr><td>{@link #ROTATION}</td><td>旋转（向外张开的角度）</td><td>[0, 0.1]，对应 0~10 度</td></tr>
 *   <tr><td>{@link #BREAST_GROWTH}</td><td>发育度（怀孕累积）</td><td>[1, 1.25] 倍，0.5 为未发育</td></tr>
 *   <tr><td>{@link #CHEST_INITIALIZED}</td><td>外观初始化标记（内部用，无滑块）</td><td>0.5 未初始化 / 1.0 已初始化</td></tr>
 * </table>
 *
 * <p>映射本身在 {@link EntityConfig#syncFromVillager} 里完成，本类只负责「声明」这些基因。</p>
 *
 * <h3>为什么不用数组或循环，而是写 6 个常量</h3>
 * <p>因为每个基因都要能按名字单独引用（编辑器滑块、渲染时读取）。而且它们必须在<b>静态初始化</b>
 * 时创建，写法越直白越好。</p>
 *
 * <h3>加载时机（重要）</h3>
 * <p>{@code Genetics.GeneType} 的构造器有一个副作用：把自己注册进 MCA 的静态基因池
 * {@code Genetics.GENOMES}。村民实体的同步数据表是在 {@code Genetics.createTrackedData}
 * 里按这个池子逐项构建的，而该方法在<b>每个村民实体被创建时</b>调用。</p>
 *
 * <p>所以这 6 个基因必须在「世界上出现第一个 MCA 村民」之前完成注册，否则它们不会进入
 * 同步数据表，读写时会直接失败。{@link #bootstrap()} 就是为此存在的——它由
 * {@link com.mcabp.wildfire.main.WildfireGender} 在模组构造阶段调用，而模组构造早于
 * 任何实体创建。</p>
 *
 * <h3>副作用：本模组必须双端安装</h3>
 * <p>基因会被写进村民实体的同步数据（索引位置由基因池顺序决定），客户端与服务端必须持有
 * <b>完全相同的基因池</b>，否则同步数据的索引会错位，导致村民的性别、名字等全部错乱。
 * 因此本模组不能只装在客户端。</p>
 */
public final class MCAVillagerGenes {

	/** 分离：两胸之间的距离。越小越收紧、越大越分开。 */
	public static final Genetics.GeneType SEPARATION = new Genetics.GeneType("BreastSeparation");

	/** 高度：胸部整体的上下位置。 */
	public static final Genetics.GeneType HEIGHT = new Genetics.GeneType("BreastHeight");

	/** 深度：胸部整体的前后位置（越靠外越突出）。 */
	public static final Genetics.GeneType DEPTH = new Genetics.GeneType("BreastDepth");

	/** 旋转：两侧胸部各自向外张开的角度。 */
	public static final Genetics.GeneType ROTATION = new Genetics.GeneType("BreastRotation");

	/**
	 * 发育度：怀孕带来的二次发育累积。
	 *
	 * <p>基因值 {@code [0,1]} 映射成倍率 {@code [1, 1.25]} ——
	 * 基准值 0.5 表示「未经二次发育」的 1.0 倍，1.0 则是上限 1.25 倍。
	 * 换算见 {@link EntityConfig#growthMultiplier}。</p>
	 *
	 * <p>拿下限的 0.5 作为基准是有意的：MCA 给新同步字段的默认值正是 0.5，
	 * 因此旧存档里没有这个字段的村民会自然落在 1.0 倍，不会凭空变大。</p>
	 */
	public static final Genetics.GeneType BREAST_GROWTH = new Genetics.GeneType("BreastGrowth");

	/**
	 * 外观初始化标记：<b>仅供本模组内部使用</b> —— 没有对应的滑块，玩家也改不到。
	 *
	 * <p>MCA 给新同步字段的默认值是 0.5，于是「0.5 = 这个村民的外观还没被随机过」，
	 * 写入 {@link #INITIALIZED} 则表示已经初始化过。它就是识别
	 * 「装本模组之前就存在的村民」的依据（见 {@link #rerollLegacyAppearance}）。</p>
	 *
	 * <p>为什么不直接看那 4 个外观值是不是默认，而要专门加个标记：
	 * 玩家完全可以在编辑器里把这 4 个滑块全拖到中间值，那样值也是默认，
	 * 但打过标记就能认出「这是玩家手动设的、不是老村民」，不会被重新随机掉。</p>
	 */
	public static final Genetics.GeneType CHEST_INITIALIZED = new Genetics.GeneType("ChestInitialized");

	/** 外观「已初始化」的标记值。MCA 给新字段的默认值是 0.5，这里写 1.0 与之区分。 */
	private static final float INITIALIZED = 1F;

	/**
	 * 判定「已初始化」的阈值。
	 *
	 * <p>取 0.75 而不是恰好 1.0：与默认值 0.5 之间留足余量，
	 * 判据不会因为取值的细微差异而翻车。</p>
	 */
	private static final float INITIALIZED_THRESHOLD = 0.75F;

	/** MCA 给所有基因的默认值 —— 老村民读到的就是这个数。 */
	private static final float DEFAULT_GENE_VALUE = 0.5F;

	private MCAVillagerGenes() {
	}

	/**
	 * 触发本类的静态初始化，把上面 6 个基因注册进 MCA 的基因池。
	 *
	 * <p>方法体刻意为空——执行到这里的意义就是「让类被加载」，而基因是在静态字段初始化时
	 * 完成注册的。调用点见 {@link com.mcabp.wildfire.main.WildfireGender} 的构造器。</p>
	 */
	public static void bootstrap() {
		// 空实现，触发静态初始化即达成目的
	}

	/**
	 * <h3>给 4 个外观基因按各自的分布赋一次随机值</h3>
	 *
	 * <p>下面这张表就是各参数的分布：</p>
	 * <table border="1">
	 *   <caption>各参数的分布</caption>
	 *   <tr><th>参数</th><th>分布</th><th>说明</th></tr>
	 *   <tr><td>{@link #SEPARATION}</td><td>中心化 {@code [0.25, 0.75]}</td>
	 *       <td>多数村民长得正常，极端间距只占少数</td></tr>
	 *   <tr><td>{@link #HEIGHT}</td><td>中心化 {@code [0.25, 0.75]}</td>
	 *       <td>同上，多数居中、偶尔偏高偏低</td></tr>
	 *   <tr><td>{@link #DEPTH}</td><td>偏向 1 的 {@code [0, 1]}</td>
	 *       <td>多数村民完全不往身体里缩，少数才缩</td></tr>
	 *   <tr><td>{@link #ROTATION}</td><td>偏向 0 的 {@code [0, 1]}</td>
	 *       <td>张开角度天然以 0 为基准，多数村民应该几乎不张</td></tr>
	 * </table>
	 *
	 * <p>两个调用方共用这一份公式：村民首次生成时的 {@code Genetics#randomize()}，
	 * 以及给老村民补随机（见 {@link #rerollLegacyAppearance}）。
	 * 只有一份实现，两边不会随时间跑偏。</p>
	 *
	 * <p>注意<b>不包含</b>罩杯（MCA 原版的 {@code Genetics.BREAST}）—— 它由 MCA 自己
	 * 用 {@code [0,1]} 均匀分布生成，正是需要的效果；也不包含 {@link #BREAST_GROWTH}，
	 * 它另有基准值。</p>
	 *
	 * @param random 随机源，由调用方提供。分开传入是为了不让这里的消耗影响
	 *               MCA 自己的随机序列
	 */
	public static void randomizeAppearance(Genetics genetics, RandomSource random) {
		// 分离与高度：中心化分布，多数村民落在中段，极端值只占少数
		genetics.setGene(SEPARATION, centeredRandom(random));
		genetics.setGene(HEIGHT, centeredRandom(random));

		// 深度：偏向 1，多数村民完全不往身体里缩，少数才缩
		genetics.setGene(DEPTH, 1F - zeroCenteredRandom(random));

		// 旋转：偏向 0，多数村民几乎不张开，少数角度较大
		genetics.setGene(ROTATION, zeroCenteredRandom(random));

		// 打上「已初始化」标记 —— 老村民补随机的判据靠它（见 rerollLegacyAppearance）
		genetics.setGene(CHEST_INITIALIZED, INITIALIZED);
	}

	/**
	 * <h3>给「装本模组之前就存在的村民」补一次外观随机</h3>
	 *
	 * <p>老村民这几个基因读出来一律是 MCA 的默认值 0.5，于是全村的老村民长得一模一样。
	 * 这里在实体加入世界时补一次随机，让它们和自然生成的新村民一样有分布差异。</p>
	 *
	 * <p>判定分两步，缺一不可：</p>
	 * <ol>
	 *   <li><b>没有初始化标记</b> —— 见 {@link #CHEST_INITIALIZED}。新村民在
	 *       {@code randomize()} 时就打上了标记；玩家手动改过的村民同样有标记，
	 *       所以这两种村民都不会被重新随机掉。</li>
	 *   <li><b>4 个外观值全是默认</b> —— 没有标记、但值已经被随机过的，是
	 *       「装过带这些基因的中间版本」的存档留下来的村民。它们只是缺标记，
	 *       补上即可，重新掷一遍只会让外观平白再变一次。</li>
	 * </ol>
	 *
	 * <p>另外，整件事<b>只在服务端做</b>：基因是服务端权威数据，客户端就算改了，
	 * 下一次同步也会被覆盖回去。村民编辑器里的预览实体属于客户端，会被这里挡掉。</p>
	 *
	 * <p>补过之后标记就落下了，因此对同一个村民只会真正生效一次。</p>
	 */
	public static void rerollLegacyAppearance(VillagerLike<?> villager) {
		if (villager.asEntity().level().isClientSide) {
			return;
		}

		Genetics genetics = villager.getGenetics();

		// 打过标记 = 已经确认过（新村民，或玩家手动改过的村民），不再动它
		if (genetics.getGene(CHEST_INITIALIZED) >= INITIALIZED_THRESHOLD) {
			return;
		}

		// 没有标记、但外观值不全是默认 —— 外观早就随机过了，只是缺标记。
		// 顺手把标记补上，以后连检查都不必做。
		if (!isAppearanceDefault(genetics)) {
			genetics.setGene(CHEST_INITIALIZED, INITIALIZED);
			return;
		}

		// 刻意新建一个随机源，而不是用 villager 自己的：
		// 免得额外消耗它的随机序列，扰动 AI 之类的既有行为
		randomizeAppearance(genetics, RandomSource.create());
	}

	/** 4 个外观参数是否都还停在 MCA 的默认值上 —— 也就是「从未被随机过」。 */
	private static boolean isAppearanceDefault(Genetics genetics) {
		return isDefault(genetics.getGene(SEPARATION))
			&& isDefault(genetics.getGene(HEIGHT))
			&& isDefault(genetics.getGene(DEPTH))
			&& isDefault(genetics.getGene(ROTATION));
	}

	/**
	 * 基因值是否等于 MCA 的默认值。
	 *
	 * <p>可以直接用 {@code ==} 比较：0.5 能被 float 精确表示，从 NBT 或网络往返回来
	 * 没有精度损失；而随机生成的值恰好等于 0.5 的概率是 0。</p>
	 */
	private static boolean isDefault(float gene) {
		return gene == DEFAULT_GENE_VALUE;
	}

	/**
	 * 中心化分布：密度在 0.5 处最高，向两端递减，范围约 {@code [0.25, 0.75]}。
	 *
	 * <p>复刻 MCA 自己给身高体宽用的算法。</p>
	 */
	private static float centeredRandom(RandomSource random) {
		return Mth.clamp((random.nextFloat() - 0.5F) * (random.nextFloat() - 0.5F) + 0.5F, 0F, 1F);
	}

	/**
	 * 偏向 0 的分布：密度在 0 处最高，向 1 递减，范围 {@code [0, 1]}。
	 *
	 * <p>形式与 {@link #centeredRandom} 相同 —— 都是两个均匀变量相乘，
	 * 区别是这里不把结果加回 0.5，于是峰值位置从 0.5 移到了 0。
	 * 需要「偏向 1」时取它的补数即可。</p>
	 */
	private static float zeroCenteredRandom(RandomSource random) {
		return random.nextFloat() * random.nextFloat();
	}
}
