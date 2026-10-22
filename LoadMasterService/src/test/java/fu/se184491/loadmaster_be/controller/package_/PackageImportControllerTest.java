package fu.se184491.loadmaster_be.controller.package_;

import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.response.ImportPreviewResult;
import fu.se184491.loadmaster_be.dto.response.RowError;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.exception.GlobalExceptionHandler;
import fu.se184491.loadmaster_be.exception.ImportValidationException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.service.import_.PackageImportService;
import fu.se184491.loadmaster_be.service.qr.QrCodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PackageImportController.class)
@Import({WebSecurityConfig.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class PackageImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PackageImportService packageImportService;

    @MockitoBean
    private QrCodeService qrCodeService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RolePermissionMapping rolePermissionMapping;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "DISPATCHER")
    @DisplayName("POST /api/packages/import/confirm with errors returns 422 with error details in data and errors fields")
    void confirm_whenValidationFails_returns422WithDetailedErrors() throws Exception {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.csv", "text/csv", "pkg,1,2,3,4,abc,dest".getBytes()
        );

        User mockUser = new User();
        mockUser.setId(99L);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(currentUserService.getCurrentUser()).thenReturn(mockUser);

        RowError error = new RowError(
                2, "handlingClass", ErrorCode.IMPORT_INVALID_HANDLING_CLASS, "'abc'"
        );
        ImportPreviewResult previewResult = new ImportPreviewResult(
                1, 0, List.of(error), Collections.emptyList()
        );

        when(packageImportService.confirm(any(), any(), any(), any()))
                .thenThrow(new ImportValidationException(previewResult));

        // When & Then
        mockMvc.perform(multipart("/api/packages/import/confirm").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"))
                .andExpect(jsonPath("$.message").value("File import chứa 1 dòng lỗi dữ liệu, vui lòng kiểm tra lại"))
                .andExpect(jsonPath("$.data.errors[0].rowIndex").value(2))
                .andExpect(jsonPath("$.data.errors[0].field").value("handlingClass"))
                .andExpect(jsonPath("$.data.errors[0].errorCode").value("IMPORT_INVALID_HANDLING_CLASS"))
                .andExpect(jsonPath("$.errors[0].field").value("handlingClass"));
    }
}
