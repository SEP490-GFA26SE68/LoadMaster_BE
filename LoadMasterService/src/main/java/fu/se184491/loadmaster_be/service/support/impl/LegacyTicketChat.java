package fu.se184491.loadmaster_be.service.support.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.dto.response.support.TicketReplyResponse;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cuộc trao đổi của bản trước: mảng JSON trong {@code resolution_note} ({@code authorId}, {@code authorName}, {@code authorRole},
 * {@code text}, {@code at} dạng {@code yyyy-MM-dd HH:mm:ss}). Chỉ đọc — không xoá, không chép đè dữ liệu cũ.
 */
final class LegacyTicketChat {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private LegacyTicketChat() {
    }

    /** Các lần trả lời đọc được trong ghi chú; ghi chú chữ thường hay mảng hỏng là danh sách rỗng. */
    static List<TicketReplyResponse> repliesOf(String note) {
        if (note == null || !note.trim().startsWith("[")) {
            return List.of();
        }
        try {
            List<Map<String, Object>> items = MAPPER.readValue(note.trim(), new TypeReference<>() {});
            List<TicketReplyResponse> out = new ArrayList<>();
            for (Map<String, Object> item : items) {
                Object text = item.get("text");
                LocalDateTime at = timeOf(item.get("at"));
                if (!(text instanceof String message) || at == null) {
                    continue;
                }
                out.add(TicketReplyResponse.builder()
                        .authorId(stringOf(item.get("authorId")))
                        .authorName(stringOf(item.get("authorName")))
                        .authorRole(stringOf(item.get("authorRole")))
                        .message(message)
                        .createdAt(at)
                        .build());
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** Lần hoạt động gần nhất của yêu cầu: mốc ghi lại khi đổi, không có thì mốc muộn nhất của tạo, giải quyết và trao đổi cũ. */
    static LocalDateTime lastActivity(SupportTicket ticket) {
        if (ticket.getUpdatedAt() != null) {
            return ticket.getUpdatedAt();
        }
        LocalDateTime latest = ticket.getCreatedAt();
        if (ticket.getResolvedAt() != null && (latest == null || ticket.getResolvedAt().isAfter(latest))) {
            latest = ticket.getResolvedAt();
        }
        for (TicketReplyResponse reply : repliesOf(ticket.getResolutionNote())) {
            if (latest == null || reply.getCreatedAt().isAfter(latest)) {
                latest = reply.getCreatedAt();
            }
        }
        return latest;
    }

    private static String stringOf(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalDateTime timeOf(Object value) {
        if (value == null) {
            return null;
        }
        String raw = value.toString().trim().replace('T', ' ');
        try {
            return LocalDateTime.parse(raw.length() > 19 ? raw.substring(0, 19) : raw, FORMAT);
        } catch (Exception ignored) {
            return null;
        }
    }
}
