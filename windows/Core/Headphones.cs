using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using NAudio.CoreAudioApi;

namespace Ponte;

public sealed record HpInfo(string Address, string Name, bool Connected);

/// <summary>
/// Passaggio automatico delle cuffie, alla Apple.
/// Il dispositivo "in uso" è l'ultimo su cui hai mosso il mouse, toccato o premuto un tasto.
/// Quando su quel dispositivo parte l'audio, le cuffie preferite passano lì e gli altri le lasciano.
/// Se non arrivano vale la riserva (casse o muto). "Blocca qui" le tiene dove sono.
/// Al più un dispositivo tiene le cuffie; dopo un po' di silenzio le lascia libere.
/// Il collegamento si governa attivando o disattivando i servizi audio Bluetooth del dispositivo.
/// </summary>
public sealed class Headphones
{
    private static readonly Guid[] Services =
    {
        new("0000110B-0000-1000-8000-00805F9B34FB"), // A2DP (ascolto)
        new("0000111E-0000-1000-8000-00805F9B34FB"), // Vivavoce
        new("00001108-0000-1000-8000-00805F9B34FB"), // Auricolare
    };

    private readonly Router _r;
    private readonly Dictionary<string, DateTime> _since = new();
    private HashSet<string> _connectedBefore = new();
    private DateTime _lastActive = DateTime.MinValue;
    private DateTime _lastFocusSent = DateTime.MinValue;
    private DateTime _myClaim = DateTime.MinValue;
    private uint _lastInputTick;
    private bool _playing;
    private bool _mutedByUs;
    private readonly MMDeviceEnumerator _enum = new();

    public IReadOnlyList<HpInfo> List { get; private set; } = Array.Empty<HpInfo>();
    public string? Holder { get; private set; }
    public string? Focus { get; private set; }
    public string? LockedBy { get; private set; }

    private string Me => _r.DeviceId;

    public Headphones(Router r) => _r = r;

    public void Start()
    {
        _ = Task.Run(async () =>
        {
            while (true)
            {
                try { Tick(); } catch (Exception) { }
                await Task.Delay(_r.S.Headphones.Count == 0 && _r.S.PreferredHp.Length == 0 ? 60_000 : 5_000);
            }
        });
        // Mouse, tastiera e audio: controlli leggeri ogni secondo, solo se il passaggio automatico è acceso.
        _ = Task.Run(async () =>
        {
            while (true)
            {
                try
                {
                    if (_r.S.PreferredHp.Length > 0)
                    {
                        CheckInput();
                        OnPlayback(AudioIsPlaying());
                    }
                }
                catch (Exception) { }
                await Task.Delay(_r.S.PreferredHp.Length > 0 ? 1_000 : 10_000);
            }
        });
    }

    // ------------------------------------------------------------------ dispositivo in uso

    [StructLayout(LayoutKind.Sequential)]
    private struct LastInputInfo { public uint Size; public uint Time; }

    [DllImport("user32.dll")] private static extern bool GetLastInputInfo(ref LastInputInfo info);

    private void CheckInput()
    {
        var li = new LastInputInfo { Size = (uint)Marshal.SizeOf<LastInputInfo>() };
        if (!GetLastInputInfo(ref li) || li.Time == _lastInputTick) return;
        var wasIdle = unchecked(li.Time - _lastInputTick) > 30_000;
        _lastInputTick = li.Time;
        var now = DateTime.UtcNow;
        if (Focus != Me || (wasIdle && now - _lastFocusSent > TimeSpan.FromSeconds(15)))
        {
            Focus = Me;
            _lastFocusSent = now;
            _r.Send(Proto.NewMsg("focus"));
            _r.Raise();
        }
    }

    public void OnFocus(JsonObject m)
    {
        Focus = m.S("from");
        _r.Raise();
    }

    // ------------------------------------------------------------------ blocco

    public void ToggleLock()
    {
        LockedBy = LockedBy == Me ? null : Me;
        _r.Send(Proto.NewMsg("audio.lock", ("by", LockedBy ?? "")));
        _r.Raise();
    }

    public void OnLock(JsonObject m)
    {
        var by = m.S("by");
        LockedBy = by.Length == 0 ? null : by;
        _r.Raise();
    }

    // ------------------------------------------------------------------ audio

    private bool AudioIsPlaying()
    {
        try
        {
            using var dev = _enum.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            return dev.AudioMeterInformation.MasterPeakValue > 0.002f;
        }
        catch (Exception) { return false; }
    }

