package fu.se184491.loadmaster_be.controller.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Trợ lý của trang công khai: khách không token hỏi được, mỗi IP 20 lượt một giờ, mã khách phải đúng dạng. */
class AssistantPublicControllerTest extends AssistantWebTestBase {

    private static final String URL = "/api/assistant/public/messages";
    private static final String GUEST_KEY = "guest-key_0123456789";

    private static String question(String guestKey) {
        return guestKey == null
                ? "{\"message\":\"LoadMaster là gì?\",\"locale\":\"vi\"}"
                : "{\"message\":\"LoadMaster là gì?\",\"locale\":\"vi\",\"guestKey\":\"" + guestKey + "\"}";
    }

    @Test
    void guestWithoutTokenGetsAnAnswerInTheEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(post(URL).with(from("203.0.113.1"))
                        .contentType(MediaType.APPLICATION_JSON).content(question(GUEST_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.handoff.offered").value(false))
                .andExpect(jsonPath("$.data.usage.inputTokens").value(900))
                .andExpect(jsonPath("$.data.usage.outputTokens").value(20))
                .andReturn();

        assertThat(AssistantWebTestBase.<String>read(result, "$.data.reply")).isEqualTo(ANSWER);
        assertThat(AssistantWebTestBase.<String>read(result, "$.data.conversationId")).startsWith("c_");
        assertThat(AssistantWebTestBase.<List<?>>read(result, "$.data.uiActions")).isEmpty();
    }

    @Test
    void twentyFirstCallFromOneIpIsRateLimited() throws Exception {
        for (int call = 1; call <= 20; call++) {
            mockMvc.perform(post(URL).with(from("203.0.113.21"))
                            .contentType(MediaType.APPLICATION_JSON).content(question(GUEST_KEY)))
                    .andExpect(status().isOk());
        }
        int callsBefore = model.calls();

        mockMvc.perform(post(URL).with(from("203.0.113.21"))
                        .contentType(MediaType.APPLICATION_JSON).content(question(GUEST_KEY)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ASSISTANT_RATE_LIMITED"));

        assertThat(model.calls()).isEqualTo(callsBefore);
        // IP khác không bị ảnh hưởng
        mockMvc.perform(post(URL).with(from("203.0.113.22"))
                        .contentType(MediaType.APPLICATION_JSON).content(question(GUEST_KEY)))
                .andExpect(status().isOk());
    }

    @Test
    void malformedOrMissingGuestKeyIsInvalidInput() throws Exception {
        for (String guestKey : new String[] {"short", "guest key with spaces!!", "x".repeat(65)}) {
            mockMvc.perform(post(URL).with(from("203.0.113.3"))
                            .contentType(MediaType.APPLICATION_JSON).content(question(guestKey)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        mockMvc.perform(post(URL).with(from("203.0.113.3"))
                        .contentType(MediaType.APPLICATION_JSON).content(question(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void flagOffAnswersUnavailable() throws Exception {
        properties.setEnabled(false);
        try {
            mockMvc.perform(post(URL).with(from("203.0.113.4"))
                            .contentType(MediaType.APPLICATION_JSON).content(question(GUEST_KEY)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"));
        } finally {
            properties.setEnabled(true);
        }
    }
}
