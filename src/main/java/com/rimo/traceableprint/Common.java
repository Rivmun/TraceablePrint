package com.rimo.traceableprint;

import com.rimo.traceableprint.config.Config;
import com.rimo.traceableprint.entity.FootprintEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
//? if <= 1.20.1 {
/*import net.minecraft.resources.ResourceLocation;
*///? } else {
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? }
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Common {
	public static final String MOD_ID = "traceableprint";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// 模组可调参数单例：构造后立即从 config/traceableprint.json 载入（不存在则写出默认）
	public static final Config CONFIG = new Config().load();

	// 父生物“最后一个脚印”的链尾指针改为服务端 mixin 的 @Unique 字段 + NBT 持久化，
	// 不再经 LivingEntity 的 SynchedEntityData 同步：26.1 的 ClassTreeIdRegistry 下，
	// 向被 Mob 等大量原版子类继承的基类新增同步字段会与其在自身 clinit 里固化的 id 撞号
	// （Mob.DATA_MOB_FLAGS_ID 已是 id 15）。客户端“是否链尾”验证改由 FootprintEntity.IS_TAIL 承载。

	// 脚印实体类型的注册表键（26.1 的 EntityType.Builder.build 需要 ResourceKey）
	public static final ResourceKey<EntityType<?>> FOOTPRINT_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, VersionUtil.getId("footprint"));

	// Define entity type
	public static final EntityType<FootprintEntity> FOOTPRINT = EntityType.Builder
			.<FootprintEntity>of(FootprintEntity::new, MobCategory.MISC)
			.sized(0.75f, 0.1f) // 判定范围：薄薄一层贴在地面
			.updateInterval(20) // 同步位置/朝向/速度的频率
			.clientTrackingRange(6) // 实体同步距离：6 区块（96 格）内玩家可见脚印出现与状态变化，作为寻踪线索稍远一些更合适
			//? if <= 1.21.1 {
			/*.build("footprint");
			*///? } else {
			.build(FOOTPRINT_KEY);
			//? }

	public static void init() {
		// Platform-specific registration happens in loaders
	}

	// - - - - - 配置上传：双端共用 payload（纯 vanilla，握手见 DedicatedServer/Client） - - - - -

	//? if <= 1.20.1 {
	/*/^*
	 * S2C 空邀约包（1.20.1）：该版本无 CustomPacketPayload/StreamCodec，此处仅作纯数据载体，
	 * 编解码由 Platform 用 FriendlyByteBuf 走 fabric 通道式 API 手工处理；TYPE 即通道 ResourceLocation。
	 ^/
	public static final class UploadRequestPayload {
		public static final ResourceLocation TYPE = VersionUtil.getId("upload_request");

		public UploadRequestPayload() {
		}
	}

	/^* C2S 配置包（1.20.1）：纯数据载体，json 由 Platform 写入/读出 FriendlyByteBuf；TYPE 即通道 ResourceLocation。 ^/
	public static final class UploadConfigPayload {
		public static final ResourceLocation TYPE = VersionUtil.getId("upload_config");
		private final String json;

		public UploadConfigPayload(String json) {
			this.json = json;
		}

		public String json() {
			return json;
		}
	}
	*///? } else {

	// S2C 空邀约包：服务端校验 op 通过后下发，客户端收到才回传本地配置（防绕过命令直接灌包）。
	public record UploadRequestPayload() implements CustomPacketPayload {
		public static final Type<UploadRequestPayload> TYPE =
				new CustomPacketPayload.Type<>(VersionUtil.getId("upload_request"));
		// 无字段：unit 编解码固定读写同一个单例实例
		public static final StreamCodec<ByteBuf, UploadRequestPayload> CODEC =
				StreamCodec.unit(new UploadRequestPayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// C2S 配置包：客户端把本地 CONFIG Gson 序列化成 string 回传服务端。
	public record UploadConfigPayload(String json) implements CustomPacketPayload {
		public static final Type<UploadConfigPayload> TYPE =
				new CustomPacketPayload.Type<>(VersionUtil.getId("upload_config"));
		public static final StreamCodec<ByteBuf, UploadConfigPayload> CODEC =
				ByteBufCodecs.STRING_UTF8.map(UploadConfigPayload::new, UploadConfigPayload::json);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
	//? }
}
