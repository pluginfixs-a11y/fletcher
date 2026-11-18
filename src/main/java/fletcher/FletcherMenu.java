package fletcher;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Меню стола лучника. Клиент ванильный, поэтому экран берётся готовый — {@code MenuType.HOPPER}
 * с пятью слотами в ряд. Свои {@code MenuType}/экран server-side мод показать не может,
 * зато можно подменить серверные слоты и полностью контролировать клики.
 * <p>
 * Слоты: 0 — оперение, 1 — древко, 2 — наконечник, 3 — заблокирован, 4 — результат.
 * Пустые слоты показывают случайную подсказку своей роли; подсказка живёт в слоте отображения,
 * а не в контейнере, поэтому её нельзя забрать.
 * <p>
 * Подсказка прячется, пока в руке что-то есть ({@link PreviewSlot#getItem()}). Это не косметика:
 * ванильные пути кладут предмет через {@code Slot#safeInsert} и раздачу драгом, а они читают
 * {@code slot.getItem()}. Если отдавать подсказку, когда рука занята, ваниль смешает предмет
 * с подсказкой и запишет сумму в контейнер — то есть выдаст лишний предмет.
 * <p>
 * Как в graves ({@code eu.pb4.graves.other.OutputSlot}), все пути забора результата перекрыты:
 * слот нельзя ни положить, ни забрать ванильным кликом, выдачу делает {@link #clicked}.
 */
public class FletcherMenu extends HopperMenu {
	private static final int SLOT_LOCKED = FletcherInventory.SLOT_LOCKED;
	private static final int SLOT_OUTPUT = FletcherInventory.SLOT_OUTPUT;
	private static final int MATERIAL_SLOTS = FletcherInventory.SLOT_MATERIALS;
	private static final int INVENTORY_START = 5;
	private static final int PREVIEW_INTERVAL = 60;

	private final FletcherInventory fletcher;
	private final BlockPos tablePos;
	private final RandomSource random = RandomSource.create();
	private int previewTimer;

	public FletcherMenu(int syncId, Inventory playerInventory, FletcherInventory inventory, BlockPos tablePos) {
		super(syncId, playerInventory, inventory);
		this.fletcher = inventory;
		this.tablePos = tablePos;

		for (int i = 0; i < MATERIAL_SLOTS; i++) {
			this.slots.set(i, new PreviewSlot(i, 44 + i * 18, 20));
		}
		this.slots.set(SLOT_LOCKED, new LockedSlot(inventory, SLOT_LOCKED, 98, 20));
		this.slots.set(SLOT_OUTPUT, new OutputSlot(inventory, SLOT_OUTPUT, 116, 20));

		refreshPreviews();
	}

	/** Тик открытого меню: раз в 3 секунды подсказки меняются. */
	public void tickPreviews() {
		if (++this.previewTimer < PREVIEW_INTERVAL) {
			return;
		}
		this.previewTimer = 0;
		refreshPreviews();
		this.broadcastChanges();
	}

	private void refreshPreviews() {
		for (int i = 0; i < MATERIAL_SLOTS; i++) {
			if (!this.fletcher.getItem(i).isEmpty()) {
				this.fletcher.clearPreview(i);
				continue;
			}
			FletcherArrows.Role role = FletcherArrows.roleForSlot(i);
			List<Item> pool = FletcherArrows.itemsFor(role);
			Item item = pick(pool, this.fletcher.getPreview(i));
			this.fletcher.setPreview(i, hint(item, role));
		}
	}

	/** Случайный предмет роли, по возможности не тот же, что был в этом слоте. */
	private Item pick(List<Item> pool, ItemStack previous) {
		Item item = pool.get(this.random.nextInt(pool.size()));
		for (int attempt = 0; attempt < 8 && !previous.isEmpty() && item == previous.getItem(); attempt++) {
			item = pool.get(this.random.nextInt(pool.size()));
		}
		return item;
	}

	/** Подсказка подписана ролью в лоре: имя предмета остаётся, слот объяснён второй строкой. */
	private static ItemStack hint(Item item, FletcherArrows.Role role) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.LORE, new ItemLore(List.of(
			Component.translatableWithFallback("fletcher.hint." + role.key(), "Сюда: " + role.label())
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC))));
		return stack;
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player player) {
		// ваниль пропускает сюда отрицательный индекс (-1/-999 — «вне окна») и для SWAP/THROW/CLONE
		// лезет в slots.get(slotIndex): такое сочетание роняет сервер с IndexOutOfBounds
		if (slotId < 0) {
			if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE || input == ContainerInput.QUICK_CRAFT) {
				super.clicked(slotId, button, input, player);
			}
			return;
		}
		if (slotId >= this.slots.size()) {
			return;
		}
		if (slotId == SLOT_OUTPUT) {
			if (input == ContainerInput.QUICK_MOVE) {
				takeOutput(player, true);
			} else if (input == ContainerInput.PICKUP) {
				takeOutput(player, false);
			}
			return;
		}
		if (slotId == SLOT_LOCKED) {
			return;
		}
		if (slotId < MATERIAL_SLOTS && this.fletcher.getItem(slotId).isEmpty()) {
			if (input == ContainerInput.SWAP) {
				// в слоте пусто, но показывается подсказка: ваниль считает слот занятым и не обменивает
				placeFromHotbar(slotId, button, player);
				return;
			}
			if (input == ContainerInput.CLONE) {
				// safeClone не спрашивает mayPickup и склонировал бы подсказку в 64 штуки
				return;
			}
		}
		super.clicked(slotId, button, input, player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index == SLOT_OUTPUT) {
			return takeOutput(player, true);
		}
		if (index == SLOT_LOCKED) {
			return ItemStack.EMPTY;
		}

		if (index >= INVENTORY_START) {
			ItemStack sourceStack = this.slots.get(index).getItem();
			if (sourceStack.isEmpty()) {
				return ItemStack.EMPTY;
			}
			ItemStack before = sourceStack.copy();
			int target = findMaterialTarget(sourceStack);
			if (target < 0) {
				return ItemStack.EMPTY;
			}
			// не через moveItemStackTo: пустой слот отдаёт подсказку, из-за чего ванильный перенос
			// считает его занятым (а при совпадении предмета записал бы подсказку в контейнер)
			ItemStack current = this.fletcher.getItem(target);
			if (current.isEmpty()) {
				int moved = Math.min(sourceStack.getCount(), this.fletcher.getMaxStackSize(sourceStack));
				ItemStack placing = sourceStack.split(moved);
				this.fletcher.setItem(target, placing);
				settleSlot(index, sourceStack);
				return before;
			}
			int space = this.fletcher.getMaxStackSize(current) - current.getCount();
			if (space <= 0) {
				return ItemStack.EMPTY;
			}
			int moved = Math.min(space, sourceStack.getCount());
			current.grow(moved);
			sourceStack.shrink(moved);
			this.fletcher.refreshOutput();
			settleSlot(index, sourceStack);
			return before.copyWithCount(moved);
		}

		// слот материалов → инвентарь; содержимое берём из контейнера, а не из слота:
		// слот может отдавать подсказку, её переносить нельзя
		ItemStack real = this.fletcher.getItem(index);
		if (real.isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack before = real.copy();
		if (!this.moveItemStackTo(real, INVENTORY_START, this.slots.size(), true)) {
			return ItemStack.EMPTY;
		}
		settleSlot(index, real);
		return before;
	}

	private void settleSlot(int index, ItemStack stack) {
		Slot slot = this.slots.get(index);
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
	}

	/** Цель для shift-click: единственный слот роли, с доливом в существующую стопку. */
	private int findMaterialTarget(ItemStack stack) {
		FletcherArrows.Role role = FletcherArrows.roleOf(stack);
		if (role == null) {
			return -1;
		}
		int slot = role.slot();
		ItemStack current = this.fletcher.getItem(slot);
		if (current.isEmpty()) {
			return slot;
		}
		if (ItemStack.isSameItemSameComponents(current, stack)
			&& current.getCount() < this.fletcher.getMaxStackSize(current)) {
			return slot;
		}
		return -1;
	}

	/** F/клавиша слота: обмен с ячейкой хотбара (0..8) или со второй рукой (40), только своей роли. */
	private void placeFromHotbar(int slot, int button, Player player) {
		if (button < 0 || (button > 8 && button != 40)) {
			return;
		}
		Inventory inventory = player.getInventory();
		ItemStack source = inventory.getItem(button);
		if (source.isEmpty() || FletcherArrows.roleOf(source) != FletcherArrows.roleForSlot(slot)) {
			return;
		}
		int max = this.fletcher.getMaxStackSize(source);
		boolean whole = source.getCount() <= max;
		ItemStack placed = whole ? source.copy() : source.split(max);
		if (whole) {
			inventory.setItem(button, ItemStack.EMPTY);
		}
		this.fletcher.setItem(slot, placed);
		this.broadcastChanges();
	}

	/**
	 * Выдать готовую стрелу и списать по одной штуке каждой роли.
	 * Единственный путь получить результат, поэтому дюп сводится к вопросу
	 * «сколько раз этот метод вызвался».
	 */
	private ItemStack takeOutput(Player player, boolean intoInventory) {
		if (!this.fletcher.hasOutput()) {
			return ItemStack.EMPTY;
		}
		ItemStack giving = this.fletcher.getItem(SLOT_OUTPUT).copy();

		if (intoInventory) {
			if (!player.getInventory().add(giving) && !giving.isEmpty()) {
				player.drop(giving, false);
			}
		} else {
			if (!this.getCarried().isEmpty()) {
				return ItemStack.EMPTY;
			}
			this.setCarried(giving);
		}

		this.fletcher.consumeInputs();
		if (player.level() instanceof ServerLevel level) {
			level.playSound(null, this.tablePos, SoundEvents.VILLAGER_WORK_FLETCHER, SoundSource.BLOCKS, 0.9F, 1.1F);
		}
		// освободившиеся слоты сразу получают подсказку, иначе они до 3 секунд выглядят пустыми
		refreshPreviews();
		this.broadcastChanges();
		return giving;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		for (int i = 0; i < MATERIAL_SLOTS; i++) {
			ItemStack stack = this.fletcher.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			ItemStack copy = stack.copy();
			this.fletcher.setItem(i, ItemStack.EMPTY);
			if (!player.getInventory().add(copy) && !copy.isEmpty()) {
				player.drop(copy, false);
			}
		}
		this.fletcher.clearContent();
	}

	@Override
	public boolean stillValid(Player player) {
		return player.level().getBlockState(this.tablePos).is(Blocks.FLETCHING_TABLE)
			&& player.isWithinBlockInteractionRange(this.tablePos, 4.0);
	}

	/** Слот материала: настоящий предмет, иначе подсказка (и только при пустой руке). */
	private class PreviewSlot extends Slot {
		PreviewSlot(int slot, int x, int y) {
			super(FletcherMenu.this.fletcher, slot, x, y);
		}

		@Override
		public ItemStack getItem() {
			ItemStack real = FletcherMenu.this.fletcher.getItem(this.getContainerSlot());
			if (!real.isEmpty()) {
				return real;
			}
			if (!FletcherMenu.this.getCarried().isEmpty()) {
				// рука занята: притворяемся пустым слотом, чтобы safeInsert/драг писали в контейнер
				return ItemStack.EMPTY;
			}
			return FletcherMenu.this.fletcher.getPreview(this.getContainerSlot());
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return FletcherArrows.roleOf(stack) == FletcherArrows.roleForSlot(this.getContainerSlot());
		}

		@Override
		public boolean mayPickup(Player player) {
			return !FletcherMenu.this.fletcher.getItem(this.getContainerSlot()).isEmpty();
		}

		@Override
		public boolean allowModification(Player player) {
			return this.mayPickup(player);
		}
	}

	private static class LockedSlot extends Slot {
		LockedSlot(FletcherInventory inventory, int slot, int x, int y) {
			super(inventory, slot, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}

		@Override
		public boolean allowModification(Player player) {
			return false;
		}
	}

	private static class OutputSlot extends Slot {
		OutputSlot(FletcherInventory inventory, int slot, int x, int y) {
			super(inventory, slot, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}

		@Override
		public boolean allowModification(Player player) {
			return false;
		}

		@Override
		public ItemStack remove(int amount) {
			return ItemStack.EMPTY;
		}
	}
}
