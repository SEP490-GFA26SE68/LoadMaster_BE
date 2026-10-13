package fu.se184491.loadmaster_be.service.cargo.Impl;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.service.cargo.PackageOwnershipService;
import org.springframework.stereotype.Service;

@Service
public class PackageOwnershipServiceImpl implements PackageOwnershipService {

    @Override
    public boolean belongsToCompany(CargoPackage pkg, Long companyId) {
        if (companyId == null) {
            return false;
        }
        Company owner = pkg.getCompany();
        if (owner == null && pkg.getOrder() != null) {
            owner = pkg.getOrder().getCompany();
        }
        return owner != null && companyId.equals(owner.getId());
    }
}
