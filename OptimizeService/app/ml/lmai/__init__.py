"""
Bản chép gói `lmai` của dự án LoadMaster_AI (E:/SEP490/LoadMaster_AI, thư mục lmai/) — chỉ các mô-đun
cần cho suy luận: problem, zones, state, validate, packer, result, env, model, ppo (load_policy), generator,
plan_score + profiles + plan_profiles.json (J theo hồ sơ; service dùng app/config/plan_profiles.json, bản ở đây chỉ để lmai import được), load_order, seed_guard (ppo nạp lúc import). Không có phần huấn luyện chạy trong service; không huấn luyện mô hình ở đây.
Cần numpy + torch; thiếu thì app/ml/lumo_models.py bỏ qua mô hình AI.
"""
