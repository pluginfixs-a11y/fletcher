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
| 2 | head | flint, copper/iron/gold nugget, amethyst shard, diamond |
| 3 | locked (the gap in the hopper layout) | — |
| 4 | result: 4 arrows per set | — |

The order is strict: a slot takes only its own role. A part of the wrong role does not go in by
click, shift-click or drag, and a hand-placed part can only ever land in its own slot (hotbar
`F`, shift-click, drag all resolve to one slot). Contents are per player: the container lives with
the open menu, leftovers return to the inventory on close, and the menu closes when the table breaks
or the player walks more than four blocks away.

Empty input slots show a rotating hint of **their own** role: feathers in slot 0, shafts in slot 1,
heads in slot 2, switching every three seconds and disappearing once something is placed. The hint
is lore-tagged («Сюда: оперение / древко / наконечник»). It lives in the display slot, not in the
container: it cannot be taken, shifted out, cloned or thrown.

The hint is hidden while the cursor holds an item. That is not cosmetic — vanilla inserts through
`Slot#safeInsert` and drag distribution, both of which read `slot.getItem()`; serving the hint to a
busy cursor makes vanilla merge the stack into it and write the sum into the container, i.e. a free
item. Drag distribution into the table works like a chest because of the same rule.

## The arrow

The result is a plain `minecraft:arrow` with components: `custom_data.fletcher = {drag, damage,
pierce}`, `custom_name`, `lore` (a vanilla client shows the numbers too) and
`enchantment_glint_override` when it pierces. A custom item id is impossible server-side: the client
would draw a missing texture.

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
| | bone | +0.5 | +0.0005 | 0 |
| head | flint | 0 | 0 | 0 |
| | copper nugget | +0.5 | 0 | 0 |
| | iron nugget | +1 | 0 | 1 |
| | gold nugget | +1 | 0 | 2 |
| | amethyst shard | +1 | +0.0020 | 0 |
| | diamond | +2.5 | 0 | 2 |

The range figure in the lore comes from a flight run, not from a gravity-free formula (that one
claimed +200 % where the real gain was +30 %): 0.991 → +4 %, 0.9925 → +10 %, 0.9955 → +26 %,
0.997 → +35 %, down to −36 % at 0.975.

The server owns the numbers: a mixin on `AbstractArrow.getAirDrag()` (inertia → range; vanilla uses
another method in water, nothing is overridden there) plus both constructors and
`setPickupItemStack` (base damage via `setBaseDamage`, piercing via `@Invoker("setPierceLevel")`).
Inertia is cached in a field because `getAirDrag` runs on every arrow every tick and reading the
component copied a tag each time. Crossbow Piercing is never weakened (`max`, not overwrite). The
client does not read these values, it only interpolates the position the server sends, so a forged
arrow can visually lag the real hitbox — the price of server-only.

Numbers read from NBT are clamped (`drag` 0.975..0.997, damage 0.5..6.0, piercing 0..3): a tagged
arrow can arrive from creative, `/give` or another mod, and without clamping any such item would
grant infinite range and a one-shot kill.

## No dupes

Modelled on `graves` (`eu.pb4.graves.other.OutputSlot`): the result is not stored state, it is
recomputed from the inputs. Slot 4 cannot be filled or taken by a vanilla path (`mayPlace`,
`mayPickup`, `allowModification`, `remove` all `false`); issuing and consuming happens in a single
`FletcherMenu#clicked` override, one item of each role. `quickMoveStack` is overridden (shift-click
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
`PICKUP_ALL`, `CLONE`, `THROW`, negative slot), a live `Arrow` (`drag=0.991 damage=3.0 pierce=1`) and
a forged tag clamped to `0.997 / 6.0 / 3`.

## Install

Fabric Loader ≥ 0.20.1 + Fabric API on the server, jar into `mods/`. `environment` is `server`, so
Fabric Loader does not load it in a client at all — singleplayer does not get the table.

## Build

```
./gradlew build
```

## License

MIT.
