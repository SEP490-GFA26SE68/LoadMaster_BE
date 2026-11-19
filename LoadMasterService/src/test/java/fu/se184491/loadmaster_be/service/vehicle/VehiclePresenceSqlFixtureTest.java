package fu.se184491.loadmaster_be.service.vehicle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VehiclePresenceSqlFixtureTest {
 @Test void exactReviewedProposalIsPackaged()throws Exception{
  try(var in=getClass().getResourceAsStream("/sql/vehicle-current-presence-proposal.sql")){
   assertNotNull(in);assertEquals("dd3074e1dcac46b1727feea016f0e69f44537a2707e765e8958d3fc95c749269",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))));
  }
 }
}
