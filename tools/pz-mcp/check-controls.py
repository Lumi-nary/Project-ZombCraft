"""Real-game checks for chat, command completion, inventory mouse events and F5. Uses only local screenshots."""
import json
import time
from pathlib import Path
import pzcore as p

ROOT = Path(__file__).resolve().parent
results = {}
test_diamonds_added = False

def st():
    return p.state()

def until(pred, what):
    def check():
        s = st()
        return s if pred(s) else None
    return p.wait_for(check, 10, .15, what)

def gui(**event):
    reply = p.api('POST', '/gui', event)
    assert reply.get('ok'), reply

def key(name):
    p.desktop('key', Key=name)

def shot(name):
    (ROOT / name).write_bytes(p.screenshot(.7))

def click(x, y, button=0, mods=0):
    gui(x=x, y=y, button=button, mods=mods, action=1)
    time.sleep(.12)
    gui(x=x, y=y, button=button, mods=mods, action=0)
    time.sleep(.2)

try:
    p.desktop('focus')
    p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    until(lambda s: s['viewpoint']['captured'], 'PZ cursor capture')
    start = st()
    if start['minecraftGui']['screen']:
        gui(key=256)
        until(lambda s: not s['minecraftGui']['screen'] and s['viewpoint']['captured'], 'screen close')
    original = st()['minecraftGui']['inventory']
    # A real keypress and native text callbacks, not the dev-command endpoint.
    key('T')
    s = until(lambda s: s['minecraftGui']['screen'] == 'ChatScreen', 'chat')
    assert s['minecraftGui'].get('text') == '', s
    assert not s['viewpoint']['captured'] and not s['input']['live'], s
    pos = [s['steve'][axis] for axis in ['x','y','z']]
    p.desktop('text', Key='/give @s minecraft:diamond 3')
    s = until(lambda s: s['minecraftGui'].get('text') == '/give @s minecraft:diamond 3', 'native text entry')
    assert s['viewpoint']['view3d'] and not s['paused'], s
    assert pos == [s['steve'][axis] for axis in ['x','y','z']], 'Typing moved Steve'
    shot('controls-chat.png')
    key('ENTER')
    s = until(lambda s: not s['minecraftGui']['screen'] and s['viewpoint']['captured'], 'chat submit')
    def diamonds(slots):
        return sum(int(v.split()[0]) for v in slots if 'minecraft:diamond' in v)
    assert diamonds(s['minecraftGui']['inventory']) == diamonds(original) + 3
    test_diamonds_added = True
    results['nativeChatCommand'] = True

    key('SLASH')
    until(lambda s: s['minecraftGui'].get('text') == '/', 'slash opener')
    gui(text='gamem')
    until(lambda s: s['minecraftGui'].get('text') == '/gamem', 'partial command')
    key('TAB')
    until(lambda s: s['minecraftGui'].get('text') == '/gamemode', 'Tab completion')
    gui(text=' creative')
    until(lambda s: s['minecraftGui'].get('text') == '/gamemode creative', 'command arguments')
    # Edit shortcuts and Unicode traverse the same character path, without sending screenshots externally.
    gui(key=65, mods=2)
    gui(text='/say 测试🙂')
    until(lambda s: s['minecraftGui'].get('text') == '/say 测试🙂', 'Unicode and Ctrl+A')
    key('ESCAPE')
    s = until(lambda s: not s['minecraftGui']['screen'] and s['viewpoint']['captured'], 'Escape from chat')
    assert not s['paused'], 'Escape leaked into PZ'
    results['completionEditingUnicode'] = True

    key('E')
    s = until(lambda s: s['minecraftGui']['screen'] == 'InventoryScreen', 'inventory')
    g = s['minecraftGui']; w,h = g['width'],g['height']; left=(w-176)//2; top=(h-166)//2
    shot('controls-inventory.png')
    # Move a known existing tool, then restore it: validation doesn't depend on how /give merged diamonds.
    hot = ((left+16)/w, (top+150)/h)
    free = next(i for i in range(9,36) if g['inventory'][i] == '0 minecraft:air')
    row,col = divmod(free-9,9)
    main = ((left+16+18*col)/w, (top+92+18*row)/h)
    item = g['inventory'][0]
    click(*hot)
    until(lambda s: s['minecraftGui']['carried'] == item, 'pick up stack')
    click(*main)
    until(lambda s: s['minecraftGui']['inventory'][free] == item and s['minecraftGui']['carried']=='0 minecraft:air', 'place stack')
    click(*main)
    click(*hot)
    until(lambda s: s['minecraftGui']['inventory'][0] == item and s['minecraftGui']['inventory'][free]=='0 minecraft:air', 'restore stack')
    results['inventoryMouseMove'] = True
    # Native close by E, then reopen and close by Escape: neither may trigger PZ interact or pause.
    key('E')
    s = until(lambda s: not s['minecraftGui']['screen'] and s['viewpoint']['captured'], 'E closes inventory')
    assert not s['paused']
    key('E')
    until(lambda s: s['minecraftGui']['screen']=='InventoryScreen', 'inventory reopen')
    key('ESCAPE')
    s = until(lambda s: not s['minecraftGui']['screen'] and s['viewpoint']['captured'], 'Escape closes inventory')
    assert not s['paused']
    results['screenCloseWithoutPzActions'] = True

    first = s['minecraftGui']['cameraMode']
    key('F5')
    s = until(lambda s: s['minecraftGui']['cameraMode'] == (first+1)%3, 'F5 changes perspective')
    shot('controls-third-person.png')
    assert s['viewpoint']['view3d'] and not s['nativeEntities']['failed']
    key('F5')
    until(lambda s: s['minecraftGui']['cameraMode'] == (first+2)%3, 'F5 reaches third perspective')
    key('F5')
    until(lambda s: s['minecraftGui']['cameraMode'] == first, 'F5 returns perspective')
    results['nativeF5'] = True
    results['passed'] = True
finally:
    try:
        if st()['minecraftGui']['screen']: gui(key=256)
        # Remove exactly the test diamonds, restore normal perspective, and leave both games paused.
        if test_diamonds_added: p.cmd('mc clear @s minecraft:diamond 3')
        for _ in range(3):
            s = st()
            if s['minecraftGui']['cameraMode'] == 0: break
            k = s['minecraftGui']['perspectiveKey']
            gui(key=k, action=1); gui(key=k, action=0)
            time.sleep(.3)
        time.sleep(.5)
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        results['finalState'] = st()
    finally:
        (ROOT / 'controls-results.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
        print(json.dumps(results, indent=2))
