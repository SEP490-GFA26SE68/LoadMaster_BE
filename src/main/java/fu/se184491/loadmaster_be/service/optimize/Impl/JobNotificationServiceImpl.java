package fu.se184491.loadmaster_be.service.optimize.Impl;

import fu.se184491.loadmaster_be.constant.optimization.OptimizationJobStatus;
import fu.se184491.loadmaster_be.dto.websocket.JobStatusNotification;
import fu.se184491.loadmaster_be.service.optimize.JobNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * WebSocket implementation of {@link JobNotificationService} (S3-10).
 *
 * <p>Publishes {@link JobStatusNotification} to:
 * {@code /topic/jobs/{jobUuid}}
 *
 * <p>The {@link SimpMessagingTemplate} is provided by the Spring WebSocket
 * auto-configuration when {@code spring-boot-starter-websocket} is on the classpath.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobNotificationServiceImpl implements JobNotificationService {

    /** Topic template — clients subscribe to /topic/jobs/{jobUuid} */
    static final String TOPIC_TEMPLATE = "/topic/jobs/";

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void notifyJobStatus(String jobUuid,
                                OptimizationJobStatus status,
                                Long planId,
                                String message) {
        JobStatusNotification notification = JobStatusNotification.builder()
                .jobUuid(jobUuid)
                .status(status)
                .planId(planId)
                .message(message)
                .build();

        String destination = TOPIC_TEMPLATE + jobUuid;
        messagingTemplate.convertAndSend(destination, notification);
        log.info("[ws] Sent {} to {}: planId={}", status, destination, planId);
    }
}
