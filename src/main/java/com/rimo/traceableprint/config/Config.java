package com.rimo.traceableprint.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.rimo.traceableprint.Common;
import com.rimo.traceableprint.PlatformUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.util.Mth;

/**
 * 模组可调参数（纯 Java，零 loader 依赖，双端共用）。
 *
 * 由 Common.CONFIG 暴露单例，其它模块经 Common.CONFIG.getXxx() 读取。
 * 读写经 Gson 序列化到写死路径 {@code Platform.PLATFORM.getConfigFolder()/traceableprint.json}：{@link #load()} 于构造单例时调用，
 * {@link #save()} 供配置变更后落盘。集合字段非 final，便于 Gson 直接反序列化填充。
 *
 * <p><b>逐生物覆写表的主字段是 Map</b>（{@code sideOffsetMap / forwardOffsetMap / sizeMap / mobIntervalMap / blockHeightMap / textureMap}），
 * 而不是历史上的 {@code List<String>} 字符串条目表：单一数据源、无缓存失效、查询天然 O(1)。
 * ConfigScreen 一侧仍按 {@code List<String>}（"id,value" 形态）读写：{@code setXxxList}/{@code getXxxList}
 * 保留原签名，内部经 {@link #parseFloatMap} / {@link #parseTextureMap} 与 {@link #formatFloatMap} / {@link #formatTextureMap} 转手。
 *
 * <p><b>不做老配置（{@code "sizeList": ["id,val",...]}）的兼容迁移</b>：模组尚未大规模应用，直接以 Map 形态为准。
 * 老 json 里的 List 字段属于未知键，Gson 读入时忽略、写回时不再出现，对应覆写表回到默认值——
 * 此事作为配置系统的破坏性变更写进更新日志，不给玩家留一份“看着像迁移成功其实全丢了”的错觉。
 */
public class Config {
	// - - - - 默认值 - - - -
	public static final long DEFAULT_FOOTPRINT_LIFETIME_TICKS = 1200L; // 脚印存活时长（tick，1200 = 60 秒），超时自毁
	// 雨天额外老化倍率：露天淋雨的脚印存活时长缩到「基准寿命 × 该值」（可配 0.1~1.0，1.0 = 雨水不影响寿命）。
	// 只作用于头顶能被雨打中（isRainingAt 为真）的脚印，屋檐/屋内不受影响。
	public static final float DEFAULT_RAIN_AGE_MULTIPLIER = 0.75F;
	public static final double DEFAULT_MIN_SPAWN_DISTANCE = 5.0;        // 与上一脚印的最小间距（方块），过近则跳过生成
	public static final int DEFAULT_SPAWN_INTERVAL_TICKS = 40;         // 移动检测/生成尝试的固定间隔（tick）
	public static final int DEFAULT_HIGHLIGHT_TICKS = 200;             // 单次点击的高亮时长（tick，10 秒）
	public static final float DEFAULT_FOOTPRINT_Y_OFFSET = 0.0F;            // 脚印抬高量（避免与地面 z-fight）
	// 注：默认左右/前后偏移幅度不再开放给玩家，已硬编码进 LivingEntityMixin（逐生物覆写表命中时优先用表值，二者最终都乘综合缩放倍率）。
	public static final float DEFAULT_FOOTPRINT_TEXTURE_SIZE = 0.3125F;  // 脚印贴图默认尺寸（方块，正方形边长），5/16 = 匹配原版像素大小；逐生物缩放表在此基础上再乘
	public static final float DEFAULT_HARDNESS_GATE = 0.7F;            // 硬度门槛：|defaultDestroyTime| < gate 才可生成
	public static final boolean DEFAULT_NOTIFY_TRACED = true;          // 被追踪提示开关：服务端在有人追到链尾时给父玩家发 action bar 提示
	public static final boolean DEFAULT_SHOW_DIRECTION_PARTICLES = true; // 方向指示粒子开关：被点击的脚印每秒向下一目标飘一颗紫色小粒子（纯客户端视觉）
	public static final boolean DEFAULT_PRINTS_FOR_INVISIBLE = true;   // 隐形实体（隐身效果 / invisible 标志）是否仍留脚印
	public static final boolean DEFAULT_ENTITY_LIST_INVERTED = false;  // 生物名单反转：关=名单作黑名单，开=名单作白名单
	public static final WorkMode DEFAULT_ENABLE_MOD = WorkMode.ALL;       // 模组总开关默认档：所有生物（仍受方块过滤/生物名单约束）

