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

package com.mcabp.wildfire.client.gui;

import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * <h2>会显示数值的滑块</h2>
 *
 * <p>MCA 自带的 {@code GeneSliderWidget} 把 {@link #updateMessage()} 留成了空实现，
 * 所以滑块上永远只有参数名，拖动时也看不到任何数值反馈。</p>
 *
 * <p>本类补上这一环：文字由「参数名 + 当前值」拼成，而
 * {@code AbstractSliderButton} 在每次数值变化时都会调用 {@code updateMessage()}，
 * 因此拖动过程中数值会实时刷新。</p>
 *
 * <p>显示什么由调用方通过 {@code formatter} 决定 —— 基因值本身是 0~1 的抽象数字，
 * 对使用者没有意义，通常应该换算成实际物理量（度数、偏移量等）再显示。</p>
 */
public class ValueShowingSlider extends AbstractSliderButton {

	/** 参数名，每次刷新文字时重新拼在最前面。 */
	private final Component label;

	/** 把滑块当前值转成要显示的文字。 */
	private final DoubleFunction<String> formatter;

	/** 数值变化时的回调，用来把改动写回实体。 */
	private final Consumer<Double> onValueChanged;

	public ValueShowingSlider(int x, int y, int width, int height, Component label, double value,
							  DoubleFunction<String> formatter, Consumer<Double> onValueChanged) {
		super(x, y, width, height, label, value);
		this.label = label;
		this.formatter = formatter;
		this.onValueChanged = onValueChanged;
		// 构造时先渲染一次，否则要等第一次拖动才会显示数值
		updateMessage();
	}

	@Override
	protected void applyValue() {
		onValueChanged.accept(value);
	}

	@Override
	protected void updateMessage() {
		setMessage(Component.empty().append(label).append(" ").append(formatter.apply(value)));
	}
}
