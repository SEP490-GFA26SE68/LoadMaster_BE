package fu.se184491.loadmaster_be.controller.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.request.order.OrderRequest;
import fu.se184491.loadmaster_be.dto.response.order.OrderResponse;
import fu.se184491.loadmaster_be.service.order.OrderService;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
public class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private ObjectMapper objectMapper;
    private OrderRequest request;
    private OrderResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        request = OrderRequest.builder()
                .customerId(10L)
                .deliveryStopId(20L)
                .build();

        response = OrderResponse.builder()
                .id(100L)
                .companyId(1L)
                .orderCode("ORD-CMP01-20261004-0001")
                .customerId(10L)
                .customerName("Acme Corp")
                .deliveryStopId(20L)
                .totalWeightKg(BigDecimal.ZERO)
                .build();
    }

    @Test
    @WithMockUser(authorities = "ORDER_MANAGE")
    void createOrder_Success() throws Exception {
        when(orderService.createOrder(eq(1L), any(OrderRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.orderCode").value("ORD-CMP01-20261004-0001"))
                .andExpect(jsonPath("$.customerId").value(10L))
                .andExpect(jsonPath("$.totalWeightKg").value(0));
    }

    @Test
    @WithMockUser(authorities = "ORDER_MANAGE")
    void createOrder_ValidationError_MissingCustomerId() throws Exception {
        OrderRequest invalidRequest = OrderRequest.builder().build();

        mockMvc.perform(post("/api/orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "ORDER_MANAGE")
    void updateOrder_Success() throws Exception {
        when(orderService.updateOrder(eq(1L), eq(100L), any(OrderRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/orders/100")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.orderCode").value("ORD-CMP01-20261004-0001"));
    }

    @Test
    @WithMockUser(authorities = "ORDER_MANAGE")
    void getOrderById_Success() throws Exception {
        when(orderService.getOrderById(eq(1L), eq(100L))).thenReturn(response);

        mockMvc.perform(get("/api/orders/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100L))
                .andExpect(jsonPath("$.orderCode").value("ORD-CMP01-20261004-0001"));
    }

    @Test
    @WithMockUser(authorities = "ORDER_MANAGE")
    void getAllOrders_Success() throws Exception {
        Page<OrderResponse> page = new PageImpl<>(Collections.singletonList(response));
        when(orderService.getAllOrders(eq(1L), any(), any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/orders")
                        .param("customerId", "10")
                        .param("deliveryStopId", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(100L))
                .andExpect(jsonPath("$.content[0].orderCode").value("ORD-CMP01-20261004-0001"));
    }
}