	private long footprintLifetimeTicks = DEFAULT_FOOTPRINT_LIFETIME_TICKS;
	private float rainAgeMultiplier = DEFAULT_RAIN_AGE_MULTIPLIER;
	private double minSpawnDistance = DEFAULT_MIN_SPAWN_DISTANCE;
	private int spawnIntervalTicks = DEFAULT_SPAWN_INTERVAL_TICKS;
	private int highlightTicks = DEFAULT_HIGHLIGHT_TICKS;
	private float footprintYOffset = DEFAULT_FOOTPRINT_Y_OFFSET;
	private float footprintTextureSize = DEFAULT_FOOTPRINT_TEXTURE_SIZE;
	private float hardnessGate = DEFAULT_HARDNESS_GATE;
	private boolean notifyTraced = DEFAULT_NOTIFY_TRACED;
	private boolean showDirectionParticles = DEFAULT_SHOW_DIRECTION_PARTICLES;
	private boolean printsForInvisible = DEFAULT_PRINTS_FOR_INVISIBLE;
	private boolean entityListInverted = DEFAULT_ENTITY_LIST_INVERTED;
	// 模组总开关（三档）：所有生物 / 仅玩家 / 禁用
	private WorkMode enableMod = DEFAULT_ENABLE_MOD;

	// 生成白名单（方块ID "namespace:path"，或 "#namespace:tag" 标签）：命中即跳过硬度判定直接放行，优先级最高
	private Set<String> applyBlocks = new HashSet<>();

	// 生物名单（实体类型ID "namespace:path"，或 "#namespace:tag" 标签）：默认作黑名单，entityListInverted 开启后反转为白名单
	private Set<String> entityList = new HashSet<>();

	// - - - 逐生物偏移初始值（复刻参考工程 FootprintParticle）- - -
	// 常量保留 List<String> 形态：一是可读性（一行一 id）；二是 ConfigScreen 的「重置」按钮 setDefaultValue 需要 List。
	// horseLikeMobs→前后（前进方向）偏移：无 float 的条目取参考中“命中但无幅度”的默认 0.75，已带 float 的保留原值。
	public static final List<String> DEF_FORWARD_OFFSET_LIKE = Arrays.asList(
			"minecraft:horse,0.75",
			"minecraft:donkey,0.75",
			"minecraft:mule,0.75",
			"minecraft:zombie_horse,0.75",
			"minecraft:skeleton_horse,0.75",
			"minecraft:camel,0.75",
			"minecraft:sniffer,0.8",
			"minecraft:ravager,0.5",
			"minecraft:creeper,0.3"
	);
	// spiderLikeMobs→左右（垂直前进方向）偏移：无 float 的条目取参考中默认 0.9，已带 float 的保留原值。
	public static final List<String> DEF_SIDE_OFFSET_LIKE = Arrays.asList(
			"minecraft:spider,0.9",
			"minecraft:cave_spider,0.9",
			"minecraft:camel,0.3",
			"minecraft:sniffer,0.3",
			"minecraft:iron_golem,0.3",
			"minecraft:ravager,0.3"
	);
	// sizePerMob→脚印贴图缩放倍率（复刻参考工程 DEF_SIZE）：命中条目直接给 float，另叠加幼体 0.66 与实体自身 getScale()。
	// 注：Map 主字段化后每个 id 只保留一个 float（同 id 后写覆盖先写），不再像旧 List 那样支持"重复写同 id 连乘"——
	// 需要连乘效果请直接把最终倍率写到一个条目里，语义更直观。
	// 注：数值一律得写成带小数点的形式（2.0 而不是 2）——配置界面“重置”按钮按当前值与默认值逐字比较，
	// 而当前值是 Map 经 Float.toString 展回的（必定带 .0），写 2 会让该项重置按钮常年点亮。
	public static final List<String> DEF_SIZE_PER_MOB = Arrays.asList(
			"minecraft:chicken,0.6",
			"minecraft:pig,0.8",
			"minecraft:cat,0.5",
			"minecraft:ocelot,0.5",
			"minecraft:wolf,0.6",
			"minecraft:sniffer,1.6",
			"minecraft:enderman,0.6",
			"minecraft:slime,2.0",
			"minecraft:magma_cube,2.0",
			"minecraft:creeper,0.8",
			"minecraft:iron_golem,1.2",
			"minecraft:ravager,2.0",
			"minecraft:armadillo,0.7"
	);
	// blockHeight→脚印在特定方块上生成时的额外 Y 抬升（复刻参考工程 DEF_BLOCKHEIGHT）：雪层/灵魂沙/泥等视觉高度与碰撞箱不符、
	// 实体踩上去会下沉，脚印需相应抬高以免被非完整方块遮挡。Map 主字段化后 key 支持 "namespace:path" 与 "#namespace:tag" 两种写法，
	// 查询按「精确 id 优先，标签兜底」短路（与 applyBlocks/isListedEntity 同一套优先级）。
	public static final List<String> DEF_BLOCK_HEIGHT = Arrays.asList(
			"minecraft:snow,0.125",
			"minecraft:soul_sand,0.125",
			"minecraft:mud,0.125"
	);
	// mobIntervalList→每生物生成间隔倍率：命中条目同时乘在 spawnIntervalTicks（时间）与 minSpawnDistance（距离）上，
	// 小于 1 = 更频繁、更密的脚印，大于 1 = 更稀疏；未列出的生物按 1.0 处理（保持全局节奏不变）。
	// 默认值按“蹄足/爪足细碎 → 频，大步重型 → 稀”给：蜘蛛类八足碎步取 0.5，苦力怕略快于默认 0.8，
	// 骆驼与铁傀儡腿长步慢取 2.0，体型最大、动作最缓的嗅探兽取 3.0。
	// 数值一律写成带小数点的形式（2.0 而不是 2）：同 DEF_SIZE_PER_MOB 的教训——配置界面“重置”按钮按当前值与默认值逐字比较，
	// 而当前值是 Map 经 Float.toString 展回的（必定带 .0）。
	public static final List<String> DEF_MOB_INTERVAL = Arrays.asList(
			"minecraft:spider,0.5",
			"minecraft:cave_spider,0.5",
			"minecraft:camel,2.0",
			"minecraft:sniffer,3.0",
			"minecraft:iron_golem,2.0",
			"minecraft:creeper,0.8"
	);
	// textureList→格式提示条目（不删、也不当配置读）：本模组的 json 配置没有注释能力，留一条样例让直接改文件的玩家
	// 一眼看懂格式；它永不命中（mod_id:mob_id 不是任何已注册实体），因此不产生任何效果。
	// 退一步讲，即使真有模组注册了这个 id，候选名也找不到对应贴图，客户端存在性校验会退回默认贴图，不会崩、也不会粉黑块。
	public static final List<String> DEF_TEXTURE_LIST = Arrays.asList(
			"mod_id:mob_id,textureName1,textureName2"
	);

