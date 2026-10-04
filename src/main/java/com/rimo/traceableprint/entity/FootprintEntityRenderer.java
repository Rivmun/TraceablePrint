//? if > 1.21.1 {
package com.rimo.traceableprint.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.rimo.traceableprint.Common;
import com.rimo.traceableprint.VersionUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//~ if >= 26.1 'client.renderer.state.CameraRenderState' -> 'client.renderer.state.level.CameraRenderState'
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 26.1 实体渲染器（RenderState 模式），采用与画（Painting）相同的直接四边形提交方式：
 * 对“贴图薄片”类实体，直接提交几何才是原版常规做法。
 * 碰撞箱(EntityType.sized)与贴图解耦；贴图沿实体 yaw 旋转、随存续时间从 0.02 下沉到 0.01。
 * 左右脚偏移已烘入实体真实坐标（见 LivingEntityMixin），贴图始终在实体原点居中。
 * 贴图本身可按生物替换：服务端生成时按 config.textureList 命中生物并随机选定一个贴图名同步下来，
 *   客户端逐帧经 FootprintTextures 解析成实际路径（见 state.texture），未定制/资源包缺图则用默认 footprint.png。
 *
 * 高亮：走自定义脉冲管线（见 FootprintRenderTypes，core/footprint_pulse 在 footprint 原色与纯白间随时间闪烁，
 *   关深度穿墙、不采样光照）。非高亮：走公共 RenderTypes.entityTranslucent，采样世界 lightmap，
 *   顶点 UV2 取实体处完整光照（天光昼夜变暗 + 方块光/火把等环境光暖照）。历史上“发红”的元凶是
 *   UV1(overlay) 默认落到的红色 overlay 行，已由 setOverlay(NO_OVERLAY) 独立规避；方块光本身只添火把暖调，故重新启用。
 *   非高亮选 translucent 而非 cutout：仅启用 alpha 混合才能把顶点 alpha 当不透明度用，实现存活末段渐淡。
 *
 * 瞄准判定框：准星指在本脚印上时，用与原版“选中方块”同一套线框样式（RenderTypes.lines() /
 *   secondaryBlockOutline()、同色同宽、同样受“高对比度方块外框”选项影响）画出自家 AABB。
 *   为何要自绘：26.1 已把实体的瞄准高亮框并入 F3+B 的 Gizmos 调试体系（EntityHitboxDebugRenderer），
 *   LevelRenderer.extractBlockOutline 只认 BlockHitResult，准星指实体时原版不再画任何框；脚印要的是
 *   “像方块那样常显”、不依赖调试按键，于是在自己的渲染器里复刻方块框的画法：线几何走
 *   submitCustomGeometry（与吊钩的钓线同一条通路），坐标用实体局部系且不施加本渲染器的 yaw 旋转
 *   （判定本就轴对齐）。零 mixin、不碰原版私有管线。
 */
public class FootprintEntityRenderer extends EntityRenderer<FootprintEntity, FootprintEntityRenderer.FootprintRenderState> {
	// 贴图水平尺寸（正方形边长）由服务端算好的最终边长（FootprintEntity.getTexSize()）直接决定：
	// 渲染时取其一半作局部半宽/半长，不再读客户端基准、不再叠加位堆栈缩放。
	// 贴图抬高量：生成时 0.02，随存续时间线性下沉，销毁前落到 0.01（而非固定值 + 随机抖动）。
	// 下限 0.01 仍是为了避开与地面方块顶面共面的 z-fight；上限降到 0.02 则为了不再看起来悬浮在空中。
	private static final float RENDER_Y_AT_BIRTH = 0.02F;
	private static final float RENDER_Y_AT_DEATH = 0.01F;

	// - - - 瞄准判定框（复刻原版选中方块样式） - - -
	// 常规框：黑色 alpha=102，即原版 ARGB.black(102)
	private static final int OUTLINE_COLOR = ARGB.black(102);
	// 开启“高对比度方块外框”时的框色（原版 LevelRenderer 同值）
	private static final int OUTLINE_COLOR_HIGH_CONTRAST = -11010079;
	// 高对比度下的厚底衬：原版用 secondaryBlockOutline 画一层不透明纯黑粗线再画细线，使框在任何背景上都可辨
	private static final int OUTLINE_SECONDARY_COLOR = -16777216; // 不透明黑
	private static final float OUTLINE_SECONDARY_WIDTH = 7.0F;
	// 判定框微量外扩：底边否则与地面方块顶面共面，会闪（原版实体框同样留了这点余量）
	private static final double OUTLINE_INFLATE = 0.004;

