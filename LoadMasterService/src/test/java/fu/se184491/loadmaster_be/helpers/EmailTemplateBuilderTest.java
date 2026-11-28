package fu.se184491.loadmaster_be.helpers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mọi chuỗi văn bản đưa vào mẫu email (tên công ty, mã giao dịch từ cổng thanh toán, tên gói...)
 * phải được thoát HTML, để người dùng không chèn được thẻ hay thuộc tính vào email gửi đi.
 */
class EmailTemplateBuilderTest {

    private static final String SCRIPT = "<script>alert(1)</script>";
    private static final String IMG = "\"><img src=x onerror=alert(1)>";

    @Test
    void accountProvisionEscapesRecipientAndItemValues() {
        String html = EmailTemplateBuilder.buildAccountProvisionEmailHtml(
                "Cty " + SCRIPT,
                "Hóa đơn Thanh toán Dịch vụ",
                "Service Payment Invoice",
                "Cảm ơn Quý khách.",
                "Thank you.",
                List.of(
                        new EmailTemplateBuilder.AccountProvisionItem("Mã giao dịch", "Transaction Code", IMG),
                        new EmailTemplateBuilder.AccountProvisionItem("Dịch vụ", "Service", "Gói dịch vụ A & B")
                ),
                null,
                List.of("Lưu ý"),
                List.of("Notice")
        );

        assertThat(html).doesNotContain("<script>").doesNotContain("<img");
        assertThat(html).contains("Cty &lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("&quot;&gt;&lt;img src=x onerror=alert(1)&gt;");
        assertThat(html).contains("Gói dịch vụ A &amp; B");
    }

    @Test
    void accountProvisionEscapesHeadingIntroNoticesAndLabels() {
        String html = EmailTemplateBuilder.buildAccountProvisionEmailHtml(
                "Cty",
                "Tiêu đề " + SCRIPT,
                "Heading " + SCRIPT,
                "Mở đầu " + SCRIPT,
                "Intro " + SCRIPT,
                List.of(new EmailTemplateBuilder.AccountProvisionItem("Nhãn " + SCRIPT, "Label " + SCRIPT, "x")),
                null,
                List.of("Lưu ý " + SCRIPT),
                List.of("Notice " + SCRIPT)
        );

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("Tiêu đề &lt;script&gt;", "Heading &lt;script&gt;",
                "Mở đầu &lt;script&gt;", "Intro &lt;script&gt;",
                "Nhãn &lt;script&gt;", "Label &lt;script&gt;",
                "Lưu ý &lt;script&gt;", "Notice &lt;script&gt;");
        // Tiêu đề thẻ <title> chỉ được thoát một lần.
        assertThat(html).contains("<title>Tiêu đề &lt;script&gt;alert(1)&lt;/script&gt; / Heading &lt;script&gt;alert(1)&lt;/script&gt;</title>");
        assertThat(html).doesNotContain("&amp;lt;");
    }

    @Test
    void accountProvisionEscapesLoginUrlInAttributeAndText() {
        String html = EmailTemplateBuilder.buildAccountProvisionEmailHtml(
                "Cty", "Tiêu đề", "Heading", "Mở đầu", "Intro",
                List.of(),
                "https://loadmaster.vn/dang-nhap?a=1&b=\"x\"",
                null,
                null
        );

        assertThat(html).doesNotContain("b=\"x\"");
        assertThat(html).contains("href=\"https://loadmaster.vn/dang-nhap?a=1&amp;b=&quot;x&quot;\"");
    }

    @Test
    void vietnameseTextAndPlainValuesStayReadable() {
        String html = EmailTemplateBuilder.buildAccountProvisionEmailHtml(
                "Công ty Vận tải Đông Á",
                "Thông tin tài khoản",
                "Account Information",
                "Kính chào Quý khách.",
                "Welcome.",
                List.of(
                        new EmailTemplateBuilder.AccountProvisionItem("Mật khẩu tạm thời", "Temporary Password", "TempPass@123"),
                        new EmailTemplateBuilder.AccountProvisionItem("Số tiền thanh toán", "Amount Paid", "1,500,000 đ")
                ),
                null,
                null,
                null
        );

        assertThat(html).contains("Công ty Vận tải Đông Á", "Thông tin tài khoản",
                "Kính chào Quý khách.", "TempPass@123", "1,500,000 đ");
    }

    @Test
    void blankItemValuesAreStillSkipped() {
        String html = EmailTemplateBuilder.buildAccountProvisionEmailHtml(
                "Cty", "Tiêu đề", "Heading", "Mở đầu", "Intro",
                List.of(
                        new EmailTemplateBuilder.AccountProvisionItem("Có", "Present", "giá trị"),
                        new EmailTemplateBuilder.AccountProvisionItem("Rỗng", "Blank", " "),
                        new EmailTemplateBuilder.AccountProvisionItem("Thiếu", "Missing", null)
                ),
                null, null, null
        );

        assertThat(html).contains("1. Có").doesNotContain("Rỗng").doesNotContain("Thiếu");
    }

    @Test
    void otpEscapesRecipientPurposeAndCode() {
        String html = EmailTemplateBuilder.buildOtpEmailHtml(
                "Khách " + SCRIPT, "<b>123456</b>", 3, "mục đích " + SCRIPT, "purpose " + SCRIPT);

        assertThat(html).doesNotContain("<script>").doesNotContain("<b>");
        assertThat(html).contains("Khách &lt;script&gt;", "&lt;b&gt;123456&lt;/b&gt;",
                "mục đích &lt;script&gt;", "purpose &lt;script&gt;");
    }

    @Test
    void wrapHtmlEscapesTitleButKeepsBodyHtml() {
        String html = EmailTemplateBuilder.wrapHtml("Tiêu đề " + SCRIPT, "<p>Nội dung</p>");

        assertThat(html).contains("<title>Tiêu đề &lt;script&gt;alert(1)&lt;/script&gt;</title>");
        assertThat(html).contains("<p>Nội dung</p>");
    }
}
