package opcua.nodeset.exporter;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfigLimits;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

class ExporterIntegrationTest {

  private static PlantTestServer server;

  private static final Set<String> EXPECTED_PLANT_NODES = expectedPlantNodes();

  @BeforeAll
  static void startServer() throws Exception {
    server = PlantTestServer.start();
  }

  @AfterAll
  static void stopServer() throws Exception {
    server.stop();
  }

  private static Set<String> expectedPlantNodes() {
    Set<String> s = new TreeSet<>();
    for (String id :
        List.of(
            "Plant",
            "Plant/Line1",
            "Plant/Machine1",
            "Plant/Machine1/Temperature",
            "Plant/Machine1/Temperature/EngineeringUnits",
            "Plant/Machine1/Temperature/EURange",
            "Plant/Machine1/Counters",
            "Plant/Machine1/Reset",
            "Plant/Many",
            "Plant/Station1",
            "Plant/Remote",
            "Types/MachineType",
            "Types/SpeedType",
            "Types/MachineState",
            "Types/Feeds",
            "Types/HasStation",
            "Views/MaintenanceView")) {
      s.add("ns=1;s=" + id);
    }
    for (int i = 0; i < PlantTestServer.MANY_CHILDREN; i++) {
      s.add("ns=1;s=Plant/Many/Tag" + i);
    }
    return s;
  }

  private static NodeSetExporter.Options options(Path dir, String... extra) {
    List<String> args = new ArrayList<>(List.of("--endpoint", server.endpointUrl(), "--out", dir.toString(), "--name", "plant"));
    args.addAll(List.of(extra));
    return NodeSetExporter.parse(args.toArray(new String[0]));
  }

