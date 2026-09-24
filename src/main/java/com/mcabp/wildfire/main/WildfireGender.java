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

package com.mcabp.wildfire.main;

import com.mojang.logging.LogUtils;
import com.mcabp.wildfire.api.WildfireAPI;
import com.mcabp.wildfire.main.entitydata.MCAVillagerGenes;
import net.conczin.mca.entity.VillagerLike;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.slf4j.Logger;

@Mod(WildfireGender.MODID)
public class WildfireGender {

    public static final String MODID = WildfireAPI.MODID;
    public static final Logger LOGGER = LogUtils.getLogger();

    public WildfireGender(ModContainer modContainer, IEventBus modEventBus) {
        // 把本模组追加的胸部基因注册进 MCA 的基因池。
        // 必须在这里（模组构造期）完成：基因池决定了村民同步数据表的构成，而同步数据表
        // 是在「每个村民实体创建时」才构建的。模组构造早于任何实体创建，因此这里是安全的。
        MCAVillagerGenes.bootstrap();
        // 注册物品能力（性别盔甲），供 WildfireHelper#getArmorConfig 查询
        modEventBus.addListener(WildfireHelper::registerCapabilities);
        // 给「装本模组之前就存在的村民」补一次外观随机。挂在实体加入世界这个点上，
        // 是因为那个时刻基因已经从存档读完了；具体的判定条件与 side 判断都在
        // MCAVillagerGenes#rerollLegacyAppearance 里。
        NeoForge.EVENT_BUS.addListener(WildfireGender::onEntityJoinLevel);
    }

    /**
     * 实体加入世界时，顺路检查它是不是需要补随机的老村民。
     *
     * <p>本模组的实体类型只有 MCA 村民，所以这里只做一个廉价的接口判断，
     * 剩下的（包括「只在服务端做」这一条）都交给 {@link MCAVillagerGenes}。</p>
     */
    private static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof VillagerLike<?> villager) {
            MCAVillagerGenes.rerollLegacyAppearance(villager);
        }
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
