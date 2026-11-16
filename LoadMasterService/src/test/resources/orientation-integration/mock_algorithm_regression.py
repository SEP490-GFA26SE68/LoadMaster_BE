"""Offline contract/AI orchestration: real synthetic geometry, no model loads/network."""
import sys,runpy,time,unittest
from pathlib import Path
from types import SimpleNamespace as NS,ModuleType
from unittest.mock import patch
# Resolve from explicitly supplied project path; do not discover or read .env.
root=Path(sys.argv[1]);sys.path.insert(0,str(root))
bootstrap=runpy.run_path(str(root/'docs/diagnostics/offline_diagnostics.py'),run_name='offline_bootstrap')
from tests.test_packing_profile_orientation import synthetic_five
from tests.test_load_balance_search import assert_safe
from app.service.optimize.optimization_client import OptimizationClient
from app.ml import lumo_models as lm
from app.service.optimize import metaheuristic as meta
from app.constant.optimization.plan_algorithm import PlanAlgorithm as P
class AlgorithmOrientationRegression(unittest.TestCase):
    def setUp(self):
        self.fixture=synthetic_five.__wrapped__();runner,db=next(self.fixture)
        self.problem=runner.build_problem_request('synthetic-only',db)
        self.client=OptimizationClient()
        self.layout=self.client.solve_in_process(self.problem,use_lumo_scorer=False)
        assert_safe(self.layout,self.problem)
        self.assertEqual(len(self.layout.placements),5)
    def tearDown(self):
        try:next(self.fixture)
        except StopIteration:pass
    def test_ga_ai_orchestration_uses_exact_profile_whitelist(self):
        tried=[]
        # Mock orchestration boundaries only. Candidate geometry is computed by real EP/DBLF.
        with patch.object(lm,'run_lumo_models',return_value=lm.LumoRun(layouts=[self.layout],tried=[P.LUMO_R1.value])) as ai,patch.object(meta,'run_ga_sa',return_value=meta.MetaResult(layouts=[self.layout],tried=[P.GA.value])) as ga:
            layouts=self.client._advanced_layouts(self.problem,[P.LUMO_R1.value,P.GA.value],[('fixture',self.layout)],time.monotonic(),time.monotonic()+5,tried)
        self.assertEqual(len(layouts),2);self.assertEqual(tried,[P.LUMO_R1.value,P.GA.value])
        self.assertIs(ai.call_args.args[0],self.problem)
        self.assertIs(ga.call_args.args[1],self.problem)
        self.assertEqual([p.allowed_rotations for p in self.problem.packages],[[0,1],[2],[1],[0,1,2,3,4,5],[0,5]])
        for layout in layouts:assert_safe(layout,self.problem)
    def mocked_ai(self,placements):
        torch=ModuleType('torch');torch.Generator=lambda **kw:NS(manual_seed=lambda seed:object())
        module=ModuleType('app.ml.lmai.problem');module.Problem=NS(from_request=lambda req:object())
        result=NS(verdict=NS(valid=True),placements=[])
        with patch.dict(sys.modules,{'torch':torch,'app.ml.lmai.problem':module}),patch.object(lm,'torch_available',return_value=True),patch.object(lm,'_load',return_value=(object(),object())),patch.object(lm,'_rollout',return_value=result),patch.object(lm,'_to_placements',return_value=placements):
            return lm.run_lumo_models(self.problem,[P.LUMO_R1.value],self.client,samples=0)
    def test_mocked_ai_inference_real_final_validator_accepts_safe_geometry(self):
        result=self.mocked_ai(self.layout.placements)
        self.assertEqual(len(result.layouts),1);assert_safe(result.layouts[0],self.problem)
    def test_mocked_ai_forbidden_rotation_rejected_despite_geometric_fit(self):
        placements=list(self.layout.placements)
        index=next(i for i,p in enumerate(placements) if str(p.package_id)=='2')
        # Profile ID1 permits solver2 only. Solver1 geometrically fits but is forbidden.
        placements[index]=placements[index].model_copy(update={'rotation_type':1,'packed_l':30,'packed_w':40,'packed_h':25})
        result=self.mocked_ai(placements)
        self.assertEqual(result.layouts,[])
        self.assertEqual(result.skipped[P.LUMO_R1.value],'CONSTRAINT_VIOLATED')
if __name__=='__main__':
    sys.argv=[sys.argv[0]]
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(AlgorithmOrientationRegression)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    assert not bootstrap['network_attempts']
    raise SystemExit(0 if result.wasSuccessful() else 1)
