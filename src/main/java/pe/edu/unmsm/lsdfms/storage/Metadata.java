package pe.edu.unmsm.lsdfms.storage;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Modelo de datos de Metadata para el sistema LSDFMS.
 * Mantiene el estado global de los archivos de datos e índices.
 */
public class Metadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private String activeDataFile;
    private long totalRecords;
    private long activeRecords;
    private long deletedRecords;
    private long lastOffset;
    private boolean indexSynced;
    private long lastCompactedAt;
    private long lastIndexedAt;
    private List<String> indexedColumns;

    public Metadata() {
        this.activeDataFile = "data/policies_data.dat";
        this.totalRecords = 0;
        this.activeRecords = 0;
        this.deletedRecords = 0;
        this.lastOffset = 0;
        this.indexSynced = true;
        this.lastCompactedAt = System.currentTimeMillis();
        this.lastIndexedAt = System.currentTimeMillis();
        this.indexedColumns = new ArrayList<>(List.of("id", "dni", "departamento", "tipoSeguro"));
    }

    public synchronized void recordInserted(long nextOffset) {
        this.totalRecords++;
        this.activeRecords++;
        this.lastOffset = nextOffset;
        this.indexSynced = false;
    }

    public synchronized void recordDeleted() {
        if (this.activeRecords > 0) {
            this.activeRecords--;
        }
        this.deletedRecords++;
        this.indexSynced = false;
    }

    public synchronized void resetAfterCompaction(long newTotalActive, long newLastOffset, String newFileName) {
        this.activeDataFile = newFileName;
        this.totalRecords = newTotalActive;
        this.activeRecords = newTotalActive;
        this.deletedRecords = 0;
        this.lastOffset = newLastOffset;
        this.lastCompactedAt = System.currentTimeMillis();
        this.indexSynced = false; // Los índices deben regenerarse tras PACK
    }

    public synchronized void markIndexesSynchronized() {
        this.indexSynced = true;
        this.lastIndexedAt = System.currentTimeMillis();
    }

    // Getters y Setters
    public String getActiveDataFile() { return activeDataFile; }
    public void setActiveDataFile(String activeDataFile) { this.activeDataFile = activeDataFile; }

    public long getTotalRecords() { return totalRecords; }
    public void setTotalRecords(long totalRecords) { this.totalRecords = totalRecords; }

    public long getActiveRecords() { return activeRecords; }
    public void setActiveRecords(long activeRecords) { this.activeRecords = activeRecords; }

    public long getDeletedRecords() { return deletedRecords; }
    public void setDeletedRecords(long deletedRecords) { this.deletedRecords = deletedRecords; }

    public long getLastOffset() { return lastOffset; }
    public void setLastOffset(long lastOffset) { this.lastOffset = lastOffset; }

    public boolean isIndexSynced() { return indexSynced; }
    public void setIndexSynced(boolean indexSynced) { this.indexSynced = indexSynced; }

    public long getLastCompactedAt() { return lastCompactedAt; }
    public void setLastCompactedAt(long lastCompactedAt) { this.lastCompactedAt = lastCompactedAt; }

    public long getLastIndexedAt() { return lastIndexedAt; }
    public void setLastIndexedAt(long lastIndexedAt) { this.lastIndexedAt = lastIndexedAt; }

    public List<String> getIndexedColumns() { return indexedColumns; }
    public void setIndexedColumns(List<String> indexedColumns) { this.indexedColumns = indexedColumns; }

    @Override
    public String toString() {
        return "Metadata{" +
                "activeDataFile='" + activeDataFile + '\'' +
                ", totalRecords=" + totalRecords +
                ", activeRecords=" + activeRecords +
                ", deletedRecords=" + deletedRecords +
                ", lastOffset=" + lastOffset +
                ", indexSynced=" + indexSynced +
                ", lastCompactedAt=" + lastCompactedAt +
                ", lastIndexedAt=" + lastIndexedAt +
                '}';
    }
}