	public FootprintEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
		this.shadowStrength = 0.0F;
	}

	@Override
	public FootprintRenderState createRenderState() {
		return new FootprintRenderState();
	}

	@Override
	public void extractRenderState(FootprintEntity entity, FootprintRenderState state, float tickDelta) {
		super.extractRenderState(entity, state, tickDelta);
		state.yawDeg = entity.getYRot();
		state.texSize = entity.getTexSize();
		// 脚印贴图：把服务端同步下来的贴图名解析成可用路径（未定制/资源包里没这个文件 → 默认 footprint.png）。
		// 两条渲染管线（天光半透明 / 高亮脉冲）都用这一个值，保证高亮前后贴图一致、不会一亮就跳回默认图。
		state.texture = FootprintTextures.resolve(entity.getTextureName());
		state.highlighted = entity.isHighlighted();
		// 全局同步的脉冲相位：用世界游戏时间（+插值）而非实体年龄，让所有高亮脚印同步闪烁
		state.gameTime = entity.level().getGameTime() + tickDelta;
		// 存续淡出：剩余时长不足总时长一半时线性变透明（min(1, 剩余/(总/2))）
		state.fadeAlpha = Mth.clamp(entity.getFadeAlpha(), 0.0F, 1.0F);
		// 存续下沉：按整段生命线性插值（与 fadeAlpha 的“后半段才淡出”是两条独立曲线，但同源于存续进度）
		// 抬高量 = 存续下沉曲线 + 配置的全局贴图高度偏移（纯渲染，不影响实体坐标/判定盒/存续检测）
		state.renderY = Mth.lerp(entity.getLifeProgress(), RENDER_Y_AT_BIRTH, RENDER_Y_AT_DEATH) + Common.CONFIG.getFootprintYOffset();
		// 瞄准判定框：与原版选中方块同源，仅当“当前确实可被选中（isPickable）”且准星命中的是本脚印时才画。
		// hitResult 每客户端 tick 才刷新一次（框随准星的滞后与原版方块框一致），故再补一道 isPickable() 实时门：
		// 切到手持物品/开始潜行的那一帧框立即消失，不必等下一 tick。
		state.aimed = entity.isPickable()
				&& Minecraft.getInstance().hitResult instanceof EntityHitResult hit && hit.getEntity() == entity;
		if (state.aimed) {
			// 世界系 AABB → 渲染局部系（减去插值位置），并微量外扩防底边与地面共面闪
			Vec3 pos = entity.getPosition(tickDelta);
			state.outlineBox = entity.getBoundingBox().inflate(OUTLINE_INFLATE).move(-pos.x, -pos.y, -pos.z);
		}
	}

	@Override
	public void submit(FootprintRenderState state, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState) {
		// 判定框先画，且刻意在 pushPose/旋转之前：此时位堆栈正落在实体原点，AABB 是轴对齐的，
		// 不该跟着贴图一起绕 yaw 转（原版实体框 likewise 始终轴对齐）
		if (state.aimed && state.outlineBox != null) {
			AABB box = state.outlineBox;
			float lineWidth = VersionUtil.getBlockOutlineLineWidth();
			if (VersionUtil.isHighContrastBlockOutline()) {
				// 高对比度：先垫一层不透明纯黑粗线，再画细线（与原版两遍一致）
				submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.secondaryBlockOutline(),
						(pose, consumer) -> drawBoxEdges(pose, consumer, box, OUTLINE_SECONDARY_COLOR, OUTLINE_SECONDARY_WIDTH));
				submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.lines(),
						(pose, consumer) -> drawBoxEdges(pose, consumer, box, OUTLINE_COLOR_HIGH_CONTRAST, lineWidth));
			} else {
				submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.lines(),
						(pose, consumer) -> drawBoxEdges(pose, consumer, box, OUTLINE_COLOR, lineWidth));
			}
		}

		poseStack.pushPose();

		// 抬高并随存续下沉，既避免与地面重叠，又让脚印“陷进地里”而不是原地变透明
		poseStack.translate(0.0F, state.renderY, 0.0F);
		// 绕 Y 旋转对齐脚印朝向：用原版 EntityRenderDispatcher 同款约定 rotationDegrees(180 - yaw)。
		// 推导：绕 Y 转 θ 把局部 -Z 映到 (-sinθ, -cosθ)，而实体前进方向是 (-sin(yaw), cos(yaw))，
		// 只有 θ = 180 - yaw 两者才相等（直接用 yaw 会把 Z 分量镜像：贴图朝向与行进方向反）。
		// 对应关系：局部 -Z = 前进方向（贴图 v=0 那一侧）。
		poseStack.mulPose(new Matrix4f().rotation(Axis.YP.rotationDegrees(180.0F - state.yawDeg)));
		// 贴图沿实体 yaw 旋转后在局部 X 上关于原点对称（居中）：左右脚偏移已烘入实体坐标，此处不再平移。
		// 尺寸直接由服务端下发的最终边长决定（见下方 half），故不再做位堆栈缩放；实体碰撞箱/判定框本就与贴图解耦、不受影响。

		// 单个四边形：非高亮走原版实体半透明管线（采样 lightmap、应用天光、被方块遮挡、支持 alpha 混合）；
		// 高亮走自定义脉冲管线（关深度穿墙、原色↔纯白闪烁、不受光照）。
		// 光照：非高亮取实体处完整光照坐标（天光 + 方块光/火把环境光）→ 白天亮、夜里暗、火把旁被暖照。
		// （“发红”元凶是 overlay 红色行，另由 NO_OVERLAY 规避，与此处方块光无关，故可安全恢复环境光。）
		// 顶点 alpha：非高亮=淡出系数×255（entityTranslucent 启用 SRC_ALPHA 混合，顶点 alpha 作为不透明度与背景叠加，
		// 实现“越接近自动销毁越透明”；cutout 无混合、此值无效，故必须走 translucent）；高亮=脉冲值（由 fsh 当插值因子）。
		int light = state.lightCoords;
		// 半尺寸：服务端下发的最终边长之半（已含基准×倍率），客户端不再乘本地基准
		float half = state.texSize * 0.5F;
		if (state.highlighted) {
			float pulse = (float) ((Math.sin(state.gameTime * 0.15) + 1.0) * 0.5); // 0..1 往复
			int vertexAlpha = (int) (pulse * 255.0F);
			drawFootprintQuad(poseStack, submitNodeCollector, FootprintRenderTypes.footprintSeeThrough(state.texture), light, vertexAlpha, half);
		} else {
			int fadeAlpha = (int) (state.fadeAlpha * 255.0F);
			drawFootprintQuad(poseStack, submitNodeCollector, FootprintRenderTypes.footprint(state.texture), light, fadeAlpha, half);
		}

		poseStack.popPose();
	}

	/**
	 * 画判定框的 12 条棱。顶点写法对齐原版 ShapeRenderer#renderShape（LINES 顶点格式）：
	 * addVertex(Pose, x, y, z) + setColor(ARGB) + setNormal(边方向) + setLineWidth。
	 * 法线取归一化的边方向（轴对齐盒子的每条边本就沿一个坐标轴）。
	 */
	private static void drawBoxEdges(PoseStack.Pose pose, VertexConsumer consumer, AABB box, int color, float lineWidth) {
		float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
		float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;
		// 底面四边
		drawEdge(pose, consumer, color, lineWidth, minX, minY, minZ, maxX, minY, minZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, minY, minZ, maxX, minY, maxZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, minY, maxZ, minX, minY, maxZ);
		drawEdge(pose, consumer, color, lineWidth, minX, minY, maxZ, minX, minY, minZ);
		// 顶面四边
		drawEdge(pose, consumer, color, lineWidth, minX, maxY, minZ, maxX, maxY, minZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, maxY, minZ, maxX, maxY, maxZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, maxY, maxZ, minX, maxY, maxZ);
		drawEdge(pose, consumer, color, lineWidth, minX, maxY, maxZ, minX, maxY, minZ);
		// 四条竖棱
		drawEdge(pose, consumer, color, lineWidth, minX, minY, minZ, minX, maxY, minZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, minY, minZ, maxX, maxY, minZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, minY, maxZ, maxX, maxY, maxZ);
		drawEdge(pose, consumer, color, lineWidth, minX, minY, maxZ, minX, maxY, maxZ);
	}

	private static void drawEdge(PoseStack.Pose pose, VertexConsumer consumer, int color, float lineWidth,
			float x1, float y1, float z1, float x2, float y2, float z2) {
		float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len <= 0.0F) return;
		dx /= len; dy /= len; dz /= len;
		consumer.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(lineWidth);
		consumer.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(lineWidth);
	}

	/**
	 * 提交一张脚印四边形。
	 * 非高亮的 entityTranslucent 顶点格式含 UV1(overlay)/UV2(light)/Normal，必须显式给出：
	 * - setLight(light)：UV2 = 实体处完整光照（天光 + 方块光），供 lightmap 采样得到昼夜与火把亮度；
	 * - setOverlay(OverlayTexture.NO_OVERLAY)：走透明 overlay 行，避免 (0,0) 红色行导致偏红（历史坑）；
	 * - setNormal(0,1,0)：地面向上法线，供实体方向光计算。
	 * 高亮的脉冲管线格式(POSITION_COLOR_TEX_LIGHTMAP)不含 UV1/Normal，对应 setter 会被安全忽略；
	 * 其顶点色 alpha(vertexAlpha) 由 fsh 用作原色↔纯白的插值因子。
	 */
	private static void drawFootprintQuad(PoseStack poseStack, SubmitNodeCollector collector,
			RenderType renderType, int light, int vertexAlpha, float half) {
		collector.submitCustomGeometry(poseStack, renderType, (pose, vertexConsumer) -> {
			var matrix = pose.pose();
			vertexConsumer.addVertex(matrix, -half, 0.0F, -half)
					.setColor(255, 255, 255, vertexAlpha).setUv(0.0F, 0.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
			vertexConsumer.addVertex(matrix, -half, 0.0F, half)
					.setColor(255, 255, 255, vertexAlpha).setUv(0.0F, 1.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
			vertexConsumer.addVertex(matrix, half, 0.0F, half)
					.setColor(255, 255, 255, vertexAlpha).setUv(1.0F, 1.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
			vertexConsumer.addVertex(matrix, half, 0.0F, -half)
					.setColor(255, 255, 255, vertexAlpha).setUv(1.0F, 0.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
		});
	}

	public static class FootprintRenderState extends EntityRenderState {
		public float yawDeg;
		public float texSize;
		public float renderY;
		public Identifier texture = FootprintRenderTypes.TEXTURE;
		public boolean highlighted;
		public double gameTime;
		public float fadeAlpha;
		public boolean aimed;
		public AABB outlineBox;
	}
}
//? } else {
/*package com.rimo.traceableprint.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.rimo.traceableprint.Common;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//? if <= 1.20.1 {
//? } else {
import com.mojang.blaze3d.vertex.MeshData;
//? }
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/^*
 * 1.21.1 legacy 实体渲染器：该版本实体渲染仍是经典的 {@code EntityRenderer<T>}（单泛型），
 * 没有 RenderState / extractRenderState / submit / SubmitNodeCollector / RenderPipeline / 自定义脉冲着色器。
 * 逐帧在 {@code render(...)} 里直接从实体取值、向 {@code MultiBufferSource} 缓冲绘制一个四边形薄片。
 *
 * 非高亮：{@link FootprintRenderTypes#footprint(ResourceLocation)}（entityTranslucent）——采样 lightmap、
 *   被方块遮挡、SRC_ALPHA 混合让顶点 alpha 当不透明度用，实现存续末段渐淡；光照取传入的 packedLight。
 * 高亮：自定义着色器 footprint_legacy + 即时绘制（Tesselator/BufferUploader，关深度测试）——原色↔纯白随顶点 alpha 脉冲，
 *   且穿墙显示；着色器不可用时回退到 {@link FootprintRenderTypes#footprintSeeThrough(ResourceLocation)}（fullbright，alpha 不低于 0.5）。
 * 瞄准判定框：准星命中本脚印时用 {@link LevelRenderer#renderLineBox} 复刻原版选中方块的黑色半透明线框。
 ^/
public class FootprintEntityRenderer extends EntityRenderer<FootprintEntity> {
	// 贴图抬高量：生成时 0.02，随存续时间线性下沉到 0.01（下限避开与地面共面的 z-fight）
	private static final float RENDER_Y_AT_BIRTH = 0.02F;
	private static final float RENDER_Y_AT_DEATH = 0.01F;
	// 判定框微量外扩，避免底边与地面方块顶面共面闪
	private static final double OUTLINE_INFLATE = 0.004;
	// 原版选中方块框：黑色 alpha=102/255
	private static final float OUTLINE_RED = 0.0F;
	private static final float OUTLINE_GREEN = 0.0F;
	private static final float OUTLINE_BLUE = 0.0F;
	private static final float OUTLINE_ALPHA = 102.0F / 255.0F;
	// fullbright 光照坐标（高亮 emissive 回退路径用，不受世界光照影响）
	private static final int FULL_BRIGHT = 0xF000F0;
	// 1.21.1 无 RenderPipeline / AW 工厂，穿墙纯白脉冲改由自定义着色器 footprint_legacy + 即时绘制实现。
	// 懒加载：首次高亮绘制时在渲染线程编译 ShaderInstance；失败则永久回退到批量 fullbright 近似（不逐帧重试）。
	private static ShaderInstance pulseShader;
	private static boolean pulseShaderFailed;
	private static final Logger LOGGER = LogUtils.getLogger();

	public FootprintEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
		this.shadowStrength = 0.0F;
	}

	@Override
	public void render(FootprintEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
			MultiBufferSource bufferSource, int packedLight) {
		// 瞄准判定框先画，且在 yaw 旋转之前：此时位堆栈落在实体原点、AABB 轴对齐，不随贴图旋转
		if (entity.isPickable() && Minecraft.getInstance().hitResult instanceof EntityHitResult hit
				&& hit.getEntity() == entity) {
			Vec3 pos = entity.getPosition(partialTick);
			AABB box = entity.getBoundingBox().inflate(OUTLINE_INFLATE).move(-pos.x, -pos.y, -pos.z);
			LevelRenderer.renderLineBox(poseStack, bufferSource.getBuffer(RenderType.lines()), box,
					OUTLINE_RED, OUTLINE_GREEN, OUTLINE_BLUE, OUTLINE_ALPHA);
		}

		poseStack.pushPose();
		// 抬高并随存续下沉（0.02 -> 0.01）+ 配置的全局贴图高度偏移（纯渲染，不影响实体坐标/判定盒/存续检测）
		float renderY = Mth.lerp(entity.getLifeProgress(), RENDER_Y_AT_BIRTH, RENDER_Y_AT_DEATH) + Common.CONFIG.getFootprintYOffset();
		poseStack.translate(0.0F, renderY, 0.0F);
		// 绕 Y 旋转对齐朝向：原版 EntityRenderDispatcher 同款约定 rotationDegrees(180 - yaw)
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - entity.getYRot()));
		// 尺寸直接由服务端下发的最终边长决定（见下方 half），故不再做位堆栈缩放；实体碰撞箱/判定框本就与贴图解耦、不受影响

		ResourceLocation texture = FootprintTextures.resolve(entity.getTextureName());
		// 半尺寸：服务端下发的最终边长之半（已含基准×倍率），客户端不再乘本地基准
		float half = entity.getTexSize() * 0.5F;
		Matrix4f matrix = poseStack.last().pose();
		if (entity.isHighlighted()) {
			// 高亮：穿墙 + 原色↔纯白脉冲。世界游戏时间（+插值）正弦作脉冲强度，随顶点色 alpha 传入着色器做 mix(texColor,white,pulse)。
			double gameTime = entity.level().getGameTime() + partialTick;
			float pulse = (float) ((Math.sin(gameTime * 0.15) + 1.0) * 0.5);
			renderSeeThroughPulse(poseStack, bufferSource, texture, half, pulse);
		} else {
			int fadeAlpha = (int) (Mth.clamp(entity.getFadeAlpha(), 0.0F, 1.0F) * 255.0F);
			drawFootprintQuad(matrix, bufferSource.getBuffer(FootprintRenderTypes.footprint(texture)),
					packedLight, fadeAlpha, half);
		}
		poseStack.popPose();

		super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
	}

	/^*
	 * 画一张脚印四边形。1.21.1 的 entityTranslucent 顶点格式含 UV1(overlay)/UV2(light)/Normal，须显式给出：
	 * - setLight(light)：UV2 = 实体处光照，供 lightmap 采样得到昼夜与火把亮度；
	 * - setOverlay(OverlayTexture.NO_OVERLAY)：走透明 overlay 行，避免 (0,0) 红色行导致偏红（历史坑）；
	 * - setNormal(0,1,0)：地面向上法线，供实体方向光计算。
	 * 该版本 VertexConsumer 用 builder 链、无 endVertex，逐 addVertex 隐式收尾。
	 *
	 * 【1.20.1 调用顺序硬约束】该版本 BufferBuilder 是**游标式**写入：每个 setter 先比对当前游标元素的 usage+index，
	 * 不匹配就静默跳过（游标不前进），endVertex 见游标未归零即抛 "Not filled all elements of the vertex"。
	 * NEW_ENTITY 的元素顺序是 Position, Color, UV0, UV1(overlay), UV2(light), Normal, Padding，
	 * 故必须严格按 vertex -> color -> uv -> overlayCoords -> uv2 -> normal 调用（1.21.1+ 为偏移式写入，顺序自由）。
	 ^/
	private static void drawFootprintQuad(Matrix4f matrix, VertexConsumer consumer, int light, int vertexAlpha, float half) {
		//? if <= 1.20.1 {
		/^consumer.vertex(matrix, -half, 0.0F, -half)
				.color(255, 255, 255, vertexAlpha).uv(0.0F, 0.0F)
				.overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 1.0F, 0.0F).endVertex();
		consumer.vertex(matrix, -half, 0.0F, half)
				.color(255, 255, 255, vertexAlpha).uv(0.0F, 1.0F)
				.overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 1.0F, 0.0F).endVertex();
		consumer.vertex(matrix, half, 0.0F, half)
				.color(255, 255, 255, vertexAlpha).uv(1.0F, 1.0F)
				.overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 1.0F, 0.0F).endVertex();
		consumer.vertex(matrix, half, 0.0F, -half)
				.color(255, 255, 255, vertexAlpha).uv(1.0F, 0.0F)
				.overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0.0F, 1.0F, 0.0F).endVertex();
		^///? } else {
		consumer.addVertex(matrix, -half, 0.0F, -half)
				.setColor(255, 255, 255, vertexAlpha).setUv(0.0F, 0.0F).setLight(light)
				.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
		consumer.addVertex(matrix, -half, 0.0F, half)
				.setColor(255, 255, 255, vertexAlpha).setUv(0.0F, 1.0F).setLight(light)
				.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
		consumer.addVertex(matrix, half, 0.0F, half)
				.setColor(255, 255, 255, vertexAlpha).setUv(1.0F, 1.0F).setLight(light)
				.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
		consumer.addVertex(matrix, half, 0.0F, -half)
				.setColor(255, 255, 255, vertexAlpha).setUv(1.0F, 0.0F).setLight(light)
				.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
		//? }
	}

	/^*
	 * 穿墙 + 原色↔纯白脉冲高亮：用自定义着色器 footprint_legacy（mix 原色/纯白、不采样 lightmap）即时绘制一张四边形，
	 * 绘制期间关闭深度测试 → 穿墙。ModelViewMat/ProjMat 由 {@code BufferUploader.drawWithShader} 从 RenderSystem 自动带入，
	 * 顶点位置仍用 {@code poseStack.last().pose()} 做 CPU 烘焙，与批量非高亮路径一致（因而位置观感与原版吻合）。
	 * 着色器不可用时回退到批量 fullbright emissive，并把整体 alpha 抬到不低于 0.5，避免旧的“全透明”闪烁。
	 ^/
	private static void renderSeeThroughPulse(PoseStack poseStack, MultiBufferSource bufferSource,
			ResourceLocation texture, float half, float pulse) {
		Matrix4f matrix = poseStack.last().pose();
		ShaderInstance shader = getOrCreatePulseShader();
		if (shader == null) {
			int alpha = (int) (Mth.clamp(0.5F + 0.5F * pulse, 0.0F, 1.0F) * 255.0F);
			drawFootprintQuad(matrix, bufferSource.getBuffer(FootprintRenderTypes.footprintSeeThrough(texture)),
					FULL_BRIGHT, alpha, half);
			return;
		}

		int pulseAlpha = (int) (Mth.clamp(pulse, 0.0F, 1.0F) * 255.0F);
		RenderSystem.setShaderTexture(0, texture);
		RenderSystem.setShader(() -> shader);

		//? if <= 1.20.1 {
		/^BufferBuilder buffer = Tesselator.getInstance().getBuilder();
		// POSITION_TEX_COLOR 的元素顺序是 Position, UV0, Color —— 游标式写入要求 uv 先于 color（见 drawFootprintQuad 注释）
		buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		buffer.vertex(matrix, -half, 0.0F, -half).uv(0.0F, 0.0F).color(255, 255, 255, pulseAlpha).endVertex();
		buffer.vertex(matrix, -half, 0.0F, half).uv(0.0F, 1.0F).color(255, 255, 255, pulseAlpha).endVertex();
		buffer.vertex(matrix, half, 0.0F, half).uv(1.0F, 1.0F).color(255, 255, 255, pulseAlpha).endVertex();
		buffer.vertex(matrix, half, 0.0F, -half).uv(1.0F, 0.0F).color(255, 255, 255, pulseAlpha).endVertex();

		// 关深度测试 → 穿墙；开 alpha 混合；关背面剔除（四边形绕向不随朝向固定，避免被剔）
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.disableDepthTest();
		BufferUploader.drawWithShader(buffer.end());
		RenderSystem.enableDepthTest();
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
		^///? } else {
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
				DefaultVertexFormat.POSITION_TEX_COLOR);
		buffer.addVertex(matrix, -half, 0.0F, -half).setColor(255, 255, 255, pulseAlpha).setUv(0.0F, 0.0F);
		buffer.addVertex(matrix, -half, 0.0F, half).setColor(255, 255, 255, pulseAlpha).setUv(0.0F, 1.0F);
		buffer.addVertex(matrix, half, 0.0F, half).setColor(255, 255, 255, pulseAlpha).setUv(1.0F, 1.0F);
		buffer.addVertex(matrix, half, 0.0F, -half).setColor(255, 255, 255, pulseAlpha).setUv(1.0F, 0.0F);

		// 关深度测试 → 穿墙；开 alpha 混合；关背面剔除（四边形绕向不随朝向固定，避免被剔）
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.disableDepthTest();
		try (MeshData mesh = buffer.buildOrThrow()) {
			BufferUploader.drawWithShader(mesh);
		}
		RenderSystem.enableDepthTest();
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
		//? }
	}

	/^* 懒加载 footprint_legacy 着色器（仅渲染线程、仅高亮首次触发）；编译失败则置标志永久回退，避免逐帧重试刷屏。 ^/
	private static ShaderInstance getOrCreatePulseShader() {
		if (pulseShader == null && !pulseShaderFailed) {
			try {
				// name 经 ResourceLocation.withDefaultNamespace 解析为 minecraft:footprint_legacy，
				// 读 assets/minecraft/shaders/core/footprint_legacy.{json,vsh,fsh}（供本类 <=1.21.1 的即时绘制路径使用；>1.21.1 走 footprint_pulse 管线）。
				// 该 json 必须同时写 "attributes" 与 "blend"：1.20.1 缺 attributes 会跳过 glBindAttribLocation
				// 导致颜色/UV 错位（高亮全透明），缺 blend 会被默认 opaque 覆盖掉 CPU 端 enableBlend。
				pulseShader = new ShaderInstance(Minecraft.getInstance().getResourceManager(), "footprint_legacy",
							DefaultVertexFormat.POSITION_TEX_COLOR);
			} catch (Exception e) {
				pulseShaderFailed = true;
				// 不静默吞异常：把真实失败原因（常见为 json 缺 values / glsl 编译错 / 资源路径不对）打印到日志，便于定位。
				LOGGER.error("[traceableprint] footprint_legacy \u7740\u8272\u5668\u52a0\u8f7d\u5931\u8d25\uff0c\u56de\u9000 fullbright \u8fd1\u4f3c", e);
			}
		}
		return pulseShader;
	}

	@Override
	public ResourceLocation getTextureLocation(FootprintEntity entity) {
		return FootprintTextures.resolve(entity.getTextureName());
	}
}
*///? }
