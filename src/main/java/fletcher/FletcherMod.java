package fletcher;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Стол лучника в ванили — обычный {@code Block} без {@code onUse} (класса {@code FletchingTableBlock}
 * в 26.2 больше нет). Значит GUI вешаем на событие правого клика и открываем ванильный хоппер-экран:
 * клиенту ничего ставить не нужно.
 * <p>
 * С шифтом событие пропускается: иначе мод съедал бы правый клик и на столе нельзя было бы
 * поставить блок, как это работает в ванили с любым интерактивным блоком.
 */
public class FletcherMod implements ModInitializer {
	public static final String MOD_ID = "fletcher";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || player.isSpectator()) {
				return InteractionResult.PASS;
			}
			// с блоком в руке и шифтом — обычная установка блока, как в ванили: без этого
			// мод съедал бы клик и на стол нельзя было бы ничего поставить
			if (player.isSecondaryUseActive() && player.getMainHandItem().getItem() instanceof BlockItem) {
				return InteractionResult.PASS;
			}
			BlockPos pos = hitResult.getBlockPos();
			if (level.getBlockState(pos).getBlock() != Blocks.FLETCHING_TABLE) {
				return InteractionResult.PASS;
			}
			if (!(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}

			FletcherInventory inventory = new FletcherInventory();
			serverPlayer.openMenu(new SimpleMenuProvider(
				(syncId, playerInventory, p) -> new FletcherMenu(syncId, playerInventory, inventory, pos),
				Component.translatableWithFallback("fletcher.table", "Стол лучника")
			));
			return InteractionResult.SUCCESS;
		});

		LOGGER.info("Fletcher: стол лучника открывает сборку стрел");

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.containerMenu instanceof FletcherMenu menu) {
					menu.tickPreviews();
				}
			}
		});

		if (System.getProperty("fletcher.selftest") != null) {
			ServerLifecycleEvents.SERVER_STARTED.register(SelfTest::run);
			LOGGER.info("Fletcher: включена самопроверка (-Dfletcher.selftest)");
		}
	}
}
