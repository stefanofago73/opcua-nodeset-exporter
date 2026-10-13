package opcua.nodeset.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProgressTest {

  private static final long S = 1_000_000_000L;

  @Test
  void hmsFormatting() {
    assertEquals("00:00:00", Progress.hms(0));
    assertEquals("00:00:00", Progress.hms(999_999_999L));
    assertEquals("00:00:59", Progress.hms(59 * S));
    assertEquals("00:01:00", Progress.hms(60 * S));
    assertEquals("01:01:01", Progress.hms(3661 * S));
    assertEquals("100:00:00", Progress.hms(360_000 * S));
  }

  @Test
  void dueAtMostOncePerInterval() throws Exception {
    Progress p = new Progress(1);
    assertFalse(p.due(), "not due right after creation");
    Thread.sleep(1100);
    assertTrue(p.due());
    assertFalse(p.due(), "not due again in the same interval");
  }

  @Test
  void rateAndEta() {
    Progress p = new Progress(3);
    assertEquals(0, p.rate(1000), "no rate in the first second");
    assertEquals("?", p.eta(0, 100), "no ETA before any progress");
    assertEquals("00:00:00", p.eta(100, 100));
  }
}
