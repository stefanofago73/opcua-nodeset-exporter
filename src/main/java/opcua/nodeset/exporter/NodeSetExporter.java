package opcua.nodeset.exporter;

import static java.util.Objects.requireNonNullElse;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.OperationLimits;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy;
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NodeSetExporter {

  private static final Logger LOG = LoggerFactory.getLogger(NodeSetExporter.class);

  static final String USAGE =
      """
      Usage: java -jar opcua-nodeset-exporter.jar --endpoint <opc.tcp://host:port[/path]> [options]

      Options:
        --endpoint <url>      OPC UA server endpoint (required)
        --root <nodeId>       crawl root, repeatable (default: i=84, RootFolder)
        --out <dir>           output directory (default: ./export)
        --name <base>         base name of the output files (default: export)
        --browse-batch <n>    max BrowseDescriptions per Browse request (default: 200)
        --read-batch <n>      max ReadValueIds per Read request (default: 2000)
        --max-nodes <n>       stop after visiting n nodes, 0 = no limit (default: 200000)
        --csv-separator <c>   CSV separator (default: ;)
        --progress-seconds <n> seconds between progress lines, >= 1 (default: 3)
        --keep-endpoint-host  use the host advertised by the server instead of the one in --endpoint

      Output: <out>/<name>.NodeSet2.xml and <out>/<name>.tags.csv
      Only SecurityPolicy None with anonymous identity is supported.
      """;

  record Options(
      String endpoint,
      List<NodeId> roots,
      Path outDir,
      String name,
      int browseBatch,
      int readBatch,
      int maxNodes,
      char csvSeparator,
      boolean keepEndpointHost,
      int progressSeconds) {}

  private NodeSetExporter() {}

  public static void main(String[] args) {
    Options options;
    try {
      options = parse(args);
    } catch (IllegalArgumentException e) {
      System.err.println("Error: " + e.getMessage());
      System.err.println();
      System.err.println(USAGE);
      System.exit(2);
      return;
    }
    try {
      run(options);
    } catch (Exception e) {
      LOG.error("Export failed: {}", e.toString(), e);
      System.exit(1);
    }
  }

  static Options parse(String[] args) {
    String endpoint = null;
    List<NodeId> roots = new ArrayList<>();
    Path outDir = Path.of("export");
    String name = "export";
    int browseBatch = 200;
    int readBatch = 2000;
    int maxNodes = 200_000;
    char separator = ';';
    boolean keepHost = false;
    int progressSeconds = 3;

    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      switch (a) {
        case "--endpoint" -> endpoint = value(args, ++i, a);
        case "--root" -> {
          String s = value(args, ++i, a);
          NodeId id = NodeId.parseOrNull(s);
          if (id == null) {
            throw new IllegalArgumentException("invalid NodeId for --root: " + s);
          }
          roots.add(id);
        }
        case "--out" -> outDir = Path.of(value(args, ++i, a));
        case "--name" -> name = value(args, ++i, a);
        case "--browse-batch" -> browseBatch = intValue(args, ++i, a, 2);
        case "--read-batch" -> readBatch = intValue(args, ++i, a, 1);
        case "--max-nodes" -> maxNodes = intValue(args, ++i, a, 0);
        case "--csv-separator" -> {
          String s = value(args, ++i, a);
          if (s.length() != 1) {
            throw new IllegalArgumentException("--csv-separator must be one character");
          }
          separator = s.charAt(0);
        }
        case "--keep-endpoint-host" -> keepHost = true;
        case "--progress-seconds" -> progressSeconds = intValue(args, ++i, a, 1);
        default -> throw new IllegalArgumentException("unknown option: " + a);
      }
    }
    if (endpoint == null) {
      throw new IllegalArgumentException("--endpoint is required");
    }
    if (roots.isEmpty()) {
      roots.add(NodeIds.RootFolder);
    }
    return new Options(
        endpoint, List.copyOf(roots), outDir, name, browseBatch, readBatch, maxNodes, separator,
        keepHost, progressSeconds);
  }

  private static String value(String[] args, int i, String option) {
    if (i >= args.length) {
      throw new IllegalArgumentException("missing value for " + option);
    }
    return args[i];
  }

  private static int intValue(String[] args, int i, String option, int min) {
    String s = value(args, i, option);
    try {
      int v = Integer.parseInt(s);
      if (v < min) {
        throw new IllegalArgumentException(option + " must be >= " + min);
      }
      return v;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("invalid number for " + option + ": " + s);
    }
  }

  static void run(Options o) throws Exception {
    Progress total = new Progress(o.progressSeconds());
    OpcUaClient client = createClient(o);
    client.connect();
    long connectNanos = total.elapsedNanos();
    try {
      NamespaceTable namespaces = client.readNamespaceTable();
      LOG.info("Connected to {}; {} namespaces", o.endpoint(), namespaces.toArray().length);

      int[] batches = batchSizes(client, o);
      int browseBatch = batches[0];
      int readBatch = batches[1];
      LOG.info("Batch sizes: browse={}, read={}", browseBatch, readBatch);

      var crawler =
          new AddressSpaceCrawler(
              client, namespaces, browseBatch, readBatch, o.maxNodes(), o.progressSeconds());
      List<ExportedNode> nodes = crawler.crawl(o.roots());

      Files.createDirectories(o.outDir());
      Path xml = o.outDir().resolve(o.name() + ".NodeSet2.xml");
      Path csv = o.outDir().resolve(o.name() + ".tags.csv");

      Progress write = new Progress(o.progressSeconds());
      LOG.info("Write NodeSet {} ...", xml.getFileName());
      Progress step = new Progress(o.progressSeconds());
      new NodeSetXmlWriter(namespaces).write(nodes, xml);
      LOG.info("Write NodeSet done in {} ({})", step.elapsed(), size(xml));
      LOG.info("Write CSV {} ...", csv.getFileName());
      step = new Progress(o.progressSeconds());
      int tags = new TagMappingCsvWriter(namespaces, o.csvSeparator()).write(nodes, csv);
      LOG.info("Write CSV done in {} ({})", step.elapsed(), size(csv));
      long writeNanos = write.elapsedNanos();

      LOG.info("Exported {} nodes to {}", nodes.size(), xml.toAbsolutePath());
      LOG.info("Exported {} variables to {}", tags, csv.toAbsolutePath());
      if (crawler.skippedReferences() > 0) {
        LOG.warn("{} references to nodes on other servers were not exported", crawler.skippedReferences());
      }
      if (crawler.isTruncated()) {
        LOG.warn("Crawl stopped at --max-nodes={}: the export is INCOMPLETE", o.maxNodes());
      }
      LOG.info(
          "Done in {} (connect {}, browse {}, read {}, write {})",
          total.elapsed(), Progress.hms(connectNanos), Progress.hms(crawler.browseNanos()),
          Progress.hms(crawler.readNanos()), Progress.hms(writeNanos));
    } finally {
      client.disconnect();
    }
  }

  static String size(Path file) throws java.io.IOException {
    return String.format(java.util.Locale.ROOT, "%.1f MB", Files.size(file) / 1_000_000.0);
  }

  static int[] batchSizes(OpcUaClient client, Options o) throws UaException {
    int browseBatch = o.browseBatch();
    int readBatch = o.readBatch();
    OperationLimits limits;
    try {
      limits = client.getOperationLimits();
    } catch (UaException e) {
      LOG.info("OperationLimits not readable ({}); using configured batch sizes", e.getMessage());
      return new int[] {browseBatch, readBatch};
    }
    long maxBrowse = limits.maxNodesPerBrowse().map(UInteger::longValue).orElse(0L);
    if (maxBrowse == 1) {
      throw new UaException(
          StatusCodes.Bad_ConfigurationError,
          "server MaxNodesPerBrowse is 1; this exporter needs at least 2");
    }
    return new int[] {
      clamp(browseBatch, limits.maxNodesPerBrowse(), 2), clamp(readBatch, limits.maxNodesPerRead(), 1)
    };
  }

  static int clamp(int configured, Optional<UInteger> serverLimit, int min) {
    long limit = serverLimit.map(UInteger::longValue).orElse(0L);
    long v = limit > 0 ? Math.min(configured, limit) : configured;
    return (int) Math.max(v, min);
  }

  static OpcUaClient createClient(Options o) throws UaException {
    String host = EndpointUtil.getHost(o.endpoint());
    int port = EndpointUtil.getPort(o.endpoint());

    return OpcUaClient.create(
        o.endpoint(),
        endpoints ->
            endpoints.stream()
                .filter(NodeSetExporter::isNoneAnonymous)
                .findFirst()
                .map(e -> o.keepEndpointHost() ? e : EndpointUtil.updateUrl(e, host, port)),
        transport -> {},
        config ->
            config
                .setApplicationName(LocalizedText.english("opcua-nodeset-exporter"))
                .setApplicationUri("urn:opcua-nodeset-exporter:client")
                .setRequestTimeout(uint(60_000)));
  }

  private static boolean isNoneAnonymous(EndpointDescription e) {
    return SecurityPolicy.None.getUri().equals(e.getSecurityPolicyUri())
        && Stream.of(requireNonNullElse(e.getUserIdentityTokens(), new UserTokenPolicy[0]))
            .anyMatch(p -> p.getTokenType() == UserTokenType.Anonymous);
  }
}