	// - - - - - 逐生物覆写表：Map 主字段（Gson 直接序列化到 json，无索引、无失效） - - - - -
	// 一律 LinkedHashMap：保留 JSON 里的书写顺序，也保留默认常量的顺序，写回磁盘时 diff 稳定；查询仍是 O(1)。
	// 声明顺序在 DEF_* 常量之后，静态初始化时能拿到已就绪的默认常量。

	/** entityId -> 左右偏移幅度（可正可负，调用方取绝对值再随机符号）；float 非法的条目在解析时就被丢弃 */
	private Map<String, Float> sideOffsetMap = parseFloatMap(DEF_SIDE_OFFSET_LIKE);
	/** entityId -> 前后偏移幅度；语义同 {@link #sideOffsetMap} */
	private Map<String, Float> forwardOffsetMap = parseFloatMap(DEF_FORWARD_OFFSET_LIKE);
	/** entityId -> 贴图缩放倍率（同 id 后写覆盖先写，不再连乘）；幼体 0.66 与 getScale() 由调用方另乘 */
	private Map<String, Float> sizeMap = parseFloatMap(DEF_SIZE_PER_MOB);
	/** entityId -> 生成间隔倍率；同时乘在生成间隔与最小间距上，非正数/NaN 在查询侧视作未配置（见 {@link #resolveIntervalMultiplier}） */
	private Map<String, Float> mobIntervalMap = parseFloatMap(DEF_MOB_INTERVAL);
	/** 落脚方块 -> 额外 Y 抬升：key 可以是精确 id "namespace:path" 或标签 "#namespace:tag"；查询走「id 优先、标签兜底」 */
	private Map<String, Float> blockHeightMap = parseFloatMap(DEF_BLOCK_HEIGHT);
	/** entityId -> 候选贴图名列表；一个 id 对应一项，多个候选用逗号并置（同一 id 写多项时后写覆盖先写） */
	private Map<String, List<String>> textureMap = parseTextureMap(DEF_TEXTURE_LIST);

	// - - - - - 标量项 getter/setter - - - - -

	public long getFootprintLifetimeTicks() {
		return footprintLifetimeTicks;
	}
	public void setFootprintLifetimeTicks(long ticks) {
		this.footprintLifetimeTicks = Math.max(0, ticks);
	}

	/** 雨天额外老化倍率：读/写都钳到 [0.1, 1.0]（NaN 回默认 0.75），兼顾手改 json 越界与 Gson 绕过 setter 加载。 */
	public float getRainAgeMultiplier() {
		return Float.isNaN(rainAgeMultiplier) ? DEFAULT_RAIN_AGE_MULTIPLIER : Mth.clamp(rainAgeMultiplier, 0.1F, 1.0F);
	}
	public void setRainAgeMultiplier(float multiplier) {
		this.rainAgeMultiplier = Float.isNaN(multiplier) ? DEFAULT_RAIN_AGE_MULTIPLIER : Mth.clamp(multiplier, 0.1F, 1.0F);
	}

	public double getMinSpawnDistance() {
		return minSpawnDistance;
	}
	public void setMinSpawnDistance(double distance) {
		this.minSpawnDistance = Math.max(0, distance);
	}

	public int getSpawnIntervalTicks() {
		return spawnIntervalTicks;
	}
	public void setSpawnIntervalTicks(int ticks) {
		this.spawnIntervalTicks = Math.max(1, ticks);
	}

	public int getHighlightTicks() {
		return highlightTicks;
	}
	public void setHighlightTicks(int ticks) {
		this.highlightTicks = Math.max(1, ticks);
	}

