package com.operator.mypack.resourcepack;

import com.google.gson.JsonObject;
import com.operator.mypack.content.item.ItemDefinition;
import com.operator.mypack.content.mob.FurnitureDefinition;
import com.operator.mypack.content.mob.MobDefinition;
import com.operator.mypack.content.mob.ModelBinding;
import com.operator.mypack.content.sound.SoundEvent;
import com.operator.mypack.model.bake.CubeModelBaker;
import com.operator.mypack.model.geo.GeometryModel;
import com.operator.mypack.model.runtime.ModelBlueprint;
import com.operator.mypack.pack.ContentRegistry;
import com.operator.mypack.pack.InstalledPack;
import com.operator.mypack.utils.HashUtils;
import com.operator.mypack.utils.JsonUtils;
import com.operator.mypack.utils.NameUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Merges the resources of every loaded pack into one Java Edition resource pack.
 *
 * <ul>
 *   <li>generates item models + item definitions ({@code assets/ns/items/*.json}, 1.21.4+) for custom items and
 *       furniture, and one model + item definition per cube of every 3D model;</li>
 *   <li>maps Bedrock texture folders to Java ones and copies textures (validated PNGs, {@code .png.mcmeta}) and sounds
 *       (validated Ogg files);</li>
 *   <li>writes {@code sounds.json} (+ subtitle language file) in the <em>same namespace</em> as the sound files;</li>
 *   <li>writes {@code pack.mcmeta} with only real fields and never ships {@code assets/minecraft/atlases/blocks.json}.</li>
 * </ul>
 *
 * <p>The output is deterministic: entries are sorted and carry a fixed timestamp, so identical inputs give an identical
 * archive and therefore an identical SHA-1.</p>
 */
public final class ResourcePackBuilder {

    /** Fixed ZIP timestamp (local time, so the DOS timestamp is the same in every time zone). */
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0, 0);
    private static final long MAX_ICON_BYTES = 1024L * 1024L;
    /** The client refuses server packs above roughly this size. */
    private static final long MAX_PACK_BYTES = 250L * 1024L * 1024L;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] OGG_MAGIC = {'O', 'g', 'g', 'S'};
    private static final String FORBIDDEN_ATLAS = "assets/minecraft/atlases/blocks.json";
    private static final Set<String> RAW_EXTENSIONS = Set.of(".png", ".mcmeta", ".ogg", ".json", ".properties", ".txt");

    /**
     * @param packFormat   {@code pack.mcmeta} {@code pack_format}
     * @param supportedMin {@code supported_formats.min_inclusive}
     * @param supportedMax {@code supported_formats.max_inclusive}
     * @param icon         optional {@code pack.png} (validated PNG) or {@code null}
     */
    public record Options(int packFormat, int supportedMin, int supportedMax, String description, byte[] icon) {
    }

    /** The finished archive. */
    public record Result(byte[] zip, String sha1Hex, int fileCount, List<String> warnings) {
    }

    /** Raised when the pack cannot be produced at all (not for individual bad files, which are skipped). */
    public static final class BuildException extends Exception {
        private static final long serialVersionUID = 1L;

        public BuildException(String message) {
            super(message);
        }
    }

    private record Entry(byte[] bytes, Path file) {
    }

    private final Map<String, Entry> entries = new TreeMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final ContentRegistry.Snapshot registry;
    private final Options options;

    private ResourcePackBuilder(ContentRegistry.Snapshot registry, Options options) {
        this.registry = registry;
        this.options = options;
    }

    public static Result build(List<InstalledPack> packs, ContentRegistry.Snapshot registry, Options options)
            throws BuildException {
        return new ResourcePackBuilder(registry, options).run(packs);
    }

    // ------------------------------------------------------------------ pipeline

    private Result run(List<InstalledPack> packs) throws BuildException {
        for (InstalledPack pack : packs) {
            generateItemModels(pack);
            generateEntityModels(pack);
            generateSounds(pack);
        }
        for (InstalledPack pack : packs) {
            copyFiles(pack);
        }
        putText("pack.mcmeta", packMeta());
        if (options.icon() != null) {
            if (options.icon().length <= MAX_ICON_BYTES && startsWith(options.icon(), PNG_MAGIC)) {
                putBytes("pack.png", options.icon());
            } else {
                warn("pack.png was ignored: it must be a PNG of at most 1 MB");
            }
        }
        byte[] zip = zip();
        if (zip.length > MAX_PACK_BYTES) {
            throw new BuildException("the resource pack is " + (zip.length / (1024 * 1024))
                    + " MB; clients refuse server packs above ~250 MB. Remove large textures or sounds.");
        }
        return new Result(zip, HashUtils.sha1Hex(zip), entries.size(), List.copyOf(warnings));
    }

    private String packMeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", options.packFormat());
        JsonObject supported = new JsonObject();
        supported.addProperty("min_inclusive", options.supportedMin());
        supported.addProperty("max_inclusive", options.supportedMax());
        pack.add("supported_formats", supported);
        pack.addProperty("description", options.description());
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return JsonUtils.PRETTY.toJson(root) + "\n";
    }

    // ------------------------------------------------------------------ generated item models

    private void generateItemModels(InstalledPack pack) {
        String ns = pack.namespace();
        for (ItemDefinition item : pack.content().items()) {
            if (item.javaModel() != null) {
                try {
                    byte[] raw = pack.files().read(item.javaModel());
                    JsonUtils.parseObject(raw); // must be a JSON object
                    putBytes("assets/" + ns + "/models/item/" + item.id().path() + ".json", raw);
                    putItemDefinition(ns, item.id().path(), ns + ":item/" + item.id().path());
                } catch (IOException | RuntimeException e) {
                    warn(ns + ": java model '" + item.javaModel() + "' of " + item.id() + " is unusable: " + e.getMessage());
                }
            } else if (item.iconTexture() != null) {
                flatItemModel(ns, item.id().path(), item.iconTexture(), item.handEquipped());
            }
        }
        for (FurnitureDefinition furniture : pack.content().furniture()) {
            if (furniture.iconTexture() != null) {
                flatItemModel(ns, furniture.id().path(), furniture.iconTexture(), false);
            }
        }
    }

    private void flatItemModel(String ns, String path, String icon, boolean handheld) {
        JsonObject model = new JsonObject();
        model.addProperty("parent", handheld ? "minecraft:item/handheld" : "minecraft:item/generated");
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", ns + ":item/" + assetName(icon));
        model.add("textures", textures);
        putText("assets/" + ns + "/models/item/" + path + ".json", JsonUtils.PRETTY.toJson(model) + "\n");
        putItemDefinition(ns, path, ns + ":item/" + path);
    }

    private void putItemDefinition(String ns, String key, String modelRef) {
        putText("assets/" + ns + "/items/" + key + ".json", JsonUtils.PRETTY.toJson(CubeModelBaker.itemDefinition(modelRef)) + "\n");
    }

    // ------------------------------------------------------------------ 3D models

    private void generateEntityModels(InstalledPack pack) {
        for (MobDefinition mob : pack.content().mobs()) {
            bakeModel(mob.id().namespace(), mob.id().path(), mob.model());
        }
        for (FurnitureDefinition furniture : pack.content().furniture()) {
            bakeModel(furniture.id().namespace(), furniture.id().path(), furniture.model());
        }
    }

    private void bakeModel(String ns, String ownerPath, ModelBinding binding) {
        if (binding == null) {
            return;
        }
        GeometryModel geometry = registry.geometries().get(binding.geometry());
        if (geometry == null) {
            warn(ns + ":" + ownerPath + ": geometry '" + binding.geometry() + "' is not defined; no model baked");
            return;
        }
        ModelBlueprint blueprint = ModelBlueprint.of(geometry, NameUtils.safe(ownerPath));
        String textureRef = ns + ":entity/" + assetName(binding.texture());
        for (ModelBlueprint.CubeNode node : blueprint.cubes()) {
            String modelPath = "assets/" + ns + "/models/entity/" + node.path() + ".json";
            if (entries.containsKey(modelPath)) {
                warn("duplicate cube model " + modelPath + "; the first one is kept");
                continue;
            }
            putText(modelPath, JsonUtils.PRETTY.toJson(CubeModelBaker.cubeModel(node, geometry, textureRef)) + "\n");
            putItemDefinition(ns, node.path(), ns + ":entity/" + node.path());
        }
    }

    // ------------------------------------------------------------------ sounds.json

    private void generateSounds(InstalledPack pack) {
        List<SoundEvent> events = pack.content().sounds();
        if (events.isEmpty()) {
            return;
        }
        String ns = pack.namespace();
        JsonObject sounds = new JsonObject();
        JsonObject lang = new JsonObject();
        for (SoundEvent event : events) {
            com.google.gson.JsonArray list = new com.google.gson.JsonArray();
            for (SoundEvent.SoundFile file : event.sounds()) {
                if (!pack.files().exists(file.file() + ".ogg")) {
                    warn(ns + ": sound file " + file.file() + ".ogg for '" + event.id().path() + "' is missing; skipped");
                    continue;
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("name", ns + ":" + assetName(file.file().substring("sounds/".length())));
                if (file.volume() != 1.0F) {
                    entry.addProperty("volume", file.volume());
                }
                if (file.pitch() != 1.0F) {
                    entry.addProperty("pitch", file.pitch());
                }
                if (file.weight() != 1) {
                    entry.addProperty("weight", file.weight());
                }
                if (file.stream()) {
                    entry.addProperty("stream", true);
                }
                list.add(entry);
            }
            if (list.isEmpty()) {
                continue;
            }
            JsonObject eventJson = new JsonObject();
            if (!event.subtitle().isBlank()) {
                String key = "subtitles." + ns + "." + event.id().path();
                eventJson.addProperty("subtitle", key);
                lang.addProperty(key, event.subtitle());
            }
            eventJson.add("sounds", list);
            sounds.add(event.id().path(), eventJson);
        }
        if (sounds.size() > 0) {
            putText("assets/" + ns + "/sounds.json", JsonUtils.PRETTY.toJson(sounds) + "\n");
        }
        if (lang.size() > 0) {
            putText("assets/" + ns + "/lang/en_us.json", JsonUtils.PRETTY.toJson(lang) + "\n");
        }
    }

    // ------------------------------------------------------------------ file copying

    private void copyFiles(InstalledPack pack) {
        String ns = pack.namespace();
        for (String rel : pack.files().listAll()) {
            try {
                if (rel.startsWith("textures/")) {
                    copyTexture(pack, ns, rel);
                } else if (rel.startsWith("sounds/")) {
                    copySound(pack, ns, rel);
                } else if (rel.startsWith("assets/")) {
                    copyRaw(pack, rel);
                }
            } catch (IOException e) {
                warn(ns + ": " + rel + " could not be read: " + e.getMessage());
            }
        }
    }

    private void copyTexture(InstalledPack pack, String ns, String rel) throws IOException {
        String lower = rel.toLowerCase(Locale.ROOT);
        boolean png = lower.endsWith(".png");
        boolean mcmeta = lower.endsWith(".png.mcmeta");
        if (!png && !mcmeta) {
            if (!lower.endsWith(".json")) { // Bedrock texture_set / item_texture.json have no Java equivalent
                warn(ns + ": " + rel + " is not a PNG and was skipped (Java Edition textures must be .png)");
            }
            return;
        }
        String rest = rel.substring("textures/".length());
        String target;
        if (rest.startsWith("items/")) {
            target = "item/" + rest.substring("items/".length());
        } else if (rest.startsWith("blocks/")) {
            target = "block/" + rest.substring("blocks/".length());
        } else {
            target = rest;
        }
        String path = assetPath(ns, "textures/" + target);
        Path file = pack.files().root().resolve(rel);
        if (png && !fileStartsWith(file, PNG_MAGIC)) {
            warn(ns + ": " + rel + " is not a valid PNG file and was skipped");
            return;
        }
        if (mcmeta) {
            try {
                JsonUtils.parseObject(pack.files().read(rel));
            } catch (RuntimeException e) {
                warn(ns + ": " + rel + " is not valid JSON and was skipped");
                return;
            }
        }
        putFile(path, file);
    }

    private void copySound(InstalledPack pack, String ns, String rel) throws IOException {
        if (rel.equals("sounds/sound_definitions.json")) {
            return; // converted into sounds.json
        }
        if (!rel.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            warn(ns + ": " + rel + " was skipped (Java Edition only plays .ogg)");
            return;
        }
        Path file = pack.files().root().resolve(rel);
        if (!fileStartsWith(file, OGG_MAGIC)) {
            warn(ns + ": " + rel + " is not a valid Ogg file and was skipped");
            return;
        }
        putFile(assetPath(ns, rel), file);
    }

    private void copyRaw(InstalledPack pack, String rel) throws IOException {
        String lower = rel.toLowerCase(Locale.ROOT);
        if (lower.equals(FORBIDDEN_ATLAS)) {
            warn(rel + " was not shipped: replacing the block atlas breaks every vanilla block texture");
            return;
        }
        String[] parts = rel.split("/");
        if (parts.length < 3) {
            return;
        }
        if (parts[1].equalsIgnoreCase("minecraft")) {
            warn(pack.namespace() + ": " + rel + " was skipped: packs may not override the minecraft namespace");
            return;
        }
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || !RAW_EXTENSIONS.contains(lower.substring(dot))) {
            warn(pack.namespace() + ": " + rel + " has an unsupported file type and was skipped");
            return;
        }
        putFile(normalize(rel), pack.files().root().resolve(rel));
    }

    // ------------------------------------------------------------------ naming / entries

    /** Lower-cases and maps characters that are illegal in resource locations to '_'. */
    static String normalize(String path) {
        return path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._\\-]", "_");
    }

    /** Resource-location safe form of a texture / sound name from a pack file. */
    static String assetName(String name) {
        return normalize(name);
    }

    private String assetPath(String ns, String relativeToAssets) {
        return "assets/" + ns + "/" + normalize(relativeToAssets);
    }

    private void putText(String path, String text) {
        putBytes(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private void putBytes(String path, byte[] bytes) {
        put(path, new Entry(bytes, null));
    }

    private void putFile(String path, Path file) {
        put(path, new Entry(null, file));
    }

    private void put(String path, Entry entry) {
        if (path.toLowerCase(Locale.ROOT).equals(FORBIDDEN_ATLAS)) {
            warn(path + " was not shipped: replacing the block atlas breaks every vanilla block texture");
            return;
        }
        if (entries.containsKey(path)) {
            warn("duplicate resource " + path + "; the first one is kept");
            return;
        }
        entries.put(path, entry);
    }

    private void warn(String message) {
        warnings.add(message);
    }

    // ------------------------------------------------------------------ zip

    private byte[] zip() throws BuildException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            // pack.mcmeta first, then everything else in sorted order
            writeEntry(zip, "pack.mcmeta", entries.get("pack.mcmeta"));
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                if (!e.getKey().equals("pack.mcmeta")) {
                    writeEntry(zip, e.getKey(), e.getValue());
                }
            }
        } catch (IOException e) {
            throw new BuildException("could not write the resource pack: " + e.getMessage());
        }
        return buffer.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String name, Entry entry) throws IOException {
        ZipEntry ze = new ZipEntry(name);
        ze.setTimeLocal(FIXED_TIME);
        zip.putNextEntry(ze);
        if (entry.bytes() != null) {
            zip.write(entry.bytes());
        } else {
            try (InputStream in = Files.newInputStream(entry.file())) {
                in.transferTo(zip);
            }
        }
        zip.closeEntry();
    }

    // ------------------------------------------------------------------ magic bytes

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean fileStartsWith(Path file, byte[] magic) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(magic.length);
            return startsWith(head, magic);
        }
    }
}
