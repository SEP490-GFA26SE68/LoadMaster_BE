-- Backfill trips.completed_at cho các chuyến DELIVERED cũ chưa ghi completed_at
-- để nhất quán giữa màn Lịch sử giao hàng và tính lương theo kỳ (DriverPayrollServiceImpl).
UPDATE trips t
SET completed_at = COALESCE(
    (SELECT MAX(ds.completed_at) FROM delivery_stops ds WHERE ds.trip_id = t.id),
    (SELECT MAX(ds.actual_arrival) FROM delivery_stops ds WHERE ds.trip_id = t.id),
    t.departure_time
)
WHERE t.status = 'DELIVERED'
  AND t.completed_at IS NULL
  AND COALESCE(
      (SELECT MAX(ds.completed_at) FROM delivery_stops ds WHERE ds.trip_id = t.id),
      (SELECT MAX(ds.actual_arrival) FROM delivery_stops ds WHERE ds.trip_id = t.id),
      t.departure_time
  ) IS NOT NULL;
