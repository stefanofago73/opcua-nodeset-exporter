package opcua.nodeset.exporter;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.milo.opcua.sdk.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.eclipse.milo.opcua.stack.core.types.structured.ApplicationDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy;

final class ServerEndpoints {

  private ServerEndpoints() {}

  static List<EndpointDescription> get(String endpointUrl) throws Exception {
    try {
      return DiscoveryClient.getEndpoints(endpointUrl).get();
    } catch (ExecutionException e) {
      throw e.getCause() instanceof Exception cause ? cause : e;
    }
  }

  static boolean isNoneAnonymous(EndpointDescription e) {
    return SecurityPolicy.None.getUri().equals(e.getSecurityPolicyUri())
        && tokens(e).anyMatch(p -> p.getTokenType() == UserTokenType.Anonymous);
  }

  static String format(String endpointUrl, List<EndpointDescription> endpoints) {
    StringBuilder sb = new StringBuilder();
    sb.append("Endpoints returned by ").append(endpointUrl).append(": ").append(endpoints.size()).append('\n');
    if (!endpoints.isEmpty()) {
      sb.append("Server: ").append(server(endpoints.get(0).getServer())).append('\n');
    }
    List<String> usable = new ArrayList<>();
    Set<ByteString> certificates = new LinkedHashSet<>();
    for (int i = 0; i < endpoints.size(); i++) {
      EndpointDescription e = endpoints.get(i);
      boolean ok = isNoneAnonymous(e);
      if (ok) {
        usable.add(Integer.toString(i + 1));
      }
      sb.append(i + 1)
          .append(". ")
          .append(policyName(e.getSecurityPolicyUri()))
          .append(", mode ")
          .append(e.getSecurityMode())
          .append(", SecurityLevel ")
          .append(e.getSecurityLevel())
          .append(ok ? ", usable by this exporter" : "")
          .append('\n');
      sb.append("   URL: ").append(e.getEndpointUrl()).append('\n');
      sb.append("   User tokens: ").append(tokenList(e)).append('\n');
      ByteString certificate = e.getServerCertificate();
      if (certificate != null && !certificate.isNullOrEmpty()) {
        certificates.add(certificate);
      }
    }
    for (ByteString c : certificates) {
      sb.append("Server certificate: ").append(certificate(c.bytesOrEmpty())).append('\n');
    }
    sb.append("Usable by this exporter (SecurityPolicy None, anonymous): ")
        .append(usable.isEmpty() ? "no" : "yes, endpoint " + String.join(", ", usable))
        .append('\n');
    return sb.toString();
  }

  static String certificate(byte[] der) {
    try {
      var x509 =
          (X509Certificate)
              CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
      Instant notAfter = x509.getNotAfter().toInstant();
      String thumbprint =
          HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(x509.getEncoded()));
      return x509.getSubjectX500Principal().getName()
          + ", valid until "
          + notAfter
          + (notAfter.isBefore(Instant.now()) ? " (EXPIRED)" : "")
          + ", SHA-1 thumbprint "
          + thumbprint;
    } catch (Exception e) {
      return "not readable (" + der.length + " bytes): " + e.getMessage();
    }
  }

  static String policyName(String uri) {
    if (uri == null || uri.isEmpty()) {
      return "-";
    }
    int hash = uri.lastIndexOf('#');
    return hash >= 0 ? uri.substring(hash + 1) : uri;
  }

  private static String server(ApplicationDescription server) {
    if (server == null) {
      return "-";
    }
    LocalizedText name = server.getApplicationName();
    String text = name == null ? null : name.getText();
    return (text == null ? "-" : text) + " (" + server.getApplicationUri() + ")";
  }

  private static String tokenList(EndpointDescription e) {
    String list =
        tokens(e)
            .map(
                p -> {
                  String policy = p.getSecurityPolicyUri();
                  return p.getTokenType()
                      + (policy == null || policy.isEmpty() ? "" : " (" + policyName(policy) + ")");
                })
            .collect(Collectors.joining(", "));
    return list.isEmpty() ? "none" : list;
  }

  private static Stream<UserTokenPolicy> tokens(EndpointDescription e) {
    UserTokenPolicy[] policies = e.getUserIdentityTokens();
    return policies == null ? Stream.empty() : Stream.of(policies);
  }
}
