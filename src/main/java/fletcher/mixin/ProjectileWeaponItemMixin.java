package fletcher.mixin;

import fletcher.Quiver;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Расход стрелы из колчана. Ваниль режет копию, полученную из {@code Player#getProjectile}, поэтому
 * без этого хука колчан был бы бесконечным. Списываем только когда {@code useAmmo} реально вернул
 * стрелу без {@code INTANGIBLE_PROJECTILE}: так «Бесконечность», креатив и лишние стрелы
 * мультивыстрела ничего не тратят, как в ванили.
 * <p>
 * Пометку снимаем до создания снаряда, иначе стрела с маркером не складывалась бы с обычными при
 * подборе.
 */
@Mixin(ProjectileWeaponItem.class)
public abstract class ProjectileWeaponItemMixin {
	@Inject(method = "useAmmo", at = @At("RETURN"))
	private static void fletcher$consumeQuiverAmmo(ItemStack weapon, ItemStack projectile, LivingEntity holder,
		boolean forceInfinite, CallbackInfoReturnable<ItemStack> cir) {
		ItemStack used = cir.getReturnValue();
		int slot = Quiver.sourceSlot(used);
		if (slot < 0) {
			return;
		}
		Quiver.unmark(used);
		if (!(holder instanceof Player player) || !(holder.level() instanceof ServerLevel)) {
			return;
		}
		if (used.get(DataComponents.INTANGIBLE_PROJECTILE) != null) {
			return;
		}
		// обычно 1, но processAmmoUse у чужого зачарования/мода может вернуть больше
		Quiver.consumeAt(player, slot, used.getCount());
	}
}
