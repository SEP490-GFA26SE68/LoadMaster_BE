package fu.se184491.loadmaster_be.service.assistant.extract;

import fu.se184491.loadmaster_be.dto.response.assistant.TryExtractResponse;
import fu.se184491.loadmaster_be.dto.response.assistant.TryExtractResponse.Carton;
import fu.se184491.loadmaster_be.dto.response.assistant.TryExtractResponse.Truck;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kiểm hậu kỳ chữ của mô hình: đúng giới hạn của form "Thử với hàng của bạn", không kẹp số, không bịa số. */
class TryExtractValidatorTest {

    private static final String TRUCK = """
            {"lengthCm": 520, "widthCm": 200, "heightCm": 200, "payloadKg": 3500}""";

    private static String carton(Object length, Object width, Object height, Object weight, Object quantity, Object stop) {
        return """
                {"lengthCm": %s, "widthCm": %s, "heightCm": %s, "weightKg": %s, "quantity": %s, "stop": %s}"""
                .formatted(length, width, height, weight, quantity, stop);
    }

    private static String output(String truck, List<String> cartons, String assumptions, String missing) {
        return """
                {"truck": %s, "cartons": [%s], "assumptions": %s, "missing": %s}"""
                .formatted(truck, String.join(",", cartons), assumptions, missing);
    }

    private static TryExtractResponse validate(String json) {
        return TryExtractValidator.validate(json);
    }

    private static void assertUnavailable(String json) {
        assertThatThrownBy(() -> validate(json)).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
    }

    @Test
    void validOutputMapsEveryField() {
        TryExtractResponse result = validate(output(TRUCK,
                List.of(carton(60, 40, 40, 12, 80, 1), carton(80, 60, 50, 30.5, 20, 2)),
                "[\"Hiểu xe 3,5 tấn là tải trọng 3.500 kg.\"]", "[]"));

        assertThat(result.truck()).isEqualTo(new Truck(520.0, 200.0, 200.0, 3500.0));
        assertThat(result.cartons()).containsExactly(
                new Carton(60.0, 40.0, 40.0, 12.0, 80, 1),
                new Carton(80.0, 60.0, 50.0, 30.5, 20, 2));
        assertThat(result.assumptions()).containsExactly("Hiểu xe 3,5 tấn là tải trọng 3.500 kg.");
        assertThat(result.missing()).isEmpty();
    }

    @Test
    void unknownWeightStaysNullAndIsMissing() {
        TryExtractResponse result = validate(output(TRUCK, List.of(carton(50, 40, 30, null, 50, 1)), "[]",
                "[\"cartons[0].weightKg\"]"));

        assertThat(result.cartons().getFirst().weightKg()).isNull();
        assertThat(result.missing()).containsExactly("cartons[0].weightKg");
    }

    @Test
    void totalOverAThousandKeepsEveryQuantity() {
        TryExtractResponse result = validate(output(TRUCK,
                List.of(carton(40, 30, 30, 5, 700, 1), carton(50, 40, 40, 9, 500, 2)), "[]", "[]"));

        assertThat(result.cartons()).extracting(Carton::quantity).containsExactly(700, 500);
        assertThat(result.missing()).containsExactly("totalQuantity");
    }

    @Test
    void moreThanFourKindsKeepsTheFirstFour() {
        List<String> six = List.of(carton(30, 30, 30, 3, 10, 1), carton(40, 30, 30, 4, 12, 1),
                carton(50, 40, 30, 6, 14, 1), carton(60, 40, 40, 9, 16, 1), carton(70, 50, 40, 12, 18, 1),
                carton(80, 60, 50, 15, 20, 1));

        TryExtractResponse result = validate(output("null", six, "[]", "[]"));

        assertThat(result.cartons()).extracting(Carton::quantity).containsExactly(10, 12, 14, 16);
        assertThat(result.missing()).containsExactly("cartonKinds");
    }

    @Test
    void cartonKindsReportedByTheModelIsKept() {
        TryExtractResponse result = validate(output("null", List.of(carton(30, 30, 30, 3, 10, 1)), "[]",
                "[\"cartonKinds\"]"));

        assertThat(result.missing()).containsExactly("cartonKinds");
    }

    @Test
    void outOfRangeValuesBecomeNullWithoutClamping() {
        String truck = """
                {"lengthCm": 2000, "widthCm": 99.99, "heightCm": 300, "payloadKg": 30001}""";

        TryExtractResponse result = validate(output(truck,
                List.of(carton(4, 301, 5, 0.09, 1001, 5), carton(300, 5, 120, 1000, 2.5, 0)), "[]", "[]"));

        assertThat(result.truck()).isEqualTo(new Truck(null, null, 300.0, null));
        assertThat(result.cartons()).containsExactly(
                new Carton(null, null, 5.0, null, null, null),
                new Carton(300.0, 5.0, 120.0, 1000.0, null, null));
        assertThat(result.missing()).containsExactly(
                "truck.lengthCm", "truck.widthCm", "truck.payloadKg",
                "cartons[0].lengthCm", "cartons[0].widthCm", "cartons[0].weightKg", "cartons[0].quantity",
                "cartons[0].stop", "cartons[1].quantity", "cartons[1].stop");
    }

