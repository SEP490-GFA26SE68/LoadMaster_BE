package fu.se184491.loadmaster_be.client;

import fu.se184491.loadmaster_be.helpers.LogMasks;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BrevoClient {

    private final RestClient brevoRestClient;

    private final String apiKey;
    private final String senderEmail;
    private final String senderName;

    public BrevoClient(
            @Qualifier("brevoRestClient") RestClient brevoRestClient,
            @Value("${app.brevo.api-key}") String apiKey,
            @Value("${app.brevo.sender-email}") String senderEmail,
            @Value("${app.brevo.sender-name}") String senderName
    ) {
        this.brevoRestClient = brevoRestClient;
        this.apiKey = apiKey;
        this.senderEmail = senderEmail;
        this.senderName = senderName;
    }

    public void sendPasswordResetOtp(
            String recipientEmail,
            String otp
    ) {
        String htmlContent = fu.se184491.loadmaster_be.helpers.EmailTemplateBuilder.buildOtpEmailHtml(
                recipientEmail,
                otp,
                3,
                "đặt lại mật khẩu",
                "password reset"
        );

        Map<String, Object> body = Map.of(
                "sender", Map.of(
                        "name", senderName != null ? senderName : "LoadMaster",
                        "email", senderEmail != null ? senderEmail : "no-reply@loadmaster.vn"
                ),
                "to", List.of(
                        Map.of("email", recipientEmail)
                ),
                "subject", "[LoadMaster] Mã xác thực đặt lại mật khẩu / Password Reset Verification Code",
                "htmlContent", htmlContent
        );

        try {
            brevoRestClient
                    .post()
                    .uri("/v3/smtp/email")
                    .header("api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Không gửi được OTP đặt lại mật khẩu tới {}: {}", LogMasks.email(recipientEmail), e.getMessage());
        }
    }

    public void sendEmail(String recipientEmail, String subject, String content) {
        String htmlContent = (content != null && content.trim().startsWith("<!DOCTYPE html>"))
                ? content
                : fu.se184491.loadmaster_be.helpers.EmailTemplateBuilder.wrapHtml(
                        subject != null ? subject : "Thông báo / Notification",
                        "<div style=\"white-space: pre-wrap; font-size: 14px; line-height: 1.6; color: #334155;\">"
                                + (content != null ? content : "")
                                + "</div>"
                );

        Map<String, Object> body = Map.of(
                "sender", Map.of(
                        "name", senderName != null ? senderName : "LoadMaster",
                        "email", senderEmail != null ? senderEmail : "no-reply@loadmaster.vn"
                ),
                "to", List.of(
                        Map.of("email", recipientEmail)
                ),
                "subject", subject != null ? subject : "Thông báo từ LoadMaster / Notification from LoadMaster",
                "htmlContent", htmlContent
        );

        try {
            brevoRestClient
                    .post()
                    .uri("/v3/smtp/email")
                    .header("api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // Không làm hỏng việc gọi (máy dev thường không có khoá Brevo), nhưng không im lặng
            log.warn("Không gửi được email tới {}: {}", LogMasks.email(recipientEmail), e.getMessage());
        }
    }

    public void sendEmailWithAttachment(String recipientEmail, String subject, String content, String attachmentName, byte[] attachmentBytes) {
        String htmlContent = (content != null && content.trim().startsWith("<!DOCTYPE html>"))
                ? content
                : fu.se184491.loadmaster_be.helpers.EmailTemplateBuilder.wrapHtml(
                        subject != null ? subject : "Thông báo / Notification",
                        "<div style=\"white-space: pre-wrap; font-size: 14px; line-height: 1.6; color: #334155;\">"
                                + (content != null ? content : "")
                                + "</div>"
                );

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("sender", java.util.Map.of(
                "name", senderName != null ? senderName : "LoadMaster",
                "email", senderEmail != null ? senderEmail : "no-reply@loadmaster.vn"
        ));
        body.put("to", List.of(java.util.Map.of("email", recipientEmail)));
        body.put("subject", subject != null ? subject : "Thông báo từ LoadMaster / Notification from LoadMaster");
        body.put("htmlContent", htmlContent);
        body.put("htmlContent", htmlContent);

        if (attachmentBytes != null && attachmentBytes.length > 0) {
            String base64Content = java.util.Base64.getEncoder().encodeToString(attachmentBytes);
            body.put("attachment", List.of(
                    java.util.Map.of(
                            "name", attachmentName != null ? attachmentName : "invoice.pdf",
                            "content", base64Content
                    )
            ));
        }

        try {
            brevoRestClient
                    .post()
                    .uri("/v3/smtp/email")
                    .header("api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // Không làm hỏng việc gọi (máy dev thường không có khoá Brevo), nhưng không im lặng
            log.warn("Không gửi được email tới {}: {}", LogMasks.email(recipientEmail), e.getMessage());
        }
    }
}