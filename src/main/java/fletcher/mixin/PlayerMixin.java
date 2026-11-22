package fletcher.mixin;

import fletcher.Quiver;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Колчан в хотбаре важнее стрел в инвентаре: оружие получает копию выбранной стрелы. Копия
 * помечена слотом хотбара, чтобы {@link ProjectileWeaponItemMixin} списал стрелу именно оттуда,
 * а не из стека, который ваниль и так режет через {@code useAmmo}.
 * <p>
 * Только сервер. Ванильный клиент не видит колчан в своём {@code getProjectile} и на первом клике
 * может попробовать левую руку; серверный {@code UseItemCallback} её в этом случае отклоняет, а
 * натяжение лука подхватывается из синхронизации состояния на следующем тике.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "getProjectile", at = @At("HEAD"), cancellable = true)
	private void fletcher$quiverProjectile(ItemStack weapon, CallbackInfoReturnable<ItemStack> cir) {
		Player self = (Player) (Object) this;
		if (!(weapon.getItem() instanceof ProjectileWeaponItem item)) {
			return;
		}
		// ванильный приоритет: стрела/фейерверк в руке (обычно в левой) важнее колчана
		if (!ProjectileWeaponItem.getHeldProjectile(self, item.getSupportedHeldProjectiles()).isEmpty()) {
			return;
		}
		Quiver.HotbarRef ref = Quiver.findActive(self);
		if (ref == null || !item.getAllSupportedProjectiles().test(ref.selected())) {
			return;
		}
		cir.setReturnValue(Quiver.mark(ref.selected(), ref.slot()));
	}
}
