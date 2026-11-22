package fletcher;

import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.ArrayList;
import java.util.List;

/**
 * Колчан: обычный {@code minecraft:bundle} с маркером в {@code custom_data}. Свой предмет серверный
 * мод завести не может (клиент не знает id и нарисует missing-текстуру), поэтому содержимое лежит
 * в штатном компоненте {@link DataComponents#CONTAINER}: пять слотов по стаку стрел.
 * <p>
 * Активный слот — число в {@code custom_data.fletcher_quiver.active}; его выбирают ПКМ в меню.
 * Имя «Колчан» и лор пересобираются при каждом изменении. Строка {@code quiver} в
 * {@link DataComponents#CUSTOM_MODEL_DATA} — точка входа для ресурспака: пак может подменить модель
 * мешка только у колчана, а без пака предмет выглядит обычным мешком.
 */
public final class Quiver {
	public static final String ROOT = "fletcher_quiver";
	public static final String KEY_ACTIVE = "active";
	private static final String KEY_QUIVER = "quiver";
	/** Маркер на копии стрелы, которую получает оружие: в каком слоте хотбара лежит колчан. */
	private static final String SOURCE_ROOT = "fletcher_quiver_source";
	private static final String KEY_SOURCE_SLOT = "slot";
	private static final String MODEL = "quiver";
	public static final int SLOTS = 5;
	public static final String NAME = "Колчан";

	private Quiver() {
	}

	/** Колчан, найденный в хотбаре, и его выбранная стрела. */
	public record HotbarRef(int slot, ItemStack quiver, ItemStack selected) {
	}

