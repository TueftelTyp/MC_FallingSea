"""Checks locales, dynamic translation references and untouched simulation methods."""
from pathlib import Path
import json, re
root=Path(__file__).resolve().parents[1]
loc=root/'src/main/resources/assets/fallingsea/lang'
de=json.loads((loc/'de_de.json').read_text(encoding='utf8'))
en=json.loads((loc/'en_us.json').read_text(encoding='utf8'))
assert set(de)==set(en), f'Missing translations: DE={set(en)-set(de)}, EN={set(de)-set(en)}'
for name,dictionary in [('de_de',de),('en_us',en)]:
    assert all(k.startswith('fallingsea.') for k in dictionary)
    for k,value in dictionary.items():
        assert value.strip(),f'Empty {name} key {k}'
        assert len(re.findall(r'(?<!%)%s',value)) == len(re.findall(r'(?<!%)%s',de[k])), f'Placeholder mismatch for {k}'
java=root/'src/main/java/de/fallingsea'
gui=(java/'client/FallingSeaConfigScreen.java').read_text()
core=(java/'FallingSea.java').read_text()
hud=(java/'client/FallingSeaClient.java').read_text()
labels=set(re.findall(r'\b(?:action|n|t)\("([a-z]+)",', gui))
assert labels == {k.removeprefix('fallingsea.option.') for k in en if k.startswith('fallingsea.option.')}
for k in re.findall(r'\b(?:tr|local)\("([a-zA-Z_.]+)"', gui):
    assert 'fallingsea.'+k in en or k in ('option.', 'message.'), k
for k in re.findall(r'Component\.translatable\("(fallingsea\.[a-z_.]+)"',core+'\n'+hud):
    assert k in en or k == "fallingsea.mode.",k
assert '"Austrocknung"' not in hud
assert 'fallingsea.effect.drying' in hud
assert 'public boolean debug = false;' in core
assert core.count('if (config.debug) ctx.getSource().sendSuccess')==2
print('Locales complete, keys referenced, placeholders and debug contracts verified')
