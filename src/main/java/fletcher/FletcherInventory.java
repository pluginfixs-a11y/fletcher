package fletcher;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Контейнер стола лучника: три слота материалов, слот добавки, слот результата.
 * <p>
 * Порядок жёсткий: 0 — оперение, 1 — древко, 2 — наконечник, 3 — добавка (порох).
 * Слот принимает только свою роль/добавку ({@link #canPlaceItem}), поэтому чужую часть туда
 * не положить ни кликом, ни shift-click'ом, ни драгом.
 * <p>
 * Пустые слоты материалов показывают подсказку: случайный предмет своей роли. Подсказка живёт
 * только в слоте отображения и никогда не попадает в контейнер, поэтому её нельзя ни забрать,
 * ни дюпнуть. Результат тоже не хранится как источник истины, а пересчитывается из входов.
 */
public class FletcherInventory implements Container {
	public static final int SLOT_FEATHER = 0;
	public static final int SLOT_SHAFT = 1;
	public static final int SLOT_HEAD = 2;
	public static final int SLOT_ADDON = 3;
	public static final int SLOT_INPUTS = 4;
	public static final int SLOT_OUTPUT = 4;
	public static final int SIZE = 5;

	private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
	/** Подсказки для слотов 0..3; на реальное содержимое не влияют. */
	private final ItemStack[] previews = {
		ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY
	};

	@Override
	public int getContainerSize() {
		return SIZE;
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < SLOT_INPUTS; i++) {
			if (!items.get(i).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Настоящее содержимое слота, без подсказок. */
	@Override
	public ItemStack getItem(int slot) {
		return items.get(slot);
	}

	/** Подсказка слота материалов/добавки (пустой стек, если подсказки нет). */
	public ItemStack getPreview(int slot) {
		return slot >= 0 && slot < SLOT_INPUTS ? previews[slot] : ItemStack.EMPTY;
	}

	public void setPreview(int slot, ItemStack stack) {
		if (slot >= 0 && slot < SLOT_INPUTS) {
			previews[slot] = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
		}
	}

	public void clearPreview(int slot) {
		setPreview(slot, ItemStack.EMPTY);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		if (slot == SLOT_OUTPUT) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = items.get(slot);
		ItemStack taken = stack.split(amount);
		if (!taken.isEmpty()) {
			refreshOutput();
		}
		return taken;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		if (slot == SLOT_OUTPUT) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = items.get(slot);
		items.set(slot, ItemStack.EMPTY);
		refreshOutput();
		return stack;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		if (slot == SLOT_OUTPUT) {
			return;
		}
		items.set(slot, stack);
		if (!stack.isEmpty()) {
			clearPreview(slot);
			if (stack.getCount() > getMaxStackSize(stack)) {
				stack.setCount(getMaxStackSize(stack));
			}
		}
		refreshOutput();
	}

	@Override
	public int getMaxStackSize() {
		return 64;
	}

	@Override
	public int getMaxStackSize(ItemStack stack) {
		return Math.min(stack.getItem().getDefaultMaxStackSize(), getMaxStackSize());
	}

	/** Слот принимает только свою роль: 0 — оперение, 1 — древко, 2 — наконечник, 3 — добавка. */
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		if (slot == SLOT_ADDON) {
			return FletcherArrows.isAddon(stack);
		}
		return FletcherArrows.roleForSlot(slot) == FletcherArrows.roleOf(stack);
	}

	@Override
	public void setChanged() {
		// ваниль зовёт это на каждый клик, в том числе по инвентарю игрока; пересчёт дешёвый
		refreshOutput();
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public void clearContent() {
		for (int i = 0; i < SIZE; i++) {
			items.set(i, ItemStack.EMPTY);
		}
		for (int i = 0; i < previews.length; i++) {
			previews[i] = ItemStack.EMPTY;
		}
	}

	/** Пересчитать витрину из четырёх входов: три материала и необязательная добавка. */
	public void refreshOutput() {
		ItemStack computed = FletcherArrows.build(
			items.get(SLOT_FEATHER), items.get(SLOT_SHAFT), items.get(SLOT_HEAD), items.get(SLOT_ADDON)
		);
		if (!ItemStack.matches(items.get(SLOT_OUTPUT), computed)) {
			items.set(SLOT_OUTPUT, computed);
		}
	}

	/**
	 * Забрать по одной штуке из каждого обязательного слота и добавки, если она есть.
	 * Вызывается только после того, как результат реально ушёл игроку.
	 */
	public void consumeInputs() {
		if (items.get(SLOT_OUTPUT).isEmpty()) {
			return;
		}
		for (int i = 0; i < SLOT_ADDON; i++) {
			if (items.get(i).isEmpty()) {
				return;
			}
		}
		for (int i = 0; i < SLOT_ADDON; i++) {
			ItemStack stack = items.get(i);
			stack.shrink(1);
			if (stack.isEmpty()) {
				items.set(i, ItemStack.EMPTY);
			}
		}
		ItemStack addon = items.get(SLOT_ADDON);
		if (!addon.isEmpty()) {
			addon.shrink(1);
			if (addon.isEmpty()) {
				items.set(SLOT_ADDON, ItemStack.EMPTY);
			}
		}
		refreshOutput();
	}

	public boolean hasOutput() {
		return !items.get(SLOT_OUTPUT).isEmpty();
	}
}
