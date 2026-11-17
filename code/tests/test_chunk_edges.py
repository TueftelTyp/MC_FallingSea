"""Regressions for layer edge ordering and automatic level barrier (source contract + model)."""
from pathlib import Path
import re
src=(Path(__file__).resolve().parents[1]/'src/main/java/de/fallingsea/FallingSea.java').read_text()
assert 'BORDER_FIRST_COLUMNS[work.column]' in src
assert 'boolean edge = x==0 || x==15 || z==0 || z==15;' in src
assert 'if (edge == (pass==0)) result[index++]=(z<<4)|x;' in src
assert 'if (save.elapsedTicks < interval) save.elapsedTicks++;' in src
assert 'save.elapsedTicks >= interval && worker.readyForNextAutomaticStep(save.level)' in src
assert 'return scannedLevel==target && surfaceQueue.isEmpty();' in src
assert 'countdownScanReady' in src

columns=[]
for edge_pass in (True,False):
    for z in range(16):
        for x in range(16):
            is_edge=x in (0,15) or z in (0,15)
            if is_edge==edge_pass:
                columns.append((x,z))
assert len(columns)==256 and len(set(columns))==256
assert all(x in (0,15) or z in (0,15) for x,z in columns[:60])
assert all(x not in (0,15) and z not in (0,15) for x,z in columns[60:])
assert columns.index((0,0))<columns.index((1,1))
for top,bottom in ((63,60),(63,42),(0,-64)):
    positions=[(x,y,z) for y in range(top,bottom-1,-1) for x,z in columns]
    assert len(positions)==(top-bottom+1)*256
    assert len(set(positions))==len(positions)

# Only visible surface work is a hard barrier. Elevated source/flow
# maintenance must not block automatic lowering indefinitely.
def can_advance(scanned,target,surface,elevated,flow):
    return scanned==target and not surface
assert not can_advance(61,60,False,False,False)
assert not can_advance(60,60,True,False,False)
assert can_advance(60,60,False,False,False)
assert can_advance(60,60,False,True,False)
assert can_advance(60,60,False,False,True)
print('Chunk-edge order, horizontal coverage and surface-only barrier model passed')
