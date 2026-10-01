# MyPack

Bedrock-style content pack system for Paper Minecraft servers.

Drop add-on packs (a folder, `.zip`, `.mcpack` or `.mcaddon`) into `plugins/MyPack/packs/` and MyPack

* registers their **custom items**, **recipes**, **loot tables** and **sounds**,
* spawns **3D-model mobs** with Bedrock geometry and animations (one `ItemDisplay` per cube, composed bone matrices, an
  `Interaction` hitbox) and places **furniture** you can sit on,
* merges every pack's resources into **one resource pack**, hosts it with a built-in web server and sends it to players
  on join and after every rebuild.

> **Verification status - read this first.** The Java sources were compiled with the Eclipse compiler against the *real*
> Paper API jars (1.21.11 and, for compatibility checks, 1.21.1) and **197 JUnit tests pass**, including a full
> install-build-host-download run of the example pack. The Gradle build files and the GitHub Actions workflow were
> **never executed** (no Maven/Gradle network in the build sandbox, and Actions is locked by a billing issue on the
> repository's account), and nothing was run on a live Minecraft server or client. See [Verification](#verification) for
> exactly what was and was not checked.

## Requirements

| | |
|---|---|
| Server | **Paper 1.21.4 or newer** (needs the `item_model` component and resource pack format 46). Built against the 1.21.11 API; `api-version: '1.21'`. Folia is supported (`folia-supported: true`). |
| Java | 21 or newer |
| Other plugins | **None.** MyPack has no soft- or hard dependencies. |
| Network | One TCP port (default `8123`) that players can reach, for the resource pack download |

## Install

1. Put `MyPack-<version>.jar` into `plugins/` and start the server once. `plugins/MyPack/` is created with `config.yml`,
   `lang/en.yml` and an empty `packs/` folder.
2. Open `plugins/MyPack/config.yml` and set **`resource-pack.http.public-host`** to the address players use to reach your
   server (for example `play.example.com`). Open the HTTP port (`resource-pack.http.port`, default `8123`) in your
   firewall. Without a public host the pack URL points at a LAN address and only players on your network can download it.
3. Copy a pack into `plugins/MyPack/packs/` (try [`examples/aether_arsenal`](examples/aether_arsenal)) and run
   `/mypack reload`. Packs are installed and enabled automatically (`packs.auto-install`).
4. Join the server: the resource pack is sent after one second. Then `/mypack browse`, or
   `/mypack give <player> aether:aether_blaster` and `/mypack spawn aether:wyvern`.

### Try the example pack

```bash
cp -r examples/aether_arsenal plugins/MyPack/packs/
# in game or in the console:
/mypack reload
/mypack list
/mypack give @s aether:aether_blaster      # right-click fires a ray; 20 tick cooldown shown on the item
/mypack give @s aether:throne              # right-click a block to place it, right-click it to sit
/mypack spawn aether:wyvern                # animated 3D mob that drops wyvern scales
/mypack loot roll aether:entities/wyvern 100
```

## Commands

`/mypack` (alias `/mp`). Every subcommand has tab completion and is hidden from players who lack its permission.

| Command | Permission | What it does |
|---|---|---|
| `/mypack` , `/mypack help` | `mypack.use` | Command overview |
| `/mypack list` | `mypack.list` | All pack sources with their state (loaded, disabled, rejected, invalid, missing, not installed) |
| `/mypack info <pack>` | `mypack.info` | Manifest data, content counts and problem count of one pack |
| `/mypack validate [pack]` | `mypack.validate` | Lists errors and warnings found while loading (bad files are skipped, never fatal) |
| `/mypack install <file>` | `mypack.install` | Installs and enables `packs/<file>` (also when `auto-install` is off) |
| `/mypack uninstall <pack>` | `mypack.install` | Moves the pack to `plugins/MyPack/trash/` and unregisters it |
| `/mypack enable <pack>` / `disable <pack>` | `mypack.install` | Toggle a pack without deleting it |
| `/mypack reload` | `mypack.reload` | Re-reads `config.yml` and the language file, rescans packs, rebuilds and re-sends the resource pack |
| `/mypack give <player> <item> [amount]` | `mypack.give` | Gives a custom, furniture or `minecraft:` item |
| `/mypack spawn <mob>` | `mypack.spawn` | Spawns a custom mob at your feet |
| `/mypack loot roll <table> [times]` | `mypack.loot` | Rolls a loot table and prints the totals (for testing weights) |
| `/mypack browse` | `mypack.gui` | Paginated GUI: packs, items and furniture (click to take), mobs (click to spawn) |
| `/mypack resourcepack status\|url\|rebuild\|push [player]` | `mypack.resourcepack` | Inspect, rebuild and re-send the resource pack |
| `/mypack purge <namespace>` | `mypack.admin` | Removes the loaded mobs and furniture of a namespace |
| `/mypack debug` | `mypack.debug` | Counters, database and HTTP server state, recent audit entries |

## Permissions

| Permission | Default | Meaning |
|---|---|---|
| `mypack.use` | everyone | `/mypack` help |
| `mypack.furniture.place` | everyone | Place furniture |
| `mypack.furniture.use` | everyone | Sit on furniture, open its settings (own pieces) |
| `mypack.furniture.admin` | op | Rotate / pick up furniture that belongs to others |
| `mypack.list`, `.info`, `.validate`, `.reload`, `.install`, `.give`, `.spawn`, `.loot`, `.gui`, `.resourcepack`, `.admin`, `.debug` | op | The matching command |
| `mypack.*` | op | Everything above |

## Configuration

`plugins/MyPack/config.yml` is fully commented. The settings you will touch most:

| Key | Default | Notes |
|---|---|---|
| `resource-pack.http.public-host` | *(empty)* | **Set this.** Address players download the pack from |
| `resource-pack.http.port` | `8123` | Port of the embedded web server (restart to change) |
| `resource-pack.force` | `true` | Declining the pack disconnects the player |
| `resource-pack.external-url` | *(empty)* | Host `cache/resourcepack.zip` on a CDN instead; disables the embedded server |
| `resource-pack.pack-format` / `supported-formats` | `46`, `34`-`46` | Written verbatim to `pack.mcmeta`; raise for newer clients |
| `packs.auto-install`, `packs.auto-enable` | `true` | Behaviour for new packs found in `packs/` |
| `packs.security.allow-commands` | `false` | Item actions may run commands only when this is on - leave it off unless you trust every pack |
| `database.type` | `sqlite` | `sqlite`, `mysql` or `postgresql` |
| `models.*`, `furniture.*` | see file | Update rate, view range, entity caps |

Messages live in `plugins/MyPack/lang/<language>.yml`; every message is MiniMessage (no `&` / `§` codes anywhere).

### Database

SQLite (`mypack.db`) works out of the box. MySQL ships with Paper. **PostgreSQL**: Paper does not bundle the driver, so put
the PostgreSQL JDBC jar into `plugins/MyPack/lib/`; MyPack loads it in an isolated class loader. The schema is created
and upgraded by versioned, idempotent migrations (`schema_version` table); all SQL is parameterized.

## Pack format

A pack is a folder (or archive of one) with a Bedrock-style `manifest.json` and these directories:

```
my_pack/
  manifest.json                  uuid, version, dependencies, metadata.namespace
  items/*.json                   minecraft:item  (+ mypack:* components)
  recipes/*.json                 minecraft:recipe_shaped | _shapeless | _furnace
  loot_tables/**/*.json          pools, weighted entries, functions, conditions
  entities/*.json                mobs (a vanilla carrier entity + a 3D model)
  furniture/*.json               placeable decoration
  models/**/*.geo.json           Bedrock geometry (format 1.12 and legacy 1.8)
  animations/**/*.animation.json Bedrock animations with a Molang subset
  sounds/sound_definitions.json  + sounds/**/*.ogg
  textures/items|entity|...      PNG (+ .png.mcmeta for animated textures)
```

The complete reference - every component, action, condition and the Molang subset - is in
[`docs/PACK_FORMAT.md`](docs/PACK_FORMAT.md).

## How the 3D models work

* Every cube of a geometry becomes **one `ItemDisplay`**; displays are never mounted on each other, so a rotating parent
  bone cannot leave its children behind. Each tick the bone hierarchy is composed with JOML matrices
  (`T(pivot + animation) * Rz*Ry*Rx * S * T(-pivot)`, parent first) and every cube gets its final matrix.
* Bedrock geometry is converted exactly like Blockbench does (X axis mirrored, rotation X/Y negated, box-UV layout).
  Pixels are converted to blocks (÷16) when the model blueprint is built; degrees become radians only in
  `BoneMath.toQuaternion`.
* The resource pack gets, per cube, a parent-less model (`from [0,0,0]` to `[16,16,16]` with that cube's six UV rectangles)
  plus an item definition `assets/<ns>/items/<model>_<bone>_<cube>.json`; the display's `item_model` component points at it.
  Size, position and rotation live in the display's transformation matrix, so cubes of any size and orientation work.
* Display settings: `interpolationDuration 1`, `interpolationDelay 0`, `teleportDuration 1`, billboard `FIXED`, shadow radius `0`.
* **Animation math runs asynchronously** (Molang, blending, matrix composition, change detection); the owning thread only
  applies the finished frame and moves the entities. Only changed cubes are sent.
* The hitbox is an `Interaction` sized from the cube bounds (a volume-weighted footprint, so tails and wings do not
  inflate it; override with `mypack:hitbox`). Hits on it are forwarded to the mob as real damage.
* Mob definitions are applied for real: max health + current health, speed, attack damage, follow range, knockback
  resistance, scale, `persistent`, `silent`. The carrier is invisible, its vanilla drops are replaced by the loot table.

## Resource pack hosting

The pack is built on an async thread, hashed with SHA-1 and served from memory at
`<scheme>://<public-host>:<port>/mypack/<sha1>.zip` (the hash is part of the URL, so clients cache correctly and a rebuilt
pack is always a new URL). Players receive it through `Player#setResourcePack(UUID, url, sha1, prompt, force)` with a
**stable pack UUID**, so a rebuilt pack *replaces* the old one on the client instead of stacking. `server.properties` is
never modified. Behind a reverse proxy set `public-scheme: https` and `public-port: 443`.

## Developer API

```java
RegisteredServiceProvider<MyPackApi> rsp = Bukkit.getServicesManager().getRegistration(MyPackApi.class);
MyPackApi api = rsp == null ? null : rsp.getProvider();
Optional<ItemStack> blaster = api.createItem("aether:aether_blaster", 1);
api.itemId(someStack);                       // Optional<String>, read from persistent data - never from name or material
api.rollLoot("aether:entities/wyvern", true, 0);
```

`PacksReloadedEvent` fires (on the global region thread) whenever the registry was replaced.

## Folia

All scheduling goes through Paper's global / region / entity / async schedulers (`Schedulers`); `BukkitScheduler` is never
used. On Folia every model gets its own entity-scheduler timer and entities are moved with `teleportAsync`. This path is
implemented but **has not been run on Folia**.

## Building

```bash
./gradlew build          # runs the tests and produces build/libs/MyPack-<version>.jar (shaded, HikariCP relocated)
./gradlew runServer      # starts a Paper test server with the plugin (run-paper)
```

Gradle Kotlin DSL with `paperweight-userdev` (dev bundle `1.21.11`), `run-paper` and `shadow`; the jar is Mojang-mapped
(`ReobfArtifactConfiguration.MOJANG_PRODUCTION`) because MyPack uses no server internals. Change `paperVersion` in
`gradle.properties` to compile against another version.

| Dependency | Scope | Why |
|---|---|---|
| Paper API (dev bundle) | compileOnly | The server platform |
| HikariCP 5.1.0 | shaded + relocated | Connection pool |
| SQLite / MySQL JDBC | provided by Paper | Default / optional databases |
| PostgreSQL JDBC | optional, `plugins/MyPack/lib/` | PostgreSQL support |
| JUnit 5 | test | Unit tests |

## Verification

What was actually run:

| Check | Result |
|---|---|
| Compile all 208 classes against the real `paper-api-1.21.11` jar (Eclipse compiler, `-21`) | clean, no warnings (unused imports / privates included) |
| Compile against the real `paper-api-1.21.1` jar | exactly 20 error sites, all in API added after 1.21.1 (`item_model`, custom-model-data/use-cooldown components, the renamed `Attribute` constants, `Player#hasCooldown/setCooldown(ItemStack)`); each was confirmed on the 1.21.4 Javadoc |
| 197 JUnit 5 tests on JRE 21 | all pass |
| Hand-computed hierarchy test (parent bone rotated 30°, child cube position, tolerance 0.01) | pass, three independent scenarios |
| HTTP server under concurrent `HttpClient` load, SHA-1 of the downloaded pack | pass |
| Hostile zip archives (zip slip, absolute paths, backslashes, case collisions, zip bombs) | rejected, nothing written outside the cache |
| Real SQLite + HikariCP: migrations, idempotency, upserts, injection-shaped input | pass |

What was **not** verified - treat as untested until you have run it:

* `./gradlew build` itself (Gradle/paperweight/shadow configuration), the CI workflow, and the jar contents.
* Any behaviour on a running Paper or Folia server: event handling, entity spawning, the schedulers, commands,
  inventories, recipes, resource pack push.
* Any rendering in a Minecraft client: that the generated models/UVs look right in game, animation smoothness, textures.
  The geometry conventions were taken from Blockbench's source and are unit-tested numerically, but never seen on screen.
* ProtocolLib is not used at all (nothing here needs packet-level visuals), PlaceholderAPI is not integrated.

## Known limitations

* Custom blocks, armor/equipment models, food/consumable components and entity AI behaviours are not part of the format yet.
* Mobs and furniture that change world (portals) lose their model until their chunk reloads.
* Bedrock `bezier` keyframes are approximated with Catmull-Rom; `poly_mesh` / `texture_meshes` are ignored.
* An Interaction hitbox is axis aligned and cannot be rotated, so the derived footprint is a square.
