"""Read only, pure validators; never import app.main/config/session/worker or connect a DB."""
import json
import pathlib
import sys
import types
import importlib.abc

class NoApplicationConfig(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname.startswith(("app.config", "app.entity", "app.main")):
            raise RuntimeError("Runtime configuration/entities forbidden in pure fixture check")
sys.meta_path.insert(0, NoApplicationConfig())
root = pathlib.Path(__file__).resolve().parents[4] / "OptimizeService"
# The optimize package __init__ eagerly imports job/DB services; skip that initializer,
# while loading the actual validator modules unchanged from their own source files.
for name, relative in [("app.service", "app/service"), ("app.service.optimize", "app/service/optimize")]:
    package = types.ModuleType(name)
    package.__path__ = [str(root / relative)]
    sys.modules[name] = package
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[4] / "OptimizeService"))
from app.dto.optimization.engine.problem_request import VehicleData, PackageData
from app.dto.optimization.engine.engine_response import PlacementData
from app.service.optimize.constraint_engine import ConstraintEngine
from app.service.optimize.unload_feasibility_validator import UnloadFeasibilityValidator

fixture = pathlib.Path(__file__).resolve().parents[1] / "resources/warehouse-step6/plan.json"
data = json.loads(fixture.read_text(encoding="utf-8"))
vehicle = VehicleData(**data["vehicle"])
packages = {p["id"]: PackageData(**p) for p in data["packages"]}
placements = [PlacementData(**p) for p in data["placements"]]
assert set(packages) == {str(p.package_id) for p in placements}
assert len(placements) == len(packages)
for p in placements:
    assert (p.packed_l, p.packed_w, p.packed_h) == (20, 20, 20)
    violations = ConstraintEngine.check_all(p, dict(vehicle=vehicle, packages_map=packages,
        existing_placements=[other for other in placements if other is not p], check_cog=True, check_axle=True))
    assert not violations, str(violations)
unload = UnloadFeasibilityValidator.validate(placements, packages, vehicle)
assert unload.passed, str(unload)
print("PASS: shared 2-package fixture; all ConstraintEngine checks incl. CoG/axle + unload; no solver/DB")
print("Unload sequence:", unload.unload_sequence)