  private static Set<String> plantNodes(TestSupport.NodeSet ns) {
    // Only the plant namespace: the server may also publish its own session diagnostics nodes.
    String prefix = "ns=" + (ns.namespaceUris().indexOf(PlantTestServer.NAMESPACE_URI) + 1) + ";";
    return ns.nodes().keySet().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toCollection(TreeSet::new));
  }

  private static List<List<String>> plantRows(List<List<String>> rows) {
    return rows.stream()
        .skip(1)
        .filter(r -> r.get(1).equals(PlantTestServer.NAMESPACE_URI))
        .collect(Collectors.toList());
  }

  private static AddressSpaceCrawler crawlAndWrite(
      Path file, int browseBatch, Consumer<AddressSpaceCrawler> configure, PlantTestServer target)
      throws Exception {
    var o =
        NodeSetExporter.parse(new String[] {"--endpoint", target.endpointUrl(), "--browse-batch", Integer.toString(browseBatch)});
    OpcUaClient client = NodeSetExporter.createClient(o);
    client.connect();
    try {
      NamespaceTable nt = client.readNamespaceTable();
      var crawler = new AddressSpaceCrawler(client, nt, browseBatch, 2000, 0, 3);
      configure.accept(crawler);
      new NodeSetXmlWriter(nt).write(crawler.crawl(List.of(NodeIds.RootFolder)), file);
      return crawler;
    } finally {
      client.disconnect();
    }
  }

  // -----------------------------------------------------------------------------------------------

  @Test
  void fullExportIsSchemaValidAndContainsExactlyTheReadableNodes(@TempDir Path dir) throws Exception {
    NodeSetExporter.run(options(dir));
    Path xml = dir.resolve("plant.NodeSet2.xml");
    TestSupport.validateNodeSet(xml);

    TestSupport.NodeSet ns = TestSupport.parseNodeSet(xml);
    assertEquals(PlantTestServer.NAMESPACE_URI, ns.namespaceUris().get(0), "plant namespace is NodeSet index 1");
    // Secret is visible in Browse but not readable: skipped. Station1 is reachable only through the
    // custom hierarchical HasStation: found.
    assertEquals(EXPECTED_PLANT_NODES, plantNodes(ns));
    assertFalse(ns.nodes().containsKey("ns=1;s=Plant/Machine1/Secret"));
    assertFalse(ns.nodes().keySet().stream().anyMatch(k -> !k.startsWith("ns=")), "no ns=0 node exported");
  }

  @Test
  void nodeClassesAndAttributesAreWrittenCorrectly(@TempDir Path dir) throws Exception {
    NodeSetExporter.run(options(dir));
    TestSupport.NodeSet ns = TestSupport.parseNodeSet(dir.resolve("plant.NodeSet2.xml"));

    Element machine = ns.node("ns=1;s=Plant/Machine1");
    assertEquals("UAObject", machine.getLocalName());
    assertEquals("1:Machine1", machine.getAttribute("BrowseName"));
    assertEquals("1", machine.getAttribute("EventNotifier"));
    assertTrue(ns.references("ns=1;s=Plant/Machine1").contains("i=40|true|ns=1;s=Types/MachineType"));
    assertTrue(ns.references("ns=1;s=Plant/Machine1").contains("ns=1;s=Types/Feeds|true|ns=1;s=Plant/Line1"));

    Element counters = ns.node("ns=1;s=Plant/Machine1/Counters");
    assertEquals("UAVariable", counters.getLocalName());
    assertEquals("i=6", counters.getAttribute("DataType"));
    assertEquals("1", counters.getAttribute("ValueRank"));
    assertEquals("4", counters.getAttribute("ArrayDimensions"));
    assertEquals("3", counters.getAttribute("AccessLevel"));
    assertEquals("250.0", counters.getAttribute("MinimumSamplingInterval"));
    assertEquals("true", counters.getAttribute("Historizing"));
    assertEquals("Contatori; \"zona\" 1\nriga 2 °C", ns.childText("ns=1;s=Plant/Machine1/Counters", "Description"));
    Element description =
        (Element) counters.getElementsByTagNameNS(NodeSetXmlWriter.UA_NODESET_NS, "Description").item(0);
    assertEquals("it", description.getAttribute("Locale"));

    Element temperature = ns.node("ns=1;s=Plant/Machine1/Temperature");
    assertEquals("i=11", temperature.getAttribute("DataType"));
    assertFalse(temperature.hasAttribute("AccessLevel"), "AccessLevel=1 is the schema default");
    assertTrue(ns.references("ns=1;s=Plant/Machine1/Temperature").contains("i=40|true|i=2368"));

    Element property = ns.node("ns=1;s=Plant/Machine1/Temperature/EngineeringUnits");
    assertEquals("EngineeringUnits", property.getAttribute("BrowseName"), "ns=0 BrowseName has no prefix");

    assertEquals("false", ns.node("ns=1;s=Plant/Machine1/Reset").getAttribute("Executable"));
    assertEquals("UAMethod", ns.node("ns=1;s=Plant/Machine1/Reset").getLocalName());

    Element machineType = ns.node("ns=1;s=Types/MachineType");
    assertEquals("UAObjectType", machineType.getLocalName());
    assertFalse(machineType.hasAttribute("IsAbstract"));
    assertTrue(ns.references("ns=1;s=Types/MachineType").contains("i=45|false|i=58"));

    Element speedType = ns.node("ns=1;s=Types/SpeedType");
    assertEquals("UAVariableType", speedType.getLocalName());
    assertEquals("true", speedType.getAttribute("IsAbstract"));
    assertEquals("i=11", speedType.getAttribute("DataType"));

    assertEquals("UADataType", ns.node("ns=1;s=Types/MachineState").getLocalName());

    Element feeds = ns.node("ns=1;s=Types/Feeds");
    assertEquals("UAReferenceType", feeds.getLocalName());
    assertFalse(feeds.hasAttribute("Symmetric"));
    assertEquals("FedBy", ns.childText("ns=1;s=Types/Feeds", "InverseName"));

    Element view = ns.node("ns=1;s=Views/MaintenanceView");
    assertEquals("UAView", view.getLocalName());
    assertEquals("true", view.getAttribute("ContainsNoLoops"));

    assertTrue(
        ns.references("ns=1;s=Plant/Station1").contains("ns=1;s=Types/HasStation|false|ns=1;s=Plant"),
        "inverse custom hierarchical reference to the parent");
  }

  @Test
  void referencesAreSymmetricAndTheCycleIsVisitedOnce(@TempDir Path dir) throws Exception {
    NodeSetExporter.run(options(dir));
    TestSupport.NodeSet ns = TestSupport.parseNodeSet(dir.resolve("plant.NodeSet2.xml"));

    Set<String> forward = new HashSet<>();
    Set<String> inverse = new HashSet<>();
    for (String id : ns.nodes().keySet()) {
      for (String r : ns.references(id)) {
        String[] p = r.split("\\|", 3);
        if (p[1].equals("true")) {
          forward.add(id + "|" + p[0] + "|" + p[2]);
        } else {
          inverse.add(p[2] + "|" + p[0] + "|" + id);
        }
      }
    }
    for (String f : forward) {
      String target = f.substring(f.lastIndexOf('|') + 1);
      if (ns.nodes().containsKey(target)) {
        assertTrue(inverse.contains(f), "missing inverse reference for " + f);
      }
    }
    // Line1 organizes its parent Plant (a cycle): the reference is exported, the node only once.
    assertTrue(ns.references("ns=1;s=Plant/Line1").contains("i=35|true|ns=1;s=Plant"));
  }

  @Test
  void csvContainsOneRowPerReadableVariable(@TempDir Path dir) throws Exception {
    NodeSetExporter.run(options(dir));
    List<List<String>> rows = TestSupport.parseCsv(dir.resolve("plant.tags.csv"), ';');
    assertEquals(TagMappingCsvWriter.HEADER, rows.get(0));
    List<List<String>> data = plantRows(rows);
    for (List<String> r : rows) {
      assertEquals(TagMappingCsvWriter.HEADER.size(), r.size());
    }
    Map<String, List<String>> byPath =
        data.stream().collect(Collectors.toMap(r -> r.get(2), r -> r, (a, b) -> a));
    // Temperature, its 2 properties, Counters, 25 tags; Secret is skipped.
    assertEquals(4 + PlantTestServer.MANY_CHILDREN, data.size());
    assertFalse(byPath.containsKey("Root/Objects/Plant/Machine1/Secret"));

    List<String> temp = byPath.get("Root/Objects/Plant/Machine1/Temperature");
    String ns = Integer.toString(server.namespaceIndex());
    assertEquals("ns=" + ns + ";s=Plant/Machine1/Temperature", temp.get(0), "CSV keeps the server index");
    assertEquals(PlantTestServer.NAMESPACE_URI, temp.get(1));
    assertEquals("Double", temp.get(7));
    assertEquals("°C", temp.get(13));
    assertEquals("0.0..250.0", temp.get(14));

    List<String> counters = byPath.get("Root/Objects/Plant/Machine1/Counters");
    assertEquals("Contatori; \"zona\" 1\nriga 2 °C", counters.get(5));
    assertEquals("Int32", counters.get(7));
    assertEquals("1", counters.get(8));
    assertEquals("4", counters.get(9));
    assertEquals("CurrentRead|CurrentWrite", counters.get(10));
    assertEquals("true", counters.get(11));
    assertEquals("250.0", counters.get(12));
    assertEquals("", counters.get(13));
  }

  @Test
  void browseNextGivesTheSameResult(@TempDir Path dir) throws Exception {
    Path normal = dir.resolve("normal.xml");
    Path paged = dir.resolve("paged.xml");
    crawlAndWrite(normal, 200, c -> {}, server);
    // At most 2 references per node and per call: every node with more needs BrowseNext.
    crawlAndWrite(paged, 200, c -> c.setMaxReferencesPerNode(2), server);
    assertEquals(plantNodes(TestSupport.parseNodeSet(normal)), plantNodes(TestSupport.parseNodeSet(paged)));
    assertEquals(EXPECTED_PLANT_NODES, plantNodes(TestSupport.parseNodeSet(paged)));
    TestSupport.NodeSet a = TestSupport.parseNodeSet(normal);
    TestSupport.NodeSet b = TestSupport.parseNodeSet(paged);
    for (String id : EXPECTED_PLANT_NODES) {
      assertEquals(new TreeSet<>(a.references(id)), new TreeSet<>(b.references(id)), id);
    }
  }

  @Test
  void noContinuationPointsIsRetriedOneNodeAtATime(@TempDir Path dir) throws Exception {
    PlantTestServer limited =
        PlantTestServer.start(
            new OpcUaServerConfigLimits() {
              @Override
              public UShort getMaxBrowseContinuationPoints() {
                return ushort(1);
              }
            });
    try {
      Path file = dir.resolve("retry.xml");
      AddressSpaceCrawler crawler = crawlAndWrite(file, 200, c -> c.setMaxReferencesPerNode(2), limited);
      assertTrue(crawler.noContinuationPointRetries() > 0, "Bad_NoContinuationPoints path not exercised");
      TestSupport.validateNodeSet(file);
      assertEquals(EXPECTED_PLANT_NODES, plantNodes(TestSupport.parseNodeSet(file)));
    } finally {
      limited.stop();
    }
  }

  @Test
  void batchSizesFollowTheServerOperationLimits(@TempDir Path dir) throws Exception {
    PlantTestServer limited =
        PlantTestServer.start(
            new OpcUaServerConfigLimits() {
              @Override
              public UInteger getMaxNodesPerBrowse() {
                return uint(5);
              }

              @Override
              public UInteger getMaxNodesPerRead() {
                return uint(7);
              }
            });
    try {
      var o = NodeSetExporter.parse(new String[] {"--endpoint", limited.endpointUrl(), "--out", dir.toString(), "--name", "plant"});
      OpcUaClient client = NodeSetExporter.createClient(o);
      client.connect();
      try {
        int[] sizes = NodeSetExporter.batchSizes(client, o);
        assertEquals(5, sizes[0]);
        assertEquals(7, sizes[1]);
      } finally {
        client.disconnect();
      }
      NodeSetExporter.run(o);
      assertEquals(EXPECTED_PLANT_NODES, plantNodes(TestSupport.parseNodeSet(dir.resolve("plant.NodeSet2.xml"))));
    } finally {
      limited.stop();
    }
  }

  @Test
  void serverAllowingOneNodePerBrowseIsRejectedWithAClearError() throws Exception {
    PlantTestServer limited =
        PlantTestServer.start(
            new OpcUaServerConfigLimits() {
              @Override
              public UInteger getMaxNodesPerBrowse() {
                return uint(1);
              }
            });
    try {
      var o = NodeSetExporter.parse(new String[] {"--endpoint", limited.endpointUrl()});
      OpcUaClient client = NodeSetExporter.createClient(o);
      client.connect();
      try {
        UaException e = assertThrows(UaException.class, () -> NodeSetExporter.batchSizes(client, o));
        assertTrue(e.getMessage().contains("MaxNodesPerBrowse"), e.getMessage());
      } finally {
        client.disconnect();
      }
    } finally {
      limited.stop();
    }
  }

  @Test
  void maxNodesTruncatesTheCrawl(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("truncated.xml");
    var o = NodeSetExporter.parse(new String[] {"--endpoint", server.endpointUrl()});
    OpcUaClient client = NodeSetExporter.createClient(o);
    client.connect();
    try {
      NamespaceTable nt = client.readNamespaceTable();
      var crawler = new AddressSpaceCrawler(client, nt, 200, 2000, 50, 3);
      List<ExportedNode> nodes = crawler.crawl(List.of(NodeIds.RootFolder));
      assertTrue(crawler.isTruncated());
      new NodeSetXmlWriter(nt).write(nodes, file);
      TestSupport.validateNodeSet(file);
    } finally {
      client.disconnect();
    }
  }

  @Test
  void customRootExportsOnlyItsSubtree(@TempDir Path dir) throws Exception {
    String root = "ns=" + server.namespaceIndex() + ";s=Plant/Machine1";
    NodeSetExporter.run(options(dir, "--root", root));
    TestSupport.NodeSet ns = TestSupport.parseNodeSet(dir.resolve("plant.NodeSet2.xml"));
    assertEquals(
        new TreeSet<>(
            List.of(
                "ns=1;s=Plant/Machine1",
                "ns=1;s=Plant/Machine1/Temperature",
                "ns=1;s=Plant/Machine1/Temperature/EngineeringUnits",
                "ns=1;s=Plant/Machine1/Temperature/EURange",
                "ns=1;s=Plant/Machine1/Counters",
                "ns=1;s=Plant/Machine1/Reset")),
        new TreeSet<>(ns.nodes().keySet()));
    // References to nodes outside the subtree are kept (here the type definition MachineType).
    assertTrue(ns.references("ns=1;s=Plant/Machine1").contains("i=40|true|ns=1;s=Types/MachineType"));
    List<List<String>> rows = TestSupport.parseCsv(dir.resolve("plant.tags.csv"), ';');
    assertEquals("Machine1/Temperature", rows.stream().map(r -> r.get(2)).filter(p -> p.endsWith("/Temperature")).findFirst().orElseThrow());
  }

  @Test
  void customSeparatorAndKeepEndpointHost(@TempDir Path dir) throws Exception {
    NodeSetExporter.run(options(dir, "--csv-separator", ",", "--keep-endpoint-host", "--progress-seconds", "1"));
    List<List<String>> rows = TestSupport.parseCsv(dir.resolve("plant.tags.csv"), ',');
    assertEquals(TagMappingCsvWriter.HEADER, rows.get(0));
    assertEquals(4 + PlantTestServer.MANY_CHILDREN, plantRows(rows).size());
  }

  @Test
  void unreadableRootFails() {
    String root = "ns=" + server.namespaceIndex() + ";s=DoesNotExist";
    UaException e =
        assertThrows(
            UaException.class,
            () -> NodeSetExporter.run(NodeSetExporter.parse(new String[] {"--endpoint", server.endpointUrl(), "--root", root})));
    assertTrue(e.getMessage().contains("Root node not readable"), e.getMessage());
  }

  @Test
  void unreachableServerFails() throws Exception {
    String url = "opc.tcp://localhost:" + PlantTestServer.unusedPort() + "/plant";
    assertThrows(UaException.class, () -> NodeSetExporter.run(NodeSetExporter.parse(new String[] {"--endpoint", url})));
  }

  @Test
  void browseNameOfTheRootIsUsedForPaths(@TempDir Path dir) throws Exception {
    NodeSetExporter.run(options(dir));
    List<List<String>> rows = TestSupport.parseCsv(dir.resolve("plant.tags.csv"), ';');
    List<List<String>> plant = plantRows(rows);
    assertFalse(plant.isEmpty());
    assertTrue(plant.stream().allMatch(r -> r.get(2).startsWith("Root/Objects/Plant/")));
  }
}
