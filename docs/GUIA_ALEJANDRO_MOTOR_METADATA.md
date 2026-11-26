# Guía de Desarrollo para Alejandro (Módulo 1: Motor LSDFMS y Metadata)

¡Hola Alejandro! Esta guía contiene todo lo necesario para que desarrolles tu módulo sin bloqueos.

---

## 🎯 Tu Responsabilidad (Requerimientos RF-01, RNF-01, RNF-02)
1. **Implementar el motor de bajo nivel en `RafStorageEngine.java`** utilizando `java.io.RandomAccessFile` en modo `"rw"`.
2. **Soportar registros de longitud variable y campos de longitud variable** sobre el modelo de negocio acordado (`InsurancePolicy`).
3. **Manejar desplazamientos (*offsets*) exactos en bytes** retornados en cada inserción y usados en `readAt(offset)`.
4. **Implementar el borrado lógico en $O(1)$** modificando exclusivamente el byte indicador (`0x00` = activo, `0x01` = eliminado) en la posición del offset sin reescribir el resto del archivo.
5. **Gestionar la persistencia de `Metadata`** (contadores de registros totales, activos, eliminados y bandera `isIndexSynced`).

---

## 📁 Archivos Preparados para Ti

En el paquete `pe.edu.unmsm.lsdfms.storage` tienes las clases listas para completar:

1. **`StorageEngine.java`:** La interfaz pública que define el contrato. **No modificar sus métodos**, ya que Brandon, Anderson y Jerson se conectarán a ella.
2. **`RafStorageEngine.java`:** Aquí programarás la lógica con `RandomAccessFile`.
   - `public long insert(InsurancePolicy record)`
   - `public InsurancePolicy readAt(long offset)`
   - `public boolean markAsDeleted(long offset)`
   - `public long getFileSize()`
   - `public void close()`
3. **`MetadataRepository.java`:** Guarda y carga los contadores del sistema en `data/metadata.properties` sin usar dependencias externas.

---

## 💾 Formato Físico Recomendado para tu `RandomAccessFile`

Revisa la especificación completa en [`docs/ESPECIFICACION_FORMATO_BINARIO.md`](./ESPECIFICACION_FORMATO_BINARIO.md):

```text
+-------------------+--------------------+-----------------------------------------------+
| Deleted Flag (1B) | Record Length (4B) | Payload del Registro (Record Length bytes)    |
+-------------------+--------------------+-----------------------------------------------+
^
|-- Offset en bytes que debes retornar en insert() y recibir en readAt() y markAsDeleted()
```

### Operaciones clave con `RandomAccessFile`:
- **Inserción:**
  ```java
  raf.seek(raf.length());
  long offset = raf.getFilePointer();
  raf.writeByte(0x00);            // 1 byte: Activo
  raf.writeInt(payloadBytes.length); // 4 bytes: Tamaño del payload
  raf.write(payloadBytes);        // N bytes de datos
  // Actualizar metadata...
  return offset;
  ```
- **Borrado Lógico en $O(1)$:**
  ```java
  raf.seek(offset);
  raf.writeByte(0x01);            // 1 byte: Eliminado
  // Actualizar contadores en metadata...
  ```
- **Lectura por Offset:**
  ```java
  raf.seek(offset);
  byte flag = raf.readByte();
  int length = raf.readInt();
  byte[] payload = new byte[length];
  raf.readFully(payload);
  // Reconstruir InsurancePolicy
  ```

---

## 🔗 Integración Posterior: ¿Quién consume tu trabajo?

Una vez que implementes y pruebes `RafStorageEngine`:
1. **Brandon (Servidor y Cola Concurrente):** Encolará las peticiones concurrentes del cliente web y llamará a tus métodos `insert()` y `markAsDeleted()`.
2. **Anderson (`PACK` Paralelo):** Leerá tu archivo de datos, particionará por bytes y creará el archivo compactado libre de registros eliminados.
3. **Jerson (`INDEX` Paralelo):** Recorrerá secuencialmente los offsets para extraer los campos clave y construir los archivos `.idx`.
4. **Arancivia (Generador y QA):** Usará tu motor para cargar datasets masivos y medir tiempos.

---

## 🧪 Cómo Verificar tu Código

Para compilar y probar tu motor:
```powershell
javac -d bin -sourcepath src/main/java (Get-ChildItem -Recurse -Filter *.java src/main/java).FullName
java -cp bin pe.edu.unmsm.lsdfms.Main
```
