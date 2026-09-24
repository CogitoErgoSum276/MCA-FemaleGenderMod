/*
	Wildfire's Female Gender Mod is a female gender mod created for Minecraft.
	Copyright (C) 2023 WildfireRomeo

	This program is free software; you can redistribute it and/or
	modify it under the terms of the GNU Lesser General Public
	License as published by the Free Software Foundation; either
	version 3 of the License, or (at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
	Lesser General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.mcabp.wildfire.main.config;

import com.mcabp.wildfire.main.WildfireGender;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * <h2>客户端配置（本地参数）</h2>
 *
 * <p>这里存放的是「观看者个人的偏好」——只影响本机的观感与手感，<b>不会同步给其他玩家，
 * 也不会写进世界数据</b>。它与另一组「服务器同步的参数」（村民的性别、罩杯等，由 MCA 的
 * 基因系统保管）在职责上是分开的：前者是"我怎么看"，后者是"NPC 客观长什么样"。</p>
 *
 * <p>因此同一个 NPC，在不同玩家的客户端上，本配置里的参数可以各不相同——这是刻意设计，
 * 不是 bug。</p>
 *
 * <p><b>怎么改</b>：</p>
 * <ul>
 *   <li>文件：{@code config/mcabp-client.toml}，文本编辑器直接改（改完重启生效）</li>
 *   <li>游戏内：NeoForge 内置的模组配置界面（入口在"模组"菜单里，见
 *       {@code WildfireGenderClient} 注册的 {@code IConfigScreenFactory}）</li>
 * </ul>
 *
 * <p>每个配置项都通过 {@code translation} 指定了显示名，对应的译文放在
 * {@code assets/mcabp/lang/*.json} 里，键名格式为 {@code mcabp.config.<配置名>}。</p>
 */
public class GeneralClientConfig {

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
	public static final GeneralClientConfig INSTANCE = new GeneralClientConfig();

	public final ModConfigSpec configSpec;

	/** 本模组总开关：为 true 时正常渲染，为 false 时完全关闭胸部渲染。 */
	public final ModConfigSpec.BooleanValue renderingEnabled;

	/** 胸部物理开关：为 true 时胸部会晃动。 */
	public final ModConfigSpec.BooleanValue physicsEnabled;

	/** 晃动幅度：值越大，受击、跳跃等外力造成的晃动越剧烈。 */
	public final ModConfigSpec.DoubleValue bounceMultiplier;

	/** 晃动时长（内部沿用 FGM 的 floppiness 命名）：值越大，受到冲击后晃得越久。 */
	public final ModConfigSpec.DoubleValue floppyMultiplier;

	/** 左右独立晃动：开启后左右两侧各自独立晃动，互不同步。 */
	public final ModConfigSpec.BooleanValue dualPhysicsEnabled;

	/** 穿甲时胸不隐藏：开启后穿着覆盖胸部的胸甲时依然显示胸部。 */
	public final ModConfigSpec.BooleanValue showInArmor;

	/** 穿甲时可以晃动：开启后忽略胸甲的束缚，穿甲也照常晃动。 */
	public final ModConfigSpec.BooleanValue armorPhysicsOverride;

	private GeneralClientConfig() {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

		renderingEnabled = builder
			  .comment("本模组总开关：关闭后所有村民的胸部都不再渲染。")
			  .translation("mcabp.config.rendering_enabled")
			  .define("renderingEnabled", true);

		physicsEnabled = builder
			  .comment("胸部物理开关：关闭后胸部不再晃动（但仍会正常渲染）。")
			  .translation("mcabp.config.physics_enabled")
			  .define("physicsEnabled", true);

		bounceMultiplier = builder
			  .comment("晃动幅度：值越大，受击、跳跃等外力造成的晃动越剧烈。",
					   "范围 0~1，默认 0.5 即原先的上限；再往上会明显夸张。",
					   "注意：行走时的起伏不受此项影响，那是步伐自带的固有节奏。")
			  .translation("mcabp.config.bounce_multiplier")
			  .defineInRange("bounceMultiplier", 0.5D, 0.0D, 1.0D);

		floppyMultiplier = builder
			  .comment("晃动时长：值越大，受到冲击后晃得越久；调小则晃两下就停。",
					   "内部对应 FGM 的 floppiness（弹簧阻尼），只是换了个更直观的说法。")
			  .translation("mcabp.config.floppy_multiplier")
			  .defineInRange("floppyMultiplier", 0.75D, 0.25D, 1.0D);

		dualPhysicsEnabled = builder
			  .comment("左右独立晃动：开启后左右两侧各自独立晃动，互不同步、更生动；",
					   "关闭时两侧共用一套物理数据，晃动完全一致。")
			  .translation("mcabp.config.dual_physics")
			  .define("dualPhysicsEnabled", true);

		showInArmor = builder
			  .comment("穿甲时胸不隐藏：开启后，就算穿着盖住胸部的胸甲，胸部也照常显示；",
					   "关闭时会被胸甲遮住。")
			  .translation("mcabp.config.show_in_armor")
			  .define("showInArmor", true);

		armorPhysicsOverride = builder
			  .comment("穿甲时可以晃动：开启后忽略胸甲的束缚，穿甲也照常晃动；",
					   "关闭时胸甲越紧，晃动被削弱得越厉害。")
			  .translation("mcabp.config.armor_physics_override")
			  .define("armorPhysicsOverride", false);

		configSpec = builder.build();
	}

	public void save() {
		EXECUTOR.submit(new ConfigSaver(configSpec));
	}

	private static class ConfigSaver implements Runnable {

		private final ModConfigSpec configSpec;
		private int retries = 0;

		private ConfigSaver(ModConfigSpec configSpec) {
			this.configSpec = configSpec;
		}

		@Override
		public void run() {
			try {
				configSpec.save();
			} catch (Exception e) {
				WildfireGender.LOGGER.error("Failed to save config", e);
				if (retries++ < 3) {
					EXECUTOR.submit(this);
				} else {
					WildfireGender.LOGGER.error("Giving up");
				}
			}
		}
	}
}
