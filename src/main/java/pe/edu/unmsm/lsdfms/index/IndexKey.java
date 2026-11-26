package pe.edu.unmsm.lsdfms.index;

/**
 * Clave indexada con orden determinista. Los textos se comparan byte a byte
 * como unsigned UTF-8, igual que se almacenan en el archivo de indices.
 */
final class IndexKey implements Comparable<IndexKey> {
    private final boolean text;
    private final long longValue;
    private final byte[] bytes;

    private IndexKey(boolean text, long longValue, byte[] bytes) {
        this.text = text;
        this.longValue = longValue;
        this.bytes = bytes;
    }

    static IndexKey ofLong(long value) {
        return new IndexKey(false, value, null);
    }

    static IndexKey ofText(byte[] value) {
        if (value == null) throw new IllegalArgumentException("Clave de texto nula");
        return new IndexKey(true, 0L, value.clone());
    }

    boolean isText() {
        return text;
    }

    long longValue() {
        return longValue;
    }

    byte[] bytes() {
        return bytes;
    }

    @Override
    public int compareTo(IndexKey other) {
        if (text != other.text) {
            throw new IllegalArgumentException("No se pueden comparar claves de distinto tipo");
        }
        if (!text) {
            return Long.compare(longValue, other.longValue);
        }
        return compareUnsigned(bytes, other.bytes);
    }

    @Override
    public String toString() {
        return text ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                : Long.toString(longValue);
    }

    static int compareUnsigned(byte[] left, byte[] right) {
        java.util.Objects.requireNonNull(left, "left");
        java.util.Objects.requireNonNull(right, "right");
        int common = Math.min(left.length, right.length);
        for (int i = 0; i < common; i++) {
            int a = left[i] & 0xff;
            int b = right[i] & 0xff;
            if (a != b) return a - b;
        }
        return Integer.compare(left.length, right.length);
    }
}
