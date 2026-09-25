package fu.se184491.loadmaster_be.controller.test;

import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class ServiceTokenTestController {

    private final ServiceTokenProvider serviceTokenProvider;

    @GetMapping("/service-token")
    public String getServiceToken() {
        return serviceTokenProvider.getOptimizationServiceToken();
    }
}