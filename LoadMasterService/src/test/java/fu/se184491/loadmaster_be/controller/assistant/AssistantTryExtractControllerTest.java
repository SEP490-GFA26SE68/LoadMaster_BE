package fu.se184491.loadmaster_be.controller.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Đọc mô tả hàng cho form "Thử với hàng của bạn": khách không token gọi được, sai dữ liệu là 400, mô hình hỏng là 503. */
class AssistantTryExtractControllerTest extends AssistantWebTestBase {

    private static final String URL = "/api/assistant/public/try-extract";
    private static final String GUEST_KEY = "guest-key_0123456789";
    private static final String OUTPUT = """
            {"truck": {"lengthCm": 520, "widthCm": 200, "heightCm": 200, "payloadKg": null},
             "cartons": [{"lengthCm": 60, "widthCm": 40, "heightCm": 40, "weightKg": 12, "quantity": 80, "stop": 1}],
             "assumptions": ["Khách không nói điểm giao nên mọi hàng giao điểm 1."],
             "missing": []}""";

    private static String request(String text, String guestKey) {
        StringBuilder json = new StringBuilder("{\"locale\":\"vi\"");
        if (text != null) {
            json.append(",\"text\":\"").append(text).append('"');
        }
        if (guestKey != null) {
            json.append(",\"guestKey\":\"").append(guestKey).append('"');
        }
        return json.append('}').toString();
    }

    @Test
    void guestWithoutTokenGetsTheFormValuesInTheEnvelope() throws Exception {
        model.reply(OUTPUT, 1400, 160);

        MvcResult result = mockMvc.perform(post(URL).with(from("203.0.113.31"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("Thùng 5,2 x 2 x 2 m; 80 thùng 60x40x40 cm 12 kg", GUEST_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.truck.lengthCm").value(520.0))
                .andExpect(jsonPath("$.data.cartons[0].quantity").value(80))
                .andExpect(jsonPath("$.data.cartons[0].stop").value(1))
                .andExpect(jsonPath("$.data.missing[0]").value("truck.payloadKg"))
                .andReturn();

        // null vẫn được ghi ra: FE phân biệt "không rõ" với "thiếu trường"
        Map<String, Object> truck = read(result, "$.data.truck");
        assertThat(truck).containsKey("payloadKg").containsEntry("payloadKg", null);
        assertThat(AssistantWebTestBase.<List<String>>read(result, "$.data.assumptions"))
                .containsExactly("Khách không nói điểm giao nên mọi hàng giao điểm 1.");
        // Không trả số token hay chữ nào khác của mô hình
        assertThat(AssistantWebTestBase.<Map<String, Object>>read(result, "$.data"))
                .containsOnlyKeys("truck", "cartons", "assumptions", "missing");
    }

    @Test
    void blankOrMissingTextIsInvalidInput() throws Exception {
        int callsBefore = model.calls();
        for (String text : new String[] {"", "   "}) {
            mockMvc.perform(post(URL).with(from("203.0.113.32"))
                            .contentType(MediaType.APPLICATION_JSON).content(request(text, GUEST_KEY)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        mockMvc.perform(post(URL).with(from("203.0.113.32"))
                        .contentType(MediaType.APPLICATION_JSON).content(request(null, GUEST_KEY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(model.calls()).isEqualTo(callsBefore);
    }

    @Test
    void textOverAThousandCharactersIsTooLong() throws Exception {
        mockMvc.perform(post(URL).with(from("203.0.113.33"))
                        .contentType(MediaType.APPLICATION_JSON).content(request("a".repeat(1001), GUEST_KEY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSISTANT_MESSAGE_TOO_LONG"));
    }

    @Test
    void malformedOrMissingGuestKeyIsInvalidInput() throws Exception {
        for (String guestKey : new String[] {"short", "guest key with spaces!!", "x".repeat(65)}) {
            mockMvc.perform(post(URL).with(from("203.0.113.34"))
                            .contentType(MediaType.APPLICATION_JSON).content(request("80 thùng 12 kg", guestKey)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        mockMvc.perform(post(URL).with(from("203.0.113.34"))
                        .contentType(MediaType.APPLICATION_JSON).content(request("80 thùng 12 kg", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void modelAnswerThatIsNotJsonIsUnavailable() throws Exception {
        // Mô hình giả của ngữ cảnh trả câu chữ thường khi không xếp câu nào
        mockMvc.perform(post(URL).with(from("203.0.113.35"))
                        .contentType(MediaType.APPLICATION_JSON).content(request("80 thùng 12 kg", GUEST_KEY)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"));
    }

    @Test
    void sharesTheHourlyGuestLimitWithPublicMessages() throws Exception {
        String ip = "203.0.113.36";
        for (int call = 1; call <= 20; call++) {
            mockMvc.perform(post("/api/assistant/public/messages").with(from(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"LoadMaster là gì?\",\"locale\":\"vi\",\"guestKey\":\"" + GUEST_KEY + "\"}"))
                    .andExpect(status().isOk());
        }
        int callsBefore = model.calls();

        mockMvc.perform(post(URL).with(from(ip))
                        .contentType(MediaType.APPLICATION_JSON).content(request("80 thùng 12 kg", GUEST_KEY)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ASSISTANT_RATE_LIMITED"));
        assertThat(model.calls()).isEqualTo(callsBefore);
    }

    @Test
    void flagOffAnswersUnavailable() throws Exception {
        properties.setEnabled(false);
        try {
            mockMvc.perform(post(URL).with(from("203.0.113.37"))
                            .contentType(MediaType.APPLICATION_JSON).content(request("80 thùng 12 kg", GUEST_KEY)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"));
        } finally {
            properties.setEnabled(true);
        }
    }
}
