"""python sheet.py OUT.png COLS cropx cropy cropw croph a.png b.png ... : tile screenshots (see contact-sheet.ps1)."""
import subprocess, sys
from pathlib import Path
out, cols, cx, cy, cw, ch, *files = sys.argv[1:]
here = Path(__file__).parent
files = ','.join(str(Path(f).resolve()) for f in files)
cmd = f'$Files="{files}"; $Out="{Path(out).resolve()}"; $Cols={cols}; $CropX={cx}; $CropY={cy}; $CropW={cw}; $CropH={ch}; iex (Get-Content -Raw "{here / "contact-sheet.ps1"}")'
print(subprocess.run(['powershell', '-NoProfile', '-Command', cmd], capture_output=True, text=True).stdout.strip())
