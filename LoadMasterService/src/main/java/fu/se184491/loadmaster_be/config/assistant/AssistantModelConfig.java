package fu.se184491.loadmaster_be.config.assistant;

import com.anthropic.models.messages.OutputConfig;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;

/**
 * Bean {@code ChatModel} của trợ lý. Chỉ dựng khi {@code assistant.enabled=true} và có
 * {@code spring.ai.anthropic.api-key}; thiếu một trong hai thì không có bean, ứng dụng vẫn khởi động và trợ lý trả
 * {@code ASSISTANT_UNAVAILABLE}. Không dùng auto-config của Spring AI. Không đặt {@code temperature}, {@code top_p},
 * {@code top_k}.
 */
@Configuration
public class AssistantModelConfig {

    public static final String MODEL_BEAN = "assistantChatModel";

    @Bean(MODEL_BEAN)
    @Conditional(EnabledWithKey.class)
    public ChatModel assistantChatModel(AssistantProperties properties,
                                        org.springframework.core.env.Environment environment) {
        String apiKey = environment.getProperty("spring.ai.anthropic.api-key");
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(properties.getCallTimeoutSeconds()))
                .maxRetries(1)
                .model(properties.getModel())
                .maxTokens(properties.getMaxOutputTokens())
                .cacheOptions(AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS).build());
        if ("adaptive".equalsIgnoreCase(properties.getThinking())) {
            options.thinkingAdaptive();
        } else {
            options.thinkingDisabled();
        }
        if (StringUtils.hasText(properties.getEffort())) {
            options.effort(OutputConfig.Effort.of(properties.getEffort().trim().toLowerCase(Locale.ROOT)));
        }
        return AnthropicChatModel.builder().options(options.build()).build();
    }

    /** Bật cờ và có key. Đọc thẳng từ môi trường vì {@code @ConditionalOnProperty} không kiểm được "khác rỗng". */
    static class EnabledWithKey implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            boolean enabled = Boolean.parseBoolean(env.getProperty("assistant.enabled", "false"));
            return enabled && StringUtils.hasText(env.getProperty("spring.ai.anthropic.api-key"));
        }
    }
}
