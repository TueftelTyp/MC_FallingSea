"""Behavioral scan-scheduling model. Does not run Minecraft or test FluidState API."""
from collections import deque
from itertools import product
from pathlib import Path
source=(Path(__file__).resolve().parents[1]/'src/main/java/de/fallingsea/FallingSea.java').read_text()
assert 'final boolean isSurface=!surfaceQueue.isEmpty()' in source
assert 'flowQueue.addLast(new Work' in source
assert 'if(scannedLevel!=Integer.MIN_VALUE && target!=scannedLevel && surfaceQueue.isEmpty())' in source

# Every column is traversed once; top rows must be inspected before cleanup.
# Model player area of 121 chunks, water at Y=62 with startingHeight=63, target=56.
def simulate(n_chunks, start, target, cleanup, budget):
    surface=deque([(i,start,0) for i in range(n_chunks)])
    flows=deque()
    seen=set()
    first_surface_water_tick=None
    last_surface_tick=None
    tick=0
    while surface or flows:
        tick+=1
        remaining=budget
        while remaining and (surface or flows):
            issurface=bool(surface)
            q=surface if issurface else flows
            ident,y,column=q.popleft()
            bottom=target+1 if issurface else target-cleanup+1
            amount=min(remaining,256-column)
            if amount<=0: raise AssertionError('zero work')
            if issurface and y==start-1 and first_surface_water_tick is None:
                first_surface_water_tick=tick
            if issurface:
                for c in range(column,column+amount):
                    coord=(ident,y,c)
                    assert coord not in seen, coord
                    seen.add(coord)
            column+=amount
            remaining-=amount
            if column>=256:
                column=0
                y-=1
            if y<bottom:
                if issurface:
                    last_surface_tick=tick
                    if cleanup: flows.append((ident,target,0))
            else:
                q.append((ident,y,column))
        assert tick < 30000
    assert len(seen)==n_chunks*256*(start-target)
    assert first_surface_water_tick is not None
    assert last_surface_tick is not None
    return first_surface_water_tick,last_surface_tick,tick

for n,budget in product((1,2,4,25,49,121),(256,1000,5000,10000)):
    first,last,total=simulate(n,63,56,48,budget)
    assert first<=last<=total
    # With 121 chunks / 5k budget the first water layer must be reached quickly,
    # rather than waiting for 48 deep layers for every chunk.
    if (n,budget)==(121,5000):
        assert first<=15,first
        assert last<=50,last
print('121-chunk surface-first scan model and various budgets passed')

# Normal and malformed configuration value bounds: the runtime clamps these.
def clamp(value,lo,hi): return max(lo,min(hi,value))
for v in (-999, -65, -64, 0, 1, 48, 128, 129, 1000000):
    assert 0<=clamp(v,0,128)<=128
for v in (-999,0,1,20,256,5000,20000,1000000):
    assert 256<=clamp(v,256,20000)<=20000
print('Configuration boundaries passed')
