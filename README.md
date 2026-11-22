# Fletcher Table

Server-side Fabric mod for Minecraft **26.2**. The vanilla fletching table opens a hopper-shaped
workbench where an arrow is assembled from three parts — feather, shaft, head — and every part
changes the arrow's damage, range and piercing. Vanilla clients need nothing installed.

## The screen

`FLETCHING_TABLE` in 26.2 is a plain `Block` with no `onUse` (the `FletchingTableBlock` class is
gone), so the mod hooks `UseBlockCallback` and opens a vanilla hopper menu (`MenuType.HOPPER`,
five slots in a row). A server-side mod cannot register its own menu type: the vanilla client does
not know the id. The title is `{"translate": "fletcher.table", "fallback": "Стол лучника"}`.

| slot | role | accepts |
|---|---|---|
| 0 | feather | feather, paper, leather, phantom membrane |
| 1 | shaft | stick, bamboo, blaze rod, bone |
| 2 | head | flint, copper/iron/gold nugget, iron ingot, amethyst shard, diamond |
| 3 | addon (optional) | gunpowder |
| 4 | result: 4 arrows per set | — |

The order is strict: a slot takes only its own role. A part of the wrong role does not go in by
click, shift-click or drag, and a hand-placed part can only ever land in its own slot (hotbar
`F`, shift-click, drag all resolve to one slot). Contents are per player: the container lives with
the open menu, leftovers return to the inventory on close, and the menu closes when the table breaks
or the player walks more than four blocks away.

Empty input slots show a rotating hint of **their own** role: feathers in slot 0, shafts in slot 1,
heads in slot 2, addons in slot 3, switching every three seconds and disappearing once something is
placed. The hint is lore-tagged («Сюда: оперение / древко / наконечник / добавка»). It lives in the
display slot, not in the container: it cannot be taken, shifted out, cloned or thrown.

The hint is hidden while the cursor holds an item. That is not cosmetic — vanilla inserts through
`Slot#safeInsert` and drag distribution, both of which read `slot.getItem()`; serving the hint to a
busy cursor makes vanilla merge the stack into it and write the sum into the container, i.e. a free
item. Drag distribution into the table works like a chest because of the same rule.

## The arrow

The result is a plain `minecraft:arrow` with components: `custom_data.fletcher = {drag, damage,
pierce}` plus the specials and addons, `custom_name`, `lore` (a vanilla client shows the numbers too)
and `enchantment_glint_override` when it pierces. A custom item id is impossible server-side: the
client would draw a missing texture.

Stats add up: `inertia = 0.99 + sum`, `damage = 2.0 + sum`, `piercing = sum` (0..3, inertia clamped
to 0.975..0.997). Lore shows damage and range as percentages of a vanilla arrow — the absolute base
damage in the tooltip only confused, because real damage is base × impact speed.

| part | item | damage | inertia | pierce |
|---|---|---|---|---|
| feather | feather | 0 | +0.0010 | 0 |
| | paper | −1 | +0.0025 | 0 |
| | leather | +1 | +0.0003 | 0 |
| | phantom membrane | 0 | +0.0040 | 0 |
| shaft | stick | 0 | 0 | 0 |
| | bamboo | −1 | +0.0015 | 0 |
| | blaze rod | +1 | −0.0015 | 0 |
| | bone | 0 | +0.0005 | 0 |
| head | flint | 0 | 0 | 0 |
| | copper nugget | +0.5 | 0 | 0 |
| | iron nugget | +1 | 0 | 1 |
| | iron ingot | −1 | 0 | 0 |
| | gold nugget | +1 | 0 | 2 |
| | amethyst shard | +1 | +0.0020 | 0 |
| | diamond | +2.5 | 0 | 3 |

## Special properties

Numbers alone made one part strictly better than another (gold was the cheap piercing, bone beat the
stick for free). Seven materials now do something a number cannot, and the budget parts (paper, bamboo,
stick) deliberately get nothing — they stay the cheap filler:

