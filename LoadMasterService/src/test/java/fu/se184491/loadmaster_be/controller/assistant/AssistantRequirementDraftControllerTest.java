package fu.se184491.loadmaster_be.controller.assistant;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bản nháp yêu cầu giao: chỉ người có DELIVERY_DEMAND_MANAGE (điều phối viên), chỉ trả bản nháp, không tạo gì. */
class AssistantRequirementDraftControllerTest extends AssistantWebTestBase {

    private static final String URL = "/api/assistant/requirement-draft";
    // Hạn luôn ở tương lai so với giờ chạy test
    private static final String DEADLINE = LocalDate.now().plusYears(1) + "T15:00:00";
    private static final String OUTPUT = """
            {"destinationText": "Co.opmart Biên Hoà, 121 Phạm Văn Thuận", "deadline": "%s", "priority": null,
             "expectedPackageCount": 120, "note": "Gọi chị Lan trước khi tới.", "customerNameGuess": "An Phú",
             "packages": [{"description": "thùng nước suối", "quantity": 120, "lengthCm": 40, "widthCm": 30,
                           "heightCm": 25, "weightKg": 12}],
             "missing": []}""".formatted(DEADLINE);

    @Autowired
    DeliveryRequirementRepository requirements;

    @BeforeEach
    void signInDispatcherWithActivePlan() {
        signInAs(dispatcher(21L));
        companyPlan(SubscriptionStatus.ACTIVE, LocalDateTime.now().plusDays(20));
    }

    private static RequestPostProcessor dispatcherToken() {
        return jwt().authorities(() -> "DELIVERY_DEMAND_MANAGE", () -> "DELIVERY_READ");
    }

    private static String request(String text) {
        return "{\"text\":\"" + text + "\"}";
    }

    @Test
    void dispatcherGetsTheDraftAndNothingIsCreated() throws Exception {
        long before = requirements.count();
        model.reply(OUTPUT, 1600, 140);

        MvcResult result = mockMvc.perform(post(URL).with(dispatcherToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(request("Giao 120 thùng nước suối tới Co.opmart Biên Hoà")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.destinationText").value("Co.opmart Biên Hoà, 121 Phạm Văn Thuận"))
                .andExpect(jsonPath("$.data.deadline").value(DEADLINE))
                .andExpect(jsonPath("$.data.expectedPackageCount").value(120))
                .andExpect(jsonPath("$.data.packages[0].lengthCm").value(40.0))
                .andExpect(jsonPath("$.data.missing[0]").value("priority"))
                .andReturn();

        // null vẫn được ghi ra; không trả số token hay chữ nào khác của mô hình
        Map<String, Object> data = read(result, "$.data");
        assertThat(data).containsOnlyKeys("destinationText", "deadline", "priority", "expectedPackageCount", "note",
                "customerNameGuess", "packages", "missing").containsEntry("priority", null);
        assertThat(AssistantWebTestBase.<List<String>>read(result, "$.data.missing")).containsExactly("priority");
        assertThat(requirements.count()).isEqualTo(before);
    }

    @Test
    void companyManagerIsForbidden() throws Exception {
        User manager = User.builder().id(22L).email("quanly22@longbinh.vn").status(UserStatus.ACTIVE)
                .userRoleType(UserRole.MANAGER).company(COMPANY).build();
        signInAs(manager);
        int callsBefore = model.calls();

        mockMvc.perform(post(URL).with(jwt().authorities(() -> "DELIVERY_READ"))
                        .contentType(MediaType.APPLICATION_JSON).content(request("Giao 10 thùng tới Kho Tân Vạn")))
                .andExpect(status().isForbidden());
        assertThat(model.calls()).isEqualTo(callsBefore);
    }

    @Test
    void withoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(request("Giao 10 thùng")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void textOverFourThousandCharactersIsTooLong() throws Exception {
        int callsBefore = model.calls();
        mockMvc.perform(post(URL).with(dispatcherToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(request("a".repeat(4001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSISTANT_MESSAGE_TOO_LONG"));
        assertThat(model.calls()).isEqualTo(callsBefore);
    }

    @Test
    void blankTextIsInvalidInput() throws Exception {
        for (String body : new String[] {request("   "), "{}"}) {
            mockMvc.perform(post(URL).with(dispatcherToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Test
    void modelAnswerThatIsNotJsonIsUnavailable() throws Exception {
        // Mô hình giả của ngữ cảnh trả câu chữ thường khi không xếp câu nào
        mockMvc.perform(post(URL).with(dispatcherToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(request("Giao 10 thùng tới Kho Tân Vạn")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"));
    }

    @Test
    void flagOffAnswersUnavailable() throws Exception {
        properties.setEnabled(false);
        try {
            mockMvc.perform(post(URL).with(dispatcherToken()).contentType(MediaType.APPLICATION_JSON)
                            .content(request("Giao 10 thùng tới Kho Tân Vạn")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"));
        } finally {
            properties.setEnabled(true);
        }
    }
}
