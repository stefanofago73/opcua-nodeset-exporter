package opcua.nodeset.exporter;

import static org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.EndpointConfig;
import org.eclipse.milo.opcua.sdk.server.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfigLimits;
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaDataTypeNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaMethodNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaObjectNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaObjectTypeNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaReferenceTypeNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableTypeNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaViewNode;
import org.eclipse.milo.opcua.sdk.server.nodes.filters.AttributeFilter;
import org.eclipse.milo.opcua.sdk.server.nodes.filters.AttributeFilterContext;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.DefaultApplicationGroup;
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager;
import org.eclipse.milo.opcua.stack.core.security.DefaultServerCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.MemoryCertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.MemoryCertificateStore;
import org.eclipse.milo.opcua.stack.core.security.MemoryTrustListManager;
import org.eclipse.milo.opcua.stack.core.security.RsaSha256CertificateFactory;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile;
import org.eclipse.milo.opcua.stack.core.types.UaStructuredType;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExpandedNodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExtensionObject;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.EUInformation;
import org.eclipse.milo.opcua.stack.core.types.structured.Range;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig;

final class PlantTestServer {

  static final String NAMESPACE_URI = "urn:opcua-nodeset-exporter:test:plant";
  static final int MANY_CHILDREN = 25;

  private final OpcUaServer server;
  private final PlantNamespace namespace;
  private final int port;

  private PlantTestServer(OpcUaServer server, PlantNamespace namespace, int port) {
    this.server = server;
    this.namespace = namespace;
    this.port = port;
  }

  String endpointUrl() {
    return "opc.tcp://localhost:" + port + "/plant";
  }

  int namespaceIndex() {
    return namespace.getNamespaceIndex().intValue();
  }

  NodeId nodeId(String id) {
    return new NodeId(namespace.getNamespaceIndex(), id);
  }

  static PlantTestServer start() throws Exception {
    return start(new OpcUaServerConfigLimits() {});
  }

