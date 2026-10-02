package fu.se184491.loadmaster_be.controller.company;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.dto.request.company.CompanyRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyResponse;
import fu.se184491.loadmaster_be.service.company.CompanyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.time.Instant;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CompanyController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
public class CompanyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CompanyService companyService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private ObjectMapper objectMapper;
    private CompanyRequest request;
    private CompanyResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        request = CompanyRequest.builder()
                .companyCode("LM-LOGISTICS")
                .companyName("LoadMaster Logistics")
                .taxCode("TAX-001")
                .billingEmail("billing@loadmaster.vn")
                .status(CompanyStatus.ACTIVE)
                .build();

        response = CompanyResponse.builder()
                .id(1L)
                .companyCode("LM-LOGISTICS")
                .companyName("LoadMaster Logistics")
                .taxCode("TAX-001")
                .billingEmail("billing@loadmaster.vn")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("POST /api/companies should return 201 Created when request is valid")
    @WithMockUser(authorities = "SYSTEM_MANAGER")
    void createCompany_success() throws Exception {
        when(companyService.createCompany(any(CompanyRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/companies")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.companyCode").value("LM-LOGISTICS"))
                .andExpect(jsonPath("$.companyName").value("LoadMaster Logistics"));
    }

    @Test
    @DisplayName("POST /api/companies should return 400 Bad Request when validation fails")
    @WithMockUser(authorities = "SYSTEM_MANAGER")
    void createCompany_validationError() throws Exception {
        CompanyRequest invalidRequest = CompanyRequest.builder()
                .companyCode("") // blank code
                .companyName("") // blank name
                .taxCode("") // blank taxCode
                .billingEmail("invalid-email") // invalid email
                .build();

        mockMvc.perform(post("/api/companies")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        verify(companyService, never()).createCompany(any());
    }

    @Test
    @DisplayName("PUT /api/companies/{id} should return 200 OK")
    @WithMockUser(authorities = "SYSTEM_MANAGER")
    void updateCompany_success() throws Exception {
        when(companyService.updateCompany(eq(1L), any(CompanyRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/companies/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.companyName").value("LoadMaster Logistics"));
    }

    @Test
    @DisplayName("GET /api/companies/{id} should return 200 OK")
    @WithMockUser(authorities = "SYSTEM_MANAGER")
    void getCompanyById_success() throws Exception {
        when(companyService.getCompanyById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/companies/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.companyCode").value("LM-LOGISTICS"));
    }

    @Test
    @DisplayName("GET /api/companies should return 200 OK with Page")
    @WithMockUser(authorities = "SYSTEM_MANAGER")
    void getAllCompanies_success() throws Exception {
        Page<CompanyResponse> page = new PageImpl<>(Collections.singletonList(response));
        when(companyService.getAllCompanies(any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1L))
                .andExpect(jsonPath("$.content[0].companyCode").value("LM-LOGISTICS"));
    }

    @Test
    @DisplayName("PATCH /api/companies/{id}/status should return 200 OK")
    @WithMockUser(authorities = "SYSTEM_MANAGER")
    void changeCompanyStatus_success() throws Exception {
        response.setStatus(CompanyStatus.SUSPENDED);
        when(companyService.changeCompanyStatus(1L, CompanyStatus.SUSPENDED)).thenReturn(response);

        mockMvc.perform(patch("/api/companies/1/status")
                        .with(csrf())
                        .param("status", "SUSPENDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }
}
