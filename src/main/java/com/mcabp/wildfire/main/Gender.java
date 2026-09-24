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

package com.mcabp.wildfire.main;

import com.mcabp.wildfire.main.text.IHasTextComponent.IHasEnumNameTextComponent;
import io.netty.buffer.ByteBuf;
import java.util.function.IntFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import org.jetbrains.annotations.NotNull;

public enum Gender implements IHasEnumNameTextComponent {
    // NOTE: The order of these should remain unchanged! Changing these WILL modify player configs!
    FEMALE(WildfireLang.FEMALE.translateColored(ChatFormatting.LIGHT_PURPLE), true),
    MALE(WildfireLang.MALE.translateColored(ChatFormatting.BLUE), false),
    OTHER(WildfireLang.OTHER.translateColored(ChatFormatting.GREEN), true);

    public static final IntFunction<Gender> BY_ID = ByIdMap.continuous(Gender::ordinal, values(), ByIdMap.OutOfBoundsStrategy.WRAP);
    public static final StreamCodec<ByteBuf, Gender> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, Gender::ordinal);

    private final Component name;
    private final boolean canHaveBreasts;

    Gender(Component name, boolean canHaveBreasts) {
        this.name = name;
        this.canHaveBreasts = canHaveBreasts;
    }

    public boolean canHaveBreasts() {
        return canHaveBreasts;
    }

    @NotNull
    @Override
    public Component getTextComponent() {
        return name;
    }
}
