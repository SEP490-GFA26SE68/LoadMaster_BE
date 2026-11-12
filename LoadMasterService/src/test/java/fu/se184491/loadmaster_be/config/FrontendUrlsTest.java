package fu.se184491.loadmaster_be.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendUrlsTest {

    @Test
    void joinDropsTrailingSlashAndFallsBackToLocalWeb() {
        assertThat(FrontendUrls.join("https://app.example.com/", "/dang-nhap")).isEqualTo("https://app.example.com/dang-nhap");
        assertThat(FrontendUrls.join(" https://app.example.com ", "")).isEqualTo("https://app.example.com");
        assertThat(FrontendUrls.join(null, "/goi-cuoc")).isEqualTo("http://localhost:5173/goi-cuoc");
        assertThat(FrontendUrls.join("", "/goi-cuoc")).isEqualTo("http://localhost:5173/goi-cuoc");
    }

    @Test
    void originsSplitOnCommasAndDefaultToLocalWeb() {
        assertThat(FrontendUrls.origins("http://localhost:5173, https://app.example.com,,"))
                .isEqualTo(List.of("http://localhost:5173", "https://app.example.com"));
        assertThat(FrontendUrls.origins(" ")).isEqualTo(List.of("http://localhost:5173"));
        assertThat(FrontendUrls.origins(null)).isEqualTo(List.of("http://localhost:5173"));
    }
}
