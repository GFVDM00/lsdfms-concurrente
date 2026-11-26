package pe.edu.unmsm.lsdfms.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Repositorio de persistencia de Metadata en disco.
 * 
 * RESPONSABLE: Alejandro (Módulo 1)
 * REQUERIMIENTOS: RF-01, RNF-01
 */
public class MetadataRepository {
    private final File metadataFile;

    public MetadataRepository(File metadataFile) {
        this.metadataFile = metadataFile;
    }

    public synchronized Metadata load() throws IOException {
        Metadata metadata = new Metadata();
        if (!metadataFile.isFile()) {
            metadata.setIndexSynced(false);
            return metadata;
        }

        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(metadataFile)) {
            properties.load(input);
        }

        metadata.setActiveDataFile(properties.getProperty(
                "activeDataFile", metadata.getActiveDataFile()));
        metadata.setTotalRecords(readLong(properties, "totalRecords", 0));
        metadata.setActiveRecords(readLong(properties, "activeRecords", 0));
        metadata.setDeletedRecords(readLong(properties, "deletedRecords", 0));
        metadata.setLastOffset(readLong(properties, "lastOffset", 0));
        boolean complete = true;
        for (String key : new String[] {"activeDataFile", "totalRecords", "activeRecords",
                "deletedRecords", "lastOffset", "isIndexSynced", "lastCompactedAt",
                "lastIndexedAt", "indexedColumns"}) {
            complete &= properties.containsKey(key);
        }
        String synced = properties.getProperty("isIndexSynced", "false");
        if (!"true".equalsIgnoreCase(synced) && !"false".equalsIgnoreCase(synced)) {
            throw new IOException("Valor de metadata no válido para isIndexSynced: " + synced);
        }
        metadata.setIndexSynced(complete && Boolean.parseBoolean(synced));
        metadata.setLastCompactedAt(readLong(
                properties, "lastCompactedAt", metadata.getLastCompactedAt()));
        metadata.setLastIndexedAt(readLong(
                properties, "lastIndexedAt", metadata.getLastIndexedAt()));

        String columns = properties.getProperty("indexedColumns");
        if (columns == null || columns.trim().isEmpty()) {
            metadata.setIndexSynced(false);
        }
        if (columns != null) {
            List<String> indexedColumns = new ArrayList<>();
            Arrays.stream(columns.split(",", -1))
                    .map(String::trim)
                    .filter(column -> !column.isEmpty())
                    .forEach(indexedColumns::add);
            metadata.setIndexedColumns(indexedColumns);
            if (indexedColumns.isEmpty()) {
                metadata.setIndexSynced(false);
            }
        }
        return metadata;
    }

    public synchronized void save(Metadata meta) throws IOException {
        File parent = metadataFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("No se pudo crear el directorio de metadata: " + parent);
        }

        Properties properties = new Properties();
        properties.setProperty("activeDataFile", meta.getActiveDataFile());
        properties.setProperty("totalRecords", Long.toString(meta.getTotalRecords()));
        properties.setProperty("activeRecords", Long.toString(meta.getActiveRecords()));
        properties.setProperty("deletedRecords", Long.toString(meta.getDeletedRecords()));
        properties.setProperty("lastOffset", Long.toString(meta.getLastOffset()));
        properties.setProperty("isIndexSynced", Boolean.toString(meta.isIndexSynced()));
        properties.setProperty("lastCompactedAt", Long.toString(meta.getLastCompactedAt()));
        properties.setProperty("lastIndexedAt", Long.toString(meta.getLastIndexedAt()));
        properties.setProperty("indexedColumns", String.join(",", meta.getIndexedColumns()));

        Path temporary = Files.createTempFile(
                parent == null ? new File(".").toPath() : parent.toPath(),
                metadataFile.getName(), ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                properties.store(output, "LSDFMS metadata");
                output.getFD().sync();
            }
            try {
                Files.move(temporary, metadataFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long readLong(Properties properties, String key, long defaultValue)
            throws IOException {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IOException("Valor de metadata no válido para " + key + ": " + value, e);
        }
    }
}
