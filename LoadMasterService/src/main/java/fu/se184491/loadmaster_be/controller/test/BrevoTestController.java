package fu.se184491.loadmaster_be.controller.test;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test/brevo")
@RequiredArgsConstructor
public class BrevoTestController {

    private final BrevoClient brevoClient;

    @PostMapping
    public ApiResponse<Void> sendTestMail(
            @RequestParam String email
    ) {
        brevoClient.sendPasswordResetOtp(
                email,
                "123456"
        );

        return ApiResponse.success(
                "Test email sent"
        );
    }
}