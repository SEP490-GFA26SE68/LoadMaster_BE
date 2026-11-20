"""Packing-profile text is distinct from solver rotation_type IDs.

PackageFormDialog serializes profile IDs in LWH,LHW,WLH,WHL,HLW,HWL order.
Solver rotation IDs stay LWH,WLH,LHW,WHL,HLW,HWL. Never reuse IDs blindly.
"""
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode

PROFILE_ID_TO_ROTATION = {'0': 0, '1': 2, '2': 1, '3': 3, '4': 4, '5': 5}
NAME_TO_ROTATION = {'LWH': 0, 'WLH': 1, 'LHW': 2, 'WHL': 3, 'HLW': 4, 'HWL': 5}


def parse_profile_orientation(value):
    """None/blank adds no restriction; explicit CSV is a whitelist, errors fail closed.

    Support the current numeric producer and unambiguous symbolic legacy values.
    Lists/JSON, mixed alphabets, invented aliases and lossy coercions are not supported.
    """
    if value is None:
        return None
    if not isinstance(value, str) or len(value) > 100:
        raise AppException(ErrorCode.PACKING_PROFILE_ORIENTATION_INVALID)
    if not value.strip():
        return None
    tokens = [item.strip() for item in value.split(',')]
    if all(token in PROFILE_ID_TO_ROTATION for token in tokens):
        return {PROFILE_ID_TO_ROTATION[token] for token in tokens}
    upper = [token.upper() for token in tokens]
    if all(token in NAME_TO_ROTATION for token in upper):
        return {NAME_TO_ROTATION[token] for token in upper}
    raise AppException(ErrorCode.PACKING_PROFILE_ORIENTATION_INVALID)
