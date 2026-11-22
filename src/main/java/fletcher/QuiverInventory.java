package fletcher;

import net.minecraft.core.NonNullList;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Пять слотов колчана. Живёт только пока открыто меню, а источник истины — сам предмет:
 * каждый {@link #setChanged()} пишет содержимое и активный слот обратно в
 * {@link net.minecraft.core.component.DataComponents#CONTAINER}. Поэтому закрытие экрана,
 * перезаход и снятие предмета ничего не теряют.
 */
public class QuiverInventory implements Container {
	private final ItemStack quiver;
	private final NonNullList<ItemStack> items;
	private int active;

	public QuiverInventory(ItemStack quiver) {
		this.quiver = quiver;
		this.items = Quiver.contents(quiver);
		this.active = Quiver.active(quiver);
	}

	public ItemStack quiver() {
		return this.quiver;
	}

	/**
	 * Перечитать предмет: содержимое могли изменить в обход меню (выстрел, команда, другой мод).
	 * Вызывается перед каждым кликом и синхронизацией, чтобы старый снимок не «воскресил» стрелы.
	 */
	public void reload() {
		NonNullList<ItemStack> fresh = Quiver.contents(this.quiver);
		int freshActive = Quiver.active(this.quiver);
		boolean changed = freshActive != this.active;
		for (int i = 0; i < Quiver.SLOTS && !changed; i++) {
			changed = !ItemStack.matches(this.items.get(i), fresh.get(i));
		}
		if (changed) {
			for (int i = 0; i < Quiver.SLOTS; i++) {
				this.items.set(i, fresh.get(i));
			}
			this.active = freshActive;
		}
	}

	public int active() {
		return this.active;
	}

	public void setActive(int index) {
		this.active = Math.max(0, Math.min(Quiver.SLOTS - 1, index));
		setChanged();
	}

	@Override
	public int getContainerSize() {
		return Quiver.SLOTS;
	}

	@Override
	public boolean isEmpty() {
		for (ItemStack stack : this.items) {
			if (!stack.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ItemStack getItem(int slot) {
		return this.items.get(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		ItemStack stack = this.items.get(slot);
		ItemStack taken = stack.split(amount);
		// split() оставляет в списке стак с count=0; ваниль в таком случае кладёт EMPTY
		if (stack.isEmpty()) {
			this.items.set(slot, ItemStack.EMPTY);
		}
		if (!taken.isEmpty()) {
			setChanged();
		}
		return taken;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		ItemStack stack = this.items.get(slot);
		this.items.set(slot, ItemStack.EMPTY);
		setChanged();
		return stack;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		this.items.set(slot, stack);
		if (!stack.isEmpty() && stack.getCount() > getMaxStackSize(stack)) {
			stack.setCount(getMaxStackSize(stack));
		}
		setChanged();
	}

	@Override
	public int getMaxStackSize() {
		return 64;
	}

	@Override
	public int getMaxStackSize(ItemStack stack) {
		return Math.min(stack.getItem().getDefaultMaxStackSize(), getMaxStackSize());
	}

	/** В колчан кладутся только стрелы; мусор не принимает ни клик, ни shift-click, ни драг. */
	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return !stack.isEmpty() && stack.is(ItemTags.ARROWS);
	}

	@Override
	public void setChanged() {
		Quiver.write(this.quiver, this.items, this.active);
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public void clearContent() {
		for (int i = 0; i < this.items.size(); i++) {
			this.items.set(i, ItemStack.EMPTY);
		}
		setChanged();
	}
}
