# MyPack pack format

MyPack packs follow the layout and vocabulary of Bedrock add-ons, so existing tools (Blockbench's *Bedrock Entity* export,
add-on templates) work. Everything that has no Bedrock equivalent lives in the `mypack:` component namespace.

A malformed file never breaks a pack: it is reported by `/mypack validate` (and the console) and skipped.

Contents: [Layout](#layout) · [Manifest](#manifest) · [Identifiers](#identifiers) · [Items](#items) · [Item actions](#item-actions) ·
[Recipes](#recipes) · [Loot tables](#loot-tables) · [Sounds](#sounds) · [Models and animations](#models-and-animations) ·
[Mobs](#mobs) · [Furniture](#furniture) · [Textures and the resource pack](#textures-and-the-resource-pack) · [Limits](#limits)

## Layout

A pack is a folder, or an archive (`.zip`, `.mcpack`, `.mcaddon`, `.mypack`) of one folder. The manifest may sit at the root
of the archive or inside one top-level folder.

```
manifest.json
items/*.json                          one item per file
recipes/*.json
loot_tables/<path>.json               id = <namespace>:<path>
entities/*.json                       mobs
furniture/*.json
models/**/*.geo.json                  geometry
animations/**/*.json
sounds/sound_definitions.json         sounds/**/*.ogg
textures/items|item|entity|blocks|... PNG, optional <name>.png.mcmeta
assets/<your namespace>/...           raw Java resource pack files, copied verbatim
```

JSON files may contain `//` and `/* */` comments and trailing commas.

## Manifest

```json
{
  "format_version": 2,
  "header": {
    "name": "Aether Arsenal",
    "description": "Sky weapons",
    "uuid": "a1e7e5c4-3c5d-4d0e-8f4a-0a3b7c91d201",
    "version": [1, 0, 0],
    "min_engine_version": [1, 21, 4]
  },
  "modules": [{ "type": "data", "uuid": "...", "version": [1, 0, 0] }, { "type": "resources", "uuid": "...", "version": [1, 0, 0] }],
  "dependencies": [{ "uuid": "<other pack uuid>", "version": [1, 0, 0] }],
  "metadata": { "namespace": "aether", "authors": ["You"], "license": "MIT", "url": "https://example.com" }
}
```

| Field | Rules |
|---|---|
| `header.name` | required, at most 128 characters |
| `header.uuid` | required, a UUID; unique across installed packs |
| `header.version` | required, `[major, minor, patch]` or `"1.2.3"` |
| `metadata.namespace` | owner of every identifier in the pack, `[a-z0-9_.-]+`; `minecraft` and `mypack` are reserved. If missing it is derived from the pack name (with a warning). Unique across installed packs. |
| `dependencies` | by `uuid` and minimum `version`; dependencies load first. A missing/outdated dependency or a cycle rejects the pack (and everything that needs it). Entries without a `uuid` (script modules) are ignored. |
| `modules` | `data`, `resources`, `client_data` are accepted; others are ignored with a warning |

## Identifiers

`namespace:path` - lower-case `[a-z0-9_.-]` for the namespace, additionally `/` in the path. A bare `path` belongs to the pack's
namespace. Every definition must use the pack's own namespace; a definition for another namespace is rejected.

## Items

```json
{
  "format_version": "1.21.0",
  "minecraft:item": {
    "description": { "identifier": "aether:aether_blaster" },
    "components": { "...": "..." }
  }
}
```

| Component | Value | Effect |
|---|---|---|
| `minecraft:display_name` | MiniMessage string or `{"value": ...}` | Item name (italics off unless you write `<italic>`); default is derived from the id |
| `minecraft:icon` | `"name"`, `{"texture": "name"}` or `{"textures": {"default": "name"}}` | Texture `textures/items/<name>.png`; generates the model and item definition |
| `minecraft:max_stack_size` | 1-99 | `max_stack_size` component |
| `minecraft:durability` | `{"max_durability": n}` | `max_damage` component (stack size 1 unless set) |
| `minecraft:glint` | bool | Enchantment glint override |
| `minecraft:hand_equipped` | bool | Uses the `item/handheld` parent for the icon model |
| `mypack:base_material` | Bukkit material name, default `PAPER` | The vanilla item underneath (decides vanilla behaviour, e.g. `IRON_SWORD`) |
| `mypack:lore` | list of MiniMessage strings | Lore |
| `mypack:rarity` | `common`, `uncommon`, `rare`, `epic` | Name colour |
| `mypack:attributes` | `[{"attribute": "attack_damage", "amount": 6, "operation": "add_value", "slot": "mainhand"}]` | Attribute modifiers. `operation`: `add_value`, `add_multiplied_base`, `add_multiplied_total`. `slot`: `any, mainhand, offhand, hand, head, chest, legs, feet, armor, body`. Note: declaring modifiers replaces the base item's default ones. |
| `mypack:enchantments` | `{"sharpness": 3}` | Enchantments (unsafe levels allowed) |
| `mypack:flags` | `["hide_attributes"]` | `ItemFlag` names |
| `mypack:unbreakable` | bool | |
| `mypack:custom_model_data` | int | Legacy compatibility value (`custom_model_data` floats) |
| `mypack:java_model` | `"models/item/x.json"` | A hand-written Java item model copied into the pack instead of the generated flat model |
| `mypack:tags` | list | `placeable` allows placing the base material as a vanilla block (otherwise placing is blocked) |
| `mypack:actions` | see below | Scripts and cooldown |

**Identification.** An item is recognised only by the persistent data entry `mypack:id` on the stack - never by material, name
or lore. The look comes from `item_model` = `<namespace>:<path>`, which points at the generated
`assets/<namespace>/items/<path>.json`. Custom items are blocked from vanilla recipes (they would otherwise work as plain
paper), and blocked from being placed as blocks unless tagged `placeable`.

## Item actions

```json
"mypack:actions": {
  "cooldown_ticks": 40,
  "on_right_click": [ { "type": "sound", "sound": "aether:aether.blaster.fire" } ],
  "on_left_click":  [],
  "on_hit_entity":  []
}
```

`cooldown_ticks` uses the client-visible item cooldown (shown on the item in the hotbar) and applies after any script ran.

| `type` | Fields | Effect |
|---|---|---|
| `sound` | `sound` (required; vanilla or pack event such as `aether:aether.blaster.fire`), `volume` 0-10 (1), `pitch` 0.5-2 (1) | Plays at the player |
| `particle` | `particle` (registry name, no data particles), `count` (1), `offset` `[x,y,z]`, `speed` | Spawns in front of the player |
| `message` | `text` (MiniMessage; `<player>` available) | Chat message |
| `command` | `command`, `executor` `console` or `player` (default `player`) | **Disabled unless `packs.security.allow-commands: true`.** Placeholders `<player> <uuid> <world> <x> <y> <z>`; names are sanitised |
| `potion_effect` | `effect`, `duration_ticks` (100), `amplifier` (0) | Applies to the player |
| `damage_ray` | `range` 1-200 (20), `damage` (5), `ray_size` (0.3), `knockback` (0), `trail_particle` | `world.rayTraceEntities` from the eyes; hits living entities and the hitbox of custom mobs. The trail is computed asynchronously |
| `consume` | `amount` (1) | Removes items from the used stack |
| `durability` | `amount` (1) | Damages the used item |

Out-of-range numbers are clamped with a warning; unknown action types are skipped.

## Recipes

Bedrock layout. Ingredients are `"item"`, `{"item": "ns:id"}` or `{"tag": "minecraft:planks"}`; a bare name is vanilla
(`stick` = `minecraft:stick`); custom items match exactly. Results are `"item"` or `{"item": ..., "count": 1-64}`.

* `minecraft:recipe_shaped` - `pattern` (1-3 rows of the same width, 1-3 characters, space = empty), `key`, `result`, optional `group`.
* `minecraft:recipe_shapeless` - `ingredients` (1-9; `"count": n` repeats an ingredient), `result`.
* `minecraft:recipe_furnace` - `input`, `output`, `tags` (`furnace`, `blast_furnace`, `smoker`, `campfire`; default furnace);
  optional extensions `experience` and `cooking_time` (ticks, 0 = station default). One recipe per station is registered
  (`ns:id`, `ns:id_blasting`, `ns:id_smoking`, `ns:id_campfire`).

Recipes are unlocked in every player's recipe book when they join (`packs.discover-recipes`).

## Loot tables

```json
{ "pools": [ {
    "rolls": { "min": 1, "max": 2 },
    "bonus_rolls": 0,
    "conditions": [ { "condition": "killed_by_player" } ],
    "entries": [
      { "type": "item", "name": "aether:wyvern_scale", "weight": 6,
        "functions": [ { "function": "set_count", "count": { "min": 1, "max": 3 } } ] },
      { "type": "empty", "weight": 1 }
    ] } ] }
```

* `rolls` / `count`: a number, `{"min","max"}` or `[min, max]`. Entries are chosen by **weight** (relative likelihood), never by percentage.
* Entry `type`: `item`, `loot_table` (`name` = `ns:path` or the Bedrock form `loot_tables/x.json`), `empty`.
* Functions: `set_count`, `looting_enchant` (`count` per Looting level, optional `limit`), `set_name`, `set_lore`.
* Conditions: `killed_by_player`, `random_chance` (`chance`), `random_chance_with_looting` (`chance`, `looting_multiplier`).
  An unsupported condition makes its entry/pool **never drop** (with a warning), so an unknown restriction can't make loot easier.
* Nested tables are followed up to depth 8; at most 256 stacks come out of one roll.

Loot tables are evaluated by MyPack itself (no datapack, no restart needed). Test one with `/mypack loot roll <table> [times]`.

## Sounds

`sounds/sound_definitions.json` (Bedrock 1.14 layout):

```json
{ "format_version": "1.14.0", "sound_definitions": {
    "aether.blaster.fire": { "category": "player", "subtitle": "Aether Blaster fires",
      "sounds": [ { "name": "sounds/blaster/fire", "volume": 0.9, "pitch": [0.9, 1.1], "weight": 1, "stream": false } ] } } }
```

* The event is played as `<namespace>:<key>` (`aether:aether.blaster.fire`).
* `name` is a pack-relative path without extension; the file must be `<name>.ogg` (Ogg Vorbis; anything else is skipped).
* A pitch range becomes its midpoint (Java has a single pitch). `subtitle` creates a language entry.
* `sounds.json` and the `.ogg` files are generated into the **same namespace** (`assets/<ns>/sounds.json`, `assets/<ns>/sounds/...`).

## Models and animations

**Geometry** (`*.geo.json`): format 1.12+ (`minecraft:geometry` array) and legacy 1.8 (`geometry.<name>` root keys).

| Bone field | |
|---|---|
| `name`, `parent`, `pivot`, `rotation`, `mirror`, `inflate`, `neverRender` | supported; bones may be listed in any order (they are sorted parent-first; cycles and unknown parents are repaired with a warning) |
| `cubes[]`: `origin`, `size`, `pivot`, `rotation`, `inflate`, `mirror` | supported; a size of 0 on an axis is thickened to keep the matrix invertible |
| `uv` | `[u, v]` (box layout) or per-face `{"north": {"uv": [u,v], "uv_size": [w,h], "uv_rotation": 90}, ...}`; faces you omit are not drawn |
| `poly_mesh`, `texture_meshes`, `locators` | ignored |

Up to **1024 cubes** per model (every cube is one display entity). Coordinates are converted like Blockbench: X mirrored,
rotation X and Y negated; box-UV sizes are floored (as Blockbench does).

**Animations** (`animations/**`): each animation has `loop` (`true`, `false`, `"hold_on_last_frame"`), `animation_length`,
`override_previous_animation` and per-bone `rotation` / `position` / `scale` channels. A channel is a constant vector,
a single value or Molang string (all axes), or time-keyed keyframes `{"0.5": [x,y,z]}` / `{"post": [...], "pre": [...], "lerp_mode": "linear|catmullrom|step"}`.
Several animations add up (rotation and position add, scale multiplies; `override_previous_animation` replaces). Base animations
cross-fade over 4 ticks.

**Molang subset**: numbers, `+ - * / %`, comparisons, `&& || !`, `?:`, parentheses, `variable.x = ...; return ...;` statement
lists, `math.` functions (`abs sin cos asin acos atan atan2 ceil floor round trunc sqrt exp ln pow min max mod clamp lerp
lerprotate hermite_blend random random_integer pi`; angles in degrees) and queries `query.anim_time`, `query.life_time`,
`query.ground_speed`, `query.is_moving`, `query.modified_distance_moved` (unknown queries read 0). A syntax error becomes the value 0 with a warning.

## Mobs

```json
{ "minecraft:entity": {
    "description": { "identifier": "aether:wyvern" },
    "components": {
      "minecraft:health": { "value": 60 }, "minecraft:movement": { "value": 0.26 }, "minecraft:attack": { "damage": 7 },
      "minecraft:follow_range": { "value": 28 }, "minecraft:knockback_resistance": { "value": 0.4 },
      "minecraft:scale": { "value": 1.5 }, "minecraft:loot": { "table": "loot_tables/entities/wyvern.json" },
      "mypack:base_entity": "ZOMBIE", "mypack:display_name": "<gold>Sky Wyvern", "mypack:name_visible": true,
      "mypack:silent": false, "mypack:persistent": true, "mypack:burns_in_daylight": false,
      "mypack:hitbox": { "width": 3.0, "height": 2.5 },
      "mypack:model": { "geometry": "geometry.wyvern", "texture": "wyvern", "scale": 1.0, "y_offset": 0,
        "animations": { "idle": "animation.wyvern.idle", "walk": "...", "attack": "...", "hurt": "...", "death": "..." } } } } }
```

The **carrier** (`mypack:base_entity`, any living entity type; default `ZOMBIE`) provides AI, collision and the vanilla behaviour.
All stats are applied to it for real and `minecraft:scale` scales both the carrier and the model. With a model the carrier is invisible
(`models.hide-base-entity`), its equipment is cleared and its vanilla drops never apply. `geometry`/`animations` accept
`name` (own pack) or `other_ns:name` (another pack you depend on). Animation states: `idle`, `walk` (moving), `attack`
(the mob hit something), `hurt`, `death` (played once, then the model is removed).
`mypack:hitbox` overrides the hitbox derived from the model; without it the volume-weighted footprint of the cubes is used.

Mobs and their models survive restarts: the carrier is persistent and identified by `mypack:mob`; display entities are not
persistent and are re-created when the chunk loads. A carrier whose definition no longer exists (pack disabled or
uninstalled) is removed once the packs have loaded.

## Furniture

```json
{ "mypack:furniture": {
    "description": { "identifier": "aether:throne" },
    "components": {
      "minecraft:display_name": "<gold>Sky Throne", "minecraft:icon": "throne", "mypack:lore": ["..."],
      "mypack:model": { "geometry": "geometry.throne", "texture": "throne" },
      "mypack:hitbox": { "width": 1.0, "height": 1.4 },
      "mypack:seats": [ { "offset": [0, 0.3, 0] } ],
      "mypack:rotation_step": 45 } } }
```

Furniture is placed by right-clicking a block with its item (`/mypack give <player> <id>`). Without `mypack:model` the icon is shown
as a flat display that always faces the player. Right-click sits (one rider per seat; seats are invisible marker armor stands),
sneak + right-click opens the settings panel (rotate, pick up), left-click picks it up - owner or `mypack.furniture.admin` only.
`rotation_step` is 0 (free) or a divisor of 360. The anchor is a persistent `Interaction` entity, so builds survive restarts and
even a disabled pack; their visuals return when the pack does.

## Textures and the resource pack

| Pack file | Resource pack file |
|---|---|
| `textures/items/x.png` (or `textures/item/`) | `assets/<ns>/textures/item/x.png` |
| `textures/entity/x.png` | `assets/<ns>/textures/entity/x.png` |
| `textures/blocks/x.png` | `assets/<ns>/textures/block/x.png` |
| other `textures/x.png` | `assets/<ns>/textures/x.png` |
| `<texture>.png.mcmeta` | next to its texture (animated textures) |
| `sounds/x.ogg` | `assets/<ns>/sounds/x.ogg` |
| `sounds/sound_definitions.json` | `assets/<ns>/sounds.json` (+ `lang/en_us.json` for subtitles) |
| `assets/<ns>/...` | copied verbatim (png, mcmeta, ogg, json, properties, txt) |

Generated: `assets/<ns>/models/item/<id>.json` + `assets/<ns>/items/<id>.json` per item with an icon, and per model cube
`assets/<ns>/models/entity/<model>_<bone>_<cube>.json` + `assets/<ns>/items/<model>_<bone>_<cube>.json`, where `<model>` is
the mob / furniture path and all three parts go through `safe()` (lower-case, anything outside `[a-z0-9_-]` becomes `_`).

* Names are normalised to legal resource locations (lower-case, no spaces); PNG and Ogg files are verified by their magic bytes.
* `pack.mcmeta` contains exactly `pack.pack_format`, `pack.supported_formats.{min_inclusive,max_inclusive}` and `pack.description`.
* `assets/minecraft/atlases/blocks.json` is never shipped and nothing below `assets/minecraft/` is copied.
* A `pack.png` in `plugins/MyPack/` (at most 1 MB) becomes the pack icon.
* The archive is deterministic (sorted entries, fixed timestamps), so the same input always has the same SHA-1.

## Limits

Configurable in `packs.limits`: archive size (256 MB), extracted size (512 MB), single file (64 MB), entries (20 000).
Fixed: 1024 cubes per model, loot depth 8, 256 stacks per roll, 16 MB per JSON file, resource pack at most 250 MB (the client limit).
