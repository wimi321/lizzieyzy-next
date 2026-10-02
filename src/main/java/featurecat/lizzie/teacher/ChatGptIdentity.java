package featurecat.lizzie.teacher;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;

final class ChatGptIdentity {
  final String subject;
  final String email;

  private ChatGptIdentity(String subject, String email) {
    this.subject = subject;
    this.email = email;
  }

  static ChatGptIdentity verify(
      String token,
      String jwks,
      String issuer,
      String clientId,
      String nonce,
      String expectedSubject,
      Instant now)
      throws IOException {
    try {
      SignedJWT jwt = SignedJWT.parse(token);
      if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) throw new Exception();
      JWK key = JWKSet.parse(jwks).getKeyByKeyId(jwt.getHeader().getKeyID());
      if (!(key instanceof RSAKey) || !jwt.verify(new RSASSAVerifier((RSAKey) key))) {
        throw new Exception();
      }
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      if (!issuer.equals(claims.getIssuer())
          || !claims.getAudience().contains(clientId)
          || claims.getExpirationTime() == null
          || !claims.getExpirationTime().after(Date.from(now))
          || claims.getSubject() == null
          || claims.getSubject().isBlank()
          || (claims.getNotBeforeTime() != null
              && claims.getNotBeforeTime().after(Date.from(now.plusSeconds(30))))
          || (claims.getIssueTime() != null
              && claims.getIssueTime().after(Date.from(now.plusSeconds(30))))
          || (nonce != null && !nonce.equals(claims.getStringClaim("nonce")))
          || (expectedSubject != null && !expectedSubject.equals(claims.getSubject()))
          || (claims.getClaim("azp") != null && !clientId.equals(claims.getStringClaim("azp")))
          || (claims.getAudience().size() > 1 && !clientId.equals(claims.getStringClaim("azp")))) {
        throw new Exception();
      }
      String email = claims.getStringClaim("email");
      return new ChatGptIdentity(claims.getSubject(), email == null ? "ChatGPT" : email);
    } catch (Exception invalid) {
      // Never retain the parsing exception: it can contain the ID token or its claims.
      throw ChatGptHttp.error("identity");
    }
  }
}
