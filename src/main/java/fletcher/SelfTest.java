package fletcher;

import fletcher.mixin.AbstractArrowInvoker;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Самопроверка запускается только с {@code -Dfletcher.selftest} на тестовом сервере.
 * <p>
 * Игра в одиночку здесь не проверяется (нужен живой игрок), но проверяется главное: что миксин
 * реально применился к загруженному классу {@code AbstractArrow} (а не «собрался и молчит»),
 * что рецепт не зависит от порядка слотов и что все известные пути дюпа закрыты.
 * <p>
 * Тесты кликов идут через {@code menu.clicked(...)} — это ровно та точка, куда попадает пакет
 * клиента ({@code ServerGamePacketListenerImpl#handleContainerClick}), поэтому проверяются
 * настоящие ванильные ветки {@code doClick}, а не наши представления о них.
 */
public final class SelfTest {
	private static final Logger LOGGER = FletcherMod.LOGGER;

	private SelfTest() {
	}

	public static void run(MinecraftServer server) {
		boolean ok = true;
		try {
			ok &= containerTest();
			ok &= menuTest(server);
			ok &= clickPathsTest(server);
			ok &= arrowTest(server);
			ok &= clampTest(server);
			ok &= specialTest(server);
		} catch (Throwable t) {
			LOGGER.error("Fletcher: самопроверка упала", t);
			ok = false;
		}
		LOGGER.info("Fletcher: самопроверка — {}", ok ? "OK" : "ПРОВАЛ");
		if (!ok) {
			throw new IllegalStateException("Fletcher: самопроверка не прошла");
		}
		// тестовый прогон не должен висеть: гасим сервер сразу после отчёта
		Thread stopper = new Thread(() -> server.halt(false), "fletcher-selftest-stop");
		stopper.setDaemon(true);
		stopper.start();
	}

	private static FakePlayer freshPlayer(MinecraftServer server) {
		FakePlayer player = FakePlayer.get(server.overworld());
		player.getInventory().clearContent();
		return player;
	}

	private static boolean containerTest() {
		// порядок жёсткий: 0 — оперение, 1 — древко, 2 — наконечник
		FletcherInventory inventory = new FletcherInventory();
		inventory.setItem(0, new ItemStack(Items.FEATHER, 3));
		inventory.setItem(1, new ItemStack(Items.STICK, 3));
		inventory.setItem(2, new ItemStack(Items.IRON_NUGGET, 3));

		ItemStack output = inventory.getItem(FletcherInventory.SLOT_OUTPUT);
		boolean built = !output.isEmpty() && output.getCount() == FletcherArrows.ARROWS_PER_CRAFT;
		inventory.consumeInputs();
		boolean consumed = inventory.getItem(0).getCount() == 2
			&& inventory.getItem(1).getCount() == 2
			&& inventory.getItem(2).getCount() == 2
			&& !inventory.getItem(FletcherInventory.SLOT_OUTPUT).isEmpty();

		// обратный порядок — не рецепт
		FletcherInventory wrong = new FletcherInventory();
		wrong.setItem(0, new ItemStack(Items.STICK));
		wrong.setItem(1, new ItemStack(Items.FEATHER));
		wrong.setItem(2, new ItemStack(Items.IRON_NUGGET));
		boolean wrongOrder = wrong.getItem(FletcherInventory.SLOT_OUTPUT).isEmpty();

		// дубль роли и мусор тоже не дают результата
		FletcherInventory junk = new FletcherInventory();
		junk.setItem(0, new ItemStack(Items.FEATHER));
		junk.setItem(1, new ItemStack(Items.FEATHER));
		junk.setItem(2, new ItemStack(Items.FLINT));
		boolean junkIgnored = junk.getItem(FletcherInventory.SLOT_OUTPUT).isEmpty();

		// слот не принимает чужую роль
		boolean filter = inventory.canPlaceItem(0, new ItemStack(Items.FEATHER))
			&& !inventory.canPlaceItem(0, new ItemStack(Items.STICK))
			&& !inventory.canPlaceItem(1, new ItemStack(Items.IRON_NUGGET))
			&& !inventory.canPlaceItem(2, new ItemStack(Items.FEATHER));

		LOGGER.info("Fletcher: контейнер — результат {}, расход {}, порядок {}, мусор {}, роли {}",
			ok(built), ok(consumed), ok(wrongOrder), ok(junkIgnored), ok(filter));
		return built && consumed && wrongOrder && junkIgnored && filter;
	}

	private static boolean menuTest(MinecraftServer server) {
		FakePlayer player = freshPlayer(server);
		FletcherInventory inventory = new FletcherInventory();
		FletcherMenu menu = new FletcherMenu(0, player.getInventory(), inventory, BlockPos.ZERO);

		boolean slots = menu.slots.size() == 41;

		// подсказки по ролям: слот 0 — оперение, 1 — древко, 2 — наконечник
		boolean preview = !menu.slots.get(0).getItem().isEmpty()
			&& FletcherArrows.roleOf(menu.slots.get(0).getItem()) == FletcherArrows.Role.FEATHER
			&& FletcherArrows.roleOf(menu.slots.get(1).getItem()) == FletcherArrows.Role.SHAFT
			&& FletcherArrows.roleOf(menu.slots.get(2).getItem()) == FletcherArrows.Role.HEAD
			&& inventory.getItem(0).isEmpty();

		// подсказка прячется, когда в руке предмет
		menu.setCarried(new ItemStack(Items.FEATHER, 2));
		boolean previewHidden = menu.slots.get(0).getItem().isEmpty() && inventory.getItem(0).isEmpty();

		// чужую роль слот не принимает: перо не ложится в слот древка
		menu.setCarried(new ItemStack(Items.FEATHER, 1));
		menu.clicked(1, 0, ContainerInput.PICKUP, player);
		boolean wrongRole = inventory.getItem(1).isEmpty() && menu.getCarried().getCount() == 1;
		menu.setCarried(ItemStack.EMPTY);

		// левая кладка: ваниль сама зовёт safeInsert и пишет в контейнер, подсказку не трогает
		menu.setCarried(new ItemStack(Items.FEATHER, 2));
		menu.clicked(0, 0, ContainerInput.PICKUP, player);
		boolean placed = inventory.getItem(0).getCount() == 2
			&& menu.getCarried().isEmpty()
			&& inventory.getPreview(0).isEmpty();

		// правая кладка: один предмет, остаток остаётся в руке и доливается левой
		menu.setCarried(new ItemStack(Items.STICK, 2));
		menu.clicked(1, 1, ContainerInput.PICKUP, player);
		boolean placedOne = inventory.getItem(1).getCount() == 1 && menu.getCarried().getCount() == 1;
		menu.clicked(1, 0, ContainerInput.PICKUP, player);
		boolean merged = inventory.getItem(1).getCount() == 2 && menu.getCarried().isEmpty();

		// shift-click из инвентаря сам находит свободный слот материала (меню 6 = инвентарь 10)
		player.getInventory().setItem(10, new ItemStack(Items.IRON_NUGGET, 2));
		menu.quickMoveStack(player, 6);
		boolean autoPlaced = player.getInventory().getItem(10).isEmpty()
			&& inventory.getItem(2).getCount() == 2;

		// комплект собран, но в руке палка — выдача не должна пройти
		menu.setCarried(new ItemStack(Items.STICK, 2));
		menu.clicked(FletcherInventory.SLOT_OUTPUT, 0, ContainerInput.PICKUP, player);
		boolean busyHand = menu.getCarried().getCount() == 2 && !inventory.getItem(0).isEmpty();

		// пустая рука: 4 стрелы на курсор, списан один комплект
		menu.setCarried(ItemStack.EMPTY);
		menu.clicked(FletcherInventory.SLOT_OUTPUT, 0, ContainerInput.PICKUP, player);
		ItemStack carried = menu.getCarried();
		boolean took = !carried.isEmpty()
			&& carried.getItem() == Items.ARROW
			&& carried.getCount() == FletcherArrows.ARROWS_PER_CRAFT
			&& inventory.getItem(0).getCount() == 1
			&& inventory.getItem(1).getCount() == 1
			&& inventory.getItem(2).getCount() == 1;

		// второй клик с занятой рукой: ни выдачи, ни расхода
		menu.clicked(FletcherInventory.SLOT_OUTPUT, 0, ContainerInput.PICKUP, player);
		boolean blocked = menu.getCarried().getCount() == FletcherArrows.ARROWS_PER_CRAFT
			&& inventory.getItem(0).getCount() == 1;

		// зачарование не слабеет, а состав перечитывается: 4 уровня пробивания уцелевают после смены
		// предмета, но урон и инерция берутся из нового стека (иначе проверка была бы тавтологией)
		boolean pierceKept = false;
		Arrow probe = null;
		try {
			ItemStack first = FletcherArrows.build(
				new ItemStack(Items.FEATHER), new ItemStack(Items.STICK), new ItemStack(Items.IRON_NUGGET));
			ItemStack second = FletcherArrows.build(
				new ItemStack(Items.PAPER), new ItemStack(Items.BAMBOO), new ItemStack(Items.FLINT));
			probe = new Arrow(server.overworld(), 0.0D, 128.0D, 0.0D, first, new ItemStack(Items.BOW));
			((AbstractArrowInvoker) probe).fletcher$setPierceLevel((byte) 4);
			Method setPickup = AbstractArrow.class.getDeclaredMethod("setPickupItemStack", ItemStack.class);
			setPickup.setAccessible(true);
			setPickup.invoke(probe, second);
			pierceKept = probe.getPierceLevel() == 4
				&& Math.abs(drag(probe) - FletcherArrows.dragOf(second)) < 1.0E-6F
				&& Math.abs(damage(probe) - FletcherArrows.baseDamageOf(second)) < 1.0E-6D;
		} catch (ReflectiveOperationException e) {
			LOGGER.error("Fletcher: проверка пробивания упала", e);
		} finally {
			if (probe != null) {
				probe.discard();
			}
		}

		// в слот результата положить нельзя, из заблокированного взять нельзя
		boolean locked = !menu.slots.get(FletcherInventory.SLOT_OUTPUT).mayPlace(new ItemStack(Items.ARROW))
			&& !menu.slots.get(FletcherInventory.SLOT_LOCKED).mayPickup(player);

		// shift-click забирает остаток и гасит результат; освободившиеся слоты снова с подсказкой
		menu.setCarried(ItemStack.EMPTY);
		menu.clicked(FletcherInventory.SLOT_OUTPUT, 0, ContainerInput.QUICK_MOVE, player);
		boolean drained = inventory.getItem(0).isEmpty()
			&& inventory.getItem(1).isEmpty()
			&& inventory.getItem(2).isEmpty()
			&& inventory.getItem(FletcherInventory.SLOT_OUTPUT).isEmpty()
			&& !menu.slots.get(0).getItem().isEmpty();
		menu.clicked(FletcherInventory.SLOT_OUTPUT, 0, ContainerInput.PICKUP, player);
		boolean noGhostOutput = menu.getCarried().isEmpty();

		boolean ok = slots && preview && previewHidden && wrongRole && placed && placedOne && merged && autoPlaced
			&& busyHand && took && blocked && pierceKept && locked && drained && noGhostOutput;
		LOGGER.info("Fletcher: меню — слоты {}, подсказка {}, спрятана {}, чужая роль {}, кладка {}, правая {}, долив {}, "
				+ "автораскладка {}, занятая рука {}, выдача {}, блок {}, пробивание-не-слабеет {}, "
				+ "блокировка {}, расход {}, без пустышки {}",
			ok(slots), ok(preview), ok(previewHidden), ok(wrongRole), ok(placed), ok(placedOne), ok(merged),
			ok(autoPlaced), ok(busyHand), ok(took), ok(blocked), ok(pierceKept),
			ok(locked), ok(drained), ok(noGhostOutput));
		return ok;
	}

	/** Пути, которыми ваниль умеет дюпать или падать: драг, двойной клик, CLONE, THROW, отрицательный слот. */
	private static boolean clickPathsTest(MinecraftServer server) {
		FakePlayer player = freshPlayer(server);

		// F/хотбар в слот с подсказкой: кость — древко, ложится только в слот 1
		FletcherInventory swapInv = new FletcherInventory();
		FletcherMenu swapMenu = new FletcherMenu(0, player.getInventory(), swapInv, BlockPos.ZERO);
		player.getInventory().setItem(0, new ItemStack(Items.BONE, 5));
		swapMenu.clicked(1, 0, ContainerInput.SWAP, player);
		boolean swapped = swapInv.getItem(1).getCount() == 5 && player.getInventory().getItem(0).isEmpty();
		player.getInventory().setItem(0, new ItemStack(Items.BONE, 3));
		swapMenu.clicked(2, 0, ContainerInput.SWAP, player);
		boolean swapRejected = swapInv.getItem(2).isEmpty() && player.getInventory().getItem(0).getCount() == 3;

		// драг по двум слотам: перья принимает только слот 0, чужой слот их не берёт
		FletcherInventory dragInv = new FletcherInventory();
		FletcherMenu dragMenu = new FletcherMenu(1, player.getInventory(), dragInv, BlockPos.ZERO);
		dragMenu.setCarried(new ItemStack(Items.FEATHER, 5));
		dragMenu.clicked(-999, AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT, player);
		dragMenu.clicked(0, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, player);
		dragMenu.clicked(1, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, player);
		dragMenu.clicked(-999, AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT, player);
		boolean drag = dragInv.getItem(0).getCount() == 5
			&& dragInv.getItem(1).isEmpty()
			&& dragMenu.getCarried().isEmpty();

		// драг только по чужому слоту: ничего не происходит
		dragMenu.setCarried(new ItemStack(Items.FEATHER, 5));
		dragMenu.clicked(-999, AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT, player);
		dragMenu.clicked(1, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, player);
		dragMenu.clicked(-999, AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT, player);
		boolean dragRejected = dragInv.getItem(1).isEmpty() && dragMenu.getCarried().getCount() == 5;

		// подсказка, двойной клик, CLONE, Q и отрицательный слот — на чистом столе
		FletcherInventory inventory = new FletcherInventory();
		FletcherMenu menu = new FletcherMenu(2, player.getInventory(), inventory, BlockPos.ZERO);
		inventory.setPreview(0, new ItemStack(Items.FEATHER));

		// двойной клик по инвентарю не должен утащить подсказку из стола
		menu.setCarried(new ItemStack(Items.FEATHER, 10));
		menu.clicked(40, 0, ContainerInput.PICKUP_ALL, player);
		boolean pickAll = menu.getCarried().getCount() == 10 && inventory.getItem(0).isEmpty();

		// creative CLONE по подсказке не должен выдать 64 предмета
		menu.setCarried(ItemStack.EMPTY);
		menu.clicked(0, 0, ContainerInput.CLONE, player);
		boolean clone = menu.getCarried().isEmpty() && inventory.getItem(0).isEmpty();

		// Q по слоту с подсказкой ничего не выбрасывает
		menu.clicked(0, 0, ContainerInput.THROW, player);
		boolean throwSafe = inventory.getItem(0).isEmpty() && inventory.getPreview(0).getCount() == 1;

		// отрицательный слот с SWAP раньше ронял сервер на slots.get(-1)
		boolean negativeSafe = true;
		try {
			menu.clicked(-999, 0, ContainerInput.SWAP, player);
			menu.clicked(-1, 0, ContainerInput.THROW, player);
		} catch (RuntimeException e) {
			negativeSafe = false;
			LOGGER.error("Fletcher: отрицательный слот не перехвачен", e);
		}

		boolean ok = swapped && swapRejected && drag && dragRejected && pickAll && clone && throwSafe && negativeSafe;
		LOGGER.info("Fletcher: клики — F {}, F-чужая {}, драг {}, драг-чужая {}, двойной {}, CLONE {}, Q {}, отрицательный слот {}",
			ok(swapped), ok(swapRejected), ok(drag), ok(dragRejected), ok(pickAll), ok(clone), ok(throwSafe), ok(negativeSafe));
		return ok;
	}

	private static boolean arrowTest(MinecraftServer server) throws Exception {
		ServerLevel level = server.overworld();
		ItemStack stack = FletcherArrows.build(
			new ItemStack(Items.FEATHER),
			new ItemStack(Items.STICK),
			new ItemStack(Items.IRON_NUGGET)
		);
		if (stack.isEmpty()) {
			LOGGER.error("Fletcher: стрела не собралась");
			return false;
		}

		Arrow arrow = new Arrow(level, 0.0D, 128.0D, 0.0D, stack, new ItemStack(Items.BOW));
		float drag = drag(arrow);
		double damage = damage(arrow);
		int pierce = arrow.getPierceLevel();
		boolean ok = Math.abs(drag - FletcherArrows.dragOf(stack)) < 1.0E-6F
			&& Math.abs(damage - FletcherArrows.baseDamageOf(stack)) < 1.0E-6D
			&& pierce == FletcherArrows.pierceOf(stack);
		LOGGER.info("Fletcher: стрела drag={} (ожид. {}), damage={} (ожид. {}), pierce={} (ожид. {})",
			drag, FletcherArrows.dragOf(stack), damage, FletcherArrows.baseDamageOf(stack),
			pierce, FletcherArrows.pierceOf(stack));
		arrow.discard();

		// стек переписали на обычную стрелу: наша база обязана сняться (иначе 3.0 висит навсегда),
		// а ванильный урон от «Силы» лука — остаться, поэтому сброс только если мы сами его ставили
		boolean reset = false;
		Arrow plain = null;
		try {
			plain = new Arrow(level, 0.0D, 128.0D, 0.0D, stack, new ItemStack(Items.BOW));
			Method setPickup = AbstractArrow.class.getDeclaredMethod("setPickupItemStack", ItemStack.class);
			setPickup.setAccessible(true);
			setPickup.invoke(plain, new ItemStack(Items.ARROW));
			reset = Math.abs(damage(plain) - FletcherArrows.BASE_DAMAGE) < 1.0E-6D
				&& Math.abs(drag(plain) - FletcherArrows.BASE_DRAG) < 1.0E-6F;
		} catch (ReflectiveOperationException e) {
			LOGGER.error("Fletcher: проверка сброса состава упала", e);
		} finally {
			if (plain != null) {
				plain.discard();
			}
		}
		LOGGER.info("Fletcher: сброс на обычную стрелу — drag={}, damage={} (ожид. ванильные {} / {})",
			reset ? "0.99" : "?", reset ? "2.0" : "?", FletcherArrows.BASE_DRAG, FletcherArrows.BASE_DAMAGE);
		return ok && reset;
	}

	/** NBT можно подделать командой или креативом: значения обязаны зажиматься. */
	private static boolean clampTest(MinecraftServer server) throws Exception {
		ServerLevel level = server.overworld();
		ItemStack forged = forgedArrow(5.0F, 1000.0F, 99);
		Arrow arrow = new Arrow(level, 0.0D, 128.0D, 0.0D, forged, new ItemStack(Items.BOW));
		boolean clamped = Math.abs(drag(arrow) - 0.997F) < 1.0E-6F
			&& Math.abs(damage(arrow) - 6.5D) < 1.0E-6D
			&& arrow.getPierceLevel() == 3;
		arrow.discard();
		LOGGER.info("Fletcher: подделка — drag={}, damage={}, pierce={} (ожид. 0.997 / 6.5 / 3)",
			drag(arrow), damage(arrow), arrow.getPierceLevel());

		// NaN/Infinity проскакивают через Mth.clamp: сравнивать с ними бессмысленно, а baseDamage=NaN
		// оставляет жертву с NaN-здоровьем. Такие значения обязаны падать к ванильным.
		Arrow nan = new Arrow(level, 0.0D, 128.0D, 0.0D,
			forgedArrow(Float.NaN, Float.NaN, 0), new ItemStack(Items.BOW));
		boolean nanSafe = Math.abs(drag(nan) - FletcherArrows.BASE_DRAG) < 1.0E-6F
			&& Math.abs(damage(nan) - FletcherArrows.BASE_DAMAGE) < 1.0E-6D;
		nan.discard();
		Arrow infinite = new Arrow(level, 0.0D, 128.0D, 0.0D,
			forgedArrow(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0), new ItemStack(Items.BOW));
		boolean infiniteSafe = Math.abs(drag(infinite) - FletcherArrows.BASE_DRAG) < 1.0E-6F
			&& Math.abs(damage(infinite) - FletcherArrows.BASE_DAMAGE) < 1.0E-6D;
		infinite.discard();
		LOGGER.info("Fletcher: NaN — drag={}, damage={}; Infinity — drag={}, damage={} (ожид. ванильные {}, {})",
			drag(nan), damage(nan), drag(infinite), damage(infinite),
			FletcherArrows.BASE_DRAG, FletcherArrows.BASE_DAMAGE);

		// потолок чтения обязан совпадать с самым сильным честным набором, иначе лор обещает одно,
		// а на выстреле получается другое. Сверяем сырое число из NBT: baseDamageOf уже обрезан,
		// и дрейф таблицы вверх (алмаз 2.5 -> 3.5) по нему не поймать.
		ItemStack best = FletcherArrows.build(
			new ItemStack(Items.LEATHER), new ItemStack(Items.BLAZE_ROD), new ItemStack(Items.DIAMOND));
		float rawDamage = storedDamage(best);
		float readDamage = FletcherArrows.baseDamageOf(best);
		boolean bestReadable = Math.abs(rawDamage - 6.5F) < 1.0E-6F
			&& Math.abs(readDamage - rawDamage) < 1.0E-6F;
		LOGGER.info("Fletcher: лучший набор — в NBT damage={}, на чтении={} (ожид. 6.5 / 6.5, без обрезки)",
			rawDamage, readDamage);

		// NaN ловит не только миксин (у него свой guard > 0): сама dragOf обязана вернуть 0,
		// иначе защиту можно снять и ничего не заметить
		boolean nanDrag = FletcherArrows.dragOf(forgedArrow(Float.NaN, FletcherArrows.BASE_DAMAGE, 0)) == 0.0F
			&& FletcherArrows.dragOf(
				forgedArrow(Float.POSITIVE_INFINITY, FletcherArrows.BASE_DAMAGE, 0)) == 0.0F
			&& FletcherArrows.dragOf(forgedArrow(-1.0F, FletcherArrows.BASE_DAMAGE, 0)) == 0.0F;
		LOGGER.info("Fletcher: dragOf на NaN / Infinity / -1 — все 0: {}", nanDrag);

		return clamped && nanSafe && infiniteSafe && bestReadable && nanDrag;
	}

	/** Сырое значение урона, записанное в {@code custom_data.fletcher} (без обрезки на чтении). */
	private static float storedDamage(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? 0.0F
			: data.copyTag().getCompoundOrEmpty(FletcherArrows.NBT_ROOT)
				.getFloatOr(FletcherArrows.KEY_DAMAGE, 0.0F);
	}

	/** Стрела с подделанным {@code custom_data.fletcher}. */
	private static ItemStack forgedArrow(float drag, float damage, int pierce) {
		ItemStack stack = new ItemStack(Items.ARROW, 4);
		CompoundTag tag = new CompoundTag();
		tag.putFloat(FletcherArrows.KEY_DRAG, drag);
		tag.putFloat(FletcherArrows.KEY_DAMAGE, damage);
		tag.putInt(FletcherArrows.KEY_PIERCE, pierce);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.put(FletcherArrows.NBT_ROOT, tag));
		return stack;
	}

	/**
	 * Особые свойства частей: гравитация мембраны, огонь блейза, подсветка аметиста, огонь по блоку
	 * от кремня и отбрасывание кости. Попадание зовётся настоящим {@code onHitEntity}, а не своей
	 * копией логики — иначе проверяли бы себя, а не игру.
	 * <p>
	 * Эффекты навешиваются в {@code doPostHurtEffects}, поэтому отдельно проверяется, что в кадры
	 * неуязвимости (второе попадание подряд) они не проходят: иначе мультивыстрел жёг бы цель без урона.
	 */
	private static boolean specialTest(MinecraftServer server) throws Exception {
		ServerLevel level = server.overworld();
		List<Entity> spawned = new ArrayList<>();
		BlockPos stone = new BlockPos(1000, 200, 1000);
		boolean stonePlaced = false;

		boolean glide;
		boolean lit;
		boolean ignites;
		boolean glows;
		double vanillaPush;
		double push;
		boolean firePlaced;
		boolean fireRespectsOwner;
		boolean ironHalves;
		boolean ironBreaks;
		boolean ironSpends;
		boolean ironSparesUnbreakable;
		boolean ironSparesDurable;
		boolean ironRespectsOwner;
		boolean ironRespectsBorder;
		boolean ironRespectsAdventure;
		boolean ironFlight;
		boolean noEffectInIFrames;
		boolean ownerlessBurns;
		boolean noDownPush;
		boolean diamondPierce;
		boolean loreLines;
		boolean noSpecialForCheap;
		boolean clampedSpecials;
		boolean vanillaPlain;
		int burntTicks;

		try {
			// мембрана: 0.025 против ванильных 0.05
			ItemStack floating = FletcherArrows.build(
				new ItemStack(Items.PHANTOM_MEMBRANE), new ItemStack(Items.STICK), new ItemStack(Items.COPPER_NUGGET));
			Arrow glider = new Arrow(level, 0.0D, 128.0D, 0.0D, floating, new ItemStack(Items.BOW));
			glide = Math.abs(glider.getGravity() - 0.025) < 1.0E-9
				&& Math.abs(glider.getGravity() * 2 - 0.05) < 1.0E-9;
			glider.discard();

			// блейз: стрела горит, цель горит 10 с (200 тиков) вместо ванильных 100
			ItemStack burning = FletcherArrows.build(
				new ItemStack(Items.FEATHER), new ItemStack(Items.BLAZE_ROD), new ItemStack(Items.COPPER_NUGGET));
			Arrow fireArrow = new Arrow(level, 0.0D, 128.0D, 0.0D, burning, new ItemStack(Items.BOW));
			lit = fireArrow.isOnFire();
			Pig burnt = pig(level, spawned);
			fireArrow.setDeltaMovement(1.0, 0.0, 0.0);
			float before = burnt.getHealth();
			hit(fireArrow, burnt);
			burntTicks = burnt.getRemainingFireTicks();
			ignites = burntTicks >= 190 && burnt.getHealth() < before;
			fireArrow.discard();

			// аметист: подсветка 8 с
			ItemStack glowing = FletcherArrows.build(
				new ItemStack(Items.FEATHER), new ItemStack(Items.STICK), new ItemStack(Items.AMETHYST_SHARD));
			Arrow glowArrow = new Arrow(level, 0.0D, 128.0D, 0.0D, glowing, new ItemStack(Items.BOW));
			Pig seen = pig(level, spawned);
			glowArrow.setDeltaMovement(1.0, 0.0, 0.0);
			hit(glowArrow, seen);
			glows = seen.hasEffect(MobEffects.GLOWING)
				&& seen.getEffect(MobEffects.GLOWING).getDuration() >= 150;
			glowArrow.discard();

			// ванильная стрела — точка отсчёта: своего толчка у неё нет
			Arrow reference = new Arrow(level, 0.0D, 128.0D, 0.0D, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
			Pig control = pig(level, spawned);
			reference.setDeltaMovement(1.0, 0.0, 0.0);
			hit(reference, control);
			vanillaPush = control.getDeltaMovement().horizontalDistance();
			reference.discard();
			vanillaPlain = !reference.isOnFire()
				&& Math.abs(reference.getGravity() - 0.05) < 1.0E-9
				&& reference.getPierceLevel() == 0;

			// кость: отбрасывание 2 уровня
			ItemStack bony = FletcherArrows.build(
				new ItemStack(Items.FEATHER), new ItemStack(Items.BONE), new ItemStack(Items.COPPER_NUGGET));
			Arrow knockArrow = new Arrow(level, 0.0D, 128.0D, 0.0D, bony, new ItemStack(Items.BOW));
			Pig pushed = pig(level, spawned);
			knockArrow.setDeltaMovement(1.0, 0.0, 0.0);
			hit(knockArrow, pushed);
			push = pushed.getDeltaMovement().horizontalDistance();
			knockArrow.discard();

			// второе попадание в кадры неуязвимости: урона нет — значит нет ни огня, ни подсветки.
			// Первая стрела заведомо сильнее второй, иначе ваниль пропустит урон разницей
			Pig guarded = pig(level, spawned);
			ItemStack heavy = FletcherArrows.build(
				new ItemStack(Items.FEATHER), new ItemStack(Items.STICK), new ItemStack(Items.DIAMOND));
			Arrow first = new Arrow(level, 0.0D, 128.0D, 0.0D, heavy, new ItemStack(Items.BOW));
			first.setDeltaMovement(1.0, 0.0, 0.0);
			float beforeGuard = guarded.getHealth();
			hit(first, guarded);
			boolean heavyLanded = guarded.getHealth() < beforeGuard;
			first.discard();
			// слабая стрела: бумага + бамбук + аметист = 1.0 урона (меньше 5 у первой), но с подделанным
			// горением — так проверяются обе половины эффектов, огонь и подсветка
			ItemStack weakGlow = FletcherArrows.build(
				new ItemStack(Items.PAPER), new ItemStack(Items.BAMBOO), new ItemStack(Items.AMETHYST_SHARD));
			CustomData.update(DataComponents.CUSTOM_DATA, weakGlow, root -> {
				CompoundTag values = root.getCompoundOrEmpty(FletcherArrows.NBT_ROOT).copy();
				values.putFloat(FletcherArrows.KEY_IGNITE, 10.0F);
				root.put(FletcherArrows.NBT_ROOT, values);
			});
			Arrow second = new Arrow(level, 0.0D, 128.0D, 0.0D, weakGlow, new ItemStack(Items.BOW));
			second.setDeltaMovement(1.0, 0.0, 0.0);
			hit(second, guarded);
			noEffectInIFrames = heavyLanded
				&& guarded.getRemainingFireTicks() == 0 && !guarded.hasEffect(MobEffects.GLOWING);
			second.discard();

			// скелет и раздатчик стреляют без владельца: огонь и подсветка обязаны работать, а вот
			// поджигать блок — нет (проверено выше)
			Pig wild = pig(level, spawned);
			Arrow ownerlessHeat = new Arrow(level, 0.0D, 128.0D, 0.0D, burning, new ItemStack(Items.BOW));
			ownerlessHeat.setDeltaMovement(1.0, 0.0, 0.0);
			hit(ownerlessHeat, wild);
			ownerlessBurns = wild.getRemainingFireTicks() >= 190;
			ownerlessHeat.discard();

			// попадание строго сверху не толкает (у ванилы в этом случае толчка тоже нет)
			Pig top = pig(level, spawned);
			Arrow downward = new Arrow(level, 0.0D, 128.0D, 0.0D, bony, new ItemStack(Items.BOW));
			downward.setDeltaMovement(0.0, 1.0, 0.0);
			hit(downward, top);
			noDownPush = top.getDeltaMovement().horizontalDistance() <= vanillaPush + 0.05;
			downward.discard();

			// кремень: поджигает блок, в который воткнулась (как огниво), но только у игрока и там,
			// где ему можно взаимодействовать
			FakePlayer owner = freshPlayer(server);
			level.setChunkForced(stone.getX() >> 4, stone.getZ() >> 4, true);
			level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
			stonePlaced = true;
			ItemStack flinty = FletcherArrows.build(
				new ItemStack(Items.FEATHER), new ItemStack(Items.STICK), new ItemStack(Items.FLINT));
			Arrow flintArrow = new Arrow(level, 0.0D, 200.0D, 0.0D, flinty, new ItemStack(Items.BOW));
			flintArrow.setOwner(owner);
			hitBlock(flintArrow, new BlockHitResult(new Vec3(1000.5, 201.0, 1000.5), Direction.UP, stone, false));
			firePlaced = level.getBlockState(stone.above()).getBlock() instanceof BaseFireBlock;
			level.removeBlock(stone.above(), false);
			flintArrow.discard();
			Arrow orphan = new Arrow(level, 0.0D, 200.0D, 0.0D, flinty, new ItemStack(Items.BOW));
			hitBlock(orphan, new BlockHitResult(new Vec3(1000.5, 201.0, 1000.5), Direction.UP, stone, false));
			fireRespectsOwner = !(level.getBlockState(stone.above()).getBlock() instanceof BaseFireBlock);
			orphan.discard();

			// железный слиток: -50% урона, зато ломает блок прочностью до 10 (тяжёлое ядро — ровно 10,
			// предел) и расходуется. Твёрже (обсидиан, незерит, древние обломки, якорь возрождения,
			// усилённый сланец, эндер-сундук) и неломаемое он не берёт — стрела просто втыкается
			// и остаётся подбираемой, платить стрелой не за что.
			ItemStack crushing = FletcherArrows.build(
				new ItemStack(Items.FEATHER), new ItemStack(Items.STICK), new ItemStack(Items.IRON_INGOT));
			Arrow breaker = new Arrow(level, 0.0D, 200.0D, 0.0D, crushing, new ItemStack(Items.BOW));
			breaker.setOwner(owner);
			ItemLore crushingLore = crushing.get(DataComponents.LORE);
			ironHalves = Math.abs(damage(breaker) - 1.0D) < 1.0E-6D
				&& crushingLore != null
				&& "Урон: -50 %".equals(crushingLore.lines().getFirst().getString());
			level.setBlock(stone, Blocks.HEAVY_CORE.defaultBlockState(), 3);
			hitBlock(breaker, new BlockHitResult(new Vec3(1000.5, 201.0, 1000.5), Direction.UP, stone, false));
			ironBreaks = level.getBlockState(stone).isAir();
			ironSpends = breaker.isRemoved();

			// бедрок: отрицательная прочность, блок цел, стрела не израсходована
			level.setBlock(stone, Blocks.BEDROCK.defaultBlockState(), 3);
			Arrow unbreakable = new Arrow(level, 0.0D, 200.0D, 0.0D, crushing, new ItemStack(Items.BOW));
			unbreakable.setOwner(owner);
			hitBlock(unbreakable, new BlockHitResult(new Vec3(1000.5, 201.0, 1000.5), Direction.UP, stone, false));
			ironSparesUnbreakable = level.getBlockState(stone).is(Blocks.BEDROCK)
				&& !unbreakable.isRemoved();
			unbreakable.discard();

			// прочные блоки (прочность > 10): тоже целы и не списывают стрелу. Тег needs_diamond_tool
			// здесь отдельно не проверяется: в ванили все его блоки и так прочнее 10, он нужен
			// модовым/датапаковым блокам с низкой прочностью.
			boolean durableIntact = true;
			Block[] durable = {Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK,
				Blocks.ANCIENT_DEBRIS, Blocks.RESPAWN_ANCHOR, Blocks.REINFORCED_DEEPSLATE, Blocks.ENDER_CHEST};
			for (Block block : durable) {
				level.setBlock(stone, block.defaultBlockState(), 3);
				Arrow toughArrow = new Arrow(level, 0.0D, 200.0D, 0.0D, crushing, new ItemStack(Items.BOW));
				toughArrow.setOwner(owner);
				hitBlock(toughArrow, new BlockHitResult(new Vec3(1000.5, 201.0, 1000.5), Direction.UP, stone, false));
				if (!level.getBlockState(stone).is(block) || toughArrow.isRemoved()) {
					durableIntact = false;
				}
				toughArrow.discard();
			}
			ironSparesDurable = durableIntact;

			// без владельца (скелет, раздатчик) не ломается и обычный блок — иначе мир гриферили бы мобы.
			// Проверяем алмазным блоком: бедрок цел и так, проверка была бы пустой.
			level.setBlock(stone, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
			Arrow unownedBreaker = new Arrow(level, 0.0D, 200.0D, 0.0D, crushing, new ItemStack(Items.BOW));
			hitBlock(unownedBreaker, new BlockHitResult(new Vec3(1000.5, 201.0, 1000.5), Direction.UP, stone, false));
			ironRespectsOwner = level.getBlockState(stone).is(Blocks.DIAMOND_BLOCK)
				&& !unownedBreaker.isRemoved();
			unownedBreaker.discard();

			// защита: снаружи границы мира (та же ветка mayInteract, что и спавн-защита) и в приключении
			// с пустой рукой (blockActionRestricted) ломаемый блок остаётся целым, стрела — подбираемой
			double borderSize = level.getWorldBorder().getSize();
			level.getWorldBorder().setSize(100.0D);
			try {
				level.setBlock(stone, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
				Arrow borderArrow = new Arrow(level, 0.0D, 200.0D, 0.0D, crushing, new ItemStack(Items.BOW));
				borderArrow.setOwner(owner);
				hitBlock(borderArrow, new BlockHitResult(new Vec3(1000.5, 201.0, 1000.5), Direction.UP, stone, false));
				ironRespectsBorder = level.getBlockState(stone).is(Blocks.DIAMOND_BLOCK) && !borderArrow.isRemoved();
				borderArrow.discard();
			} finally {
				level.getWorldBorder().setSize(borderSize);
			}

			GameType gameModeBefore = owner.gameMode();
			owner.setGameMode(GameType.ADVENTURE);
			try {
				level.setBlock(stone, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
				Arrow adventureArrow = new Arrow(level, 0.0D, 200.0D, 0.0D, crushing, new ItemStack(Items.BOW));
				adventureArrow.setOwner(owner);
				hitBlock(adventureArrow, new BlockHitResult(new Vec3(1000.5, 201.0, 1000.5), Direction.UP, stone, false));
				ironRespectsAdventure = level.getBlockState(stone).is(Blocks.DIAMOND_BLOCK)
					&& !adventureArrow.isRemoved();
				adventureArrow.discard();
			} finally {
				owner.setGameMode(gameModeBefore);
			}

			// настоящий полёт: проверяется не только прямой вызов onHitBlock, но и то, что стрела
			// долетает сама, ломает блок и исчезает в тике попадания. Столб над целью чистим:
			// на тестовом мире с высоким рельефом стрела иначе воткнулась бы в него.
			for (int y = stone.getY() + 1; y <= 206; y++) {
				level.setBlock(new BlockPos(stone.getX(), y, stone.getZ()), Blocks.AIR.defaultBlockState(), 3);
			}
			level.setBlock(stone, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
			Arrow flying = new Arrow(level, 1000.5D, 206.0D, 1000.5D, crushing, new ItemStack(Items.BOW));
			flying.setOwner(owner);
			flying.shoot(0.0D, -1.0D, 0.0D, 1.5F, 0.0F);
			for (int i = 0; i < 20 && !flying.isRemoved(); i++) {
				flying.tick();
			}
			ironFlight = level.getBlockState(stone).isAir() && flying.isRemoved();

			// дроп не проверяем: ручной tick стрелы не онбордит секции сущностей чанка, поэтому
			// только что выпавший ItemEntity не виден ни getAllEntities, ни getEntitiesOfClass;
			// сам вызов destroyBlock(..., true) — ванильный

			// алмазный наконечник теперь добирается до потолка пробивания
			ItemStack sniper = FletcherArrows.build(
				new ItemStack(Items.FEATHER), new ItemStack(Items.STICK), new ItemStack(Items.DIAMOND));
			diamondPierce = FletcherArrows.pierceOf(sniper) == 3;

			// лор: три строки чисел + строка на каждую часть со свойством
			ItemStack described = FletcherArrows.build(
				new ItemStack(Items.PHANTOM_MEMBRANE), new ItemStack(Items.BLAZE_ROD), new ItemStack(Items.FLINT));
			ItemLore lore = described.get(DataComponents.LORE);
			loreLines = lore != null && lore.lines().size() == 6;
			noSpecialForCheap = FletcherArrows.specialLabelOf(new ItemStack(Items.BAMBOO)).isEmpty()
				&& FletcherArrows.specialLabelOf(new ItemStack(Items.PAPER)).isEmpty()
				&& FletcherArrows.specialLabelOf(new ItemStack(Items.STICK)).isEmpty()
				&& !FletcherArrows.specialLabelOf(new ItemStack(Items.BLAZE_ROD)).isEmpty();

			// подделанные спецсвойства зажимаются по честным потолкам
			ItemStack forgedSpecial = forgedArrow(FletcherArrows.BASE_DRAG, FletcherArrows.BASE_DAMAGE, 0);
			CustomData.update(DataComponents.CUSTOM_DATA, forgedSpecial, root -> {
				CompoundTag values = new CompoundTag();
				values.putFloat(FletcherArrows.KEY_IGNITE, 999.0F);
				values.putFloat(FletcherArrows.KEY_GRAVITY, 0.0001F);
				values.putInt(FletcherArrows.KEY_GLOW, 99_999);
				values.putFloat(FletcherArrows.KEY_KNOCKBACK, 99.0F);
				root.put(FletcherArrows.NBT_ROOT, values);
			});
			clampedSpecials = FletcherArrows.igniteOf(forgedSpecial) == 30.0F
				&& Math.abs(FletcherArrows.gravityOf(forgedSpecial) - 0.005F) < 1.0E-6F
				&& FletcherArrows.glowOf(forgedSpecial) == 1200
				&& Math.abs(FletcherArrows.knockbackOf(forgedSpecial) - 2.0F) < 1.0E-6F;
		} finally {
			for (Entity entity : spawned) {
				entity.discard();
			}
			if (stonePlaced) {
				level.removeBlock(stone.above(), false);
				level.removeBlock(stone, false);
				// ловит только дроп, попавший в уже видимые секции (например, от прошлого прогона);
				// свежий дроп этого прогона до онбординга секций здесь не виден
				for (Entity entity : level.getAllEntities()) {
					if (entity instanceof ItemEntity item
						&& item.position().distanceToSqr(1000.5D, 200.5D, 1000.5D) < 64.0D) {
						item.discard();
					}
				}
				level.setChunkForced(stone.getX() >> 4, stone.getZ() >> 4, false);
			}
		}

		LOGGER.info("Fletcher: свойства — мембрана {}, блейз горит {}, цель горит {} тиков, аметист {}, "
				+ "кость толчок {} (ванильная {}), кадры неуязвимости {}, без владельца поджигает {}, "
				+ "сверху не толкает {}, кремень огонь {}, без владельца огня нет {}, "
				+ "слиток -50% {}, слиток ломает {}, слиток расходуется {}, неломаемое цело {}, прочное цело {}, "
				+ "без владельца не ломает {}, граница мира {}, приключение {}, в полёте {}, "
				+ "алмаз пробивание {}, лор {}, без свойств у дешёвых {}, зажим подделки {}, обычная стрела {}",
			ok(glide), ok(lit), burntTicks, ok(glows), push, vanillaPush, ok(noEffectInIFrames),
			ok(ownerlessBurns), ok(noDownPush), ok(firePlaced), ok(fireRespectsOwner),
			ok(ironHalves), ok(ironBreaks), ok(ironSpends), ok(ironSparesUnbreakable),
			ok(ironSparesDurable), ok(ironRespectsOwner), ok(ironRespectsBorder), ok(ironRespectsAdventure),
			ok(ironFlight), ok(diamondPierce), ok(loreLines), ok(noSpecialForCheap), ok(clampedSpecials),
			ok(vanillaPlain));
		return glide && lit && ignites && glows && push > vanillaPush + 0.5 && noEffectInIFrames
			&& ownerlessBurns && noDownPush && firePlaced && fireRespectsOwner
			&& ironHalves && ironBreaks && ironSpends && ironSparesUnbreakable && ironSparesDurable
			&& ironRespectsOwner && ironRespectsBorder && ironRespectsAdventure && ironFlight
			&& diamondPierce && loreLines && noSpecialForCheap && clampedSpecials && vanillaPlain;
	}

	/** Свинья-мишень в воздухе: по ней и проверяем попадание. */
	private static Pig pig(ServerLevel level, List<Entity> spawned) {
		Pig pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
		pig.setPos(0.5D, 128.0D, 0.5D);
		level.addFreshEntity(pig);
		spawned.add(pig);
		return pig;
	}

	private static void hit(Arrow arrow, LivingEntity target) throws ReflectiveOperationException {
		Method onHit = AbstractArrow.class.getDeclaredMethod("onHitEntity", EntityHitResult.class);
		onHit.setAccessible(true);
		onHit.invoke(arrow, new EntityHitResult(target));
	}

	private static void hitBlock(Arrow arrow, BlockHitResult hit) throws ReflectiveOperationException {
		Method onHit = AbstractArrow.class.getDeclaredMethod("onHitBlock", BlockHitResult.class);
		onHit.setAccessible(true);
		onHit.invoke(arrow, hit);
	}

	private static float drag(AbstractArrow arrow) throws ReflectiveOperationException {
		Method method = AbstractArrow.class.getDeclaredMethod("getAirDrag");
		method.setAccessible(true);
		return (float) method.invoke(arrow);
	}

	private static double damage(AbstractArrow arrow) throws ReflectiveOperationException {
		Field field = AbstractArrow.class.getDeclaredField("baseDamage");
		field.setAccessible(true);
		return field.getDouble(arrow);
	}

	private static String ok(boolean value) {
		return value ? "OK" : "ПРОВАЛ";
	}
}
