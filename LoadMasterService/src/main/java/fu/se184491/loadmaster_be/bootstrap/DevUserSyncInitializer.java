package fu.se184491.loadmaster_be.bootstrap;

import fu.se184491.loadmaster_be.service.account.UserIdentitySyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("dev")
@Order(3)
@ConditionalOnProperty(name = "app.bootstrap.sync.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevUserSyncInitializer implements ApplicationRunner {

    private final UserIdentitySyncService userIdentitySyncService;

    @Override
    public void run(ApplicationArguments args) {
        UserIdentitySyncService.Result result = userIdentitySyncService.syncMissingUsers();
        log.info(
                "DEV_USER_SYNC xong: createdInDb={} createdInKeycloak={} relinked={}",
                result.createdInDb(),
                result.createdInKeycloak(),
                result.relinked()
        );
    }
}
