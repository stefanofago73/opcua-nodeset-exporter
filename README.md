# opcua-nodeset-exporter

Command line tool that connects to an OPC UA server, walks its address space and writes two files:

- `<name>.NodeSet2.xml`: the node dictionary in UANodeSet format (OPC UA Part 6, Annex F);
- `<name>.tags.csv`: a tag mapping with one row per variable, ready to open in Excel.

Built on [Eclipse Milo](https://github.com/eclipse-milo/milo) 1.1.7, runs on Java 17 or later, single self-contained jar.

## Build and run

Requires JDK 17 or later and Git; Maven is downloaded by the wrapper.

```
git clone https://github.com/stefanofago73/opcua-nodeset-exporter.git
cd opcua-nodeset-exporter
./mvnw clean package        # Linux, macOS
mvnw.cmd clean package      # Windows
```

The build runs the tests (see [Tests](#tests)) and produces the self-contained jar `target/opcua-nodeset-exporter.jar`, which can be copied anywhere and needs only Java 17 or later to run:

```
java -jar target/opcua-nodeset-exporter.jar --endpoint opc.tcp://<host>:<port> --out export --name machine01
```

At the end `export/machine01.NodeSet2.xml` and `export/machine01.tags.csv` are written. The output directory is created if missing; existing files with the same name are overwritten. Run without arguments to print the usage text.

## Parameters

| Parameter | Default | Allowed values |
|---|---|---|
| `--endpoint <url>` | required | `opc.tcp://host:port[/path]` |
| `--root <nodeId>` | `i=84` (RootFolder) | NodeId text; repeatable |
| `--out <dir>` | `export` | Directory, relative or absolute |
| `--name <base>` | `export` | File base name |
| `--browse-batch <n>` | `200` | Integer >= 2 |
| `--read-batch <n>` | `2000` | Integer >= 1 |
| `--max-nodes <n>` | `200000` | Integer >= 0, 0 = no limit |
| `--csv-separator <c>` | `;` | Exactly one character |
| `--keep-endpoint-host` | off | Flag, no value |
| `--progress-seconds <n>` | `3` | Integer >= 1 |

`--endpoint`: server endpoint URL. The client asks the server for its endpoints and uses the first one with SecurityPolicy None and an anonymous token policy; if there is none, the run fails with `no endpoint selected`.

`--root`: node where the crawl starts. Repeat it to start from several nodes (`--root "ns=2;s=Tags" --root i=86`). Accepted forms: `i=85`, `ns=2;i=1001`, `ns=2;s=Tags`, `ns=2;g=<GUID>`, `ns=2;b=<base64>`. The namespace index is the server's one. Quote the value: in PowerShell and in Unix shells `;` separates commands. The default `i=84` covers `Objects`, `Types` and `Views`; a narrower root is faster but leaves out the vendor types referenced by the exported nodes.

`--out`, `--name`: where the two files are written and their base name.

`--browse-batch`: maximum BrowseDescriptions in one Browse request. Each exported node needs two, so 200 means about 100 nodes per request. Lower it if the server closes the connection on large responses; it is also reduced automatically to the server's `MaxNodesPerBrowse`.

`--read-batch`: maximum attributes in one Read request; reduced automatically to the server's `MaxNodesPerRead`.

`--max-nodes`: stops the crawl after this many visited nodes (all namespaces, ns=0 included). Protects against servers that generate node ids without end; when it triggers, the log says `the export is INCOMPLETE`.

`--csv-separator`: field separator of the CSV. `;` suits Excel with regional settings that use the comma as decimal separator; use `","` otherwise or for other tools (quoted: in PowerShell a bare `,` is an operator).

`--keep-endpoint-host`: connect to the host name the server advertises in GetEndpoints instead of the host written in `--endpoint`. By default the host of `--endpoint` is kept, because many embedded servers advertise a host name that does not resolve on the network.

`--progress-seconds`: interval between progress lines in the log.

## Examples

Full export:

```
java -jar target/opcua-nodeset-exporter.jar --endpoint opc.tcp://my-server:4840 --name machine01
```

Only one subtree:

```
java -jar target/opcua-nodeset-exporter.jar --endpoint opc.tcp://my-server:4840 --root "ns=2;s=Tags" --name tags-only
```

Server that drops the connection with large responses:

```
java -jar target/opcua-nodeset-exporter.jar --endpoint opc.tcp://my-server:4840 --browse-batch 100 --read-batch 500
```

Very large server, more heap and a GC log to check memory:

```
java -Xmx2g -Xlog:gc:file=gc.log -jar target/opcua-nodeset-exporter.jar --endpoint opc.tcp://my-server:4840 --max-nodes 0
```

CSV with commas, progress every 10 seconds:

```
java -jar target/opcua-nodeset-exporter.jar --endpoint opc.tcp://my-server:4840 --csv-separator "," --progress-seconds 10
```

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Export completed (check the log for WARN lines: skipped nodes, truncated crawl) |
| `1` | Export failed: connection, server error, unreadable root, write error |
| `2` | Invalid command line; the usage text is printed |

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `java` not found | Java is not installed or not on the PATH |
| `UnsupportedClassVersionError ... class file version 61.0` | Java is older than 17 |
| `no endpoint selected` | The server offers no endpoint with SecurityPolicy None and anonymous access: not supported |
| `Bad_ConnectionRejected` | Wrong host or port, server down, or port blocked by a firewall |
| `Bad_SecurityChecksFailed`, then `Bad_ConnectionClosed` | The server cannot handle large multi-chunk responses: lower `--browse-batch` and `--read-batch` |
| `the export is INCOMPLETE` | `--max-nodes` reached: run again with a higher value or `0` |
| `WARN ... Skipped` lines | Nodes visible in Browse but not readable by the anonymous user: left out |

## Progress output

Every log line starts with the time (`HH:mm:ss`). Progress lines are printed at most once every `--progress-seconds`:

```
11:21:54 ... Namespace ns=2 urn:eclipse:milo:hello-world first seen at Root/Objects/HelloWorld
11:21:56 ... Browse 00:00:02 visited=1763 (ns0=1673 ns1=4 ns2=86) exported=74 queued=1324 (881 nodes/s)
11:22:08 ... Browse done in 00:00:13: visited=4771 exported=145
11:22:08 ...   ns=2 urn:eclipse:milo:hello-world: visited=89 exported=89
11:22:08 ... Write NodeSet done in 00:00:00 (0.1 MB)
11:22:08 ... Done in 00:00:16 (connect 00:00:01, browse 00:00:13, read 00:00:00, write 00:00:00)
```

The browse phase shows no percentage: the total is unknown until the tree is fully discovered, so the queue size is the indicator of convergence. The read phase knows its total and shows percentage and ETA for each of its three passes (`Read attributes (1/3) <elapsed> <percent>% (<done>/<total>) ETA <remaining>`). A namespace line is printed only the first time a node of that namespace is reached.

As an order of magnitude, a server with about 50,000 nodes was exported in under 3 minutes with the default batch sizes and less than 200 MB of heap.

## How it works

1. Connects with SecurityPolicy None and anonymous identity, then reads the server NamespaceArray.
2. Browses breadth-first from the roots, following forward `HierarchicalReferences` (subtypes included) in every namespace, with BrowseNext for continuation points; results with `Bad_NoContinuationPoints` are retried one at a time. A visited set prevents loops on cyclic references.
3. Exports only nodes whose namespace index is not 0: the standard nodes are defined by the base nodeset. The default root `i=84` covers `Objects`, `Types` and `Views`, so vendor ObjectTypes, VariableTypes, DataTypes and ReferenceTypes are included.
4. For each exported node, browses all its references (forward and inverse) and reads Description, WriteMask and the attributes of its NodeClass. NodeClass, BrowseName and DisplayName are taken from the Browse results. A node with no readable attribute (for example `Bad_UserAccessDenied`) is skipped with a WARN.
5. Writes the NodeSet with namespace indexes remapped to its own `NamespaceUris` table (server index 2 can become NodeSet index 1), and the CSV with server indexes plus the namespace URI.

Batch sizes are reduced automatically to the server's `OperationLimits` (`MaxNodesPerBrowse`, `MaxNodesPerRead`) when the server publishes them.

## CSV columns

One row per Variable: `NodeId`, `NamespaceUri`, `BrowsePath`, `BrowseName`, `DisplayName`, `Description`, `DataType`, `DataTypeName`, `ValueRank`, `ArrayDimensions`, `AccessLevel`, `Historizing`, `MinimumSamplingInterval`, `EngineeringUnits`, `EURange`.

`NodeId` uses the server's namespace index; `NamespaceUri` gives its stable identity. `EngineeringUnits` and `EURange` come from the standard properties of the same name (AnalogItemType), when present. The file is UTF-8 with BOM and CRLF line ends, so that Excel shows accented characters and symbols such as °C.

## Limits

- Only SecurityPolicy None with anonymous identity.
- The NodeSet is a dump of what the server exposes at runtime to this session, not the vendor's design model: nodes the anonymous user cannot read are missing or skipped (logged as WARN).
- Not written: node `Value`, `DataTypeDefinition` of structured types, `Models`, `Aliases`, `RolePermissions`. The file is schema-valid, but tools that require `Models` (for example some nodeset compilers) need it added.
- Dynamic server content is exported as found, including the session diagnostics nodes of the exporter's own session when the server publishes them in a non-zero namespace.
- References to nodes on other servers (ExpandedNodeId with server index) are dropped and counted in the log.
- A dropped connection stops the export: there is no reconnect or resume, the run has to be started again.
- A server that allows only one node per Browse (`MaxNodesPerBrowse=1`) is refused with an explicit error: an exported node needs two BrowseDescriptions in the same request.

## Tests

`mvnw clean package` (or `mvnw test`) runs 43 tests:

| Test class | What it covers |
|---|---|
| `ExporterIntegrationTest` | End-to-end runs against `PlantTestServer`, an embedded Milo 1.1.7 server on a free `localhost` port |
| `NodeSetXmlWriterTest` | NodeSet XML: namespace remap, all node classes, identifier types, defaults, validity against `UANodeSet.xsd` |
| `TagMappingCsvWriterTest` | CSV: BOM, CRLF, quoting, separators, AccessLevel bits, only Variables |
| `NodeSetExporterTest` | Command line options, defaults, errors, server limits, size formatting |
| `AddressSpaceCrawlerTest` | Attributes per NodeClass, NodeClass fallback read, dropped references |
| `ProgressTest` | Time formatting, throttling, ETA |

`PlantTestServer` publishes one namespace with every case the exporter must handle: a reference cycle, a custom hierarchical ReferenceType (nodes reachable only through it), custom ObjectType, VariableType, DataType and ReferenceType, a View, a Method with `Executable=false`, an AnalogItem with EngineeringUnits and EURange, an array variable with special characters in its description, a folder with 25 children, and a node visible in Browse whose attributes are denied. The integration tests also cover BrowseNext, `Bad_NoContinuationPoints` retries, server OperationLimits, `--max-nodes`, `--root`, `--csv-separator`, `--keep-endpoint-host`, an unreadable root and an unreachable server.

## License

Apache License 2.0, see [LICENSE](LICENSE). The jar built by `mvnw package` bundles third-party libraries (Eclipse Milo and digitalpetri libraries under EPL-2.0, Netty, Guava and JCTools under Apache-2.0, Bouncy Castle and SLF4J under MIT): see [NOTICE](NOTICE) and the [licenses](licenses) directory.

OPC UA is a trademark of the OPC Foundation. This project is not affiliated with the OPC Foundation or the Eclipse Foundation.