| part | item | effect |
|---|---|---|
| feather | phantom membrane | gravity 0.025 instead of 0.05 — the arrow glides |
| shaft | blaze rod | the arrow burns and sets the target on fire for 10 s, twice Flame's 5 s |
| shaft | bone | knockback equal to Punch II (its +0.5 damage is gone — it is a trade, not a bonus) |
| head | flint | ignites the block it sticks into, like flint and steel |
| head | amethyst shard | the target glows for 8 s — you can see it through walls and track it |
| head | iron ingot | breaks blocks with destroy speed ≤ 10 (stone, ores, storage blocks, heavy core) and is consumed; obsidian, netherite, ender chest and harder stay intact |
| head | diamond | piercing 3 (the ceiling that used to be unreachable) |

Specials stack: membrane + blaze rod is a gliding fire arrow, amethyst + piercing 3 lights up a whole
line of mobs. They travel in the arrow's own `custom_data.fletcher` (`ignite`, `gravity`, `glow`,
`fire`, `break`, `knockback`), so an old arrow without those keys is simply a plain fletcher arrow, and
every number read is clamped to what an honest arrow can do (ignite ≤ 30 s, gravity 0.005..0.05, glow ≤
1200 ticks, knockback ≤ 2 = Punch II — Punch on the bow adds on top by itself). The two flags (`fire`,
`break`) have nothing to clamp: forging them takes `/give` or creative, i.e. an operator.

On-hit specials hang off `doPostHurtEffects`, which vanilla calls only inside the branch where the
damage actually went through. So the second arrow of a multishot volley, an arrow stopped by i-frames,
and an arrow at a target another mod cancelled deal no fire, no glow and no push — exactly like the
vanilla ignite that is rolled back in those cases.

Three things are deliberately vanilla-shaped rather than stronger:

* **fire** — the arrow must be burning for vanilla to ignite the target at all, and `clearFire` in the
  arrow's tick puts it out in water or rain, so a fire arrow is not a permanent upgrade;
* **flint fire on blocks** — the arrow places fire exactly where flint and steel would
  (`BaseFireBlock.canBePlacedAt` → `getState` → `setBlock(..., 11)` + the same sound), but only for an
  arrow with a player owner and only where `mayUseItemAt` allows it — the very check vanilla runs
  before using flint and steel, so spawn protection, adventure mode and region plugins refuse the
  arrow the same way. Spread still obeys `doFireTick`, so a server that turned fire spread off does
  not burn down;
