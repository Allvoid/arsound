package app.revanced.extension.soundcloud.theme;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a minimal compiled resource table (resources.arsc) that overrides some resources of the app
 * by their ids. Android loads it with {@code ResourcesProvider.loadFromTable} into a {@code ResourcesLoader};
 * the values then win over the app's own for every lookup: XML, styles, {@code getColor}, Compose.
 * <p>
 * Only what Arsound needs: one package, colours, dimensions, file references (fonts, raw files) and
 * references to other resources of the app, each in the default configuration or the night one.
 */
final class ArscWriter {
    private static final short RES_STRING_POOL_TYPE = 0x0001;
    private static final short RES_TABLE_TYPE = 0x0002;
    private static final short RES_TABLE_PACKAGE_TYPE = 0x0200;
    private static final short RES_TABLE_TYPE_TYPE = 0x0201;
    private static final short RES_TABLE_TYPE_SPEC_TYPE = 0x0202;

    static final byte TYPE_REFERENCE = 0x01;
    static final byte TYPE_STRING = 0x03;
    static final byte TYPE_DIMENSION = 0x05;
    static final byte TYPE_INT_COLOR_ARGB8 = 0x1c;

    private static final int CONFIG_SIZE = 64;
    private static final byte UI_MODE_NIGHT_YES = 0x20;
    private static final int CONFIG_UI_MODE = 0x1000;

    static final class Value {
        final byte type;
        final int data;
        /** For strings: the text, put into the global string pool. */
        final String text;

        private Value(byte type, int data, String text) {
            this.type = type;
            this.data = data;
            this.text = text;
        }

        static Value color(int argb) {
            return new Value(TYPE_INT_COLOR_ARGB8, argb, null);
        }

        /** A file inside the provider, such as {@code res/font/x.ttf}. */
        static Value file(String path) {
            return new Value(TYPE_STRING, 0, path);
        }

        /** Another resource of the app, such as a theme's own drawable that stands in for SoundCloud's. */
        static Value reference(int id) {
            return new Value(TYPE_REFERENCE, id, null);
        }

        /** Density-independent pixels, as a complex dimension. */
        static Value dp(float value) {
            // Radix 23p0 (whole numbers) with unit dip (1): mantissa in the top 24 bits.
            int mantissa = Math.round(value) & 0xFFFFFF;
            return new Value(TYPE_DIMENSION, (mantissa << 8) | 0x01, null);
        }
    }

    private static final class Entry {
        final String key;
        Value normal;
        Value night;

        Entry(String key) {
            this.key = key;
        }
    }

    private final int packageId;
    private final String packageName;
    /** Type id to type name. */
    private final Map<Integer, String> typeNames = new TreeMap<>();
    /** Type id to (entry index to entry). */
    private final Map<Integer, TreeMap<Integer, Entry>> types = new TreeMap<>();

    ArscWriter(int packageId, String packageName) {
        this.packageId = packageId;
        this.packageName = packageName;
    }

    /**
     * @param id    The resource id to override.
     * @param type  Its type name ("color", "font", "raw", "dimen").
     * @param key   Its entry name.
     * @param night True for the value used in dark mode only.
     */
    void put(int id, String type, String key, Value value, boolean night) {
        if ((id >>> 24) != packageId) throw new IllegalArgumentException("Other package: " + Integer.toHexString(id));
        int typeId = (id >> 16) & 0xFF;
        int entryIndex = id & 0xFFFF;
        typeNames.put(typeId, type);
        TreeMap<Integer, Entry> entries = types.get(typeId);
        if (entries == null) types.put(typeId, entries = new TreeMap<>());
        Entry entry = entries.get(entryIndex);
        if (entry == null) entries.put(entryIndex, entry = new Entry(key));
        if (night) entry.night = value;
        else entry.normal = value;
    }

