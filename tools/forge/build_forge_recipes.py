"""Collect every Dwarven Forge recipe from a local NotEnoughUpdates item repo (the copy Skyblocker keeps up to date in
config/skyblocker/item-repo is fine) into forge-recipes.json for the Forge flip list.

NEU stores forge recipes on the output item as {"type": "forge", "inputs": ["ID:qty", ...], "count", "overrideOutputId",
"duration"} with duration in SECONDS (drill upgrades really are 30s). Coins are an input named SKYBLOCK_COIN; they are moved
to their own field here. Display names are included for every id that appears (outputs and inputs), because the mod's
ItemNames only knows Bazaar products and AH items like drills would otherwise show as "Mithril Drill 2".

usage: python build_forge_recipes.py [items_dir] [out_file]
"""
import json, os, re, subprocess, sys

DEFAULT_ITEMS = os.path.join(os.environ.get('APPDATA', ''), '.minecraft', 'config', 'skyblocker', 'item-repo', 'items')
DEFAULT_OUT = os.path.join(os.path.dirname(__file__), '..', '..', 'src', 'main', 'resources', 'assets', 'bazaarutils',
                           'forge-recipes.json')
COIN = 'SKYBLOCK_COIN'

items_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_ITEMS
out_file = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT


def clean_name(raw):
    name = re.sub('§.', '', raw or '').strip()
    return re.sub(r'^\[Lvl \{?\w+\}?\] ', '', name)  # pets: "[Lvl {LVL}] Ammonite" -> "Ammonite"


items = {}
for f in os.listdir(items_dir):
    if f.endswith('.json'):
        with open(os.path.join(items_dir, f), encoding='utf-8') as fh:
            d = json.load(fh)
        items[d.get('internalname', f[:-5])] = d

recipes = []
for item_id, d in sorted(items.items()):
    for r in d.get('recipes', []):
        if r.get('type') != 'forge':
            continue
        out_id = r.get('overrideOutputId') or item_id
        coins, inputs = 0, {}
        for entry in r.get('inputs', []):
            iid, _, qty = entry.rpartition(':') if entry.count(':') else (entry, '', '1')
            qty = float(qty)
            if iid == COIN:
                coins += qty
            else:
                inputs[iid] = inputs.get(iid, 0) + qty
        seconds = float(r.get('duration', 0))
        assert seconds > 0, f'{out_id}: forge recipe without a duration'
        recipes.append({'id': out_id, 'count': int(r.get('count', 1) or 1), 'seconds': seconds, 'coins': coins,
                        'inputs': {k: (int(v) if v == int(v) else v) for k, v in sorted(inputs.items())}})

referenced = {r['id'] for r in recipes} | {i for r in recipes for i in r['inputs']}
names = {i: clean_name(items[i]['displayname']) for i in sorted(referenced) if i in items and items[i].get('displayname')}

try:
    commit = subprocess.check_output(['git', '-C', os.path.dirname(os.path.abspath(items_dir)), 'log', '-1', '--format=%h %cs'],
                                     text=True).strip()
except Exception:
    commit = 'unknown'

os.makedirs(os.path.dirname(os.path.abspath(out_file)), exist_ok=True)
with open(out_file, 'w', encoding='utf-8') as fh:
    json.dump({'source': 'NotEnoughUpdates-REPO items/*.json (type=forge), commit ' + commit,
               'recipes': recipes, 'names': names}, fh, separators=(',', ':'), ensure_ascii=False)

print('recipes:', len(recipes), '| with coins:', sum(1 for r in recipes if r['coins']),
      '| ids:', len(referenced), '| named:', len(names), '| missing names:', sorted(referenced - set(names))[:10])
print('source commit:', commit, '->', os.path.abspath(out_file))
