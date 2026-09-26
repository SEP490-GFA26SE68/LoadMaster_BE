# S1-05 · Swagger/OpenAPI Config

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Infra & Config |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **Assignee** | — |
| **Status** | 🔲 Todo |

## Mô tả
Tích hợp springdoc-openapi để tự sinh API documentation, truy cập tại `/swagger-ui.html`.

## Acceptance Criteria
- [ ] Thêm dependency `springdoc-openapi-starter-webmvc-ui` vào `pom.xml`
- [ ] Cấu hình title, version, description trong `OpenApiConfig.java`
- [ ] Swagger UI accessible tại `/swagger-ui.html`
- [ ] Hỗ trợ Bearer token input trong Swagger UI
- [ ] Group API theo module (auth, user, vehicle, order, ...)

## Files cần tạo
- `src/main/java/.../config/OpenApiConfig.java`
