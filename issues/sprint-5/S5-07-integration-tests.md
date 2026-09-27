# S5-07 · Integration Tests

| Field | Value |
|-------|-------|
| **Sprint** | 5 |
| **Module** | QA |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **Status** | 🔲 Todo |

## Acceptance Criteria

### Auth Flow Test
- [ ] Login → get token → access protected API → 200
- [ ] No token → 401
- [ ] Wrong role → 403
- [ ] User có `status != ACTIVE` → 403

### Optimization Flow Test
- [ ] Create trip → validate → create job → mock engine → verify plan saved
- [ ] Approve plan → verify status
- [ ] Pin `Package.isPinned` & rerun → verify `LoadPlan.planVersion` increment

### Warehouse Flow Test
- [ ] Start loading → verify `LoadingExecution(loadPlanId, workerUserId, status)` → complete với `sealNumber`

### Driver Flow Test
- [ ] Get trips → get ordered stop manifest → complete trip → verify `Trip.status` và AuditLog
