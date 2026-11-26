package pe.edu.unmsm.lsdfms.pack;

/** Half-open byte range [startOffset, endOffset) aligned to record boundaries. */
public final class DataPartition {
    private final long startOffset;
    private final long endOffset;

    public DataPartition(long startOffset, long endOffset) {
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("Invalid partition range: [" + startOffset + ", " + endOffset + ")");
        }
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public long getStartOffset() { return startOffset; }
    public long getEndOffset() { return endOffset; }
    public long size() { return endOffset - startOffset; }
    public boolean isEmpty() { return startOffset == endOffset; }

    @Override public String toString() { return "DataPartition[" + startOffset + ", " + endOffset + ")"; }
}
