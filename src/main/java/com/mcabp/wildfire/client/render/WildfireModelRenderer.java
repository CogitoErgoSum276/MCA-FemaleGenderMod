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

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

/**
 * <h2>自定义几何体工具类</h2>
 *
 * <p>原版 Minecraft 的 {@code ModelPart} 只支持「按固定贴图布局解析出来的长方体」，
 * 无法在运行时动态改变长宽深。而胸部的大小每 tick 都在变，必须能实时重建几何体，
 * 于是模组自己实现了一套最小化的「可动态构建长方体」工具。</p>
 *
 * <p>核心概念：</p>
 * <ul>
 *   <li>{@link ModelBox}：一个长方体，内部保存顶点的绝对坐标（单位是模型像素，1 像素 = 1/16 格）
 *       和每个面在贴图上的 UV 区域。</li>
 *   <li>{@link TexturedQuad}：长方体<b>的一个面</b>，持有 4 个顶点和一条法线。</li>
 *   <li>{@link PositionTextureVertex}：一个顶点，包含位置与 UV 两部分信息。</li>
 * </ul>
 *
 * <p>一个长方体理论上有 6 个面，但本工具只生成其中的一部分（通常是 5 个）：
 * 贴着身体或贴着其他几何体的那一面永远看不到，画出来只是白白浪费顶点。
 * 这正是 {@link OverlayModelBox} 只生成 4 个面的原因。</p>
 *
 * <p>本类是个纯工具类，因此声明为 {@code final} 并把构造函数设为 {@code private}，
 * 防止被继承或实例化。</p>
 */
public final class WildfireModelRenderer {

    // 私有构造函数：本类只提供静态内部类和静态方法，不需要（也不允许）被实例化
    private WildfireModelRenderer() {
    }

    /**
     * <h3>可动态构建的长方体</h3>
     *
     * <p>构造时传入尺寸，立刻解析出各面的顶点与 UV。与 {@code ModelPart} 不同，
     * 这里的尺寸是构造参数而非固定资源，所以想改大小就得重新 new 一个。</p>
     *
     * <p><b>参数命名约定</b>（t = texture，即贴图）：</p>
     * <ul>
     *   <li>{@code tW}/{@code tH}：贴图的总宽/总高（像素），用来把 UV 归一化到 [0,1]。</li>
     *   <li>{@code texU}/{@code texV}：本长方体在贴图上「展开图」的左上角起点。</li>
     *   <li>{@code x}/{@code y}/{@code z}：长方体的起始角点（模型像素，相对于部件原点）。</li>
     *   <li>{@code dx}/{@code dy}/{@code dz}：长/高/深（模型像素）。</li>
     *   <li>{@code delta}：外扩量，把长方体整体"胀大"一圈，用于盖住相邻几何体之间的接缝。</li>
     *   <li>{@code mirror}：是否沿 X 轴镜像，右半边用。</li>
     * </ul>
     */
    public static class ModelBox {

        // 各面的四边形。数组长度由构造时的 quads 参数决定（5 或 4）
        public final TexturedQuad[] quads;
        // 原始（未加 delta 外扩）的包围盒角点，记录两个对角点即可描述整个盒子的范围
        public final float posX1;
        public final float posY1;
        public final float posZ1;
        public final float posX2;
        public final float posY2;
        public final float posZ2;

        /**
         * 最常用的构造函数：生成标准的 5 个面。
         * 只做参数转发，把面数固定为 5。
         */
        public ModelBox(int tW, int tH, int texU, int texV, float x, float y, float z, int dx, int dy, int dz, float delta, boolean mirror) {
            this(tW, tH, texU, texV, x, y, z, dx, dy, dz, delta, mirror, 5);
        }

        /**
         * 允许子类指定面数的构造函数（{@link OverlayModelBox} 用它把面数降到 4）。
         * {@code extra} 固定传 false，仅供子类覆写 {@link #initQuads} 时使用。
         */
        protected ModelBox(int tW, int tH, int texU, int texV, float x, float y, float z, int dx, int dy, int dz, float delta, boolean mirror, int quads) {
            this(tW, tH, texU, texV, x, y, z, dx, dy, dz, delta, mirror, quads, false);
        }

