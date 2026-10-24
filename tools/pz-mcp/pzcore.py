"""Shared client for the PzCraft control API (see pz-mod Control.java) plus process, log and desktop helpers.

No third-party packages: standard library only, so it runs anywhere Python 3.9+ does.
"""
from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ZOMBOID = Path(os.environ.get("PZCRAFT_ZOMBOID", r"E:\Zomboid"))
PZ_DIR = Path(os.environ.get("PZCRAFT_PZDIR", r"E:\SteamLibrary\steamapps\common\ProjectZomboid"))
PORT = int(os.environ.get("PZCRAFT_PORT", "47600"))
PS = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass"]


class PzError(RuntimeError):
    pass


# ---------------------------------------------------------------------------------------------------------------
# control API

def _token() -> str:
    t = ZOMBOID / "Lua" / "pzcraft-control.token"
    if not t.is_file():
        raise PzError(f"no control token at {t}: PzCraft has not started the control API yet (is PZ running with the mod?)")
    return t.read_text().strip()


def api(method: str, path: str, body: dict | None = None, timeout: float = 15.0, raw: bool = False):
    url = f"http://127.0.0.1:{PORT}{path}"
    data = json.dumps(body).encode() if body is not None else (b"{}" if method == "POST" else None)
    req = urllib.request.Request(url, data=data, method=method, headers={"X-Token": _token(), "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            payload = r.read()
            return payload if raw else json.loads(payload.decode())
    except urllib.error.HTTPError as e:
        raise PzError(f"{path}: HTTP {e.code}: {e.read().decode(errors='replace')}") from None
    except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
        raise PzError(f"control API not reachable on port {PORT} ({e}). Is PZ running with PzCraft?") from None


def alive() -> bool:
    try:
        api("GET", "/state", timeout=3)
        return True
    except PzError:
        return False


def state() -> dict:
    return api("GET", "/state")


def screenshot(scale: float = 0.5) -> bytes:
    return api("GET", f"/screenshot?scale={scale}", raw=True, timeout=20)


def send_input(hold=None, ms=200, look=None, hotbar=None, wait=False, release=False) -> dict:
    body: dict = {"ms": int(ms)}
    if hold:
        body["hold"] = list(hold)
    if look:
        body["look"] = look
    if hotbar:
        body["hotbar"] = int(hotbar)
    if wait:
        body["wait"] = True
    if release:
        body["release"] = True
    return api("POST", "/input", body, timeout=ms / 1000 + 20 if wait else 15)


def cmd(line: str) -> dict:
    return api("POST", "/cmd", {"line": line})


def lua(code: str) -> dict:
    return api("POST", "/lua", {"code": code})


def menu(action: str) -> dict:
    return api("POST", "/menu", {"action": action})


_INERT_TOOL = __import__("re").compile(r"\d+ minecraft:(wooden|stone|iron|golden|diamond|netherite)_(sword|pickaxe|shovel|hoe)\b")


def select_inert_hand() -> int | None:
    """Before a test holds the use key: select a hotbar slot whose item the use key cannot place (blocks, boats, redstone), wear
    (PZ clothes, elytra), eat, light or fire. A held block or boat is used on the targeted ground instead of opening a PZ container,
    and is consumed (this destroyed a repeater once). Prefers an empty slot, else a plain tool. Returns the slot that was
    selected before (0-8, or None if the game does not report it) for restore_hand."""
    g = state()["minecraftGui"]
    hotbar = g["inventory"][:9]
    pick = next((i for i, s in enumerate(hotbar) if s.startswith("0 ")), None)
    if pick is None:
        pick = next((i for i, s in enumerate(hotbar) if _INERT_TOOL.match(s)), None)
    if pick is None:
        raise PzError("no hotbar slot is safe to hold while pressing the use key: empty one or put a sword in it")
    if g.get("selectedSlot") != pick:
        _select_slot(pick)
    return g.get("selectedSlot")


def _select_slot(slot: int, timeout: float = 5.0) -> None:
    """Selects a hotbar slot and waits until Minecraft reports it: the input is only taken while the world runs, so a request
    followed at once by a pause would be lost. Never press the use key before this has returned."""
    t0 = time.time()
    while True:
        send_input(hotbar=slot + 1, wait=True)
        time.sleep(0.2)
        if state()["minecraftGui"].get("selectedSlot") == slot:
            return
        if time.time() - t0 > timeout:
            raise PzError(f"could not select hotbar slot {slot + 1}: is the world running and Minecraft linked?")


def restore_hand(previous: int | None) -> None:
    if previous is not None:
        _select_slot(previous)


# ---------------------------------------------------------------------------------------------------------------
# processes and logs

def _ps_json(script: str):
    r = subprocess.run(PS + ["-Command", script], capture_output=True, text=True, timeout=60)
    out = r.stdout.strip()
    return json.loads(out) if out else []


def processes() -> dict:
    """The PZ and Minecraft JVMs, found by their command lines."""
    rows = _ps_json(
        "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
        "Select-Object ProcessId, @{n='cmd';e={$_.CommandLine}} | ConvertTo-Json -Compress")
    if isinstance(rows, dict):
        rows = [rows]
    out = {"pz": [], "minecraft": [], "gradle": []}
    for r in rows:
        c = r.get("cmd") or ""
        if "MainScreenState" in c:
            out["pz"].append(r["ProcessId"])
        elif ("KnotClient" in c or "pzcraft-direct.args" in c) and "fabric-mod" in c:
            out["minecraft"].append(r["ProcessId"])
        elif "gradle" in c.lower() and "runClient" in c and "fabric-mod" in c:
            out["gradle"].append(r["ProcessId"])
    return out


def save_worlds(timeout: float = 15.0) -> list:
    """Before stopping the games: PZ's saveGame() and Minecraft's /save-all flush (PzCraft registers it for the integrated
    server, where vanilla has none), waiting until Minecraft confirms in its log. A paused PZ pauses Minecraft's server too and
    a paused server never runs the command, so PZ is unpaused for the save and paused again afterwards."""
    done = []
    was_paused = False
    try:
        st = state()
        if st.get("inGame"):
            was_paused = bool(st.get("paused"))
            if was_paused:
                lua("UIManager.getSpeedControls():SetCurrentGameSpeed(1)")
                time.sleep(1.0)
            lua("saveGame()")
            done.append("PZ saved")
    except PzError:
        pass
    try:
        log = LOGS["minecraft"]
        mark = log.stat().st_size if log.is_file() else 0
        cmd("mc save-all flush")
        t0 = time.time()
        while time.time() - t0 < timeout:
            with open(log, "rb") as f:
                f.seek(mark)
                if b"Minecraft world saved" in f.read():
                    done.append("Minecraft saved")
                    break
            time.sleep(0.25)
        else:
            done.append("Minecraft save not confirmed")
    except (PzError, OSError):
        pass
    if was_paused:
        try:
            lua("UIManager.getSpeedControls():SetCurrentGameSpeed(0)")
        except PzError:
            pass
    if done:
        time.sleep(2)  # PZ writes its save on the game thread
    return done


def kill(which: str = "all", save: bool = True, force: bool = False) -> dict:
    """Stops the game JVMs. With save=True both worlds must be confirmed saved first; otherwise nothing is killed
    (force=True overrides that, for a hung game): a killed game keeps only what was saved."""
    saved = save_worlds() if save and which in ("all", "pz", "minecraft", "mc") else []
    p = processes()
    if save and not force:
        missing = []
        if p["pz"] and which in ("all", "pz") and "PZ saved" not in saved:
            missing.append("PZ")
        if (p["minecraft"] or p["gradle"]) and which in ("all", "minecraft", "mc") and "Minecraft saved" not in saved:
            missing.append("Minecraft")
        if missing:
            return {"saved": saved, "killed": [], "refused": f"{' and '.join(missing)} save not confirmed: nothing was stopped (force=True overrides)"}
    killed = []
    targets = []
    if which in ("all", "pz"):
        targets += p["pz"]
    if which in ("all", "minecraft", "mc"):
        targets += p["minecraft"] + p["gradle"]
    for pid in targets:
        subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)
        killed.append(pid)
    return {"saved": saved, "killed": killed}


LOGS = {
    "console": ZOMBOID / "console.txt",
    "minecraft": ROOT / "fabric-run-live.log",
    "launch": ROOT / "pz-launch.log",
}


def logs(source: str = "console", pattern: str | None = None, n: int = 40) -> str:
    path = LOGS.get(source)
    if path is None or not path.is_file():
        raise PzError(f"unknown or missing log '{source}' (have: {', '.join(LOGS)})")
    import re
    rx = re.compile(pattern, re.I) if pattern else None
    out: list[str] = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if rx is None or rx.search(line):
                out.append(line.rstrip("\n")[:400])
                if len(out) > n * 4:
                    out = out[-n:]
    return "\n".join(out[-n:])


# ---------------------------------------------------------------------------------------------------------------
# desktop automation: real mouse/keyboard on the user's desktop. It brings PZ's window to the front and takes the user's
# focus, so every use must be announced to the user first. The control API now covers the loading click (menu start) and
# the O key (menu view3d); these remain only as an opt-in fallback.

DESKTOP_PS = ROOT / "tools" / "pz-mcp" / "desktop.ps1"


def desktop(action: str, **kw) -> str:
    args = PS + ["-File", str(DESKTOP_PS), "-Action", action]
    for k, v in kw.items():
        args += [f"-{k}", str(v)]
    r = subprocess.run(args, capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        raise PzError(f"desktop {action} failed: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout.strip()


def click_pz(fx: int, fy: int) -> str:
    """Click at PZ's own screen coordinates (the pixels of pz_screenshot at scale 1.0). The window frame and title bar make
    PZ's coordinates differ from the desktop's, so the offset is measured first with the real cursor."""
    desktop("move", X=500, Y=500)
    time.sleep(0.4)
    r = lua("return Mouse.getXA() .. ',' .. Mouse.getYA()")["results"][0]
    px, py = (int(float(v)) for v in r.split(","))
    return desktop("click", X=int(fx + (500 - px)), Y=int(fy + (500 - py)))


# ---------------------------------------------------------------------------------------------------------------
# launching

def wait_for(pred, timeout: float, every: float = 1.0, what: str = "condition"):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            v = pred()
            if v:
                return v
        except PzError:
            pass
        time.sleep(every)
    raise PzError(f"timed out after {timeout:.0f}s waiting for {what}")


def launch(restart: bool = False, allow_all: bool = False, enter_game: bool = True, enable_3d: bool = True,
           save: str | None = None, game_mode: str = "Apocalypse", focus_fallback: bool = False) -> dict:
    """Start PZ (+ the hidden Minecraft), optionally continue the latest save and switch on Viewpoint's 3D view.

    Nothing here clicks or presses keys on the desktop: loading's "click to start" and Viewpoint's O key go through the
    control API. focus_fallback=True allows the old desktop click / O key for a PzCraft build without those API actions;
    that brings PZ's window to the front, so tell the user first. (A freshly started PZ window may still come to the
    front on its own, as any newly started program can.)

    allow_all=True is for unattended development: it passes ZombieBuddy policy=allow-all together with config_dir pointing at a
    private directory (~/.pzcraft/zb-dev), so the allow decisions ZombieBuddy records never touch the user's real
    ~/.zombie_buddy/mod_approvals.json and their own launches still ask. Without it, a changed PzCraft jar makes ZombieBuddy
    show its approval dialog and the game waits for a human to answer.
    """
    steps = []
    if restart:
        stopped = kill("all")  # saves PZ and Minecraft first: a killed game keeps only what was saved
        steps.append(stopped)
        if stopped.get("refused"):
            return {"ok": False, "steps": steps}
        time.sleep(4)
    elif alive():
        steps.append("PZ already running")
    if not alive():
        env = dict(os.environ)
        if allow_all:
            devdir = Path.home() / ".pzcraft" / "zb-dev"
            devdir.mkdir(parents=True, exist_ok=True)
            env["PZ_ZB_ARGS"] = f"policy=allow-all,config_dir={devdir}"
        env.pop("ZB_VERBOSITY", None)
        with open(LOGS["launch"], "a", encoding="utf-8") as lf:
            lf.write(f"\n===== pz_launch {time.strftime('%Y-%m-%dT%H:%M:%S')} allow_all={allow_all} =====\n")
        # one command-line string: a list would make Python quote the window title and `start` would misparse it
        subprocess.Popen(f'cmd.exe /c start "PzCraft" /min "{ROOT / "scripts" / "start-all.bat"}"', env=env,
                         creationflags=0x00000008 | 0x00000200)  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
        steps.append("launched start-all.bat")
        try:
            wait_for(lambda: alive(), 150, 2, "the PzCraft control API (an approval dialog may be waiting for you)")
        except PzError as e:
            steps.append(str(e))
            return {"ok": False, "steps": steps}
    st = state()
    steps.append(f"gameState={st.get('gameState')} inGame={st.get('inGame')}")
    if enter_game and not st.get("inGame"):
        wait_for(lambda: state().get("gameState") == "MainScreenState", 90, 2, "the main menu")
        time.sleep(2)
        try:
            steps.append(lua(f"MainScreen.continueLatestSave({json.dumps(game_mode)}, {json.dumps(save)})")
                         if save else menu("continue"))
        except PzError as e:
            # Loading a save that uses another mod list reloads PZ's Lua inside this very call, which can outlast the
            # control API's wait although the load goes ahead; the wait below tells.
            steps.append(f"continue: {e} (waiting for the load anyway)")
        wait_for(lambda: state().get("inGame"), 240, 3, "the save to load")
        # PZ waits for "click to start" once loading is done. The control API does what the click does (no real mouse,
        # no window focus); the desktop click is only a fallback for an older PzCraft, and only with focus_fallback=True.
        deadline = time.time() + 120
        started = False
        clicks = 0
        while state().get("gameState") != "IngameState" and time.time() < deadline:
            if state().get("gameState") == "GameLoadingState":
                if not started:
                    try:
                        started = bool(menu("start").get("ok"))
                    except PzError:
                        started = False
                if not started and focus_fallback:
                    desktop("clickcenter")  # brings PZ to the front: callers must have warned the user
                    clicks += 1
            time.sleep(3)
        steps.append(f"save loaded ({'control API start' if started else f'{clicks} desktop click(s)'}); "
                     f"gameState={state().get('gameState')}")
        time.sleep(2)
    if enter_game and enable_3d:
        st = state()
        if not st["viewpoint"]["view3d"]:
            try:
                r = menu("view3d")
                steps.append(f"Viewpoint 3D through the control API: {r}")
            except PzError as e:
                r = {"ok": False}
                steps.append(f"Viewpoint 3D through the control API failed: {e}")
            if not r.get("ok") and focus_fallback:
                steps.append("pressing O: " + desktop("key", Key="O"))  # brings PZ to the front
            time.sleep(4)
        steps.append("unpause: " + json.dumps(lua("UIManager.getSpeedControls():SetCurrentGameSpeed(1)")))
        def minecraft_loaded():
            s=state()
            return s["link"]["ready"] or (s.get("minecraftGui",{}).get("screen")=="DeathScreen"
                and s.get("health",{}).get("steveHealth")==0 and s["link"]["connected"])
        wait_for(minecraft_loaded, 90, 2, "Minecraft to be ready (Steve placed or awaiting Respawn)")
    return {"ok": True, "steps": steps, "state": state()}


def start_minecraft(timeout: float = 170.0) -> dict:
    """Starts only the hidden Minecraft half (PZ keeps running; PZ itself starts Minecraft only when it starts) and waits until
    it is linked and ready, and the inventory mirror is in step. PZ is unpaused for it (it places Steve), and left unpaused."""
    if processes()["minecraft"]:
        return {"ok": True, "steps": ["Minecraft already running"]}
    lua("UIManager.getSpeedControls():SetCurrentGameSpeed(1)")
    # no console window: run-minecraft.bat's java inherits the hidden console (its output goes to the log files)
    subprocess.Popen(["cmd.exe", "/c", str(ROOT / "scripts" / "run-minecraft.bat")],
                     creationflags=0x08000000 | 0x00000200)  # CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP

    def ready():
        s = state()
        m = s.get("pzMirror", {})
        return s["link"]["ready"] and (not m.get("active") or (m.get("status") == "in step" and m.get("pending") == 0))

    wait_for(lambda: ready() or None, timeout, 2, "Minecraft to be linked and the mirror in step")
    time.sleep(3)
    return {"ok": True, "steps": ["Minecraft started and linked"], "state": state()}


RESPAWN_LUA = """
local ui = ISPostDeathUI.instance and ISPostDeathUI.instance[0]
if not ui then return "no death screen" end
ui:onRespawn()
local w = CoopCharacterCreation.instance
if not w then return "no character creation window" end
w.mapSpawnSelect:useDefaultSpawnRegion()
local desc = MainScreen.instance.desc
if not desc:getCharacterProfession() then desc:setCharacterProfession(CharacterProfession.UNEMPLOYED) end
w:accept()
return "new character; region " .. tostring(CoopMapSpawnSelect.instance.selectedRegion and CoopMapSpawnSelect.instance.selectedRegion.name)
"""


def respawn() -> dict:
    """After a death: "Continue with new character" with the defaults (default spawn region, unemployed, no traits).
    Minecraft re-places Steve at the new character by itself."""
    r = lua(RESPAWN_LUA)
    out = {"lua": r.get("results")}
    try:
        wait_for(lambda: state()["link"]["ready"], 60, 1, "Minecraft to place Steve at the new character")
        out["state"] = {k: state().get(k) for k in ("player", "steve", "health")}
    except PzError as e:
        out["error"] = str(e)
    return out


# ---------------------------------------------------------------------------------------------------------------
# optional OpenRouter helpers (the user provides the key; nothing is sent anywhere without it)

def openrouter_key() -> str | None:
    k = os.environ.get("OPENROUTER_API_KEY")
    if k:
        return k.strip()
    f = Path.home() / ".pzcraft" / "openrouter.key"
    return f.read_text().strip() if f.is_file() else None


def openrouter(model: str, content, max_tokens: int = 600, timeout: float = 90) -> str:
    key = openrouter_key()
    if not key:
        raise PzError("no OpenRouter key: set OPENROUTER_API_KEY, or put the key in " + str(Path.home() / ".pzcraft" / "openrouter.key"))
    body = json.dumps({"model": model, "max_tokens": max_tokens, "messages": [{"role": "user", "content": content}]}).encode()
    req = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", data=body, method="POST",
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            j = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raise PzError(f"OpenRouter HTTP {e.code}: {e.read().decode(errors='replace')[:300]}") from None
    return j["choices"][0]["message"]["content"]


DECIDE_MODEL = os.environ.get("PZ_DECIDE_MODEL", "inception/mercury-decide:free")


def decide(state, instructions: str, choices: dict[str, str]) -> dict:
    """Mercury's decisions endpoint: pick one of `choices` ({name: what it means}) for a `state` description.

    Returns {"choice", "probabilities", "confidence", "ms"}. It is a fast classifier-style model (a few hundred ms), meant for
    quick routing decisions such as "which screen is this" or "what next", not for open-ended generation.
    """
    key = openrouter_key()
    if not key:
        raise PzError("no OpenRouter key: set OPENROUTER_API_KEY or put it in " + str(Path.home() / ".pzcraft" / "openrouter.key"))
    body = {"model": DECIDE_MODEL, "state": state,
            "questions": {"q": {"type": "choice", "instructions": instructions, "criteria": choices}}}
    req = urllib.request.Request("https://openrouter.ai/api/alpha/decisions", data=json.dumps(body).encode(), method="POST",
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            j = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raise PzError(f"OpenRouter decisions HTTP {e.code}: {e.read().decode(errors='replace')[:300]}") from None
    a = j["answers"]["q"]
    return {"choice": a["choice"], "probabilities": a["probabilities"], "confidence": a["confidence"],
            "ms": round((time.time() - t0) * 1000)}


VISION_MODEL = os.environ.get("PZ_VISION_MODEL", "unbiased/pareto-26.10-preview")
FAST_MODEL = os.environ.get("PZ_FAST_MODEL", "inception/mercury-2.5")


def vision(question: str, scale: float = 0.5) -> str:
    png = screenshot(scale)
    b64 = base64.b64encode(png).decode()
    return openrouter(VISION_MODEL, [{"type": "text", "text": question},
                                     {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}}])


def fast(prompt: str) -> str:
    return openrouter(FAST_MODEL, prompt)


def log_digest(source: str = "console", lines: int = 200) -> str:
    text = logs(source, r"error|exception|warn|fail|\[PzCraft\]|\[ZB\]", lines)
    return fast("Summarize problems in these game log lines in at most 8 bullets, most important first; quote the key line "
                "for each:\n\n" + text)


if __name__ == "__main__":
    print(json.dumps(state(), indent=2))
