package fu.se184491.loadmaster_be.service.qr;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.service.cargo.PackageOwnershipService;
import fu.se184491.loadmaster_be.service.qr.Impl.QrCodeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** Ảnh QR theo mã kiện chỉ trả kiện của công ty người gọi. */
@ExtendWith(MockitoExtension.class)
class QrCodeServiceImplScopeTest {

    @Mock
    private CargoPackageRepository cargoPackageRepository;
    @Mock
    private PackageOwnershipService packageOwnershipService;
    @InjectMocks
    private QrCodeServiceImpl service;

    @Test
    void findById_ownPackage_returnsIt() {
        CargoPackage pkg = new CargoPackage();
        when(cargoPackageRepository.findById(7L)).thenReturn(Optional.of(pkg));
        when(packageOwnershipService.belongsToCompany(pkg, 1L)).thenReturn(true);

        assertSame(pkg, service.findById(7L, 1L));
    }

    @Test
    void findById_otherCompanyPackage_isNotFound() {
        CargoPackage pkg = new CargoPackage();
        when(cargoPackageRepository.findById(7L)).thenReturn(Optional.of(pkg));
        when(packageOwnershipService.belongsToCompany(pkg, 2L)).thenReturn(false);

        AppException ex = assertThrows(AppException.class, () -> service.findById(7L, 2L));
        assertEquals(ErrorCode.PACKAGE_NOT_FOUND, ex.getErrorCode());
    }
}
