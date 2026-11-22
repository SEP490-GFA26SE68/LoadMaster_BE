package fu.se184491.loadmaster_be.service.assistant.model;

/** Token của một lượt. {@code inputTokens} gồm cả token đọc và ghi bộ đệm prompt. */
public record TokenUsage(int inputTokens, int outputTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public long total() {
        return (long) inputTokens + outputTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