    byte[] build() {
        // Global strings: file paths.
        List<String> strings = new ArrayList<>();
        for (TreeMap<Integer, Entry> entries : types.values()) {
            for (Entry entry : entries.values()) {
                for (Value value : new Value[]{entry.normal, entry.night}) {
                    if (value != null && value.text != null && !strings.contains(value.text)) strings.add(value.text);
                }
            }
        }
        byte[] globalPool = stringPool(strings);

        // Type names: every id up to the highest one used needs a name.
        int maxType = types.isEmpty() ? 0 : ((TreeMap<Integer, ?>) types).lastKey();
        List<String> typeStrings = new ArrayList<>();
        for (int i = 1; i <= maxType; i++) typeStrings.add(typeNames.containsKey(i) ? typeNames.get(i) : "unused" + i);

        List<String> keys = new ArrayList<>();
        for (TreeMap<Integer, Entry> entries : types.values()) {
            for (Entry entry : entries.values()) if (!keys.contains(entry.key)) keys.add(entry.key);
        }

        ByteArrayOutputStream typeChunks = new ByteArrayOutputStream();
        for (Map.Entry<Integer, TreeMap<Integer, Entry>> type : types.entrySet()) {
            int typeId = type.getKey();
            TreeMap<Integer, Entry> entries = type.getValue();
            int entryCount = entries.lastKey() + 1;

            boolean anyNight = false;
            ByteBuffer spec = buffer(16 + 4 * entryCount);
            spec.putShort(RES_TABLE_TYPE_SPEC_TYPE).putShort((short) 16).putInt(16 + 4 * entryCount);
            spec.put((byte) typeId).put((byte) 0).putShort((short) 0).putInt(entryCount);
            for (int i = 0; i < entryCount; i++) {
                Entry entry = entries.get(i);
                boolean night = entry != null && entry.night != null;
                anyNight |= night;
                spec.putInt(night ? CONFIG_UI_MODE : 0);
            }
            typeChunks.write(spec.array(), 0, spec.capacity());

            byte[] normal = typeChunk(typeId, entryCount, entries, keys, strings, false);
            if (normal != null) typeChunks.write(normal, 0, normal.length);
            if (anyNight) {
                byte[] night = typeChunk(typeId, entryCount, entries, keys, strings, true);
                if (night != null) typeChunks.write(night, 0, night.length);
            }
        }

        byte[] typePool = stringPool(typeStrings);
        byte[] keyPool = stringPool(keys);
        int packageHeader = 288;
        int packageSize = packageHeader + typePool.length + keyPool.length + typeChunks.size();
        ByteBuffer pkg = buffer(packageHeader);
        pkg.putShort(RES_TABLE_PACKAGE_TYPE).putShort((short) packageHeader).putInt(packageSize);
        pkg.putInt(packageId);
        char[] name = packageName.toCharArray();
        for (int i = 0; i < 128; i++) pkg.putChar(i < name.length ? name[i] : 0);
        pkg.putInt(packageHeader);                       // typeStrings
        pkg.putInt(typeStrings.size());                  // lastPublicType
        pkg.putInt(packageHeader + typePool.length);     // keyStrings
        pkg.putInt(keys.size());                         // lastPublicKey
        pkg.putInt(0);                                   // typeIdOffset

        int tableSize = 12 + globalPool.length + packageSize;
        ByteBuffer table = buffer(tableSize);
        table.putShort(RES_TABLE_TYPE).putShort((short) 12).putInt(tableSize).putInt(1);
        table.put(globalPool);
        table.put(pkg.array());
        table.put(typePool);
        table.put(keyPool);
        table.put(typeChunks.toByteArray());
        return table.array();
    }

    private static byte[] typeChunk(int typeId, int entryCount, TreeMap<Integer, Entry> entries, List<String> keys,
                                    List<String> strings, boolean night) {
        int present = 0;
        for (Entry entry : entries.values()) if ((night ? entry.night : entry.normal) != null) present++;
        if (present == 0) return null;

        int headerSize = 20 + CONFIG_SIZE;
        int entriesStart = headerSize + 4 * entryCount;
        int size = entriesStart + 16 * present;
        ByteBuffer chunk = buffer(size);
        chunk.putShort(RES_TABLE_TYPE_TYPE).putShort((short) headerSize).putInt(size);
        chunk.put((byte) typeId).put((byte) 0).putShort((short) 0).putInt(entryCount).putInt(entriesStart);
        // ResTable_config: everything "any", except the night mode for the night chunk.
        byte[] config = new byte[CONFIG_SIZE];
        config[0] = CONFIG_SIZE;
        if (night) config[29] = UI_MODE_NIGHT_YES;
        chunk.put(config);

        int offset = 0;
        for (int i = 0; i < entryCount; i++) {
            Entry entry = entries.get(i);
            Value value = entry == null ? null : (night ? entry.night : entry.normal);
            if (value == null) {
                chunk.putInt(0xFFFFFFFF);
            } else {
                chunk.putInt(offset);
                offset += 16;
            }
        }
        for (int i = 0; i < entryCount; i++) {
            Entry entry = entries.get(i);
            Value value = entry == null ? null : (night ? entry.night : entry.normal);
            if (value == null) continue;
            chunk.putShort((short) 8).putShort((short) 0).putInt(keys.indexOf(entry.key));
            chunk.putShort((short) 8).put((byte) 0).put(value.type);
            chunk.putInt(value.text != null ? strings.indexOf(value.text) : value.data);
        }
        return chunk.array();
    }

    /** A UTF-8 string pool without styles. */
    private static byte[] stringPool(List<String> strings) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = data.size();
            byte[] utf8 = strings.get(i).getBytes(StandardCharsets.UTF_8);
            writeLength(data, strings.get(i).length());
            writeLength(data, utf8.length);
            data.write(utf8, 0, utf8.length);
            data.write(0);
        }
        while (data.size() % 4 != 0) data.write(0);

        int headerSize = 28;
        int stringsStart = headerSize + 4 * strings.size();
        int size = stringsStart + data.size();
        ByteBuffer pool = buffer(size);
        pool.putShort(RES_STRING_POOL_TYPE).putShort((short) headerSize).putInt(size);
        pool.putInt(strings.size()).putInt(0).putInt(0x100).putInt(stringsStart).putInt(0);
        for (int offset : offsets) pool.putInt(offset);
        pool.put(data.toByteArray());
        return pool.array();
    }

    /** Lengths up to 0x7FFF: one byte below 0x80, two bytes (high bit set) above. */
    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length > 0x7F) {
            out.write(((length >> 8) & 0x7F) | 0x80);
            out.write(length & 0xFF);
        } else {
            out.write(length);
        }
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
