package opcua.nodeset.exporter;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TagMappingCsvWriterTest {

  static final NamespaceTable SERVER = new NamespaceTable("urn:server", "urn:machine");

  static ExportedNode variable(String id, String description, String eu, String range) {
    Map<AttributeId, Object> a = new EnumMap<>(AttributeId.class);
    a.put(AttributeId.DataType, NodeIds.Float);
    a.put(AttributeId.ValueRank, 1);
    a.put(AttributeId.ArrayDimensions, new UInteger[] {uint(2), uint(3)});
    a.put(AttributeId.AccessLevel, ubyte(3));
    a.put(AttributeId.Historizing, true);
    a.put(AttributeId.MinimumSamplingInterval, 100.0);
    return new ExportedNode(
        new NodeId(2, id),
        NodeClass.Variable,
        new QualifiedName(2, id),
        LocalizedText.english("Display " + id),
        description == null ? LocalizedText.NULL_VALUE : new LocalizedText("it", description),
        "Root/Objects/" + id,
        a,
        List.of(),
        "Float",
        eu,
        range);
  }

  static ExportedNode object(String id) {
    return new ExportedNode(
        new NodeId(2, id),
        NodeClass.Object,
        new QualifiedName(2, id),
        LocalizedText.english(id),
        LocalizedText.NULL_VALUE,
        "Root/Objects/" + id,
        new EnumMap<>(AttributeId.class),
        List.of(),
        null,
        null,
        null);
  }

  @Test
  void quoting() {
    assertEquals("", TagMappingCsvWriter.quote(null));
    assertEquals("\"\"", TagMappingCsvWriter.quote(""));
    assertEquals("\"a;b\"", TagMappingCsvWriter.quote("a;b"));
    assertEquals("\"say \"\"hi\"\"\"", TagMappingCsvWriter.quote("say \"hi\""));
    assertEquals("\"l1\nl2\"", TagMappingCsvWriter.quote("l1\nl2"));
  }

  @Test
  void accessLevelBits() {
    assertNull(TagMappingCsvWriter.accessLevel(null));
    assertNull(TagMappingCsvWriter.accessLevel("x"));
    assertEquals("", TagMappingCsvWriter.accessLevel(ubyte(0)));
    assertEquals("CurrentRead", TagMappingCsvWriter.accessLevel(ubyte(1)));
    assertEquals("CurrentWrite", TagMappingCsvWriter.accessLevel(ubyte(2)));
    assertEquals(
        "CurrentRead|CurrentWrite|HistoryRead|HistoryWrite|SemanticChange|StatusWrite|TimestampWrite",
        TagMappingCsvWriter.accessLevel(ubyte(127)));
    assertEquals("CurrentRead", TagMappingCsvWriter.accessLevel(ubyte(129)), "bit 7 is not defined");
  }

  @Test
  void writesBomHeaderCrlfAndOnlyVariables(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("t.csv");
    int rows =
        new TagMappingCsvWriter(SERVER, ';')
            .write(List.of(object("Folder"), variable("V1", "a;b \"c\"\nd °C", "°C", "0.0..1.0"), variable("V2", null, null, null)), f);
    assertEquals(2, rows);

    byte[] bytes = Files.readAllBytes(f);
    assertEquals((byte) 0xEF, bytes[0]);
    assertEquals((byte) 0xBB, bytes[1]);
    assertEquals((byte) 0xBF, bytes[2]);
    String text = new String(bytes, StandardCharsets.UTF_8);
    assertTrue(text.contains("\r\n"));

    List<List<String>> csv = TestSupport.parseCsv(f, ';');
    assertEquals(3, csv.size());
    assertEquals(TagMappingCsvWriter.HEADER, csv.get(0));
    assertEquals(
        List.of(
            "ns=2;s=V1",
            "urn:machine",
            "Root/Objects/V1",
            "V1",
            "Display V1",
            "a;b \"c\"\nd °C",
            "i=10",
            "Float",
            "1",
            "2,3",
            "CurrentRead|CurrentWrite",
            "true",
            "100.0",
            "°C",
            "0.0..1.0"),
        csv.get(1));
    List<String> v2 = csv.get(2);
    assertEquals("", v2.get(5));
    assertEquals("", v2.get(13));
    assertEquals("", v2.get(14));
  }

  @Test
  void customSeparator(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("t.csv");
    new TagMappingCsvWriter(SERVER, ',').write(List.of(variable("V1", "x,y", null, null)), f);
    List<List<String>> csv = TestSupport.parseCsv(f, ',');
    assertEquals(TagMappingCsvWriter.HEADER.size(), csv.get(1).size());
    assertEquals("x,y", csv.get(1).get(5));
  }

  @Test
  void emptyExportWritesOnlyTheHeader(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("t.csv");
    assertEquals(0, new TagMappingCsvWriter(SERVER, ';').write(List.of(), f));
    assertEquals(List.of(TagMappingCsvWriter.HEADER), TestSupport.parseCsv(f, ';'));
  }
}
