package fu.se184491.loadmaster_be.helpers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CompanyCodeGeneratorTest {

    private final CompanyCodeGenerator generator = new CompanyCodeGenerator();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "'Công ty Cổ phần Vận tải Tân Cảng Sài Gòn', TCSG_LOG",
            "'Công ty TNHH Vận tải Anpha Express', ANPHA_EXP_LOG",
            "'Công ty TNHH MTV Thịnh Vượng', THINH_VUONG_LOG",
            "'Doanh nghiệp Tư nhân Hoàng Long', HOANG_LONG_LOG",
            "'Công ty Cổ phần Tập đoàn Mai Linh', MAI_LINH_LOG",
            "'   ', COMP_LOG"
    })
    @DisplayName("generateBaseCode sinh mã viết tắt chuẩn hóa, loại bỏ tiền tố pháp lý và có hậu tố LOG")
    void generateBaseCode_validatesOutput(String input, String expected) {
        String code = generator.generateBaseCode(input);
        assertNotNull(code);
        assertTrue(code.matches("^[A-Z0-9_]{3,50}$"), "Code must match regex: " + code);
        assertEquals(expected, code);
    }

    @Test
    @DisplayName("generateUniqueCode tự động thêm số thứ tự nếu mã đã bị trùng trong CSDL")
    void generateUniqueCode_resolvesCollision() {
        Set<String> existing = new HashSet<>();
        existing.add("TCSG_LOG");
        existing.add("TCSG_LOG_01");

        String code = generator.generateUniqueCode("Công ty Cổ phần Vận tải Tân Cảng Sài Gòn", existing::contains);

        assertEquals("TCSG_LOG_02", code);
    }
}
