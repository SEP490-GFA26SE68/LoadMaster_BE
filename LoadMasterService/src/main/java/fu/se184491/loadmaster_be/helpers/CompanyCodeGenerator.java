package fu.se184491.loadmaster_be.helpers;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Component
public class CompanyCodeGenerator {

    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final String DEFAULT_FALLBACK_CODE = "COMP_LOG";

    private static final Set<String> LEGAL_KEYWORDS = Set.of(
            "CONG", "TY", "CO", "PHAN", "CP", "TNHH", "MTV", "DOANH", "NGHIEP", "TU", "NHAN",
            "DNTN", "TAP", "DOAN", "CHI", "NHANH", "HOLDINGS", "GROUP"
    );

    private static final Set<String> SECONDARY_KEYWORDS = Set.of(
            "VAN", "TAI", "LOGISTICS", "VANCHUYEN", "LOG"
    );

    /**
     * Sinh mã viết tắt doanh nghiệp từ tên công ty.
     * Quy tắc:
     * - Bỏ dấu tiếng Việt, chuyển chữ hoa.
     * - Loại bỏ tiền tố pháp lý phổ biến (Công ty, Cổ phần, TNHH, MTV, Tập đoàn...).
     * - Nếu tên gồm từ 3 từ trở lên: lấy chữ cái đầu ghép lại + hậu tố _LOG (VD: Tân Cảng Sài Gòn -> TCSG_LOG).
     * - Nếu tên gồm 1-2 từ: giữ từ có nghĩa + hậu tố _LOG (VD: Thịnh Vượng -> THINH_VUONG_LOG).
     * - Đảm bảo thỏa mãn độ dài 3-50 ký tự [A-Z0-9_].
     */
    public String generateBaseCode(String companyName) {
        if (companyName == null || companyName.trim().isEmpty()) {
            return DEFAULT_FALLBACK_CODE;
        }

        // 1. Chuẩn hóa tiếng Việt sang không dấu, chữ hoa
        String normalized = Normalizer.normalize(companyName.trim(), Normalizer.Form.NFD);
        normalized = DIACRITICS_PATTERN.matcher(normalized).replaceAll("");
        normalized = normalized.replace("đ", "d").replace("Đ", "D");
        normalized = normalized.toUpperCase().replaceAll("[^A-Z0-9]+", " ").trim();

        if (normalized.isEmpty()) {
            return DEFAULT_FALLBACK_CODE;
        }

        // 2. Tách từ và loại bỏ các từ thuộc nhóm pháp lý
        String[] tokens = normalized.split("\\s+");
        List<String> nonLegalTokens = new ArrayList<>();
        for (String token : tokens) {
            if (!LEGAL_KEYWORDS.contains(token)) {
                nonLegalTokens.add(token);
            }
        }

        if (nonLegalTokens.isEmpty()) {
            nonLegalTokens = Arrays.asList(tokens);
        }

        // 3. Nếu còn từ 3 từ trở lên và có chứa từ vận tải / logistics, loại bớt để lấy tên thương hiệu lõi
        List<String> coreTokens = new ArrayList<>();
        for (String token : nonLegalTokens) {
            if (!SECONDARY_KEYWORDS.contains(token)) {
                coreTokens.add(token);
            }
        }

        if (coreTokens.size() < 2) {
            coreTokens = nonLegalTokens;
        }

        String baseCode;
        if (coreTokens.size() >= 3) {
            StringBuilder acronym = new StringBuilder();
            for (String token : coreTokens) {
                if (!token.isEmpty()) {
                    acronym.append(token.charAt(0));
                }
            }
            baseCode = acronym + "_LOG";
        } else if (coreTokens.size() == 2) {
            String w1 = coreTokens.get(0);
            String w2 = coreTokens.get(1);
            if (w2.length() > 3 && (w2.startsWith("EXP") || w2.equals("EXPRESS"))) {
                baseCode = w1 + "_EXP_LOG";
            } else {
                baseCode = w1 + "_" + w2 + "_LOG";
            }
        } else {
            baseCode = coreTokens.get(0) + "_LOG";
        }

        // Đảm bảo không vượt quá 45 ký tự để còn dư chỗ cho suffix nếu trùng
        if (baseCode.length() > 45) {
            baseCode = baseCode.substring(0, 41) + "_LOG";
        }

        return baseCode;
    }

    /**
     * Sinh mã công ty duy nhất, kiểm tra trùng lặp thông qua existChecker và tự sinh hậu tố nếu đã tồn tại.
     */
    public String generateUniqueCode(String companyName, Predicate<String> existsChecker) {
        String baseCode = generateBaseCode(companyName);
        if (!existsChecker.test(baseCode)) {
            return baseCode;
        }

        // Bị trùng -> thêm hậu tố _01, _02, ...
        int counter = 1;
        while (counter < 1000) {
            String candidate = baseCode + String.format("_%02d", counter);
            if (candidate.length() > 50) {
                candidate = baseCode.substring(0, 50 - 4) + String.format("_%02d", counter);
            }
            if (!existsChecker.test(candidate)) {
                return candidate;
            }
            counter++;
        }

        return baseCode + "_" + System.currentTimeMillis() % 10000;
    }
}
