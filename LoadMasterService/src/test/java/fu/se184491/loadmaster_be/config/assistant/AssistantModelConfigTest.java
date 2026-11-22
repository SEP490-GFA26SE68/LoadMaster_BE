package fu.se184491.loadmaster_be.config.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Bean mô hình chỉ có khi bật cờ và có key; thiếu một trong hai thì ứng dụng vẫn khởi động, không có bean. */
class AssistantModelConfigTest {

    // Key giả: dựng bean không gọi mạng
    private static final String FAKE_KEY = "sk-ant-test-not-a-real-key";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AssistantProperties.class, AssistantModelConfig.class);

    @Test
    void enabledWithKeyBuildsTheModel() {
        runner.withPropertyValues("assistant.enabled=true", "spring.ai.anthropic.api-key=" + FAKE_KEY)
                .run(context -> assertThat(context).hasSingleBean(ChatModel.class)
                        .hasBean(AssistantModelConfig.MODEL_BEAN));
    }

    @Test
    void enabledWithoutKeyHasNoModel() {
        runner.withPropertyValues("assistant.enabled=true", "spring.ai.anthropic.api-key=")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatModel.class));
    }

    @Test
    void keyWithoutFlagHasNoModel() {
        runner.withPropertyValues("spring.ai.anthropic.api-key=" + FAKE_KEY)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ChatModel.class));
    }

    @Test
    void adaptiveThinkingAndAnotherEffortAlsoBuild() {
        runner.withPropertyValues("assistant.enabled=true", "spring.ai.anthropic.api-key=" + FAKE_KEY,
                        "assistant.thinking=adaptive", "assistant.effort=medium")
                .run(context -> assertThat(context).hasSingleBean(ChatModel.class));
    }
}
