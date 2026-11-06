from enum import Enum


class PlanAlgorithm(str, Enum):
    """
    Thuật toán đã thật sự sinh ra một phương án (cột load_plans.algorithm_name).

    Khác với mã thuật toán của job (EP_DBLF | EP_DBLF_GA | EP_DBLF_GA_AI — hạng của gói):
    mã job nói gói được chạy những gì, mã phương án nói phương án này đến từ đâu.
    Bộ mã cố định, FE dịch theo đúng các mã này.
    """
    # Bộ dựng Extreme Points + DBLF, mọi chiến lược (CENTER_FIRST, DEEP_FIRST, HEAVY_FIRST, AXLE_TARGET).
    EP_DBLF = "EP_DBLF"
    # Giải thuật di truyền: thứ tự kiện + hướng ưu tiên, giải mã bằng EP + DBLF.
    GA = "GA"
    # Mô phỏng luyện kim, bắt đầu từ cá thể tốt nhất của GA, giải mã bằng EP + DBLF.
    SA = "SA"
    # EP + DBLF, Lumo-R1.1 chấm điểm chỗ đặt ngay trong vòng xếp.
    EP_LUMO_R1_1 = "EP_LUMO_R1_1"
    # Mô hình học tăng cường tự xếp cả phương án (suy luận, không huấn luyện).
    LUMO_R1 = "LUMO_R1"
    LUMO_R1_1 = "LUMO_R1_1"
    # Bản thử nghiệm: chưa qua tiêu chí phát hành.
    LUMO_R2 = "LUMO_R2"
