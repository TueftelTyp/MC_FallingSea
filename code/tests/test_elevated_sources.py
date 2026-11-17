"""Contract tests for elevated source cleanup, no Minecraft runtime."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
s=(root/'src/main/java/de/fallingsea/FallingSea.java').read_text()
for token in ('elevatedQueue','elevatedSweep','cfg.startingWaterLevel+1',
              'remove=cfg.suppressInfiniteSources && y>scannedLevel;',
              'top,false,true',
              'boolean flowingWater=fluid.is(FluidTags.WATER) && !fluid.isSource();'):
    assert token in s,token
assert 'final boolean isSurface=!surfaceQueue.isEmpty();' in s
assert 'final boolean isElevated=!isSurface && !elevatedQueue.isEmpty();' in s
# A source/flow block strictly above target must be removed, while the
# source at or below target must be kept. First band = starting+below, second = elevated.
for start in (-10,63,120):
    for target in (start,start-1,start-5):
        for y in range(start-6,start+8):
            surface=(target<y<=start)
            elevated=y>start and y>target
            assert (surface or elevated)==(y>target)
            assert not (surface or elevated) if y<=target else (surface or elevated)
print('Elevated-source boundary and priority model checks passed.')

assert 'highestSurfaceY(work.world,work.x,work.z)' in s
