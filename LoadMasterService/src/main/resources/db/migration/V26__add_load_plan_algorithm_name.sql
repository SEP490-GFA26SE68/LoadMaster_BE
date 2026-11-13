-- Thuật toán đã tạo ra từng phương án (EP_DBLF, GA, SA, EP_LUMO_R1_1, LUMO_R1, LUMO_R1_1, LUMO_R2).
-- Dịch vụ tối ưu ghi; phương án cũ để trống và đọc theo thuật toán của job.
ALTER TABLE load_plans ADD COLUMN IF NOT EXISTS algorithm_name VARCHAR(60);
