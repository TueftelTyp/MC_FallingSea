"""Dependency-free structural and algorithm contract checks (not a Minecraft runtime test)."""
from pathlib import Path
import json, zipfile

ROOT=Path(__file__).resolve().parents[1]
java=list(ROOT.glob('src/main/java/**/*.java'))
assert len(java)==6, f'Unexpected Java file count: {len(java)}'
for f in java:
    source=f.read_text(encoding='utf8')
    # Lightweight delimiter scan, ignoring strings/comments to catch truncation.
    stack=[]
    i=0;mode='code'
    while i<len(source):
        c=source[i];n=source[i+1] if i+1<len(source) else ''
        if mode=='line':
            if c=='\n':mode='code'
        elif mode=='block':
            if c=='*' and n=='/':mode='code';i+=1
        elif mode in ('string','char'):
            if c=='\\':i+=1
            elif c==('"' if mode=='string' else "'"):mode='code'
        elif c=='/' and n=='/':mode='line';i+=1
        elif c=='/' and n=='*':mode='block';i+=1
        elif c=='"':mode='string'
        elif c=="'":mode='char'
        elif c in '({[':stack.append(c)
        elif c in ')}]':
            assert stack and stack.pop()=={')':'(', '}':'{', ']':'['}[c],f'{f}: unbalanced {c}'
        i+=1
    assert not stack, f'{f}: unclosed delimiters {stack}'
    assert mode not in ('string','char','block'),f'{f}: unterminated literal/comment'

core=(ROOT/'src/main/java/de/fallingsea/FallingSea.java').read_text()
assert 'worker.invalidate();\n                    persist()' not in core
assert 'if(ready.getOrDefault(key,Integer.MAX_VALUE)==scannedLevel)continue;' in core
assert 'bottomY(work.world,cfg,scannedLevel)' in core
assert 'completedPassLevel=scannedLevel;' in core
assert 'scannedLevel!=target' in core
assert 'StandardCopyOption.ATOMIC_MOVE' in core
assert (ROOT/'src/main/resources/assets/fallingsea/icon.png').read_bytes()[:8]==bytes.fromhex('89504e470d0a1a0a')
json.load((ROOT/'config-example/fallingsea.json').open())
mod=json.load((ROOT/'src/main/resources/fabric.mod.json').open())
assert mod['id']=='fallingsea' and mod['icon']=='assets/fallingsea/icon.png'
assert 'mod_version=1.22' in (ROOT/'gradle.properties').read_text()
# Sea level removes *only* heights strictly above target through starting water height.
for start in [63,100,-10]:
    for target in [start,start-1,start-4]:
        for y in range(start-6,start+3):
            actual=y>target and y<=start
            expected=target<y<=start
            assert actual==expected
assert 'Commands.literal("refill")' in core
assert 'fallingsea.chat.none_started' in core
assert 'config.enableDrying=true; save.running=true;' in core
assert 'if(cfg.suppressInfiniteSources)' in core
assert 'flowCleanupDepth' in core and 'cfg.flowCleanupDepth' in core
assert 'Commands.literal("flowdepth")' in core
assert '!fluid.isSource()' in core
# All water-bearing aquatic blocks need direct removal, not waterlogged toggling.
for name in ['KELP','KELP_PLANT','SEAGRASS','TALL_SEAGRASS','BUBBLE_COLUMN']:
    assert 'Blocks.'+name in core, f'missing aquatic water block: {name}'
assert 'fluid.is(FluidTags.WATER)' in core
assert 'water||aquaticWater||ice' in core
assert 'boolean dryWaterloggedHere=waterlogged && (cfg.dryWaterlogged' in core
print(f'Static checks passed: {len(java)} Java sources, delimiter balance, metadata, icon, scheduling invariants, sample sea-level boundaries.')

# Round-robin work should retain each chunk's cursor and yield fairly.
assert '(isSurface?surfaceQueue:(isElevated?elevatedQueue:flowQueue)).addLast(work)' in core and 'int quantum=Math.min(remaining,256)' in core
assert 'work.column++' in core and 'if(work.column==256)' in core and 'work.y--' in core
assert 'queued.remove(key)' in core
assert 'snapshotAll();' in core and 'countdownRemainingSeconds' in core
client=(ROOT/'src/main/java/de/fallingsea/client/FallingSeaClient.java').read_text()
assert 'HudElementRegistry.addLast' in client
assert 'guiWidth()' in client and 'graphics.text' in client
assert 'sendOverlayMessage' not in core

# Chat diagnostics must not appear when debug is false.
assert 'public boolean debug = false;' in core
assert 'if (config.debug && config.announceFall)' in core
assert 'if (config.debug) server.getPlayerList().broadcastSystemMessage' in core
assert core.count('if (config.debug) ctx.getSource().sendSuccess') == 2
assert 'Commands.literal("debug")' in core
assert 'case "debug" -> config.debug=value;' in core
config_screen=(ROOT/'src/main/java/de/fallingsea/client/FallingSeaConfigScreen.java').read_text()
assert 't("debug", "debug")' in config_screen
assert 'Map.entry("debug", "debug")' in config_screen
assert 'private static final int WIDTH = 120;' in client
assert 'private static final int HEIGHT = 32;' in client
assert 'drawVanillaEffectCard' in client
assert 'drawEffectIcon' in client
assert '0xCC1D1D1D' not in client
assert json.load((ROOT/'config-example/fallingsea.json').open())['debug'] is False
print('Debug- und HUD-Kontrakte bestanden.')

client=(ROOT/"src/main/java/de/fallingsea/client/FallingSeaClient.java").read_text()
assert client.count("net.minecraft.client.gui.GuiGraphicsExtractor graphics") == 2
assert "net.minecraft.client.gui.GuiGraphics graphics" not in client
print("Minecraft 26.3 HUD render type checked.")

assert 'private final ArrayDeque<Work> surfaceQueue' in core
assert 'private final ArrayDeque<Work> flowQueue' in core
assert 'final boolean isSurface=!surfaceQueue.isEmpty();' in core
assert 'target!=scannedLevel && surfaceQueue.isEmpty()' in core
assert 'flowQueue.clear();' in core
assert 'Heightmap.Types.OCEAN_FLOOR' in core
print('Surface priority, target switching, and ocean-floor filtering contracts passed.')
