package fu.se184491.loadmaster_be.security;

import fu.se184491.loadmaster_be.controller.internal.CreditInternalController;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CreditInternalController.class)
@Import(TestJwtSupport.class)
class InternalCreditIdentitySecurityTest {
 @Autowired MockMvc mvc;
 @MockitoBean CreditService credits;
 private void request(String action,String token,int expected)throws Exception{
  var r=post("/api/internal/credits/"+action).contentType("application/json")
      .content("{\"companyId\":1,\"reference\":\"isolated-job\"}");
  if(token!=null)r.header("Authorization","Bearer "+token);
  mvc.perform(r).andExpect(status().is(expected));
 }
 @ParameterizedTest @ValueSource(strings={"deduct","refund"}) void missingTokenRejected(String a)throws Exception{
  request(a,null,401);verifyNoInteractions(credits);
 }
 @ParameterizedTest @ValueSource(strings={"deduct","refund"}) void wrongAudienceRejected(String a)throws Exception{
  request(a,TestJwtSupport.serviceToken("wrong-api","optimization-service","service-account-optimization-service",List.of("OPTIMIZATION_CREDIT")),401);verifyNoInteractions(credits);
 }
 @ParameterizedTest @ValueSource(strings={"deduct","refund"}) void missingRoleRejected(String a)throws Exception{
  request(a,TestJwtSupport.serviceToken("loadmaster-api","optimization-service","service-account-optimization-service",List.of()),403);verifyNoInteractions(credits);
 }
 @ParameterizedTest @ValueSource(strings={"deduct","refund"}) void wrongClientRejectedEvenWithRole(String a)throws Exception{
  request(a,TestJwtSupport.serviceToken("loadmaster-api","another-service","service-account-another-service",List.of("OPTIMIZATION_CREDIT")),403);verifyNoInteractions(credits);
 }
 @ParameterizedTest @ValueSource(strings={"deduct","refund"}) void userCannotImpersonateServiceUsingRole(String a)throws Exception{
  request(a,TestJwtSupport.serviceToken("loadmaster-api","optimization-service","ordinary-user",List.of("OPTIMIZATION_CREDIT")),403);verifyNoInteractions(credits);
 }
 @ParameterizedTest @ValueSource(strings={"deduct","refund"}) void exactServiceIdentityAllowed(String a)throws Exception{
  request(a,TestJwtSupport.serviceToken("loadmaster-api","optimization-service","service-account-optimization-service",List.of("OPTIMIZATION_CREDIT")),200);
  if(a.equals("deduct"))verify(credits).deductCredit(1L,"isolated-job");else verify(credits).refundCredit(1L,"isolated-job",null);
 }
}
