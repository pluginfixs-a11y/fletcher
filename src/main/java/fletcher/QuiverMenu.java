package fletcher;

import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Меню колчана — ванильный экран воронки, как у стола лучника: свой {@code MenuType} серверный мод
 * показать не может. Пять слотов контейнера заменены настоящими слотами колчана (не подсказками).
 * <p>
 * Управление: ПКМ по слоту выбирает активную стрелу (содержимое не трогается), ЛКМ работает как
 * в ванили, shift-click переносит стак в инвентарь и обратно. Поэтому {@code Slot#index} обязан
 * совпадать с номером слота: заменённые слоты иначе получают 0, и одиночный драг уходит не туда.
 */
public class QuiverMenu extends HopperMenu {
	private static final int SLOTS = Quiver.SLOTS;
	private static final int INVENTORY_START = 5;

	private final QuiverInventory quiverInventory;

	public QuiverMenu(int syncId, Inventory playerInventory, QuiverInventory inventory) {
		super(syncId, playerInventory, inventory);
		this.quiverInventory = inventory;

		for (int i = 0; i < SLOTS; i++) {
			Slot slot = new QuiverSlot(inventory, i, 44 + i * 18, 20);
			slot.index = i;
			this.slots.set(i, slot);
		}
	}

	@Override
	public void clicked(int slotId, int button, ContainerInput input, Player player) {
		this.quiverInventory.reload();
		// ваниль пускает сюда отрицательный индекс и для SWAP/THROW/CLONE лезет в slots.get(-1)
		if (slotId < 0) {
			if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE
				|| input == ContainerInput.QUICK_CRAFT) {
				super.clicked(slotId, button, input, player);
			}
			return;
		}
		if (slotId >= this.slots.size()) {
			return;
		}
		// ПКМ по слоту колчана — только выбор активной стрелы, ничего не перекладываем
		if (slotId < SLOTS && input == ContainerInput.PICKUP && button == 1) {
			this.quiverInventory.setActive(slotId);
			player.sendOverlayMessage(
				Component.literal("Колчан: активная стрела — " + describe(slotId)));
			this.broadcastChanges();
			return;
		}
		super.clicked(slotId, button, input, player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index < 0 || index >= this.slots.size()) {
			return ItemStack.EMPTY;
		}
		if (index < SLOTS) {
			// из колчана в инвентарь; содержимое берём из контейнера, а не из слота
			ItemStack real = this.quiverInventory.getItem(index);
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

		// из инвентаря в колчан: ваниль сама проверит mayPlace (только стрелы) и дольёт стак
		ItemStack source = this.slots.get(index).getItem();
		if (source.isEmpty() || !source.is(ItemTags.ARROWS)) {
			return ItemStack.EMPTY;
		}
		ItemStack before = source.copy();
		if (!this.moveItemStackTo(source, 0, SLOTS, false)) {
			return ItemStack.EMPTY;
		}
		settleSlot(index, source);
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

	@Override
	public void removed(Player player) {
		super.removed(player);
		// финальное состояние — в предмет; стрелы остаются в нём, а не в меню
		this.quiverInventory.reload();
		this.quiverInventory.setChanged();
	}

	@Override
	public void broadcastChanges() {
		this.quiverInventory.reload();
		super.broadcastChanges();
	}

	@Override
	public boolean stillValid(Player player) {
		// колчан всё ещё в инвентаре (или на курсоре): иначе меню закрывается и пишет в пустоту
		if (this.getCarried() == this.quiverInventory.quiver()) {
			return true;
		}
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (player.getInventory().getItem(i) == this.quiverInventory.quiver()) {
				return true;
			}
		}
		return false;
	}

	private String describe(int slot) {
		ItemStack stack = this.quiverInventory.getItem(slot);
		return stack.isEmpty() ? "—" : stack.getHoverName().getString() + " ×" + stack.getCount();
	}

	/**
	 * В 26.2 {@code Slot#mayPlace} по умолчанию возвращает {@code true} и контейнер не спрашивает,
	 * поэтому «только стрелы» надо продублировать здесь: иначе клик/драг/долив кладут в колчан что
	 * угодно, а {@link QuiverInventory#canPlaceItem} остаётся декорацией.
	 */
	private static class QuiverSlot extends Slot {
		QuiverSlot(QuiverInventory inventory, int slot, int x, int y) {
			super(inventory, slot, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return this.container.canPlaceItem(this.getContainerSlot(), stack);
		}
	}
}
