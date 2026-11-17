"""SEA_LEVEL flow-removal decision tests and source contracts (no Minecraft runtime)."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'src/main/java/de/fallingsea/FallingSea.java').read_text()
assert 'fluid.is(FluidTags.WATER) && !fluid.isSource()' in source
assert 'cfg.suppressInfiniteSources && flowingWater && (water || aquaticWater)' in source
assert 'work.world.setBlock(pos,Blocks.AIR.defaultBlockState(),3)' in source
assert 'if ("SEA_LEVEL".equals(cfg.dryingMode))' in source

def remove_sea(y,target,start,prevent_refill,water_level):
    return (target<y<=start) or (prevent_refill and y<=target and water_level>0)

for target in [-64, 0, 40, 59, 62, 63]:
    for y in [-64,-20,0,1,39,40,41,58,59,60,61,62,63,64]:
        for water_level in range(0,16):
            for prevent_refill in (False,True):
                actual=remove_sea(y,target,63,prevent_refill,water_level)
                if y<=target and water_level==0: assert not actual, (y,target)
                if y<=target and water_level>0: assert actual==prevent_refill
                if target<y<=63: assert actual
                if y>63: assert not actual
print('Flowing water level/source model checks passed.')
