package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseLabelVerifierTest {
    CargoPackage pkg = CargoPackage.builder().id(12L).packageCode("PK-001").qrToken("qr-001").trackingBarcode("BC-001").build();
    @Test void packageCodeAllowsTrimAndCaseButNotOtherLabels() {
        assertDoesNotThrow(() -> WarehouseLabelVerifier.requireMatch(pkg, StagingMethod.CODE, "  pk-001  "));
        for (String code : new String[]{"12","qr-001","BC-001","PK-002"})
            assertEquals(ErrorCode.PACKAGE_CODE_MISMATCH, assertThrows(AppException.class,
                () -> WarehouseLabelVerifier.requireMatch(pkg, StagingMethod.CODE, code)).getErrorCode());
    }
    @ParameterizedTest @ValueSource(strings={" qr-001 ","BC-001"})
    void qrKeepsPrintedQrAndBarcode(String code) {
        assertDoesNotThrow(() -> WarehouseLabelVerifier.requireMatch(pkg, StagingMethod.QR, code));
    }
    @Test void missingCodeAndManualCannotMasqueradeAsLabel() {
        for (String code : new String[]{null,""," "})
            assertThrows(AppException.class, () -> WarehouseLabelVerifier.requireMatch(pkg, StagingMethod.CODE, code));
        assertThrows(AppException.class, () -> WarehouseLabelVerifier.requireMatch(pkg, StagingMethod.MANUAL, "PK-001"));
        assertThrows(AppException.class, () -> WarehouseLabelVerifier.requireMatch(pkg, null, "PK-001"));
    }
}
