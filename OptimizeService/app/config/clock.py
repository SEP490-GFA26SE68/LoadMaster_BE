"""Giờ ghi vào DB dùng chung với Java: giờ Việt Nam, không kèm múi giờ.

Java (TimeZone mặc định Asia/Ho_Chi_Minh) ghi giờ Việt Nam vào các cột timestamp không múi giờ,
nên dịch vụ này ghi cùng cách để hai bên đọc ra một mốc. Việt Nam cố định UTC+7, không đổi giờ
theo mùa, nên không cần gói tzdata.
"""
from datetime import datetime, timedelta, timezone

VIETNAM = timezone(timedelta(hours=7), "Asia/Ho_Chi_Minh")


def vietnam_now() -> datetime:
    return datetime.now(VIETNAM).replace(tzinfo=None)
