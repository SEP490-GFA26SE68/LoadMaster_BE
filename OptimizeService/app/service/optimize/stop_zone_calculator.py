from __future__ import annotations

from typing import Optional, List, Union, Dict, Any
from app.dto.optimization.engine.problem_request import VehicleData
from app.dto.optimization.engine.zone_data import (
    StopZoneInput,
    StopPackageData,
    ZoneData,
    StopZoneCalculationResponse,
)


class StopZoneCalculator:
    """
    Tính kích thước vùng (zone) trong xe cho từng delivery stop theo công thức dynamic sizing.
    Thỏa mãn:
      - volume_ratio_i = sum_volume_stop_i / total_volume_all_stops
      - zone_length_i  = usable_length * volume_ratio_i
      - ACCESSIBILITY_BUFFER = 0.1m (10cm) giữa các zone
      - Zone đầu tiên = gần cửa sau (rear door, x -> inner_l) — stop giao trước
      - Zone cuối = sâu nhất (x = 0) — stop giao sau
      - Tổng zone_length <= vehicle.inner_l (trừ sum of buffers)
    """

    ACCESSIBILITY_BUFFER_M = 0.1

    @classmethod
    def calculate_zones(
        cls,
        vehicle: Union[VehicleData, Dict[str, Any]],
        stops: List[Union[StopZoneInput, Dict[str, Any]]],
        accessibility_buffer: Optional[float] = None,
    ) -> StopZoneCalculationResponse:
        if not stops:
            return StopZoneCalculationResponse(zones=[])

        # 1. Parse vehicle
        if isinstance(vehicle, dict):
            vehicle_data = VehicleData(**vehicle)
        else:
            vehicle_data = vehicle

        inner_l = float(vehicle_data.inner_l)
        inner_w = float(vehicle_data.inner_w)
        inner_h = float(vehicle_data.inner_h)
        max_payload = float(vehicle_data.max_payload_kg or 0.0)
        cross_section = inner_w * inner_h

        # 2. Determine buffer based on unit (m vs mm)
        is_mm = inner_l > 50.0
        if accessibility_buffer is not None:
            if is_mm and accessibility_buffer < 1.0:
                buffer = accessibility_buffer * 1000.0
            else:
                buffer = float(accessibility_buffer)
        else:
            buffer = 100.0 if is_mm else cls.ACCESSIBILITY_BUFFER_M

        num_stops = len(stops)
        num_buffers = max(0, num_stops - 1)
        total_buffer = num_buffers * buffer
        usable_length = max(0.0, inner_l - total_buffer)

        # 3. Parse stops & calculate volume per stop
        parsed_stops = []
        for idx, stop in enumerate(stops):
            if isinstance(stop, dict):
                stop_id = stop.get("stop_id", stop.get("id", idx + 1))
                pkgs = stop.get("packages", [])
                seq = stop.get("sequence", idx + 1)
            else:
                stop_id = stop.stop_id
                pkgs = stop.packages
                seq = stop.sequence if stop.sequence is not None else idx + 1

            stop_vol = 0.0
            stop_wt = 0.0
            for p in pkgs:
                if isinstance(p, dict):
                    vol = p.get("volume")
                    if vol is None or vol <= 0:
                        l = p.get("l")
                        w = p.get("w")
                        h = p.get("h")
                        if l is not None and w is not None and h is not None:
                            vol = float(l) * float(w) * float(h)
                        else:
                            vol = 0.0
                    wt = float(p.get("weight") or 0.0)
                elif isinstance(p, StopPackageData):
                    vol = p.get_volume()
                    wt = p.get_weight()
                else:
                    vol = getattr(p, "volume", 0.0) or 0.0
                    wt = getattr(p, "weight", 0.0) or 0.0

                stop_vol += float(vol or 0.0)
                stop_wt += float(wt or 0.0)

            parsed_stops.append({
                "stop_id": stop_id,
                "sequence": seq,
                "volume": stop_vol,
                "weight": stop_wt,
                "original_idx": idx,
            })

        total_vol = sum(s["volume"] for s in parsed_stops)

        # 4. Calculate volume ratio & zone length for each stop
        for s in parsed_stops:
            if total_vol > 0:
                s["volume_ratio"] = s["volume"] / total_vol
            else:
                s["volume_ratio"] = 1.0 / num_stops
            s["zone_length"] = usable_length * s["volume_ratio"]

        # 5. Coordinate layout:
        # Stop giao trước (sequence 1 / stop đầu tiên) -> gần cửa sau (rear door, x = inner_l)
        # Stop giao sau (sequence N / stop cuối cùng) -> sâu nhất (x = 0)
        #
        # Ordering along X from 0 (deepest) to inner_l (door):
        # Position 0 has the deepest stop (reversed delivery sequence).
        # Position last has the first delivery stop.
        stops_by_delivery = list(parsed_stops)  # delivery order: 1, 2, ... N
        deepest_to_door = list(reversed(stops_by_delivery))  # N, N-1, ... 1

        cur_x = 0.0
        for i, s in enumerate(deepest_to_door):
            z_len = s["zone_length"]
            s["zone_start_x"] = round(cur_x, 4)
            s["zone_end_x"] = round(cur_x + z_len, 4)
            cur_x += z_len
            if i < len(deepest_to_door) - 1:
                cur_x += buffer

        # Snap last zone ending to inner_l to eliminate float rounding errors
        if deepest_to_door:
            deepest_to_door[-1]["zone_end_x"] = round(inner_l, 4)

        # 6. Build ZoneData output in the original stop order
        zones: List[ZoneData] = []
        for s in parsed_stops:
            actual_len = s["zone_end_x"] - s["zone_start_x"]
            vol = round(actual_len * cross_section, 4)
            wt_limit = round(max_payload * s["volume_ratio"], 2)

            zones.append(
                ZoneData(
                    stop_id=s["stop_id"],
                    zone_start_x=s["zone_start_x"],
                    zone_end_x=s["zone_end_x"],
                    zone_volume=vol,
                    zone_weight_limit=wt_limit,
                    volume_ratio=round(s["volume_ratio"], 4),
                    zone_length=round(actual_len, 4),
                    sequence=s["sequence"],
                )
            )

        return StopZoneCalculationResponse(zones=zones)
