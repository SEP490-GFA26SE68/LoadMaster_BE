-- Trợ lý AI: hội thoại, tin nhắn và lượt gọi công cụ.
-- Không khoá ngoại tới users / companies (như V24): xoá tài khoản hay công ty không vấp lịch sử trợ lý.
-- Không lưu kết quả công cụ, chỉ tên, tham số, đạt / không đạt và thời gian.
CREATE TABLE IF NOT EXISTS assistant_conversations (
    id                  BIGSERIAL PRIMARY KEY,
    public_id           VARCHAR(40) NOT NULL,
    user_id             BIGINT      NULL,
    company_id          BIGINT      NULL,
    guest_key           VARCHAR(64) NULL,
    channel             VARCHAR(20) NOT NULL,          -- PUBLIC | APP
    handoff_ticket_code VARCHAR(40) NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_assistant_conversations_public_id UNIQUE (public_id)
);

CREATE TABLE IF NOT EXISTS assistant_messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES assistant_conversations (id),
    role            VARCHAR(10) NOT NULL,              -- USER | ASSISTANT
    content         TEXT        NOT NULL,
    ui_actions      JSONB       NULL,
    handoff_offered BOOLEAN     NOT NULL DEFAULT FALSE,
    input_tokens    INT         NOT NULL DEFAULT 0,
    output_tokens   INT         NOT NULL DEFAULT 0,
    model           VARCHAR(60) NULL,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS assistant_tool_calls (
    id          BIGSERIAL PRIMARY KEY,
    message_id  BIGINT      NOT NULL REFERENCES assistant_messages (id),
    tool_name   VARCHAR(60) NOT NULL,
    arguments   JSONB       NULL,
    ok          BOOLEAN     NOT NULL,
    duration_ms INT         NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_assistant_conversations_user_id ON assistant_conversations (user_id);
CREATE INDEX IF NOT EXISTS idx_assistant_messages_conversation_id ON assistant_messages (conversation_id);
CREATE INDEX IF NOT EXISTS idx_assistant_messages_created_at ON assistant_messages (created_at);
CREATE INDEX IF NOT EXISTS idx_assistant_tool_calls_message_id ON assistant_tool_calls (message_id);
