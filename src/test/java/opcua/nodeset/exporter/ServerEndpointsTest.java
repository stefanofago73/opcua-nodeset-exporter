package opcua.nodeset.exporter;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.List;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.enumerated.ApplicationType;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType;
import org.eclipse.milo.opcua.stack.core.types.structured.ApplicationDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;
import org.junit.jupiter.api.Test;

class ServerEndpointsTest {

  private static final String URL = "opc.tcp://server:4840";

  private static final ApplicationDescription SERVER =
      new ApplicationDescription(
          "urn:example:server", null, LocalizedText.english("Example Server"), ApplicationType.Server,
          null, null, null);

  private static EndpointDescription endpoint(
      ByteString certificate, MessageSecurityMode mode, SecurityPolicy policy, int level, UserTokenPolicy... tokens) {
    return new EndpointDescription(URL, SERVER, certificate, mode, policy.getUri(), tokens, null, ubyte(level));
  }

  private static final EndpointDescription NONE_ANONYMOUS =
      endpoint(
          ByteString.NULL_VALUE, MessageSecurityMode.None, SecurityPolicy.None, 0,
          new UserTokenPolicy("anonymous", UserTokenType.Anonymous, null, null, null));

  private static final EndpointDescription SECURE_USERNAME =
      endpoint(
          ByteString.NULL_VALUE, MessageSecurityMode.SignAndEncrypt, SecurityPolicy.Basic256Sha256, 3,
          new UserTokenPolicy("user", UserTokenType.UserName, null, null, SecurityPolicy.Basic256Sha256.getUri()));

  @Test
  void formatListsEveryEndpointAndTheUsableOnes() {
    String both = ServerEndpoints.format(URL, List.of(SECURE_USERNAME, NONE_ANONYMOUS));
    assertTrue(both.startsWith("Endpoints returned by opc.tcp://server:4840: 2\n"), both);
    assertTrue(both.contains("Server: Example Server (urn:example:server)\n"), both);
    assertTrue(both.contains("1. Basic256Sha256, mode SignAndEncrypt, SecurityLevel 3\n"), both);
    assertTrue(both.contains("   User tokens: UserName (Basic256Sha256)\n"), both);
    assertTrue(both.contains("2. None, mode None, SecurityLevel 0, usable by this exporter\n"), both);
    assertTrue(both.contains("   User tokens: Anonymous\n"), both);
    assertFalse(both.contains("Server certificate:"), both);
    assertTrue(both.endsWith("Usable by this exporter (SecurityPolicy None, anonymous): yes, endpoint 2\n"), both);

    String secureOnly = ServerEndpoints.format(URL, List.of(SECURE_USERNAME));
    assertTrue(secureOnly.endsWith("Usable by this exporter (SecurityPolicy None, anonymous): no\n"), secureOnly);

    assertTrue(ServerEndpoints.isNoneAnonymous(NONE_ANONYMOUS));
    assertFalse(ServerEndpoints.isNoneAnonymous(SECURE_USERNAME));
    assertEquals("-", ServerEndpoints.policyName(null));
  }

  @Test
  void serverCertificateIsDescribedOnce() throws Exception {
    KeyPair keyPair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);
    X509Certificate certificate =
        new SelfSignedCertificateBuilder(keyPair)
            .setCommonName("Example Server")
            .setApplicationUri("urn:example:server")
            .build();
    ByteString der = ByteString.of(certificate.getEncoded());
    var anonymous = new UserTokenPolicy("anonymous", UserTokenType.Anonymous, null, null, null);
    String text =
        ServerEndpoints.format(
            URL,
            List.of(
                endpoint(der, MessageSecurityMode.None, SecurityPolicy.None, 0, anonymous),
                endpoint(der, MessageSecurityMode.Sign, SecurityPolicy.Basic256Sha256, 2, anonymous)));
    assertEquals(1, text.split("Server certificate: ", -1).length - 1, text);
    assertTrue(text.contains("CN=Example Server"), text);
    assertTrue(text.contains(", SHA-1 thumbprint "), text);
    assertFalse(text.contains("EXPIRED"), text);

    assertTrue(ServerEndpoints.certificate(new byte[] {1, 2, 3}).startsWith("not readable (3 bytes)"));
  }
}
