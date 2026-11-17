"""Layer-first scanner model: all 256 coordinates on each layer, once each."""
from pathlib import Path
source=(Path(__file__).resolve().parents[1]/'src/main/java/de/fallingsea/FallingSea.java').read_text()
assert 'while(used<quantum && work.y>=bottom)' in source
assert 'if(work.column==256)' in source
assert 'work.column=0;' in source
assert 'work.y--;' in source
assert 'if(work.y<bottom)' in source
for top,bottom in [(63,56),(63,15),(0,-64),(-63,-64)]:
    y=top
    column=0
    positions=[]
    while y>=bottom:
        x=column&15
        z=column>>4
        positions.append((x,y,z))
        column+=1
        if column==256:
            column=0
            y-=1
    assert len(positions)==(top-bottom+1)*256
    assert len(set(positions))==len(positions)
    assert positions[0]==(0,top,0)
    assert positions[255]==(15,top,15)
    assert positions[256]==(0,top-1,0)
print('Layer-first scanner coverage tests passed')
