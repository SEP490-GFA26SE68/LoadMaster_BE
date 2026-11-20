package fu.se184491.loadmaster_be.service.assistant.briefing;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantLink;
import fu.se184491.loadmaster_be.service.assistant.output.LinkCatalog;
import fu.se184491.loadmaster_be.service.assistant.output.LinkTarget;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolScope;

import java.util.ArrayList;
import java.util.List;

/**
 * Liên kết dưới bản tin, dựng từ sự kiện qua {@link LinkCatalog} theo vai trò người hỏi (mô hình không viết đường
 * dẫn): Giám sát khi có sự cố hay nguy cơ trễ hạn, từng chuyến cần chú ý, rồi danh sách chuyến khi hôm nay có chuyến.
 */
final class BriefingLinks {

    static final int MAX_LINKS = 5;

    private BriefingLinks() {
    }

    static List<AssistantLink> of(BriefingFacts facts, UserRole role, String language) {
        ToolScope scope = ToolScope.of(role);
        List<AssistantLink> links = new ArrayList<>();
        if (facts.openExceptions() > 0 || facts.lateRiskStops() > 0) {
            add(links, LinkTarget.MONITORING, null, scope, language);
        }
        for (BriefingFacts.AttentionTrip trip : facts.attentionTrips()) {
            add(links, LinkTarget.TRIP_DETAIL, trip.tripCode(), scope, language);
        }
        if (facts.tripsTodayTotal() > 0) {
            add(links, LinkTarget.TRIPS, null, scope, language);
        }
        return links;
    }

    private static void add(List<AssistantLink> links, LinkTarget target, String tripCode, ToolScope scope,
                            String language) {
        if (links.size() < MAX_LINKS) {
            LinkCatalog.resolve(target, tripCode, scope, language).ifPresent(links::add);
        }
    }
}
