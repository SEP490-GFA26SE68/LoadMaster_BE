# S1-04 · GlobalExceptionHandler + ApiResponse

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | Infra & Config |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Assignee** | — |
| **Status** | ✅ Done |

## Mô tả
Đã có sẵn — review và bổ sung nếu thiếu case.

## Acceptance Criteria
- [x] `GlobalExceptionHandler` bắt `AppException`, `MethodArgumentNotValidException`, `AccessDeniedException`
- [x] `ErrorCode` enum có HttpStatus + message
- [x] `ApiResponse<T>` wrapper chuẩn
- [ ] Bổ sung bắt `DataIntegrityViolationException` (unique constraint)
- [ ] Bổ sung bắt `HttpMessageNotReadableException` (malformed JSON)

## Files liên quan
- `src/main/java/.../exception/GlobalExceptionHandler.java` (đã có)
- `src/main/java/.../exception/ErrorCode.java` (đã có)
- `src/main/java/.../exception/AppException.java` (đã có)
- `src/main/java/.../dto/response/ApiResponse.java` (đã có)