	/** 脚印贴图全局抬高量，单位为方块；纯渲染偏移，仅叠加到渲染器的 renderY，不改变实体真实坐标 / 判定盒 / 存续检测 */
	public float getFootprintYOffset() {
		return footprintYOffset;
	}
	public void setFootprintYOffset(float offset) {
		this.footprintYOffset = offset;
	}

	/** 脚印贴图默认尺寸（方块，正方形边长）：默认 5/16 匹配原版像素大小；逐生物缩放表（sizeMap）与实体 getScale() 在此基准上再乘 */
	public float getFootprintTextureSize() {
		return footprintTextureSize;
	}
	public void setFootprintTextureSize(float size) {
		this.footprintTextureSize = Math.max(0, size);
	}

	public float getHardnessGate() {
		return hardnessGate;
	}
	public void setHardnessGate(float gate) {
		this.hardnessGate = Math.max(0, gate);
	}

	/** 被追踪提示开关（多人服务器：有人通过脚印追到你时，向被追踪者发 action bar 提示） */
	public boolean isNotifyTraced() {
		return notifyTraced;
	}
	public void setNotifyTraced(boolean notifyTraced) {
		this.notifyTraced = notifyTraced;
	}

	/**
	 * 方向指示粒子开关：开启时，被右键的脚印在高亮时长内每秒向“下一个脚印/父实体”飘出一颗
	 * END_ROD 紫色粒子（末影之眼同款，沿速度方向拉成短拖尾），指明下一目标在哪。
	 * 发射者是被点击的脚印（而非被点亮的目标）；纯客户端本地视觉，不经任何网络。
	 */
	public boolean isShowDirectionParticles() {
		return showDirectionParticles;
	}
	public void setShowDirectionParticles(boolean showDirectionParticles) {
		this.showDirectionParticles = showDirectionParticles;
	}

	/**
	 * 隐形实体是否仍留脚印：开（默认）= 隐身≠无迹可寻，隐形玩家/生物照常踩出脚印；
	 * 关 = 处于隐身效果或自带 invisible 标志的实体不留脚印（潜行与否不受本开关管制，一律不留）。
	 */
	public boolean isPrintsForInvisible() {
		return printsForInvisible;
	}
	public void setPrintsForInvisible(boolean printsForInvisible) {
		this.printsForInvisible = printsForInvisible;
	}

	/**
	 * 生物名单反转：
	 * 关（默认）= entityList 是黑名单，名单内实体不留脚印，其余照常；
	 * 开 = entityList 是白名单，只为名单内实体留脚印，其余一律不留。
	 * 注意：反转且名单为空时按字面语义理解 = 谁都不留脚印（不是“忽略过滤”）。
	 */
	public boolean isEntityListInverted() {
		return entityListInverted;
	}
	public void setEntityListInverted(boolean entityListInverted) {
		this.entityListInverted = entityListInverted;
	}

	/** 模组总开关：见 {@link WorkMode}。{@code DISABLED} 时服务端不生成任何脚印；{@code PLAYER_ONLY} 仅玩家生成。 */
	public WorkMode getEnableMod() {
		return enableMod;
	}
	public void setEnableMod(WorkMode enableMod) {
		this.enableMod = enableMod == null ? DEFAULT_ENABLE_MOD : enableMod;
	}

	/**
	 * 模组总开关三档：{@code ALL} 所有生物都生成脚印（仍受方块过滤与生物名单约束）、
	 * {@code PLAYER_ONLY} 仅玩家生成、{@code DISABLED} 完全禁用。
	 * 只存翻译键、不引 MC 的 Component，以保持 Config 零 loader/MC 依赖；翻译键在配置界面（fabric 侧）转成 Component。
	 */
	public enum WorkMode {
		DISABLED("text.traceableprint.work_mode.disabled"),
		PLAYER_ONLY("text.traceableprint.work_mode.player_only"),
		ALL("text.traceableprint.work_mode.all");

		private final String translationKey;
		WorkMode(String translationKey) {
			this.translationKey = translationKey;
		}
		public String getTranslationKey() {
			return translationKey;
		}
	}

	// - - - - - Set 主字段：applyBlocks / entityList（ConfigScreen 走 List<String> 视图） - - - - -

	public void setApplyBlocks(List<String> blocks) {
		applyBlocks.clear();
		applyBlocks.addAll(blocks);
	}
	public List<String> getApplyBlocks() {
		return applyBlocks.stream().toList();
	}
	/**
	 * 方块白名单成员判定（直接内部 HashSet O(1)）：入参可以是精确 id "namespace:path"，
	 * 也可以是标签字面串 "#namespace:tag"——两种写法在同一集合里混存，无需区分。
	 * mixin 侧标签查询逐次传入 "#" + tag.location() 即可同样走 O(1)。
	 */
	public boolean isInApplyBlocks(String blockId) {
		return applyBlocks.contains(blockId);
	}
	/** 白名单是否为空：与 {@link #isInApplyBlocks} 拆开提供，方便调用方在取 Holder 前先廉价短路。 */
	public boolean hasAnyApplyBlockEntries() {
		return !applyBlocks.isEmpty();
	}

