"""0.12 flight and third-person checks. Only use the backed-up PzCraftFeatureTest-20261002 save."""
import argparse,json,math,statistics,time,subprocess,ctypes
from pathlib import Path
import pzcore as p
OUT=Path(__file__).parent

def run(label,seconds,record=False):
    result={'passed':False,'label':label,'seconds':seconds};samples=[]
    s=p.state(); assert s['gameState']=='IngameState' and s['link']['ready']
    origin=s['player'];option=p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
    inventory=s['minecraftGui']['inventory'];active=False;video=None;video_log=None
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        if record:
            pid=p.processes()['pz'][0]
            hwnd=int(subprocess.check_output(p.PS+['-Command',f'(Get-Process -Id {pid}).MainWindowHandle.ToInt64()'],text=True).strip())
            assert hwnd and not ctypes.windll.user32.IsIconic(ctypes.c_void_p(hwnd)), 'PZ must be visible for GPU recording'
            path=OUT/f'speed-{label}-0.12.mp4';video_log=(OUT/f'speed-{label}-record.log').open('w')
            video=subprocess.Popen(['ffmpeg','-hide_banner','-loglevel','warning','-f','lavfi','-i',
                f'gfxcapture=hwnd={hwnd}:width=960:height=540:max_framerate=20:capture_cursor=0',
                '-c:v','h264_nvenc','-preset','p1','-b:v','3M','-an','-t',str(seconds+12),'-y',str(path)],
                stdin=subprocess.PIPE,stdout=video_log,stderr=video_log,creationflags=0x08000000)
            result['recording']=str(path)
        p.send_input(release=True,look={'yaw':-90,'pitch':-2,'relative':False})
        p.cmd('mc pzcraft_test_flight begin '+('elytra' if label=='elytra' else 'fixed '+label));active=True
        time.sleep(2)
        p.cmd('camera 1');time.sleep(.5)
        start=time.monotonic();shot_at=0;rocket_at=0;refresh_at=0
        while time.monotonic()-start<seconds:
            now=time.monotonic();elapsed=now-start
            mode=1 if elapsed<seconds/2 else 2
            if elapsed>=seconds/2 and result.get('front') is None:
                p.cmd('camera 2');result['front']=True
            if now-refresh_at>.6:
                # Elytra needs neither W nor synthetic motion: rockets drive vanilla flight physics.
                p.send_input(hold=['FWD','SPRINT'] if label!='elytra' else ['FWD'],ms=1800);refresh_at=now
            if label=='elytra' and now-rocket_at>1.5:
                p.send_input(hold=['USE','FWD'],ms=150);rocket_at=now;refresh_at=0
            s=p.state();g=s['minecraftGui'];pose=s['steve'];v=g.get('velocity',[0,0,0])
            sample={'t':round(elapsed,3),'xyz':[pose['x'],pose['y'],pose['z']],'velocity':v,
                    'speed':math.hypot(v[0],v[2]),'fallFlying':g.get('fallFlying'),
                    'mode':g['cameraMode'],'terrain':s.get('terrain'), 'coverage':g.get('terrain'),
                    'streaming':s.get('streaming'),'fps':s['fps'],'overlayAgeMs':s['overlay']['frameAgeMs'],'frameId':s['overlay']['frameId'],
                    'native':s['nativeEntities'],'videoEntities':g.get('videoEntities')}
            samples.append(sample)
            assert pose['y']>-1,'fell below PZ ground'
            assert not s['nativeEntities']['failed'] and not s['nativeBlocks']['failed'],s
            assert g.get('videoEntities',0)==0,'player or entity still being drawn by world video'
            assert s['nativeEntities'].get('playerBatches',0)>0,'third-person avatar missing from native scene'
            if now-shot_at>=1:
                shot_at=now;(OUT/f'speed-{label}-{int(elapsed):02}.png').write_bytes(p.screenshot(.5))
            time.sleep(.1)
        result['speedMedian']=round(statistics.median(x['speed'] for x in samples[10:]),2)
        result['speedMax']=round(max(x['speed'] for x in samples),2)
        result['minY']=round(min(x['xyz'][1] for x in samples),3)
        result['distance']=round(math.dist(samples[0]['xyz'],samples[-1]['xyz']),2)
        result['fpsMedian']=round(statistics.median(x['fps'] for x in samples),1)
        result['pendingSamples']=sum(x['coverage'].get('loading',False) for x in samples)
        result['videoEntitiesMax']=max(x['videoEntities'] for x in samples)
        result['overlayFps']=round((samples[-1]['frameId']-samples[0]['frameId'])/(samples[-1]['t']-samples[0]['t']),1)
        result['overlayAgeMedianMs']=round(statistics.median(x['overlayAgeMs'] for x in samples),2)
        result['nativeFrameAgeMedianMs']=round(statistics.median(x['native']['frameAgeMs'] for x in samples),2)
        masks=[x['streaming'].get('nearGroundMaskAhead',[]) for x in samples]
        result['nearGroundMaskAheadMin']=[min(row[i] for row in masks if len(row)>i) for i in range(7)]
        result['farCellsMin']=min(x['streaming'].get('farCells',0) for x in samples)
        result['streamerQueueMax']=max(x['streaming'].get('streamerQueue',0) for x in samples)
        result['loadedQueueMax']=max(x['streaming'].get('loadedQueue',0) for x in samples)
        result['scanMsP95']=round(sorted(x['terrain']['scanMs'] for x in samples)[int(len(samples)*.95)],3)
        if label=='elytra':
            assert sum(x['fallFlying'] for x in samples)/len(samples)>.98,'not sustained vanilla elytra flight'
            assert result['speedMedian']>=27,'flight never reached required elytra speed'
        result['passed']=True
    except Exception as e:
        result['error']=str(e)
    finally:
        p.send_input(release=True)
        if active:p.cmd('mc pzcraft_test_flight end')
        time.sleep(3)
        # Re-establish the host square at the saved feet position, including the vertical coordinate.
        now=p.state()['player'];p.lua(f"getPlayer():setZ({origin['z']})")
        p.cmd(f"tp {origin['x']-now['x']} {origin['y']-now['y']}");p.cmd('camera 0');time.sleep(3)
        result['inventoryRestored']=p.state()['minecraftGui']['inventory']==inventory
        if not result['inventoryRestored']:result['passed']=False;result['error']='fixture changed inventory'
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss('+option+')')
        if video:
            if video.poll() is None:
                video.stdin.write(b'q\n');video.stdin.flush()
                try:video.wait(timeout=10)
                except subprocess.TimeoutExpired:video.kill();video.wait()
            result['recordExit']=video.returncode
            if video.returncode!=0:result['passed']=False;result['error']='GPU recording failed; see record log'
        if video_log:video_log.close()
        (OUT/f'speed-{label}-samples.json').write_text(json.dumps(samples,indent=2))
        (OUT/f'speed-{label}-results.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2),flush=True)
    if not result['passed']:raise SystemExit(1)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('label',choices=['0.05','0.075','0.1','elytra']);parser.add_argument('--seconds',type=int,default=60)
    parser.add_argument('--record',action='store_true');a=parser.parse_args();run(a.label,a.seconds,a.record)
