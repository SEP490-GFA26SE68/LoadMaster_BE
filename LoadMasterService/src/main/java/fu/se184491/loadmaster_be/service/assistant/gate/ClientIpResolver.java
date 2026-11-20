package fu.se184491.loadmaster_be.service.assistant.gate;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * IP của khách để đếm lượt hỏi. Máy chủ chạy sau reverse proxy trên cùng máy, nên {@code getRemoteAddr()} là IP của proxy.
 * Chỉ khi peer là địa chỉ nội bộ (loopback, dải riêng, link-local, IPv6 ULA) mới tin {@code X-Forwarded-For}, và chỉ phần
 * tử phải nhất — phần proxy tự ghi; các phần tử bên trái do trình duyệt gửi, giả được.
 */
@Component
public class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!isLocalProxy(peer)) {
            return peer;
        }
        String header = request.getHeader(FORWARDED_FOR);
        if (header == null) {
            return peer;
        }
        String[] parts = header.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].trim();
            if (!candidate.isEmpty()) {
                return isIpLiteral(candidate) ? candidate : peer;
            }
        }
        return peer;
    }

    private static boolean isLocalProxy(String peer) {
        if (peer == null || !isIpLiteral(peer)) {
            return false;
        }
        try {
            // Chuỗi đã là địa chỉ số nên không tra DNS
            InetAddress address = InetAddress.getByName(peer);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || (address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC);
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isIpLiteral(String value) {
        return IPV4.matcher(value).matches() || (value.indexOf(':') >= 0 && IPV6.matcher(value).matches());
    }
}
