package opcua.nodeset.exporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class TestSupport {

  private TestSupport() {}

  static void validateNodeSet(Path file) throws Exception {
    var schema =
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            .newSchema(TestSupport.class.getResource("/UANodeSet.xsd"));
    schema.newValidator().validate(new StreamSource(file.toFile()));
  }

  record NodeSet(List<String> namespaceUris, Map<String, Element> nodes) {

    Element node(String nodeId) {
      Element e = nodes.get(nodeId);
      if (e == null) {
        throw new AssertionError("node not in NodeSet: " + nodeId + " (have " + nodes.keySet() + ")");
      }
      return e;
    }

    List<String> references(String nodeId) {
      List<String> out = new ArrayList<>();
      NodeList refs = node(nodeId).getElementsByTagNameNS(NodeSetXmlWriter.UA_NODESET_NS, "Reference");
      for (int i = 0; i < refs.getLength(); i++) {
        Element r = (Element) refs.item(i);
        String forward = r.hasAttribute("IsForward") ? r.getAttribute("IsForward") : "true";
        out.add(r.getAttribute("ReferenceType") + "|" + forward + "|" + r.getTextContent());
      }
      return out;
    }

    String childText(String nodeId, String child) {
      NodeList l = node(nodeId).getElementsByTagNameNS(NodeSetXmlWriter.UA_NODESET_NS, child);
      return l.getLength() == 0 ? null : l.item(0).getTextContent();
    }
  }

  static NodeSet parseNodeSet(Path file) throws Exception {
    var f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    Document doc = f.newDocumentBuilder().parse(file.toFile());
    List<String> uris = new ArrayList<>();
    Map<String, Element> nodes = new LinkedHashMap<>();
    for (Node n = doc.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
      if (!(n instanceof Element e)) {
        continue;
      }
      if (e.getLocalName().equals("NamespaceUris")) {
        NodeList l = e.getElementsByTagNameNS(NodeSetXmlWriter.UA_NODESET_NS, "Uri");
        for (int i = 0; i < l.getLength(); i++) {
          uris.add(l.item(i).getTextContent());
        }
      } else {
        if (nodes.put(e.getAttribute("NodeId"), e) != null) {
          throw new AssertionError("duplicate NodeId " + e.getAttribute("NodeId"));
        }
      }
    }
    return new NodeSet(uris, nodes);
  }

  static String withoutTimestamp(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8).replaceAll("LastModified=\"[^\"]*\"", "");
  }

  static List<List<String>> parseCsv(Path file, char separator) throws IOException {
    String s = Files.readString(file, StandardCharsets.UTF_8);
    if (s.startsWith("﻿")) {
      s = s.substring(1);
    }
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          field.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == separator) {
        row.add(field.toString());
        field.setLength(0);
      } else if (c == '\r') {
        // expect \n next
      } else if (c == '\n') {
        row.add(field.toString());
        field.setLength(0);
        rows.add(row);
        row = new ArrayList<>();
      } else {
        field.append(c);
      }
    }
    if (field.length() > 0 || !row.isEmpty()) {
      row.add(field.toString());
      rows.add(row);
    }
    return rows;
  }
}
