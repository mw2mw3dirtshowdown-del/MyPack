# Example packs

## `aether_arsenal`

A complete pack that uses every feature of the format:

| File | Shows |
|---|---|
| `items/aether_blaster.json` | custom item: attributes, durability, rarity, glint, lore, right-click script (`sound`, `damage_ray` with a particle trail, `durability`), cooldown |
| `items/core_shard.json` + `textures/items/core_shard.png(.mcmeta)` | animated texture (4 frames, `frametime 6`) |
| `recipes/*.json` | shaped (custom + vanilla ingredients), shapeless (`count`), furnace + blast furnace |
| `loot_tables/entities/wyvern.json` | weighted entries, `set_count`, `looting_enchant`, `killed_by_player`, `random_chance_with_looting` |
| `entities/wyvern.json` | custom mob: stats, loot table, 3D model, five animations |
| `models/entity/wyvern.geo.json` | 13 bones / 18 cubes: hierarchy (neck > head > jaw, wing > wing tip, tail chain), mirrored cubes, inflate |
| `animations/wyvern.animation.json` | Molang-driven idle/walk loops, keyframed attack/hurt, Catmull-Rom death with `hold_on_last_frame` |
| `furniture/throne.json` | 3D furniture with a seat and 45° placement steps |
| `furniture/lantern_post.json` | flat icon furniture with an explicit hitbox |
| `sounds/` | two synthesized Ogg Vorbis sounds + `sound_definitions.json` with subtitles |

The textures and sounds were generated procedurally for this example and are dedicated to the public domain (CC0).

```bash
cp -r examples/aether_arsenal plugins/MyPack/packs/
/mypack reload
```

Use it as a template: copy the folder, give the copy a new `header.uuid` and `metadata.namespace`, and replace the content.
`/mypack validate <namespace>` lists every problem MyPack finds in a pack.
