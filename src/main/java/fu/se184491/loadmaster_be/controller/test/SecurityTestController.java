package fu.se184491.loadmaster_be.controller.test;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
public class SecurityTestController {

    @GetMapping("/public")
    public String publicEndpoint() {
        return "public ok";
    }

    @GetMapping("/authenticated")
    public String authenticated(Authentication authentication) {
        return "hello " + authentication.getName();
    }

    @PreAuthorize("hasRole('DISPATCHER')")
    @GetMapping("/dispatcher")
    public String dispatcher(Authentication authentication) {
        return "dispatcher ok - " + authentication.getName();
    }
}