package fu.se184491.loadmaster_be.helpers;

import java.util.List;

/**
 * Tiện ích xây dựng mẫu HTML email song ngữ (Việt - Anh xen kẽ, ưu tiên Tiếng Việt trước).
 * Tuân thủ quy định:
 * - Thống nhất giao diện nhận diện thương hiệu LoadMaster.
 * - 100% song ngữ Tiếng Việt và Tiếng Anh xen kẽ.
 * - Không sử dụng bất kỳ emoji/icon nào.
 * - Dùng số thứ tự (1, 2, 3...) cho danh mục thông tin và dấu gạch đầu dòng (-) cho lưu ý/điều khoản.
 */
public final class EmailTemplateBuilder {

    private EmailTemplateBuilder() {
    }

    /**
     * Dựng mẫu email gửi mã xác thực OTP song ngữ.
     */
    public static String buildOtpEmailHtml(
            String recipientName,
            String otp,
            int expireMinutes,
            String purposeVi,
            String purposeEn
    ) {
        String safeName = (recipientName != null && !recipientName.isBlank()) ? recipientName.trim() : "Quý khách / Customer";
        String safePurposeVi = (purposeVi != null && !purposeVi.isBlank()) ? purposeVi.trim() : "xác thực tài khoản";
        String safePurposeEn = (purposeEn != null && !purposeEn.isBlank()) ? purposeEn.trim() : "account verification";

        String contentHtml = """
                <h2 style="margin: 0 0 16px 0; font-size: 20px; color: #0f172a; font-weight: 700;">
                    Mã xác thực OTP / Verification Code
                </h2>
                
                <p style="margin: 0 0 12px 0; font-size: 15px; line-height: 1.6; color: #334155;">
                    Kính gửi <strong>%s</strong>,
                </p>
                <p style="margin: 0 0 20px 0; font-size: 14px; line-height: 1.6; color: #475569;">
                    Mã xác thực cho %s của bạn là:
                    <br />
                    <span style="color: #64748b; font-size: 13px;">Your verification code for %s is:</span>
                </p>
                
                <!-- OTP Box -->
                <div style="text-align: center; margin: 24px 0; padding: 20px; background-color: #f8fafc; border: 1px solid #cbd5e1; border-radius: 8px;">
                    <div style="font-size: 12px; text-transform: uppercase; letter-spacing: 1.5px; color: #64748b; margin-bottom: 8px;">
                        MÃ XÁC THỰC / VERIFICATION CODE
                    </div>
                    <div style="font-size: 34px; font-weight: 700; letter-spacing: 8px; color: #0284c7; font-family: 'Courier New', Courier, monospace;">
                        %s
                    </div>
                </div>
                
                <p style="margin: 0 0 20px 0; font-size: 14px; line-height: 1.6; color: #334155;">
                    Mã này có hiệu lực trong vòng <strong>%d phút</strong>.
                    <br />
                    <span style="color: #64748b; font-size: 13px;">This code will expire in %d minutes.</span>
                </p>
                
                <!-- Security Notices -->
                <div style="background-color: #f1f5f9; border-left: 4px solid #0284c7; padding: 14px 16px; border-radius: 4px; margin-top: 24px;">
                    <div style="font-size: 13px; font-weight: 700; color: #0f172a; margin-bottom: 8px;">
                        LƯU Ý BẢO MẬT / SECURITY NOTICE:
                    </div>
                    <ul style="margin: 0; padding-left: 18px; font-size: 13px; line-height: 1.6; color: #475569; list-style-type: disc;">
                        <li style="margin-bottom: 6px;">
                            Tuyệt đối không chia sẻ mã xác thực này cho bất kỳ ai.
                            <br />
                            <span style="color: #64748b;">Never share this verification code with anyone.</span>
                        </li>
                        <li style="margin-bottom: 6px;">
                            LoadMaster không bao giờ yêu cầu cung cấp OTP qua điện thoại hoặc tin nhắn.
                            <br />
                            <span style="color: #64748b;">LoadMaster will never request your OTP via phone or message.</span>
                        </li>
                        <li>
                            Nếu bạn không yêu cầu mã này, vui lòng bỏ qua email hoặc thông báo cho bộ phận hỗ trợ.
                            <br />
                            <span style="color: #64748b;">If you did not request this code, please ignore this email or contact support.</span>
                        </li>
                    </ul>
                </div>
                """.formatted(
                safeName,
                safePurposeVi, safePurposeEn,
                otp,
                expireMinutes, expireMinutes
        );

        return wrapHtml("Mã xác thực OTP / Verification Code", contentHtml);
    }

