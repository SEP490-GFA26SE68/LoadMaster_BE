package fu.se184491.loadmaster_be.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import fu.se184491.loadmaster_be.config.security.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Local ephemeral key: no Keycloak, database, service credentials or hardcoded access tokens. */
@TestConfiguration(proxyBeanMethods = false)
@Import({WebSecurityConfig.class, JwtConverterConfig.class, KeycloakRealmRoleConverter.class})
public class TestJwtSupport {
    public static final String ISSUER = "https://issuer.test/realms/loadmaster";
    private static final RSAKey KEY;
    static {
        try { KEY = new RSAKeyGenerator(2048).keyID("security-test").generate(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @Bean
    JwtDecoder jwtDecoder() throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtConfig.validatorsFor(ISSUER));
        return decoder;
    }

    public static String token(String audience, String client, String username, String subject, List<String> roles) throws Exception {
        return token(audience, client, username, subject, roles, ISSUER, Instant.now().plusSeconds(300));
    }

    public static String token(String audience, String client, String username, String subject,
                               List<String> roles, String issuer, Instant expires) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(expires))
                .claim("azp", client).claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", roles)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    public static String serviceToken(String audience, String client, String username, List<String> roles) throws Exception {
        return token(audience, client, username, "credit-service-account", roles);
    }

    public static String userToken(String subject, String role) throws Exception {
        return token("loadmaster-api", "loadmaster-web", subject, subject, List.of(role));
    }
}
