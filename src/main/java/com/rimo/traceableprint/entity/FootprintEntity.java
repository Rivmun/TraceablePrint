package com.rimo.traceableprint.entity;

import com.rimo.traceableprint.util.ClientHighlights;
import com.rimo.traceableprint.Common;
import com.rimo.traceableprint.VersionUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
//? if <= 1.21.1 {
/*import net.minecraft.nbt.CompoundTag;
*///? } else {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//? }
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

/**
 * 脚印实体：不可移动、不可破坏的纯标记实体。
 * PARENT_UUID 记录生成它的父生物；NEXT_UUID 记录同一条足迹链中的下一个脚印（由服务端生成时写入），
 * 玩家点击脚印即依链寻踪；无后继且确为链尾时跳向父生物本体。
 *
 * 存续采用“自毁”而非链上限：genTime 记录生成时的绝对游戏时间（同步 + 持久化），rainAge 记录雨天额外老化
 * （两端各自本地累加、不走同步，服务端额外落 NBT），
 * 服务端每秒比对 (now−genTime)+rainAge 与 lifetime，超时即 discard；从存档加载的过期脚印同样自毁，无需外部管理器维护。
 */
public class FootprintEntity extends Entity {
	// 该脚印所属的父生物 UUID（26.1 已移除 OPTIONAL_UUID 序列化器，改用字符串存储）
	private static final EntityDataAccessor<String> PARENT_UUID =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.STRING);
	// 同一条链中的下一个脚印 UUID（最后一个脚印此值为空，此时应跳向父生物）
	private static final EntityDataAccessor<String> NEXT_UUID =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.STRING);
	// 是否为足迹链当前链尾（服务端维护并同步）。
	// 客户端点击脚印沿链前进到无可走时，仅当自己确为链尾才跳向父生物（断链验证）。
	// 链尾指针不能放在父生物 LivingEntity 的同步数据上（26.1 会与 Mob 等原版子类 id 撞号），
	// 故下放到我们完全掌控的 FootprintEntity 自身，随实体同步。
	private static final EntityDataAccessor<Boolean> IS_TAIL =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.BOOLEAN);
	// 生成时的绝对游戏时间（服务端 level 时钟），走同步数据：
	// 服务端 tick 据此 + rainAge 判定过期自毁，客户端渲染器据此计算淡出透明度。
	private static final EntityDataAccessor<Long> GEN_TIME =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.LONG);
	// 脚印贴图最终边长（方块，服务端生成时按「基准尺寸 × 逐生物倍率 × 幼体 × getScale」算好并同步）：
	// 客户端渲染器直接用其一半作半宽，不再读本地基准、不再叠加位堆栈缩放；不改实体碰撞箱。
	private static final EntityDataAccessor<Float> TEX_SIZE =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.FLOAT);
	// 脚印贴图名（服务端生成时按父生物注册名在 config.textureList 命中后随机选定并同步）：
	// 存字符串而非索引，是为了不要求两端配置一致——客户端拿到名字后自行组装 Identifier 并校验资源包里是否存在，
	// 取不到就退回默认 footprint.png。空串表示“没有定制贴图”，直接走默认。
	private static final EntityDataAccessor<String> TEX_NAME =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.STRING);

	// 高亮倒计时：纯客户端本地状态（谁点击谁知道），由交互直接写入、客户端 tick 递减，驱动渲染器金色脉冲。
	private int clientHighlightTicks = 0;

	// 雨天额外老化量（tick）：两端各自逐 tick 累加（isRainingAt 为真时 += 1/倍率 − 1）的本地字段，不走同步；
	// 服务端那份权威（决定移除）且额外落 NBT 供重启续算，客户端那份仅驱动淡出。与 GEN_TIME 共同构成存续进度。
	private float rainAge = 0.0F;

	// 方向指示倒计时：写在“被点击”的本脚印上（而非被点亮的那个），交互时一次写入、客户端 tick 递减。
	private int directionTicks = 0;

	// 从存档读入的生成时间：仅当读入时机早于 entityData 就绪时兜底，
	// 首次服务端 tick 补写（正常路径在 readAdditionalSaveData / 构造器已直接写入）。
	private Long pendingGenTime;

	// 支撑探测深度：从脚印实体位置正下方向下探测支撑方块的深度（供存续检测用）。
	private static final double SUPPORT_PROBE_DEPTH = 0.2;

	// 服务端自检（过期 + 存续）的时间窗口（tick），也是 checkPhase 的模数：
	// 一个脚印每 CHECK_INTERVAL_TICKS tick 被检查一次；N 个脚印按 UUID 派生 phase 均匀摄开，
	// 均每 tick 约 N/CHECK_INTERVAL_TICKS 个跑检查体。旧写法过期 20/存续 10 已含 2 拾频差异，
	// 统一为 20 既符合 “每秒一次足够”的直觉，也与错相叠加后把每 tick 峰值开销控制到约 1/20。
	private static final int CHECK_INTERVAL_TICKS = 20;

	// 被追踪提示的节流窗口（tick）：同一链尾脚印在此期间重复被点击只提示一次，防刷。
	private static final int TRACE_NOTIFY_COOLDOWN_TICKS = 60;
	// 下次允许提示的绝对游戏时间（仅服务端使用，无需同步/持久化）。
	private long traceNotifyReadyTime = 0L;

	// 自检相位（0~19）：从 UUID 派生，把不同脚印的服务端自检（过期/存续）均匀错开到 20 tick 窗口内，
	// 消除 “所有脚印同一 tick 批量查方块” 的峰值突发。选 UUID 而非 Random 有三点考虑：
	// 1) 零额外分配与调用（一次 long 取模 vs RandomSource.nextInt）；
	// 2) 世界重载相位稳定（同一实体同一 UUID → 同一 phase），不需要落 NBT；
	// 3) 与 super.tick() 每 tick 开销相比，比较常量换成读一个 final int 字段完全在噪声以下。
	private final int checkPhase = (int) Math.floorMod(this.getUUID().getLeastSignificantBits(), CHECK_INTERVAL_TICKS);

	// - - - 方向指示粒子（纯客户端视觉，由被点击的脚印发射） - - -
	// 喷发间隔（tick）：点击即时放出首颗，其后每秒四颗（每 5 tick 一颗），持续整个高亮时长
	private static final int DIRECTION_PARTICLE_INTERVAL_TICKS = 5;
	// 起点扇形半角（度）：26.3 的 PORTAL “起点 = 终点 + 传入的速度参数”，故把该偏移方向绕竖直轴在目标方向左右各 30° 内随机
	private static final double DIRECTION_PARTICLE_CONE_DEGREES = 30.0;
	// 起点扇形半径（方块）：终点已落在长 1 方块的方向线上，起点再沿扇形方向外推至多 1 方块
	private static final double DIRECTION_PARTICLE_SPRAY = 1.0;
	// 当前发射源脚印 id（仅客户端有意义，对齐 ClientHighlights.highlightedId 的排他模式：
	// 存 id 不存引用，发射源移出本地副本/被移除时引用随实体自然消亡，静态字段零泄漏）
	private static int activeDirectionEmitterId = -1;

	// 脚印最大渲染距离（方块）：超出后脚印在屏幕上已小到看不清，直接不渲染以省开销。
	// 注意 shouldRenderAtSqrDistance 的入参是“距离的平方”（Minecraft 为避免开方传平方值），
	// 故下面比较的是 RENDER_DISTANCE_BLOCKS 的平方，别把平方当成格数写错阈值。
	private static final double RENDER_DISTANCE_BLOCKS = 64.0;

	public FootprintEntity(EntityType<? extends Entity> type, Level level) {
		super(type, level);
		this.setNoGravity(true); // 脚印悬于地面之上，不受重力
		// GEN_TIME 在首次服务端 tick 播种为当前游戏时间（构造期 entityData 尚未就绪）
	}

	// 供服务端生成时调用的便捷构造
	public FootprintEntity(ServerLevel level, UUID parentId) {
		this(Common.FOOTPRINT, level);
		this.setParentUUID(parentId);
		// super() 返回后 entityData 已就绪，构造时直接播种生成时间
		// （不能留到 tickCount==0 再播种：baseTick 会先把 tickCount 递增到 1，首帧判断永远不命中）
		this.entityData.set(GEN_TIME, level.getLevelData().getGameTime());
	}

	//? if <= 1.20.1 {
	/*// 1.20.1 无 SynchedEntityData.Builder：defineSynchedData() 无参，直接调 this.entityData.define。
	@Override
	protected void defineSynchedData() {
		//【关键】所有 EntityDataAccessor 必须在实体数据构建时注册，否则 entityData.set 会直接崩溃
		this.entityData.define(PARENT_UUID, "");
		this.entityData.define(NEXT_UUID, "");
		this.entityData.define(IS_TAIL, false);
		this.entityData.define(GEN_TIME, 0L);
		this.entityData.define(TEX_SIZE, com.rimo.traceableprint.config.Config.DEFAULT_FOOTPRINT_TEXTURE_SIZE);
		this.entityData.define(TEX_NAME, "");
	}
	*///? } else {
	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(PARENT_UUID, "");
		builder.define(NEXT_UUID, "");
		builder.define(IS_TAIL, false);
		builder.define(GEN_TIME, 0L);
		builder.define(TEX_SIZE, com.rimo.traceableprint.config.Config.DEFAULT_FOOTPRINT_TEXTURE_SIZE);
		builder.define(TEX_NAME, "");
	}
	//? }

	// 设置脚印贴图最终边长（方块，仅服务端生成时写入，随实体同步到客户端）
	public void setTexSize(float size) {
		this.entityData.set(TEX_SIZE, size);
	}

	// 获取脚印贴图最终边长（方块，双端可读）
	public float getTexSize() {
		return this.entityData.get(TEX_SIZE);
	}

	// 设置脚印贴图名（仅服务端生成时写入，随实体同步到客户端）；空串 = 使用默认贴图
	public void setTextureName(String name) {
		this.entityData.set(TEX_NAME, name == null ? "" : name);
	}

	// 获取脚印贴图名（双端可读，客户端渲染器据此检索资源包贴图）
	public String getTextureName() {
		return this.entityData.get(TEX_NAME);
	}

	public void setParentUUID(UUID uuid) {
		this.entityData.set(PARENT_UUID, uuid.toString());
	}

	public Optional<UUID> getParentUUID() {
		return parseUuid(this.entityData.get(PARENT_UUID));
	}

	public void setNextUUID(UUID uuid) {
		this.entityData.set(NEXT_UUID, uuid.toString());
	}

	// 标记/清除本脚印的链尾身份（服务端生成新链尾时：新脚印置 true，旧的链尾置 false）
	public void setChainTail(boolean tail) {
		this.entityData.set(IS_TAIL, tail);
	}

	public boolean isChainTail() {
		return this.entityData.get(IS_TAIL);
	}

	public Optional<UUID> getNextUUID() {
		return parseUuid(this.entityData.get(NEXT_UUID));
	}

	private static Optional<UUID> parseUuid(String str) {
		if (str == null || str.isEmpty()) return Optional.empty();
		try {
			return Optional.of(UUID.fromString(str));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	// 统一取随机源：1.20.1 无 Entity#getRandom()，用受保护字段 this.random；>1.20.1 走 getRandom()。
	//? if <= 1.20.1 {
	/*private net.minecraft.util.RandomSource rand() {
		return this.random;
	}
	*///? } else {
	private net.minecraft.util.RandomSource rand() {
		return this.getRandom();
	}
	//? }

	// 生成时的绝对游戏时间（双端可读，走同步数据）
	public long getGenTime() {
		return this.entityData.get(GEN_TIME);
	}

	/** 雨天额外老化量（tick，本地字段，两端各自累加；服务端额外落 NBT，不走同步）。 */
	public float getRainAge() {
		return this.rainAge;
	}
	private void setRainAge(float age) {
		this.rainAge = age;
	}

	/**
	 * 存续进度 0..1：(当前游戏时间 − 生成时间 + 雨天额外老化) / 总时长。与 getFadeAlpha()、服务端过期判定
	 * 同源于一条时间轴，供渲染把贴图从生成时的抬高量线性下沉到销毁前（“陷入地里”而不是原地变透明）。
	 * rainAge 为本地累加字段（非同步）：从出生即被追踪时两端同起点同规则逐 tick 累加，淡出与服务端移除锁步；
	 * 仅“中途才进入视野”的脚印客户端从 0 起算、比服务端少淋一段而略微晚淡，但那一段你并不在场、看不见。
	 * 未播种 / 时长非法时按 0（刚生成）处理。
	 */
	public float getLifeProgress() {
		long genTime = this.getGenTime();
		long lifetime = Common.CONFIG.getFootprintLifetimeTicks();
		if (genTime <= 0 || lifetime <= 0) return 0.0F;
		long now = this.level().getLevelData().getGameTime();
		float age = (float) (now - genTime) + this.getRainAge();
		return Mth.clamp(age / (float) lifetime, 0.0F, 1.0F);
	}

	/**
	 * 存续淡出系数：透明度 = min(1, 剩余时长 / (总时长 / 2))。
	 * 等价于 min(1, 2×(1 - 存续进度))：前一半生命保持完全不透明，后一半线性淡出至全透明（客户端渲染用）。
	 */
	public float getFadeAlpha() {
		return Math.min(1.0F, 2.0F * (1.0F - this.getLifeProgress()));
	}

	// 是否处于高亮状态（仅客户端有意义：读本地倒计时，服务端永远为 false）
	public boolean isHighlighted() {
		return this.clientHighlightTicks > 0;
	}

	/**
	 * 激活客户端本地高亮（重复点击续期）。
	 */
	public void applyClientHighlight(int ticks) {
		if (this.level().isClientSide()) {
			this.clientHighlightTicks = Math.max(this.clientHighlightTicks, ticks);
		}
	}

	/**
	 * 清除客户端本地高亮（排他切换：点亮其它脚印时回到常规渲染管线）。
	 */
	public void clearClientHighlight() {
		if (this.level().isClientSide()) {
			this.clientHighlightTicks = 0;
		}
	}

	// 26.1 的 NBT 读写改成了 ValueInput / ValueOutput 流式接口；1.21.1 及更早仍用 CompoundTag
	//? if <= 1.21.1 {
	/*@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
		parseUuid(tag.getString("ParentUUID"))
				.ifPresent(this::setParentUUID);
		parseUuid(tag.getString("NextUUID"))
				.ifPresent(this::setNextUUID);
		// 恢复生成时间：entityData 此时已就绪则直接写入；缺失时按“当前时刻”兑底，避免因无字段而立即自毁
		long genTime = tag.contains("GenTime") ? tag.getLong("GenTime") : this.level().getLevelData().getGameTime();
		try {
			this.entityData.set(GEN_TIME, genTime);
		} catch (IllegalStateException e) {
			this.pendingGenTime = genTime;
		}
		// 恢复雨天额外老化：缺失时按 0（未淋雨）兜底
		this.setRainAge((float) (tag.contains("RainAge") ? tag.getDouble("RainAge") : 0.0));
		// 恢复贴图边长：缺失时按基准尺寸兜底
		this.setTexSize((float) (tag.contains("TexSize") ? tag.getDouble("TexSize") : com.rimo.traceableprint.config.Config.DEFAULT_FOOTPRINT_TEXTURE_SIZE));
		// 恢复贴图名：缺失时按空串（默认 footprint 贴图）兜底
		this.setTextureName(tag.getString("TextureName"));
	}
	*///? } else {
	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		parseUuid(input.getStringOr("ParentUUID", ""))
				.ifPresent(this::setParentUUID);
		parseUuid(input.getStringOr("NextUUID", ""))
				.ifPresent(this::setNextUUID);
		// 恢复生成时间：entityData 此时已就绪则直接写入；
		// 缺失时按“当前时刻”兑底，避免因无字段而立即自毁
		long genTime = input.getLongOr("GenTime", this.level().getLevelData().getGameTime());
		try {
			this.entityData.set(GEN_TIME, genTime);
		} catch (IllegalStateException e) {
			// 极端情况：读档时机早于同步数据构建，缓到首次服务端 tick 补写
			this.pendingGenTime = genTime;
		}
		// 恢复雨天额外老化：缺失时按 0（未淋雨）兜底
		this.setRainAge((float) input.getDoubleOr("RainAge", 0.0));
		// 恢复贴图边长：缺失时按基准尺寸兜底
		this.setTexSize((float) input.getDoubleOr("TexSize", com.rimo.traceableprint.config.Config.DEFAULT_FOOTPRINT_TEXTURE_SIZE));
		// 恢复贴图名：缺失时按空串（默认 footprint 贴图）兑底。存档里的名字可能是旧配置留下的，
		// 如今已从资源包删除也没关系——客户端解析时校验存在性，取不到自然退回默认。
		this.setTextureName(input.getStringOr("TextureName", ""));
	}
	//? }

	//? if <= 1.21.1 {
	/*@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
		tag.putString("ParentUUID", getParentUUID().map(UUID::toString).orElse(""));
		tag.putString("NextUUID", getNextUUID().map(UUID::toString).orElse(""));
		tag.putLong("GenTime", this.getGenTime());
		tag.putDouble("RainAge", this.getRainAge());
		tag.putDouble("TexSize", this.getTexSize());
		String textureName = this.getTextureName();
		if (!textureName.isEmpty()) {
			tag.putString("TextureName", textureName);
		}
	}
	*///? } else {
	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putString("ParentUUID", getParentUUID().map(UUID::toString).orElse(""));
		output.putString("NextUUID", getNextUUID().map(UUID::toString).orElse(""));
		output.putLong("GenTime", this.getGenTime());
		output.putDouble("RainAge", this.getRainAge());
		output.putDouble("TexSize", this.getTexSize());
		String textureName = this.getTextureName();
		if (!textureName.isEmpty()) {
			output.putString("TextureName", textureName);
		}
	}
	//? }

	// Entity.interact 的参数个数随版本不同：1.21.11 及更早为 (Player, InteractionHand) 两参数；
	// 26.1 起加入命中位置 Vec3，变为三参数签名。方法体两者都只用 player/hand，与 location 无关。
	@Override
	//? if <= 1.21.11 {
	/*public @NonNull InteractionResult interact(@NonNull Player player, @NonNull InteractionHand hand) {
	*///? } else {
	public @NonNull InteractionResult interact(@NonNull Player player, @NonNull InteractionHand hand, net.minecraft.world.phys.@NonNull Vec3 location) {
	//? }
		// 潜行/手持物品时的“对射线透明”已上移到 isPickable()，从拾取阶段源头放行
		// （潜行或主手有物 → 本脚印根本选不中，interact 不会被调），故此处不再重复潜行判断。
		// 交互在客户端完全封闭：链数据取 SynchedEntityData 本地副本，高亮是仅点击者可见的本地状态。
		// 主手本地调用直接 CONSUME，客户端不再重试副手。
		if (this.level() instanceof ClientLevel level) {
			this.traceAndHighlight(level);
			return InteractionResult.CONSUME;
		}
		// 服务端：右键实体的包本就会送达服务端并回调此处（客户端返回 CONSUME 只阻止副手重试，不拦服务端）。
		// 在此权威地判定“本次点击是否把人追到了父玩家”，若是则向被追踪者发 action bar 提示。
		// 非模组客户端可能主/副手各发一包，故只认主手。
		if (hand == InteractionHand.MAIN_HAND) {
			this.notifyParentIfTraced((ServerPlayer) player);
		}
		return InteractionResult.PASS;
	}

	/**
	 * 服务端：若本次点击把足迹链追到了“父玩家”（本脚印为链尾、无存活后继、且父 UUID 对应一名在线玩家），
	 * 按“追到的是谁”分两种提示：追到别人→通知被追踪者“有人正在寻找你的踪迹…”（受 notifyTraced 开关控制，
	 * 不暴露追踪者身份）；追到自己（含单人）→给点击者自己一条“这似乎是你自己的足迹…”（纯本地反馈，
	 * 不受该开关影响）。两者走同一节流。
	 * 走服务端权威的 isChainTail/NEXT/PARENT 同步数据 + 全局按 UUID 查玩家，不受追踪者客户端渲染距离限制。
	 */
	private void notifyParentIfTraced(ServerPlayer tracer) {
		// 仅当链上无存活后继、且自己确为链尾时，本次点击才会“跳向父实体”（与客户端 traceAndHighlight 判定一致）
		if (hasLiveNext() || !this.isChainTail()) return;
		UUID parentId = this.getParentUUID().orElse(null);
		if (parentId == null) return;
		ServerPlayer target = this.level().getServer().getPlayerList().getPlayer(parentId);
		if (target == null) return; // 父实体不是在线玩家（其它生物 / 已卸载或离线玩家）：静默
		if (target == tracer) {
			this.sendTraceHint(tracer, "traceableprint.message.own_footprints");
			return;
		}
		if (Common.CONFIG.isNotifyTraced()) {
			this.sendTraceHint(target, "traceableprint.message.be_tracked");
		}
	}

	/** 发一条“追到人”的 action bar 提示；同一脚印带节流，防连点刷屏。 */
	private void sendTraceHint(ServerPlayer recipient, String translationKey) {
		long now = this.level().getGameTime();
		if (now < this.traceNotifyReadyTime) return; // 节流窗口内，忽略重复点击
		this.traceNotifyReadyTime = now + TRACE_NOTIFY_COOLDOWN_TICKS;
		// 经 VersionUtil 封装 action bar 发送（26.1 为 ServerPlayer#sendOverlayMessage，旧版自动回退 displayClientMessage）。
		VersionUtil.sendActionBar(recipient, Component.translatable(translationKey));
	}

	// 本脚印是否仍有存活的直接后继（有则本次点击只推进到下一个脚印，尚未追到人）。
	private boolean hasLiveNext() {
		UUID next = this.getNextUUID().orElse(null);
		if (next == null) return false;
		return entityByUuid(this.level(), next) instanceof FootprintEntity fp && !fp.isRemoved();
	}

	// 1.21.1 的 Level/ClientLevel 仅有 getEntity(int)（网络 id），按 UUID 取实体需分端：服务端走 ServerLevel#getEntity(UUID)，
	// 客户端遍历本地可见实体；>=1.21.11 起 Level 自带 getEntity(UUID)。
	//? if <= 1.21.1 {
	/*private static Entity entityByUuid(Level level, UUID uuid) {
		if (uuid == null) return null;
		if (level instanceof ServerLevel serverLevel) return serverLevel.getEntity(uuid);
		if (level instanceof ClientLevel clientLevel) {
			for (Entity entity : clientLevel.entitiesForRendering()) {
				if (entity.getUUID().equals(uuid)) return entity;
			}
		}
		return null;
	}
	*///? } else {
	private static Entity entityByUuid(Level level, UUID uuid) {
		return level.getEntity(uuid);
	}
	//? }

	/**
	 * 解析本脚印的寻踪目标（纯客户端）：沿 NEXT_UUID 取本地副本中仍存活的下一个脚印；
	 * 无后继/后继已消失时，仅当“自己确为链尾”才指向父实体（断链验证，与 traceAndHighlight 同一套规则）。
	 * 父实体是本地玩家自己时不算目标（第一人称下指向自己无意义，服务端另有文字提示）。
	 * 交互点亮与方向指示粒子共用本方法，保证“高亮谁”与“烟迹指哪”永远一致。
	 */
	private Entity resolveTraceTarget(ClientLevel level) {
		Entity next = this.getNextUUID().map(u -> entityByUuid(level, u)).orElse(null);
		if (next instanceof FootprintEntity nextFootprint && !nextFootprint.isRemoved()) {
			return nextFootprint;
		}
		if (this.isChainTail()) {
			// 仅当自己确为链尾时，才跳向父实体（否则为被炸断的悬空中段，静默跳过）
			// 26.1 的 ClientLevel 已不再有 player 字段（只有 players() 列表），本地玩家取 Minecraft#player；
			// 本方法只在客户端分支被调（instanceof ClientLevel 已守卫），取不到玩家时为 null，不影响判等。
			Entity parent = this.getParentUUID().map(u -> entityByUuid(level, u)).orElse(null);
			if (parent instanceof LivingEntity living && living != Minecraft.getInstance().player && !living.isRemoved()) {
				return living;
			}
		}
		return null;
	}

	/**
	 * 依链寻踪（纯客户端）：点亮下一个脚印，链尾则跳向父实体；目标解析见 {@link #resolveTraceTarget}。
	 * 同时在本（被点击的）脚印上挂方向指示倒计时：由“脚下这一格”向刚点亮的目标飘粒子指路，
	 * 而不是让目标自己往自己脸上喷（重复点击仅续期，与高亮同时长）。
	 * 发射源排他与高亮同规则：全局同时仅一个“被点击脚印”在发射，切换时掐断上一个；
	 * 点击当场立即放出首颗（不等间隔对齐，消除最多一秒的起手延迟），其后每秒一颗。
	 */
	private void traceAndHighlight(ClientLevel level) {
		Entity target = resolveTraceTarget(level);
		if (target != null) {
			ClientHighlights.apply(level, target.getId(), Common.CONFIG.getHighlightTicks());
			if (Common.CONFIG.isShowDirectionParticles()) {
				// 排他：上一个发射源不是本次点击时，掐断它的发射（已不在本地副本则随实体消亡，无需处理）
				if (activeDirectionEmitterId != -1 && activeDirectionEmitterId != this.getId()
						&& level.getEntity(activeDirectionEmitterId) instanceof FootprintEntity previous) {
					previous.directionTicks = 0;
				}
				activeDirectionEmitterId = this.getId();
				this.directionTicks = Common.CONFIG.getHighlightTicks();
				this.spawnDirectionParticle(level, target);
			}
		}
	}

	/**
	 * 方向指示续发（纯客户端，仅本次点击写入的倒计时内生效）：按倒计时每秒（20 tick 对齐）放出一颗。
	 * 用倒计时而非实体 tickCount 取模：后者相位随机，点击后的首颗续发可能还要再等近一秒；
	 * 倒计时从点击那刻起算，周期天然与首颗衔接。
	 */
	private void tickDirectionIndicator(ClientLevel level) {
		if (this.directionTicks <= 0) return;
		this.directionTicks--;
		if (this.directionTicks % DIRECTION_PARTICLE_INTERVAL_TICKS != 0) return;
		Entity target = resolveTraceTarget(level);
		if (target == null) return;
		this.spawnDirectionParticle(level, target);
	}

	/**
	 * 向目标方向发射一颗传送门粒子（PORTAL，紫色漩涡）。
	 * 26.3 的 PortalParticle 是“确定曲线滑移”模型：传入坐标 (px,py,pz) 是粒子消散处的**终点**，
	 * 传入的 (vx,vy,vz) 不是速度而是“起点−终点”的偏移，且原版在 Y 上硬编码 +1 方块（粒子生来从终点上方 1 格坠落归位）。
	 * 据此布局指向：终点随机落在“从被点击脚印出发、指向目标、长 1 方块”的线上；起点偏移方向把 dir 绕竖直轴在左右 ±30° 内随机偏转，
	 * 于是粒子从朝向目标的扇形飞出、汇聚回线上终点，用整排分布而非单颗轨迹表达指向。
	 * 目标取身体中段（+半身高），仰/俯角时方向更直观；过近（<0.5）时方向无意义且会糊在脚印上，跳过。
	 * ClientLevel#addParticle 是纯本地调用，不产生任何网络包；粒子走深度测试、不能穿墙，
	 * 目标被方块全遮时不可见（父实体不受影响，其 glowing 描边本就穿墙）。
	 */
	private void spawnDirectionParticle(ClientLevel level, Entity target) {
		double ox = this.getX();
		double oy = this.getY() + 0.15;
		double oz = this.getZ();
		Vec3 delta = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0).subtract(ox, oy, oz);
		double distance = delta.length();
		if (distance < 0.5) return; // 目标过近时方向无意义，且会糊在脚印上干扰脉冲辨认
		Vec3 dir = delta.scale(1.0 / distance); // 指向目标的单位向量
		double t = this.rand().nextDouble(); // 终点：沿“单位长方向线”随机取点（PORTAL 收敛消失处）
		// 起点偏移：把 dir 绕竖直轴在左右 ±30° 内随机偏转（保单位长、保俯仰），再沿该方向外推至多 1 方块
		double angle = Math.toRadians((this.rand().nextDouble() * 2.0 - 1.0) * DIRECTION_PARTICLE_CONE_DEGREES);
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double sx = dir.x * cos + dir.z * sin;
		double sz = -dir.x * sin + dir.z * cos;
		double len = this.rand().nextDouble() * DIRECTION_PARTICLE_SPRAY;
		// 传参语义：(px,py,pz)=终点，(vx,vy,vz)=起点相对终点的偏移
		level.addParticle(ParticleTypes.PORTAL,
				ox + dir.x * t, oy + dir.y * t, oz + dir.z * t,
				sx * len, dir.y * len, sz * len);
	}

	// - - - - 存续规则 - - - -

	@Override
	public void tick() {
		super.tick();
		// 两端各自逐 tick 累加雨天额外老化（本地字段，非同步）：服务端据此权威移除、客户端据此淡出。
		// 放在端分支与相位闸门之前让客户端也参与；晴天 isRainingAt 首行 isRaining() 短路，成本仅一次布尔读。
		this.accumulateRainAge();
		// 高亮倒计时与方向指示倒计时（纯客户端本地，每个玩家只维护自己的）：
		// 前者归零后渲染器自然回到非高亮管线；后者挂在“被点击”的本脚印上，与是否被点亮无关，故分两条独立递减
		if (this.level().isClientSide()) {
			if (this.clientHighlightTicks > 0) {
				this.clientHighlightTicks--;
			}
			this.tickDirectionIndicator((ClientLevel) this.level());
			return;
		}
		// 服务端：按 phase 到点自检（过期自毁 + 存续检测），两个检查共用同一时间窗口省一次取模。
		// 【为什么错相位】旧写法 tickCount % 10 / 20 == 0 会让世界内所有脚印在同 tick 集中调
		// getBlockState/getCollisionShape，形成规律性峰值 tick；按 UUID 派生 phase 摊平后，每 tick 只有约
		// N/CHECK_INTERVAL 个脚印被查，峰值时长线性下降。总检查次数不变（频率从 10→20 才真的降总量）。
		// 【为什么加 tickCount==0 兜底】错相后新生脚印可能要到下一 tick 边界才第一次自检；若一出生脚下就是空气
		// / 被完整方块埋住（极端边界），需要立刻销毁以免穿模渲染。tickCount==0 只跑一次，成本可忽略。
		if (this.tickCount == 0 || this.tickCount % CHECK_INTERVAL_TICKS != this.checkPhase) {
			return;
		}
		// 兜底补写：仅当播种路径未生效（GEN_TIME 仍为默认 0）时写入，不依赖 tickCount 首帧时序
		if (this.getGenTime() <= 0) {
			long now = this.level().getLevelData().getGameTime();
			this.entityData.set(GEN_TIME, this.pendingGenTime != null ? this.pendingGenTime : now);
			this.pendingGenTime = null;
		}
		// 过期自毁：绝对基准 (now − genTime) + 雨天额外老化 rainAge 一并计入（与 getLifeProgress 同源），超时即 discard
		long now = this.level().getLevelData().getGameTime();
		if (now - this.getGenTime() + this.getRainAge() > Common.CONFIG.getFootprintLifetimeTicks()) {
			this.discard();
			return;
		}
		// 存续检测：脚下失去支撑或被完整方块覆盖时销毁自身
		if (!isFootprintValid(this.level(), this.getX(), this.getY(), this.getZ())) {
			this.discard();
		}
	}

	/**
	 * 雨天额外老化（双端各自 tick 调用）：脚印所在格露天淋雨时，把本地 rainAge 每 tick 累加 (1/倍率 − 1)。
	 * 倍率 v∈[0.1,1] 来自 {@link com.rimo.traceableprint.config.Config#getRainAgeMultiplier()}，含义是“雨中脚印只存活基准寿命的 v 比例”：
	 * 等效时钟被提速到 1/v，故除绝对时间基准 (now−genTime) 已计的 1/tick 外，额外补 (1/v − 1)/tick；全程淋雨时总寿命精确缩为 lifetime × v。
	 * 倍率 ≥1（关闭）直接跳过，连方块都不查；{@code isRainingAt} 首行以全局 {@code isRaining()} 短路，晴天每 tick 仅一次布尔读。
	 * rainAge 为本地字段（非同步）：服务端那份权威（决定移除）且落 NBT，客户端那份仅驱动淡出；两端用各自本地配置与本端 isRainingAt 独立累加。
	 */
	private void accumulateRainAge() {
		float multiplier = Common.CONFIG.getRainAgeMultiplier();
		if (multiplier >= 1.0F) return;
		if (!this.level().isRainingAt(this.blockPosition())) return;
		this.setRainAge(this.getRainAge() + (1.0F / multiplier - 1.0F));
	}

	/**
	 * 脚印能否立足（结构判定）：用实体真实坐标而非整数方块坐标。
	 * 支撑：实体位置正下方 SUPPORT_PROBE_DEPTH 处必须有碰撞形状（贴地）；
	 * 覆盖：脚印体所在格不得是“完整方块”——非完整方块（雪层、半砖、植物等）允许立足。
	 */
	private static boolean isFootprintValid(Level world, double x, double y, double z) {
		BlockPos supportPos = BlockPos.containing(x, y - SUPPORT_PROBE_DEPTH, z);
		BlockState support = world.getBlockState(supportPos);
		if (support.getCollisionShape(world, supportPos).isEmpty()) {
			return false; // 下方悬空
		}
		BlockPos ownPos = BlockPos.containing(x, y + 0.05, z);
		BlockState own = world.getBlockState(ownPos);
		return !isFullCube(world, own, ownPos);
	}

	/**
	 * 是否完整方块（碰撞形状覆盖整格 [0,1]³）。用于“所在方块是否把脚印埋住”的判断。
	 */
	private static boolean isFullCube(Level world, BlockState state, BlockPos pos) {
		VoxelShape shape = state.getCollisionShape(world, pos);
		if (shape.isEmpty()) {
			return false; // 空气等无碰撞方块不是完整方块（空 shape 也不能调 bounds）
		}
		AABB ab = shape.bounds();
		return ab.minX <= 0.0001 && ab.minY <= 0.0001 && ab.minZ <= 0.0001
				&& ab.maxX >= 0.9999 && ab.maxY >= 0.9999 && ab.maxZ >= 0.9999;
	}

	// - - - - 不可破坏 / 不可移动 - - - -

	@Override
	public boolean isInvulnerable() {
		return true;
	}

	@Override
	//? if <= 1.21.1 {
	/*public boolean hurt(DamageSource source, float damage) {
	*///? } else {
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
	//? }
		// 爆炸伤害（TNT、苦力帕等）：清除自身脚印（仅移除自己，链上其它脚印由客户端断链验证自然兜住）；其余伤害一概无效
		if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			this.discard();
			return true;
		}
		return false;
	}

	@Override
	public boolean isSilent() {
		return true;
	}

	@Override
	public boolean isPushable() {
		return false; // 不会被推动
	}

	@Override
	//? if <= 1.21.1 {
	/*public boolean canBeCollidedWith() {
	*///? } else {
	public boolean canBeCollidedWith(Entity entity) {
	//? }
		return false; // 不阻挡移动、不与其他实体碰撞
	}

	@Override
	public boolean isPickable() {
		//【关键】准星拾取闸门，按“本地玩家姿态/手部状态”动态判定（纯客户端、每个玩家各自评估、零网络包）：
		// - 潜行 → false：与原版“潜行穿实体操作后方块”的直觉一致，脚印对射线完全透明；
		// - 主手持物 → false：可照常挖/放/攻击其后的方块与实体，左键右键都不再被脚印接管
		//   （实测旧方案潜行穿不透，根因在拾取阶段，故从源头直接不选中，不再依赖 interact 的潜行 PASS）；
		// - 主手为空 → true：右键点击脚印即依链寻踪（副手是否持物不影响，放宽自“双手皆空”）。
		// isPickable 仅在客户端准星射线（Minecraft#pick → Level#clip 的 EntitySelector.CAN_BE_PICKED 过滤）中被查询，
		// 因此返回随本地玩家状态变化的值是安全且语义正确的：谁能选中互不影响。
		// 服务端没有“本地玩家姿态/手持”这一上下文，保守返回 true 以保留既有实体交互/选中逻辑不受影响。
		if (!this.level().isClientSide()) return true;
		Player player = Minecraft.getInstance().player;
		if (player == null) return true;
		if (player.isCrouching()) return false;
		return player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty();
	}

	@Override
	public boolean isNoGravity() {
		return true;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distSqr) {
		return distSqr < RENDER_DISTANCE_BLOCKS * RENDER_DISTANCE_BLOCKS; // 64 格之外不渲染
	}
}
