package fu.se184491.loadmaster_be.service.payment;

import fu.se184491.loadmaster_be.config.payment.VnPayConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MockPaymentPolicy {
    private final VnPayConfig config;
    private final Environment environment;

    public boolean isEnabled() {
        return config.isMock() && environment.matchesProfiles("dev")
                && !environment.matchesProfiles("prod", "production");
    }

    public void requireEnabled() {
        if (!isEnabled()) {
            throw new AccessDeniedException("Mock payments require an explicitly enabled development profile");
        }
    }
}
