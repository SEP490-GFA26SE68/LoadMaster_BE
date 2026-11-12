package fu.se184491.loadmaster_be.helpers;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeycloakAccountsTest {

    private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    private final UserRepository users = mock(UserRepository.class);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void finishTransaction(int status) {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                sync.afterCommit();
            }
            sync.afterCompletion(status);
        }
    }

    @Test
    void createdKeycloakUserIsDeletedWhenTheTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        when(keycloak.createUser("a@x.vn", "A")).thenReturn("kc-1");

        String id = KeycloakAccounts.provision(keycloak, users, "a@x.vn", "A", "pw", true, UserRole.DISPATCHER, false);
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(id).isEqualTo("kc-1");
        verify(keycloak).setPassword("kc-1", "pw", true);
        verify(keycloak).assignRealmRole("kc-1", "DISPATCHER");
        verify(keycloak).deleteUser("kc-1");
    }

    @Test
    void createdKeycloakUserStaysWhenTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        when(keycloak.createUser("a@x.vn", "A")).thenReturn("kc-1");

        KeycloakAccounts.provision(keycloak, users, "a@x.vn", "A", "pw", true, UserRole.DISPATCHER, false);
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verify(keycloak, never()).deleteUser(anyString());
    }

    @Test
    void reusedOrphanIsNeverDeleted() {
        TransactionSynchronizationManager.initSynchronization();
        when(keycloak.findUserIdByEmail("a@x.vn")).thenReturn("kc-orphan");
        when(users.findByKeycloakId("kc-orphan")).thenReturn(Optional.empty());

        String id = KeycloakAccounts.provision(keycloak, users, "a@x.vn", "A", "pw", false, UserRole.ADMIN, true);
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(id).isEqualTo("kc-orphan");
        verify(keycloak).updateUserProfile("kc-orphan", "A");
        verify(keycloak, never()).createUser(anyString(), anyString());
        verify(keycloak, never()).deleteUser(anyString());
    }

    @Test
    void keycloakUserLinkedToAnAccountIsNotTakenOver() {
        when(keycloak.findUserIdByEmail("a@x.vn")).thenReturn("kc-used");
        when(users.findByKeycloakId("kc-used")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> KeycloakAccounts.provision(
                keycloak, users, "a@x.vn", "A", "pw", false, UserRole.ADMIN, true))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
        verify(keycloak, never()).setPassword(anyString(), anyString(), anyBoolean());
    }

    @Test
    void withoutATransactionAFailedSetupIsUndoneImmediately() {
        when(keycloak.createUser("a@x.vn", "A")).thenReturn("kc-1");
        doThrow(new IllegalStateException("role missing")).when(keycloak).assignRealmRole(any(), any());

        assertThatThrownBy(() -> KeycloakAccounts.provision(
                keycloak, users, "a@x.vn", "A", "pw", true, UserRole.DISPATCHER, false))
                .isInstanceOf(IllegalStateException.class);
        verify(keycloak).deleteUser("kc-1");
    }

    @Test
    void failedCompensationDoesNotHideTheRollback() {
        TransactionSynchronizationManager.initSynchronization();
        when(keycloak.createUser("a@x.vn", "A")).thenReturn("kc-1");
        doThrow(new IllegalStateException("keycloak down")).when(keycloak).deleteUser("kc-1");

        KeycloakAccounts.provision(keycloak, users, "a@x.vn", "A", "pw", true, UserRole.DISPATCHER, false);
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(keycloak).deleteUser("kc-1");
    }

    @Test
    void afterCommitRunsOnlyWhenTheTransactionCommits() {
        AtomicInteger sent = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run("thử", sent::incrementAndGet);
        assertThat(sent).hasValue(0);
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(sent).hasValue(0);
        TransactionSynchronizationManager.clearSynchronization();

        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run("thử", sent::incrementAndGet);
        AfterCommit.run("lỗi", () -> {
            throw new IllegalStateException("brevo down");
        });
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        assertThat(sent).hasValue(1);
    }
}
