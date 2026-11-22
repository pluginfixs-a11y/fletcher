package fletcher.mixin;

import fletcher.Quiver;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Мешок умеет складывать предметы в {@code bundle_contents} кликом по нему в инвентаре. Колчан
 * использует штатный {@code container} и свою вкладку, а {@code BUNDLE_CONTENTS} у него пустой по
 * умолчанию (компонент приходит из прототипа предмета), поэтому без запрета стрелу можно было бы
 * положить «мимо» пяти слотов — она бы пропала из виду. Запрещаем оба жеста именно у колчана.
 */
@Mixin(BundleItem.class)
public abstract class BundleItemMixin {
	@Inject(method = "overrideStackedOnOther", at = @At("HEAD"), cancellable = true)
	private void fletcher$blockBundleStorage(ItemStack self, Slot slot, ClickAction action, Player player,
		CallbackInfoReturnable<Boolean> cir) {
		if (Quiver.isQuiver(self)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "overrideOtherStackedOnMe", at = @At("HEAD"), cancellable = true)
	private void fletcher$blockBundleStorageOther(ItemStack self, ItemStack other, Slot slot, ClickAction action,
		Player player, SlotAccess access, CallbackInfoReturnable<Boolean> cir) {
		if (Quiver.isQuiver(self)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * Колчан держит стрелы в {@code CONTAINER}, а ванильный {@code BundleItem.onDestroyed} выбрасывает
	 * только {@code BUNDLE_CONTENTS}. Без этого лава/огонь/кактус уничтожали колчан вместе со стрелами,
	 * тогда как обычный мешок своё содержимое выбрасывает. {@code CONTAINER} чистим, чтобы предмет
	 * не уехал вместе с уже выброшенными копиями.
	 */
	@Inject(method = "onDestroyed", at = @At("HEAD"))
	private void fletcher$dropQuiverContents(ItemEntity entity, CallbackInfo ci) {
		ItemStack stack = entity.getItem();
		if (!Quiver.isQuiver(stack)) {
			return;
		}
		NonNullList<ItemStack> items = Quiver.contents(stack);
		stack.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
		ItemUtils.onContainerDestroyed(entity, items.stream().filter(item -> !item.isEmpty()));
	}
}
