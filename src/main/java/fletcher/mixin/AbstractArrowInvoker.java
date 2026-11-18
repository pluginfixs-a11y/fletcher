package fletcher.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code setPierceLevel} в ванили приватный, а пробивание задаётся наконечником. */
@Mixin(AbstractArrow.class)
public interface AbstractArrowInvoker {
	@Invoker("setPierceLevel")
	void fletcher$setPierceLevel(byte level);
}
