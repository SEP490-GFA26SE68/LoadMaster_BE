package fu.se184491.loadmaster_be.config.security;

import fu.se184491.loadmaster_be.exception.ErrorCode;
import tools.jackson.databind.json.JsonMapper;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint
        implements AuthenticationEntryPoint {

    private final JsonMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiResponse<Object> body =
                ApiResponse.builder()
                        .success(false)
                        .message(ErrorCode.UNAUTHENTICATED.name())
                        .build();

        objectMapper.writeValue(
                response.getOutputStream(),
                body
        );
    }
}