  static PlantTestServer start(OpcUaServerConfigLimits limits) throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    String applicationUri = "urn:opcua-nodeset-exporter:test:plant-server";
    KeyPair keyPair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);
    X509Certificate certificate =
        new SelfSignedCertificateBuilder(keyPair)
            .setCommonName("PlantTestServer")
            .setApplicationUri(applicationUri)
            .addDnsName("localhost")
            .build();

    var trustListManager = new MemoryTrustListManager();
    var quarantine = new MemoryCertificateQuarantine();
    var certificateFactory =
        new RsaSha256CertificateFactory() {
          @Override
          protected KeyPair createRsaSha256KeyPair() {
            return keyPair;
          }

          @Override
          protected X509Certificate[] createRsaSha256CertificateChain(KeyPair kp) {
            return new X509Certificate[] {certificate};
          }
        };
    var group =
        DefaultApplicationGroup.createAndInitialize(
            trustListManager,
            new MemoryCertificateStore(),
            certificateFactory,
            new DefaultServerCertificateValidator(trustListManager, quarantine));

    EndpointConfig endpoint =
        EndpointConfig.newBuilder()
            .setBindAddress("localhost")
            .setHostname("localhost")
            .setPath("/plant")
            .setBindPort(port)
            .setCertificate(certificate)
            .setSecurityPolicy(SecurityPolicy.None)
            .setSecurityMode(MessageSecurityMode.None)
            .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
            .addTokenPolicies(USER_TOKEN_POLICY_ANONYMOUS)
            .build();

    OpcUaServerConfig config =
        OpcUaServerConfig.builder()
            .setApplicationUri(applicationUri)
            .setApplicationName(LocalizedText.english("Plant test server"))
            .setProductUri("urn:opcua-nodeset-exporter:test")
            .setEndpoints(Set.of(endpoint))
            .setCertificateManager(new DefaultCertificateManager(quarantine, group))
            .setIdentityValidator(AnonymousIdentityValidator.INSTANCE)
            .setLimits(limits)
            .build();

    var server =
        new OpcUaServer(
            config,
            profile -> new OpcTcpServerTransport(OpcTcpServerTransportConfig.newBuilder().build()));

    var namespace = new PlantNamespace(server);
    namespace.startup();
    server.startup().get();
    // The custom reference types must be known to Browse with includeSubtypes=true.
    server.updateReferenceTypeTree();
    return new PlantTestServer(server, namespace, port);
  }

  void stop() throws Exception {
    namespace.shutdown();
    server.shutdown().get();
  }

  static int unusedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  // -----------------------------------------------------------------------------------------------

  static final class PlantNamespace extends ManagedNamespaceWithLifecycle {

    private final OpcUaServer server;

    PlantNamespace(OpcUaServer server) {
      super(server, NAMESPACE_URI);
      this.server = server;
      getLifecycleManager().addStartupTask(this::build);
    }

    // The exporter never subscribes: monitored items are not supported by this test namespace.
    @Override
    public void onDataItemsCreated(List<DataItem> dataItems) {}

    @Override
    public void onDataItemsModified(List<DataItem> dataItems) {}

    @Override
    public void onDataItemsDeleted(List<DataItem> dataItems) {}

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {}

    private void build() {
      // --- types -------------------------------------------------------------------------------
      UaObjectTypeNode machineType =
          new UaObjectTypeNode.UaObjectTypeNodeBuilder(getNodeContext())
              .setNodeId(newNodeId("Types/MachineType"))
              .setBrowseName(newQualifiedName("MachineType"))
              .setDisplayName(LocalizedText.english("MachineType"))
              .setIsAbstract(false)
              .build();
      add(machineType, NodeIds.HasSubtype, NodeIds.BaseObjectType);

      UaVariableTypeNode speedType =
          new UaVariableTypeNode.UaVariableTypeNodeBuilder(getNodeContext())
              .setNodeId(newNodeId("Types/SpeedType"))
              .setBrowseName(newQualifiedName("SpeedType"))
              .setDisplayName(LocalizedText.english("SpeedType"))
              .setDataType(NodeIds.Double)
              .setValueRank(-1)
              .setIsAbstract(true)
              .build();
      add(speedType, NodeIds.HasSubtype, NodeIds.BaseDataVariableType);

      var machineState =
          new UaDataTypeNode(
              getNodeContext(),
              newNodeId("Types/MachineState"),
              newQualifiedName("MachineState"),
              LocalizedText.english("MachineState"),
              LocalizedText.NULL_VALUE,
              uint(0),
              uint(0),
              false);
      add(machineState, NodeIds.HasSubtype, NodeIds.Enumeration);

      var feeds =
          new UaReferenceTypeNode(
              getNodeContext(),
              newNodeId("Types/Feeds"),
              newQualifiedName("Feeds"),
              LocalizedText.english("Feeds"),
              LocalizedText.NULL_VALUE,
              uint(0),
              uint(0),
              false,
              false,
              LocalizedText.english("FedBy"));
      add(feeds, NodeIds.HasSubtype, NodeIds.NonHierarchicalReferences);

      var hasStation =
          new UaReferenceTypeNode(
              getNodeContext(),
              newNodeId("Types/HasStation"),
              newQualifiedName("HasStation"),
              LocalizedText.english("HasStation"),
              LocalizedText.NULL_VALUE,
              uint(0),
              uint(0),
              false,
              false,
              LocalizedText.english("StationOf"));
      add(hasStation, NodeIds.HasSubtype, NodeIds.HasComponent);

      // --- instances ---------------------------------------------------------------------------
      UaFolderNode plant = folder("Plant", NodeIds.ObjectsFolder);
      UaFolderNode line1 = folder("Plant/Line1", plant.getNodeId());
      // Cycle: Line1 organizes its own parent.
      line1.addReference(
          new Reference(line1.getNodeId(), NodeIds.Organizes, plant.getNodeId().expanded(), true));

      UaObjectNode machine =
          new UaObjectNode.UaObjectNodeBuilder(getNodeContext())
              .setNodeId(newNodeId("Plant/Machine1"))
              .setBrowseName(newQualifiedName("Machine1"))
              .setDisplayName(LocalizedText.english("Machine1"))
              .setEventNotifier(ubyte(1))
              .setTypeDefinition(machineType.getNodeId())
              .build();
      add(machine, NodeIds.Organizes, plant.getNodeId());
      machine.addReference(
          new Reference(machine.getNodeId(), feeds.getNodeId(), line1.getNodeId().expanded(), true));

      UaVariableNode temperature =
          variable("Plant/Machine1/Temperature", "Temperature", NodeIds.Double, NodeIds.AnalogItemType)
              .setValue(new DataValue(new Variant(21.5)))
              .build();
      add(temperature, NodeIds.HasComponent, machine.getNodeId());
      property(
          temperature,
          "EngineeringUnits",
          NodeIds.EUInformation,
          new EUInformation(
              "http://www.opcfoundation.org/UA/units/un/cefact",
              4408652,
              LocalizedText.english("°C"),
              LocalizedText.english("degree Celsius")));
      property(temperature, "EURange", NodeIds.Range, new Range(0.0, 250.0));

      UaVariableNode counters =
          variable("Plant/Machine1/Counters", "Counters", NodeIds.Int32, NodeIds.BaseDataVariableType)
              .setDescription(new LocalizedText("it", "Contatori; \"zona\" 1\nriga 2 °C"))
              .setValueRank(1)
              .setArrayDimensions(new UInteger[] {uint(4)})
              .setAccessLevel(AccessLevel.READ_WRITE)
              .setUserAccessLevel(AccessLevel.READ_WRITE)
              .setMinimumSamplingInterval(250.0)
              .setHistorizing(true)
              .setValue(new DataValue(new Variant(new Integer[] {1, 2, 3, 4})))
              .build();
      add(counters, NodeIds.HasComponent, machine.getNodeId());

      UaMethodNode reset =
          new UaMethodNode.UaMethodNodeBuilder(getNodeContext())
              .setNodeId(newNodeId("Plant/Machine1/Reset"))
              .setBrowseName(newQualifiedName("Reset"))
              .setDisplayName(LocalizedText.english("Reset"))
              .setExecutable(false)
              .setUserExecutable(false)
              .build();
      add(reset, NodeIds.HasComponent, machine.getNodeId());

      UaVariableNode secret =
          variable("Plant/Machine1/Secret", "Secret", NodeIds.String, NodeIds.BaseDataVariableType)
              .setValue(new DataValue(new Variant("hidden")))
              .build();
      secret.getFilterChain().addLast(new DenyAllFilter());
      add(secret, NodeIds.HasComponent, machine.getNodeId());

      UaFolderNode many = folder("Plant/Many", plant.getNodeId());
      for (int i = 0; i < MANY_CHILDREN; i++) {
        UaVariableNode v =
            variable("Plant/Many/Tag" + i, "Tag" + i, NodeIds.UInt16, NodeIds.BaseDataVariableType)
                .setValue(new DataValue(new Variant(ushort(i))))
                .build();
        add(v, NodeIds.Organizes, many.getNodeId());
      }

      UaObjectNode station =
          new UaObjectNode.UaObjectNodeBuilder(getNodeContext())
              .setNodeId(newNodeId("Plant/Station1"))
              .setBrowseName(newQualifiedName("Station1"))
              .setDisplayName(LocalizedText.english("Station1"))
              .setTypeDefinition(NodeIds.BaseObjectType)
              .build();
      add(station, hasStation.getNodeId(), plant.getNodeId());

      UaObjectNode remote =
          new UaObjectNode.UaObjectNodeBuilder(getNodeContext())
              .setNodeId(newNodeId("Plant/Remote"))
              .setBrowseName(newQualifiedName("Remote"))
              .setDisplayName(LocalizedText.english("Remote"))
              .setTypeDefinition(NodeIds.BaseObjectType)
              .build();
      add(remote, NodeIds.Organizes, plant.getNodeId());
      remote.addReference(
          new Reference(
              remote.getNodeId(),
              feeds.getNodeId(),
              new ExpandedNodeId(
                  new ExpandedNodeId.ServerReference.ServerIndex(uint(1)),
                  new ExpandedNodeId.NamespaceReference.NamespaceIndex(ushort(0)),
                  uint(85)),
              true));

      var view =
          new UaViewNode(
              getNodeContext(),
              newNodeId("Views/MaintenanceView"),
              newQualifiedName("MaintenanceView"),
              LocalizedText.english("MaintenanceView"),
              LocalizedText.NULL_VALUE,
              uint(0),
              uint(0),
              true,
              ubyte(0));
      add(view, NodeIds.Organizes, NodeIds.ViewsFolder);
    }

    private UaFolderNode folder(String id, NodeId parent) {
      String name = id.substring(id.lastIndexOf('/') + 1);
      var f =
          new UaFolderNode(
              getNodeContext(), newNodeId(id), newQualifiedName(name), LocalizedText.english(name));
      add(f, NodeIds.Organizes, parent);
      return f;
    }

    private UaVariableNode.UaVariableNodeBuilder variable(
        String id, String name, NodeId dataType, NodeId typeDefinition) {
      return new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
          .setNodeId(newNodeId(id))
          .setBrowseName(newQualifiedName(name))
          .setDisplayName(LocalizedText.english(name))
          .setDataType(dataType)
          .setTypeDefinition(typeDefinition)
          .setAccessLevel(AccessLevel.CurrentRead)
          .setUserAccessLevel(AccessLevel.CurrentRead);
    }

    private void property(
        UaVariableNode owner, String name, NodeId dataType, UaStructuredType structure) {
      ExtensionObject xo =
          ExtensionObject.encode(server.getStaticEncodingContext(), structure);
      UaVariableNode p =
          new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
              .setNodeId(newNodeId(owner.getNodeId().getIdentifier() + "/" + name))
              .setBrowseName(new QualifiedName(0, name))
              .setDisplayName(LocalizedText.english(name))
              .setDataType(dataType)
              .setTypeDefinition(NodeIds.PropertyType)
              .setAccessLevel(AccessLevel.CurrentRead)
              .setUserAccessLevel(AccessLevel.CurrentRead)
              .setValue(new DataValue(new Variant(xo)))
              .build();
      add(p, NodeIds.HasProperty, owner.getNodeId());
    }

    private void add(UaNode node, NodeId referenceType, NodeId parent) {
      getNodeManager().addNode(node);
      node.addReference(
          new Reference(node.getNodeId(), referenceType, parent.expanded(), Reference.Direction.INVERSE));
    }
  }

  static final class DenyAllFilter implements AttributeFilter {
    private static final Set<AttributeId> BROWSE_ATTRIBUTES =
        Set.of(
            AttributeId.NodeId,
            AttributeId.NodeClass,
            AttributeId.BrowseName,
            AttributeId.DisplayName);

    @Override
    public Object readAttribute(AttributeFilterContext ctx, AttributeId attributeId)
        throws UaException {
      if (BROWSE_ATTRIBUTES.contains(attributeId)) {
        return ctx.readAttribute(attributeId);
      }
      throw new UaException(StatusCodes.Bad_UserAccessDenied);
    }
  }
}
