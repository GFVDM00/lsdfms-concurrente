package pe.edu.unmsm.lsdfms.index;

import java.io.DataOutput;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Formato fisico de un archivo .idx:
 *
 *   header fijo (32 B): "LSDX" | version | kind | lenNombre | reservado
 *                       | entryCount | keysOffset
 *   nombre de la columna (lenNombre bytes UTF-8)
 *   tabla de entryCount entradas de 16 B: keyCode (long) + recordOffset (long)
 *   bloque de claves de longitud variable: short len + bytes UTF-8
 *
 * En columnas LONG, keyCode es el propio valor long (sin bloque de claves).
 * En columnas TEXT, keyCode es el offset absoluto de la clave en el bloque.
 */
final class IndexFileFormat {

    static final int HEADER_BYTES = 32;
    static final int ENTRY_BYTES = 16;
    static final int VERSION = 1;

    private static final int MAGIC = 0x4C534458; // LSDX
    private static final int KIND_LONG = 1;
    private static final int KIND_TEXT = 2;
    private static final int MAX_NAME_BYTES = 256;

    private IndexFileFormat() {}

    static final class Header {
        final ColumnKeyExtractor.Kind kind;
        final String column;
        final long entryCount;
        final long tableOffset;
        final long keysOffset;
        final long fileLength;

        Header(ColumnKeyExtractor.Kind kind, String column, long entryCount,
               long tableOffset, long keysOffset, long fileLength) {
            this.kind = kind;
            this.column = column;
            this.entryCount = entryCount;
            this.tableOffset = tableOffset;
            this.keysOffset = keysOffset;
            this.fileLength = fileLength;
        }
    }

    static final class EntryRef {
        final long keyCode;
        final long recordOffset;

        EntryRef(long keyCode, long recordOffset) {
            this.keyCode = keyCode;
            this.recordOffset = recordOffset;
        }
    }

    static void writeHeader(DataOutput out, ColumnKeyExtractor.Kind kind, String column,
                            long entryCount) throws IOException {
        byte[] name = column.getBytes(StandardCharsets.UTF_8);
        if (name.length == 0 || name.length > MAX_NAME_BYTES) {
            throw new IOException("Nombre de columna no valido: " + column);
        }
        long keysOffset = HEADER_BYTES + name.length + entryCount * (long) ENTRY_BYTES;
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(kind == ColumnKeyExtractor.Kind.LONG ? KIND_LONG : KIND_TEXT);
        out.writeShort(name.length);
        out.writeShort(0);
        out.writeLong(entryCount);
        out.writeLong(keysOffset);
        out.write(name);
    }

    static Header read(RandomAccessFile file) throws IOException {
        long fileLength = file.length();
        if (fileLength < HEADER_BYTES) {
            throw new IOException("Archivo de indice truncado");
        }
        file.seek(0);
        if (file.readInt() != MAGIC) {
            throw new IOException("Archivo de indice no reconocido");
        }
        if (file.readInt() != VERSION) {
            throw new IOException("Version de indice no soportada");
        }
        int kindCode = file.readInt();
        ColumnKeyExtractor.Kind kind = switch (kindCode) {
            case KIND_LONG -> ColumnKeyExtractor.Kind.LONG;
            case KIND_TEXT -> ColumnKeyExtractor.Kind.TEXT;
            default -> throw new IOException("Tipo de clave de indice no valido: " + kindCode);
        };
        int nameLength = file.readUnsignedShort();
        file.readUnsignedShort();
        long entryCount = file.readLong();
        long keysOffset = file.readLong();
        if (nameLength == 0 || nameLength > MAX_NAME_BYTES || entryCount < 0) {
            throw new IOException("Cabecera de indice no valida");
        }
        long tableOffset = HEADER_BYTES + (long) nameLength;
        long expectedKeysOffset = tableOffset + entryCount * (long) ENTRY_BYTES;
        if (keysOffset != expectedKeysOffset || fileLength < keysOffset) {
            throw new IOException("Longitud de indice inconsistente con su cabecera");
        }
        byte[] name = new byte[nameLength];
        file.readFully(name);
        return new Header(kind, new String(name, StandardCharsets.UTF_8),
                entryCount, tableOffset, keysOffset, fileLength);
    }

    static EntryRef readEntry(RandomAccessFile file, Header header, long index) throws IOException {
        if (index < 0 || index >= header.entryCount) {
            throw new IOException("Entrada de indice fuera de rango: " + index);
        }
        file.seek(header.tableOffset + index * (long) ENTRY_BYTES);
        return new EntryRef(file.readLong(), file.readLong());
    }

    static IndexKey readKey(RandomAccessFile file, Header header, EntryRef entry) throws IOException {
        if (header.kind == ColumnKeyExtractor.Kind.LONG) {
            return IndexKey.ofLong(entry.keyCode);
        }
        if (entry.keyCode < header.keysOffset || entry.keyCode > header.fileLength - 2) {
            throw new IOException("Referencia de clave fuera de rango");
        }
        file.seek(entry.keyCode);
        int length = file.readUnsignedShort();
        if (length < 0 || entry.keyCode + 2L + length > header.fileLength) {
            throw new IOException("Clave de indice truncada");
        }
        byte[] bytes = new byte[length];
        file.readFully(bytes);
        return IndexKey.ofText(bytes);
    }
}
