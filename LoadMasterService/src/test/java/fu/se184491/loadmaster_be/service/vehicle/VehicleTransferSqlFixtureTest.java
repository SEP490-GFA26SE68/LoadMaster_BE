package fu.se184491.loadmaster_be.service.vehicle;
import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;
class VehicleTransferSqlFixtureTest {
 @Test void reviewedProposalHasExactClasspathBytes()throws Exception{try(var in=getClass().getResourceAsStream("/sql/vehicle-warehouse-transfers.sql")){assertNotNull(in);assertEquals("7ee072250af95a387ed814f05e6cda510dbd74fea0bc344bd1007e3bc71cb50b",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))));}}
}
