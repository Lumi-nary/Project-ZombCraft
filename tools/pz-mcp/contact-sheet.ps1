# Tile images into one contact sheet (System.Drawing, no downloads).
# Feed it to a shell: powershell -NoProfile -Command "$Files='a.png,b.png'; $Out='sheet.png'; $Cols=2; $CropX=0.25; $CropY=0.3; $CropW=0.7; $CropH=0.7; iex (Get-Content -Raw contact-sheet.ps1)"
Add-Type -AssemblyName System.Drawing
$list = $Files -split ','
$first = [System.Drawing.Image]::FromFile($list[0]); $w = $first.Width; $h = $first.Height; $first.Dispose()
$rect = New-Object System.Drawing.Rectangle ([int]($w * $CropX)), ([int]($h * $CropY)), ([int]($w * $CropW)), ([int]($h * $CropH))
$cw = $rect.Width; $ch = $rect.Height
$rows = [math]::Ceiling($list.Count / $Cols)
$sheet = New-Object System.Drawing.Bitmap ($Cols * $cw), ($rows * $ch)
$g = [System.Drawing.Graphics]::FromImage($sheet)
$i = 0
foreach ($f in $list) {
  $img = [System.Drawing.Image]::FromFile($f)
  $dx = ($i % $Cols) * $cw; $dy = [math]::Floor($i / $Cols) * $ch
  $dest = New-Object System.Drawing.Rectangle $dx, $dy, $cw, $ch
  $g.DrawImage($img, $dest, $rect, [System.Drawing.GraphicsUnit]::Pixel)
  $img.Dispose(); $i++
}
$g.Dispose(); $sheet.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png); $sheet.Dispose(); "saved $Out ($($list.Count) images)"