* **knockback** — computed with vanilla's own formula (`direction × force × 0.6 × (1 − knockback
  resistance))`, so Punch on the bow stacks with it instead of being replaced, and a straight-down
  hit pushes nothing, as in vanilla.

The iron ingot is the one deliberate exception to "vanilla-shaped rather than stronger": the arrow
breaks the block it hits when its destroy speed is at most 10 — stone, deepslate, ores,
diamond/iron/gold/emerald blocks, heavy core, creaking heart — and is consumed doing it: a mining tool
that costs one arrow entity per block (a Multishot volley spends one ammo item for three arrows, so
it can break up to three blocks) and gives up half the damage. It refuses what the game gates behind
a diamond pickaxe or high hardness: obsidian, crying obsidian, netherite block, ancient debris,
respawn anchor, reinforced deepslate, ender chest, everything with a negative destroy speed (bedrock,
barrier, command and structure blocks) and anything in `minecraft:needs_diamond_tool`. Those just
stick like a normal arrow and are not spent. Drops are the block's own
(`destroyBlock(..., true)`), so an ore still drops its item; a block without a drop table simply
vanishes. It still needs a player owner (a skeleton's or dispenser's arrow only sticks) and still
asks `mayInteract`/`blockActionRestricted`, so adventure mode, spawn protection and the world border
refuse it the way they refuse a player's break. Guarded blocks (bastion chests, gold) still anger
nearby piglins: the arrow reproduces the `GUARDED_BY_PIGLINS` hook around the hit block, because the
shooter can be outside vanilla's 16-block anger radius. Their chase range stays vanilla.

## Addons

Slot 3 takes one optional addon and changes no stats; an empty slot is a normal fletcher arrow.

| addon | item | effect |
|---|---|---|
| gunpowder | gunpowder | explodes on contact with a block or an entity, destroys the arrow |

**Gunpowder** replaces the ordinary hit: the arrow deals no separate arrow damage and applies no
on-hit part specials, it just explodes and is spent. Explosion power is 0.8 — the ghast's
`LargeFireball` is 1.0 — and blocks break regardless of `mobGriefing`
(`ExplosionInteraction.BLOCK`, as the owner asked); obsidian, netherite and other blast-resistant
blocks still survive that 0.8. Entity damage is **doubled** by a custom
`ExplosionDamageCalculator` while block damage stays at 0.8. Because the arrow dies on the first
contact, its piercing is forced to 0 (crossbow Piercing included) and the lore shows `Взрыв: 0.8`
instead of damage/piercing. The explosion is credited to the owning player so kills count, and the
shooter is not excluded from the blast.

A fiery part in the set makes the blast itself fiery: with a **blaze rod** shaft the explosion gets
`fire = true` (vanilla then scatters fire), and the lore reads `Взрыв: 0.8 (огонь)`. The arrow's own
"sets the target on fire" special is still replaced by the explosion — the fire comes from the blast.
Like the block damage, that fire is not gated by `mobGriefing` (`ServerExplosion.createFire` has no
gamerule check), which follows the same "blocks always break" rule. A set with a membrane feather and
gunpowder stays a plain blast and keeps its "almost no gravity" special.

Arrows crafted before 1.5.0 may still carry the removed `harpoon` tag and the old
«Притягивает к блоку» name/lore: nothing reads that tag any more, so they fly as ordinary fletcher
arrows with their stats and do not pull.

## Mechanics

The range figure in the lore comes from a flight run, not from a gravity-free formula (that one
claimed +200 % where the real gain was +30 %): 0.991 → +4 %, 0.9925 → +10 %, 0.9955 → +26 %,
0.997 → +35 %, down to −36 % at 0.975.

The server owns the numbers: a mixin on `AbstractArrow.getAirDrag()` (inertia → range; vanilla uses
another method in water, nothing is overridden there), `getDefaultGravity()` (which `Entity.applyGravity`
reaches through `getGravity()`), `doPostHurtEffects`/`onHitBlock` for the specials, plus both constructors
and `setPickupItemStack` (base damage via `setBaseDamage`, piercing via `@Invoker("setPierceLevel")`).
Inertia and gravity are cached in fields because both run on every arrow every tick and reading the
component copied a tag each time. Crossbow Piercing is never weakened (`max`, not overwrite). The
client does not read these values, it only interpolates the position the server sends, so a forged
arrow can visually lag the real hitbox — the price of server-only.

Numbers read from NBT are clamped (`drag` 0.975..0.997, damage 0.5..6.5, piercing 0..3): a tagged
arrow can arrive from creative, `/give` or another mod, and without clamping any such item would
grant infinite range and a one-shot kill. NaN and Infinity fall back to the vanilla values —
`Mth.clamp` passes them through (every comparison with NaN is false) and `setBaseDamage(NaN)`
leaves the victim with NaN health. The 6.5 ceiling is exactly the best honest set (leather + blaze
rod + diamond); the self-test checks it so the lore never promises more than the shot delivers.

## No dupes

Modelled on `graves` (`eu.pb4.graves.other.OutputSlot`): the result is not stored state, it is
recomputed from the inputs. Slot 4 cannot be filled or taken by a vanilla path (`mayPlace`,
`mayPickup`, `allowModification`, `remove` all `false`); issuing and consuming happens in a single
`FletcherMenu#clicked` override, one item of each role and, if present, one addon. `quickMoveStack`
is overridden (shift-click
does not call `onTake`, and vanilla transfer would write into the slot that serves the hint),
`CLONE` on a hint is blocked (`Slot#safeClone` never asks `mayPickup` and would clone it to 64),
and negative slot ids are rejected — for `SWAP`/`THROW`/`CLONE` vanilla would otherwise touch
`slots.get(-1)` and crash the server.

