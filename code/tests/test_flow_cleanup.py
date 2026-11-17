"""Boundary tests for the flow cleanup search band (not an engine simulation)."""
for target in (-64, -20, 0, 59, 63, 319):
    for depth in (0, 1, 48, 128):
        for minimum in (-64, 0):
            bottom = max(minimum, target - depth + 1)
            assert bottom >= minimum
            if depth > 0 and target >= minimum:
                for y in range(max(minimum, target - depth + 1), target + 1):
                    assert y >= bottom
            if depth == 0:
                assert bottom == max(minimum, target + 1)
print("Flow cleanup depth boundary checks passed.")