	public static boolean isQuiver(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BundleItem)) {
			return false;
		}
		CompoundTag tag = rootOf(stack);
		return tag != null && tag.getBooleanOr(KEY_QUIVER, false);
	}

	/** Можно ли превратить мешок в колчан: пустой мешок (в том числе крашеный), ещё не колчан. */
	public static boolean canConvert(ItemStack stack) {
		if (stack.isEmpty() || stack.getCount() != 1 || !(stack.getItem() instanceof BundleItem)
			|| isQuiver(stack)) {
			return false;
		}
		BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
		return contents == null || contents.isEmpty();
	}

	/** Новый пустой колчан: всегда обычный мешок, чтобы ресурспаку хватило одной модели. */
	public static ItemStack create() {
		ItemStack quiver = new ItemStack(Items.BUNDLE);

		CompoundTag tag = new CompoundTag();
		tag.putBoolean(KEY_QUIVER, true);
		tag.putInt(KEY_ACTIVE, 0);
		CustomData.update(DataComponents.CUSTOM_DATA, quiver, root -> root.put(ROOT, tag));

		quiver.set(DataComponents.ITEM_NAME, Component.literal(NAME).withStyle(ChatFormatting.GOLD));
		// содержимое показываем своим лором, а не ванильными «Has N×…» из компонента container
		quiver.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT
			.withHidden(DataComponents.CONTAINER, true)
			.withHidden(DataComponents.BUNDLE_CONTENTS, true));
		quiver.set(DataComponents.CUSTOM_MODEL_DATA,
			new CustomModelData(List.of(), List.of(), List.of(MODEL), List.of()));
		updateLore(quiver);
		return quiver;
	}

	/** Пять слотов содержимого; пустые — {@link ItemStack#EMPTY}. */
	public static NonNullList<ItemStack> contents(ItemStack quiver) {
		NonNullList<ItemStack> list = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
		ItemContainerContents stored = quiver.get(DataComponents.CONTAINER);
		if (stored != null) {
			stored.copyInto(list);
		}
		return list;
	}

	/** Индекс активного слота, всегда 0..{@link #SLOTS}-1. */
	public static int active(ItemStack quiver) {
		CompoundTag tag = rootOf(quiver);
		int index = tag == null ? 0 : tag.getIntOr(KEY_ACTIVE, 0);
		return index >= 0 && index < SLOTS ? index : 0;
	}

	public static void setActive(ItemStack quiver, int index) {
		int clamped = Math.max(0, Math.min(SLOTS - 1, index));
		CompoundTag tag = rootOf(quiver);
		if (tag == null) {
			tag = new CompoundTag();
			tag.putBoolean(KEY_QUIVER, true);
		}
		tag.putInt(KEY_ACTIVE, clamped);
		CompoundTag values = tag;
		CustomData.update(DataComponents.CUSTOM_DATA, quiver, root -> root.put(ROOT, values));
		updateLore(quiver);
	}

	/** Выбранная стрела (может быть пустой). */
	public static ItemStack selected(ItemStack quiver) {
		return contents(quiver).get(active(quiver));
	}

	/** Записать содержимое и активный слот в предмет, обновив имя/лор. */
	public static void write(ItemStack quiver, List<ItemStack> items, int active) {
		quiver.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
		CompoundTag tag = rootOf(quiver);
		if (tag == null) {
			tag = new CompoundTag();
			tag.putBoolean(KEY_QUIVER, true);
		}
		tag.putInt(KEY_ACTIVE, Math.max(0, Math.min(SLOTS - 1, active)));
		CompoundTag values = tag;
		CustomData.update(DataComponents.CUSTOM_DATA, quiver, root -> root.put(ROOT, values));
		updateLore(quiver);
	}

	/**
	 * Первый колчан в хотбаре (0..8), у которого в активном слоте есть стрела. Колчан в основном
	 * инвентаре не работает: так просил владелец.
	 */
	public static HotbarRef findActive(Player player) {
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!isQuiver(stack)) {
				continue;
			}
			ItemStack selected = selected(stack);
			if (!selected.isEmpty() && selected.is(ItemTags.ARROWS)) {
				return new HotbarRef(slot, stack, selected);
			}
		}
		return null;
	}

	/** Списать одну стрелу из активного слота колчана в указанном слоте хотбара. */
	public static void consumeAt(Player player, int hotbarSlot) {
		ItemStack quiver = player.getInventory().getItem(hotbarSlot);
		if (!isQuiver(quiver)) {
			return;
		}
		NonNullList<ItemStack> items = contents(quiver);
		int index = active(quiver);
		ItemStack selected = items.get(index);
		if (selected.isEmpty()) {
			return;
		}
		selected.shrink(1);
		if (selected.isEmpty()) {
			items.set(index, ItemStack.EMPTY);
		}
		write(quiver, items, index);
	}

	/** Копия стрелы для оружия с временной пометкой слота хотбара. */
	public static ItemStack mark(ItemStack arrow, int hotbarSlot) {
		ItemStack copy = arrow.copy();
		CustomData.update(DataComponents.CUSTOM_DATA, copy, root -> {
			CompoundTag tag = new CompoundTag();
			tag.putInt(KEY_SOURCE_SLOT, hotbarSlot);
			root.put(SOURCE_ROOT, tag);
		});
		return copy;
	}

	/** Слот хотбара из пометки; -1 — стрела не из колчана. */
	public static int sourceSlot(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return -1;
		}
		CompoundTag root = data.copyTag();
		if (!root.contains(SOURCE_ROOT)) {
			return -1;
		}
		return root.getCompoundOrEmpty(SOURCE_ROOT).getIntOr(KEY_SOURCE_SLOT, -1);
	}

	/** Снять пометку, чтобы подобранная стрела складывалась с обычными. */
	public static void unmark(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return;
		}
		CompoundTag root = data.copyTag();
		if (!root.contains(SOURCE_ROOT)) {
			return;
		}
		root.remove(SOURCE_ROOT);
		if (root.isEmpty()) {
			stack.remove(DataComponents.CUSTOM_DATA);
		} else {
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		}
	}

	/** Имя/лор: активная стрела и заполненность. */
	private static void updateLore(ItemStack quiver) {
		NonNullList<ItemStack> items = contents(quiver);
		int index = active(quiver);
		ItemStack current = items.get(index);

		List<Component> lore = new ArrayList<>();
		lore.add(Component.literal(current.isEmpty()
			? "Активная стрела: —"
			: "Активная: " + current.getHoverName().getString() + " ×" + current.getCount())
			.withStyle(ChatFormatting.GRAY));
		lore.add(Component.literal("Стрелы: " + filled(items) + "/" + SLOTS).withStyle(ChatFormatting.DARK_GRAY));
		quiver.set(DataComponents.LORE, new ItemLore(lore));
	}

	private static int filled(List<ItemStack> items) {
		int count = 0;
		for (ItemStack stack : items) {
			if (!stack.isEmpty()) {
				count++;
			}
		}
		return count;
	}

	private static CompoundTag rootOf(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		CompoundTag root = data.copyTag();
		return root.contains(ROOT) ? root.getCompoundOrEmpty(ROOT) : null;
	}
}
