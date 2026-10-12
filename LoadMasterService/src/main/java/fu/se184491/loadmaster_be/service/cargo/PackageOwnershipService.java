package fu.se184491.loadmaster_be.service.cargo;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;

/**
 * Decides which company a package belongs to.
 */
public interface PackageOwnershipService {

    /**
     * True when the package belongs to the given company: its own company if set,
     * otherwise the company of its order (legacy packages).
     */
    boolean belongsToCompany(CargoPackage pkg, Long companyId);
}
