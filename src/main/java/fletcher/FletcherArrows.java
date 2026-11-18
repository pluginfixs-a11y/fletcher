package fletcher;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Стрела собирается из трёх частей: оперения, древка и наконечника.
 * <p>
 * Порядок жёсткий и совпадает со слотами стола: 0 — оперение, 1 — древко, 2 — наконечник
 * ({@link Role#slot()}). Роль предмета выводится из самого предмета, поэтому слот не примет
 * чужую часть, даже если её туда положат обходным путём.
 * <p>
 * Свойств у готовой стрелы три, и все они живут в {@code custom_data} стека:
 * <ul>
 *   <li>{@code drag} — коэффициент инерции в воздухе (ваниль 0.99); больше = дальше полёт;</li>
 *   <li>{@code damage} — базовый урон (ваниль 2.0); фактический урон = скорость × база;</li>
 *   <li>{@code pierce} — сколько целей пробивает (0..3).</li>
 * </ul>
 * Свой предмет-стрелу server-side мод завести не может: ванильный клиент не знает id и нарисует
 * missing-текстуру. Поэтому всё лежит в компонентах обычной {@code minecraft:arrow} — имя и лор
 * клиент покажет сам, а цифры прочитает миксин на сервере.
 * <p>
 * Читаемые из NBT числа зажимаются: стрелу с {@code custom_data.fletcher} можно получить не только
 * со стола (креатив, {@code /give}, чужая сборка), и без зажима любая такая вещь давала бы
 * бесконечную дальность и ваншот.
 */
public final class FletcherArrows {
	public static final String NBT_ROOT = "fletcher";
	public static final String KEY_DRAG = "drag";
	public static final String KEY_DAMAGE = "damage";
	public static final String KEY_PIERCE = "pierce";

	/** Ванильный коэффициент инерции стрелы: {@code AbstractArrow.getAirDrag()}. */
	public static final float BASE_DRAG = 0.99F;
	/** Ванильный базовый урон стрелы: {@code AbstractArrow.ARROW_BASE_DAMAGE}. */
	public static final float BASE_DAMAGE = 2.0F;
	public static final int ARROWS_PER_CRAFT = 4;

	private static final float MIN_DRAG = 0.975F;
	private static final float MAX_DRAG = 0.997F;
	private static final int MAX_PIERCE = 3;
	private static final float MIN_DAMAGE = 0.5F;
	/**
	 * Потолок для чтения из NBT: ровно столько даёт лучший набор (кожа + огненный стержень + алмаз =
	 * 2.0 + 1.0 + 1.0 + 2.5). Меньше нельзя — честная стрела обрезалась бы на выстреле, больше нельзя —
	 * подделка снова стала бы ваншотом. Сверяется в самопроверке, чтобы не разъехалось с таблицами.
	 */
	private static final float MAX_READ_DAMAGE = 6.5F;

	private static final Map<Item, Part> FEATHERS = new HashMap<>();
	private static final Map<Item, Part> SHAFTS = new HashMap<>();
	private static final Map<Item, Part> HEADS = new HashMap<>();
	private static final List<Item> FEATHER_ITEMS;
	private static final List<Item> SHAFT_ITEMS;
	private static final List<Item> HEAD_ITEMS;
	/** Всё, что вообще принимает стол: для подсказок в пустых слотах. */
	private static final List<Item> POOL;

	static {
		// оперение: дальность и урон
		FEATHERS.put(Items.FEATHER, new Part(0.0F, 0.0010F, 0, "перо"));
		FEATHERS.put(Items.PAPER, new Part(-1.0F, 0.0025F, 0, "бумага"));
		FEATHERS.put(Items.LEATHER, new Part(1.0F, 0.0003F, 0, "кожа"));
		FEATHERS.put(Items.PHANTOM_MEMBRANE, new Part(0.0F, 0.0040F, 0, "мембрана"));

		// древко: скорость и вес
		SHAFTS.put(Items.STICK, new Part(0.0F, 0.0F, 0, "палка"));
		SHAFTS.put(Items.BAMBOO, new Part(-1.0F, 0.0015F, 0, "бамбук"));
		SHAFTS.put(Items.BLAZE_ROD, new Part(1.0F, -0.0015F, 0, "огненный стержень"));
		SHAFTS.put(Items.BONE, new Part(0.5F, 0.0005F, 0, "кость"));

		// наконечник: урон и пробивание, всё мелкое
		HEADS.put(Items.FLINT, new Part(0.0F, 0.0F, 0, "кремень"));
		HEADS.put(Items.COPPER_NUGGET, new Part(0.5F, 0.0F, 0, "медь"));
		HEADS.put(Items.IRON_NUGGET, new Part(1.0F, 0.0F, 1, "железо"));
		HEADS.put(Items.GOLD_NUGGET, new Part(1.0F, 0.0F, 2, "золото"));
		HEADS.put(Items.AMETHYST_SHARD, new Part(1.0F, 0.0020F, 0, "аметист"));
		HEADS.put(Items.DIAMOND, new Part(2.5F, 0.0F, 2, "алмаз"));

		FEATHER_ITEMS = List.copyOf(FEATHERS.keySet());
		SHAFT_ITEMS = List.copyOf(SHAFTS.keySet());
		HEAD_ITEMS = List.copyOf(HEADS.keySet());

		List<Item> all = new ArrayList<>();
		all.addAll(FEATHER_ITEMS);
		all.addAll(SHAFT_ITEMS);
		all.addAll(HEAD_ITEMS);
		POOL = List.copyOf(all);
	}

	private FletcherArrows() {
	}

	public record Part(float damage, float drag, int pierce, String label) {
	}

	/**
	 * Роль части и её слот в столе. Номера совпадают с {@code FletcherInventory.SLOT_*}:
	 * 0 — оперение, 1 — древко, 2 — наконечник.
	 */
	public enum Role {
		FEATHER(0, "feather", "оперение"),
		SHAFT(1, "shaft", "древко"),
		HEAD(2, "head", "наконечник");

		private final int slot;
		private final String key;
		private final String label;

		Role(int slot, String key, String label) {
			this.slot = slot;
			this.key = key;
			this.label = label;
		}

		public int slot() {
			return this.slot;
		}

		public String key() {
			return this.key;
		}

		public String label() {
			return this.label;
		}
	}

	/** Роль предмета в рецепте; {@code null} — стол его не использует. */
	public static Role roleOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		Item item = stack.getItem();
		if (FEATHERS.containsKey(item)) {
			return Role.FEATHER;
		}
		if (SHAFTS.containsKey(item)) {
			return Role.SHAFT;
		}
		if (HEADS.containsKey(item)) {
			return Role.HEAD;
		}
		return null;
	}

	/** Роль, которой принадлежит слот 0..2; для остальных — {@code null}. */
	public static Role roleForSlot(int slot) {
		for (Role role : Role.values()) {
			if (role.slot() == slot) {
				return role;
			}
		}
		return null;
	}

	public static Part partOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		Item item = stack.getItem();
		Part part = FEATHERS.get(item);
		if (part == null) {
			part = SHAFTS.get(item);
		}
		if (part == null) {
			part = HEADS.get(item);
		}
		return part;
	}

	public static List<Item> pool() {
		return POOL;
	}

	/** Предметы одной роли — для подсказок в её слоте. */
	public static List<Item> itemsFor(Role role) {
		return switch (role) {
			case FEATHER -> FEATHER_ITEMS;
			case SHAFT -> SHAFT_ITEMS;
			case HEAD -> HEAD_ITEMS;
		};
	}

	/**
	 * Собирает стрелу из трёх слотов по порядку: оперение, древко, наконечник.
	 * Любой пустой или чужой слот — {@link ItemStack#EMPTY}.
	 */
	public static ItemStack build(ItemStack featherStack, ItemStack shaftStack, ItemStack headStack) {
		Part feather = FEATHERS.get(featherStack.getItem());
		Part shaft = SHAFTS.get(shaftStack.getItem());
		Part head = HEADS.get(headStack.getItem());
		if (feather == null || shaft == null || head == null) {
			return ItemStack.EMPTY;
		}

		float drag = Mth.clamp(BASE_DRAG + feather.drag() + shaft.drag() + head.drag(), MIN_DRAG, MAX_DRAG);
		float damage = Math.min(MAX_READ_DAMAGE,
			Math.max(MIN_DAMAGE, BASE_DAMAGE + feather.damage() + shaft.damage() + head.damage()));
		int pierce = Mth.clamp(feather.pierce() + shaft.pierce() + head.pierce(), 0, MAX_PIERCE);

		ItemStack out = new ItemStack(Items.ARROW, ARROWS_PER_CRAFT);

		CompoundTag tag = new CompoundTag();
		tag.putFloat(KEY_DRAG, drag);
		tag.putFloat(KEY_DAMAGE, damage);
		tag.putInt(KEY_PIERCE, pierce);
		CustomData.update(DataComponents.CUSTOM_DATA, out, root -> root.put(NBT_ROOT, tag));

		out.set(DataComponents.CUSTOM_NAME, Component.literal("Стрела · " + head.label() + " / " + feather.label())
			.withStyle(head.pierce() > 0 ? ChatFormatting.AQUA : ChatFormatting.WHITE));
		out.set(DataComponents.LORE, new ItemLore(List.of(
			Component.literal("Урон: " + damagePercent(damage)).withStyle(ChatFormatting.GRAY),
			Component.literal("Дальность: " + rangePercent(drag)).withStyle(ChatFormatting.GRAY),
			Component.literal("Пробивание: " + pierce).withStyle(ChatFormatting.GRAY)
		)));
		if (pierce > 0) {
			out.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
		}
		return out;
	}

	/** Коэффициент инерции конкретной стрелы; 0 — если стрела ванильная. */
	public static float dragOf(ItemStack arrow) {
		CompoundTag tag = dataOf(arrow);
		if (tag == null) {
			return 0.0F;
		}
		float drag = tag.getFloatOr(KEY_DRAG, 0.0F);
		// NaN проскочил бы и через clamp (сравнения с ним ложны), а инерция-NaN — это стрела,
		// которая никогда не замедляется; поэтому нечисловые значения считаем отсутствующими
		if (!Float.isFinite(drag) || drag <= 0.0F) {
			return 0.0F;
		}
		return Mth.clamp(drag, MIN_DRAG, MAX_DRAG);
	}

	/** Абсолютный базовый урон стрелы; у ванильной — {@link #BASE_DAMAGE}. */
	public static float baseDamageOf(ItemStack arrow) {
		CompoundTag tag = dataOf(arrow);
		if (tag == null) {
			return BASE_DAMAGE;
		}
		float damage = tag.getFloatOr(KEY_DAMAGE, BASE_DAMAGE);
		// NaN/Infinity из NBT иначе доезжают до setBaseDamage: у жертвы становится NaN здоровье
		if (!Float.isFinite(damage)) {
			return BASE_DAMAGE;
		}
		return Mth.clamp(damage, MIN_DAMAGE, MAX_READ_DAMAGE);
	}

	public static int pierceOf(ItemStack arrow) {
		CompoundTag tag = dataOf(arrow);
		if (tag == null) {
			return 0;
		}
		return Mth.clamp(tag.getIntOr(KEY_PIERCE, 0), 0, MAX_PIERCE);
	}

	/** Наша ли это стрела (маркер {@code fletcher} в custom_data). */
	public static boolean isFletcher(ItemStack arrow) {
		return dataOf(arrow) != null;
	}

	private static CompoundTag dataOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		CompoundTag root = data.copyTag();
		return root.contains(NBT_ROOT) ? root.getCompoundOrEmpty(NBT_ROOT) : null;
	}

	/**
	 * Отношение дальности к ванильной стреле. Числа — из прогона полёта (инерция, скорость 3.0 блока/тик,
	 * гравитация 0.05, лучший угол), а не из формулы без гравитации: та давала +200 % там, где
	 * на самом деле +30 %. Между точками — линейная интерполяция.
	 */
	private static final float[] RANGE_DRAG = {0.975F, 0.980F, 0.985F, 0.9885F, 0.990F, 0.991F, 0.9925F, 0.9933F, 0.9955F, 0.997F};
	private static final int[] RANGE_PCT = {-36, -27, -16, -5, 0, 4, 10, 14, 26, 35};

	/** Дальность в процентах от ванильной стрелы. */
	private static String rangePercent(float drag) {
		float clamped = Mth.clamp(drag, RANGE_DRAG[0], RANGE_DRAG[RANGE_DRAG.length - 1]);
		int pct = RANGE_PCT[0];
		for (int i = 1; i < RANGE_DRAG.length; i++) {
			if (clamped <= RANGE_DRAG[i]) {
				float span = RANGE_DRAG[i] - RANGE_DRAG[i - 1];
				float t = span <= 0.0F ? 0.0F : (clamped - RANGE_DRAG[i - 1]) / span;
				pct = Math.round(RANGE_PCT[i - 1] + (RANGE_PCT[i] - RANGE_PCT[i - 1]) * t);
				break;
			}
			pct = RANGE_PCT[RANGE_PCT.length - 1];
		}
		return signed(pct);
	}

	/**
	 * Урон в процентах от ванильной стрелы. Абсолютный базовый урон намеренно не показываем:
	 * фактический урон — это база, умноженная на скорость стрелы в момент удара, поэтому
	 * «3.0» в тултипе только путало.
	 */
	private static String damagePercent(float damage) {
		return signed(Math.round((damage / BASE_DAMAGE - 1.0F) * 100.0F));
	}

	private static String signed(int pct) {
		return (pct > 0 ? "+" : "") + pct + " %";
	}
}
