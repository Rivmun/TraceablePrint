package com.rimo.traceableprint.config;

import com.rimo.traceableprint.Common;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.StringListBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 基于 ClothConfig 的配置界面
 *
 * <p>约定：所有 setSaveConsumer 直接写入活单例 {@code Common.CONFIG}，ClothConfig 在点击「完成」时统一回调这些消费者，
 * 随后 {@link ConfigBuilder#setSavingRunnable} 触发 {@code CONFIG.save()} 落盘到 config/traceableprint.json。
 * 数值/文本项配 {@code setDefaultValue}，界面右上角「重置」可回到代码里的默认常量（列表项默认值取 Config 的 public DEF_* 常量）。
 *
 * <p>提示文案全部走翻译键（前缀 {@code text.traceableprint.}）；tooltip 只放一两句短说明，
 * 像自定义贴图那种多约束的复杂解释改用内联的文本描述项（startTextDescription）逐条展示，避免 tooltip 过长难以阅读。
 *
 * <p>分类与条目顺序与语言文件（zh_cn / en_us）严格对齐：通用 → 外观 → 过滤 → 覆写 → 自定义贴图。
 */
public class ConfigScreen {
	// Common.CONFIG 活单例的局部别名，缩短下面消费者/初值表达的重复前缀
	private static final Config CONFIG = Common.CONFIG;

	public static Screen create(Screen parent) {
		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.translatable("text.traceableprint.title"))
				.setTransparentBackground(true);
		builder.setSavingRunnable(CONFIG::save);
		ConfigEntryBuilder eb = builder.entryBuilder();

		buildGeneral(builder, eb);
		buildAppearance(builder, eb);
		buildFilter(builder, eb);
		buildPerMob(builder, eb);
		buildTexture(builder, eb);

		return builder.build();
	}

	// - - - 通用 - - -
	private static void buildGeneral(ConfigBuilder builder, ConfigEntryBuilder eb) {
		ConfigCategory cat = builder.getOrCreateCategory(Component.translatable("text.traceableprint.category.general"));

		// 模组总开关放最前：三档（所有生物 / 仅玩家 / 禁用），用下拉选择器展示
		cat.addEntry(eb.startEnumSelector(t("option.enableMod"), Config.WorkMode.class, CONFIG.getEnableMod())
				.setDefaultValue(Config.DEFAULT_ENABLE_MOD)
				.setEnumNameProvider(mode -> Component.translatable(((Config.WorkMode) mode).getTranslationKey()))
				.setSaveConsumer(CONFIG::setEnableMod)
				.build());

		cat.addEntry(eb.startIntSlider(t("option.spawnInterval"), CONFIG.getSpawnIntervalTicks(), 10, 200)
				.setDefaultValue(Config.DEFAULT_SPAWN_INTERVAL_TICKS)
				.setTextGetter(ticks -> Component.nullToEmpty(ticks + "t"))
				.setTooltip(t("option.spawnInterval.@Tooltip"))
				.setSaveConsumer(CONFIG::setSpawnIntervalTicks)
				.build());

		cat.addEntry(eb.startLongSlider(t("option.lifetime"), CONFIG.getFootprintLifetimeTicks(), 600, 12000)
				.setDefaultValue(Config.DEFAULT_FOOTPRINT_LIFETIME_TICKS)
				.setTextGetter(ConfigScreen::seconds)
				.setSaveConsumer(CONFIG::setFootprintLifetimeTicks)
				.build());

		cat.addEntry(eb.startDoubleField(t("option.minDistance"), CONFIG.getMinSpawnDistance())
				.setDefaultValue(Config.DEFAULT_MIN_SPAWN_DISTANCE)
				.setMin(1).setMax(16)
				.setTooltip(t("option.minDistance.@Tooltip"))
				.setSaveConsumer(CONFIG::setMinSpawnDistance)
				.build());

		cat.addEntry(eb.startBooleanToggle(t("option.printsForInvisible"), CONFIG.isPrintsForInvisible())
				.setDefaultValue(Config.DEFAULT_PRINTS_FOR_INVISIBLE)
				.setTooltip(t("option.printsForInvisible.@Tooltip"))
				.setSaveConsumer(CONFIG::setPrintsForInvisible)
				.build());
	}

	// - - - 外观 - - -
	private static void buildAppearance(ConfigBuilder builder, ConfigEntryBuilder eb) {
		ConfigCategory cat = builder.getOrCreateCategory(Component.translatable("text.traceableprint.category.appearance"));

		// 基准尺寸的结果会乘以服务端同步的逐生物倍率，故置于“仅本地”声明之上；header 以下各项才是纯本地表现项
		cat.addEntry(eb.startFloatField(t("option.textureSize"), CONFIG.getFootprintTextureSize())
				.setDefaultValue(Config.DEFAULT_FOOTPRINT_TEXTURE_SIZE)
				.setMin(0.01F).setMax(1.5F)
				.setTooltip(t("option.textureSize.@Tooltip"))
				.setSaveConsumer(CONFIG::setFootprintTextureSize)
				.build());

		cat.addEntry(eb.startTextDescription(t("appearance.header")).build());

		cat.addEntry(eb.startFloatField(t("option.yOffset"), CONFIG.getFootprintYOffset())
				.setDefaultValue(Config.DEFAULT_FOOTPRINT_Y_OFFSET)
				.setMin(-1).setMax(1)
				.setTooltip(t("option.yOffset.@Tooltip"))
				.setSaveConsumer(CONFIG::setFootprintYOffset)
				.build());

		cat.addEntry(eb.startBooleanToggle(t("option.notifyTraced"), CONFIG.isNotifyTraced())
				.setDefaultValue(Config.DEFAULT_NOTIFY_TRACED)
				.setTooltip(t("option.notifyTraced.@Tooltip"))
				.setSaveConsumer(CONFIG::setNotifyTraced)
				.build());

		cat.addEntry(eb.startIntSlider(t("option.highlightTicks"), CONFIG.getHighlightTicks(), 20, 1200)
				.setDefaultValue(Config.DEFAULT_HIGHLIGHT_TICKS)
				.setTextGetter(ConfigScreen::seconds)
				.setSaveConsumer(CONFIG::setHighlightTicks)
				.build());

		// 方向指示粒子与高亮时长同居“交互反馈”语义区，紧跟其后；纯客户端本地生效
		cat.addEntry(eb.startBooleanToggle(t("option.directionParticles"), CONFIG.isShowDirectionParticles())
				.setDefaultValue(Config.DEFAULT_SHOW_DIRECTION_PARTICLES)
				.setTooltip(t("option.directionParticles.@Tooltip"))
				.setSaveConsumer(CONFIG::setShowDirectionParticles)
				.build());
	}

	// - - - 过滤 - - -
	private static void buildFilter(ConfigBuilder builder, ConfigEntryBuilder eb) {
		ConfigCategory cat = builder.getOrCreateCategory(Component.translatable("text.traceableprint.category.filter"));

		cat.addEntry(eb.startFloatField(t("option.hardnessGate"), CONFIG.getHardnessGate())
				.setDefaultValue(Config.DEFAULT_HARDNESS_GATE)
				.setMin(0)
				.setTooltip(t("option.hardnessGate.@Tooltip"))
				.setSaveConsumer(CONFIG::setHardnessGate)
				.build());

		cat.addEntry(eb.startStrList(t("option.applyBlocks"), CONFIG.getApplyBlocks())
				.setDefaultValue(new ArrayList<>())
				.setTooltip(t("option.applyBlocks.@Tooltip"))
				.setSaveConsumer(CONFIG::setApplyBlocks)
				.build());

		// 生物名单：黑名单还是白名单由下面的反转开关决定，两者配在同屏相邻位置便于对照
		cat.addEntry(eb.startBooleanToggle(t("option.entityListInverted"), CONFIG.isEntityListInverted())
				.setDefaultValue(Config.DEFAULT_ENTITY_LIST_INVERTED)
				.setTooltip(t("option.entityListInverted.@Tooltip"))
				.setSaveConsumer(CONFIG::setEntityListInverted)
				.build());

		cat.addEntry(eb.startStrList(t("option.entityList"), CONFIG.getEntityList())
				.setDefaultValue(new ArrayList<>())
				.setTooltip(t("option.entityList.@Tooltip"))
				.setSaveConsumer(CONFIG::setEntityList)
				.build());
	}

	// - - - 逐生物/方块覆写表 - - -
	private static void buildPerMob(ConfigBuilder builder, ConfigEntryBuilder eb) {
		ConfigCategory cat = builder.getOrCreateCategory(Component.translatable("text.traceableprint.category.per_mob"));

		// 该分类整体是「条目字符串」列表，格式统一但字段各异，先在顶部放一段格式说明，比每个列表各写一遍更省版面
		cat.addEntry(eb.startTextDescription(t("per_mob.header")).build());

		// 每生物生成间隔倍率排在本分类首位：它与通用分类的「生成间隔」「最小间距」是同一套判定的乘数，语义上离得最近
		// 校验多一道「必须为正数」（validateIntervalRow）：0/负数在查询侧只是默默回到 1.0，不报出来玩家会以为生效了
		addValidatedList(cat, eb, t("option.mobIntervalList"), CONFIG.getMobIntervalList(),
				new ArrayList<>(Config.DEF_MOB_INTERVAL), t("option.mobIntervalList.@Tooltip"),
				CONFIG::setMobIntervalList, Config::validateIntervalRow);

		addValidatedList(cat, eb, t("option.sizeList"), CONFIG.getSizeList(),
				new ArrayList<>(Config.DEF_SIZE_PER_MOB), t("option.sizeList.@Tooltip"),
				CONFIG::setSizeList, Config::validateFloatRow);

		addValidatedList(cat, eb, t("option.sideOffsetList"), CONFIG.getSideOffsetList(),
				new ArrayList<>(Config.DEF_SIDE_OFFSET_LIKE), t("option.sideOffsetList.@Tooltip"),
				CONFIG::setSideOffsetList, Config::validateFloatRow);

		addValidatedList(cat, eb, t("option.forwardOffsetList"), CONFIG.getForwardOffsetList(),
				new ArrayList<>(Config.DEF_FORWARD_OFFSET_LIKE), t("option.forwardOffsetList.@Tooltip"),
				CONFIG::setForwardOffsetList, Config::validateFloatRow);

		addValidatedList(cat, eb, t("option.blockHeightList"), CONFIG.getBlockHeightList(),
				new ArrayList<>(Config.DEF_BLOCK_HEIGHT), t("option.blockHeightList.@Tooltip"),
				CONFIG::setBlockHeightList, Config::validateFloatRow);
	}

	// - - - 自定义贴图 - - -
	private static void buildTexture(ConfigBuilder builder, ConfigEntryBuilder eb) {
		ConfigCategory cat = builder.getOrCreateCategory(Component.translatable("text.traceableprint.category.texture"));

		addValidatedList(cat, eb, t("option.textureList"), CONFIG.getTextureList(),
				new ArrayList<>(Config.DEF_TEXTURE_LIST), null,
				CONFIG::setTextureList, Config::validateTextureRow);

		// 贴图项约束多，用内联文本描述逐条摊开，而不是塞进一个超长 tooltip
		for (String key : new String[] {
				"texture.desc.format",
				"texture.desc.path",
				"texture.desc.match" }) {
			cat.addEntry(eb.startTextDescription(t(key)).build());
		}
	}

	/**
	 * 构造一个带实时校验的字符串列表项，接 Cloth Config 的两个原生校验钩子：
	 * <ul>
	 *   <li>{@code setCellErrorSupplier(Function<String,…>)} 逐行校验格式（{@code rowValidator}）：非法行就地标红；</li>
	 *   <li>{@code setErrorSupplier(Function<List<String>,…>)} 整表校验：跨行扫描同一 id 是否出现 &gt; 1 次，命中则报 {@code duplicate_id}。</li>
	 * </ul>
	 * 重复键是本模组设计上不允许的（一个 id 只应一条覆写），但输入框对玩家自由，故在此兜底；
	 * 两个钩子都是 Cloth 原生的，无需闭包引用已 build 的 entry。
	 */
	private static void addValidatedList(ConfigCategory cat, ConfigEntryBuilder eb, Component label,
			List<String> current, List<String> defaultValue, Component tooltip,
			Consumer<List<String>> saveConsumer, Function<String, String> rowValidator) {
		StringListBuilder builder = eb.startStrList(label, current)
				.setDefaultValue(defaultValue)
				.setSaveConsumer(saveConsumer)
				.setCellErrorSupplier(row -> {
					String code = rowValidator.apply(row);
					return code == null ? Optional.empty() : Optional.of(t("row_error." + code));
				})
				.setErrorSupplier(rows -> {
					Set<String> seen = new HashSet<>();
					for (String row : rows) {
						String id = Config.extractRowId(row);
						if (id != null && !seen.add(id)) return Optional.of(t("row_error.duplicate_id"));
					}
					return Optional.empty();
				});
		if (tooltip != null) {
			builder.setTooltip(tooltip);
		}
		cat.addEntry(builder.build());
	}

	/** 取翻译组件，统一前缀 {@code text.traceableprint.}，减少上文噪音。 */
	private static Component t(String key) {
		return Component.translatable("text.traceableprint." + key);
	}

	/** 把 tick 数格式化成「N 秒」用于滑条文本；20 tick = 1 秒（整除截断，滑条粒度足够）。 */
	private static Component seconds(Number ticks) {
		return Component.translatable("text.traceableprint.seconds", ticks.longValue() / 20L);
	}
}