    @Test
    void integerFieldsAcceptWholeDecimals() {
        TryExtractResponse result = validate(output("null", List.of(carton(60, 40, 40, 12, 80.0, 2.0)), "[]", "[]"));

        assertThat(result.cartons().getFirst().quantity()).isEqualTo(80);
        assertThat(result.cartons().getFirst().stop()).isEqualTo(2);
    }

    @Test
    void numbersAreRoundedToTwoDecimals() {
        TryExtractResponse result = validate(output("null", List.of(carton(45.555, 40.004, 40, 0.104, 1, 1)),
                "[]", "[]"));

        assertThat(result.cartons().getFirst()).isEqualTo(new Carton(45.56, 40.0, 40.0, 0.1, 1, 1));
    }

    @Test
    void noCartonsIsMissingCartons() {
        TryExtractResponse result = validate(output(TRUCK, List.of(), "[]", "[]"));

        assertThat(result.cartons()).isEmpty();
        assertThat(result.missing()).containsExactly("cartons");
    }

    @Test
    void absentTruckStaysNullWithoutTruckPaths() {
        TryExtractResponse result = validate(output("null", List.of(carton(60, 40, 40, 12, 80, 1)), "[]",
                "[\"truck.lengthCm\"]"));

        assertThat(result.truck()).isNull();
        assertThat(result.missing()).isEmpty();
    }

    @Test
    void missingIsRecomputedDedupedAndOrdered() {
        String truck = """
                {"lengthCm": 520, "widthCm": null, "heightCm": 200, "payloadKg": null}""";
        List<String> cartons = List.of(carton(60, 40, 40, null, 700, null), carton(50, 40, 40, 9, 400, 1),
                carton(30, 30, 30, 2, 1, 1), carton(30, 30, 30, 2, 1, 1), carton(30, 30, 30, 2, 1, 1));

        TryExtractResponse result = validate(output(truck, cartons, "[]",
                "[\"totalQuantity\", \"cartons[0].stop\", \"cartons[0].stop\", \"truck.lengthCm\", \"bogus\"]"));

        assertThat(result.missing()).containsExactly("truck.widthCm", "truck.payloadKg", "cartons[0].weightKg",
                "cartons[0].stop", "cartonKinds", "totalQuantity");
    }

    @Test
    void assumptionsAreTrimmedCappedAndShortened() {
        String longSentence = "ý ".repeat(200).strip();
        TryExtractResponse result = validate(output("null", List.of(carton(60, 40, 40, 12, 80, 1)),
                "[\"  Một\\n  hai  \", \"\", \"   \", \"" + longSentence + "\", \"c\", \"d\", \"e\", \"f\"]", "[]"));

        assertThat(result.assumptions()).hasSize(5);
        assertThat(result.assumptions().getFirst()).isEqualTo("Một hai");
        String shortened = result.assumptions().get(1);
        assertThat(shortened.codePointCount(0, shortened.length())).isEqualTo(TryExtractValidator.MAX_ASSUMPTION_CHARS);
        assertThat(shortened).endsWith("…");
        assertThat(result.assumptions().subList(2, 5)).containsExactly("c", "d", "e");
    }

    @Test
    void textThatIsNotJsonIsUnavailable() {
        assertUnavailable("Đây là kết quả: {\"truck\": null}");
        assertUnavailable("");
        assertUnavailable("[]");
    }

    @Test
    void outputThatBreaksTheSchemaIsUnavailable() {
        // thiếu khoá
        assertUnavailable("{\"truck\": null, \"cartons\": [], \"assumptions\": []}");
        // khoá thừa
        assertUnavailable(output("null", List.of(), "[]", "[]").replace("{\"truck\"", "{\"poem\": \"x\", \"truck\""));
        // số viết thành chữ
        assertUnavailable(output("null", List.of(carton("\"60\"", 40, 40, 12, 80, 1)), "[]", "[]"));
        // kiện thiếu trường
        assertUnavailable(output("null", List.of("{\"lengthCm\": 60}"), "[]", "[]"));
        // xe không phải đối tượng
        assertUnavailable(output("[]", List.of(), "[]", "[]"));
        // giả định không phải chữ
        assertUnavailable(output("null", List.of(), "[1]", "[]"));
        assertUnavailable(output("null", List.of(), "[]", "\"cartons\""));
    }
}
