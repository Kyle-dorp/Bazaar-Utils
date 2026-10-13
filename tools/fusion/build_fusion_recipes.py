"""Collect every attribute shard fusion recipe from the Hypixel SkyBlock wiki's Module:Fusion/<Shard>.json pages
(the data behind each shard page's fusion tables). Each page's output_recipes lists every way to make that shard, so
the union over all pages is the full recipe set. Output: fusion-recipes.json, compact, with the source revision times."""
import json, time, urllib.parse, urllib.request

API = 'https://hypixelskyblock.minecraft.wiki/api.php?'


def api(q):
    url = API + urllib.parse.urlencode({**q, 'format': 'json'})
    for attempt in range(4):
        try:
            return json.load(urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'BazaarUtils-fusion-research'}), timeout=120))
        except Exception:
            time.sleep(2 + attempt * 3)
    raise RuntimeError('failed: ' + url)


titles = json.load(open('fusion_titles.json'))
neu = json.load(open('attribute_shards.json', encoding='utf-8'))['attributes']
name_to_id = {a['displayName'] + ' Shard': a['bazaarName'] for a in neu}

recipes, revisions, unknown_names = set(), {}, set()
for i, title in enumerate(titles):
    d = api({'action': 'query', 'prop': 'revisions', 'rvprop': 'content|timestamp', 'rvslots': 'main', 'titles': title})
    page = list(d['query']['pages'].values())[0]
    rev = page['revisions'][0]
    data = json.loads(rev['slots']['main']['*'])
    revisions[data['name']] = rev['timestamp']
    out_id = name_to_id.get(data['name'])
    if out_id is None:
        unknown_names.add(data['name'])
    for r in data.get('output_recipes', []):
        recipes.add((r['fusion_type'], r['left_skyblock_id'], r['left_count'], r['right_skyblock_id'], r['right_count'],
                     out_id or data['name'], r['output_count']))
    if i % 25 == 0:
        print('%d/%d %s (%d recipes so far)' % (i, len(titles), data['name'], len(recipes)), flush=True)
    time.sleep(0.3)

rows = sorted(recipes)
json.dump({
    'source': 'hypixelskyblock.minecraft.wiki Module:Fusion/*.json (output_recipes), collected %s' % time.strftime('%Y-%m-%d'),
    'newestPageEdit': max(revisions.values()),
    'columns': ['type', 'left', 'leftCount', 'right', 'rightCount', 'output', 'outputCount'],
    'recipes': [list(r) for r in rows],
}, open('fusion-recipes.json', 'w', encoding='utf-8'), separators=(',', ':'))
print('\nrecipes:', len(rows), '| by type:', {t: sum(1 for r in rows if r[0] == t) for t in sorted({r[0] for r in rows})})
print('page names not in NEU list:', sorted(unknown_names))
print('newest page edit:', max(revisions.values()))
