# Especificación del Formato Binario y Estructura de Almacenamiento LSDFMS

Este documento define la representación a bajo nivel en disco para el **Large Scale Data File Management System (LSDFMS)**, cumpliendo estrictamente con el requerimiento **RF-01** y la restricción **RNF-01** (Java estándar puro con `java.io.RandomAccessFile`).

---

## 1. Modelo de Dominio: Pólizas de Seguro (`InsurancePolicy`)

El caso de negocio modela la emisión y gestión de pólizas de seguros en el Perú. Permite demostrar campos numéricos de longitud fija y cadenas de caracteres de **longitud variable**.

| Campo | Tipo Java | Longitud Física | Indexable | Descripción |
|---|---|---|---|---|
| `deleted` | `boolean` | 1 byte | No | Bandera de borrado lógico (`0x00`: Activo, `0x01`: Eliminado) |
| `id` | `long` | 8 bytes | Sí ($I_1$) | Identificador único de póliza (secuencial) |
| `dni` | `String` | 2 bytes (len) + variable UTF-8 | Sí ($I_2$) | Documento de Identidad del Asegurado (8 caracteres) |
| `titular` | `String` | 2 bytes (len) + variable UTF-8 | Sí ($I_3$) | Nombres y Apellidos del Asegurado (longitud variable) |
| `tipoSeguro` | `String` | 2 bytes (len) + variable UTF-8 | Sí ($I_4$) | Tipo de póliza (ej. "Salud Integral", "Vehicular Todo Riesgo") |
| `departamento`| `String` | 2 bytes (len) + variable UTF-8 | Sí ($I_5$) | Región del Perú (ej. "Lima", "Arequipa", "Cusco") |
| `prima` | `double` | 8 bytes | No | Monto económico de la prima asegurada |
| `fechaEmision`| `String` | 2 bytes (len) + variable UTF-8 | No | Fecha en formato ISO (`YYYY-MM-DD`) |

---

## 2. Estructura Física del Archivo de Datos ($F_i$)

Cada archivo de datos (por ejemplo `data/policies_data.dat`) está compuesto por una secuencia contigua de registros binarios de longitud variable:

```text
+-------------------+--------------------+-----------------------------------------------+
| Deleted Flag (1B) | Record Length (4B) | Payload del Registro (Record Length bytes)    |
+-------------------+--------------------+-----------------------------------------------+
^
|-- Offset del Registro (offset devuelto y guardado en índices)
```

### 2.1. Anatomía de un Registro
1. **Header del Registro (5 bytes fijos):**
   - **`deleted` (1 byte):**
     - `0x00` $\rightarrow$ Registro Activo.
     - `0x01` $\rightarrow$ Registro Marcado para Borrado Lógico.
   - **`recordLength` (4 bytes, `int`):** Tamaño en bytes del *payload* que sigue inmediatamente. Esto permite a los hilos de `PACK()` y lectura saltar registros completos rápidamente (`seek(currentOffset + 5 + recordLength)`).

2. **Payload del Registro (longitud variable):**
   - `id`: 8 bytes (`writeLong`).
   - `dni`: 2 bytes (`short` con la longitud en bytes) + bytes UTF-8 (`writeShort` + `write`).
   - `titular`: 2 bytes (`short`) + bytes UTF-8.
   - `tipoSeguro`: 2 bytes (`short`) + bytes UTF-8.
   - `departamento`: 2 bytes (`short`) + bytes UTF-8.
   - `prima`: 8 bytes (`writeDouble`).
   - `fechaEmision`: 2 bytes (`short`) + bytes UTF-8.

---

## 3. Repositorio de Metadata (`metadata.json` / `MetadataRepository`)

El archivo de metadata mantiene la coherencia global del sistema.

```json
{
  "activeDataFile": "data/policies_data.dat",
  "totalRecords": 1000000,
  "activeRecords": 950000,
  "deletedRecords": 50000,
  "lastOffset": 128456720,
  "isIndexSynced": true,
  "lastCompactedAt": 1732578000000,
  "lastIndexedAt": 1732578000000,
  "indexes": [
    {
      "column": "id",
      "indexFile": "data/idx_id.idx",
      "type": "LONG"
    },
    {
      "column": "dni",
      "indexFile": "data/idx_dni.idx",
      "type": "STRING_8"
    },
    {
      "column": "departamento",
      "indexFile": "data/idx_departamento.idx",
      "type": "STRING_VAR"
    }
  ]
}
```

---

## 4. Reglas de Operación para Alejandro (Motor de Almacenamiento)

1. **Inserción (`insert`):**
   - Se posiciona al final del archivo (`raf.seek(raf.length())`).
   - Se guarda el `offset = raf.getFilePointer()`.
   - Se escribe el flag `0x00`, el tamaño del payload y el payload.
   - Se incrementan `totalRecords`, `activeRecords` en Metadata.
   - Se marca `isIndexSynced = false` (el índice queda desactualizado hasta que se ejecute `INDEX()`).
   - Se retorna el `offset`.

2. **Lectura por Offset (`readAt`):**
   - `raf.seek(offset)`.
   - Se lee el byte de borrado. Si es `0x01`, se puede lanzar excepción o retornar objeto con `isDeleted() == true` según el flag solicitado.
   - Se lee el `recordLength` y se deserializan los campos del payload.

3. **Borrado Lógico (`markAsDeleted`):**
   - `raf.seek(offset)`.
   - Se sobrescribe únicamente el primer byte con `0x01` (`raf.writeByte(0x01)`).
   - Se incrementa `deletedRecords` y decrementa `activeRecords` en Metadata.
   - Se marca `isIndexSynced = false`.
   - **Costo:** $O(1)$ directo por *offset*. No reescribe el resto del archivo.