## Self-test

`-Dfletcher.selftest=1` runs the whole thing on the first server tick and stops the server with the
result. It covers the container (order, roles, junk, consume), the menu (41 slots, per-role previews,
hint visible with an empty cursor and hidden with a full one, wrong role refused, left/right click,
stacking, shift-click routing, the 4-arrow issue, a second click with a busy cursor, crossbow pierce
kept, shift-click drain, no ghost items), the click paths (`F` into the right and wrong slot, drag,
`PICKUP_ALL`, `CLONE`, `THROW`, negative slot), a live `Arrow` (`drag=0.991 damage=3.0 pierce=1`), a forged tag clamped to `0.997 / 6.5 / 3`, NaN/Infinity
falling back to the vanilla `0.99 / 2.0`, the best set stored and read back as 6.5 (the ceiling guard reads
the raw tag, so a growing part table cannot pass it silently), and a stack rewritten to a plain arrow
dropping the fletcher damage back to 2.0.

The specials are exercised through the real `onHitEntity`/`onHitBlock`, not through a copy of their
logic: a membrane arrow reports gravity 0.025, a blaze rod arrow is on fire and leaves a pig burning
for 200 ticks, an amethyst arrow gives the pig 8 s of Glowing, a bone arrow pushes it further than a
plain arrow does, a second arrow into the same pig's i-frames applies nothing, a flint arrow sets the
block it hits on fire while an ownerless one does not, an iron ingot arrow reports −50 % damage
(tooltip included), breaks a heavy core (destroy speed 10, the allowed boundary) and is spent, leaves
bedrock, obsidian, crying obsidian, netherite block, ancient debris, respawn anchor, reinforced
deepslate and ender chest intact and unspent, refuses a breakable block without a player owner,
outside the world border and in adventure mode with an empty hand, and does all of it on a real
flight — a ticked arrow into a diamond block, not only a direct `onHitBlock` call. The drop itself is
not asserted: the manually ticked arrow does not onboard the chunk's entity sections, so a freshly
spawned item entity is not visible to entity queries; the call is vanilla `destroyBlock(..., true)`.
A diamond arrow reports piercing 3, the lore of a three-special arrow has six lines, forged
`ignite/gravity/glow/knockback` clamp to 30 / 0.005 / 1200 / 2, and bamboo/paper/stick report no
special at all.

Slot 3 is covered as well: gunpowder is accepted, string is refused through the container, drag,
`F`/hotbar swap and shift-click, an empty slot still makes a normal arrow, one addon is consumed per
set, and `F`/drag/shift-click/`CLONE`/`Q` follow the same no-dupe rules. A gunpowder arrow removes
dirt even with `mobGriefing=false` and is destroyed; an entity hit also detonates (checked by the
block under the pig, since entity sections are hidden before the first tick — the damage itself is
vanilla explosion code). A blaze-rod set marks the explosion as fiery in the item tag and in the
mixin field, and a real detonation over a dirt platform actually leaves fire; a stick-shaft set and a
membrane set stay non-fiery (the membrane keeps its gravity special).

## Install

Fabric Loader ≥ 0.20.1 + Fabric API on the server, jar into `mods/`. `environment` is `server`, so
Fabric Loader does not load it in a client at all — singleplayer does not get the table.

## Build

JDK 25 must be the active `JAVA_HOME`; the build no longer pins a local path.

```
./gradlew build
```

## License

MIT.
