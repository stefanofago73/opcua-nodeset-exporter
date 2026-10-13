package opcua.nodeset.exporter;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeSetExporterTest {

  private static NodeSetExporter.Options parse(String... args) {
    return NodeSetExporter.parse(args);
  }

  private static String error(String... args) {
    return assertThrows(IllegalArgumentException.class, () -> NodeSetExporter.parse(args)).getMessage();
  }

  @Test
  void defaults() {
    var o = parse("--endpoint", "opc.tcp://h:4840");
    assertEquals("opc.tcp://h:4840", o.endpoint());
    assertEquals(List.of(NodeIds.RootFolder), o.roots());
    assertEquals(Path.of("export"), o.outDir());
    assertEquals("export", o.name());
    assertEquals(200, o.browseBatch());
    assertEquals(2000, o.readBatch());
    assertEquals(200_000, o.maxNodes());
    assertEquals(';', o.csvSeparator());
    assertFalse(o.keepEndpointHost());
    assertEquals(3, o.progressSeconds());
  }

  @Test
  void allOptions() {
    var o =
        parse(
            "--endpoint", "opc.tcp://h:4840/x",
            "--root", "ns=2;s=A",
            "--root", "i=85",
            "--out", "dir",
            "--name", "n",
            "--browse-batch", "2",
            "--read-batch", "1",
            "--max-nodes", "0",
            "--csv-separator", ",",
            "--keep-endpoint-host",
            "--progress-seconds", "1");
    assertEquals(List.of(NodeId.parse("ns=2;s=A"), NodeIds.ObjectsFolder), o.roots());
    assertEquals(Path.of("dir"), o.outDir());
    assertEquals("n", o.name());
    assertEquals(2, o.browseBatch());
    assertEquals(1, o.readBatch());
    assertEquals(0, o.maxNodes());
    assertEquals(',', o.csvSeparator());
    assertTrue(o.keepEndpointHost());
    assertEquals(1, o.progressSeconds());
  }

  @Test
  void invalidArguments() {
    assertEquals("--endpoint is required", error());
    assertEquals("unknown option: --bogus", error("--endpoint", "x", "--bogus"));
    assertEquals("missing value for --out", error("--endpoint", "x", "--out"));
    assertEquals("invalid number for --read-batch: abc", error("--endpoint", "x", "--read-batch", "abc"));
    assertEquals("--browse-batch must be >= 2", error("--endpoint", "x", "--browse-batch", "1"));
    assertEquals("--read-batch must be >= 1", error("--endpoint", "x", "--read-batch", "0"));
    assertEquals("--max-nodes must be >= 0", error("--endpoint", "x", "--max-nodes", "-1"));
    assertEquals("--progress-seconds must be >= 1", error("--endpoint", "x", "--progress-seconds", "0"));
    assertEquals("--csv-separator must be one character", error("--endpoint", "x", "--csv-separator", ";;"));
    assertTrue(error("--endpoint", "x", "--root", "not-a-node-id").startsWith("invalid NodeId for --root"));
  }

  @Test
  void clampAppliesServerLimits() {
    assertEquals(200, NodeSetExporter.clamp(200, Optional.empty(), 2));
    assertEquals(200, NodeSetExporter.clamp(200, Optional.of(uint(0)), 2), "0 = no limit");
    assertEquals(50, NodeSetExporter.clamp(200, Optional.of(uint(50)), 2));
    assertEquals(200, NodeSetExporter.clamp(200, Optional.of(uint(5000)), 2));
    assertEquals(2, NodeSetExporter.clamp(200, Optional.of(uint(1)), 2), "minimum enforced");
    assertEquals(2000, NodeSetExporter.clamp(2000, Optional.of(uint(4_000_000_000L)), 1), "UInt32 above int range");
  }

  @Test
  void sizeIsLocaleIndependent(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("f");
    Files.write(f, new byte[1_250_000]);
    assertEquals("1.3 MB", NodeSetExporter.size(f));
    Files.write(f, new byte[0]);
    assertEquals("0.0 MB", NodeSetExporter.size(f));
  }

  @Test
  void usageListsEveryOption() {
    for (String opt :
        List.of(
            "--endpoint", "--root", "--out", "--name", "--browse-batch", "--read-batch",
            "--max-nodes", "--csv-separator", "--keep-endpoint-host", "--progress-seconds")) {
      assertTrue(NodeSetExporter.USAGE.contains(opt), opt);
    }
  }
}
