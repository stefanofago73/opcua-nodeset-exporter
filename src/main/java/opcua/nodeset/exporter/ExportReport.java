package opcua.nodeset.exporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.StructureType;
import org.eclipse.milo.opcua.stack.core.types.structured.EnumDefinition;
import org.eclipse.milo.opcua.stack.core.types.structured.StructureDefinition;
import org.xml.sax.SAXParseException;

final class ExportReport {

  record Input(
      String endpoint,
      String namespaceFilter,
      Path nodeSetFile,
      Path csvFile,
      NamespaceTable serverNamespaces,
      List<ExportedNode> nodes,
      List<String> nodeSetUris,
      List<NodeSetXmlWriter.Model> models,
      Map<NodeId, String> definitionsFromProperties,
      int skippedNodes,
      int skippedReferences,
      boolean truncated) {}

  private static final List<NodeClass> CLASSES =
      List.of(
          NodeClass.Object,
          NodeClass.Variable,
          NodeClass.Method,
          NodeClass.ObjectType,
          NodeClass.VariableType,
          NodeClass.DataType,
          NodeClass.ReferenceType,
          NodeClass.View);

  private ExportReport() {}

  static String validate(Path nodeSet) {
    try {
      var schema =
          SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
              .newSchema(ExportReport.class.getResource("/UANodeSet.xsd"));
      schema.newValidator().validate(new StreamSource(nodeSet.toFile()));
      return null;
    } catch (SAXParseException e) {
      return "line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ": " + e.getMessage();
    } catch (Exception e) {
      return e.toString();
    }
  }

  static void write(Input in, String validationError, Path file) throws IOException {
    Files.writeString(file, render(in, validationError, Instant.now()), StandardCharsets.UTF_8);
  }