        /**
         * 真正干活的构造函数。整体思路与 MC 原版 {@code ModelPart$Cube} 一致：
         * 先算出长方体的 8 个角点，再把它们按「哪 4 个角组成哪个面」交给
         * {@link #initQuads} 装配成面。
         *
         * <p><b>注意参数被就地复用了</b>：{@code x/y/z} 表示「起始角」，
         * {@code f/f1/f2} 表示「终止角」，之后 x/y/z 会被改写成外扩后的值。
         * 这是照搬原版写法留下的风格。</p>
         */
        protected ModelBox(int tW, int tH, int texU, int texV, float x, float y, float z, int dx, int dy, int dz, float delta, boolean mirror, int quads, boolean extra) {
            // 记录原始包围盒（不含 delta 外扩），两个对角点即可描述范围
            this.posX1 = x;
            this.posY1 = y;
            this.posZ1 = z;
            this.posX2 = x + (float) dx;
            this.posY2 = y + (float) dy;
            this.posZ2 = z + (float) dz;
            this.quads = new TexturedQuad[quads];
            // f/f1/f2 是「终止角」坐标（即 x+dx、y+dy、z+dz）
            float f = x + (float) dx;
            float f1 = y + (float) dy;
            float f2 = z + (float) dz;
            // delta 外扩：起始角往负方向挪，终止角往正方向挪 —— 盒子整体"胀大"一圈。
            // 目的是让相邻的几何体互相重叠一点点，避免两者之间的接缝漏出背景色。
            x = x - delta;
            y = y - delta;
            z = z - delta;
            f = f + delta;
            f1 = f1 + delta;
            f2 = f2 + delta;
            // 镜像：交换 X 方向的起始角与终止角，相当于沿 X 轴翻转，供右半边使用。
            if (mirror) {
                float f3 = f;
                f = x;
                x = f3;
            }
            // 按顺序传入长方体的 8 个角点（3 位小数不出现，全部为 0.0F/8.0F 的占位 UV，
            // 真正的 UV 稍后会由 TexturedQuad 逐个覆盖，见下方说明）：
            //   第 0 个 (f , y , z )  X 最大、Y 最小、Z 最小
            //   第 1 个 (f , f1, z )  X 最大、Y 最大、Z 最小
            //   第 2 个 (x , f1, z )  X 最小、Y 最大、Z 最小
            //   第 3 个 (x , y , f2)  X 最小、Y 最小、Z 最大
            //   第 4 个 (f , y , f2)  X 最大、Y 最小、Z 最大
            //   第 5 个 (f , f1, f2)  X 最大、Y 最大、Z 最大
            //   第 6 个 (x , f1, f2)  X 最小、Y 最大、Z 最大
            //   第 7 个 (x , y , z )  X 最小、Y 最小、Z 最小
            // 初始化时给的 UV 只是占位，{@link TexturedQuad} 的构造器会用每个面自己的
            // 贴图区域把这 4 个顶点重新包装一遍，所以同一个角点在东面和北面会有不同的 UV。
            initQuads(tW, tH, texU, texV, dx, dy, dz, mirror, extra,
                  new PositionTextureVertex(f, y, z, 0.0F, 8.0F),
                  new PositionTextureVertex(f, f1, z, 8.0F, 8.0F),
                  new PositionTextureVertex(x, f1, z, 8.0F, 0.0F),
                  new PositionTextureVertex(x, y, f2, 0.0F, 0.0F),
                  new PositionTextureVertex(f, y, f2, 0.0F, 8.0F),
                  new PositionTextureVertex(f, f1, f2, 8.0F, 8.0F),
                  new PositionTextureVertex(x, f1, f2, 8.0F, 0.0F),
                  new PositionTextureVertex(x, y, z, 0.0F, 0.0F)
            );
        }

