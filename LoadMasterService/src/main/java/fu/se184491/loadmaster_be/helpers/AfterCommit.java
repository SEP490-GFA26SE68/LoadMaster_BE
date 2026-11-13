package fu.se184491.loadmaster_be.helpers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Việc ra ngoài hệ thống (gửi email...) chạy sau khi transaction đã ghi xong: transaction rollback thì không gửi
 * thư báo một tài khoản không tồn tại. Lỗi lúc đó không còn đảo được dữ liệu nên chỉ ghi log.
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(String description, Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    log.error("Việc sau khi ghi thất bại ({}): {}", description, ex.getMessage(), ex);
                }
            }
        });
    }
}
