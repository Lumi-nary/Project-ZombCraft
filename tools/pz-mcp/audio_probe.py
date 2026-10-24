"""Peak level of the PZ process's audio (see audio-peak.ps1). probe(ms) blocks; start(ms) returns a Popen to read later."""
import subprocess
from pathlib import Path
import pzcore as p

SCRIPT = Path(__file__).parent / 'audio-peak.ps1'


def start(milliseconds, pid=None):
    pid = pid or p.processes()['pz'][0]
    cmd = f'$ProcessIdToWatch={pid}; $Milliseconds={int(milliseconds)}; iex (Get-Content -Raw "{SCRIPT}")'
    return subprocess.Popen(['powershell', '-NoProfile', '-Command', cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)


def result(proc):
    out = proc.communicate(timeout=60)[0].strip().splitlines()
    line = out[-1] if out else ''
    return float(line.split('peak=')[1].split()[0]) if 'peak=' in line else None, line


def probe(milliseconds, pid=None):
    return result(start(milliseconds, pid))
