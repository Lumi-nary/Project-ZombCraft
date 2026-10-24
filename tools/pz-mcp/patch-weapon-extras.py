"""Adds `categories`, `swingAnim` and `maxHitCount` to every weapon in ~/.pzcraft/items/items.json, taken from the running PZ.

pz-mod's ItemExporter writes the same three fields from its next build on; the exporter only rewrites items.json when it is
missing or from another game version, so until that jar is installed this brings the existing file to the same shape.
Backs the file up first (items.json.before-weapon-extras-<stamp>), changes only weapon entries, and does nothing when the
file already matches. Needs PZ in a world (control API).
"""
import json
import shutil
import time
from pathlib import Path
import pzcore as p

FILE = Path.home() / '.pzcraft' / 'items' / 'items.json'

DUMP = r'''
local out = {}
local items = ScriptManager.instance:getAllItems()
for i = 0, items:size()-1 do
  local it = items:get(i)
  local ok, line = pcall(function()
    if tostring(it:getItemType()):lower() ~= "base:weapon" then return nil end
    local cats = {}
    local set = it:getWeaponCategories()
    if set then local itr = set:iterator(); while itr:hasNext() do table.insert(cats, tostring(itr:next())) end end
    return it:getFullName() .. "|" .. table.concat(cats, ",") .. "|" .. tostring(it:getSwingAnim()) .. "|" .. tostring(it:getMaxHitCount())
  end)
  if ok and line then table.insert(out, line) end
end
return table.concat(out, ";")
'''


def module_free(name):
    t = name.lower()
    return t.split(':', 1)[1] if ':' in t else t


def main():
    rows = p.lua(DUMP)['results'][0].split(';')
    extras = {}
    for row in rows:
        full, cats, swing, hits = row.split('|')
        extras[full] = (sorted(module_free(c) for c in cats.split(',') if c), swing, int(hits))
    root = json.loads(FILE.read_text(encoding='utf-8'))
    changed = 0
    missing = []
    for item in root['items']:
        w = item.get('weapon')
        if w is None:
            continue
        if item['id'] not in extras:
            missing.append(item['id'])
            continue
        cats, swing, hits = extras[item['id']]
        new = {'categories': cats, 'swingAnim': swing, 'maxHitCount': hits}
        if any(w.get(k) != v for k, v in new.items()):
            w.update(new)
            changed += 1
    print(f'{len(extras)} weapons from PZ, {changed} entries changed, {len(missing)} catalogue weapons PZ did not list: {missing[:5]}')
    if changed:
        backup = FILE.with_name(f'items.json.before-weapon-extras-{time.strftime("%Y%m%d-%H%M%S")}')
        shutil.copy2(FILE, backup)
        FILE.write_text(json.dumps(root, separators=(',', ':'), ensure_ascii=False), encoding='utf-8')
        print('backup:', backup)


if __name__ == '__main__':
    main()
