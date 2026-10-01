package fu.se184491.loadmaster_be.controller.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.request.customer.CustomerRequest;
import fu.se184491.loadmaster_be.dto.response.customer.CustomerResponse;
import fu.se184491.loadmaster_be.service.customer.CustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CustomerController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
public class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private ObjectMapper objectMapper;
    private CustomerRequest request;
    private CustomerResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        request = CustomerRequest.builder()
                .name("John Doe")
                .contactPhone("123456789")
                .address("123 Main St")
                .build();

        response = CustomerResponse.builder()
                .id(1L)
                .companyId(1L)
                .name("John Doe")
                .contactPhone("123456789")
                .address("123 Main St")
                .build();
    }

    @Test
    @WithMockUser(authorities = "CUSTOMER_MANAGE")
    void createCustomer_Success() throws Exception {
        when(customerService.createCustomer(eq(1L), any(CustomerRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("John Doe"));
    }

    @Test
    @WithMockUser(authorities = "CUSTOMER_MANAGE")
    void getAllCustomers_Success() throws Exception {
        Page<CustomerResponse> page = new PageImpl<>(Collections.singletonList(response));
        
        when(customerService.getAllCustomers(eq(1L), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1L));
    }
}
