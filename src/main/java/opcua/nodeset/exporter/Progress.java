package opcua.nodeset.exporter;

final class Progress {

  private final long intervalNanos;
  private final long start = System.nanoTime();
  private long last = start;

  Progress(int intervalSeconds) {
    this.intervalNanos = intervalSeconds * 1_000_000_000L;
  }

  boolean due() {
    long now = System.nanoTime();
    if (now - last >= intervalNanos) {
      last = now;
      return true;
    }
    return false;
  }

  long elapsedNanos() {
    return System.nanoTime() - start;
  }

  String elapsed() {
    return hms(elapsedNanos());
  }

  long rate(long items) {
    long seconds = elapsedNanos() / 1_000_000_000L;
    return seconds == 0 ? 0 : items / seconds;
  }

  String eta(long done, long total) {
    if (done <= 0) {
      return "?";
    }
    return hms((long) ((double) elapsedNanos() * (total - done) / done));
  }

  static String hms(long nanos) {
    long s = nanos / 1_000_000_000L;
    return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
  }
}