	public void setEntityList(List<String> entities) {
		entityList.clear();
		entityList.addAll(entities);
	}
	/** 生物名单（实体类型ID 或 "#tag"），黑名单还是白名单由 isEntityListInverted() 决定 */
	public List<String> getEntityList() {
		return entityList.stream().toList();
	}
	/**
	 * 生物名单成员判定（直接内部 HashSet O(1)）：入参可以是精确 id，也可以是 "#namespace:tag" 标签字面串，
	 * 两种写法混存在同一集合里。语义同 {@link #isInApplyBlocks(String)}。
	 */
	public boolean isInEntityList(String entityId) {
		return entityList.contains(entityId);
	}
	/** 生物名单是否为空：供调用方在取注册表 Holder 前先廉价短路。 */
	public boolean hasAnyEntityEntries() {
		return !entityList.isEmpty();
	}

	// - - - - - Map 主字段的 ConfigScreen 视图对（List<String> "id,val" 形态 <-> 内部 Map） - - - - -

	/**
	 * 逐生物左右偏移表（"modid:mobid,float"）：setter 把 List 解析进 {@link #sideOffsetMap}，getter 把 Map 展回 List 视图。
	 * 同一 id 重复出现时按「后写覆盖」处理（Map put 语义）；float 非法的条目直接丢弃。
	 */
	public void setSideOffsetList(List<String> entries) {
		this.sideOffsetMap = parseFloatMap(entries);
	}
	public List<String> getSideOffsetList() {
		return formatFloatMap(sideOffsetMap);
	}

	/** 逐生物前后偏移表；语义同 {@link #setSideOffsetList(List)} / {@link #getSideOffsetList()}。 */
	public void setForwardOffsetList(List<String> entries) {
		this.forwardOffsetMap = parseFloatMap(entries);
	}
	public List<String> getForwardOffsetList() {
		return formatFloatMap(forwardOffsetMap);
	}

	/** 逐生物贴图缩放表；语义同 {@link #setSideOffsetList(List)}（同 id 覆盖，不再连乘）。 */
	public void setSizeList(List<String> entries) {
		this.sizeMap = parseFloatMap(entries);
	}
	public List<String> getSizeList() {
		return formatFloatMap(sizeMap);
	}

	/**
	 * 每生物生成间隔倍率表（"modid:mobid,float"）；解析/覆盖语义同 {@link #setSideOffsetList(List)}。
	 * 倍率同时作用于「时间」（spawnIntervalTicks）与「距离」（minSpawnDistance），
	 * 只乘其一会出现“间隔改了但闸门没改”的错配：例如倍率 0.5 下脚印每半个窗口就生成一次，
	 * 却被原样的 5 格间距闸门接连拦掉，实际频率毫无变化。
	 */
	public void setMobIntervalList(List<String> entries) {
		this.mobIntervalMap = parseFloatMap(entries);
	}
	public List<String> getMobIntervalList() {
		return formatFloatMap(mobIntervalMap);
	}

	/**
	 * 方块额外抬高表（"blockid,float" 或 "#tagid,float"）：Map 主字段化后 key 直接支持两种写法混存，
	 * 查询规则「精确 id 优先、标签兜底」（见 LivingEntityMixin#traceableprint$blockHeightOffset）。
	 */
	public void setBlockHeightList(List<String> entries) {
		this.blockHeightMap = parseFloatMap(entries);
	}
	public List<String> getBlockHeightList() {
		return formatFloatMap(blockHeightMap);
	}
	/**
	 * 方块抬高值查询：key 是精确 id "namespace:path" 或标签 "#namespace:tag"，两者混存在同一 Map 里；
	 * 直接 O(1) 命中，未命中返回 null。调用方（LivingEntityMixin）先查 id、miss 再逐标签查。
	 */
	public Float blockHeightValue(String key) {
		return key == null ? null : blockHeightMap.get(key);
	}
	/** 抬高表是否为空：供调用方在取 Holder 前先廉价短路。 */
	public boolean hasAnyBlockHeights() {
		return !blockHeightMap.isEmpty();
	}

	/**
	 * 逐生物贴图覆写表（"modid:mobid,texture1,texture2,..."）：Map 主字段是 {@code entityId -> List<String>}，
	 * ConfigScreen 视图按「一行一 id、候选逗号并置」展平。
	 *
	 * <p>【接入配置界面时，本项 tooltip 必须包含以下要点，不能只写“自定义脚印贴图”】
	 * 候选贴图是「服务端」从它自己那份 textureMap 里抽的，抽完把名字同步下来，所以多人游戏下服务端优先：
	 * 与客户端不一致时，客户端这份列表完全不参与选取（既盖不了服务端选定的图，也补不上服务端没配的生物），
	 * 客户端唯一保留的话语权是资源存在性——同步来的贴图名在本机资源包里找不到时，退回默认 footprint.png。
	 * 单人与自己的内嵌服读的是同一个 json，不存在差异（别把上面这句写成“客户端配置无用”以免误导单人玩家）。
	 * 参考文案（英文同步写给 en_us）：
	 * 「注意：具体用哪张贴图由服务端的这份配置决定。多人游戏中若服务端配置与本机不同，以服务端为准，
	 * 本机列表不参与选取（仅当同步来的贴图在本机资源包里找不到时退回默认 footprint.png）。」
	 */
	public void setTextureList(List<String> entries) {
		this.textureMap = parseTextureMap(entries);
	}
	public List<String> getTextureList() {
		return formatTextureMap(textureMap);
	}

