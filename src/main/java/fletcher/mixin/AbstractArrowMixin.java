package fletcher.mixin;

import fletcher.FletcherArrows;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Свойства собранной стрелы применяются на сервере: состав стрелы лежит в её стеке
 * ({@code pickupItemStack}), а физика считается сервером.
 * <p>
 * Миксин стоит на общих классах, поэтому работает и на выделенном сервере с ванильными клиентами:
 * клиент эти значения не читает, он только интерполирует позицию, которую присылает сервер.
 * <p>
 * Конструкторы нужны с точными дескрипторами: {@code method = "<init>"} без дескриптора цепляет
 * только первый попавшийся конструктор (в 26.2 — базовый без стека, где ещё нет состава стрелы).
 * <p>
 * Инерция кэшируется: {@code getAirDrag} зовётся каждый тик каждой стрелой, а чтение NBT каждый раз
 * копирует весь compound. Кэш обновляется там же, где применяются свойства.
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {
	@Unique
	private float fletcher$drag;
	@Unique
	private boolean fletcher$applied;

	@Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;DDDLnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
	private void fletcher$specOnSpawn(CallbackInfo ci) {
		fletcher$applySpec();
	}

	@Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
	private void fletcher$specOnShot(CallbackInfo ci) {
		fletcher$applySpec();
	}

	/** Загрузка из NBT и смена предмета подбора: состав приезжает позже конструктора. */
	@Inject(method = "setPickupItemStack", at = @At("RETURN"))
	private void fletcher$specOnStackChange(ItemStack stack, CallbackInfo ci) {
		fletcher$applySpec();
	}

	@Inject(method = "getAirDrag", at = @At("RETURN"), cancellable = true)
	private void fletcher$airDrag(CallbackInfoReturnable<Float> cir) {
		// в воде ваниль берёт getWaterInertia(), сюда не заходит — подменять нечего
		if (this.fletcher$drag > 0.0F) {
			cir.setReturnValue(this.fletcher$drag);
		}
	}

	@Unique
	private void fletcher$applySpec() {
		AbstractArrow self = (AbstractArrow) (Object) this;
		ItemStack arrow = self.getPickupItemStackOrigin();
		if (!FletcherArrows.isFletcher(arrow)) {
			this.fletcher$drag = 0.0F;
			// стек переписали на обычную стрелу (креатив, /data, чужой мод): возвращаем ванильную базу,
			// иначе наш урон остался бы навсегда — но только если мы его сами и ставили, иначе
			// затрём масштабирование от «Силы» лука
			if (this.fletcher$applied) {
				this.fletcher$applied = false;
				self.setBaseDamage(FletcherArrows.BASE_DAMAGE);
			}
			return;
		}
		this.fletcher$applied = true;
		this.fletcher$drag = FletcherArrows.dragOf(arrow);
		self.setBaseDamage(FletcherArrows.baseDamageOf(arrow));
		// не ослабляем зачарование: у арбалета с «Пробиванием» уровень уже выставлен конструктором
		int pierce = Math.max(self.getPierceLevel(), FletcherArrows.pierceOf(arrow));
		if (pierce > 0) {
			((AbstractArrowInvoker) self).fletcher$setPierceLevel((byte) pierce);
		}
	}
}
