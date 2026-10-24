"""Compare the hidden client's idle runtime processes, CPU, RAM and overlay age. No desktop focus or game fixtures."""
import argparse
import ctypes as c
from ctypes import wintypes as w
import json
import statistics
import subprocess
import time
from pathlib import Path
import pzcore as pz

kernel = c.WinDLL("kernel32", use_last_error=True)
psapi = c.WinDLL("psapi", use_last_error=True)
kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
kernel.OpenProcess.restype = w.HANDLE
kernel.CloseHandle.argtypes = [w.HANDLE]
kernel.GetProcessTimes.argtypes = [w.HANDLE] + [c.POINTER(w.FILETIME)] * 4


class Memory(c.Structure):
    _fields_ = [("cb", w.DWORD), ("faults", w.DWORD)] + [(name, c.c_size_t) for name in
        ("peak", "working", "peakPaged", "paged", "peakNonPaged", "nonPaged", "pagefile", "peakPagefile", "private")]


psapi.GetProcessMemoryInfo.argtypes = [w.HANDLE, c.POINTER(Memory), w.DWORD]


def usage(pid):
    h = kernel.OpenProcess(0x410, False, pid)
    if not h:
        return None
    try:
        times = [w.FILETIME() for _ in range(4)]
        if not kernel.GetProcessTimes(h, *(c.byref(t) for t in times)):
            return None
        cpu = sum((t.dwHighDateTime << 32) | t.dwLowDateTime for t in times[2:]) / 1e7
        mem = Memory(); mem.cb = c.sizeof(mem)
        if not psapi.GetProcessMemoryInfo(h, c.byref(mem), mem.cb):
            return None
        return {"cpuSeconds": cpu, "workingMiB": mem.working / 2**20, "privateMiB": mem.private / 2**20}
    finally:
        kernel.CloseHandle(h)


def runtime(mc_pid):
    # Read command lines only to identify the game's launch ancestry; the report contains no command lines.
    script = "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Select-Object ProcessId,ParentProcessId,CommandLine | ConvertTo-Json -Compress"
    items = json.loads(subprocess.check_output(["powershell.exe", "-NoProfile", "-Command", script], text=True))
    if isinstance(items, dict): items = [items]
    by_id = {item["ProcessId"]: item for item in items}
    selected = {mc_pid: "Minecraft"}
    current = by_id[mc_pid]
    while current["ParentProcessId"] in by_id:
        current = by_id[current["ParentProcessId"]]
        if "gradle" not in (current["CommandLine"] or "").lower(): break
        selected[current["ProcessId"]] = "Gradle runtime"
    for item in items:
        command = item["CommandLine"] or ""
        if "GradleWrapperMain" in command and "runClient" in command and "fabric-mod" in command:
            selected[item["ProcessId"]] = "Gradle wrapper"
    return selected


def run(label, seconds, gpu=False):
    s = pz.state()
    assert s["gameState"] == "IngameState" and s["link"]["ready"]
    paused = s["paused"]
    option = pz.lua("return tostring(getCore():getOptionPauseOnFocusloss())")["results"][0]
    pz.lua("getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)")
    processes = runtime(s["link"]["minecraftPid"])
    before = {pid: usage(pid) for pid in processes}
    gpu_job=None
    if gpu:
        ids=list(processes)+pz.processes()['pz'];pattern='^pid_('+ '|'.join(str(n) for n in ids)+')_'
        script="""$ErrorActionPreference='Stop'; $rows=@(Get-Counter '\\GPU Engine(*)\\Utilization Percentage' -SampleInterval 1 -MaxSamples %d | ForEach-Object {
          $stamp=$_.Timestamp.ToString('o'); $_.CounterSamples | Where-Object {$_.InstanceName -match '%s'} | ForEach-Object {
            [pscustomobject]@{time=$stamp;engine=$_.InstanceName;percent=[math]::Round($_.CookedValue,3)}
          }
        }); ConvertTo-Json -InputObject $rows -Compress""" % (seconds,pattern)
        gpu_job=subprocess.Popen(['pwsh.exe','-NoProfile','-Command',script],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    samples = []
    t0 = time.monotonic()
    try:
        while time.monotonic() - t0 < seconds:
            s = pz.state()
            assert s["link"]["ready"] and s["nativeBlocks"]["active"] and not s["nativeBlocks"]["failed"]
            samples.append({"fps": s["fps"], "ageMs": s["overlay"]["frameAgeMs"], "frameId": s["overlay"]["frameId"]})
            time.sleep(.2)
        elapsed = time.monotonic() - t0
        rows = []
        for pid, role in processes.items():
            after = usage(pid)
            assert before[pid] and after, f"runtime process {pid} exited"
            rows.append({"pid": pid, "role": role, "cpuCorePercent": round(100*(after["cpuSeconds"]-before[pid]["cpuSeconds"])/elapsed, 2),
                         "workingMiB": round(after["workingMiB"], 1), "privateMiB": round(after["privateMiB"], 1)})
        frame_rate = (samples[-1]["frameId"] - samples[0]["frameId"]) / elapsed
        result = {"passed": True, "label": label, "seconds": round(elapsed,1), "processes": rows,
                  "workingMiB": round(sum(r["workingMiB"] for r in rows),1),
                  "privateMiB": round(sum(r["privateMiB"] for r in rows),1),
                  "overlayAgeMedianMs": round(statistics.median(r["ageMs"] for r in samples),2),
                  "overlayAgeP95Ms": round(sorted(r["ageMs"] for r in samples)[int(.95*(len(samples)-1))],2),
                  "overlayFps": round(frame_rate,1), "pzFpsMedian": round(statistics.median(r["fps"] for r in samples),1),
                  "estimatedReadbackMiBPerSec": round(s["overlay"]["texW"]*s["overlay"]["texH"]*(8 if s["overlay"].get("worldVideo",True) else 4)*frame_rate/2**20,1),
                  "layers": 2 if s["overlay"].get("worldVideo",True) else 1,
                  "measurement": "Idle unpaused client; full RGBA readback estimate uses the actual layer count; GPU usage not measured"}
        if gpu_job:
            output,error=gpu_job.communicate(timeout=15)
            if gpu_job.returncode:result['gpuError']=error.strip()
            else:
                gpu_samples=json.loads(output);(Path(__file__).parent/f'gpu-{label}.json').write_text(json.dumps(gpu_samples,indent=2))
                engines={}
                for sample in gpu_samples:engines.setdefault(sample['engine'],[]).append(sample['percent'])
                result['gpuEngines']=[{'engine':k,'meanPercent':round(statistics.mean(v),2),'p95Percent':round(sorted(v)[int(.95*(len(v)-1))],2)}
                                      for k,v in engines.items() if statistics.mean(v)>.01]
                result['measurement']='Idle unpaused client; actual video layer count; Windows per-process GPU engine counters (not additive overall GPU percent)'
        (Path(__file__).parent / f"headless-{label}.json").write_text(json.dumps(result, indent=2))
        print(json.dumps(result, indent=2), flush=True)
    finally:
        pz.lua("getCore():setOptionPauseOnFocusloss(" + option + "); UIManager.getSpeedControls():SetCurrentGameSpeed(" + ("0" if paused else "1") + ")")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label", choices=("baseline", "direct", "native", "minimized", "hidden"))
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument('--gpu',action='store_true')
    args = parser.parse_args()
    run(args.label, args.seconds,args.gpu)
