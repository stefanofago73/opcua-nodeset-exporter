package opcua.nodeset.exporter;

import java.util.List;
import java.util.Map;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;

public record ExportedNode(
    NodeId nodeId,
    NodeClass nodeClass,
    QualifiedName browseName,
    LocalizedText displayName,
    LocalizedText description,
    String browsePath,
    Map<AttributeId, Object> attributes,
    List<Ref> references,
    String dataTypeName,
    String engineeringUnits,
    String euRange) {

  public record Ref(
      NodeId referenceTypeId, boolean forward, NodeId targetId, QualifiedName targetBrowseName) {}

  public Object attribute(AttributeId id) {
    return attributes.get(id);
  }
}
