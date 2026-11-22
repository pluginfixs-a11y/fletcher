package fletcher.mixin;

import fletcher.FletcherArrows;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
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
 * применяются свойства. Эффекты при попадании берут готовые числа из тех же полей и навешиваются
 * в {@code doPostHurtEffects} — то есть только когда урон реально прошёл.
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
	private boolean fletcher$breaks;
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
	 * Эффекты частей на попадании — только после успешного урона: ваниль зовёт {@code doPostHurtEffects}
	 * из ветки, где {@code hurt} вернул {@code true}. На отказе (кадры неуязвимости, креатив, отмена
	 * другим модом) эффектов нет — иначе вторая стрела мультивыстрела жгла бы цель без урона.
	 * <p>
	 * Горение растягиваем после ванильного поджога (оно ставит ровно 5 секунд), отбрасывание считаем
	 * по той же формуле, что ванильный «Удар», подсветка — обычный зельевой эффект, клиент рисует
	 * контур сам.
	 */
	@Inject(method = "doPostHurtEffects", at = @At("RETURN"))
	private void fletcher$postHurt(LivingEntity target, CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		// эндермена ваниль не поджигает — не меняем это правило
		if (this.fletcher$ignite > 0.0F && self.isOnFire() && !target.is(EntityTypes.ENDERMAN)) {
			target.igniteForSeconds(this.fletcher$ignite);
		}
		if (this.fletcher$glow > 0) {
			target.addEffect(new MobEffectInstance(MobEffects.GLOWING, this.fletcher$glow, 0));
		}
		// ваниль при нулевом горизонтальном направлении не толкает вовсе — не добавляем «подскок»
		if (this.fletcher$knockback > 0.0F && (this.fletcher$dirX != 0.0 || this.fletcher$dirZ != 0.0)) {
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
		// огниво — действие игрока: без владельца (раздатчик) и там, где игроку нельзя взаимодействовать
		// (спавн-защита, чужой регион, приключение), огня не будет — та же проверка, что у ванильного
		// использования предмета на блоке
		if (!(self.getOwner() instanceof ServerPlayer owner)
			|| !owner.mayUseItemAt(hit.getBlockPos(), hit.getDirection(), self.getPickupItemStackOrigin())) {
			return;
		}
		BlockPos pos = hit.getBlockPos().relative(hit.getDirection());
		Direction direction = hit.getDirection();
		if (!BaseFireBlock.canBePlacedAt(level, pos, direction)) {
			return;
		}
		BlockState fire = BaseFireBlock.getState(level, pos);
		level.setBlock(pos, fire, 11);
		level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F,
			level.getRandom().nextFloat() * 0.4F + 0.8F);
	}

	/**
	 * Железный слиток: стрела ломает блок, в который воткнулась, без инструмента, но только до
	 * прочности 10 — камень, руды, алмазный блок, тяжёлое ядро. Твёрже (обсидиан, незерит, древние
	 * обломки, якорь возрождения, усилённый глубинный сланец) и неломаемое (отрицательная прочность:
	 * бедрок, барьер, командный и структурный блок) не берёт. Плата — сама стрела: после попадания
	 * она уничтожается, подобрать её нельзя.
	 * <p>
	 * Без игрока-владельца (скелет, раздатчик) и там, где игроку нельзя ломать (приключение,
	 * спавн-защита, граница мира), блок остаётся целым — стрела тогда просто втыкается, как обычная,
	 * и остаётся подбираемой: платить за несостоявшийся взлом нечем.
	 */
	@Inject(method = "onHitBlock", at = @At("RETURN"))
	private void fletcher$breakBlock(BlockHitResult hit, CallbackInfo ci) {
		if (!this.fletcher$breaks) {
			return;
		}
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (!(self.level() instanceof ServerLevel level)
			|| !(self.getOwner() instanceof ServerPlayer owner)) {
			return;
		}
		// те же проверки, что у ванильного разбивания игроком: приключение, спавн-защита, граница мира.
		// До чтения блока — за границей позиция может быть вне мира, там состояния блока просто нет.
		BlockPos pos = hit.getBlockPos();
		if (!level.mayInteract(owner, pos) || owner.blockActionRestricted(level, pos, owner.gameMode())) {
			return;
		}
		// destroyBlock прочность не проверяет вообще, поэтому потолок ставим сами: неломаемое
		// (отрицательная скорость) и всё твёрже 10 стрела не берёт — обсидиан, незерит, древние
		// обломки, якорь возрождения, усилённый глубинный сланец. Тег отдельно: модовый блок может
		// требовать алмазную кирку и при низкой прочности.
		BlockState state = level.getBlockState(pos);
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F || hardness > 10.0F || state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
			return;
		}
		// playerWillDestroy сюда не зовётся, а именно в нём ваниль злит пиглинов на сундук/золото
		// бастиона. Сначала ванильный вызов — он честно обрабатывает UNIVERSAL_ANGER вокруг игрока,
		// затем то же самое вокруг блока: стрела летит издалека, и пиглины у сундука иначе не узнают,
		// кто его сломал. Второй цикл повторяет тело PiglinAi.setAngerTarget (ANGRY_AT + UNIVERSAL_ANGER).
		if (state.is(BlockTags.GUARDED_BY_PIGLINS)) {
			PiglinAi.angerNearbyPiglins(level, owner, false);
			// самопроверка этот путь не покрывает: свежеспавненный пиглин до онбординга секций
			// не виден getEntitiesOfClass (та же причина, что у дропа); в игре чанк уже тикает
			for (Piglin piglin : level.getEntitiesOfClass(Piglin.class, new AABB(pos).inflate(16.0D))) {
				if (piglin.getBrain().isActive(Activity.IDLE)
					&& !owner.isInvisible() && piglin.canAttack(owner)) {
					piglin.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
					piglin.getBrain().setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, owner.getUUID(), 600L);
					if (level.getGameRules().get(GameRules.UNIVERSAL_ANGER)) {
						piglin.getBrain().setMemoryWithExpiry(MemoryModuleType.UNIVERSAL_ANGER, true, 600L);
					}
				}
			}
		}
		// dropResources=true: блок выпадает так же, как от кирки; прочность destroyBlock не смотрит,
		// поэтому обсидиан ломается с одного попадания. Стрела расходуется только если блок исчез.
		if (level.destroyBlock(pos, true, self, 512)) {
			self.discard();
		}
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
			this.fletcher$breaks = false;
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
		this.fletcher$breaks = FletcherArrows.breaksBlocks(arrow);
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
