package opcua.nodeset.exporter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;

final class TagMappingCsvWriter {

  static final List<String> HEADER =
      List.of(
          "NodeId",
          "NamespaceUri",
          "BrowsePath",
          "BrowseName",
          "DisplayName",
          "Description",
          "DataType",
          "DataTypeName",
          "ValueRank",
          "ArrayDimensions",
          "AccessLevel",
          "Historizing",
          "MinimumSamplingInterval",
          "EngineeringUnits",
          "EURange");

  private static final String[] ACCESS_LEVEL_BITS = {
    "CurrentRead", "CurrentWrite", "HistoryRead", "HistoryWrite",
    "SemanticChange", "StatusWrite", "TimestampWrite"
  };

  private final NamespaceTable serverNamespaces;
  private final char separator;

  TagMappingCsvWriter(NamespaceTable serverNamespaces, char separator) {
    this.serverNamespaces = serverNamespaces;
    this.separator = separator;
  }

  int write(List<ExportedNode> nodes, Path file) throws IOException {
    int rows = 0;
    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      out.write('﻿');
      writeRow(out, HEADER);
      for (ExportedNode n : nodes) {
        if (n.nodeClass() == NodeClass.Variable) {
          writeRow(out, row(n));
          rows++;
        }
      }
    }
    return rows;
  }

  List<String> row(ExportedNode n) {
    NodeId dataType = n.attribute(AttributeId.DataType) instanceof NodeId dt ? dt : null;
    List<String> r = new ArrayList<>(HEADER.size());
    r.add(n.nodeId().toParseableString());
    r.add(serverNamespaces.get(n.nodeId().getNamespaceIndex()));
    r.add(n.browsePath());
    r.add(AddressSpaceCrawler.name(n.browseName()));
    r.add(AddressSpaceCrawler.text(n.displayName()));
    r.add(AddressSpaceCrawler.text(n.description()));
    r.add(dataType == null ? null : dataType.toParseableString());
    r.add(n.dataTypeName());
    r.add(str(n.attribute(AttributeId.ValueRank)));
    r.add(NodeSetXmlWriter.arrayDimensions(n.attribute(AttributeId.ArrayDimensions)));
    r.add(accessLevel(n.attribute(AttributeId.AccessLevel)));
    r.add(str(n.attribute(AttributeId.Historizing)));
    r.add(str(n.attribute(AttributeId.MinimumSamplingInterval)));
    r.add(n.engineeringUnits());
    r.add(n.euRange());
    return r;
  }

  static String accessLevel(Object value) {
    if (!(value instanceof Number num)) {
      return null;
    }
    int bits = num.intValue();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < ACCESS_LEVEL_BITS.length; i++) {
      if ((bits & (1 << i)) != 0) {
        if (sb.length() > 0) {
          sb.append('|');
        }
        sb.append(ACCESS_LEVEL_BITS[i]);
      }
    }
    return sb.toString();
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }

  private void writeRow(BufferedWriter out, List<String> fields) throws IOException {
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) {
        out.write(separator);
      }
      out.write(quote(fields.get(i)));
    }
    out.write("\r\n");
  }

  static String quote(String s) {
    return s == null ? "" : '"' + s.replace("\"", "\"\"") + '"';
  }
}
