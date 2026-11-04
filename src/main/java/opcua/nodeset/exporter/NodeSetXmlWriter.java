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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.StructureType;
import org.eclipse.milo.opcua.stack.core.types.structured.EnumDefinition;
import org.eclipse.milo.opcua.stack.core.types.structured.EnumField;
import org.eclipse.milo.opcua.stack.core.types.structured.StructureDefinition;
import org.eclipse.milo.opcua.stack.core.types.structured.StructureField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class NodeSetXmlWriter {

  static final String UA_NODESET_NS = "http://opcfoundation.org/UA/2011/03/UANodeSet.xsd";
  static final String UA_NAMESPACE_URI = "http://opcfoundation.org/UA/";

  record ModelDefaults(String version, Instant publicationDate) {
    static final ModelDefaults NONE = new ModelDefaults(null, null);
  }

  record Model(
      String uri,
      String version,
      Instant publicationDate,
      String versionSource,
      String dateSource,
      List<Model> required) {}

  static final String SOURCE_SERVER = "server";
  static final String SOURCE_COMMAND_LINE = "command line";
  static final String SOURCE_MISSING = "missing";

  private static final Logger LOG = LoggerFactory.getLogger(NodeSetXmlWriter.class);

  private final NamespaceTable serverNamespaces;
  private final Map<String, NamespaceMetadataReader.ModelInfo> metadata;
  private final ModelDefaults defaults;
  private final Map<Integer, Integer> remap = new HashMap<>();
  private final List<String> uris = new ArrayList<>();
  private final Map<Integer, Set<Integer>> dependencies = new LinkedHashMap<>();
  private final List<Model> models = new ArrayList<>();
  private final Set<NodeId> hierarchicalTypes =
      new java.util.HashSet<>(
          List.of(
              NodeIds.HierarchicalReferences,
              NodeIds.HasChild,
              NodeIds.Organizes,
              NodeIds.HasEventSource,
              NodeIds.Aggregates,
              NodeIds.HasSubtype,
              NodeIds.HasProperty,
              NodeIds.HasComponent,
              NodeIds.HasNotifier,
              NodeIds.HasOrderedComponent));

  NodeSetXmlWriter(NamespaceTable serverNamespaces) {
    this(serverNamespaces, Map.of(), ModelDefaults.NONE);
  }

  NodeSetXmlWriter(
      NamespaceTable serverNamespaces,
      Map<String, NamespaceMetadataReader.ModelInfo> metadata,
      ModelDefaults defaults) {
    this.serverNamespaces = serverNamespaces;
    this.metadata = metadata;
    this.defaults = defaults;
  }

  void write(List<ExportedNode> nodes, Path file) throws IOException {
    registerNamespaces(nodes);
    buildModels();
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

  void addHierarchicalReferenceTypes(Set<NodeId> types) {
    hierarchicalTypes.addAll(types);
  }

  List<Model> models() {
    return List.copyOf(models);
  }

  // ---------------------------------------------------------------------------------------------

  private void registerNamespaces(List<ExportedNode> nodes) {
    // Exported nodes first, so that their namespaces get the lowest indexes.
    for (ExportedNode n : nodes) {
      int own = map(n.nodeId().getNamespaceIndex().intValue());
      if (own != 0 && elementName(n.nodeClass()) != null) {
        dependencies.computeIfAbsent(own, k -> new TreeSet<>());
      }
    }
    for (ExportedNode n : nodes) {
      Set<Integer> deps =
          !dependencies.containsKey(map(n.nodeId().getNamespaceIndex().intValue()))
                  || elementName(n.nodeClass()) == null
              ? new TreeSet<Integer>()
              : dependencies.get(map(n.nodeId().getNamespaceIndex().intValue()));
      deps.add(map(n.browseName().namespaceIndex().intValue()));
      if (n.attribute(AttributeId.DataType) instanceof NodeId dt) {
        deps.add(map(dt.getNamespaceIndex().intValue()));
      }
      if (n.attribute(AttributeId.DataTypeDefinition) instanceof StructureDefinition sd
          && sd.getFields() != null) {
        for (StructureField f : sd.getFields()) {
          if (f.getDataType() != null) {
            deps.add(map(f.getDataType().getNamespaceIndex().intValue()));
          }
        }
      }
      for (ExportedNode.Ref r : n.references()) {
        deps.add(map(r.referenceTypeId().getNamespaceIndex().intValue()));
        int target = map(r.targetId().getNamespaceIndex().intValue());
        if (requiresTarget(r)) {
          deps.add(target);
        }
      }
    }
  }

  boolean requiresTarget(ExportedNode.Ref r) {
    if (NodeIds.HasSubtype.equals(r.referenceTypeId())) {
      return !r.forward();
    }
    return r.forward() && !hierarchicalTypes.contains(r.referenceTypeId());
  }

  private void buildModels() {
    for (Map.Entry<Integer, Set<Integer>> e : dependencies.entrySet()) {
      String uri = uris.get(e.getKey() - 1);
      List<Model> required = new ArrayList<>();
      required.add(requiredModel(UA_NAMESPACE_URI));
      for (int idx : e.getValue()) {
        if (idx != 0 && idx != e.getKey()) {
          required.add(requiredModel(uris.get(idx - 1)));
        }
      }
      NamespaceMetadataReader.ModelInfo info = metadata.get(uri);
      String version = info == null ? null : info.version();
      Instant date = info == null ? null : info.publicationDate();
      String versionSource = version != null ? SOURCE_SERVER : SOURCE_MISSING;
      String dateSource = date != null ? SOURCE_SERVER : SOURCE_MISSING;
      if (version == null && defaults.version() != null) {
        version = defaults.version();
        versionSource = SOURCE_COMMAND_LINE;
      }
      if (date == null && defaults.publicationDate() != null) {
        date = defaults.publicationDate();
        dateSource = SOURCE_COMMAND_LINE;
      }
      models.add(new Model(uri, version, date, versionSource, dateSource, List.copyOf(required)));
    }
  }

  private Model requiredModel(String uri) {
    NamespaceMetadataReader.ModelInfo info = metadata.get(uri);
    String version = info == null ? null : info.version();
    Instant date = info == null ? null : info.publicationDate();
    return new Model(
        uri,
        version,
        date,
        version != null ? SOURCE_SERVER : SOURCE_MISSING,
        date != null ? SOURCE_SERVER : SOURCE_MISSING,
        List.of());
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

    if (!models.isEmpty()) {
      nl(w, 1);
      w.writeStartElement("Models");
      for (Model m : models) {
        nl(w, 2);
        w.writeStartElement("Model");
        writeModelAttributes(w, m);
        for (Model r : m.required()) {
          nl(w, 3);
          w.writeEmptyElement("RequiredModel");
          writeModelAttributes(w, r);
        }
        if (!m.required().isEmpty()) {
          nl(w, 2);
        }
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

  private static void writeModelAttributes(XMLStreamWriter w, Model m) throws XMLStreamException {
    w.writeAttribute("ModelUri", xml(m.uri()));
    if (m.version() != null) {
      w.writeAttribute("Version", xml(m.version()));
    }
    if (m.publicationDate() != null) {
      w.writeAttribute("PublicationDate", m.publicationDate().toString());
    }
  }

  static String elementName(NodeClass nodeClass) {
    if (nodeClass == null) {
      return null;
    }
    return switch (nodeClass) {
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
  }

  private void writeNode(XMLStreamWriter w, ExportedNode n) throws XMLStreamException {
    String element = elementName(n.nodeClass());
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

    if (n.nodeClass() == NodeClass.DataType) {
      writeDefinition(w, n);
    }

    nl(w, 1);
    w.writeEndElement();
  }

  private void writeDefinition(XMLStreamWriter w, ExportedNode n) throws XMLStreamException {
    Object definition = n.attribute(AttributeId.DataTypeDefinition);
    if (definition instanceof StructureDefinition sd) {
      StructureType st = sd.getStructureType();
      boolean union = st == StructureType.Union || st == StructureType.UnionWithSubtypedValues;
      boolean optionalFields = st == StructureType.StructureWithOptionalFields;
      boolean subtypedValues =
          st == StructureType.StructureWithSubtypedValues || st == StructureType.UnionWithSubtypedValues;
      nl(w, 2);
      w.writeStartElement("Definition");
      w.writeAttribute("Name", xml(qualifiedName(n.browseName())));
      if (union) {
        w.writeAttribute("IsUnion", "true");
      }
      StructureField[] fields = sd.getFields() == null ? new StructureField[0] : sd.getFields();
      for (StructureField f : fields) {
        nl(w, 3);
        w.writeStartElement("Field");
        w.writeAttribute("Name", xml(f.getName() == null ? "" : f.getName()));
        if (f.getDataType() != null && !NodeIds.BaseDataType.equals(f.getDataType())) {
          w.writeAttribute("DataType", xml(nodeId(f.getDataType())));
        }
        writeNumberIfNot(w, "ValueRank", f.getValueRank(), -1);
        String dims = arrayDimensions(f.getArrayDimensions());
        if (!dims.isEmpty()) {
          w.writeAttribute("ArrayDimensions", dims);
        }
        writeNumberIfNot(w, "MaxStringLength", f.getMaxStringLength(), 0);
        if (Boolean.TRUE.equals(f.getIsOptional())) {
          if (optionalFields) {
            w.writeAttribute("IsOptional", "true");
          } else if (subtypedValues) {
            w.writeAttribute("AllowSubTypes", "true");
          }
        }
        writeLocalizedText(w, "Description", f.getDescription(), 4);
        endField(w, f.getDescription());
      }
      nl(w, 2);
      w.writeEndElement();
    } else if (definition instanceof EnumDefinition ed) {
      nl(w, 2);
      w.writeStartElement("Definition");
      w.writeAttribute("Name", xml(qualifiedName(n.browseName())));
      if (isOptionSet(n)) {
        w.writeAttribute("IsOptionSet", "true");
      }
      EnumField[] fields = ed.getFields() == null ? new EnumField[0] : ed.getFields();
      for (EnumField f : fields) {
        nl(w, 3);
        w.writeStartElement("Field");
        w.writeAttribute("Name", xml(enumFieldName(f)));
        Long value = f.getValue();
        if (value != null && value != -1 && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
          w.writeAttribute("Value", Long.toString(value));
        } else if (value != null && value != -1) {
          LOG.warn("Enum value {} of {} does not fit xs:int; not written", value, n.nodeId().toParseableString());
        }
        boolean children = hasText(f.getDisplayName()) || hasText(f.getDescription());
        writeLocalizedText(w, "DisplayName", f.getDisplayName(), 4);
        writeLocalizedText(w, "Description", f.getDescription(), 4);
        if (children) {
          nl(w, 3);
        }
        w.writeEndElement();
      }
      nl(w, 2);
      w.writeEndElement();
    }
  }

  private static void endField(XMLStreamWriter w, LocalizedText description)
      throws XMLStreamException {
    if (hasText(description)) {
      nl(w, 3);
    }
    w.writeEndElement();
  }

  private static boolean hasText(LocalizedText lt) {
    return lt != null && lt.text() != null;
  }

  static String enumFieldName(EnumField f) {
    if (f.getName() != null && !f.getName().isEmpty()) {
      return f.getName();
    }
    if (hasText(f.getDisplayName()) && !f.getDisplayName().text().isEmpty()) {
      return f.getDisplayName().text();
    }
    return "Value" + f.getValue();
  }

  static boolean isOptionSet(ExportedNode n) {
    for (ExportedNode.Ref r : n.references()) {
      if (!r.forward()
          && NodeIds.HasSubtype.equals(r.referenceTypeId())
          && (NodeIds.Byte.equals(r.targetId())
              || NodeIds.UInt16.equals(r.targetId())
              || NodeIds.UInt32.equals(r.targetId())
              || NodeIds.UInt64.equals(r.targetId()))) {
        return true;
      }
    }
    return false;
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
    writeLocalizedText(w, element, lt, 2);
  }

  private static void writeLocalizedText(
      XMLStreamWriter w, String element, LocalizedText lt, int depth) throws XMLStreamException {
    if (lt == null || lt.text() == null) {
      return;
    }
    nl(w, depth);
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