    /**
     * Thông tin chi tiết để tạo email bàn giao tài khoản.
     */
    public record AccountProvisionItem(String labelVi, String labelEn, String value) {
    }

    /**
     * Dựng mẫu email bàn giao tài khoản (Admin công ty, nhân viên, dùng thử).
     */
    public static String buildAccountProvisionEmailHtml(
            String recipientName,
            String headingVi,
            String headingEn,
            String introVi,
            String introEn,
            List<AccountProvisionItem> items,
            String loginUrl,
            List<String> noticesVi,
            List<String> noticesEn
    ) {
        String safeName = (recipientName != null && !recipientName.isBlank()) ? recipientName.trim() : "Quý khách / Customer";

        StringBuilder itemsHtml = new StringBuilder();
        int counter = 1;
        for (AccountProvisionItem item : items) {
            if (item.value() != null && !item.value().isBlank()) {
                itemsHtml.append("""
                        <li style="margin-bottom: 10px; font-size: 14px; color: #334155; line-height: 1.5;">
                            <strong>%d. %s / <span style="color: #64748b; font-weight: normal;">%s</span>:</strong>
                            <span style="color: #0f172a; font-weight: 600;">%s</span>
                        </li>
                        """.formatted(counter++, item.labelVi(), item.labelEn(), item.value()));
            }
        }

        StringBuilder noticesHtml = new StringBuilder();
        if (noticesVi != null && !noticesVi.isEmpty()) {
            noticesHtml.append("""
                    <div style="background-color: #f8fafc; border-left: 4px solid #0284c7; padding: 14px 16px; border-radius: 4px; margin: 24px 0;">
                        <div style="font-size: 13px; font-weight: 700; color: #0f172a; margin-bottom: 8px;">
                            LƯU Ý QUAN TRỌNG / IMPORTANT NOTICES:
                        </div>
                        <ul style="margin: 0; padding-left: 18px; font-size: 13px; line-height: 1.6; color: #475569; list-style-type: disc;">
                    """);
            for (int i = 0; i < noticesVi.size(); i++) {
                String vi = noticesVi.get(i);
                String en = (noticesEn != null && i < noticesEn.size()) ? noticesEn.get(i) : null;
                noticesHtml.append("<li style=\"margin-bottom: 6px;\">").append(vi);
                if (en != null && !en.isBlank()) {
                    noticesHtml.append("<br /><span style=\"color: #64748b;\">").append(en).append("</span>");
                }
                noticesHtml.append("</li>");
            }
            noticesHtml.append("</ul></div>");
        }

        String buttonHtml = "";
        if (loginUrl != null && !loginUrl.isBlank()) {
            buttonHtml = """
                    <div style="margin: 28px 0; text-align: center;">
                        <a href="%s" style="background-color: #0284c7; color: #ffffff; padding: 12px 28px; text-decoration: none; border-radius: 6px; font-size: 14px; font-weight: 600; display: inline-block;">
                            Đăng nhập vào hệ thống / Log In to System
                        </a>
                        <div style="margin-top: 10px; font-size: 12px; color: #64748b;">
                            Đường dẫn truy cập: <a href="%s" style="color: #0284c7;">%s</a>
                        </div>
                    </div>
                    """.formatted(loginUrl, loginUrl, loginUrl);
        }

        String contentHtml = """
                <h2 style="margin: 0 0 16px 0; font-size: 20px; color: #0f172a; font-weight: 700;">
                    %s
                    <br />
                    <span style="font-size: 14px; color: #64748b; font-weight: normal;">%s</span>
                </h2>
                
                <p style="margin: 0 0 12px 0; font-size: 15px; line-height: 1.6; color: #334155;">
                    Kính gửi <strong>%s</strong>,
                </p>
                <p style="margin: 0 0 20px 0; font-size: 14px; line-height: 1.6; color: #475569;">
                    %s
                    <br />
                    <span style="color: #64748b; font-size: 13px;">%s</span>
                </p>
                
                <div style="background-color: #ffffff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 18px 20px; margin-bottom: 20px;">
                    <div style="font-size: 13px; font-weight: 700; color: #0f172a; margin-bottom: 12px; text-transform: uppercase; letter-spacing: 0.5px;">
                        DANH SÁCH THÔNG TIN / DETAILS:
                    </div>
                    <ul style="list-style-type: none; padding-left: 0; margin: 0;">
                        %s
                    </ul>
                </div>
                
                %s
                %s
                """.formatted(
                headingVi, headingEn,
                safeName,
                introVi, introEn,
                itemsHtml.toString(),
                buttonHtml,
                noticesHtml.toString()
        );

        return wrapHtml(headingVi + " / " + headingEn, contentHtml);
    }

