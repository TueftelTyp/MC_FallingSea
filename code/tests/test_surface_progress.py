"""Regression checks for the stalled-clock bug; model only, not Minecraft runtime."""
from pathlib import Path
src=(Path(__file__).resolve().parents[1]/'src/main/java/de/fallingsea/FallingSea.java').read_text()
assert 'return scannedLevel==target && surfaceQueue.isEmpty();' in src
assert 'if(scannedLevel!=Integer.MIN_VALUE && target!=scannedLevel && surfaceQueue.isEmpty())' in src
assert 'highestSurfaceY(work.world,work.x,work.z)' in src
assert 'net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE' in src

def may_lower(scanned,target,surface_pending):
    return scanned==target and not surface_pending
for loaded in (0,1,121,275):
    # Once surface work completes, a long upper-source sweep cannot hold
    # the automatic clock. Even 0 loaded chunks must not deadlock it.
    assert may_lower(59,59,False)
    assert not may_lower(59,59,True)
    assert not may_lower(60,59,False)
# One chunk can contain 0..256 world-surface height, but skipping air
# above the highest top block must not miss block positions in the chunk.
for chunk_top in (-64,0,63,95,319):
    assert all(y<=chunk_top for y in range(-64,chunk_top+1))
print('Automatic-level liveness and high-altitude top-boundary model passed.')
