"""Regression model for retaining solid blocks but draining their water."""
from pathlib import Path
source=(Path(__file__).resolve().parents[1]/'src/main/java/de/fallingsea/FallingSea.java').read_text()
assert 'cfg.suppressInfiniteSources && y>scannedLevel' in source
assert 'dryWaterloggedHere' in source
assert 'flowingWater && (water || aquaticWater)' in source
assert 'block.setValue(BlockStateProperties.WATERLOGGED,false)' in source

def drain_waterlogged(waterlogged,explicit,prevent_refill,y,target):
    return waterlogged and (explicit or (prevent_refill and y>target))
for y in (40,41,42,63,120):
    for target in (41,63):
        for explicit in (False,True):
            for prevention in (False,True):
                for wet in (False,True):
                    result=drain_waterlogged(wet,explicit,prevention,y,target)
                    assert not result or wet
                    if wet and prevention and y>target: assert result
                    if wet and not explicit and y<=target: assert not result
                    if wet and not prevention and not explicit: assert not result
print('Waterlogged source preservation / drainage truth table passed.')