        /**
         * 把 8 个角点装配成各个面，并计算每个面在贴图上的 UV 区域。
         *
         * <p>贴图布局采用 MC 传统的「盒子展开图」，即把长方体的几个面摊平画在一张图上：</p>
         *
         * <pre>
         *            ┌──────────┐
         *            │   DOWN   │              ← 宽 dx，高 dz
         *   ┌────┬───┴──────┬───┴────┐
         *   │WEST│  NORTH   │  EAST  │         ← 中排：宽 dz / dx / dz，高都是 dy
         *   └────┴──────────┴────────┘
         *            │    UP    │
         *            └──────────┘              ← 宽 dx，高 dy
         * </pre>
         *
         * <p>{@code texU}/{@code texV} 是这张展开图的左上角，各面的 UV 都由它加上
         * 偏移量算出，因此只要挪动 {@code texU/texV} 就能让同一个盒子使用贴图上不同位置的图案
         * —— 胸甲贴图就是靠这个机制切换的。</p>
         *
         * <p><b>只生成 5 个面</b>，缺的是 SOUTH（Z 最大那一面）。贴身体的那一面永远看不到，
         * 省掉它能少写 4 个顶点。</p>
         *
         * <p>传给 {@link TexturedQuad} 的 4 个顶点顺序并非随意：它的构造器会按下标把
         * {@code (u1,v1)}~{@code (u2,v2)} 依次贴到 positions[0]~positions[3] 上，
         * 顺序错了贴图就会翻转或错位。</p>
         *
         * <p>注意 {@code quads[3]}（UP 面）的 v 起点用的是 {@code texV + dz + 4} 而不是
         * 按上面布局应有的 {@code texV + dz + dy}，贴图高度也传了 {@code tH - 1} 而不是 {@code tH}。
         * 这两处是本模组自己的硬编码写法，与实际布局规律不同，阅读时不必强行套用原版规律。</p>
         */
        protected void initQuads(int tW, int tH, int texU, int texV, int dx, int dy, int dz, boolean mirror, boolean extra, PositionTextureVertex vertex,
              PositionTextureVertex vertex1, PositionTextureVertex vertex2, PositionTextureVertex vertex3, PositionTextureVertex vertex4, PositionTextureVertex vertex5,
              PositionTextureVertex vertex6, PositionTextureVertex vertex7) {
            // 东面（X 最大）：由 4、0、1、5 四个角组成
            this.quads[0] = new TexturedQuad(texU + dz + dx, texV + dz, texU + dz + dx + dz, texV + dz + dy, tW, tH, mirror, Direction.EAST,
                  vertex4, vertex, vertex1, vertex5);
            // 西面（X 最小）：由 7、3、6、2 四个角组成
            this.quads[1] = new TexturedQuad(texU, texV + dz, texU + dz, texV + dz + dy, tW, tH, mirror, Direction.WEST,
                  vertex7, vertex3, vertex6, vertex2);
            // 下面（Y 最小）：由 4、3、7、0 四个角组成
            this.quads[2] = new TexturedQuad(texU + dz, texV, texU + dz + dx, texV + dz, tW, tH, mirror, Direction.DOWN,
                  vertex4, vertex3, vertex7, vertex);
            // 上面（Y 最大）：由 1、2、6、5 四个角组成
            this.quads[3] = new TexturedQuad(texU + dz, texV + dz + 4, texU + dz + dx, texV + 1 + dz + dy, tW, tH - 1, mirror, Direction.UP,
                  vertex1, vertex2, vertex6, vertex5);
            // 北面（Z 最小）：由 0、7、2、1 四个角组成。SOUTH（Z 最大）不生成
            this.quads[4] = new TexturedQuad(texU + dz, texV + dz, texU + dz + dx, texV + dz + dy, tW, tH, mirror, Direction.NORTH,
                  vertex, vertex7, vertex2, vertex1);
        }
    }

