package fu.se184491.loadmaster_be.service.assistant;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.service.assistant.model.AssistantCaller;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Người hỏi trong ứng dụng, đọc từ token và DB. Mọi thứ về vai trò, công ty, gói đi vào ngữ cảnh đều lấy ở đây. */
@Component
@RequiredArgsConstructor
public class AssistantCallerResolver {

    private final CurrentUserService currentUserService;
    private final CompanySubscriptionRepository subscriptions;

    // Công ty và gói là quan hệ LAZY: đọc trong một giao dịch
    @Transactional(readOnly = true)
    public AssistantCaller current() {
        User user = currentUserService.getCurrentUser();
        Company company = user.getCompany();
        Long companyId = company == null ? null : company.getId();
        String companyName = company == null ? null : company.getCompanyName();
        String planName = companyId == null ? null : subscriptions.findByCompanyId(companyId)
                .map(CompanySubscription::getPlan)
                .map(SubscriptionPlan::getName)
                .orElse(null);
        return new AssistantCaller(user.getId(), companyId, user.getUserRoleType(), companyName, planName);
    }
}