    /**
     * Bọc nội dung trong khung giao diện email chuẩn của LoadMaster.
     */
    public static String wrapHtml(String title, String bodyHtml) {
        return """
                <!DOCTYPE html>
                <html lang="vi">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s</title>
                </head>
                <body style="margin: 0; padding: 0; background-color: #f1f5f9; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #1e293b;">
                    <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="background-color: #f1f5f9; padding: 36px 16px;">
                        <tr>
                            <td align="center">
                                <table width="100%%" cellpadding="0" cellspacing="0" role="presentation" style="max-width: 600px; background-color: #ffffff; border-radius: 12px; overflow: hidden; border: 1px solid #e2e8f0; box-shadow: 0 4px 16px rgba(15, 23, 42, 0.06);">
                                    <!-- Header -->
                                    <tr>
                                        <td style="background-color: #0f172a; padding: 26px 32px; text-align: center;">
                                            <div style="font-size: 26px; font-weight: 800; letter-spacing: 1px; color: #ffffff;">
                                                LoadMaster
                                            </div>
                                            <div style="margin-top: 6px; font-size: 13px; color: #94a3b8; line-height: 1.4;">
                                                Nền tảng tối ưu hóa vận tải và logistics
                                                <br />
                                                <span style="font-size: 12px; color: #64748b;">Logistics Optimization Platform</span>
                                            </div>
                                        </td>
                                    </tr>
                                    
                                    <!-- Content Body -->
                                    <tr>
                                        <td style="padding: 32px 32px 24px 32px;">
                                            %s
                                        </td>
                                    </tr>
                                    
                                    <!-- Footer -->
                                    <tr>
                                        <td style="background-color: #f8fafc; border-top: 1px solid #e2e8f0; padding: 22px 32px; text-align: center; font-size: 12px; line-height: 1.6; color: #64748b;">
                                            <strong style="color: #334155;">LoadMaster Platform</strong>
                                            <br />
                                            Email tự động, vui lòng không phản hồi thư này.
                                            <br />
                                            <span style="color: #94a3b8;">This is an automated notification, please do not reply directly.</span>
                                            <br />
                                            <span style="color: #94a3b8; margin-top: 4px; display: inline-block;">© 2026 LoadMaster. Bảo lưu mọi quyền. / All rights reserved.</span>
                                        </td>
                                    </tr>
                                </table>
                            </td>
                        </tr>
                    </table>
                </body>
                </html>
                """.formatted(title, bodyHtml);
    }
}
