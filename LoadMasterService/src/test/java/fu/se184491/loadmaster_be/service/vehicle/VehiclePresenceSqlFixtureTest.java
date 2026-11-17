package fu.se184491.loadmaster_be.service.vehicle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VehiclePresenceSqlFixtureTest {
 @Test void exactReviewedProposalIsPackaged()throws Exception{
  try(var in=getClass().getResourceAsStream("/sql/vehicle-current-presence-proposal.sql")){
   assertNotNull(in);assertEquals("613cc3165aa7f0c636a53aa0930044bd10bf8c17db21a513ef668a6b22b6c4e3",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
  }
 }
}
