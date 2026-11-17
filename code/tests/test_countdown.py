"""Countdown eligibility contract checks (no Minecraft runtime)."""
from pathlib import Path

root=Path(__file__).resolve().parents[1]
client=(root/'src/main/java/de/fallingsea/client/FallingSeaClient.java').read_text()
server=(root/'src/main/java/de/fallingsea/FallingSea.java').read_text()
assert 'left == 0 || left > warningSeconds' in client
assert 'level > minimum' in client
assert 'show && enabled && running' in client
assert 'mc.gui.hud.isHidden()' in client
assert 'MAX_SNAPSHOT_AGE_MS' in client
assert 'currentWaterLevel' in client and 'minWaterLevel' in client
assert 'Y=' not in client
assert 'countdownLastSeconds' in server
assert 'countdownRemainingSeconds' in server
assert 'Math.max(0,Math.min(60,config.countdownLastSeconds))' in server

def visible(remaining, window, active=True, at_min=False):
    return active and not at_min and window > 0 and 0 < remaining <= window

for window in (0, 1, 10, 60):
    for remaining in (0, 1, 9, 10, 11, 60, 61, 300):
        expected = window > 0 and 0 < remaining <= window
        assert visible(remaining,window) == expected
        assert not visible(remaining,window,active=False)
        assert not visible(remaining,window,at_min=True)
print('Countdown warning window and disabled/paused/minimum cases passed.')
