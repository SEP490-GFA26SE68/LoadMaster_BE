package fu.se184491.loadmaster_be.config.security;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KeycloakRealmRoleConverter phát vai trò dưới dạng ROLE_&lt;tên&gt;, nên
 * hasAuthority('DISPATCHER') không khớp ai và hasRole('ROLE_DISPATCHER') thành ROLE_ROLE_DISPATCHER.
 */
class PreAuthorizeRoleNamesTest {

    /** Controller do nhánh khác sửa (vòng đời chuyến) — không kiểm ở đây. */
    private static final Set<String> EXEMPT = Set.of("TripController");

    private static final Pattern AUTHORITY_CALL = Pattern.compile("has(Any)?Authority\\(([^)]*)\\)");
    private static final Pattern ROLE_CALL = Pattern.compile("has(Any)?Role\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    @Test
    @DisplayName("@PreAuthorize không dùng tên vai trò làm authority trần, không gắn ROLE_ trong hasRole")
    void roleNamesAreWrittenTheWaySpringSecurityMatchesThem() throws Exception {
        Set<String> roles = Arrays.stream(UserRole.values()).map(Enum::name).collect(Collectors.toSet());
        List<String> problems = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition candidate : scanner.findCandidateComponents("fu.se184491.loadmaster_be.controller")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (EXEMPT.contains(type.getSimpleName())) {
                continue;
            }
            List<String> expressions = new ArrayList<>();
            PreAuthorize onClass = type.getAnnotation(PreAuthorize.class);
            if (onClass != null) {
                expressions.add(onClass.value());
            }
            for (Method method : type.getDeclaredMethods()) {
                PreAuthorize onMethod = method.getAnnotation(PreAuthorize.class);
                if (onMethod != null) {
                    expressions.add(onMethod.value());
                }
            }
            for (String expression : expressions) {
                // Authority trần trùng một hasRole cùng biểu thức là thừa nhưng vô hại; chỉ chặn khi nó là lối vào duy nhất.
                List<String> grantedRoles = quotedArgs(ROLE_CALL, expression);
                for (String name : quotedArgs(AUTHORITY_CALL, expression)) {
                    if (roles.contains(name) && !grantedRoles.contains(name)) {
                        problems.add(type.getSimpleName() + ": authority '" + name + "' trong " + expression);
                    }
                }
                for (String name : quotedArgs(ROLE_CALL, expression)) {
                    if (name.startsWith("ROLE_")) {
                        problems.add(type.getSimpleName() + ": hasRole '" + name + "' trong " + expression);
                    }
                }
            }
        }

        assertThat(problems).isEmpty();
    }

    private static List<String> quotedArgs(Pattern call, String expression) {
        List<String> names = new ArrayList<>();
        Matcher calls = call.matcher(expression);
        while (calls.find()) {
            Matcher quoted = QUOTED.matcher(calls.group(2));
            while (quoted.find()) {
                names.add(quoted.group(1));
            }
        }
        return names;
    }
}