    private void OnPlayback(bool active)
    {
        if (active) _lastActive = DateTime.UtcNow;
        if (active && !_playing) { _playing = true; OnAudioStart(); }
        else if (!active) _playing = false;
    }

    private int Prio() => LockedBy == Me ? 2 : Focus == Me ? 1 : 0;

    private bool MayTake() => LockedBy == Me || (LockedBy == null && (Focus == Me || Focus == null));

    private void OnAudioStart()
    {
        var hp = _r.S.PreferredHp;
        if (hp.Length == 0) return;
        var here = IsConnected(hp);
        if (!MayTake())
        {
            if (!here) Fallback();
            return;
        }
        Claim(hp);
        if (here) return;
        _ = Task.Run(async () =>
        {
            SetServices(hp, enable: true);
            await Task.Delay(8_000);
            List = Enumerate();
            if (!IsConnected(hp) && _playing) Fallback();
            _r.Raise();
        });
    }

    private void Fallback()
    {
        if (_r.S.FallbackMute) SetMute(true);
    }

    private void SetMute(bool mute)
    {
        if (mute == _mutedByUs) return;
        try
        {
            using var dev = _enum.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            dev.AudioEndpointVolume.Mute = mute;
            _mutedByUs = mute;
        }
        catch (Exception) { }
    }

    // ------------------------------------------------------------------ cuffie

    private bool IsConnected(string address) => List.Any(h => h.Address == address && h.Connected);

    private void Tick()
    {
        var before = List;
        List = Enumerate();
        var marked = new HashSet<string>(_r.S.Headphones);
        if (_r.S.PreferredHp.Length > 0) marked.Add(_r.S.PreferredHp);
        var mine = List.Where(h => h.Connected && marked.Contains(h.Address)).Select(h => h.Address).ToHashSet();
        var now = DateTime.UtcNow;

        // Cuffie appena collegate qui (anche a mano): se tocca a noi le teniamo e avvisiamo gli altri, se no le lasciamo.
        foreach (var a in mine.Except(_connectedBefore))
        {
            SetMute(false);
            if (LockedBy == Me || (LockedBy == null && (Focus == Me || Holder == null || Holder == Me))) Claim(a);
            else Release(a);
        }
        // Cuffie appena andate via mentre l'audio suona: riserva.
        if (_connectedBefore.Except(mine).Any() && _playing) Fallback();
        _connectedBefore = mine;

        foreach (var k in _since.Keys.Where(k => !mine.Contains(k)).ToList()) _since.Remove(k);
        if (!List.SequenceEqual(before)) _r.Raise();
        if (mine.Count == 0) return;
        foreach (var a in mine) _since.TryAdd(a, now);

        var idle = _r.S.IdleMinutes;
        if (idle > 0 && !_playing && LockedBy != Me)
        {
            var since = _since.Values.Min();
            var reference = _lastActive > since ? _lastActive : since;
            if (now - reference > TimeSpan.FromMinutes(idle)) foreach (var a in mine) Release(a);
        }
    }

    public void Claim(string address)
    {
        _myClaim = DateTime.UtcNow;
        Holder = Me;
        _r.Send(Proto.NewMsg("bt.claim", ("hp", address), ("prio", Prio())));
        _r.Raise();
    }

    public void OnClaim(JsonObject m)
    {
        var addr = m.S("hp");
        var from = m.S("from");
        if (addr.Length == 0) return;
        Holder = from;
        _r.Raise();
        if (!IsConnected(addr)) return;
        // Due dispositivi le prendono insieme: vince il bloccato, poi quello in uso, poi l'id minore.
        var theirs = (int)m.L("prio");
        var mine = Prio();
        if (DateTime.UtcNow - _myClaim < TimeSpan.FromSeconds(5) &&
            (mine > theirs || (mine == theirs && string.CompareOrdinal(Me, from) < 0)))
        {
            Holder = Me;
            return;
        }
        Release(addr);
        if (_playing) Fallback();
    }

    /// <summary>Collega le cuffie a questo PC e avvisa gli altri di lasciarle.</summary>
    public void Connect(string address)
    {
        Claim(address);
        SetServices(address, enable: true);
        _lastActive = DateTime.UtcNow;
    }

    /// <summary>Scollega le cuffie da questo PC e le tiene libere finché non si preme "Collega qui".</summary>
    public void Release(string address)
    {
        SetServices(address, enable: false);
        List = Enumerate();
        _r.Raise();
    }

