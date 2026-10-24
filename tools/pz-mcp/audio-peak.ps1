# Peak output level of one process's audio session on the default playback device (Windows Core Audio, no downloads).
# Usage (the .ps1 may not be run directly on this machine: feed it to a shell):
#   powershell -NoProfile -Command "$ProcessIdToWatch=9596; $Milliseconds=3000; iex (Get-Content -Raw audio-peak.ps1)"
# Prints one line: peak=<0..1> samples=<n> sessions=<m> found=<True|False>
Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;

public static class AudioPeaks {
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom {}
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator { [PreserveSig] int EnumAudioEndpoints(int flow, int mask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IMMDevice device); }
    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice { [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr p, [MarshalAs(UnmanagedType.IUnknown)] out object o); }
    [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionManager2 { [PreserveSig] int GetAudioSessionControl(IntPtr a, int b, out IntPtr c);
        [PreserveSig] int GetSimpleAudioVolume(IntPtr a, int b, out IntPtr c);
        [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator e); }
    [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionEnumerator { [PreserveSig] int GetCount(out int n); [PreserveSig] int GetSession(int i, out IAudioSessionControl2 s); }
    [ComImport, Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioSessionControl2 {
        [PreserveSig] int GetState(out int s); [PreserveSig] int GetDisplayName(out IntPtr s); [PreserveSig] int SetDisplayName(IntPtr s, IntPtr c);
        [PreserveSig] int GetIconPath(out IntPtr s); [PreserveSig] int SetIconPath(IntPtr s, IntPtr c); [PreserveSig] int GetGroupingParam(out Guid g);
        [PreserveSig] int SetGroupingParam(ref Guid g, IntPtr c); [PreserveSig] int RegisterAudioSessionNotification(IntPtr n);
        [PreserveSig] int UnregisterAudioSessionNotification(IntPtr n);
        [PreserveSig] int GetSessionIdentifier(out IntPtr s); [PreserveSig] int GetSessionInstanceIdentifier(out IntPtr s);
        [PreserveSig] int GetProcessId(out uint pid); }
    [ComImport, Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioMeterInformation { [PreserveSig] int GetPeakValue(out float peak); }

    public static string Watch(uint pid, int milliseconds) {
        var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorCom());
        IMMDevice device; enumerator.GetDefaultAudioEndpoint(0, 1, out device);
        Guid iid = new Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"); object o;
        device.Activate(ref iid, 23, IntPtr.Zero, out o);
        var manager = (IAudioSessionManager2)o;
        float peak = 0; int samples = 0, sessionCount = 0; bool found = false;
        var end = DateTime.UtcNow.AddMilliseconds(milliseconds);
        while (DateTime.UtcNow < end) {
            IAudioSessionEnumerator sessions; manager.GetSessionEnumerator(out sessions);
            int n; sessions.GetCount(out n); sessionCount = n;
            for (int i = 0; i < n; i++) {
                IAudioSessionControl2 s; sessions.GetSession(i, out s);
                uint p; s.GetProcessId(out p);
                if (p == pid) {
                    found = true; float v; ((IAudioMeterInformation)s).GetPeakValue(out v); samples++;
                    if (v > peak) peak = v;
                }
            }
            Thread.Sleep(10);
        }
        return "peak=" + peak.ToString("0.0000", System.Globalization.CultureInfo.InvariantCulture) + " samples=" + samples + " sessions=" + sessionCount + " found=" + found;
    }
}
"@
[AudioPeaks]::Watch([uint32]$ProcessIdToWatch, [int]$Milliseconds)