	// - - - - - 热路径查询：直接走 Map（O(1)），无索引/无失效 - - - - -

	/**
	 * 收集实体注册名（namespace:path）在贴图覆写表中的全部候选贴图名：{@code textureMap.get(entityId)} 一次哈希即得。
	 * 未命中返回空列表，由调用方回退默认贴图。只按 id 精确匹配，不匹配标签。
	 * 本方法只在生成侧（服务端）被查一次；客户端拿到名字后不再二查本表（服务端优先的利弊见 getTextureList 说明）。
	 */
	public List<String> resolveTextureCandidates(String entityId) {
		if (entityId == null) return List.of();
		List<String> candidates = textureMap.get(entityId);
		return candidates == null ? List.of() : List.copyOf(candidates);
	}

	/**
	 * 按实体注册名（namespace:path）取脚印贴图缩放倍率：{@code sizeMap.get(entityId)}，未命中返回 1.0F。
	 * 幼体 0.66 与实体 getScale() 由调用方另乘（见 LivingEntityMixin）。
	 */
	public float resolveSizeMultiplier(String entityId) {
		if (entityId == null) return 1.0F;
		Float v = sizeMap.get(entityId);
		return v == null ? 1.0F : v;
	}

	/**
	 * 按实体注册名（namespace:path）取生成间隔倍率：{@code mobIntervalMap.get(entityId)}，未命中返回 1.0F。
	 * 只按 id 精确匹配，不匹配标签（与 sizeMap / 偏移表同一套规则）。
	 *【为何要挡住非正数】0 与负数会让冷却恒为 1 tick、间距闸门恒为 0，玩家一个 tick 踩出一地脚印；
	 * NaN 更阴 —— {@code ddx*ddx + ddz*ddz < NaN} 恒为 false，最小间距会整体失效而变成“每 tick 都生成”。
	 * 故这里统一用 {@code !(v > 0)} 兜底（NaN 与 0、负数一并落回 1.0），而不是只在 setter 里校验一次。
	 */
	public float resolveIntervalMultiplier(String entityId) {
		if (entityId == null) return 1.0F;
		Float v = mobIntervalMap.get(entityId);
		return v == null || !(v > 0.0F) ? 1.0F : v;
	}

	/**
	 * 在左右偏移表中按实体注册名（namespace:path）查找自定义幅度：{@code sideOffsetMap.get(entityId)}；
	 * 未命中或 float 非法返回 null（后者在 parse 阶段已丢弃，不会出现在 Map 里）。
	 * 只按 id 精确匹配，不匹配标签。
	 */
	public Float findSideOffset(String entityId) {
		if (entityId == null) return null;
		return sideOffsetMap.get(entityId);
	}

	/** 在前后偏移表中按实体注册名（namespace:path）查找自定义幅度；语义同 {@link #findSideOffset(String)}。 */
	public Float findForwardOffset(String entityId) {
		if (entityId == null) return null;
		return forwardOffsetMap.get(entityId);
	}

	// - - - - - List<String> "id,value" <-> Map 转换（ConfigScreen 视图与默认常量解析共用） - - - - -

	/**
	 * 解析 "id,float" 形态的条目列表到 LinkedHashMap（保留插入序，diff 稳定）。
	 * 语义：同 id 后写覆盖先写；float 解析失败或条目不含逗号的都丢弃；空 key 丢弃。
	 */
	static Map<String, Float> parseFloatMap(List<String> entries) {
		Map<String, Float> m = new LinkedHashMap<>();
		if (entries == null) return m;
		for (String entry : entries) {
			if (entry == null) continue;
			int comma = entry.indexOf(',');
			if (comma < 0) continue;
			String id = entry.substring(0, comma).trim();
			if (id.isEmpty()) continue;
			try {
				m.put(id, Float.parseFloat(entry.substring(comma + 1).trim()));
			} catch (NumberFormatException e) {
				// 非法 float：丢弃（旧版会视作 null/0，新语义统一为「等同于未配置」）
			}
		}
		return m;
	}

	/**
	 * 解析 "id,name1,name2,..." 形态的条目列表到 LinkedHashMap。
	 * 语义：同 id 后写覆盖先写（一行一 id，多个候选并置在同一行）；
	 * 首个逗号之后的各段去空白、跳过空段；无候选（全空段）时留空列表，让查询侧自然回退默认贴图。
	 */
	static Map<String, List<String>> parseTextureMap(List<String> entries) {
		Map<String, List<String>> m = new LinkedHashMap<>();
		if (entries == null) return m;
		for (String entry : entries) {
			if (entry == null) continue;
			int comma = entry.indexOf(',');
			if (comma < 0) continue;
			String id = entry.substring(0, comma).trim();
			if (id.isEmpty()) continue;
			List<String> list = new ArrayList<>();
			for (String name : entry.substring(comma + 1).split(",")) {
				String t = name.trim();
				if (!t.isEmpty()) list.add(t);
			}
			m.put(id, list);
		}
		return m;
	}

