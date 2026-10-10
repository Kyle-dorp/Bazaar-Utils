"""Build enchant family -> max tier craftable on an anvil, from the Hypixel SkyBlock wiki (each enchant page says
"can be combined on an Anvil up to <Enchant> <roman>"). Families the wiki doesn't state are listed, not guessed."""
import json, os, re, time, urllib.parse, urllib.request

ROMAN = {'I': 1, 'II': 2, 'III': 3, 'IV': 4, 'V': 5, 'VI': 6, 'VII': 7, 'VIII': 8, 'IX': 9, 'X': 10}
names = json.load(open(os.path.expandvars(r'%APPDATA%\.minecraft\config\bazaarutils\bazaar-resources.json'), encoding='utf-8'))
bz = json.load(urllib.request.urlopen(urllib.request.Request('https://api.hypixel.net/skyblock/bazaar', headers={'User-Agent': 'x'}), timeout=30))['products']

families = {}
for pid in bz:
    m = re.match(r'^ENCHANTMENT_(.+)_(\d+)$', pid)
    if m:
        families.setdefault(m.group(1), []).append(int(m.group(2)))


def display_base(fam):
    for t in sorted(families[fam]):
        n = names.get('ENCHANTMENT_%s_%d' % (fam, t))
        if n:
            return re.sub(r'\s+[IVXL]+$', '', n)
    return fam.replace('ULTIMATE_', '').replace('_', ' ').title()


def wikitext(page):
    url = 'https://hypixelskyblock.minecraft.wiki/api.php?' + urllib.parse.urlencode(
        {'action': 'parse', 'page': page, 'prop': 'wikitext', 'format': 'json', 'redirects': 1})
    try:
        d = json.load(urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'BazaarUtils-research'}), timeout=20))
        return None if 'error' in d else (d['parse']['title'], d['parse']['wikitext']['*'])
    except Exception:
        return None


caps, unresolved = {}, {}
for fam in sorted(families):
    base = display_base(fam)
    tried = [base, 'Ultimate ' + base, fam.replace('_', ' ').title(), base + ' (Enchantment)']
    found = None
    for page in dict.fromkeys(tried):
        r = wikitext(page)
        time.sleep(0.25)
        if not r:
            continue
        text = r[1]
        # 1) the explicit sentence, in its several spellings
        m = re.search(r'can be combined (?:on|in) an? (?:\{\{Item\|Anvil\}\}|\[\[Anvil\]\]),? up to \{\{Ench\|(?:book=1\|)?([^}]+?)\s+([IVX]+)\}\}', text)
        if m:
            found = (ROMAN[m.group(2)], r[0], m.group(0))
            break
        # 2) the Obtaining table: highest tier whose source is the Enchantment Table (wiki, Enchanted Book page:
        #    books with levels not obtainable from an Enchantment Table cannot be combined to increase their level)
        best = 0
        for row in re.finditer(r'\|\s*\{\{Ench\|[^}|]*?\s([IVX]+)(?:-([IVX]+))?\}\}\s*\|\|([^\n]*(?:\n\*[^\n]*)*)', text):
            if 'Enchantment Table' in row.group(3):
                hi = ROMAN.get(row.group(2) or row.group(1), 0)
                best = max(best, hi)
        if best:
            found = (best, r[0], 'Obtaining table: Enchantment Table up to tier %d' % best)
            break
    if found:
        caps[fam] = {'maxCraftTier': found[0], 'wikiPage': found[1], 'source': found[2]}
    else:
        unresolved[fam] = {'displayName': base, 'tiersOnBazaar': sorted(families[fam])}
    print('%-28s %s' % (fam, found[0] if found else 'UNRESOLVED'), flush=True)

json.dump({'source': 'hypixelskyblock.minecraft.wiki, fetched %s' % time.strftime('%Y-%m-%d'), 'caps': caps, 'unresolved': unresolved},
          open('book-craft-caps.json', 'w', encoding='utf-8'), indent=1)
print('\nresolved %d, unresolved %d' % (len(caps), len(unresolved)))
