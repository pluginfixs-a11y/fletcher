package fletcher.mixin;

import fletcher.FletcherArrows;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
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
 * Инерция и гравитация кэшируются: {@code getAirDrag} и {@code getDefaultGravity} зовутся каждый тик
 * каждой стрелой, а чтение NBT каждый раз копирует весь compound. Кэш обновляется там же, где
 * применяются свойства. Эффекты при попадании берут готовые числа из тех же полей.
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {
	@Unique
	private float fletcher$drag;
	@Unique
	private double fletcher$gravity;
	@Unique
	private float fletcher$ignite;
	@Unique
	private int fletcher$glow;
	@Unique
	private boolean fletcher$fire;
	@Unique
	private float fletcher$knockback;
	@Unique
	private boolean fletcher$applied;
	/** Направление полёта в момент удара: ваниль после удара гасит скорость, а отбрасывать надо по нему. */
	@Unique
	private double fletcher$dirX;
	@Unique
	private double fletcher$dirZ;

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

	/**
	 * Гравитация: {@code Entity.applyGravity()} зовёт {@code getGravity()} → {@code getDefaultGravity()},
	 * то есть подменять надо именно здесь, а не в тике — иначе поедут все сущности мира.
	 */
	@Inject(method = "getDefaultGravity", at = @At("RETURN"), cancellable = true)
	private void fletcher$gravity(CallbackInfoReturnable<Double> cir) {
		if (this.fletcher$gravity > 0.0) {
			cir.setReturnValue(this.fletcher$gravity);
		}
	}

	@Inject(method = "onHitEntity", at = @At("HEAD"))
	private void fletcher$rememberDirection(EntityHitResult hit, CallbackInfo ci) {
		Vec3 motion = ((AbstractArrow) (Object) this).getDeltaMovement().multiply(1.0, 0.0, 1.0);
		double length = motion.length();
		this.fletcher$dirX = length < 1.0E-7 ? 0.0 : motion.x / length;
		this.fletcher$dirZ = length < 1.0E-7 ? 0.0 : motion.z / length;
	}

	/**
	 * Эффекты частей на попадании. Горение растягиваем после ванильного поджога (оно ставит ровно
	 * 5 секунд), отбрасывание считаем по той же формуле, что ванильный «Удар», подсветка — обычный
	 * зельевой эффект, клиент рисует контур сам.
	 */
	@Inject(method = "onHitEntity", at = @At("RETURN"))
	private void fletcher$onHitEntity(EntityHitResult hit, CallbackInfo ci) {
		if (!(hit.getEntity() instanceof LivingEntity target)) {
			return;
		}
		AbstractArrow self = (AbstractArrow) (Object) this;
		// эндермена ваниль не поджигает — не меняем это правило
		if (this.fletcher$ignite > 0.0F && self.isOnFire() && !target.is(EntityTypes.ENDERMAN)) {
			target.igniteForSeconds(this.fletcher$ignite);
		}
		if (this.fletcher$glow > 0) {
			target.addEffect(new MobEffectInstance(MobEffects.GLOWING, this.fletcher$glow, 0));
		}
		if (this.fletcher$knockback > 0.0F) {
			double resistance = Math.max(0.0, 1.0 - target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
			double force = this.fletcher$knockback * 0.6 * resistance;
			if (force > 0.0) {
				target.push(this.fletcher$dirX * force, 0.1, this.fletcher$dirZ * force);
			}
		}
	}

	/** Кремень: стрела поджигает блок, в который воткнулась — как огниво, без своего предмета. */
	@Inject(method = "onHitBlock", at = @At("RETURN"))
	private void fletcher$onHitBlock(BlockHitResult hit, CallbackInfo ci) {
		if (!this.fletcher$fire) {
			return;
		}
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (!(self.level() instanceof ServerLevel level)) {
			return;
		}
		BlockPos pos = hit.getBlockPos();
		Direction direction = hit.getDirection();
		if (BaseFireBlock.canBePlacedAt(level, pos.relative(direction), direction)) {
			pos = pos.relative(direction);
		} else if (!BaseFireBlock.canBePlacedAt(level, pos, direction)) {
			return;
		}
		BlockState fire = BaseFireBlock.getState(level, pos);
		level.setBlock(pos, fire, 11);
		level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F,
			level.getRandom().nextFloat() * 0.4F + 0.8F);
	}

	@Unique
	private void fletcher$applySpec() {
		AbstractArrow self = (AbstractArrow) (Object) this;
		ItemStack arrow = self.getPickupItemStackOrigin();
		if (!FletcherArrows.isFletcher(arrow)) {
			this.fletcher$drag = 0.0F;
			this.fletcher$gravity = 0.0;
			this.fletcher$ignite = 0.0F;
			this.fletcher$glow = 0;
			this.fletcher$fire = false;
			this.fletcher$knockback = 0.0F;
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
		this.fletcher$gravity = FletcherArrows.gravityOf(arrow);
		this.fletcher$ignite = FletcherArrows.igniteOf(arrow);
		this.fletcher$glow = FletcherArrows.glowOf(arrow);
		this.fletcher$fire = FletcherArrows.setsFire(arrow);
		this.fletcher$knockback = FletcherArrows.knockbackOf(arrow);
		self.setBaseDamage(FletcherArrows.baseDamageOf(arrow));
		// огненное древко: стрела должна гореть сама, иначе ваниль не подожжёт цель.
		// 600 тиков — больше, чем летит стрела; вода и дождь тушат её штатно (clearFire в тике)
		if (this.fletcher$ignite > 0.0F && !self.isOnFire()) {
			self.setRemainingFireTicks(600);
		}
		// не ослабляем зачарование: у арбалета с «Пробиванием» уровень уже выставлен конструктором
		int pierce = Math.max(self.getPierceLevel(), FletcherArrows.pierceOf(arrow));
		if (pierce > 0) {
			((AbstractArrowInvoker) self).fletcher$setPierceLevel((byte) pierce);
		}
	}
}
