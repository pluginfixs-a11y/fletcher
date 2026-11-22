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
	/** Секунды горения цели (0 — обычные 5 от Flame). */
	public static final String KEY_IGNITE = "ignite";
	/** Своя гравитация стрелы (0 — ванильные 0.05). */
	public static final String KEY_GRAVITY = "gravity";
	/** Тики подсветки цели (0 — нет). */
	public static final String KEY_GLOW = "glow";
	/** 1 — поджигает блок, в который воткнулась. */
	public static final String KEY_FIRE = "fire";
	/** 1 — ломает блок прочностью до 10 (твёрже и неломаемое — нет), расходуясь. */
	public static final String KEY_BREAK = "break";
	/** 1 — взрывается при столкновении и уничтожается. */
	public static final String KEY_EXPLODE = "explode";
	/** 1 — при попадании в блок притягивает стрелка. */
	public static final String KEY_HARPOON = "harpoon";
	/** Сила отбрасывания в единицах зачарования «Удар» (2 уровня = 2.0). */
	public static final String KEY_KNOCKBACK = "knockback";

	/** Ванильный коэффициент инерции стрелы: {@code AbstractArrow.getAirDrag()}. */
	public static final float BASE_DRAG = 0.99F;
	/** Ванильный базовый урон стрелы: {@code AbstractArrow.ARROW_BASE_DAMAGE}. */
	public static final float BASE_DAMAGE = 2.0F;
	public static final int ARROWS_PER_CRAFT = 4;
	/** Гаст взрывает на 1.0; «чуть меньше» — 0.8. */
	public static final float EXPLOSION_POWER = 0.8F;
	/** Гарпун тянет 3 блока в секунду = 0.15 блока за тик. */
	public static final double HARPOON_SPEED = 0.15D;
	/** Дальше этого гарпун не тянет, стрела просто втыкается. */
	public static final double HARPOON_RANGE = 64.0D;

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

	/** Потолки чтения спецсвойств: NBT можно подделать, «горит вечность» не должно существовать. */
	private static final float MAX_READ_IGNITE = 30.0F;
	private static final float MIN_READ_GRAVITY = 0.005F;
	private static final float VANILLA_GRAVITY = 0.05F;
	private static final int MAX_READ_GLOW = 1200;
	/** Отбрасывание зажимаем ровно по честному максимуму (кость = «Удар II»): «Удар» на луке добавится сам. */
	private static final float MAX_READ_KNOCKBACK = 2.0F;

	private static final Map<Item, Part> FEATHERS = new HashMap<>();
	private static final Map<Item, Part> SHAFTS = new HashMap<>();
	private static final Map<Item, Part> HEADS = new HashMap<>();
	/** Особые свойства: только у стоящих материалов, у «дешёвых» (бумага, бамбук) их нет. */
	private static final Map<Item, Special> SPECIALS = new HashMap<>();
	/** Добавки слота 3: порох и нитка. */
	private static final Map<Item, Addon> ADDONS = new HashMap<>();
	private static final List<Item> FEATHER_ITEMS;
	private static final List<Item> SHAFT_ITEMS;
	private static final List<Item> HEAD_ITEMS;
	private static final List<Item> ADDON_ITEMS;
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
		SHAFTS.put(Items.BONE, new Part(0.0F, 0.0005F, 0, "кость"));

		// наконечник: урон и пробивание, всё мелкое
		HEADS.put(Items.FLINT, new Part(0.0F, 0.0F, 0, "кремень"));
		HEADS.put(Items.COPPER_NUGGET, new Part(0.5F, 0.0F, 0, "медь"));
		HEADS.put(Items.IRON_NUGGET, new Part(1.0F, 0.0F, 1, "железо"));
		HEADS.put(Items.IRON_INGOT, new Part(-1.0F, 0.0F, 0, "железный слиток"));
		HEADS.put(Items.GOLD_NUGGET, new Part(1.0F, 0.0F, 2, "золото"));
		HEADS.put(Items.AMETHYST_SHARD, new Part(1.0F, 0.0020F, 0, "аметист"));
		HEADS.put(Items.DIAMOND, new Part(2.5F, 0.0F, 3, "алмаз"));

		// Особые свойства. Цифры — не «на глаз»: 10 с горения вдвое длиннее ванильных 5 от Flame,
		// 2.0 отбрасывания — ровно «Удар II» (base 1.0 + 1.0 за уровень), 0.025 гравитации — половина
		// ванильных 0.05, подсветка 160 тиков = 8 с.
		SPECIALS.put(Items.BLAZE_ROD, new Special(10.0F, 0.0F, 0, false, 0.0F, false, "Поджигает 10 с"));
		SPECIALS.put(Items.PHANTOM_MEMBRANE, new Special(0.0F, 0.025F, 0, false, 0.0F, false, "Почти не падает"));
		SPECIALS.put(Items.AMETHYST_SHARD, new Special(0.0F, 0.0F, 160, false, 0.0F, false, "Подсвечивает 8 с"));
		SPECIALS.put(Items.FLINT, new Special(0.0F, 0.0F, 0, true, 0.0F, false, "Поджигает блок"));
		SPECIALS.put(Items.BONE, new Special(0.0F, 0.0F, 0, false, 2.0F, false, "Отбрасывает как «Удар II»"));
		// слиток стоит вдвое дешевле урона (-1.0 = половина ванильной базы) и ломает обычный блок
		// (прочность до 10), не глядя на инструмент; твёрже и неломаемое — нет.
		// Цена — сама стрела, после попадания её не подобрать
		SPECIALS.put(Items.IRON_INGOT, new Special(0.0F, 0.0F, 0, false, 0.0F, true, "Ломает блок, расходуясь"));

		ADDONS.put(Items.GUNPOWDER, new Addon(Items.GUNPOWDER, "gunpowder", "порох",
			"Взрывается при попадании", true, false));
		ADDONS.put(Items.STRING, new Addon(Items.STRING, "string", "нитка",
			"Притягивает к блоку", false, true));

		FEATHER_ITEMS = List.copyOf(FEATHERS.keySet());
		SHAFT_ITEMS = List.copyOf(SHAFTS.keySet());
		HEAD_ITEMS = List.copyOf(HEADS.keySet());
		ADDON_ITEMS = List.copyOf(ADDONS.keySet());

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

	/** Добавка слота 3: порох или нитка. Статы не меняет, включает свой спецэффект. */
	public record Addon(Item item, String key, String label, String effect, boolean explode, boolean harpoon) {
		public static final Addon NONE = new Addon(Items.AIR, "none", "", "", false, false);

		public boolean isEmpty() {
			return this.item == Items.AIR;
		}
	}

	/**
	 * Особое свойство части. Пустые поля означают «обычная часть» — {@link #NONE}.
	 * <p>
	 * Значения попадают в {@code custom_data.fletcher} готовой стрелы, чтобы миксин не лазил
	 * в таблицы предметов на каждый тик, а читал уже посчитанное (и зажимал при чтении).
	 */
	public record Special(float ignite, float gravity, int glow, boolean fire, float knockback, boolean breaksBlock,
		String label) {
		public static final Special NONE = new Special(0.0F, 0.0F, 0, false, 0.0F, false, "");

		public boolean isEmpty() {
			return this.ignite <= 0.0F && this.gravity <= 0.0F && this.glow <= 0 && !this.fire
				&& this.knockback <= 0.0F && !this.breaksBlock;
		}
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

	/** Добавка предмета; у обычного предмета — {@link Addon#NONE}. */
	public static Addon addonOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return Addon.NONE;
		}
		return ADDONS.getOrDefault(stack.getItem(), Addon.NONE);
	}

	/** Принимает ли слот 3 этот предмет. */
	public static boolean isAddon(ItemStack stack) {
		return !addonOf(stack).isEmpty();
	}

	/** Предметы-добавки — для подсказки в пустом слоте 3. */
	public static List<Item> addonPool() {
		return ADDON_ITEMS;
	}

	/** Особое свойство предмета; у обычной части — {@link Special#NONE}. */
	public static Special specialOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return Special.NONE;
		}
		return SPECIALS.getOrDefault(stack.getItem(), Special.NONE);
	}

	/** Подпись особого свойства — для подсказки в столе; пустая строка, если свойства нет. */
	public static String specialLabelOf(ItemStack stack) {
		return specialOf(stack).label();
	}

	/**
	 * Собирает стрелу из трёх обязательных слотов (оперение, древко, наконечник) и необязательной
	 * добавки (порох/нитка). Любой пустой или чужой обязательный слот — {@link ItemStack#EMPTY}.
	 */
	public static ItemStack build(ItemStack featherStack, ItemStack shaftStack, ItemStack headStack) {
		return build(featherStack, shaftStack, headStack, ItemStack.EMPTY);
	}

	public static ItemStack build(ItemStack featherStack, ItemStack shaftStack, ItemStack headStack,
		ItemStack addonStack) {
		Part feather = FEATHERS.get(featherStack.getItem());
		Part shaft = SHAFTS.get(shaftStack.getItem());
		Part head = HEADS.get(headStack.getItem());
		if (feather == null || shaft == null || head == null) {
			return ItemStack.EMPTY;
		}

		Addon addon = addonOf(addonStack);
		float drag = Mth.clamp(BASE_DRAG + feather.drag() + shaft.drag() + head.drag(), MIN_DRAG, MAX_DRAG);
		float damage = Math.min(MAX_READ_DAMAGE,
			Math.max(MIN_DAMAGE, BASE_DAMAGE + feather.damage() + shaft.damage() + head.damage()));
		int pierce = Mth.clamp(feather.pierce() + shaft.pierce() + head.pierce(), 0, MAX_PIERCE);
		Special featherSpecial = specialOf(featherStack);
		Special shaftSpecial = specialOf(shaftStack);
		Special headSpecial = specialOf(headStack);
		// гарпун держится за блок: железный наконечник не должен его сломать
		if (addon.harpoon() && headSpecial.breaksBlock()) {
			headSpecial = new Special(headSpecial.ignite(), headSpecial.gravity(), headSpecial.glow(),
				headSpecial.fire(), headSpecial.knockback(), false, "");
		}
		Special special = combine(featherSpecial, shaftSpecial, headSpecial);
		// порох заменяет обычное попадание: пробивание и on-hit свойства не сработают
		if (addon.explode()) {
			pierce = 0;
			special = new Special(0.0F, special.gravity(), 0, false, 0.0F, false, "");
		}

		ItemStack out = new ItemStack(Items.ARROW, ARROWS_PER_CRAFT);

		CompoundTag tag = new CompoundTag();
		tag.putFloat(KEY_DRAG, drag);
		tag.putFloat(KEY_DAMAGE, damage);
		tag.putInt(KEY_PIERCE, pierce);
		// особые ключи пишем только когда свойство есть: старая стрела без них читается как обычная
		if (special.ignite() > 0.0F) {
			tag.putFloat(KEY_IGNITE, special.ignite());
		}
		if (special.gravity() > 0.0F) {
			tag.putFloat(KEY_GRAVITY, special.gravity());
		}
		if (special.glow() > 0) {
			tag.putInt(KEY_GLOW, special.glow());
		}
		if (special.fire()) {
			tag.putBoolean(KEY_FIRE, true);
		}
		if (special.breaksBlock()) {
			tag.putBoolean(KEY_BREAK, true);
		}
		if (special.knockback() > 0.0F) {
			tag.putFloat(KEY_KNOCKBACK, special.knockback());
		}
		if (addon.explode()) {
			tag.putBoolean(KEY_EXPLODE, true);
		}
		if (addon.harpoon()) {
			tag.putBoolean(KEY_HARPOON, true);
		}
		CustomData.update(DataComponents.CUSTOM_DATA, out, root -> root.put(NBT_ROOT, tag));

		String suffix = addon.isEmpty() ? "" : " · " + addon.label();
		out.set(DataComponents.CUSTOM_NAME,
			Component.literal("Стрела · " + head.label() + " / " + feather.label() + suffix)
				.withStyle(pierce > 0 || special.glow() > 0 ? ChatFormatting.AQUA : ChatFormatting.WHITE));
		List<Component> lore = new ArrayList<>();
		// взрыв заменяет обычный урон; абсолютный урон наконечника тут ничего не значит
		lore.add(Component.literal(addon.explode()
			? "Взрыв: " + EXPLOSION_POWER
			: "Урон: " + damagePercent(damage)).withStyle(ChatFormatting.GRAY));
		// таблица процентов снята с ванильной гравитации: при своей (мембрана) она врёт в меньшую сторону
		lore.add(Component.literal(special.gravity() > 0.0F
			? "Дальность: " + rangePercent(drag) + " по инерции и почти не падает"
			: "Дальность: " + rangePercent(drag)).withStyle(ChatFormatting.GRAY));
		if (!addon.explode()) {
			lore.add(Component.literal("Пробивание: " + pierce).withStyle(ChatFormatting.GRAY));
		}
		for (Special used : new Special[]{featherSpecial, shaftSpecial, headSpecial}) {
			if (used.isEmpty()) {
				continue;
			}
			// у взрывной стрелы остаются только свойства полёта (гравитация мембраны)
			if (addon.explode() && used.gravity() <= 0.0F) {
				continue;
			}
			lore.add(Component.literal(used.label()).withStyle(ChatFormatting.GOLD));
		}
		if (!addon.isEmpty()) {
			lore.add(Component.literal(addon.effect()).withStyle(ChatFormatting.GOLD));
		}
		out.set(DataComponents.LORE, new ItemLore(lore));
		if (pierce > 0) {
			out.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
		}
		return out;
	}

	/** Сводит особые свойства трёх частей: горение/подсветка/отбрасывание — сильнейшее, гравитация — минимальная. */
	private static Special combine(Special first, Special second, Special third) {
		float ignite = 0.0F;
		float gravity = 0.0F;
		int glow = 0;
		boolean fire = false;
		float knockback = 0.0F;
		boolean breaksBlock = false;
		for (Special part : new Special[]{first, second, third}) {
			ignite = Math.max(ignite, part.ignite());
			glow = Math.max(glow, part.glow());
			knockback = Math.max(knockback, part.knockback());
			fire |= part.fire();
			breaksBlock |= part.breaksBlock();
			if (part.gravity() > 0.0F) {
				gravity = gravity <= 0.0F ? part.gravity() : Math.min(gravity, part.gravity());
			}
		}
		return new Special(ignite, gravity, glow, fire, knockback, breaksBlock, "");
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

	/** Секунды горения цели от огненного древка; 0 — обычные 5 от Flame. */
	public static float igniteOf(ItemStack arrow) {
		CompoundTag tag = dataOf(arrow);
		if (tag == null) {
			return 0.0F;
		}
		float value = tag.getFloatOr(KEY_IGNITE, 0.0F);
		if (!Float.isFinite(value) || value <= 0.0F) {
			return 0.0F;
		}
		return Math.min(value, MAX_READ_IGNITE);
	}

	/** Своя гравитация стрелы; 0 — ванильные 0.05. */
	public static float gravityOf(ItemStack arrow) {
		CompoundTag tag = dataOf(arrow);
		if (tag == null) {
			return 0.0F;
		}
		float value = tag.getFloatOr(KEY_GRAVITY, 0.0F);
		if (!Float.isFinite(value) || value <= 0.0F || value >= VANILLA_GRAVITY) {
			return 0.0F;
		}
		return Mth.clamp(value, MIN_READ_GRAVITY, VANILLA_GRAVITY);
	}

	/** Тики подсветки цели; 0 — нет. */
	public static int glowOf(ItemStack arrow) {
		CompoundTag tag = dataOf(arrow);
		if (tag == null) {
			return 0;
		}
		return Mth.clamp(tag.getIntOr(KEY_GLOW, 0), 0, MAX_READ_GLOW);
	}

	/** Поджигает ли стрела блок, в который воткнулась. */
	public static boolean setsFire(ItemStack arrow) {
		CompoundTag tag = dataOf(arrow);
		return tag != null && tag.getBooleanOr(KEY_FIRE, false);
	}

	/** Ломает ли стрела блок, в который воткнулась (прочность до 10); тогда же она и расходуется. */
	public static boolean breaksBlocks(ItemStack arrow) {
		CompoundTag tag = dataOf(arrow);
		return tag != null && tag.getBooleanOr(KEY_BREAK, false);
	}

	/** Взрывается ли стрела при столкновении (и уничтожается). */
	public static boolean explodes(ItemStack arrow) {
		CompoundTag tag = dataOf(arrow);
		return tag != null && tag.getBooleanOr(KEY_EXPLODE, false);
	}

	/** Притягивает ли стрела стрелка к блоку, в который воткнулась. */
	public static boolean harpoons(ItemStack arrow) {
		CompoundTag tag = dataOf(arrow);
		return tag != null && tag.getBooleanOr(KEY_HARPOON, false);
	}

	/** Сила отбрасывания цели в единицах «Удара»; 0 — только ванильное. */
	public static float knockbackOf(ItemStack arrow) {
		CompoundTag tag = dataOf(arrow);
		if (tag == null) {
			return 0.0F;
		}
		float value = tag.getFloatOr(KEY_KNOCKBACK, 0.0F);
		if (!Float.isFinite(value) || value <= 0.0F) {
			return 0.0F;
		}
		return Math.min(value, MAX_READ_KNOCKBACK);
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
