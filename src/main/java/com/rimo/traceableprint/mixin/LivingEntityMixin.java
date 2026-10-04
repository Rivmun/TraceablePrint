package com.rimo.traceableprint.mixin;

import com.rimo.traceableprint.Common;
import com.rimo.traceableprint.config.Config;
import com.rimo.traceableprint.entity.FootprintEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
//? if <= 1.21.1 {
/*import net.minecraft.nbt.CompoundTag;
*///? } else {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//? }
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

/**
 * LivingEntity 服务端侧注入：脚印生成（移动/落地检测）、链尾指针维护与持久化。
 * 客户端侧的本地高亮在 mixin.client.LivingEntityMixin，两边各归各的加载段。
 *
 * 注：早期版本曾在 jumpFromGround 离地瞬间额外留一印（参考工程 FootprintParticle 的做法），
 * 但它会被下一次落地生成的最小间距闸门（minSpawnDistance，默认 5 格）挡住：
 * 普通跳跃起跳→落地点间距总小于阈值，于是“落地脚印永远不生成”，链只剩起跳印。
 * 现已删除起跳生成，只保留移动/落地两条触发（见 onTick 的 A/B 条件）。
 *
 * 架构（去 FootprintManager 后）：
 * - 父生物链尾 UUID 仅服务端维护（mixin @Unique 字段 + NBT 持久化）：
 *   生成新脚印时把上一个脚印的 NEXT_UUID 接上、再把链尾更新为新脚印；
 *   不再用 SynchedEntityData 同步到客户端（26.1 ClassTreeIdRegistry 下，向 LivingEntity 新增
 *   同步字段会与 Mob 等原版子类在其 clinit 固化的 id 撞号）；客户端“是否链尾”验证改由
 *   FootprintEntity.IS_TAIL 承载（见 spawnFootprint 里的链尾翻转）；
 * - 数量控制不再靠链上限，改由脚印实体自身的 genTime+lifetime 过期自毁（见 FootprintEntity）；
 * - 服务端只负责生成/串链，客户端负责交互与高亮，两端完全解绑。
 *
 * 非 @Inject 的成员统一 traceableprint$ 前缀防撞名（本 mixin 无 @Implements，@Inject 方法也带前缀不影响软实现判定）。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	// 尝试生成脚印的固定间隔（tick）由 Common.CONFIG.getSpawnIntervalTicks() 提供（再乘每生物生成间隔倍率），此处只做倒计时
	@Unique private int traceableprint$footprintCooldown = 0;
	@Unique private boolean traceableprint$wasOnGround = true;
	// 记录上一次检测时的位置，只用真实位移判断“是否在移动”（方向另走速度，见 spawnFootprint）
	@Unique private double traceableprint$lastCheckX;
	@Unique private double traceableprint$lastCheckZ;
	@Unique private boolean traceableprint$lastCheckInit = false;
	// 链尾脚印 UUID：仅服务端权威维护，不走 SynchedEntityData（避免 26.1 与原版 Mob 子类 id 撞号），
	// 经 addAdditionalSaveData/readAdditionalSaveData 写读 NBT，卸载重载后链不丢。
	@Unique private String traceableprint$lastFootprint = "";

	// 默认左右/前后偏移幅度（方块）：不再开放给玩家配置，硬编码于此；逐生物覆写表命中时优先用表值，
	// 二者最终都乘综合缩放倍率（见 spawnFootprint 里的 footprintScale）。
	@Unique private static final double traceableprint$DEFAULT_SIDE_OFFSET = 0.125F;
	@Unique private static final double traceableprint$DEFAULT_FORWARD_OFFSET = 0.0625F;

	// 被玩家骑乘的坐骑生成加速倍率：马类等在玩家操控下前进极快，据此把「生成冷却」与「最小间距」两道闸门一并 ×0.5
	// （加密、拉近），让高速坐骑身后拖出更连续的足迹。只做加速、不做未骑减速；固定值、不开放配置（与参考工程一致）。
	@Unique private static final float traceableprint$RIDED_SPAWN_MULTIPLIER = 0.5F;

	@Inject(method = "tick", at = @At("TAIL"))
	private void traceableprint$onTick(CallbackInfo ci) {
		LivingEntity entity = (LivingEntity) (Object) this;
		// 脚印只在服务端生成
		if (entity.level().isClientSide()) return;

		double cx = entity.getX();
		double cz = entity.getZ();
		boolean onGround = entity.onGround();
		// 自上次刷新以来的真实位移平方判断移动（阈值 0.01≈0.1 格）：
		// “是否真的在走”只认位移，不认速度——顶墙/撞船壁时速度仍非零但根本没位移
		double dx = this.traceableprint$lastCheckInit ? cx - this.traceableprint$lastCheckX : 0.0;
		double dz = this.traceableprint$lastCheckInit ? cz - this.traceableprint$lastCheckZ : 0.0;
		boolean moving = (dx * dx + dz * dz) > 1.0E-2;
		// 落地边沿（上一 tick 离地、本 tick 贴地）每 tick 检测、且不受冷却节流影响：
		// 无论主动跳跃、从高处跌落还是被击飞后落地，只要发生“离地→贴地”即生成（此路径无视冷却，仅受最小间距闸门约束）。
		boolean justLanded = !this.traceableprint$wasOnGround && onGround;
		this.traceableprint$wasOnGround = onGround;
		if (justLanded) {
			traceableprint$spawnFootprint(entity, new Vec3(dx, 0.0, dz));
			// 落地生成后重置冷却，让紧随其后的“移动周期生成”暂停一个窗口，避免落地印与移动印瞬时叠加
			this.traceableprint$footprintCooldown = traceableprint$spawnInterval(entity);
			this.traceableprint$lastCheckX = cx;
			this.traceableprint$lastCheckZ = cz;
			this.traceableprint$lastCheckInit = true;
			return;
		}
		// 移动周期生成：仍受冷却节流（节流窗口在冲刺时缩短至三分之二）。冷却未过只递减、不动位移基准。
		if (this.traceableprint$footprintCooldown > 0) {
			this.traceableprint$footprintCooldown--;
			return;
		}
		if (moving && onGround) {
			// 位移一并传入：速度近零（骑船、被推挤）时作为方向兜底
			traceableprint$spawnFootprint(entity, new Vec3(dx, 0.0, dz));
		}
		this.traceableprint$lastCheckX = cx;
		this.traceableprint$lastCheckZ = cz;
		this.traceableprint$lastCheckInit = true;
		this.traceableprint$footprintCooldown = traceableprint$spawnInterval(entity);
	}

	/**
	 * 当前生效的移动生成间隔（tick）：基础值来自 spawnIntervalTicks，先乘每生物生成间隔倍率（mobIntervalList，
	 * 未命中为 1.0），再在冲刺时缩短至三分之二，被玩家骑乘的坐骑再乘 {@code traceableprint$RIDED_SPAWN_MULTIPLIER} 加密，最后四舍五入并强制下限 1 tick。
	 *【为何先乘倍率再取整】把倍率留在 int 上（先转 int 再乘）会让 0.6 这类小倍率在 base=1 时被截回原值；
	 * 先乘后取整则在小 base、小倍率下自然向到 1 tick 下限，语义上是“最多密到这个程度”而不是“倍率失效”。
	 */
	@Unique
	private static int traceableprint$spawnInterval(LivingEntity entity) {
		float base = Common.CONFIG.getSpawnIntervalTicks() * traceableprint$intervalMultiplier(entity);
		if (entity.isSprinting()) base *= 2.0F / 3.0F;
		if (traceableprint$isPlayerRidden(entity)) base *= traceableprint$RIDED_SPAWN_MULTIPLIER;
		return Math.max(1, Math.round(base));
	}

	/**
	 * 每生物生成间隔倍率（Config.mobIntervalMap）：按注册名 namespace:path 精确匹配，不查标签，
	 * 未命中（及非正数/NaN）回退 1.0。与尺寸/偏移表同一个取 id 写法；
	 * 本方法只在冷却刷新与间距闸门两处被调（每生物每生成窗口一次），构造一次小字符串在噪声以下。
	 */
	@Unique
	private static float traceableprint$intervalMultiplier(LivingEntity entity) {
		return Common.CONFIG.resolveIntervalMultiplier(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
	}

	/**
	 * 该实体是否正被玩家骑乘操控：坐骑的 {@code getControllingPassenger()} 返回驾驶它的实体（玩家操控时即为玩家）。
	 * 马类等可操控坐骑在玩家骑乘下前进速度极快，据此触发 {@code traceableprint$RIDED_SPAWN_MULTIPLIER} 的生成加速；
	 * 口径为「被玩家操控的坐骑」，不绑前后偏移表（故猪、炽足兽等被玩家驾驶时同样命中）。
	 * {@code getControllingPassenger()} 是 {@code Entity} public、跨 1.20.1~26.x 签名一致，{@code instanceof Player} 即可，无需 stonecutter 分支。
	 */
	@Unique
	private static boolean traceableprint$isPlayerRidden(LivingEntity entity) {
		return entity.getControllingPassenger() instanceof Player;
	}

	// 持久化链尾 UUID：写入生物 NBT，卸载重载/服务端重启后链头不丢（值取自服务端 @Unique 字段）
	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	//? if <= 1.21.1 {
	/*private void traceableprint$saveLastFootprint(CompoundTag tag, CallbackInfo ci) {
		if (!this.traceableprint$lastFootprint.isEmpty()) tag.putString("TraceablePrintLastFootprint", this.traceableprint$lastFootprint);
	}
	*///? } else {
	private void traceableprint$saveLastFootprint(ValueOutput output, CallbackInfo ci) {
		if (!this.traceableprint$lastFootprint.isEmpty()) output.putString("TraceablePrintLastFootprint", this.traceableprint$lastFootprint);
	}
	//? }

	@Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
	//? if <= 1.21.1 {
	/*private void traceableprint$loadLastFootprint(CompoundTag tag, CallbackInfo ci) {
		this.traceableprint$lastFootprint = tag.getString("TraceablePrintLastFootprint");
	}
	*///? } else {
	private void traceableprint$loadLastFootprint(ValueInput input, CallbackInfo ci) {
		this.traceableprint$lastFootprint = input.getStringOr("TraceablePrintLastFootprint", "");
	}
	//? }

	/**
	 * 尝试生成脚印：生成闸门（潜行 / 隐形 / 生物名单）→ 区块加载检查 → 朝向/身后落点解算 → 落地方块判定 → 最小间距过滤
	 * → 创建实体并串链（回写上一脚印 NEXT，再把父实体链尾更新为新脚印）→ addFreshEntity。
	 * 判定/间距不通过时不产生任何副作用（不腾位、不改链尾）。
	 *
	 * @param movementHint 区间真实位移，作速度失效（骑乘/推挤已减速）时的兜底方向；零向量表示忽略
	 */
	@Unique
	private void traceableprint$spawnFootprint(LivingEntity parent, Vec3 movementHint) {
		if (!(parent.level() instanceof ServerLevel world)) return;

		// 生成闸门：放在唯一入口，onTick 的移动/落地两条触发路径一并生效
		// 0) 模组总开关：禁用直接返回；仅玩家档下非玩家实体一律不留（其余闸门对玩家档同样生效）
		Config.WorkMode enableMod = Common.CONFIG.getEnableMod();
		if (enableMod == Config.WorkMode.DISABLED) return;
		if (enableMod == Config.WorkMode.PLAYER_ONLY && !(parent instanceof Player)) return;
		// 1) 潜行（蹲走）一律不留脚印（无开关：潜行本身就是“轻手轻脚”的语义，与配置无关）
		if (parent.isCrouching()) return;
		// 2) 隐形实体是否留脚印交给配置（关=隐身者真正无痕；开=隐身≠无迹可寻）
		if (!Common.CONFIG.isPrintsForInvisible() && traceableprint$isInvisible(parent)) return;
		// 3) 生物名单：未反转=黑名单（名单内不留），反转=白名单（仅名单内留）。
		//    两种语义等价于“命中状态与反转标志不一致就跳过”（空名单=无人命中，故黑名单放行一切、白名单拦住一切）
		if (traceableprint$isListedEntity(parent) != Common.CONFIG.isEntityListInverted()) return;

		// 检查父实体所在区块是否已加载
		if (!world.getChunkSource().hasChunk(
				SectionPos.blockToSectionCoord(parent.getBlockX()),
				SectionPos.blockToSectionCoord(parent.getBlockZ()))) {
			return;
		}

		// 计算移动朝向：优先用实体本 tick 速度（瞬时方向，转弯跟手，不再滞后一整个生成间隔）。
		// 26.1 起玩家移动改为客户端只上传 Input、服务端 travel() 模拟，travelInAir/travelInFluid/travelFlying
		// 均会写 deltaMovement，故速度对玩家同样有效（旧版“玩家服务端速度不可靠”的前提已不成立）；
		// 速度近零（骑船/马等非操控座骑、被推挤后已停下）时回退区间真实位移，再不行回退实体自身朝向。
		// 方向向量与 yaw 的关系：dir = (-sin(yaw), 0, cos(yaw))，反推 yaw = atan2(-x, z)
		// （注意 atan2 的第一个参数取 -x：MC 的 yaw 顺时针为正，与数学极角不同）
		Vec3 velocity = parent.getDeltaMovement();
		Vec3 movement = velocity.horizontalDistanceSqr() > 1.0E-4 ? velocity : movementHint;
		float targetYaw = parent.getYRot();
		if (movement.horizontalDistanceSqr() > 1.0E-4) {
			targetYaw = (float) Math.toDegrees(Math.atan2(-movement.x, movement.z));
		}

		// 脚印放在父实体身后约 0.35 格处、贴地放置（全局抬高量已改为纯渲染偏移，见 FootprintEntityRenderer，不再烘入实体坐标）
		//【朝向】MC 实体 yaw 的前进向量是 (-sin(yaw), cos(yaw))（yaw=0 朝南 +Z、yaw=-90 朝东 +X），
		// 所以“身后”是 +sin / -cos；写成 -sin / -cos 会把 X 分量镜像，导致东西走向时脚印落在身前。
		double rad = Math.toRadians(targetYaw);
		double behindX = parent.getX() + Math.sin(rad) * 0.35;
		double behindZ = parent.getZ() - Math.cos(rad) * 0.35;
		double footY = parent.getY();

		// 左右脚偏移：沿垂直于行进方向的侧向（前进 dir=(-sin,cos) 的法向 (cos,sin)）平移，随机取正负模拟左/右脚；
		// 前后偏移：沿前进方向 dir=(-sin,cos) 平移，随机取正负（前/后错落）；两者同时、独立随机，直接烘入实体真实坐标，
		// 使贴图在实体中居中的同时碰撞箱/交互随之偏移。
		// 幅度优先取逐生物覆写表（按注册名 namespace:path 精确匹配，不匹配标签）命中条目的 float（取绝对值），
		// 未命中则回退上面硬编码的默认；无论读到正负都重新随机符号；最终统一乘综合缩放倍率 footprintScale。
		// 综合缩放倍率（逐生物尺寸表 × 幼体 0.66 × 原版 getScale）：既用于贴图缩放，也用于左右/前后偏移缩放，
		// 使大生物的脚印不仅贴图更大、左右脚间距与前后错落也一并放大，小生物反之。
		String mobId = BuiltInRegistries.ENTITY_TYPE.getKey(parent.getType()).toString();
		float footprintScale = Common.CONFIG.resolveSizeMultiplier(mobId);
		if (parent.isBaby()) footprintScale *= 0.66F;
		footprintScale *= parent.getScale();
		Float customSide = Common.CONFIG.findSideOffset(mobId);
		Float customForward = Common.CONFIG.findForwardOffset(mobId);
		double sideMagnitude = (customSide != null ? Math.abs(customSide) : traceableprint$DEFAULT_SIDE_OFFSET) * footprintScale;
		double forwardMagnitude = (customForward != null ? Math.abs(customForward) : traceableprint$DEFAULT_FORWARD_OFFSET) * footprintScale;
		double sideSign = parent.getRandom().nextBoolean() ? 1.0 : -1.0;
		double sideOffset = sideMagnitude * sideSign;
		double forwardSign = parent.getRandom().nextBoolean() ? 1.0 : -1.0;
		double forwardOffset = forwardMagnitude * forwardSign;
		double spawnX = behindX + Math.cos(rad) * sideOffset - Math.sin(rad) * forwardOffset;
		double spawnZ = behindZ + Math.sin(rad) * sideOffset + Math.cos(rad) * forwardOffset;

		// 生成位置预检：落脚格实心→否则回退下一格完整方块；命中 blockHeight 表的落脚方块再叠加额外 Y 抬升（避免被非完整方块遮挡）
		// 判定通过前绝不改动任何状态；spawnY 为 NaN 表示取消生成
		double spawnY = traceableprint$resolveSpawnY(world, spawnX, footY, spawnZ);
		if (Double.isNaN(spawnY)) {
			return;
		}

		UUID parentId = parent.getUUID();
		UUID lastId = traceableprint$parseUuid(this.traceableprint$lastFootprint);

		// 最小间距：与上一个脚印（若仍在世界）过近则跳过，防原地跳跃/慢蹭刷屏（上一脚印卸载/取不到则放行）
		// 每生物生成间隔倍率与最小间距同乘（与上面冷却里的倍率一致，否则“时间变密、空间不变”会互相抵消），
		// 冲刺时闸门再缩短至三分之二，让高速下更密集的落点（含更近的落地）也能留印
		float intervalMul = Common.CONFIG.resolveIntervalMultiplier(mobId);
		double minDist = Common.CONFIG.getMinSpawnDistance() * intervalMul;
		if (parent.isSprinting()) minDist *= 2.0 / 3.0;
		// 被玩家骑乘的坐骑：与冷却闸门同一倍率把最小间距也一并缩短（否则高速下更近的落点仍会被间距拦住，表现为“加速没生效”）
		if (traceableprint$isPlayerRidden(parent)) minDist *= traceableprint$RIDED_SPAWN_MULTIPLIER;
		if (minDist > 0 && lastId != null && world.getEntity(lastId) instanceof FootprintEntity prevFp) {
			double ddx = prevFp.getX() - spawnX;
			double ddz = prevFp.getZ() - spawnZ;
			if (ddx * ddx + ddz * ddz < minDist * minDist) {
				return;
			}
		}

		FootprintEntity footprint = new FootprintEntity(world, parentId);
		footprint.setPos(spawnX, spawnY, spawnZ);
		footprint.setYRot(targetYaw);
		footprint.setXRot(parent.getXRot());
		// 贴图最终边长 = 基准尺寸 × 综合倍率（footprintScale 与左右/前后偏移同源）：整体在服务端算好后经同步数据下发，
		// 客户端渲染器直接用该边长、不再读本地基准，故多人下贴图尺寸彻底以服务端为准；不改实体碰撞箱/交互。
		footprint.setTexSize(Common.CONFIG.getFootprintTextureSize() * footprintScale);
		// 脚印贴图替换：按注册名在 config.textureList 命中则从候选贴图名里随机取一个，服务端选定后走同步数据下发，
		// 保证同一条脚印在所有玩家眼里是同一张贴图（也同一条链上左右脚/前后脚可以各不相同）。
		// 未命中就不写（留空串）：客户端按默认 footprint.png 渲染；名字→资源路径的组装与存在性校验都在客户端做。
		List<String> textureCandidates = Common.CONFIG.resolveTextureCandidates(mobId);
		if (!textureCandidates.isEmpty()) {
			footprint.setTextureName(textureCandidates.get(parent.getRandom().nextInt(textureCandidates.size())));
		}
		// 新脚印即当前链尾
		footprint.setChainTail(true);

		// 串链：新脚印 UUID 写给上一个脚印（若仍在世界），并把它从链尾降为普通节点，再把父实体链尾更新为新脚印
		if (lastId != null && world.getEntity(lastId) instanceof FootprintEntity prevFp) {
			prevFp.setNextUUID(footprint.getUUID());
			prevFp.setChainTail(false);
		}

		world.addFreshEntity(footprint);
		this.traceableprint$lastFootprint = footprint.getUUID().toString();
	}

	/**
	 * 隐形判定：隐身效果（喷药/生物自带）或实体自身的 invisible 标志（setInvisible）。
	 * 不用 isInvisibleTo(Player)：那个随观看者而变（创造玩家对生存玩家隐形等），
	 * 而“脚印该不该存在”必须是服务端客观事实。
	 * 1.20.1 里 hasEffect 收裸 MobEffect、MobEffects.INVISIBILITY 同名，源码写法一致，无需 stonecutter 分支。
	 */
	@Unique
	private static boolean traceableprint$isInvisible(LivingEntity parent) {
		return parent.isInvisible() || parent.hasEffect(MobEffects.INVISIBILITY);
	}

	@Unique
	private static UUID traceableprint$parseUuid(String str) {
		if (str == null || str.isEmpty()) return null;
		try {
			return UUID.fromString(str);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * 生成位置判定 + 抬高解算（复刻 FootprintParticle 落脚规则，并叠加 blockHeight 额外抬升）：
	 * 1) 落脚格：方块允许生成 且 canOcclude → 用该格，Y 叠加其 blockHeight；
	 * 2) 否则回退下一格：方块允许生成 且 canOcclude 且碰撞形状为完整方块 → 用该格，Y 叠加其 blockHeight；
	 * 3) 都失败 → 返回 {@link Double#NaN} 取消生成。
	 * blockHeight 用于雪层/灵魂沙/泥等视觉高于碰撞箱的方块：实体踩上去下沉，脚印需相应抬高以免被遮挡。
	 */
	@Unique
	private static double traceableprint$resolveSpawnY(ServerLevel world, double x, double footY, double z) {
		BlockPos probe = BlockPos.containing(x, footY, z);
		BlockState state = world.getBlockState(probe);
		if (traceableprint$isBlockAllowed(world, probe) && state.canOcclude()) {
			return footY + traceableprint$blockHeightOffset(world, probe);
		}
		BlockPos below = probe.below();
		BlockState belowState = world.getBlockState(below);
		if (traceableprint$isBlockAllowed(world, below) && belowState.canOcclude()
				&& Block.isShapeFullBlock(belowState.getCollisionShape(world, below))) {
			return footY + traceableprint$blockHeightOffset(world, below);
		}
		return Double.NaN;
	}

	/**
	 * 查方块额外抬高表（Config.blockHeightMap，主字段即 Map）：O(1) 命中。
	 * 优先级——精确 id 命中 > 任何 #tag 命中（与 isBlockAllowed / isListedEntity 同一套 “id 优先短路”规则）；
	 * 同 id 与多个 tag 都命中时取 id；多个 tag 都命中时按 MC 提供的 tags() 流序首个胜出。
	 */
	@Unique
	private static double traceableprint$blockHeightOffset(ServerLevel world, BlockPos pos) {
		if (!Common.CONFIG.hasAnyBlockHeights()) return 0.0;
		BlockState block = world.getBlockState(pos);
		String id = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
		Float direct = Common.CONFIG.blockHeightValue(id);
		if (direct != null) return direct;
		//~ if >= 26.1 'block.getBlockHolder()' -> 'block.typeHolder()'
		Float viaTag = block.typeHolder().tags()
				.map(t -> Common.CONFIG.blockHeightValue("#" + t.location()))
				.filter(java.util.Objects::nonNull)
				.findFirst()
				.orElse(null);
		return viaTag == null ? 0.0 : viaTag;
	}

	/**
	 * 生物名单命中判定（与方块白名单同构）：实体类型ID "namespace:path" 或 "#namespace:tag" 标签任一命中即为命中。
	 * 本方法只回答“在不在名单里”，黑名单/白名单语义由 Config.isEntityListInverted() 决定。
	 * 标签走 typeHolder().tags()（与方块的 state.typeHolder().tags() 同一套机制，可写 #minecraft:undead 这类分组）。
	 *【热路径优化】精确 id 与标签都走 Config 内部的 HashSet.contains（O(1)）：名单里 id 与 "#tag" 混存，
	 * 同一集合能直接满足两种写法；.tags().anyMatch(...) 对 Stream 短路，避免中间 List。
	 */
	@Unique
	private static boolean traceableprint$isListedEntity(LivingEntity parent) {
		if (Common.CONFIG.isInEntityList(BuiltInRegistries.ENTITY_TYPE.getKey(parent.getType()).toString())) {
			return true;
		}
		// 空名单短路：不取 Holder、不遍历标签
		if (!Common.CONFIG.hasAnyEntityEntries()) return false;
		//~ if >= 26.1 'parent.getType().builtInRegistryHolder()' -> 'parent.typeHolder()'
		return parent.typeHolder().tags().anyMatch(
				tag -> Common.CONFIG.isInEntityList("#" + tag.location()));
	}

	/**
	 * 方块允许判定：白名单(支持 "#tag") 优先；其后硬度门槛 |defaultDestroyTime| < gate。
	 *【热路径优化】精确 id 与标签都走 Config 内部 HashSet.contains（O(1)）；applyBlocks 为空时干脆不取 Holder；
	 * .tags().anyMatch(...) 对 Stream 短路，避免中间 List。
	 */
	@Unique
	private static boolean traceableprint$isBlockAllowed(ServerLevel world, BlockPos pos) {
		BlockState block = world.getBlockState(pos);
		String id = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
		boolean canGen = Common.CONFIG.isInApplyBlocks(id);
		if (!canGen && Common.CONFIG.hasAnyApplyBlockEntries()) {
			//~ if >= 26.1 'block.getBlockHolder()' -> 'block.typeHolder()'
			canGen = block.typeHolder().tags().anyMatch(
					tag -> Common.CONFIG.isInApplyBlocks("#" + tag.location()));
		}
		if (!canGen) {
			float gate = Common.CONFIG.getHardnessGate();
			float hardness = block.getBlock().defaultDestroyTime();
			canGen = gate > 0 && hardness >= 0 && Mth.abs(hardness) < gate;
		}
		return canGen;
	}
}
