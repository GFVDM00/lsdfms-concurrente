package pe.edu.unmsm.lsdfms.index;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Extrae la clave de una columna desde el payload de InsurancePolicy.
 * El payload sigue el orden de RafStorageEngine.serialize:
 * id (long), dni, titular, tipoSeguro, departamento (short + UTF-8),
 * prima (double) y fechaEmision (short + UTF-8).
 */
public final class ColumnKeyExtractor {

    public enum Kind { LONG, TEXT }

    public static final List<String> SUPPORTED_COLUMNS =
            List.of("id", "dni", "titular", "departamento", "tipoSeguro");

    public static final List<String> DEFAULT_COLUMNS =
            List.of("id", "dni", "departamento", "tipoSeguro");

    private static final int FIELD_DNI = 0;
    private static final int FIELD_TITULAR = 1;
    private static final int FIELD_TIPO_SEGURO = 2;
    private static final int FIELD_DEPARTAMENTO = 3;

    private final String column;
    private final Kind kind;
    private final int textField;

    private ColumnKeyExtractor(String column, Kind kind, int textField) {
        this.column = column;
        this.kind = kind;
        this.textField = textField;
    }

    /** @return null si la columna no es indexable. */
    public static ColumnKeyExtractor forColumn(String column) {
        if (column == null) return null;
        return switch (column) {
            case "id" -> new ColumnKeyExtractor(column, Kind.LONG, -1);
            case "dni" -> new ColumnKeyExtractor(column, Kind.TEXT, FIELD_DNI);
            case "titular" -> new ColumnKeyExtractor(column, Kind.TEXT, FIELD_TITULAR);
            case "departamento" -> new ColumnKeyExtractor(column, Kind.TEXT, FIELD_DEPARTAMENTO);
            case "tipoSeguro" -> new ColumnKeyExtractor(column, Kind.TEXT, FIELD_TIPO_SEGURO);
            default -> null;
        };
    }

    public String column() {
        return column;
    }

    public Kind kind() {
        return kind;
    }

    /** Normaliza la clave recibida por la API; null si el tipo no es compatible. */
    public IndexKey queryKey(Object key) {
        if (key == null) return null;
        if (kind == Kind.LONG) {
            return key instanceof Number number ? IndexKey.ofLong(number.longValue()) : null;
        }
        byte[] bytes;
        if (key instanceof byte[] raw) {
            bytes = raw;
        } else if (key instanceof CharSequence text) {
            bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            return null;
        }
        return IndexKey.ofText(bytes);
    }

    /** Extrae y valida la clave del payload de un registro activo. */
    public IndexKey extract(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            long id = input.readLong();
            byte[] dni = readStringBytes(input);
            byte[] titular = readStringBytes(input);
            byte[] tipoSeguro = readStringBytes(input);
            byte[] departamento = readStringBytes(input);
            input.readDouble();
            readStringBytes(input);
            if (input.available() != 0) {
                throw new IOException("El payload contiene bytes sobrantes");
            }
            if (kind == Kind.LONG) {
                return IndexKey.ofLong(id);
            }
            byte[] selected = switch (textField) {
                case FIELD_DNI -> dni;
                case FIELD_TITULAR -> titular;
                case FIELD_TIPO_SEGURO -> tipoSeguro;
                case FIELD_DEPARTAMENTO -> departamento;
                default -> throw new IllegalStateException("Campo de texto no configurado");
            };
            return IndexKey.ofText(selected);
        } catch (EOFException e) {
            throw new IOException("Payload de poliza incompleto", e);
        }
    }

    private static byte[] readStringBytes(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > input.available()) {
            throw new IOException("Longitud de campo de texto no valida: " + length);
        }
        byte[] value = new byte[length];
        input.readFully(value);
        return value;
    }
}
