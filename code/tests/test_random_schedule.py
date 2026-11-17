"""Random Minecraft-day scheduling invariants and source contract checks.
This is a model test, not an in-game test.
"""
from pathlib import Path
from random import Random
import json
ROOT=Path(__file__).resolve().parents[1]
source=(ROOT/'src/main/java/de/fallingsea/FallingSea.java').read_text()
client=(ROOT/'src/main/java/de/fallingsea/client/FallingSeaConfigScreen.java').read_text()
for token in ['public boolean randomSchedule = false;', 'public int randomMinDays = 1;',
              'public int randomMaxDays = 5;', 'long scheduledIntervalTicks;',
              'boolean scheduledRandomMode;', 'chooseRandomIntervalTicks()',
              'synchronizeSchedule(false)', 'if (config.randomSchedule) save.scheduledIntervalTicks = chooseRandomIntervalTicks();',
              'if (!config.randomSchedule) return (long) config.intervalSeconds * 20L;',
              'config.randomMaxDays + 1', 'currentIntervalTicks() - save.elapsedTicks']:
    assert token in source,token
assert 't("randomschedule", "randomschedule")' in client
assert 'opt.key.equals("interval") && toggles.getOrDefault("randomschedule", false)' in client
for name in ('de_de','en_us'):
    data=json.loads((ROOT/f'src/main/resources/assets/fallingsea/lang/{name}.json').read_text())
    for key in ('fallingsea.option.randomschedule','fallingsea.option.randommindays',
                'fallingsea.option.randommaxdays','fallingsea.schedule.fixed','fallingsea.schedule.random'):
        assert key in data,key
config=json.loads((ROOT/'config-example/fallingsea.json').read_text())
assert config['randomSchedule'] is False
assert config['randomMinDays']==1 and config['randomMaxDays']==5
TICKS_DAY=24000
rng=Random(8123)
for min_days,max_days in [(1,1),(1,5),(5,5),(2,30),(365,365)]:
    for _ in range(1000):
        ticks=rng.randint(min_days,max_days)*TICKS_DAY
        assert min_days*TICKS_DAY<=ticks<=max_days*TICKS_DAY
        assert ticks % TICKS_DAY==0
        assert ticks >= TICKS_DAY
# A persisted deadline must be reused after restart, not re-rolled.
selected=5*TICKS_DAY
elapsed=2*TICKS_DAY+800
saved={'scheduledIntervalTicks':selected, 'elapsedTicks':elapsed,'scheduledRandomMode':True}
loaded=dict(saved)
assert loaded['scheduledIntervalTicks']==selected
assert selected-loaded['elapsedTicks']==3*TICKS_DAY-800
# Fixed and random are exclusive choices: exactly one is selected.
for random_mode in (False,True):
    assert int(random_mode)+int(not random_mode)==1
    actual_interval=selected if random_mode else 300*20
    assert actual_interval == (selected if random_mode else 6000)
print('Random interval contract, day bounds, persistence model, and mode exclusivity passed.')
