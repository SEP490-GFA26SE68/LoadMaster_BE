package fu.se184491.loadmaster_be.controller.cargo;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.request.cargo.StackingRuleRequest;
import fu.se184491.loadmaster_be.dto.response.cargo.StackingRuleResponse;
import fu.se184491.loadmaster_be.service.cargo.StackingRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StackingRuleController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
public class StackingRuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StackingRuleService stackingRuleService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler restAccessDeniedHandler;

    @MockitoBean
    private RolePermissionMapping rolePermissionMapping;

    private ObjectMapper objectMapper;
    private StackingRuleRequest request;
    private StackingRuleResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        request = StackingRuleRequest.builder()
                .bottomPackageTypeId(10L)
                .topPackageTypeId(20L)
                .isAllowed(false)
                .minContactRatio(new BigDecimal("0.85"))
                .build();

        response = StackingRuleResponse.builder()
                .id(100L)
                .companyId(1L)
                .bottomPackageTypeId(10L)
                .bottomPackageTypeName("Bottom Box")
                .topPackageTypeId(20L)
                .topPackageTypeName("Top Box")
                .isAllowed(false)
                .minContactRatio(new BigDecimal("0.85"))
                .build();
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void createStackingRule_Success() throws Exception {
        when(stackingRuleService.createStackingRule(eq(1L), any(StackingRuleRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/stacking-rules")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.bottomPackageTypeId").value(10L))
                .andExpect(jsonPath("$.topPackageTypeId").value(20L))
                .andExpect(jsonPath("$.isAllowed").value(false));
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void createStackingRule_ValidationError_MissingBottomType() throws Exception {
        request.setBottomPackageTypeId(null);

        mockMvc.perform(post("/api/stacking-rules")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void updateStackingRule_Success() throws Exception {
        when(stackingRuleService.updateStackingRule(eq(1L), eq(100L), any(StackingRuleRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/stacking-rules/100")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.isAllowed").value(false));
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void getStackingRuleById_Success() throws Exception {
        when(stackingRuleService.getStackingRuleById(eq(1L), eq(100L))).thenReturn(response);

        mockMvc.perform(get("/api/stacking-rules/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.bottomPackageTypeName").value("Bottom Box"));
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void deleteStackingRule_Success() throws Exception {
        doNothing().when(stackingRuleService).deleteStackingRule(1L, 100L);

        mockMvc.perform(delete("/api/stacking-rules/100")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void getAllStackingRules_Success() throws Exception {
        Page<StackingRuleResponse> page = new PageImpl<>(Collections.singletonList(response));
        when(stackingRuleService.getAllStackingRules(eq(1L), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/stacking-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(100L));
    }

    @Test
    @WithMockUser(authorities = "PACKAGE_TYPE_MANAGE")
    void checkStackingAllowed_Success() throws Exception {
        when(stackingRuleService.isStackingAllowed(1L, 10L, 20L)).thenReturn(false);

        mockMvc.perform(get("/api/stacking-rules/check")
                        .param("bottomPackageTypeId", "10")
                        .param("topPackageTypeId", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
    }
}
