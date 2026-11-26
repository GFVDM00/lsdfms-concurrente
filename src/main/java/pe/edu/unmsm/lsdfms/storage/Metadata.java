package pe.edu.unmsm.lsdfms.storage;

import java.io.Serializable;
import java.util.AbstractList;
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
    private transient volatile Runnable indexSyncListener;
    private transient boolean indexSyncedChanged;
    private transient boolean lastIndexedAtChanged;
    private transient boolean indexedColumnsChanged;

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

    /** Crea una vista coherente e independiente para consumidores de solo lectura. */
    public synchronized Metadata snapshot() {
        Metadata copy = new Metadata();
        copy.activeDataFile = activeDataFile;
        copy.totalRecords = totalRecords;
        copy.activeRecords = activeRecords;
        copy.deletedRecords = deletedRecords;
        copy.lastOffset = lastOffset;
        copy.indexSynced = indexSynced;
        copy.lastCompactedAt = lastCompactedAt;
        copy.lastIndexedAt = lastIndexedAt;
        copy.indexedColumns = new TrackedList(new ArrayList<>(indexedColumns),
                () -> copy.indexedColumnsChanged = true);
        return copy;
    }

    synchronized boolean hasIndexSyncChanges() {
        return indexSyncedChanged || lastIndexedAtChanged || indexedColumnsChanged;
    }

    synchronized void applyIndexSyncChanges(Metadata view) {
        if (view.indexSyncedChanged) indexSynced = view.indexSynced;
        if (view.lastIndexedAtChanged) lastIndexedAt = view.lastIndexedAt;
        if (view.indexedColumnsChanged) indexedColumns = new ArrayList<>(view.indexedColumns);
    }

    synchronized void restore(Metadata copy) {
        activeDataFile = copy.activeDataFile;
        totalRecords = copy.totalRecords;
        activeRecords = copy.activeRecords;
        deletedRecords = copy.deletedRecords;
        lastOffset = copy.lastOffset;
        indexSynced = copy.indexSynced;
        lastCompactedAt = copy.lastCompactedAt;
        lastIndexedAt = copy.lastIndexedAt;
        indexedColumns = new ArrayList<>(copy.indexedColumns);
    }

    void setIndexSyncListener(Runnable listener) {
        indexSyncListener = listener;
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

    public void markIndexesSynchronized() {
        synchronized (this) {
            this.indexSynced = true;
            this.lastIndexedAt = System.currentTimeMillis();
            this.indexSyncedChanged = true;
            this.lastIndexedAtChanged = true;
        }
        if (indexSyncListener != null) {
            indexSyncListener.run();
        }
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
    public synchronized void setIndexSynced(boolean indexSynced) {
        this.indexSynced = indexSynced;
        this.indexSyncedChanged = true;
    }

    public long getLastCompactedAt() { return lastCompactedAt; }
    public void setLastCompactedAt(long lastCompactedAt) { this.lastCompactedAt = lastCompactedAt; }

    public long getLastIndexedAt() { return lastIndexedAt; }
    public synchronized void setLastIndexedAt(long lastIndexedAt) {
        this.lastIndexedAt = lastIndexedAt;
        this.lastIndexedAtChanged = true;
    }

    public List<String> getIndexedColumns() { return indexedColumns; }
    public synchronized void setIndexedColumns(List<String> indexedColumns) {
        this.indexedColumns = new TrackedList(new ArrayList<>(indexedColumns),
                () -> indexedColumnsChanged = true);
        this.indexedColumnsChanged = true;
    }

    private static final class TrackedList extends AbstractList<String> {
        private final List<String> values;
        private final Runnable changed;
        TrackedList(List<String> values, Runnable changed) {
            this.values = values;
            this.changed = changed;
        }
        @Override public String get(int index) { return values.get(index); }
        @Override public int size() { return values.size(); }
        @Override public String set(int index, String element) {
            String previous = values.set(index, element);
            changed.run();
            return previous;
        }
        @Override public void add(int index, String element) {
            values.add(index, element);
            changed.run();
        }
        @Override public String remove(int index) {
            String previous = values.remove(index);
            changed.run();
            return previous;
        }
    }

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