    // ------------------------------------------------------------------ Bluetooth (bthprops.cpl)

    private static string Fmt(ulong a) => string.Join(":", Enumerable.Range(0, 6).Reverse().Select(i => ((a >> (8 * i)) & 0xFF).ToString("X2")));

    private static List<IntPtr> Radios()
    {
        var res = new List<IntPtr>();
        var p = new FindRadioParams { Size = Marshal.SizeOf<FindRadioParams>() };
        var h = BluetoothFindFirstRadio(ref p, out var radio);
        if (h == IntPtr.Zero) return res;
        do res.Add(radio); while (BluetoothFindNextRadio(h, out radio));
        BluetoothFindRadioClose(h);
        return res;
    }

    private static IReadOnlyList<HpInfo> Enumerate()
    {
        var res = new List<HpInfo>();
        foreach (var radio in Radios())
        {
            var sp = new SearchParams
            {
                Size = Marshal.SizeOf<SearchParams>(), ReturnAuthenticated = 1, ReturnRemembered = 1, ReturnConnected = 1, Radio = radio,
            };
            var info = NewInfo();
            var h = BluetoothFindFirstDevice(ref sp, ref info);
            if (h == IntPtr.Zero) continue;
            do
            {
                // Classe 0x04 = audio e video (cuffie, auricolari, altoparlanti).
                if (((info.ClassOfDevice >> 8) & 0x1F) == 4) res.Add(new HpInfo(Fmt(info.Address), info.Name, info.Connected != 0));
                info = NewInfo();
            } while (BluetoothFindNextDevice(h, ref info));
            BluetoothFindDeviceClose(h);
        }
        return res.OrderBy(x => x.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
    }

    private static DeviceInfo NewInfo() => new() { Size = Marshal.SizeOf<DeviceInfo>(), Name = "" };

    private static void SetServices(string address, bool enable)
    {
        foreach (var radio in Radios())
        {
            var sp = new SearchParams
            {
                Size = Marshal.SizeOf<SearchParams>(), ReturnAuthenticated = 1, ReturnRemembered = 1, ReturnConnected = 1, Radio = radio,
            };
            var info = NewInfo();
            var h = BluetoothFindFirstDevice(ref sp, ref info);
            if (h == IntPtr.Zero) continue;
            do
            {
                if (Fmt(info.Address) == address)
                {
                    foreach (var g in Services)
                    {
                        var guid = g;
                        BluetoothSetServiceState(radio, ref info, ref guid, enable ? 1u : 0u);
                    }
                    BluetoothFindDeviceClose(h);
                    return;
                }
                info = NewInfo();
            } while (BluetoothFindNextDevice(h, ref info));
            BluetoothFindDeviceClose(h);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct FindRadioParams { public int Size; }

    [StructLayout(LayoutKind.Sequential)]
    private struct SearchParams
    {
        public int Size;
        public int ReturnAuthenticated, ReturnRemembered, ReturnUnknown, ReturnConnected, IssueInquiry;
        public byte TimeoutMultiplier;
        public IntPtr Radio;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SystemTime { public ushort A, B, C, D, E, F, G, H; }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DeviceInfo
    {
        public int Size;
        public ulong Address;
        public uint ClassOfDevice;
        public int Connected, Remembered, Authenticated;
        public SystemTime LastSeen, LastUsed;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)] public string Name;
    }

    [DllImport("bthprops.cpl", SetLastError = true)] private static extern IntPtr BluetoothFindFirstRadio(ref FindRadioParams p, out IntPtr radio);
    [DllImport("bthprops.cpl", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool BluetoothFindNextRadio(IntPtr h, out IntPtr radio);
    [DllImport("bthprops.cpl", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool BluetoothFindRadioClose(IntPtr h);
    [DllImport("bthprops.cpl", SetLastError = true, CharSet = CharSet.Unicode)] private static extern IntPtr BluetoothFindFirstDevice(ref SearchParams p, ref DeviceInfo info);
    [DllImport("bthprops.cpl", SetLastError = true, CharSet = CharSet.Unicode)] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool BluetoothFindNextDevice(IntPtr h, ref DeviceInfo info);
    [DllImport("bthprops.cpl", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool BluetoothFindDeviceClose(IntPtr h);
    [DllImport("bthprops.cpl", SetLastError = true, CharSet = CharSet.Unicode)] private static extern uint BluetoothSetServiceState(IntPtr radio, ref DeviceInfo info, ref Guid service, uint flags);
}
