#!/bin/sh
# Mở client loadmaster-web của realm ĐÃ CÓ cho origin web production (VPS), giữ nguyên http://localhost:5173.
#
# --import-realm chỉ nhập realm khi realm chưa tồn tại, nên realm đã tạo trước đó không nhận LOADMASTER_WEB_URL của
# file import. Script này đặt lại đúng bốn chỗ của client loadmaster-web: rootUrl / baseUrl / adminUrl, redirectUris,
# webOrigins và post.logout.redirect.uris. Chạy lại bao nhiêu lần cũng ra cùng kết quả. Không đụng Direct Access Grants
# (web đăng nhập bằng password grant, dự phòng authorization code + PKCE S256) và không đụng client loadmaster-mobile.
#
# Chạy trong container Keycloak (có sẵn kcadm.sh):
#   docker exec -e KEYCLOAK_ADMIN_PASSWORD -e LOADMASTER_WEB_URL=https://app.example.vn \
#     loadmaster-keycloak sh /opt/keycloak/scripts/set-web-url.sh
# hoặc chép script vào container trước. Biến:
#   LOADMASTER_WEB_URL       origin của web, không có dấu / cuối (bắt buộc), ví dụ https://app.example.vn
#   KEYCLOAK_ADMIN_PASSWORD  mật khẩu admin realm master (bắt buộc; không in ra)
#   KEYCLOAK_ADMIN           mặc định admin
#   KEYCLOAK_SERVER          mặc định http://localhost:8080 (bên trong container)
#   KEYCLOAK_REALM           mặc định loadmaster
#   KCADM                    mặc định /opt/keycloak/bin/kcadm.sh
set -eu

: "${LOADMASTER_WEB_URL:?Đặt LOADMASTER_WEB_URL, ví dụ https://app.example.vn}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Đặt KEYCLOAK_ADMIN_PASSWORD}"
KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
KEYCLOAK_SERVER="${KEYCLOAK_SERVER:-http://localhost:8080}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-loadmaster}"
KCADM="${KCADM:-/opt/keycloak/bin/kcadm.sh}"
LOCAL="http://localhost:5173"
WEB="${LOADMASTER_WEB_URL%/}"

case "$WEB" in
  https://*|http://localhost*|http://127.0.0.1*) ;;
  *) echo "LOADMASTER_WEB_URL phải là https:// (hoặc localhost khi thử ở máy): $WEB" >&2; exit 1 ;;
esac

"$KCADM" config credentials --server "$KEYCLOAK_SERVER" --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

CLIENT_ID=$("$KCADM" get clients -r "$KEYCLOAK_REALM" -q clientId=loadmaster-web --fields id --format csv --noquotes)
if [ -z "$CLIENT_ID" ]; then
  echo "Không tìm thấy client loadmaster-web trong realm $KEYCLOAK_REALM" >&2
  exit 1
fi

"$KCADM" update "clients/$CLIENT_ID" -r "$KEYCLOAK_REALM" \
  -s "rootUrl=$WEB" -s "baseUrl=$WEB" -s "adminUrl=$WEB" \
  -s "redirectUris=[\"$LOCAL/*\",\"$WEB/*\",\"https://oauth.pstmn.io/v1/callback\"]" \
  -s "webOrigins=[\"$LOCAL\",\"$WEB\"]" \
  -s "attributes.\"post.logout.redirect.uris\"=$LOCAL/*##$WEB/*" \
  -s "directAccessGrantsEnabled=true"

echo "loadmaster-web của realm $KEYCLOAK_REALM nhận $WEB (vẫn giữ $LOCAL)."
