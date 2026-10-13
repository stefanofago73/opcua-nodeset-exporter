package opcua.nodeset.exporter;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExtensionObject;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseNextResponse;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResponse;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.EUInformation;
import org.eclipse.milo.opcua.stack.core.types.structured.Range;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadResponse;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ViewDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AddressSpaceCrawler {

  private static final Logger LOG = LoggerFactory.getLogger(AddressSpaceCrawler.class);

  private static final UInteger RESULT_MASK_ALL = uint(BrowseResultMask.All.getValue());
  private static final UInteger ALL_NODE_CLASSES = uint(0);
  private static final ViewDescription NO_VIEW =
      new ViewDescription(NodeId.NULL_VALUE, DateTime.MIN_VALUE, uint(0));
  private static final int MAX_BROWSE_NEXT_CALLS = 10_000;

  private static final List<AttributeId> COMMON_ATTRIBUTES =
      List.of(AttributeId.Description, AttributeId.WriteMask);

  private final OpcUaClient client;
  private final NamespaceTable namespaceTable;
  private final int browseBatch;
  private final int readBatch;
  private final int maxNodes;
  private final int progressSeconds;

  private UInteger maxReferencesPerNode = uint(0);
  private int noContinuationPointRetries;

  private boolean truncated;
  private int skippedReferences;
  private long browseNanos;
  private long readNanos;
  private final Map<Integer, long[]> perNamespace = new TreeMap<>();

  AddressSpaceCrawler(
      OpcUaClient client,
      NamespaceTable namespaceTable,
      int browseBatch,
      int readBatch,
      int maxNodes,
      int progressSeconds) {
    if (browseBatch < 2) {
      throw new IllegalArgumentException("browseBatch must be >= 2");
    }
    if (readBatch < 1) {
      throw new IllegalArgumentException("readBatch must be >= 1");
    }
    this.client = client;
    this.namespaceTable = namespaceTable;
    this.browseBatch = browseBatch;
    this.readBatch = readBatch;
    this.maxNodes = maxNodes;
    this.progressSeconds = progressSeconds;
  }

  void setMaxReferencesPerNode(int max) {
    this.maxReferencesPerNode = uint(max);
  }

  int noContinuationPointRetries() {
    return noContinuationPointRetries;
  }

  boolean isTruncated() {
    return truncated;
  }

  long browseNanos() {
    return browseNanos;
  }

  long readNanos() {
    return readNanos;
  }

  int skippedReferences() {
    return skippedReferences;
  }

  static boolean isExported(NodeId nodeId) {
    return nodeId.getNamespaceIndex().intValue() != 0;
  }

  private record Pending(
      NodeId nodeId,
      NodeClass nodeClass,
      QualifiedName browseName,
      LocalizedText displayName,
      String path) {}

  private record Found(Pending pending, List<ExportedNode.Ref> refs) {}

  List<ExportedNode> crawl(List<NodeId> roots) throws UaException {
    Deque<Pending> queue = new ArrayDeque<>();
    Set<NodeId> visited = new HashSet<>();
    List<Found> found = new ArrayList<>();
    Progress progress = new Progress(progressSeconds);

    for (Pending root : readRoots(roots)) {
      if (visited.add(root.nodeId())) {
        countVisited(root.nodeId(), root.path());
        queue.add(root);
      }
    }

    while (!queue.isEmpty()) {
      List<Pending> batch = new ArrayList<>();
      List<BrowseDescription> descriptions = new ArrayList<>();

      while (!queue.isEmpty() && descriptions.size() + 2 <= browseBatch) {
        Pending p = queue.poll();
        batch.add(p);
        descriptions.add(
            new BrowseDescription(
                p.nodeId(),
                BrowseDirection.Forward,
                NodeIds.HierarchicalReferences,
                true,
                ALL_NODE_CLASSES,
                RESULT_MASK_ALL));
        if (isExported(p.nodeId())) {
          descriptions.add(
              new BrowseDescription(
                  p.nodeId(),
                  BrowseDirection.Both,
                  NodeIds.References,
                  true,
                  ALL_NODE_CLASSES,
                  RESULT_MASK_ALL));
        }
      }

      List<List<ReferenceDescription>> results = browseAll(descriptions);

      int i = 0;
      for (Pending p : batch) {
        for (ReferenceDescription rd : results.get(i++)) {
          Optional<NodeId> target = rd.getNodeId().toNodeId(namespaceTable);
          if (target.isEmpty() || visited.contains(target.get())) {
            continue;
          }
          if (maxNodes > 0 && visited.size() >= maxNodes) {
            truncated = true;
            break;
          }
          visited.add(target.get());
          String path = p.path() + "/" + name(rd.getBrowseName());
          countVisited(target.get(), path);
          queue.add(
              new Pending(
                  target.get(), rd.getNodeClass(), rd.getBrowseName(), rd.getDisplayName(), path));
        }
        if (isExported(p.nodeId())) {
          found.add(new Found(p, toRefs(results.get(i++))));
          perNamespace.get(p.nodeId().getNamespaceIndex().intValue())[1]++;
        }
      }

      if (progress.due()) {
        LOG.info(
            "Browse {} visited={} ({}) exported={} queued={} ({} nodes/s)",
            progress.elapsed(), visited.size(), visitedByNamespace(), found.size(), queue.size(),
            progress.rate(visited.size()));
      }
    }
    browseNanos = progress.elapsedNanos();
    LOG.info(
        "Browse done in {}: visited={} exported={}",
        Progress.hms(browseNanos), visited.size(), found.size());
    for (Map.Entry<Integer, long[]> e : perNamespace.entrySet()) {
      LOG.info(
          "  ns={} {}: visited={} exported={}",
          e.getKey(), namespaceTable.get(e.getKey()), e.getValue()[0], e.getValue()[1]);
    }

    Progress readProgress = new Progress(progressSeconds);
    List<ExportedNode> nodes = readNodes(found);
    readNanos = readProgress.elapsedNanos();
    LOG.info("Read done in {}", Progress.hms(readNanos));
    return nodes;
  }

  private void countVisited(NodeId nodeId, String path) {
    int ns = nodeId.getNamespaceIndex().intValue();
    long[] counters = perNamespace.get(ns);
    if (counters == null) {
      counters = new long[2];
      perNamespace.put(ns, counters);
      LOG.info("Namespace ns={} {} first seen at {}", ns, namespaceTable.get(ns), path);
    }
    counters[0]++;
  }

  private String visitedByNamespace() {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<Integer, long[]> e : perNamespace.entrySet()) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append("ns").append(e.getKey()).append('=').append(e.getValue()[0]);
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // Browse

  private List<List<ReferenceDescription>> browseAll(List<BrowseDescription> descriptions)
      throws UaException {

    BrowseResponse response = client.browse(NO_VIEW, maxReferencesPerNode, descriptions);
    BrowseResult[] results = response.getResults();
    if (results == null || results.length != descriptions.size()) {
      throw new UaException(
          StatusCodes.Bad_UnexpectedError,
          "Browse returned " + (results == null ? 0 : results.length) + " results for "
              + descriptions.size() + " requests");
    }

    List<List<ReferenceDescription>> out = new ArrayList<>(descriptions.size());
    List<Integer> retry = new ArrayList<>();

    // First follow the continuation points of the successful results, so that they are released.
    for (int i = 0; i < results.length; i++) {
      if (results[i].getStatusCode().getValue() == StatusCodes.Bad_NoContinuationPoints) {
        out.add(null);
        retry.add(i);
        noContinuationPointRetries++;
      } else {
        out.add(collect(descriptions.get(i).getNodeId(), results[i]));
      }
    }

    // Then retry, one at a time, the nodes for which the server had no continuation point left.
    for (int i : retry) {
      BrowseResponse single = client.browse(NO_VIEW, maxReferencesPerNode, List.of(descriptions.get(i)));
      BrowseResult[] r = single.getResults();
      out.set(
          i,
          r == null || r.length == 0
              ? new ArrayList<>()
              : collect(descriptions.get(i).getNodeId(), r[0]));
    }

    return out;
  }

  private List<ReferenceDescription> collect(NodeId nodeId, BrowseResult result)
      throws UaException {

    List<ReferenceDescription> references = new ArrayList<>();
    StatusCode status = result.getStatusCode();
    if (status.isBad()) {
      LOG.warn("Browse of {} failed: {}", nodeId.toParseableString(), status);
      return references;
    }
    addAll(references, result.getReferences());

    ByteString cp = result.getContinuationPoint();
    int calls = 0;
    while (cp != null && !cp.isNullOrEmpty()) {
      if (++calls > MAX_BROWSE_NEXT_CALLS) {
        client.browseNext(true, List.of(cp));
        LOG.warn("BrowseNext of {} stopped after {} calls", nodeId.toParseableString(), calls - 1);
        break;
      }
      BrowseNextResponse next = client.browseNext(false, List.of(cp));
      BrowseResult[] r = next.getResults();
      if (r == null || r.length == 0) {
        LOG.warn("BrowseNext of {} returned no results", nodeId.toParseableString());
        break;
      }
      if (r[0].getStatusCode().isBad()) {
        LOG.warn("BrowseNext of {} failed: {}", nodeId.toParseableString(), r[0].getStatusCode());
        break;
      }
      addAll(references, r[0].getReferences());
      cp = r[0].getContinuationPoint();
    }
    return references;
  }

  private static void addAll(List<ReferenceDescription> list, ReferenceDescription[] array) {
    if (array != null) {
      list.addAll(Arrays.asList(array));
    }
  }

  List<ExportedNode.Ref> toRefs(List<ReferenceDescription> descriptions) {
    List<ExportedNode.Ref> refs = new ArrayList<>(descriptions.size());
    for (ReferenceDescription rd : descriptions) {
      Optional<NodeId> target = rd.getNodeId().toNodeId(namespaceTable);
      if (target.isEmpty()) {
        skippedReferences++;
        LOG.debug("Skipped reference to non-local node {}", rd.getNodeId().toParseableString());
        continue;
      }
      refs.add(
          new ExportedNode.Ref(
              rd.getReferenceTypeId(),
              Boolean.TRUE.equals(rd.getIsForward()),
              target.get(),
              rd.getBrowseName()));
    }
    return refs;
  }

  // ---------------------------------------------------------------------------------------------
  // Read

  private List<Pending> readRoots(List<NodeId> roots) throws UaException {
    List<ReadValueId> ids = new ArrayList<>();
    for (NodeId root : roots) {
      ids.add(readValueId(root, AttributeId.NodeClass));
      ids.add(readValueId(root, AttributeId.BrowseName));
      ids.add(readValueId(root, AttributeId.DisplayName));
    }
    List<DataValue> values = readAll("root attributes", ids);

    List<Pending> pending = new ArrayList<>();
    for (int i = 0; i < roots.size(); i++) {
      DataValue nodeClass = values.get(3 * i);
      if (!nodeClass.getStatusCode().isGood()) {
        throw new UaException(
            nodeClass.getStatusCode(), "Root node not readable: " + roots.get(i).toParseableString());
      }
      NodeClass nc = good(nodeClass) instanceof Number n ? NodeClass.from(n.intValue()) : null;
      QualifiedName qn =
          good(values.get(3 * i + 1)) instanceof QualifiedName q
              ? q
              : new QualifiedName(0, roots.get(i).toParseableString());
      LocalizedText dn =
          good(values.get(3 * i + 2)) instanceof LocalizedText t ? t : LocalizedText.NULL_VALUE;
      pending.add(new Pending(roots.get(i), nc, qn, dn, name(qn)));
    }
    return pending;
  }

  private List<ExportedNode> readNodes(List<Found> found) throws UaException {
    // NodeClass comes from Browse; it is read only where Browse did not return a valid one.
    List<NodeClass> fromBrowse = new ArrayList<>(found.size());
    List<NodeId> nodeIds = new ArrayList<>(found.size());
    for (Found f : found) {
      fromBrowse.add(f.pending().nodeClass());
      nodeIds.add(f.pending().nodeId());
    }
    List<NodeClass> classes = resolveNodeClasses(fromBrowse, nodeIds, this::readAll);

    // One pass: Description, WriteMask and the attributes specific to the node class.
    List<ReadValueId> ids = new ArrayList<>();
    for (int i = 0; i < found.size(); i++) {
      for (AttributeId a : attributesToRead(classes.get(i))) {
        ids.add(readValueId(found.get(i).pending().nodeId(), a));
      }
    }
    List<DataValue> values = readAll("attributes (1/3)", ids);

    // Names of the referenced DataTypes, for the CSV.
    Map<NodeId, String> dataTypeNames = new HashMap<>();
    // EngineeringUnits / EURange property nodes, by owning variable.
    Map<NodeId, NodeId> euProperty = new HashMap<>();
    Map<NodeId, NodeId> rangeProperty = new HashMap<>();

    List<Map<AttributeId, Object>> attributeMaps = new ArrayList<>(found.size());
    List<Boolean> readable = new ArrayList<>(found.size());
    int s = 0;
    for (int i = 0; i < found.size(); i++) {
      Map<AttributeId, Object> attrs = new EnumMap<>(AttributeId.class);
      boolean anyGood = false;
      for (AttributeId a : attributesToRead(classes.get(i))) {
        DataValue dv = values.get(s++);
        if (dv.getStatusCode().isGood()) {
          anyGood = true;
          Object v = dv.getValue().getValue();
          if (v != null) {
            attrs.put(a, v);
          }
        }
      }
      if (!(attrs.get(AttributeId.WriteMask) instanceof UInteger)) {
        attrs.remove(AttributeId.WriteMask);
      }
      attributeMaps.add(attrs);
      readable.add(anyGood);
      if (attrs.get(AttributeId.DataType) instanceof NodeId dt) {
        dataTypeNames.put(dt, null);
      }
      if (classes.get(i) == NodeClass.Variable) {
        for (ExportedNode.Ref r : found.get(i).refs()) {
          if (r.forward() && NodeIds.HasProperty.equals(r.referenceTypeId())) {
            QualifiedName qn = r.targetBrowseName();
            if (qn != null && qn.namespaceIndex().intValue() == 0) {
              if ("EngineeringUnits".equals(qn.name())) {
                euProperty.put(found.get(i).pending().nodeId(), r.targetId());
              } else if ("EURange".equals(qn.name())) {
                rangeProperty.put(found.get(i).pending().nodeId(), r.targetId());
              }
            }
          }
        }
      }
    }

    readDataTypeNames(dataTypeNames);
    Map<NodeId, Object> euValues = readPropertyValues("EngineeringUnits (3/3)", euProperty);
    Map<NodeId, Object> rangeValues = readPropertyValues("EURange (3/3)", rangeProperty);

    List<ExportedNode> nodes = new ArrayList<>(found.size());
    for (int i = 0; i < found.size(); i++) {
      Found f = found.get(i);
      NodeId nodeId = f.pending().nodeId();
      NodeClass nc = classes.get(i);
      if (nc == null) {
        LOG.warn("Skipped {}: NodeClass not readable", nodeId.toParseableString());
        continue;
      }
      // Every NodeClass has at least one mandatory attribute among those read, so a node with
      // no Good read is not readable by this session (e.g. Bad_UserAccessDenied).
      if (!readable.get(i)) {
        LOG.warn("Skipped {}: attributes not readable", nodeId.toParseableString());
        continue;
      }
      Map<AttributeId, Object> attrs = attributeMaps.get(i);
      LocalizedText description =
          attrs.remove(AttributeId.Description) instanceof LocalizedText t
              ? t
              : LocalizedText.NULL_VALUE;
      QualifiedName browseName = f.pending().browseName();
      LocalizedText displayName =
          f.pending().displayName() != null ? f.pending().displayName() : LocalizedText.NULL_VALUE;

      String dataTypeName =
          attrs.get(AttributeId.DataType) instanceof NodeId dt ? dataTypeNames.get(dt) : null;
      String eu = euValues.get(nodeId) instanceof EUInformation info ? text(info.getDisplayName()) : null;
      String range =
          rangeValues.get(nodeId) instanceof Range r ? r.getLow() + ".." + r.getHigh() : null;

      nodes.add(
          new ExportedNode(
              nodeId,
              nc,
              browseName,
              displayName,
              description,
              f.pending().path(),
              attrs,
              f.refs(),
              dataTypeName,
              eu,
              range));
    }
    return nodes;
  }

  interface Reader {
    List<DataValue> read(String label, List<ReadValueId> ids) throws UaException;
  }

  static List<NodeClass> resolveNodeClasses(
      List<NodeClass> fromBrowse, List<NodeId> nodeIds, Reader reader) throws UaException {
    List<NodeClass> classes = new ArrayList<>(fromBrowse.size());
    List<Integer> unknown = new ArrayList<>();
    for (int i = 0; i < fromBrowse.size(); i++) {
      NodeClass nc = fromBrowse.get(i);
      if (nc == null || nc == NodeClass.Unspecified) {
        unknown.add(i);
        nc = null;
      }
      classes.add(nc);
    }
    if (!unknown.isEmpty()) {
      List<ReadValueId> ids = new ArrayList<>();
      for (int i : unknown) {
        ids.add(readValueId(nodeIds.get(i), AttributeId.NodeClass));
      }
      List<DataValue> values = reader.read("NodeClass missing from Browse", ids);
      for (int k = 0; k < unknown.size(); k++) {
        Object v = good(values.get(k));
        NodeClass nc = v instanceof Number n ? NodeClass.from(n.intValue()) : null;
        classes.set(unknown.get(k), nc == NodeClass.Unspecified ? null : nc);
      }
    }
    return classes;
  }

  static List<AttributeId> attributesToRead(NodeClass nodeClass) {
    if (nodeClass == null) {
      return List.of();
    }
    List<AttributeId> list = new ArrayList<>(COMMON_ATTRIBUTES);
    list.addAll(specificAttributes(nodeClass));
    return list;
  }

  static List<AttributeId> specificAttributes(NodeClass nodeClass) {
    if (nodeClass == null) {
      return List.of();
    }
    return switch (nodeClass) {
      case Object -> List.of(AttributeId.EventNotifier);
      case Variable ->
          List.of(
              AttributeId.DataType,
              AttributeId.ValueRank,
              AttributeId.ArrayDimensions,
              AttributeId.AccessLevel,
              AttributeId.MinimumSamplingInterval,
              AttributeId.Historizing);
      case Method -> List.of(AttributeId.Executable);
      case ObjectType, DataType -> List.of(AttributeId.IsAbstract);
      case VariableType ->
          List.of(
              AttributeId.IsAbstract,
              AttributeId.DataType,
              AttributeId.ValueRank,
              AttributeId.ArrayDimensions);
      case ReferenceType ->
          List.of(AttributeId.IsAbstract, AttributeId.Symmetric, AttributeId.InverseName);
      case View -> List.of(AttributeId.ContainsNoLoops, AttributeId.EventNotifier);
      default -> List.of();
    };
  }

  private void readDataTypeNames(Map<NodeId, String> names) throws UaException {
    List<NodeId> ids = new ArrayList<>(new LinkedHashSet<>(names.keySet()));
    List<ReadValueId> reads = new ArrayList<>();
    for (NodeId id : ids) {
      reads.add(readValueId(id, AttributeId.BrowseName));
    }
    List<DataValue> values = readAll("data type names (2/3)", reads);
    for (int i = 0; i < ids.size(); i++) {
      if (good(values.get(i)) instanceof QualifiedName q) {
        names.put(ids.get(i), q.name());
      }
    }
  }

  private Map<NodeId, Object> readPropertyValues(String label, Map<NodeId, NodeId> propertyByOwner)
      throws UaException {
    List<NodeId> owners = new ArrayList<>(propertyByOwner.keySet());
    List<ReadValueId> reads = new ArrayList<>();
    for (NodeId owner : owners) {
      reads.add(readValueId(propertyByOwner.get(owner), AttributeId.Value));
    }
    List<DataValue> values = readAll(label, reads);
    Map<NodeId, Object> out = new HashMap<>();
    for (int i = 0; i < owners.size(); i++) {
      Object v = good(values.get(i));
      if (v instanceof ExtensionObject xo) {
        try {
          v = xo.decode(client.getStaticEncodingContext());
        } catch (RuntimeException e) {
          LOG.debug("Cannot decode property of {}: {}", owners.get(i).toParseableString(), e.toString());
          v = null;
        }
      }
      if (v != null) {
        out.put(owners.get(i), v);
      }
    }
    return out;
  }

  private List<DataValue> readAll(String label, List<ReadValueId> ids) throws UaException {
    List<DataValue> out = new ArrayList<>(ids.size());
    if (ids.isEmpty()) {
      return out;
    }
    LOG.info("Read {}: {} attributes", label, ids.size());
    Progress progress = new Progress(progressSeconds);
    for (int from = 0; from < ids.size(); from += readBatch) {
      if (progress.due()) {
        LOG.info(
            "Read {} {} {}% ({}/{}) ETA {}",
            label, progress.elapsed(), 100L * from / ids.size(), from, ids.size(),
            progress.eta(from, ids.size()));
      }
      List<ReadValueId> chunk = ids.subList(from, Math.min(ids.size(), from + readBatch));
      ReadResponse response = client.read(0.0, TimestampsToReturn.Neither, chunk);
      DataValue[] results = response.getResults();
      if (results == null || results.length != chunk.size()) {
        throw new UaException(
            StatusCodes.Bad_UnexpectedError,
            "Read returned " + (results == null ? 0 : results.length) + " results for "
                + chunk.size() + " requests");
      }
      out.addAll(Arrays.asList(results));
    }
    return out;
  }

  private static ReadValueId readValueId(NodeId nodeId, AttributeId attributeId) {
    return new ReadValueId(nodeId, attributeId.uid(), null, QualifiedName.NULL_VALUE);
  }

  private static Object good(DataValue dv) {
    return dv != null && dv.getStatusCode().isGood() ? dv.getValue().getValue() : null;
  }

  static String name(QualifiedName qn) {
    return qn == null || qn.name() == null ? "?" : qn.name();
  }

  static String text(LocalizedText lt) {
    return lt == null ? null : lt.text();
  }
}
