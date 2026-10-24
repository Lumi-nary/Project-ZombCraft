"""Approve the verified local PzCraft build in the private ZombieBuddy profile.

The owner authorized agents to handle PzCraft build approvals on 2026-10-04.
Only this mod's exact installed hash is approved; normal loader policy remains
prompt, and the owner's ordinary .zombie_buddy profile is never edited.
"""
import hashlib
import json
import shutil
import time
from pathlib import Path
import pzcore as p


def approve():
    built = p.ROOT / 'pz-mod/build/libs/Project-ZombCraft.jar'
    installed = p.ZOMBOID / 'mods/Project-ZombCraft/42/media/java/Project-ZombCraft.jar'
    digest = hashlib.sha256(built.read_bytes()).hexdigest()
    assert hashlib.sha256(installed.read_bytes()).hexdigest() == digest, 'installed jar differs from local build'
    profile = Path.home() / '.pzcraft/zb-dev/mod_approvals.json'
    state = json.loads(profile.read_text(encoding='utf-8'))
    assert state.get('formatVersion') == 2 and isinstance(state.get('mods'), list), 'unrecognized approval format'
    entry = next((m for m in state['mods'] if m.get('id') == 'Project-ZombCraft' and m.get('jar_hash') == digest), None)
    if entry and entry.get('decision') is True:
        return {'hash': digest, 'profile': str(profile), 'status': 'already approved'}
    backup = p.ROOT / '.pzcraft-backups' / ('pzcraft-approvals-'+time.strftime('%Y%m%d-%H%M%S')+'-'+digest[:8]+'.json')
    backup.parent.mkdir(exist_ok=True)
    shutil.copy2(profile, backup)
    if entry is None:
        state['mods'].append({'id': 'Project-ZombCraft', 'jar_hash': digest, 'decision': True})
    else:
        entry['decision'] = True
    temporary = profile.with_suffix('.json.pzcraft-tmp')
    temporary.write_text(json.dumps(state, indent=2)+'\n', encoding='utf-8')
    temporary.replace(profile)
    return {'hash': digest, 'profile': str(profile), 'status': 'approved exact local build', 'backup': str(backup)}


if __name__ == '__main__':
    print(json.dumps(approve(), indent=2))
