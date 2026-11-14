package dev.penguinsuit.reverse;

/** Counts native byte-array references retained by jobs in Penguin's shared send queue. */
public final class OutboundBudget {
    private long bytes; private int jobs;
    public synchronized Lease reserve(long amount, long maxBytes, int maxJobs) {
        if (amount < 1 || amount > maxBytes - bytes || jobs >= maxJobs) return null;
        bytes += amount; jobs++; return new Lease(amount);
    }
    public synchronized long bytes() { return bytes; }
    public synchronized int jobs() { return jobs; }
    public final class Lease implements AutoCloseable {
        private final long amount; private boolean released;
        private Lease(long amount) { this.amount = amount; }
        public void close() { synchronized (OutboundBudget.this) {
            if (!released) { released = true; bytes -= amount; jobs--; }
        } }
    }
}