  static String render(Input in, String validationError, Instant generated) {
    List<String> out = new ArrayList<>();
    out.add("# NodeSet export report");
    out.add("");
    out.add("Generated " + generated.truncatedTo(ChronoUnit.SECONDS) + " from `" + in.endpoint() + "`"
        + (in.namespaceFilter() == null ? "" : ", namespace `" + in.namespaceFilter() + "`")
        + ". Files: `" + in.nodeSetFile().getFileName() + "`, `" + in.csvFile().getFileName() + "`.");
    out.add("");

    // ---- checks
    List<NodeSetXmlWriter.Model> models = in.models();
    boolean allDates = !models.isEmpty() && models.stream().allMatch(m -> m.publicationDate() != null);
    int dataTypes = 0;
    int withDefinition = 0;
    int simple = 0;
    for (ExportedNode n : in.nodes()) {
      if (n.nodeClass() == NodeClass.DataType) {
        dataTypes++;
        if (definition(n) != null) {
          withDefinition++;
        } else if (simpleSupertype(n) != null) {
          simple++;
        }
      }
    }
    out.add("## Checks");
    out.add("");
    out.add("| Check | Result | Needed by |");
    out.add("|---|---|---|");
    out.add(row("Schema UANodeSet.xsd", validationError == null ? "OK" : "FAILED", "every tool"));
    out.add(row("Models present", yesNo(!models.isEmpty()), "ModelCompiler, Cloud Library"));
    out.add(row("Exactly one Model", yesNo(models.size() == 1) + " (" + models.size() + ")", "Cloud Library (use --namespace)"));
    out.add(row("PublicationDate on every Model", yesNo(allDates), "Cloud Library"));
    out.add(
        row(
            "DataTypes with Definition",
            withDefinition + " of " + (dataTypes - simple) + (simple > 0 ? " (simple types: " + simple + ")" : ""),
            "code generators"));
    out.add(row("Crawl complete", in.truncated() ? "no (--max-nodes reached)" : "yes", "-"));
    out.add(row("Nodes skipped (not readable)", Integer.toString(in.skippedNodes()), "-"));
    out.add(row("References to other servers dropped", Integer.toString(in.skippedReferences()), "-"));
    out.add("");
    if (validationError != null) {
      out.add("Schema error: " + oneLine(validationError));
      out.add("");
    }

    // ---- models
    out.add("## Models");
    out.add("");
    if (models.isEmpty()) {
      out.add("No Model: the export contains no node.");
      out.add("");
    }
    for (NodeSetXmlWriter.Model m : models) {
      out.add("### " + m.uri());
      out.add("");
      out.add("- Version: " + valueOrDash(m.version()) + " (" + m.versionSource() + ")");
      out.add("- PublicationDate: " + (m.publicationDate() == null ? "-" : m.publicationDate().toString()) + " (" + m.dateSource() + ")");
      for (NodeSetXmlWriter.Model r : m.required()) {
        out.add("- Requires: " + r.uri() + " " + valueOrDash(r.version()));
      }
      out.add("");
    }

    // ---- node counts
    out.add("## Nodes by NodeClass");
    out.add("");
    Map<String, Map<NodeClass, Integer>> counts = new TreeMap<>();
    for (ExportedNode n : in.nodes()) {
      String uri = uri(in.serverNamespaces(), n.nodeId());
      counts.computeIfAbsent(uri, k -> new EnumMap<>(NodeClass.class)).merge(n.nodeClass(), 1, Integer::sum);
    }
    StringBuilder header = new StringBuilder("| NS");
    StringBuilder rule = new StringBuilder("|---");
    for (NodeClass nc : CLASSES) {
      header.append(" | ").append(nc);
      rule.append("|---");
    }
    out.add(header.append(" | Total |").toString());
    out.add(rule.append("|---|").toString());
    for (Map.Entry<String, Map<NodeClass, Integer>> e : counts.entrySet()) {
      StringBuilder sb = new StringBuilder("| ").append(nodeSetIndex(in.nodeSetUris(), e.getKey()));
      int total = 0;
      for (NodeClass nc : CLASSES) {
        int c = e.getValue().getOrDefault(nc, 0);
        total += c;
        sb.append(" | ").append(c);
      }
      out.add(sb.append(" | ").append(total).append(" |").toString());
    }
    out.add("");
    out.add("NodeSet namespace table:");
    out.add("");
    for (int i = 0; i < in.nodeSetUris().size(); i++) {
      out.add("- ns=" + (i + 1) + ": " + in.nodeSetUris().get(i));
    }
    out.add("");

    // ---- data types
    out.add("## DataTypes");
    out.add("");
    if (dataTypes == 0) {
      out.add("No DataType in the export.");
      out.add("");
    } else {
      out.add("| DataType | Kind | Fields | Definition from |");
      out.add("|---|---|---|---|");
      for (ExportedNode n : in.nodes()) {
        if (n.nodeClass() != NodeClass.DataType) {
          continue;
        }
        Object d = definition(n);
        NodeId supertype = d == null ? simpleSupertype(n) : null;
        String source;
        if (d != null) {
          source = in.definitionsFromProperties().getOrDefault(n.nodeId(), "DataTypeDefinition");
        } else if (supertype != null) {
          source = "not needed (subtype of " + supertype.toParseableString() + ")";
        } else {
          source = "missing";
        }
        out.add(row(AddressSpaceCrawler.name(n.browseName()), supertype != null ? "Simple" : kind(n, d), fieldCount(d), source));
      }
      out.add("");
    }

    // ---- references leaving the export
    out.add("## References to nodes outside the export");
    out.add("");
    Set<NodeId> exported = new HashSet<>();
    for (ExportedNode n : in.nodes()) {
      exported.add(n.nodeId());
    }
    Set<String> exportedNamespaces = new HashSet<>();
    for (ExportedNode n : in.nodes()) {
      exportedNamespaces.add(uri(in.serverNamespaces(), n.nodeId()));
    }
    // Namespace -> reference type -> count, for targets in other namespaces and for targets
    // missing from namespaces that are in the export.
    Map<String, Map<String, Integer>> otherNamespaces = new TreeMap<>();
    Map<String, Map<String, Integer>> missingNodes = new TreeMap<>();
    for (ExportedNode n : in.nodes()) {
      for (ExportedNode.Ref r : n.references()) {
        if (r.targetId().getNamespaceIndex().intValue() != 0 && !exported.contains(r.targetId())) {
          String target = uri(in.serverNamespaces(), r.targetId());
          String type = r.referenceTypeId().toParseableString() + (r.forward() ? "" : " (inverse)");
          (exportedNamespaces.contains(target) ? missingNodes : otherNamespaces)
              .computeIfAbsent(target, k -> new TreeMap<>())
              .merge(type, 1, Integer::sum);
        }
      }
    }
    if (otherNamespaces.isEmpty() && missingNodes.isEmpty()) {
      out.add("None, apart from the OPC UA namespace (ns=0).");
      out.add("");
    }
    if (!otherNamespaces.isEmpty()) {
      out.add("Namespaces not in this NodeSet: their NodeSets are needed when the file is loaded by a tool.");
      out.add("");
      addReferenceTable(out, otherNamespaces);
    }
    if (!missingNodes.isEmpty()) {
      out.add("Nodes missing from namespaces that are in this NodeSet: not reached by the crawl or not readable.");
      out.add("");
      addReferenceTable(out, missingNodes);
    }
    return String.join("\n", out);
  }

