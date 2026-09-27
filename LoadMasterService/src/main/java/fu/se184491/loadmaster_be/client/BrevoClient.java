package fu.se184491.loadmaster_be.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

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

        String htmlContent = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>LoadMaster Password Reset</title>
                </head>
                
                <body style="
                    margin:0;
                    padding:0;
                    background:#f4f7fb;
                    font-family:Arial, Helvetica, sans-serif;
                    color:#1f2937;
                ">
                
                <table width="100%%" cellpadding="0" cellspacing="0" role="presentation"
                       style="background:#f4f7fb; padding:40px 16px;">
                    <tr>
                        <td align="center">
                
                            <table width="100%%" cellpadding="0" cellspacing="0" role="presentation"
                                   style="
                                       max-width:560px;
                                       background:#ffffff;
                                       border-radius:16px;
                                       overflow:hidden;
                                       box-shadow:0 8px 24px rgba(15,23,42,0.08);
                                   ">
                
                                <!-- Header -->
                                <tr>
                                    <td align="center"
                                        style="
                                            background:#0f172a;
                                            padding:28px 24px;
                                        ">
                                        <div style="
                                            font-size:28px;
                                            font-weight:700;
                                            letter-spacing:1px;
                                            color:#ffffff;
                                        ">
                                            LoadMaster
                                        </div>
                
                                        <div style="
                                            margin-top:6px;
                                            font-size:13px;
                                            color:#94a3b8;
                                        ">
                                            Logistics Optimization Platform
                                        </div>
                                    </td>
                                </tr>
                
                                <!-- Content -->
                                <tr>
                                    <td style="padding:36px 36px 20px 36px;">
                
                                        <h2 style="
                                            margin:0 0 16px 0;
                                            font-size:22px;
                                            color:#0f172a;
                                        ">
                                            Reset your password
                                        </h2>
                
                                        <p style="
                                            margin:0 0 24px 0;
                                            font-size:15px;
                                            line-height:1.6;
                                            color:#475569;
                                        ">
                                            We received a request to reset your LoadMaster password.
                                            Use the verification code below to continue.
                                        </p>
                
                                        <!-- OTP -->
                                        <div style="
                                            text-align:center;
                                            margin:28px 0;
                                            padding:22px;
                                            background:#f8fafc;
                                            border:1px solid #e2e8f0;
                                            border-radius:12px;
                                        ">
                
                                            <div style="
                                                font-size:12px;
                                                text-transform:uppercase;
                                                letter-spacing:1.5px;
                                                color:#64748b;
                                                margin-bottom:10px;
                                            ">
                                                Verification code
                                            </div>
                
                                            <div style="
                                                font-size:36px;
                                                font-weight:700;
                                                letter-spacing:8px;
                                                color:#0f172a;
                                            ">
                                                %s
                                            </div>
                
                                        </div>
                
                                        <p style="
                                            margin:0 0 8px 0;
                                            font-size:14px;
                                            color:#475569;
                                        ">
                                            This code expires in
                                            <strong style="color:#dc2626;">3 minutes</strong>.
                                        </p>
                
                                        <p style="
                                            margin:0;
                                            font-size:14px;
                                            line-height:1.6;
                                            color:#64748b;
                                        ">
                                            If you didn't request a password reset, you can safely ignore this email.
                                            Your password will remain unchanged.
                                        </p>
                
                                    </td>
                                </tr>
                
                                <!-- Security notice -->
                                <tr>
                                    <td style="padding:0 36px 32px 36px;">
                                        <div style="
                                            background:#fff7ed;
                                            border-left:4px solid #f97316;
                                            padding:14px 16px;
                                            border-radius:6px;
                                            font-size:13px;
                                            line-height:1.5;
                                            color:#9a3412;
                                        ">
                                            Never share this verification code with anyone.
                                            LoadMaster will never ask for your OTP by phone or message.
                                        </div>
                                    </td>
                                </tr>
                
                                <!-- Footer -->
                                <tr>
                                    <td align="center"
                                        style="
                                            background:#f8fafc;
                                            border-top:1px solid #e2e8f0;
                                            padding:22px;
                                            font-size:12px;
                                            line-height:1.6;
                                            color:#94a3b8;
                                        ">
                
                                        <strong style="color:#64748b;">
                                            LoadMaster
                                        </strong>
                
                                        <br>
                
                                        Automated security email — please do not reply.
                
                                        <br>
                
                                        © 2026 LoadMaster. All rights reserved.
                
                                    </td>
                                </tr>
                
                            </table>
                
                        </td>
                    </tr>
                </table>
                
                </body>
                </html>
                """.formatted(otp);
        Map<String, Object> body = Map.of(
                "sender", Map.of(
                        "name", senderName,
                        "email", senderEmail
                ),
                "to", List.of(
                        Map.of("email", recipientEmail)
                ),
                "subject", "Your LoadMaster password reset code",
                "htmlContent", htmlContent
        );


        brevoRestClient
                .post()
                .uri("/v3/smtp/email")
                .header("api-key", apiKey)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}