package fu.se184491.loadmaster_be.controller.assistant;

import fu.se184491.loadmaster_be.service.assistant.cache.GuestReplyCache;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sự kiện đường trả lời của trang công khai (không token, hạn mức riêng, chỉ đếm), bảng đếm cho hai vai trò nền tảng, và
 * bộ nhớ đệm câu trả lời của khách qua HTTP.
 */
class AssistantRouteStatsControllerTest extends AssistantWebTestBase {

    private static final String EVENTS = "/api/assistant/public/route-events";
    private static final String STATS = "/api/assistant/route-stats";

    @Autowired
    GuestReplyCache replyCache;

    @AfterEach
    void restore() {
        properties.getRouteStats().setEventsPerHour(120);
        properties.getReplyCache().setEnabled(false);
        replyCache.clear();
    }

    private static String event(String route, String topicId, String locale) {
        return "{\"route\":" + quoted(route) + ",\"topicId\":" + quoted(topicId) + ",\"locale\":" + quoted(locale) + "}";
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private long topicTotal(String topicId) throws Exception {
        MvcResult result = mockMvc.perform(get(STATS)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
                .andExpect(status().isOk())
                .andReturn();
        JSONArray totals = read(result, "$.data.byTopic[?(@.topicId == '" + topicId + "')].total");
        return totals.isEmpty() ? 0 : ((Number) totals.getFirst()).longValue();
    }

    @Test
    void validEventsAreCountedWithoutABody() throws Exception {
        long before = topicTotal("routeStatsTopic");

        for (String route : new String[] {"PREPARED", "PREPARED", "ESCALATED", "SMALL_TALK"}) {
            mockMvc.perform(post(EVENTS).with(from("203.0.113.61")).contentType(MediaType.APPLICATION_JSON)
                            .content(event(route, "routeStatsTopic", "en")))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
        }
        mockMvc.perform(post(EVENTS).with(from("203.0.113.61")).contentType(MediaType.APPLICATION_JSON)
                        .content(event("SMALL_TALK", null, "vi")))
                .andExpect(status().isNoContent());

        assertThat(topicTotal("routeStatsTopic")).isEqualTo(before + 4);
    }

    @Test
    void malformedEventsAreRejected() throws Exception {
        String[] bodies = {
                event("MODEL", "plans", "vi"), event("CACHED", "plans", "vi"), event("prepared", "plans", "vi"),
                event(null, "plans", "vi"), event("PREPARED", "has space", "vi"), event("PREPARED", "plans2", "vi"),
                event("PREPARED", "x".repeat(41), "vi"), event("PREPARED", "", "vi"), event("PREPARED", "plans", "fr"),
                event("PREPARED", "plans", null), "{}"};
        for (String body : bodies) {
            mockMvc.perform(post(EVENTS).with(from("203.0.113.62")).contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Test
    void eventsHaveTheirOwnHourlyLimit() throws Exception {
        properties.getRouteStats().setEventsPerHour(2);
        for (int call = 1; call <= 2; call++) {
            mockMvc.perform(post(EVENTS).with(from("203.0.113.63")).contentType(MediaType.APPLICATION_JSON)
                            .content(event("PREPARED", "plans", "vi")))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(post(EVENTS).with(from("203.0.113.63")).contentType(MediaType.APPLICATION_JSON)
                        .content(event("PREPARED", "plans", "vi")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ASSISTANT_RATE_LIMITED"));

        // Hạn mức hỏi của cùng IP không bị đụng tới
        mockMvc.perform(post("/api/assistant/public/messages").with(from("203.0.113.63"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Xin chào\",\"locale\":\"vi\",\"guestKey\":\"route-stats_limit01\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void statsAreForThePlatformRolesOnly() throws Exception {
        mockMvc.perform(get(STATS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(STATS).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(STATS).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(STATS).param("from", "2026-10-01").param("to", "2026-10-31")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value("2026-10-01"))
                .andExpect(jsonPath("$.data.to").value("2026-10-31"))
                .andExpect(jsonPath("$.data.byRoute.MODEL").isNumber())
                .andExpect(jsonPath("$.data.byRoute.CACHED").isNumber());
    }

    @Test
    void rangesLongerThanNinetyDaysOrReversedAreInvalid() throws Exception {
        for (String[] range : new String[][] {{"2026-01-01", "2026-04-01"}, {"2026-10-31", "2026-10-01"}}) {
            mockMvc.perform(get(STATS).param("from", range[0]).param("to", range[1])
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Test
    void sameFirstGuestQuestionIsServedFromCache() throws Exception {
        properties.getReplyCache().setEnabled(true);
        String body = "{\"message\":\"Đội xe có mấy loại?\",\"locale\":\"vi\",\"guestKey\":\"route-stats_cache01\"}";
        mockMvc.perform(post("/api/assistant/public/messages").with(from("203.0.113.64"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usage.inputTokens").value(900));
        int callsBefore = model.calls();

        mockMvc.perform(post("/api/assistant/public/messages").with(from("203.0.113.65"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reply").value(ANSWER))
                .andExpect(jsonPath("$.data.usage.inputTokens").value(0))
                .andExpect(jsonPath("$.data.usage.outputTokens").value(0));

        assertThat(model.calls()).isEqualTo(callsBefore);
    }
}