    /**
     * <h3>只生成 4 个面的「外套层」长方体</h3>
     *
     * <p>用于渲染皮肤上那层夹克/衬衫（{@code lBreastWear}/{@code rBreastWear}）。
     * 与父类相比只改了两点：面数从 5 减到 4，并且少掉的那一面随左右侧而变。</p>
     *
     * <p><b>少的是哪一面？</b>朝向身体中线的那一面（左半边少 EAST、右半边少 WEST）。
     * 这一面被埋在躯干内部，永远不会被看到，于是干脆不生成。
     * 注意 {@link #initQuads} 里左右两侧计算 {@code quads[0]} 时用的顶点也不同，
     * 并不是简单地把同一份数据复制过去。</p>
     */
    public static class OverlayModelBox extends ModelBox {

        /**
         * @param isLeft 是否渲染左半边。这里的取值会通过父类构造函数里那个
         *               名叫 {@code extra} 的形参一路传到 {@link #initQuads}，
         *               所以同一个 boolean 在父类签名里叫 {@code extra}、在子类里叫 {@code isLeft}。
         */
        public OverlayModelBox(boolean isLeft, int tW, int tH, int texU, int texV, float x, float y, float z, int dx, int dy, int dz, float delta, boolean mirror) {
            super(tW, tH, texU, texV, x, y, z, dx, dy, dz, delta, mirror, 4, isLeft);
        }

        @Override
        protected void initQuads(int tW, int tH, int texU, int texV, int dx, int dy, int dz, boolean mirror, boolean isLeft, PositionTextureVertex vertex,
              PositionTextureVertex vertex1, PositionTextureVertex vertex2, PositionTextureVertex vertex3, PositionTextureVertex vertex4, PositionTextureVertex vertex5,
              PositionTextureVertex vertex6, PositionTextureVertex vertex7) {
            // quads[0] 是「外侧那一面」：
            //   右半边（!isLeft）取 EAST，左半边取 WEST —— 两者都是远离身体中线的方向
            // 另外 3 个面固定为 DOWN / UP / NORTH，与父类一致
            if (!isLeft) {
                this.quads[0] = new TexturedQuad(texU + dz + dx, texV + dz, texU + dz + dx + dz, texV + dz + dy, tW, tH, mirror, Direction.EAST,
                      vertex4, vertex, vertex1, vertex5);
            } else {
                this.quads[0] = new TexturedQuad(texU, texV + dz, texU + dz, texV + dz + dy, tW, tH, mirror, Direction.WEST,
                      vertex7, vertex3, vertex6, vertex2);
            }
            this.quads[1] = new TexturedQuad(texU + dz, texV, texU + dz + dx, texV + dz, tW, tH, mirror, Direction.DOWN,
                  vertex4, vertex3, vertex7, vertex);
            this.quads[2] = new TexturedQuad(texU + dz, texV + dz + 4, texU + dz + dx, texV + 1 + dz + dy, tW, tH - 1, mirror, Direction.UP,
                  vertex1, vertex2, vertex6, vertex5);
            this.quads[3] = new TexturedQuad(texU + dz, texV + dz, texU + dz + dx, texV + dz + dy, tW, tH, mirror, Direction.NORTH,
                  vertex, vertex7, vertex2, vertex1);
        }
    }

    /**
     * <h3>乳房专用的长方体：UV 布局锁定为「深度 4」</h3>
     *
     * <p>本类与父类 {@link ModelBox} 只有一个区别：{@link #initQuads} 里所有本该是
     * {@code dz} 的位置全部换成了字面量 {@code 4}。除此之外完全一致（同样是 5 个面）。</p>
     *
     * <p><b>为什么要这么写？</b>因为乳房盒子的实际深度是会变的。
     * {@code GenderLayer#resizeBox} 会按罩杯算出 {@code dz = 4 - breastOffsetZ - reducer}，
     * 结果在 2~5 之间浮动。如果 UV 布局跟着 {@code dz} 一起变，那么玩家每次调整罩杯，
     * 贴图在脸上都会跟着"移动"一下，看起来像贴图在漂。把 UV 布局钉死在深度 4 的模板上，
     * 几何体尺寸随便变，贴图区域始终保持稳定 —— 这就是这个子类存在的全部意义。</p>
     *
     * <p>{@code GenderLayer} 里的乳房本体（{@code lBreast}/{@code rBreast}）和
     * 胸甲覆盖层（{@code lBoobArmor}/{@code rBoobArmor}）都用它来构建。</p>
     */
    public static class BreastModelBox extends ModelBox {

