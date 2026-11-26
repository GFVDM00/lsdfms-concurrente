package pe.edu.unmsm.lsdfms.qa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Resultado acumulativo de las verificaciones de integridad. */
public final class IntegrityReport {

    private final List<String> errors = new ArrayList<>();
    private long records;
    private long active;
    private long deleted;
    private long indexChecks;

    public void addError(String message) {
        errors.add(message);
    }

    public void addErrors(List<String> messages) {
        errors.addAll(messages);
    }

    public void setCounts(long records, long active, long deleted) {
        this.records = records;
        this.active = active;
        this.deleted = deleted;
    }

    public void incrementIndexChecks(long amount) {
        indexChecks += amount;
    }

    public boolean isValid() { return errors.isEmpty(); }
    public List<String> getErrors() { return List.copyOf(errors); }
    public long getRecords() { return records; }
    public long getActive() { return active; }
    public long getDeleted() { return deleted; }
    public long getIndexChecks() { return indexChecks; }

    public void requireValid() throws IOException {
        if (!isValid()) {
            throw new IOException("Errores de integridad (" + errors.size() + "):\n - "
                    + String.join("\n - ", errors));
        }
    }

    @Override
    public String toString() {
        return "IntegrityReport{" +
                "valid=" + isValid() +
                ", records=" + records +
                ", active=" + active +
                ", deleted=" + deleted +
                ", indexChecks=" + indexChecks +
                ", errors=" + errors.size() +
                '}';
    }
}
