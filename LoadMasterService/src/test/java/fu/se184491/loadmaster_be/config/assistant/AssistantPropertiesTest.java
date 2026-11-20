package fu.se184491.loadmaster_be.config.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPropertiesTest {

    private static AssistantProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        return binder.bindOrCreate("assistant", Bindable.of(AssistantProperties.class));
    }

    @Test
    void defaultsWhenNothingConfigured() {
        AssistantProperties p = bind(Map.of());

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getModel()).isEqualTo("claude-haiku-5-5");
        assertThat(p.getMaxOutputTokens()).isEqualTo(800);
        assertThat(p.getMaxToolRounds()).isEqualTo(5);
        assertThat(p.getHistoryMessages()).isEqualTo(12);
        assertThat(p.getGuestPerHour()).isEqualTo(20);
        assertThat(p.getUserPerHour()).isEqualTo(60);
        assertThat(p.getDailyTokenBudget()).isEqualTo(2_000_000L);
        assertThat(p.getMaxMessageChars()).isEqualTo(1000);
        assertThat(p.getMaxDraftChars()).isEqualTo(4000);
        assertThat(p.getThinking()).isEqualTo("disabled");
        assertThat(p.getEffort()).isEqualTo("low");
        assertThat(p.getCallTimeoutSeconds()).isEqualTo(20);
        assertThat(p.getTurnTimeoutSeconds()).isEqualTo(35);
    }

    @Test
    void bindsTheSpecBlock() {
        AssistantProperties p = bind(Map.of(
                "assistant.enabled", "true",
                "assistant.model", "claude-sonnet-5-5",
                "assistant.max-output-tokens", "600",
                "assistant.max-tool-rounds", "3",
                "assistant.history-messages", "8",
                "assistant.guest-per-hour", "10",
                "assistant.user-per-hour", "30",
                "assistant.daily-token-budget", "500000"));

        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getModel()).isEqualTo("claude-sonnet-5-5");
        assertThat(p.getMaxOutputTokens()).isEqualTo(600);
        assertThat(p.getMaxToolRounds()).isEqualTo(3);
        assertThat(p.getHistoryMessages()).isEqualTo(8);
        assertThat(p.getGuestPerHour()).isEqualTo(10);
        assertThat(p.getUserPerHour()).isEqualTo(30);
        assertThat(p.getDailyTokenBudget()).isEqualTo(500_000L);
    }
}
