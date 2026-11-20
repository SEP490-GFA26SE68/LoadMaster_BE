# Phase 1: credit/rerun endpoint security

No credit formula, balance, refund policy, algorithm, migration or Flyway change is included.

## Required Keycloak configuration (manual)

The live `optimization-service` client-credentials token was signature/issuer/expiry verified without calling any credit API. Its `azp` and service account username were correct and its realm roles included `OPTIMIZATION_CREDIT`, but `aud` was only `account`. It therefore fails the existing Spring Boot `loadmaster-api` audience requirement. The fix intentionally keeps that requirement strict.

In the `loadmaster` realm, configure the confidential `optimization-service` client:

1. Keep service accounts enabled and assign realm role `OPTIMIZATION_CREDIT` to its service account.
2. Add an OIDC Audience mapper in its dedicated/default client scope: included client audience `loadmaster-api`, add to access token enabled. Do not replace/remove audiences required by other services.
3. Ensure `preferred_username` is included and is `service-account-optimization-service`; `azp` must be `optimization-service`. The allowed client ID can be configured with `loadmaster.security.credit-service-client-id` (default `optimization-service`).
4. Disable interactive flows for this machine-only client if unused. Keep its secret server-side; do not expose service tokens to the frontend.
5. Obtain a fresh client-credentials token (or restart Python to clear its existing in-memory token cache). Confirm issuer, expiry/signature, audience, role, `azp` and service-account username. Never log the raw token or secret.

No live Keycloak configuration was changed. Existing runtime refunds will continue to fail audience validation until the mapper is applied and a fresh token is issued.

## Endpoint access

- Both `/api/internal/credits/{deduct,refund}` and legacy `/api/credits/{deduct,refund}` require a valid JWT with audience `loadmaster-api`, realm role `OPTIMIZATION_CREDIT`, the allowed `azp` and service-account username. Anonymous calls are 401; authenticated wrong roles/identity are 403.
- `/api/load-plans/{id}/pin`, DELETE `/api/load-plans/{id}/pin/{placementId}`, and `/api/load-plans/{id}/rerun` require DISPATCHER and company ownership through plan -> job -> trip -> company before any write/credit/engine call.
- GET `/api/load-plans/compare` accepts company operational readers: DISPATCHER, MANAGER, ADMIN/COMPANY_ADMIN, WAREHOUSE_WORKER, DRIVER. Both plans must belong to the current company. Platform roles do not automatically bypass tenant ownership.
- Existing optimization-job status/plan proxy routes keep their existing Dispatcher authentication rules; their payloads are unchanged.
- `/webhook/vnpay` and `/webhook/vnpay-return` remain public for the gateway, but always require a valid payment signature. Neither a `MOCK_` reference nor client `vnp_Mock` can disable signature checking.
- Mock checkout requires a company administrator, the server's active `dev` profile and explicit `VNPAY_MOCK=true`. It is disabled if `prod` or `production` is also active, and the payment must belong to that administrator's company. Default `VNPAY_MOCK` is false. Production rejects a requested MOCK gateway before creating any payment record.

## Verification

Security tests use the real filter chain, converter and production issuer/audience validators with ephemeral RSA-signed JWTs and a local public-key decoder. There is no mocked authentication postprocessor and no live credit/payment API call. Repositories, credit service and external optimization engine are mocked; no application startup initializer, shared database, Keycloak admin action or migration runs in these slice tests.

Not included in phase 1: missing company/subscription on initial jobs, idempotency races, refund settlement semantics, PRO quota correction, rerun engine fallback behavior or GA/AI dispatch.