        /**
         * 面数沿用父类默认的 5，因此没有 quads 参数。
         */
        public BreastModelBox(int tW, int tH, int texU, int texV, float x, float y, float z, int dx, int dy, int dz, float delta, boolean mirror) {
            super(tW, tH, texU, texV, x, y, z, dx, dy, dz, delta, mirror);
        }

        /**
         * 与 {@link ModelBox#initQuads} 逐项对照即可看出：除了把 {@code dz} 全部替换为 {@code 4}
         * 之外，面数、面朝向、顶点分组、UV 公式完全一致。参数 {@code dz} 在这里被彻底忽略，
         * 只用于决定几何体的形状（由父类构造函数使用），不参与 UV 计算。
         */
        @Override
        protected void initQuads(int tW, int tH, int texU, int texV, int dx, int dy, int dz, boolean mirror, boolean extra, PositionTextureVertex vertex,
              PositionTextureVertex vertex1, PositionTextureVertex vertex2, PositionTextureVertex vertex3, PositionTextureVertex vertex4, PositionTextureVertex vertex5,
              PositionTextureVertex vertex6, PositionTextureVertex vertex7) {
            // 东面（X 最大）：UV 起点 texU+4+dx，宽/高按 dz=4 的模板固定
            this.quads[0] = new TexturedQuad(
                  texU + 4 + dx, texV + 4,
                  texU + 4 + dx + 4, texV + 4 + dy,
                  tW, tH,
                  mirror, Direction.EAST,
                  vertex4, vertex, vertex1, vertex5
            );

            // 西面（X 最小）
            this.quads[1] = new TexturedQuad(
                  texU, texV + 4,
                  texU + 4, texV + 4 + dy,
                  tW, tH,
                  mirror, Direction.WEST,
                  vertex7, vertex3, vertex6, vertex2
            );

            // 下面（Y 最小）
            this.quads[2] = new TexturedQuad(
                  texU + 4, texV,
                  texU + 4 + dx, texV + 4,
                  tW, tH,
                  mirror, Direction.DOWN,
                  vertex4, vertex3, vertex7, vertex
            );

            // 上面（Y 最大）。与父类同样存在 v 起点用 +4、贴图高度用 tH-1 的硬编码写法
            this.quads[3] = new TexturedQuad(
                  texU + 4, texV + 4 + 4,
                  texU + 4 + dx, texV + 1 + 4 + dy,
                  tW, tH - 1,
                  mirror, Direction.UP,
                  vertex1, vertex2, vertex6, vertex5
            );

            // 北面（Z 最小）。同样不生成南面
            this.quads[4] = new TexturedQuad(
                  texU + 4, texV + 4,
                  texU + 4 + dx, texV + 4 + dy,
                  tW, tH,
                  mirror, Direction.NORTH,
                  vertex, vertex7, vertex2, vertex1
            );
        }
    }

    /**
     * 一个顶点：位置 + 贴图坐标。
     *
     * <p>用 {@code record} 声明，因此天然是<b>不可变</b>的——所有字段 final，
     * 也没有 setter。想改 UV 只能造一个新对象，这正是
     * {@link #withTexturePosition} 存在的原因。</p>
     *
     * <p>位置单位是模型像素（1 像素 = 1/16 格），UV 单位是 [0,1] 的归一化坐标
     * （由 {@link TexturedQuad} 换算）。</p>
     */
    public record PositionTextureVertex(float x, float y, float z, float texturePositionX, float texturePositionY) {

