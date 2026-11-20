package fu.se184491.loadmaster_be.controller.dispatcher;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.payroll.DispatcherPayrollService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DispatcherPayrollControllerTest {

    @Test
    void dispatcherCanReadOwnStatement() throws Exception {
        DispatcherPayrollService service = mock(DispatcherPayrollService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        User dispatcher = User.builder().id(31L).build();
        when(currentUser.getCurrentUser()).thenReturn(dispatcher);
        when(currentUser.getCurrentCompanyId()).thenReturn(10L);

        MockMvcBuilders.standaloneSetup(new DispatcherPayrollController(service, currentUser)).build()
                .perform(get("/api/dispatcher/payroll").param("period", "2026-11"))
                .andExpect(status().isOk());
    }
}
