package opcua.nodeset.exporter;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NodeSetXmlWriter {

  static final String UA_NODESET_NS = "http://opcfoundation.org/UA/2011/03/UANodeSet.xsd";

  private static final Logger LOG = LoggerFactory.getLogger(NodeSetXmlWriter.class);

  private final NamespaceTable serverNamespaces;
  private final Map<Integer, Integer> remap = new HashMap<>();
  private final List<String> uris = new ArrayList<>();

  NodeSetXmlWriter(NamespaceTable serverNamespaces) {
    this.serverNamespaces = serverNamespaces;
  }

  void write(List<ExportedNode> nodes, Path file) throws IOException {
    registerNamespaces(nodes);
    // The JDK XMLStreamWriter hands bytes to the stream one at a time: without a buffer every
    // byte is a separate write on the file.
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
      XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
      try {
        writeDocument(w, nodes);
      } finally {
        w.close();
      }
    } catch (XMLStreamException e) {
      throw new IOException("Cannot write " + file, e);
    }
  }

  List<String> namespaceUris() {
    return List.copyOf(uris);
  }

  // ---------------------------------------------------------------------------------------------

  private void registerNamespaces(List<ExportedNode> nodes) {
    // Exported nodes first, so that their namespaces get the lowest indexes.
    for (ExportedNode n : nodes) {
      map(n.nodeId().getNamespaceIndex().intValue());
    }
    for (ExportedNode n : nodes) {
      map(n.browseName().namespaceIndex().intValue());
      if (n.attribute(AttributeId.DataType) instanceof NodeId dt) {
        map(dt.getNamespaceIndex().intValue());
      }
      for (ExportedNode.Ref r : n.references()) {
        map(r.referenceTypeId().getNamespaceIndex().intValue());
        map(r.targetId().getNamespaceIndex().intValue());
      }
    }
  }

  private int map(int serverIndex) {
    if (serverIndex == 0) {
      return 0;
    }
    return remap.computeIfAbsent(
        serverIndex,
        idx -> {
          String uri = serverNamespaces.get(idx);
          if (uri == null) {
            uri = "urn:unknown-namespace-index:" + idx;
            LOG.warn("Namespace index {} is not in the server NamespaceArray; using {}", idx, uri);
          }
          uris.add(uri);
          return uris.size();
        });
  }

  private String nodeId(NodeId id) {
    return id.withNamespaceIndex(ushort(map(id.getNamespaceIndex().intValue())))
        .toParseableString();
  }

  private String qualifiedName(QualifiedName qn) {
    int idx = map(qn.namespaceIndex().intValue());
    String name = qn.name() == null ? "" : qn.name();
    return idx == 0 ? name : idx + ":" + name;
  }

  // ---------------------------------------------------------------------------------------------

  private void writeDocument(XMLStreamWriter w, List<ExportedNode> nodes)
      throws XMLStreamException {

    w.writeStartDocument("UTF-8", "1.0");
    nl(w, 0);
    w.writeStartElement("UANodeSet");
    w.writeDefaultNamespace(UA_NODESET_NS);
    w.writeAttribute("LastModified", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());

    if (!uris.isEmpty()) {
      nl(w, 1);
      w.writeStartElement("NamespaceUris");
      for (String uri : uris) {
        nl(w, 2);
        w.writeStartElement("Uri");
        w.writeCharacters(xml(uri));
        w.writeEndElement();
      }
      nl(w, 1);
      w.writeEndElement();
    }

    for (ExportedNode n : nodes) {
      writeNode(w, n);
    }

    nl(w, 0);
    w.writeEndElement();
    nl(w, 0);
    w.writeEndDocument();
  }

  private void writeNode(XMLStreamWriter w, ExportedNode n) throws XMLStreamException {
    String element =
        switch (n.nodeClass()) {
          case Object -> "UAObject";
          case Variable -> "UAVariable";
          case Method -> "UAMethod";
          case View -> "UAView";
          case ObjectType -> "UAObjectType";
          case VariableType -> "UAVariableType";
          case DataType -> "UADataType";
          case ReferenceType -> "UAReferenceType";
          default -> null;
        };
    if (element == null) {
      return;
    }

    nl(w, 1);
    w.writeStartElement(element);
    w.writeAttribute("NodeId", xml(nodeId(n.nodeId())));
    w.writeAttribute("BrowseName", xml(qualifiedName(n.browseName())));
    writeNumberIfNot(w, "WriteMask", n.attribute(AttributeId.WriteMask), 0);

    switch (n.nodeClass()) {
      case Object -> writeNumberIfNot(w, "EventNotifier", n.attribute(AttributeId.EventNotifier), 0);
      case Variable -> {
        writeVariableAttributes(w, n);
        writeNumberIfNot(w, "AccessLevel", n.attribute(AttributeId.AccessLevel), 1);
        if (n.attribute(AttributeId.MinimumSamplingInterval) instanceof Double d && d != 0.0) {
          w.writeAttribute("MinimumSamplingInterval", xsdDouble(d));
        }
        writeBooleanIfTrue(w, "Historizing", n.attribute(AttributeId.Historizing));
      }
      case Method -> {
        if (Boolean.FALSE.equals(n.attribute(AttributeId.Executable))) {
          w.writeAttribute("Executable", "false");
        }
      }
      case ObjectType, DataType -> writeBooleanIfTrue(w, "IsAbstract", n.attribute(AttributeId.IsAbstract));
      case VariableType -> {
        writeBooleanIfTrue(w, "IsAbstract", n.attribute(AttributeId.IsAbstract));
        writeVariableAttributes(w, n);
      }
      case ReferenceType -> {
        writeBooleanIfTrue(w, "IsAbstract", n.attribute(AttributeId.IsAbstract));
        writeBooleanIfTrue(w, "Symmetric", n.attribute(AttributeId.Symmetric));
      }
      case View -> {
        writeBooleanIfTrue(w, "ContainsNoLoops", n.attribute(AttributeId.ContainsNoLoops));
        writeNumberIfNot(w, "EventNotifier", n.attribute(AttributeId.EventNotifier), 0);
      }
      default -> {}
    }

    // Element order follows the UANode sequence: DisplayName, Description, References, then the
    // elements of the derived type (InverseName for UAReferenceType).
    writeLocalizedText(w, "DisplayName", n.displayName());
    writeLocalizedText(w, "Description", n.description());

    if (!n.references().isEmpty()) {
      nl(w, 2);
      w.writeStartElement("References");
      for (ExportedNode.Ref r : n.references()) {
        nl(w, 3);
        w.writeStartElement("Reference");
        w.writeAttribute("ReferenceType", xml(nodeId(r.referenceTypeId())));
        if (!r.forward()) {
          w.writeAttribute("IsForward", "false");
        }
        w.writeCharacters(xml(nodeId(r.targetId())));
        w.writeEndElement();
      }
      nl(w, 2);
      w.writeEndElement();
    }

    if (n.attribute(AttributeId.InverseName) instanceof LocalizedText inverse) {
      writeLocalizedText(w, "InverseName", inverse);
    }

    nl(w, 1);
    w.writeEndElement();
  }

  private void writeVariableAttributes(XMLStreamWriter w, ExportedNode n)
      throws XMLStreamException {
    if (n.attribute(AttributeId.DataType) instanceof NodeId dt) {
      w.writeAttribute("DataType", xml(nodeId(dt)));
    }
    writeNumberIfNot(w, "ValueRank", n.attribute(AttributeId.ValueRank), -1);
    String dims = arrayDimensions(n.attribute(AttributeId.ArrayDimensions));
    if (!dims.isEmpty()) {
      w.writeAttribute("ArrayDimensions", dims);
    }
  }

  static String arrayDimensions(Object value) {
    if (!(value instanceof Object[] array) || array.length == 0) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Object o : array) {
      if (!(o instanceof Number)) {
        return "";
      }
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(((Number) o).longValue());
    }
    return sb.toString();
  }

  private static void writeNumberIfNot(
      XMLStreamWriter w, String name, Object value, long defaultValue) throws XMLStreamException {
    if (value instanceof Number num && num.longValue() != defaultValue) {
      w.writeAttribute(name, Long.toString(num.longValue()));
    }
  }

  private static void writeBooleanIfTrue(XMLStreamWriter w, String name, Object value)
      throws XMLStreamException {
    if (Boolean.TRUE.equals(value)) {
      w.writeAttribute(name, "true");
    }
  }

  private static void writeLocalizedText(XMLStreamWriter w, String element, LocalizedText lt)
      throws XMLStreamException {
    if (lt == null || lt.text() == null) {
      return;
    }
    nl(w, 2);
    w.writeStartElement(element);
    if (lt.locale() != null && !lt.locale().isEmpty()) {
      w.writeAttribute("Locale", xml(lt.locale()));
    }
    w.writeCharacters(xml(lt.text()));
    w.writeEndElement();
  }

  private static void nl(XMLStreamWriter w, int depth) throws XMLStreamException {
    w.writeCharacters("\n" + "  ".repeat(depth));
  }

  static String xsdDouble(double d) {
    if (Double.isInfinite(d)) {
      return d > 0 ? "INF" : "-INF";
    }
    return Double.toString(d);
  }

  static String xml(String s) {
    StringBuilder sb = null;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      boolean ok =
          cp == 0x9 || cp == 0xA || cp == 0xD
              || (cp >= 0x20 && cp <= 0xD7FF)
              || (cp >= 0xE000 && cp <= 0xFFFD)
              || (cp >= 0x10000 && cp <= 0x10FFFF);
      if (!ok && sb == null) {
        sb = new StringBuilder(s.substring(0, i));
      }
      if (ok && sb != null) {
        sb.appendCodePoint(cp);
      }
      i += Character.charCount(cp);
    }
    return sb == null ? s : sb.toString();
  }
}