	/** 把 Map 展成 "id,value" List 视图给 ConfigScreen；null 值跳过（parseFloatMap 已保证不会有）。 */
	static List<String> formatFloatMap(Map<String, Float> m) {
		List<String> out = new ArrayList<>();
		if (m == null) return out;
		for (Map.Entry<String, Float> e : m.entrySet()) {
			if (e.getValue() == null) continue;
			out.add(e.getKey() + "," + e.getValue());
		}
		return out;
	}

	/** 把 {@code id -> [候选名...]} Map 展成 "id,name1,name2" List 视图；空列表条目输出成 "id," 以便玩家看得见这行存在。 */
	static List<String> formatTextureMap(Map<String, List<String>> m) {
		List<String> out = new ArrayList<>();
		if (m == null) return out;
		for (Map.Entry<String, List<String>> e : m.entrySet()) {
			List<String> v = e.getValue();
			out.add(v == null || v.isEmpty() ? e.getKey() + "," : e.getKey() + "," + String.join(",", v));
		}
		return out;
	}

	// - - - - - ConfigScreen 逐行输入校验（本不允重复键，仅对玩家自由输入做兜底） - - - - -
	// 下列方法只回答“合法/非法 + 错因”，不改动数据；非法时 {@link #validateFloatRow} /
	// {@link #validateTextureRow} 返回一个错误码（供上层拼翻译键），{@code null} 表示合法。

	/** 资源 id（可带 {@code #} 前缀当标签）：{@code [namespace]:[path]}，字符集对齐原版 {@code ResourceLocation}。 */
	private static final java.util.regex.Pattern ID_OR_TAG_PATTERN =
			java.util.regex.Pattern.compile("#?[a-z0-9._-]+:[a-z0-9._/\\-]+");

	/** 是否为合法的 {@code namespace:path} 或 {@code #namespace:tag}。 */
	public static boolean isIdOrTag(String s) {
		return s != null && ID_OR_TAG_PATTERN.matcher(s).matches();
	}

	/**
	 * 从 {@code "id,..."} 行里抽取 id 段（去前导空白），供重复检测；无逗号或 id 为空时返回 {@code null}。
	 */
	public static String extractRowId(String row) {
		if (row == null) return null;
		int comma = row.indexOf(',');
		if (comma < 0) return null;
		String id = row.substring(0, comma).trim();
		return id.isEmpty() ? null : id;
	}

	/**
	 * 校验 {@code "id,float"} 形态单行。合法返回 {@code null}；否则返回错误码：
	 * {@code empty_row} / {@code missing_comma} / {@code empty_id} / {@code invalid_id} / {@code empty_value} / {@code invalid_float}。
	 */
	public static String validateFloatRow(String row) {
		if (row == null || row.isBlank()) return "empty_row";
		int comma = row.indexOf(',');
		if (comma < 0) return "missing_comma";
		String id = row.substring(0, comma).trim();
		if (id.isEmpty()) return "empty_id";
		if (!isIdOrTag(id)) return "invalid_id";
		String val = row.substring(comma + 1).trim();
		if (val.isEmpty()) return "empty_value";
		try {
			Float.parseFloat(val);
		} catch (NumberFormatException e) {
			return "invalid_float";
		}
		return null;
	}

	/** 每生物生成间隔倍率行专用校验：{@link #validateFloatRow} 的全部错误码之上再加 {@code non_positive}。 */
	public static String validateIntervalRow(String row) {
		String code = validateFloatRow(row);
		if (code != null) return code;
		float v = Float.parseFloat(row.substring(row.indexOf(',') + 1).trim());
		// !(v > 0) 兼拦 0 / 负数 / NaN：这些值在查询侧会默默回退 1.0（“写了却没用”），故直接在输入行报错。
		// 不拦 Infinity：它正好对应“永不生成新脚印”的极端稀疏语义，与玩家写大数的意图一致。
		return v > 0.0F ? null : "non_positive";
	}

	/**
	 * 校验 {@code "id,candidate1,candidate2,..."} 形态单行。合法返回 {@code null}；否则错误码：
	 * {@code empty_row} / {@code missing_comma} / {@code empty_id} / {@code invalid_id} / {@code empty_candidates}。
	 */
	public static String validateTextureRow(String row) {
		if (row == null || row.isBlank()) return "empty_row";
		int comma = row.indexOf(',');
		if (comma < 0) return "missing_comma";
		String id = row.substring(0, comma).trim();
		if (id.isEmpty()) return "empty_id";
		if (!isIdOrTag(id)) return "invalid_id";
		for (String name : row.substring(comma + 1).split(",")) {
			if (!name.trim().isEmpty()) return null;
		}
		return "empty_candidates";
	}

