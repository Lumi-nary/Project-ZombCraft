# Minimal desktop automation for the PZ window: only used for what PzCraft's control API cannot do
# (the click PZ wants after loading, and Viewpoint's O key). Run with -ExecutionPolicy Bypass.
param(
  [Parameter(Mandatory = $true)][string]$Action,
  [string]$Key = "",
  [int]$X = 0,
  [int]$Y = 0
)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class U32 {
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
}
"@

function Fg-Title {
  $sb = New-Object System.Text.StringBuilder 256
  [U32]::GetWindowText([U32]::GetForegroundWindow(), $sb, 256) | Out-Null
  return $sb.ToString()
}
function Focus-Pz {
  $p = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -eq 'Project Zomboid' } | Select-Object -First 1
  if (-not $p) { throw "Project Zomboid window not found" }
  [U32]::ShowWindow($p.MainWindowHandle, 5) | Out-Null
  [U32]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [U32]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
  [U32]::BringWindowToTop($p.MainWindowHandle) | Out-Null
  [U32]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
  Start-Sleep -Milliseconds 500
  if ((Fg-Title) -ne 'Project Zomboid') { throw "could not bring PZ to the foreground (foreground is '$(Fg-Title)')" }
}
function Click([int]$x, [int]$y) {
  [U32]::SetCursorPos($x, $y) | Out-Null
  Start-Sleep -Milliseconds 120
  [U32]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60
  [U32]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 300
}

Focus-Pz
switch ($Action) {
  "focus" { "focused" }
  "clickcenter" { $b = [Windows.Forms.Screen]::PrimaryScreen.Bounds; Click ([int]($b.Width / 2)) ([int]($b.Height * 0.55)); "clicked" }
  "click" { Click $X $Y; "clicked $X,$Y" }
  "move" { [U32]::SetCursorPos($X, $Y) | Out-Null; Start-Sleep -Milliseconds 300; "moved $X,$Y" }
  "key" {
    $namedKeys = @{ F5=0x74; ESCAPE=0x1B; ENTER=0x0D; TAB=0x09; BACKSPACE=0x08; SLASH=0xBF }
    $vk = if ($namedKeys.ContainsKey($Key.ToUpper())) { [byte]$namedKeys[$Key.ToUpper()] } else { [byte][char]$Key.ToUpper() }
    [U32]::keybd_event($vk, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60
    [U32]::keybd_event($vk, 0, 2, [UIntPtr]::Zero); "pressed $Key"
  }
  "text" { [Windows.Forms.SendKeys]::SendWait($Key); "typed text into PZ" }
  default { throw "unknown action $Action" }
}
