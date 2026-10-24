param([Parameter(Mandatory=$true)][string]$Folder)
Add-Type -AssemblyName System.Drawing
$taskFrames = Get-ChildItem -LiteralPath $Folder -Filter '*.png' | Where-Object { $_.Name -match '^\d\d-' } | Sort-Object Name
$taskSheet = New-Object System.Drawing.Bitmap(1440, (280 * [math]::Ceiling($taskFrames.Count/3)))
$taskGraphics = [System.Drawing.Graphics]::FromImage($taskSheet)
$taskGraphics.Clear([System.Drawing.Color]::Black)
$taskFont = New-Object System.Drawing.Font('Consolas',14)
try {
    for($taskIndex=0;$taskIndex -lt $taskFrames.Count;$taskIndex++) {
        $taskImg=[System.Drawing.Image]::FromFile($taskFrames[$taskIndex].FullName)
        try {
            $taskX=($taskIndex%3)*480
            $taskY=[math]::Floor($taskIndex/3)*280
            $taskGraphics.DrawImage($taskImg,$taskX,$taskY,480,254)
            $taskGraphics.DrawString($taskFrames[$taskIndex].Name,$taskFont,[System.Drawing.Brushes]::White,$taskX,$taskY+254)
        } finally { $taskImg.Dispose() }
    }
    $taskSheet.Save((Join-Path (Resolve-Path -LiteralPath $Folder).Path 'sheet.png'))
} finally { $taskGraphics.Dispose(); $taskSheet.Dispose(); $taskFont.Dispose() }