        /**
         * 返回一个位置不变、但 UV 被替换成新值的顶点副本。
         * 由于 record 不可变，这里必须新建对象而不是修改自身。
         */
        public PositionTextureVertex withTexturePosition(float texU, float texV) {
            return new PositionTextureVertex(x, y, z, texU, texV);
        }
    }

    /**
     * <h3>长方体的一个面</h3>
     *
     * <p>持有 4 个顶点和一条法线。构造过程中做两件事：</p>
     * <ol>
     *   <li><b>把 UV 从像素换算成 [0,1]</b>，并写入 4 个顶点。这一步会
     *       <b>替换</b>掉传入顶点的 UV（原本的占位值被丢弃），所以每个面都持有
     *       自己那一份顶点副本，同一个角点在不同面上可以有不同的 UV。</li>
     *   <li><b>处理镜像</b>：反转顶点顺序，让面朝向翻转。</li>
     * </ol>
     */
    public static class TexturedQuad {

        public final PositionTextureVertex[] vertexPositions;
        public final Vec3i normal;

        /**
         * @param u1/v1/u2/v2   该面在贴图上的矩形区域（像素坐标）
         * @param texWidth      贴图总宽，用于归一化 u
         * @param texHeight     贴图总高，用于归一化 v
         * @param mirrorIn      是否镜像
         * @param directionIn   面的朝向，用来取法线（决定光照）
         * @param positionsIn   该面的 4 个顶点，顺序必须与 UV 的对应关系一致
         */
        public TexturedQuad(float u1, float v1, float u2, float v2, float texWidth, float texHeight, boolean mirrorIn, Direction directionIn, PositionTextureVertex... positionsIn) {
            // 传进来的顶点数不是 4 就无法构成四边形，直接抛异常（属于开发期的断言）
            if (positionsIn.length != 4) {
                throw new IllegalArgumentException("Wrong number of vertex's. Expected: 4, Received: " + positionsIn.length);
            }
            this.vertexPositions = positionsIn;
            // 注意：这两个值是原版代码的残留 —— 0 除以任何数都是 0，所以 f 和 f1 恒等于 0，
            // 下面的 UV 计算实际就是简单的 u/texWidth、v/texHeight 归一化。
            float f = 0.0F / texWidth;
            float f1 = 0.0F / texHeight;
            // 按下标把矩形区域的四个角依次贴到四个顶点上，对应关系为：
            //   positions[0] ← (u2, v1)   右上
            //   positions[1] ← (u1, v1)   左上
            //   positions[2] ← (u1, v2)   左下
            //   positions[3] ← (u2, v2)   右下
            // 顺序写错贴图就会翻转或旋转，这也是 initQuads 里顶点顺序不能随便调的原因。
            positionsIn[0] = positionsIn[0].withTexturePosition(u2 / texWidth - f, v1 / texHeight + f1);
            positionsIn[1] = positionsIn[1].withTexturePosition(u1 / texWidth + f, v1 / texHeight + f1);
            positionsIn[2] = positionsIn[2].withTexturePosition(u1 / texWidth + f, v2 / texHeight - f1);
            positionsIn[3] = positionsIn[3].withTexturePosition(u2 / texWidth - f, v2 / texHeight - f1);
            // 镜像：把顶点顺序整体反转（首尾两两交换）。顶点绕序反转后，
            // 渲染时就会以相反的朝向显示，等同于沿该面法线方向翻了个面。
            if (mirrorIn) {
                int i = positionsIn.length;

                for (int j = 0; j < i / 2; ++j) {
                    PositionTextureVertex vertex = positionsIn[j];
                    positionsIn[j] = positionsIn[i - 1 - j];
                    positionsIn[i - 1 - j] = vertex;
                }
            }

            this.normal = directionIn.getNormal();
            if (mirrorIn) {
                // 注意：Vec3i 是不可变类型，multiply 返回的是一个新对象，
                // 这里的返回值没有被赋回 this.normal，所以这一行实际上不产生任何效果
                // （即镜像时法线并未翻转）。保留原样是为了与原版代码保持一致。
                this.normal.multiply(-1);
            }
        }
    }
}