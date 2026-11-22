package fu.se184491.loadmaster_be.service.assistant.extract;

import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.dto.response.assistant.RequirementDraftResponse;
import fu.se184491.loadmaster_be.dto.response.assistant.RequirementDraftResponse.DraftPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kiểm hậu kỳ bản nháp yêu cầu giao: giá trị không dùng được thành null và vào missing, sai schema là 503. */
class RequirementDraftValidatorTest {

    /** 21/11/2026 09:15 giờ Việt Nam. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 9, 15);

    private static String output(String destination, String deadline, String priority, String count, String note,
                                 String customer, String packages, String missing) {
        return "{\"destinationText\":" + destination + ",\"deadline\":" + deadline + ",\"priority\":" + priority
                + ",\"expectedPackageCount\":" + count + ",\"note\":" + note + ",\"customerNameGuess\":" + customer
                + ",\"packages\":" + packages + ",\"missing\":" + missing + "}";
    }

    private static String full(String deadline, String priority, String count) {
        return output("\"Co.opmart Biên Hoà, 121 Phạm Văn Thuận\"", deadline, priority, count,
                "\"Gọi chị Lan trước khi tới.\"", "\"Nước Suối An Phú\"",
                "[{\"description\":\"thùng nước suối\",\"quantity\":120,\"lengthCm\":40,\"widthCm\":30,"
                        + "\"heightCm\":25,\"weightKg\":12.345}]", "[]");
    }

    private static void assertUnavailable(String text) {
        assertThatThrownBy(() -> RequirementDraftValidator.validate(text, NOW)).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
    }

    @Test
    void completeDraftKeepsEveryField() {
        RequirementDraftResponse draft = RequirementDraftValidator.validate(
                full("\"2026-11-24T15:00:00\"", "\"URGENT\"", "122"), NOW);

        assertThat(draft.destinationText()).isEqualTo("Co.opmart Biên Hoà, 121 Phạm Văn Thuận");
        assertThat(draft.deadline()).isEqualTo("2026-11-24T15:00:00");
        assertThat(draft.priority()).isEqualTo(DeliveryRequirementPriority.URGENT);
        assertThat(draft.expectedPackageCount()).isEqualTo(122);
        assertThat(draft.note()).isEqualTo("Gọi chị Lan trước khi tới.");
        assertThat(draft.customerNameGuess()).isEqualTo("Nước Suối An Phú");
        assertThat(draft.packages()).containsExactly(new DraftPackage("thùng nước suối", 120, 40.0, 30.0, 25.0, 12.35));
        assertThat(draft.missing()).isEmpty();
    }

    @Test
    void textWithoutDeadlineLeavesItNullAndMissing() {
        RequirementDraftResponse draft = RequirementDraftValidator.validate(full("null", "null", "null"), NOW);

        assertThat(draft.deadline()).isNull();
        assertThat(draft.priority()).isNull();
        assertThat(draft.expectedPackageCount()).isNull();
        assertThat(draft.missing()).containsExactly("deadline", "priority", "expectedPackageCount");
    }

    @Test
    void deadlineThatIsNotInTheFutureOrNotARealDateIsDropped() {
        for (String deadline : new String[] {"\"2026-11-21T09:15:00\"", "\"2026-11-20T15:00:00\"",
                "\"2020-01-01T00:00:00\"", "\"2026-02-30T10:00:00\"", "\"2026-11-25T24:00:00\"",
                "\"2026-11-25T15:00\"", "\"2026-11-25 15:00:00\"", "\"25/11/2026 15:00\"",
                "\"2026-11-25T15:00:00+07:00\"", "\"ngày mai\""}) {
            RequirementDraftResponse draft = RequirementDraftValidator.validate(full(deadline, "\"HIGH\"", "3"), NOW);
            assertThat(draft.deadline()).as(deadline).isNull();
            assertThat(draft.missing()).as(deadline).containsExactly("deadline");
        }
        assertThat(RequirementDraftValidator.validate(full("\"2026-11-21T09:15:01\"", "\"HIGH\"", "3"), NOW)
                .deadline()).isEqualTo("2026-11-21T09:15:01");
    }

    @Test
    void priorityOutsideTheEnumAndBadCountsAreDropped() {
        for (String priority : new String[] {"\"CRITICAL\"", "\"urgent\"", "\"\""}) {
            assertThat(RequirementDraftValidator.validate(full("null", priority, "5"), NOW).priority()).isNull();
        }
        for (String count : new String[] {"0", "-3", "2.5", "3000000000"}) {
            RequirementDraftResponse draft = RequirementDraftValidator.validate(full("null", "\"LOW\"", count), NOW);
            assertThat(draft.expectedPackageCount()).as(count).isNull();
            assertThat(draft.missing()).as(count).containsExactly("deadline", "expectedPackageCount");
        }
        assertThat(RequirementDraftValidator.validate(full("null", "\"LOW\"", "4.0"), NOW).expectedPackageCount())
                .isEqualTo(4);
    }

    @Test
    void missingIsRecomputedNotCopiedFromTheModel() {
        RequirementDraftResponse draft = RequirementDraftValidator.validate(output("null",
                "\"2026-12-01T08:00:00\"", "\"NORMAL\"", "10", "null", "null", "[]",
                "[\"note\", \"customer\", \"deadline\"]"), NOW);

        assertThat(draft.missing()).containsExactly("destinationText");
    }

    @Test
    void longTextIsCutAndBlankTextIsNull() {
        String note = "\"" + "a".repeat(600) + "\"";
        String destination = "\"" + "Kho  Tân\\nVạn ".repeat(40) + "\"";
        RequirementDraftResponse draft = RequirementDraftValidator.validate(output(destination, "null", "null",
                "null", note, "\"   \"", "[]", "[]"), NOW);

        assertThat(draft.note()).hasSize(500);
        assertThat(draft.destinationText()).doesNotContain("\n").doesNotContain("  ")
                .hasSizeLessThanOrEqualTo(255).startsWith("Kho Tân Vạn Kho Tân Vạn");
        assertThat(draft.customerNameGuess()).isNull();
    }

    @Test
    void packagesKeepPositiveNumbersOnlyAndDropBlankDescriptions() {
        String packages = "[{\"description\":\"pallet\",\"quantity\":null,\"lengthCm\":0,\"widthCm\":-1,"
                + "\"heightCm\":null,\"weightKg\":0.001},"
                + "{\"description\":\"  \",\"quantity\":3,\"lengthCm\":10,\"widthCm\":10,\"heightCm\":10,"
                + "\"weightKg\":1}]";
        RequirementDraftResponse draft = RequirementDraftValidator.validate(output("null", "null", "null", "null",
                "null", "null", packages, "[]"), NOW);

        assertThat(draft.packages()).containsExactly(new DraftPackage("pallet", null, null, null, null, null));
    }

    @Test
    void atMostTwentyPackageKindsAreKept() {
        String one = "{\"description\":\"thùng\",\"quantity\":1,\"lengthCm\":null,\"widthCm\":null,"
                + "\"heightCm\":null,\"weightKg\":null}";
        String packages = "[" + String.join(",", java.util.Collections.nCopies(25, one)) + "]";

        assertThat(RequirementDraftValidator.validate(output("null", "null", "null", "null", "null", "null",
                packages, "[]"), NOW).packages()).hasSize(20);
    }

    @Test
    void outputBreakingTheSchemaIsUnavailable() {
        assertUnavailable("Đây là bài thơ về biển.");
        assertUnavailable(null);
        assertUnavailable("{\"destinationText\":null}");
        // Thừa khoá
        assertUnavailable(full("null", "null", "null").replace("{\"destinationText\"",
                "{\"extra\":1,\"destinationText\""));
        // Sai kiểu
        assertUnavailable(full("null", "null", "\"122\""));
        assertUnavailable(full("20261124", "null", "null"));
        assertUnavailable(output("null", "null", "null", "null", "null", "null", "[]", "[1]"));
        assertUnavailable(output("null", "null", "null", "null", "null", "null",
                "[{\"description\":\"thùng\",\"quantity\":1}]", "[]"));
        // Chữ thừa sau JSON
        assertUnavailable(full("null", "null", "null") + " xong");
    }
}