	/* - - - - - IO（Gson 序列化，参考 SuperFancyClouds SharedConfig，仅保留 load/save）- - - - - */

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	// 配置文件路径在类初始化时写死：{@code config/traceableprint.json}。
	private static final Path CONFIG_PATH = PlatformUtil.PLATFORM.getConfigFolder().resolve(Common.MOD_ID + ".json");

	/**
	 * 从 {@code CONFIG_PATH} 载入并回填到当前实例，返回 {@code this} 以便链式初始化。
	 * 文件不存在时写入一份默认配置；读取/解析失败则保留当前值（默认）不影响运行。
	 * Gson 会新建一个 Config 反序列化（未出现的键保持默认值，天然向后兼容新增字段；未知键直接忽略），再复制到本实例。
	 */
	public Config load() {
		if (Files.exists(CONFIG_PATH)) {
			try (BufferedReader reader = Files.newBufferedReader(CONFIG_PATH)) {
				Config loaded = GSON.fromJson(reader, Config.class);
				if (loaded != null) {
					this.copyFrom(loaded);
				}
			} catch (IOException | JsonParseException e) {
				Common.LOGGER.error("Failed to read config file: {}, using current/default config", CONFIG_PATH, e);
			}
		} else {
			save();
		}
		return this;
	}

	/** 将当前实例序列化写入 {@code CONFIG_PATH}（目录缺失自动创建）。 */
	public void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			Common.LOGGER.error("Failed to write config file: {}", CONFIG_PATH, e);
		}
	}

	/**
	 * 将当前实例序列化为 JSON 字符串（与 {@link #save()} 用同一 GSON，含缩进）。
	 * 供配置上传功能把客户端本地配置打包经网络发往服务端。
	 */
	public String serializeJson() {
		return GSON.toJson(this);
	}

	/**
	 * 解析上传来的 JSON 并回填到当前实例，成功返回 {@code true}。
	 * 解析失败/空串返回 {@code false} 且不改动现有值（落盘由调用方决定）。
	 * 注意：Gson 按 {@code name()} 反序列化 {@link WorkMode}；未出现的键保持默认，天然向后兼容。
	 */
	public boolean applyJson(String json) {
		if (json == null || json.isEmpty()) {
			return false;
		}
		try {
			Config parsed = GSON.fromJson(json, Config.class);
			if (parsed == null) {
				return false;
			}
			this.copyFrom(parsed);
			return true;
		} catch (JsonParseException e) {
			Common.LOGGER.error("Failed to parse uploaded config JSON", e);
			return false;
		}
	}

	/**
	 * 把已反序列化的 {@code src} 各字段值覆写进本实例。
	 * 集合字段（Set / Map）直接引用 src 的对象：src 是 load/applyJson 内部的临时反序列化产物，随后即弃，
	 * 本实例持有其引用不会被外部改动；这也避免了 copy 一遍集合的额外分配。
	 */
	private void copyFrom(Config src) {
		this.footprintLifetimeTicks = src.footprintLifetimeTicks;
		this.rainAgeMultiplier = src.rainAgeMultiplier;
		this.minSpawnDistance = src.minSpawnDistance;
		this.spawnIntervalTicks = src.spawnIntervalTicks;
		this.highlightTicks = src.highlightTicks;
		this.footprintYOffset = src.footprintYOffset;
		this.footprintTextureSize = src.footprintTextureSize;
		this.hardnessGate = src.hardnessGate;
		this.notifyTraced = src.notifyTraced;
		this.showDirectionParticles = src.showDirectionParticles;
		this.printsForInvisible = src.printsForInvisible;
		this.entityListInverted = src.entityListInverted;
		this.enableMod = src.enableMod;
		this.applyBlocks = src.applyBlocks;
		this.entityList = src.entityList;
		this.sideOffsetMap = nullSafe(src.sideOffsetMap, Config::parseFloatMapEmpty);
		this.forwardOffsetMap = nullSafe(src.forwardOffsetMap, Config::parseFloatMapEmpty);
		this.sizeMap = nullSafe(src.sizeMap, Config::parseFloatMapEmpty);
		this.mobIntervalMap = nullSafe(src.mobIntervalMap, Config::parseFloatMapEmpty);
		this.blockHeightMap = nullSafe(src.blockHeightMap, Config::parseFloatMapEmpty);
		this.textureMap = nullSafe(src.textureMap, Config::parseTextureMapEmpty);
	}

	/** Gson 遇到 json 里没这个键时会把字段留 null（UnsafeAllocator 绕过构造器，不跑 field initializer）；null 兜底给空 Map，后续 setter 直接替换引用即可。 */
	private static <T> T nullSafe(T v, java.util.function.Supplier<T> fallback) {
		return v != null ? v : fallback.get();
	}

	private static Map<String, Float> parseFloatMapEmpty() {
		return new LinkedHashMap<>();
	}
	private static Map<String, List<String>> parseTextureMapEmpty() {
		return new LinkedHashMap<>();
	}
}