  private static void addReferenceTable(List<String> out, Map<String, Map<String, Integer>> byNamespace) {
    out.add("| Namespace | Reference type | References |");
    out.add("|---|---|---|");
    for (Map.Entry<String, Map<String, Integer>> e : byNamespace.entrySet()) {
      for (Map.Entry<String, Integer> t : e.getValue().entrySet()) {
        out.add(row(e.getKey(), t.getKey(), Integer.toString(t.getValue())));
      }
    }
    out.add("");
  }

  static NodeId simpleSupertype(ExportedNode n) {
    for (ExportedNode.Ref r : n.references()) {
      if (!r.forward() && NodeIds.HasSubtype.equals(r.referenceTypeId())) {
        NodeId t = r.targetId();
        if (t.getNamespaceIndex().intValue() == 0 && t.getIdentifier() instanceof Number num) {
          long id = num.longValue();
          if (id >= 1 && id <= 25 && id != 22 && id != 24) {
            return t;
          }
        }
      }
    }
    return null;
  }

  private static Object definition(ExportedNode n) {
    Object d = n.attribute(AttributeId.DataTypeDefinition);
    return d instanceof StructureDefinition || d instanceof EnumDefinition ? d : null;
  }

  private static String kind(ExportedNode n, Object d) {
    if (d instanceof StructureDefinition sd) {
      StructureType st = sd.getStructureType();
      return st == StructureType.Union || st == StructureType.UnionWithSubtypedValues ? "Union" : "Structure";
    }
    if (d instanceof EnumDefinition) {
      return NodeSetXmlWriter.isOptionSet(n) ? "OptionSet" : "Enumeration";
    }
    return "-";
  }

  private static String fieldCount(Object d) {
    if (d instanceof StructureDefinition sd) {
      return Integer.toString(sd.getFields() == null ? 0 : sd.getFields().length);
    }
    if (d instanceof EnumDefinition ed) {
      return Integer.toString(ed.getFields() == null ? 0 : ed.getFields().length);
    }
    return "-";
  }

  private static String uri(NamespaceTable table, NodeId id) {
    String uri = table.get(id.getNamespaceIndex());
    return uri != null ? uri : "urn:unknown-namespace-index:" + id.getNamespaceIndex();
  }

  private static String nodeSetIndex(List<String> nodeSetUris, String uri) {
    int i = nodeSetUris.indexOf(uri);
    return i < 0 ? uri : "ns=" + (i + 1);
  }

  private static String yesNo(boolean b) {
    return b ? "yes" : "no";
  }

  private static String valueOrDash(String s) {
    return s == null ? "-" : s;
  }

  private static String row(String... cells) {
    StringBuilder sb = new StringBuilder("|");
    for (String c : cells) {
      sb.append(' ').append(oneLine(c).replace("|", "\\|")).append(" |");
    }
    return sb.toString();
  }

  private static String oneLine(String s) {
    return s == null ? "" : s.replace("\r", " ").replace("\n", " ");
  }
}
