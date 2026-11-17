"""Rotation codes are a whitelist, never a truthiness-based permission default."""
from typing import Annotated
from pydantic import BeforeValidator


def parse_rotation(value):
    # Keep legacy canonical numeric strings, but never truncate floats or accept bools.
    if type(value) is int and 0 <= value <= 5:
        return value
    if type(value) is str and value in ("0", "1", "2", "3", "4", "5"):
        return int(value)
    raise ValueError("rotation must be an integer code 0..5 (or its canonical numeric string)")


RotationCode = Annotated[int, BeforeValidator(parse_rotation)]


def allowed_codes(package):
    allowed = {parse_rotation(code) for code in package.allowed_rotations}
    return allowed if package.rotation_allowed else allowed & {0}


def orientations(package):
    l, w, h = float(package.l), float(package.w), float(package.h)
    dimensions = [(l, w, h), (w, l, h), (l, h, w), (w, h, l), (h, l, w), (h, w, l)]
    allowed = allowed_codes(package)
    return [(*dims, code) for code, dims in enumerate(dimensions) if code in allowed]


def matches_rotation(package, placement, epsilon=1e-6):
    try:
        code = parse_rotation(placement.rotation_type)
        actual = (placement.packed_l, placement.packed_w, placement.packed_h)
        return any(code == expected[3] and all(abs(a-b) <= epsilon for a,b in zip(actual, expected[:3]))
                   for expected in orientations(package))
    except (ValueError, TypeError):
        return False


def validate_result_rotations(problem, result):
    """Check the exact geometry before the runner hands it to persistence."""
    metadata = {str(p.id): p for p in problem.packages}
    for placement in result.placements:
        package = metadata.get(str(placement.package_id))
        if package is None or not matches_rotation(package, placement):
            raise ValueError("Result contains a forbidden rotation or inconsistent packed dimensions")
