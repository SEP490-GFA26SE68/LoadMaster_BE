package fu.se184491.loadmaster_be.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;

import java.util.Objects;

public class AudienceValidator
        implements OAuth2TokenValidator<Jwt> {

    private final String audience;

    public AudienceValidator(String audience) {
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {

        if (Objects.requireNonNull(jwt.getAudience()).contains(audience)) {
            return OAuth2TokenValidatorResult.success();
        }

        OAuth2Error error =
                new OAuth2Error(
                        "invalid_token",
                        "Required audience is missing",
                        null
                );

        return OAuth2TokenValidatorResult.failure(error);
    }

    @Bean
    public JwtDecoder jwtDecoder() {

        String issuer =
                "http://localhost:8180/realms/loadmaster";

        NimbusJwtDecoder decoder =
                JwtDecoders.fromIssuerLocation(issuer);

        OAuth2TokenValidator<Jwt> issuerValidator =
                JwtValidators.createDefaultWithIssuer(issuer);

        OAuth2TokenValidator<Jwt> audienceValidator =
                new AudienceValidator("loadmaster-api");

        OAuth2TokenValidator<Jwt> validator =
                new DelegatingOAuth2TokenValidator<>(
                        issuerValidator,
                        audienceValidator
                );

        decoder.setJwtValidator(validator);

        return decoder;
    }
}