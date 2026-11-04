package opcua.nodeset.exporter;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.StructureType;
import org.eclipse.milo.opcua.stack.core.types.structured.EnumDefinition;
import org.eclipse.milo.opcua.stack.core.types.structured.EnumField;
import org.eclipse.milo.opcua.stack.core.types.structured.StructureDefinition;
import org.eclipse.milo.opcua.stack.core.types.structured.StructureField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeSetXmlWriterTest {

  // Server table: 0 = OPC UA, 1 = unused by the export, 2 = machine, 3 = types.
  static final NamespaceTable SERVER =
      new NamespaceTable("urn:server:internal", "urn:vendor:machine", "urn:vendor:types");

  static final NodeId MACHINE = new NodeId(2, "Machine");
  static final NodeId TEMP = new NodeId(2, "Machine.Temperature");
  static final NodeId EU = new NodeId(2, "Machine.Temperature.EngineeringUnits");
  static final NodeId MACHINE_TYPE = new NodeId(3, 1001);

  static List<ExportedNode> sampleNodes() {
    Map<AttributeId, Object> objAttrs = new EnumMap<>(AttributeId.class);
    objAttrs.put(AttributeId.EventNotifier, ubyte(1));
    ExportedNode machine =
        new ExportedNode(
            MACHINE,
            NodeClass.Object,
            new QualifiedName(2, "Machine"),
            new LocalizedText("en", "Machine & <Extruder>"),
            LocalizedText.NULL_VALUE,
            "Root/Objects/Machine",
            objAttrs,
            List.of(
                new ExportedNode.Ref(NodeIds.Organizes, false, NodeIds.ObjectsFolder, new QualifiedName(0, "Objects")),
                new ExportedNode.Ref(NodeIds.HasTypeDefinition, true, MACHINE_TYPE, new QualifiedName(3, "MachineType")),
                new ExportedNode.Ref(NodeIds.HasComponent, true, TEMP, new QualifiedName(2, "Temperature"))),
            null,
            null,
            null);

    Map<AttributeId, Object> varAttrs = new EnumMap<>(AttributeId.class);
    varAttrs.put(AttributeId.DataType, NodeIds.Double);
    varAttrs.put(AttributeId.ValueRank, 1);
    varAttrs.put(AttributeId.ArrayDimensions, new UInteger[] {uint(8)});
    varAttrs.put(AttributeId.AccessLevel, ubyte(3));
    varAttrs.put(AttributeId.MinimumSamplingInterval, 100.0);
    varAttrs.put(AttributeId.Historizing, false);
    ExportedNode temp =
        new ExportedNode(
            TEMP,
            NodeClass.Variable,
            new QualifiedName(2, "Temperature"),
            new LocalizedText(null, "Temperature \"zone\""),
            new LocalizedText("en-GB", "Temperature zone; control char:\u0001"),
            "Root/Objects/Machine/Temperature",
            varAttrs,
            List.of(
                new ExportedNode.Ref(NodeIds.HasComponent, false, MACHINE, new QualifiedName(2, "Machine")),
                new ExportedNode.Ref(NodeIds.HasTypeDefinition, true, NodeIds.AnalogItemType, new QualifiedName(0, "AnalogItemType")),
                new ExportedNode.Ref(NodeIds.HasProperty, true, EU, new QualifiedName(0, "EngineeringUnits"))),
            "Double",
            "°C",
            "0.0..400.0");

    Map<AttributeId, Object> typeAttrs = new EnumMap<>(AttributeId.class);
    typeAttrs.put(AttributeId.IsAbstract, false);
    ExportedNode type =
        new ExportedNode(
            MACHINE_TYPE,
            NodeClass.ObjectType,
            new QualifiedName(3, "MachineType"),
            LocalizedText.english("MachineType"),
            LocalizedText.NULL_VALUE,
            "Root/Types/ObjectTypes/BaseObjectType/MachineType",
            typeAttrs,
            List.of(new ExportedNode.Ref(NodeIds.HasSubtype, false, NodeIds.BaseObjectType, new QualifiedName(0, "BaseObjectType"))),
            null,
            null,
            null);

    Map<AttributeId, Object> refTypeAttrs = new EnumMap<>(AttributeId.class);
    refTypeAttrs.put(AttributeId.IsAbstract, false);
    refTypeAttrs.put(AttributeId.Symmetric, false);
    refTypeAttrs.put(AttributeId.InverseName, LocalizedText.english("FeedsFrom"));
    ExportedNode refType =
        new ExportedNode(
            new NodeId(3, 2001),
            NodeClass.ReferenceType,
            new QualifiedName(3, "Feeds"),
            LocalizedText.english("Feeds"),
            LocalizedText.NULL_VALUE,
            "Root/Types/ReferenceTypes/References/NonHierarchicalReferences/Feeds",
            refTypeAttrs,
            List.of(new ExportedNode.Ref(NodeIds.HasSubtype, false, NodeIds.NonHierarchicalReferences, new QualifiedName(0, "NonHierarchicalReferences"))),
            null,
            null,
            null);

    return List.of(machine, temp, type, refType);
  }

  @Test
  void writesSchemaValidNodeSetWithRemappedNamespaces(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("test.NodeSet2.xml");
    NodeSetXmlWriter writer = new NodeSetXmlWriter(SERVER);
    writer.write(sampleNodes(), file);

    // 1. Valid against the official UANodeSet.xsd.
    var schema =
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            .newSchema(getClass().getResource("/UANodeSet.xsd"));
    schema.newValidator().validate(new StreamSource(file.toFile()));

    // 2. Server index 2 -> NodeSet 1, server index 3 -> NodeSet 2; index 1 is unused and absent.
    assertEquals(List.of("urn:vendor:machine", "urn:vendor:types"), writer.namespaceUris());

    String xml = Files.readString(file, StandardCharsets.UTF_8);
    assertTrue(xml.contains("<UAObject NodeId=\"ns=1;s=Machine\" BrowseName=\"1:Machine\" EventNotifier=\"1\">"), xml);
    assertTrue(xml.contains("<Reference ReferenceType=\"i=40\">ns=2;i=1001</Reference>"), xml);
    assertTrue(xml.contains("<Reference ReferenceType=\"i=35\" IsForward=\"false\">i=85</Reference>"), xml);
    assertTrue(xml.contains("DataType=\"i=11\" ValueRank=\"1\" ArrayDimensions=\"8\" AccessLevel=\"3\" MinimumSamplingInterval=\"100.0\""), xml);
    assertTrue(xml.contains("<DisplayName Locale=\"en\">Machine &amp; &lt;Extruder&gt;</DisplayName>"), xml);
    assertTrue(xml.contains("<InverseName Locale=\"en\">FeedsFrom</InverseName>"), xml);
    assertTrue(!xml.contains("\u0001"), "invalid XML character must be removed");
  }

  @Test
  void arrayDimensionsAndDoubles() {
    assertEquals("", NodeSetXmlWriter.arrayDimensions(null));
    assertEquals("", NodeSetXmlWriter.arrayDimensions(new UInteger[0]));
    assertEquals("2,3", NodeSetXmlWriter.arrayDimensions(new UInteger[] {uint(2), uint(3)}));
    assertEquals("INF", NodeSetXmlWriter.xsdDouble(Double.POSITIVE_INFINITY));
    assertEquals("-1.0", NodeSetXmlWriter.xsdDouble(-1.0));
  }

  static ExportedNode node(NodeId id, NodeClass nc, Map<AttributeId, Object> attrs, LocalizedText dn) {
    return new ExportedNode(
        id, nc, new QualifiedName(id.getNamespaceIndex(), "N"), dn, LocalizedText.NULL_VALUE, "p", attrs, List.of(), null, null, null);
  }

  @Test
  void emptyExportIsValidAndHasNoNamespaceUris(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("empty.xml");
    NodeSetXmlWriter writer = new NodeSetXmlWriter(SERVER);
    writer.write(List.of(), file);
    TestSupport.validateNodeSet(file);
    assertEquals(List.of(), writer.namespaceUris());
    assertTrue(!Files.readString(file).contains("NamespaceUris"));
  }

  @Test
  void unspecifiedNodeClassIsSkippedAndUnknownNamespaceGetsPlaceholder(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("x.xml");
    NodeSetXmlWriter writer = new NodeSetXmlWriter(SERVER);
    Map<AttributeId, Object> a = new EnumMap<>(AttributeId.class);
    writer.write(
        List.of(
            node(new NodeId(9, "orphan"), NodeClass.Object, a, LocalizedText.english("o")),
            node(new NodeId(2, "skip"), NodeClass.Unspecified, a, LocalizedText.english("s"))),
        file);
    TestSupport.validateNodeSet(file);
    TestSupport.NodeSet ns = TestSupport.parseNodeSet(file);
    assertEquals(List.of("urn:unknown-namespace-index:9", "urn:vendor:machine"), ns.namespaceUris());
    assertEquals(Set.of("ns=1;s=orphan"), ns.nodes().keySet());
  }

  @Test
  void identifierTypesAndDefaults(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ids.xml");
    Map<AttributeId, Object> v = new EnumMap<>(AttributeId.class);
    v.put(AttributeId.WriteMask, uint(5));
    v.put(AttributeId.MinimumSamplingInterval, 0.0);
    v.put(AttributeId.ValueRank, -1);
    v.put(AttributeId.AccessLevel, ubyte(1));
    v.put(AttributeId.Historizing, false);
    Map<AttributeId, Object> inf = new EnumMap<>(AttributeId.class);
    inf.put(AttributeId.MinimumSamplingInterval, Double.POSITIVE_INFINITY);
    UUID guid = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    new NodeSetXmlWriter(SERVER)
        .write(
            List.of(
                node(new NodeId(3, 42), NodeClass.Variable, v, new LocalizedText("", "no locale")),
                node(new NodeId(3, guid), NodeClass.Variable, inf, LocalizedText.NULL_VALUE),
                node(new NodeId(3, ByteString.of(new byte[] {1, 2, 3})), NodeClass.Object, new EnumMap<>(AttributeId.class), LocalizedText.english("b"))),
            file);
    TestSupport.validateNodeSet(file);
    String xml = Files.readString(file);
    assertTrue(xml.contains("<UAVariable NodeId=\"ns=1;i=42\" BrowseName=\"1:N\" WriteMask=\"5\">"), xml);
    assertTrue(xml.contains("<DisplayName>no locale</DisplayName>"), "empty locale not written");
    assertTrue(xml.contains("NodeId=\"ns=1;g=0F8FAD5B-D9CB-469F-A165-70867728950E\""), xml);
    assertTrue(xml.contains("MinimumSamplingInterval=\"INF\""), xml);
    assertTrue(xml.contains("NodeId=\"ns=1;b=AQID\""), xml);
    TestSupport.NodeSet ns = TestSupport.parseNodeSet(file);
    assertEquals(null, ns.childText("ns=1;g=0F8FAD5B-D9CB-469F-A165-70867728950E", "DisplayName"), "null text not written");
  }

  @Test
  void invalidXmlCharactersAreRemoved() {
    assertEquals("ab", NodeSetXmlWriter.xml("a\u0000b"));
    assertEquals("a\tb\nc\rd", NodeSetXmlWriter.xml("a\tb\nc\rd"));
    assertEquals("ab", NodeSetXmlWriter.xml("a\uD800b"), "lone surrogate");
    assertEquals("a\uD83D\uDE00b", NodeSetXmlWriter.xml("a\uD83D\uDE00b"), "valid supplementary character kept");
    assertEquals("ab", NodeSetXmlWriter.xml("a\uFFFEb"));
    String s = "plain";
    assertTrue(s == NodeSetXmlWriter.xml(s), "no copy when nothing to remove");
  }

  @Test
  void arrayDimensionsWithNonNumbersAreIgnored() {
    assertEquals("", NodeSetXmlWriter.arrayDimensions(new Object[] {"x"}));
    assertEquals("", NodeSetXmlWriter.arrayDimensions("not an array"));
    assertEquals("0", NodeSetXmlWriter.arrayDimensions(new UInteger[] {uint(0)}));
  }

  static ExportedNode dataType(NodeId id, String name, NodeId superType, Object definition) {
    Map<AttributeId, Object> a = new EnumMap<>(AttributeId.class);
    a.put(AttributeId.IsAbstract, false);
    if (definition != null) {
      a.put(AttributeId.DataTypeDefinition, definition);
    }
    return new ExportedNode(
        id,
        NodeClass.DataType,
        new QualifiedName(id.getNamespaceIndex(), name),
        LocalizedText.english(name),
        LocalizedText.NULL_VALUE,
        "p",
        a,
        List.of(new ExportedNode.Ref(NodeIds.HasSubtype, false, superType, new QualifiedName(0, "super"))),
        null,
        null,
        null);
  }

  static final NodeId RECIPE = new NodeId(3, 3001);
  static final NodeId KIND = new NodeId(3, 3002);
  static final NodeId FLAGS = new NodeId(3, 3003);
  static final NodeId PLAIN = new NodeId(3, 3004);

  static List<ExportedNode> nodesWithDataTypes() {
    List<ExportedNode> nodes = new ArrayList<>(sampleNodes());
    nodes.add(
        dataType(
            RECIPE,
            "Recipe",
            NodeIds.Structure,
            new StructureDefinition(
                NodeId.NULL_VALUE,
                NodeIds.Structure,
                StructureType.StructureWithOptionalFields,
                new StructureField[] {
                  new StructureField("Name", LocalizedText.english("the name"), NodeIds.String, -1, null, uint(32), false),
                  new StructureField("Note", LocalizedText.NULL_VALUE, NodeIds.String, -1, null, uint(0), true),
                  new StructureField("Steps", LocalizedText.NULL_VALUE, NodeIds.Int32, 1, new UInteger[] {uint(0)}, uint(0), false),
                  new StructureField("Any", LocalizedText.NULL_VALUE, NodeIds.BaseDataType, -1, null, uint(0), false),
                  new StructureField("Kind", LocalizedText.NULL_VALUE, KIND, -1, null, uint(0), false)
                })));
    nodes.add(
        dataType(
            KIND,
            "Kind",
            NodeIds.Enumeration,
            new EnumDefinition(
                new EnumField[] {
                  new EnumField(0L, LocalizedText.english("A"), LocalizedText.NULL_VALUE, "A"),
                  new EnumField(7L, LocalizedText.english("Bee"), LocalizedText.english("the b"), null)
                })));
    nodes.add(
        dataType(
            FLAGS,
            "Flags",
            NodeIds.UInt32,
            new EnumDefinition(new EnumField[] {new EnumField(0L, LocalizedText.english("Bit0"), LocalizedText.NULL_VALUE, "Bit0")})));
    nodes.add(dataType(PLAIN, "Plain", NodeIds.Structure, null));
    return nodes;
  }

  @Test
  void modelsAndDataTypeDefinitionsAreWritten(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("models.xml");
    Instant uaDate = Instant.parse("2024-12-01T00:00:00Z");
    Instant typesDate = Instant.parse("2025-06-01T00:00:00Z");
    Instant defaultDate = Instant.parse("2026-02-01T00:00:00Z");
    NodeSetXmlWriter writer =
        new NodeSetXmlWriter(
            SERVER,
            Map.of(
                NodeSetXmlWriter.UA_NAMESPACE_URI,
                new NamespaceMetadataReader.ModelInfo(NodeSetXmlWriter.UA_NAMESPACE_URI, "1.05.04", uaDate),
                "urn:vendor:types",
                new NamespaceMetadataReader.ModelInfo("urn:vendor:types", "2.0", typesDate)),
            new NodeSetXmlWriter.ModelDefaults("9.9", defaultDate));
    writer.write(nodesWithDataTypes(), file);
    TestSupport.validateNodeSet(file);
    TestSupport.NodeSet ns = TestSupport.parseNodeSet(file);

    // Models: machine (ns=1) takes the command line defaults and needs the types namespace;
    // types (ns=2) has server metadata and needs only the OPC UA namespace.
    String ua = NodeSetXmlWriter.UA_NAMESPACE_URI + "|1.05.04|2024-12-01T00:00:00Z";
    String types = "urn:vendor:types|2.0|2025-06-01T00:00:00Z";
    Map<String, List<String>> expected = new java.util.LinkedHashMap<>();
    expected.put("urn:vendor:machine|9.9|2026-02-01T00:00:00Z", List.of(ua, types));
    expected.put(types, List.of(ua));
    assertEquals(expected, ns.models());

    List<NodeSetXmlWriter.Model> models = writer.models();
    assertEquals(NodeSetXmlWriter.SOURCE_COMMAND_LINE, models.get(0).versionSource());
    assertEquals(NodeSetXmlWriter.SOURCE_COMMAND_LINE, models.get(0).dateSource());
    assertEquals(NodeSetXmlWriter.SOURCE_SERVER, models.get(1).versionSource());
    assertEquals(NodeSetXmlWriter.SOURCE_SERVER, models.get(1).dateSource());

    // Structure with optional fields.
    assertEquals("2:Recipe", ns.definition("ns=2;i=3001").getAttribute("Name"));
    assertFalse(ns.definition("ns=2;i=3001").hasAttribute("IsUnion"));
    List<Map<String, String>> recipe = ns.fields("ns=2;i=3001");
    assertEquals(Map.of("Name", "Name", "DataType", "i=12", "MaxStringLength", "32", "Description", "the name"), recipe.get(0));
    assertEquals(Map.of("Name", "Note", "DataType", "i=12", "IsOptional", "true"), recipe.get(1));
    assertEquals(Map.of("Name", "Steps", "DataType", "i=6", "ValueRank", "1", "ArrayDimensions", "0"), recipe.get(2));
    assertEquals(Map.of("Name", "Any"), recipe.get(3), "BaseDataType (i=24) is the schema default");
    assertEquals(Map.of("Name", "Kind", "DataType", "ns=2;i=3002"), recipe.get(4));

    // Enumeration: Name from DisplayName when missing.
    List<Map<String, String>> kind = ns.fields("ns=2;i=3002");
    assertEquals(Map.of("Name", "A", "Value", "0", "DisplayName", "A"), kind.get(0));
    assertEquals(Map.of("Name", "Bee", "Value", "7", "DisplayName", "Bee", "Description", "the b"), kind.get(1));
    assertFalse(ns.definition("ns=2;i=3002").hasAttribute("IsOptionSet"));

    // EnumDefinition on a UInt32 subtype: OptionSet.
    assertEquals("true", ns.definition("ns=2;i=3003").getAttribute("IsOptionSet"));

    // Unknown definition: no Definition element.
    assertEquals(null, ns.definition("ns=2;i=3004"));
  }

  @Test
  void withoutMetadataModelsHaveOnlyTheUri(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("plain.xml");
    NodeSetXmlWriter writer = new NodeSetXmlWriter(SERVER);
    writer.write(sampleNodes(), file);
    TestSupport.validateNodeSet(file);
    TestSupport.NodeSet ns = TestSupport.parseNodeSet(file);
    assertEquals(
        List.of("urn:vendor:machine||", "urn:vendor:types||"), new ArrayList<>(ns.models().keySet()));
    assertEquals(NodeSetXmlWriter.SOURCE_MISSING, writer.models().get(0).versionSource());
    assertEquals(NodeSetXmlWriter.SOURCE_MISSING, writer.models().get(0).dateSource());
  }

  @Test
  void requiredModelsFollowOnlyTheReferencesThatUseTheTarget(@TempDir Path dir) throws Exception {
    // ns=2 (machine): a type with a subtype, an instance and a child in ns=3, plus a supertype in ns=0.
    // ns=3 (types): a subtype of the ns=2 type. Only ns=3 depends on ns=2.
    NodeId base = new NodeId(2, "Base");
    NodeId sub = new NodeId(3, "Sub");
    Map<AttributeId, Object> a = new EnumMap<>(AttributeId.class);
    ExportedNode baseNode =
        new ExportedNode(
            base, NodeClass.ObjectType, new QualifiedName(2, "Base"), LocalizedText.english("Base"), LocalizedText.NULL_VALUE, "p", a,
            List.of(
                new ExportedNode.Ref(NodeIds.HasSubtype, false, NodeIds.BaseObjectType, new QualifiedName(0, "BaseObjectType")),
                new ExportedNode.Ref(NodeIds.HasSubtype, true, sub, new QualifiedName(3, "Sub")),
                new ExportedNode.Ref(NodeIds.HasTypeDefinition, false, new NodeId(3, "Instance"), new QualifiedName(3, "Instance")),
                new ExportedNode.Ref(NodeIds.HasComponent, true, new NodeId(3, "Child"), new QualifiedName(3, "Child"))),
            null, null, null);
    ExportedNode subNode =
        new ExportedNode(
            sub, NodeClass.ObjectType, new QualifiedName(3, "Sub"), LocalizedText.english("Sub"), LocalizedText.NULL_VALUE, "p", a,
            List.of(new ExportedNode.Ref(NodeIds.HasSubtype, false, base, new QualifiedName(2, "Base"))),
            null, null, null);
    Path file = dir.resolve("deps.xml");
    NodeSetXmlWriter writer = new NodeSetXmlWriter(SERVER);
    writer.write(List.of(baseNode, subNode), file);
    TestSupport.validateNodeSet(file);
    TestSupport.NodeSet ns = TestSupport.parseNodeSet(file);
    String ua = NodeSetXmlWriter.UA_NAMESPACE_URI + "||";
    Map<String, List<String>> expected = new java.util.LinkedHashMap<>();
    expected.put("urn:vendor:machine||", List.of(ua));
    expected.put("urn:vendor:types||", List.of(ua, "urn:vendor:machine||"));
    assertEquals(expected, ns.models());
  }

  @Test
  void customHierarchicalReferenceTypesAreChildrenToo() {
    NodeId custom = new NodeId(3, "HasStation");
    NodeSetXmlWriter writer = new NodeSetXmlWriter(SERVER);
    ExportedNode.Ref ref = new ExportedNode.Ref(custom, true, new NodeId(3, "x"), new QualifiedName(3, "x"));
    assertTrue(writer.requiresTarget(ref), "unknown reference type: the target is used");
    writer.addHierarchicalReferenceTypes(Set.of(custom));
    assertFalse(writer.requiresTarget(ref), "hierarchical: the target is a child");
    assertTrue(writer.requiresTarget(new ExportedNode.Ref(NodeIds.HasTypeDefinition, true, new NodeId(3, "t"), null)));
    assertFalse(writer.requiresTarget(new ExportedNode.Ref(NodeIds.HasTypeDefinition, false, new NodeId(3, "t"), null)));
    assertTrue(writer.requiresTarget(new ExportedNode.Ref(NodeIds.HasSubtype, false, new NodeId(3, "t"), null)));
    assertFalse(writer.requiresTarget(new ExportedNode.Ref(NodeIds.HasSubtype, true, new NodeId(3, "t"), null)));
  }
}
