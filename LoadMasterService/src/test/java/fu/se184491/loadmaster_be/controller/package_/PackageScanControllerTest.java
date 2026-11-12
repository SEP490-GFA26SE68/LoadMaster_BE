package fu.se184491.loadmaster_be.controller.package_;

import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.cargo.HandlingClass;
import fu.se184491.loadmaster_be.constant.cargo.PackageFlag;
import fu.se184491.loadmaster_be.constant.cargo.PackageStatus;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Quét mã QR trả cả cờ của kiện, để màn Tra cứu kiện biết kiện đang mang cờ "Không tìm thấy". */
@WebMvcTest(PackageScanController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class PackageScanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QrCodeService qrCodeService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private fu.se184491.loadmaster_be.config.security.RolePermissionMapping rolePermissionMapping;

    @MockitoBean
    private fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler accessDeniedHandler;

    private CargoPackage pkg(PackageFlag flag) {
        CargoPackage pkg = new CargoPackage();
        pkg.setId(100L);
        pkg.setPackageCode("PK-100");
        pkg.setQrToken("lm-abcd-efgh-jkmn");
        pkg.setHandlingClass(HandlingClass.STANDARD);
        pkg.setActualWeightKg(new BigDecimal("12.50"));
        pkg.setStatus(PackageStatus.PENDING);
        pkg.setFlag(flag);
        return pkg;
    }

    @Test
    @WithMockUser(roles = "WAREHOUSE_WORKER")
    void scan_FlaggedPackage_ReturnsFlag() throws Exception {
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(qrCodeService.lookupByToken("lm-abcd-efgh-jkmn", 1L)).thenReturn(pkg(PackageFlag.NOT_FOUND));

        mockMvc.perform(get("/api/packages/scan/lm-abcd-efgh-jkmn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.flag").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "DISPATCHER")
    void scan_PackageWithoutFlag_ReturnsNullFlag() throws Exception {
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(qrCodeService.lookupByToken("lm-abcd-efgh-jkmn", 1L)).thenReturn(pkg(null));

        mockMvc.perform(get("/api/packages/scan/lm-abcd-efgh-jkmn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flag").doesNotExist());
    }
}
