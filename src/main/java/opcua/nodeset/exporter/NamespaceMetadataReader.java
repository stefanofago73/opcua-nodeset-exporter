package opcua.nodeset.exporter;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.ViewDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NamespaceMetadataReader {

  private static final Logger LOG = LoggerFactory.getLogger(NamespaceMetadataReader.class);

  private static final ViewDescription NO_VIEW =
      new ViewDescription(NodeId.NULL_VALUE, DateTime.MIN_VALUE, uint(0));

  record ModelInfo(String uri, String version, Instant publicationDate) {}

  private NamespaceMetadataReader() {}

  static Map<String, ModelInfo> read(OpcUaClient client, NamespaceTable namespaces) {
    try {
      Map<String, ModelInfo> out = readOrThrow(client, namespaces);
      LOG.info("NamespaceMetadata found for {} namespaces", out.size());
      return out;
    } catch (UaException | RuntimeException e) {
      LOG.warn("Server/Namespaces not readable ({}): Model Version and PublicationDate come only from the command line", e.getMessage());
      return Map.of();
    }
  }

  private static Map<String, ModelInfo> readOrThrow(OpcUaClient client, NamespaceTable nt)
      throws UaException {
    Map<String, ModelInfo> out = new LinkedHashMap<>();
    for (ReferenceDescription child : browse(client, NodeIds.Server_Namespaces, NodeIds.HasComponent)) {
      if (child.getNodeClass() != NodeClass.Object) {
        continue;
      }
      NodeId objectId = child.getNodeId().toNodeId(nt).orElse(null);
      if (objectId == null) {
        continue;
      }
      Map<String, NodeId> properties = new HashMap<>();
      for (ReferenceDescription p : browse(client, objectId, NodeIds.HasProperty)) {
        QualifiedName qn = p.getBrowseName();
        if (qn != null && qn.namespaceIndex().intValue() == 0 && qn.name() != null) {
          p.getNodeId().toNodeId(nt).ifPresent(id -> properties.put(qn.name(), id));
        }
      }
      List<String> names = List.of("NamespaceUri", "NamespaceVersion", "NamespacePublicationDate");
      List<Object> values = readValues(client, names.stream().map(properties::get).toList());

      // The BrowseName of a NamespaceMetadata object is the namespace URI (Part 5); the
      // NamespaceUri property, when readable, wins.
      String uri = values.get(0) instanceof String s && !s.isBlank() ? s : AddressSpaceCrawler.name(child.getBrowseName());
      String version = values.get(1) instanceof String s && !s.isBlank() ? s : null;
      Instant date =
          values.get(2) instanceof DateTime dt && dt.isValid() && dt.getUtcTime() > 0 ? dt.getJavaInstant() : null;
      out.put(uri, new ModelInfo(uri, version, date));
    }
    return out;
  }

  private static List<ReferenceDescription> browse(OpcUaClient client, NodeId nodeId, NodeId referenceType)
      throws UaException {
    BrowseDescription bd =
        new BrowseDescription(
            nodeId, BrowseDirection.Forward, referenceType, true, uint(0), uint(BrowseResultMask.All.getValue()));
    BrowseResult[] results = client.browse(NO_VIEW, uint(0), List.of(bd)).getResults();
    if (results == null || results.length == 0 || results[0].getStatusCode().isBad()) {
      return List.of();
    }
    ByteString cp = results[0].getContinuationPoint();
    if (cp != null && !cp.isNullOrEmpty()) {
      client.browseNext(true, List.of(cp));
      LOG.warn("Browse of {} truncated by the server; some NamespaceMetadata may be missing", nodeId.toParseableString());
    }
    ReferenceDescription[] refs = results[0].getReferences();
    return refs == null ? List.of() : Arrays.asList(refs);
  }

  private static List<Object> readValues(OpcUaClient client, List<NodeId> nodeIds) throws UaException {
    List<ReadValueId> ids = new ArrayList<>();
    for (NodeId id : nodeIds) {
      if (id != null) {
        ids.add(new ReadValueId(id, AttributeId.Value.uid(), null, QualifiedName.NULL_VALUE));
      }
    }
    DataValue[] results =
        ids.isEmpty() ? new DataValue[0] : client.read(0.0, TimestampsToReturn.Neither, ids).getResults();
    List<Object> out = new ArrayList<>();
    int r = 0;
    for (NodeId id : nodeIds) {
      Object v = null;
      if (id != null && results != null && r < results.length) {
        DataValue dv = results[r++];
        v = dv.getStatusCode().isGood() ? dv.getValue().getValue() : null;
      }
      out.add(v);
    }
    return out;
  }
}
