package fu.se184491.loadmaster_be.service.planning;

import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackagePackingProfile;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackagePackingProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementCargoPriorityTest {

    @Mock
    private CargoPackagePackingProfileRepository packingProfileRepository;

    @InjectMocks
    private RequirementCargoPriority requirementCargoPriority;

    @Test
    void urgentRequirementMarksPackageMustLoadWithPriorityFour() {
        CargoPackage cargo = CargoPackage.builder().id(9L).build();
        when(packingProfileRepository.findByCargoPackageId(9L)).thenReturn(Optional.empty());

        requirementCargoPriority.apply(List.of(cargo), DeliveryRequirementPriority.URGENT);

        ArgumentCaptor<CargoPackagePackingProfile> captor = ArgumentCaptor.forClass(CargoPackagePackingProfile.class);
        verify(packingProfileRepository).save(captor.capture());
        assertEquals(4, captor.getValue().getPriority());
        assertTrue(captor.getValue().getMustLoad());
    }
}
