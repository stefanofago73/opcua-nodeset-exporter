package opcua.nodeset.exporter;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExpandedNodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.junit.jupiter.api.Test;

class AddressSpaceCrawlerTest {

  static final NamespaceTable SERVER = new NamespaceTable("urn:server", "urn:machine");

  @Test
  void everyNodeClassReadsAtLeastOneMandatoryAttribute() {
    // The "no Good read means not readable" rule relies on this.
    assertEquals(List.of(AttributeId.EventNotifier), AddressSpaceCrawler.specificAttributes(NodeClass.Object));
    assertEquals(
        List.of(
            AttributeId.DataType,
            AttributeId.ValueRank,
            AttributeId.ArrayDimensions,
            AttributeId.AccessLevel,
            AttributeId.MinimumSamplingInterval,
            AttributeId.Historizing),
        AddressSpaceCrawler.specificAttributes(NodeClass.Variable));
    assertEquals(List.of(AttributeId.Executable), AddressSpaceCrawler.specificAttributes(NodeClass.Method));
    assertEquals(List.of(AttributeId.IsAbstract), AddressSpaceCrawler.specificAttributes(NodeClass.ObjectType));
    assertEquals(List.of(AttributeId.IsAbstract), AddressSpaceCrawler.specificAttributes(NodeClass.DataType));
    assertEquals(
        List.of(AttributeId.IsAbstract, AttributeId.DataType, AttributeId.ValueRank, AttributeId.ArrayDimensions),
        AddressSpaceCrawler.specificAttributes(NodeClass.VariableType));
    assertEquals(
        List.of(AttributeId.IsAbstract, AttributeId.Symmetric, AttributeId.InverseName),
        AddressSpaceCrawler.specificAttributes(NodeClass.ReferenceType));
    assertEquals(
        List.of(AttributeId.ContainsNoLoops, AttributeId.EventNotifier),
        AddressSpaceCrawler.specificAttributes(NodeClass.View));
    assertEquals(List.of(), AddressSpaceCrawler.specificAttributes(NodeClass.Unspecified));
    assertEquals(List.of(), AddressSpaceCrawler.specificAttributes(null));
  }

  @Test
  void attributesToReadAddDescriptionAndWriteMask() {
    assertEquals(
        List.of(AttributeId.Description, AttributeId.WriteMask, AttributeId.Executable),
        AddressSpaceCrawler.attributesToRead(NodeClass.Method));
    assertEquals(List.of(), AddressSpaceCrawler.attributesToRead(null));
    for (AttributeId notRead : List.of(AttributeId.NodeClass, AttributeId.BrowseName, AttributeId.DisplayName)) {
      for (NodeClass nc : NodeClass.values()) {
        assertFalse(AddressSpaceCrawler.attributesToRead(nc).contains(notRead), nc + " " + notRead);
      }
    }
  }

  @Test
  void onlyNonZeroNamespacesAreExported() {
    assertFalse(AddressSpaceCrawler.isExported(NodeIds.ObjectsFolder));
    assertTrue(AddressSpaceCrawler.isExported(new NodeId(1, 1)));
    assertTrue(AddressSpaceCrawler.isExported(new NodeId(2, "x")));
  }

  @Test
  void referencesToOtherServersOrUnknownNamespacesAreDropped() {
    var crawler = new AddressSpaceCrawler(null, SERVER, 2, 1, 0, 3);
    List<ReferenceDescription> rds =
        List.of(
            rd(new NodeId(2, "local").expanded()),
            rd(ExpandedNodeId.of("urn:machine", "byUri")),
            rd(ExpandedNodeId.of("urn:not-on-this-server", "x")),
            rd(
                new ExpandedNodeId(
                    new ExpandedNodeId.ServerReference.ServerIndex(uint(1)),
                    new ExpandedNodeId.NamespaceReference.NamespaceIndex(ushort(0)),
                    uint(85))));
    List<ExportedNode.Ref> refs = crawler.toRefs(rds);
    assertEquals(2, refs.size());
    assertEquals(new NodeId(2, "local"), refs.get(0).targetId());
    assertEquals(new NodeId(2, "byUri"), refs.get(1).targetId(), "namespace URI resolved to the index");
    assertTrue(refs.get(0).forward());
    assertEquals(2, crawler.skippedReferences());
  }

  @Test
  void constructorRejectsInvalidBatchSizes() {
    assertThrows(IllegalArgumentException.class, () -> new AddressSpaceCrawler(null, SERVER, 1, 1, 0, 3));
    assertThrows(IllegalArgumentException.class, () -> new AddressSpaceCrawler(null, SERVER, 2, 0, 0, 3));
  }

  @Test
  void nameAndTextHandleNulls() {
    assertEquals("?", AddressSpaceCrawler.name(null));
    assertEquals("?", AddressSpaceCrawler.name(new QualifiedName(0, null)));
    assertEquals("A", AddressSpaceCrawler.name(new QualifiedName(3, "A")));
    assertEquals(null, AddressSpaceCrawler.text(null));
    assertEquals("t", AddressSpaceCrawler.text(LocalizedText.english("t")));
  }

  private static ReferenceDescription rd(ExpandedNodeId target) {
    return new ReferenceDescription(
        NodeIds.Organizes,
        true,
        target,
        new QualifiedName(2, "n"),
        LocalizedText.english("n"),
        NodeClass.Object,
        NodeIds.FolderType.expanded());
  }

  @Test
  void nodeClassFromBrowseIsUsedWithoutReading() throws Exception {
    List<NodeClass> r =
        AddressSpaceCrawler.resolveNodeClasses(
            List.of(NodeClass.Object, NodeClass.Variable),
            List.of(new NodeId(2, "a"), new NodeId(2, "b")),
            (label, ids) -> {
              throw new AssertionError("no read expected");
            });
    assertEquals(List.of(NodeClass.Object, NodeClass.Variable), r);
  }

  @Test
  void missingNodeClassIsReadOnlyForThoseNodes() throws Exception {
    List<List<ReadValueId>> calls = new ArrayList<>();
    List<NodeClass> fromBrowse = new ArrayList<>();
    fromBrowse.add(NodeClass.Object);
    fromBrowse.add(null);
    fromBrowse.add(NodeClass.Unspecified);
    fromBrowse.add(NodeClass.Unspecified);
    fromBrowse.add(NodeClass.Unspecified);
    List<NodeId> ids = List.of(new NodeId(2, "a"), new NodeId(2, "b"), new NodeId(2, "c"), new NodeId(2, "d"), new NodeId(2, "e"));
    List<NodeClass> r =
        AddressSpaceCrawler.resolveNodeClasses(
            fromBrowse,
            ids,
            (label, read) -> {
              calls.add(read);
              return List.of(
                  new DataValue(new Variant(NodeClass.Variable.getValue())),
                  new DataValue(new StatusCode(StatusCodes.Bad_UserAccessDenied)),
                  new DataValue(new Variant(NodeClass.Unspecified.getValue())),
                  new DataValue(new Variant("not a number")));
            });
    assertEquals(1, calls.size());
    assertEquals(4, calls.get(0).size());
    assertEquals(new NodeId(2, "b"), calls.get(0).get(0).getNodeId());
    assertEquals(AttributeId.NodeClass.uid(), calls.get(0).get(0).getAttributeId());
    assertEquals(Arrays.asList(NodeClass.Object, NodeClass.Variable, null, null, null), r);
  }
}
