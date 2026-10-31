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
/// Le cuffie hanno 2 posti: due dispositivi (PC e telefono) le tengono collegate insieme e il passaggio è immediato.
/// Solo un terzo che le chiede libera un posto, e lo lascia quello usato meno di recente (mai il bloccato, per ultimo chi suona).
/// Il dispositivo in uso si decide dall'ultimo input di ciascuno, non dall'ordine dei messaggi. Dopo un po' di silenzio le cuffie si lasciano libere.
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
    private DateTime _lastAnnounce = DateTime.MinValue;
    private HashSet<string> _connectedNow = new();
    private const int Slots = 2;
    private readonly Dictionary<string, DateTime> _lastInputAt = new();
    private sealed record HpState(bool On, bool Play, DateTime Use, DateTime Seen);
    private readonly Dictionary<string, Dictionary<string, HpState>> _holders = new();
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
        var was = Focus;
        _lastInputAt[Me] = now;
        Focus = ComputeFocus();
        if (was != Me || (wasIdle && now - _lastFocusSent > TimeSpan.FromSeconds(15)))
        {
            _lastFocusSent = now;
            _r.Send(Proto.NewMsg("focus", ("ago", 0)));
            _r.Raise();
        }
    }

    public void OnFocus(JsonObject m)
    {
        var from = m.S("from");
        if (from.Length == 0) return;
        _lastInputAt[from] = DateTime.UtcNow - TimeSpan.FromMilliseconds(m.L("ago"));
        Focus = ComputeFocus();
        _r.Raise();
    }

    /// <summary>Il dispositivo in uso è quello con l'input più recente; se nessuno ha toccato nulla da 10 minuti, nessuno.</summary>
    private string? ComputeFocus()
    {
        var now = DateTime.UtcNow;
        return _lastInputAt.Where(kv => now - kv.Value < TimeSpan.FromMinutes(10)).OrderByDescending(kv => kv.Value).Select(kv => kv.Key).FirstOrDefault();
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
            // Collegate dalle cuffie stesse o a mano: le tengo se c'è un posto libero o se qui è in uso; se tocca a un altro, le lascio.
            if (LockedBy == Me || (LockedBy == null && (Focus == Me || FreeSlot(a)))) Claim(a);
            else Release(a);
        }
        // Cuffie appena andate via mentre l'audio suona: riserva.
        if (_connectedBefore.Except(mine).Any() && _playing) Fallback();
        _connectedBefore = mine;

        // Annuncio lo stato delle cuffie quando cambia e comunque ogni 45 secondi, così gli altri sanno chi occupa i posti.
        var changed = !mine.SetEquals(_connectedNow);
        _connectedNow = mine;
        if (changed || now - _lastAnnounce > TimeSpan.FromSeconds(45))
        {
            _lastAnnounce = now;
            foreach (var a in marked.Union(_holders.Keys).ToList()) Announce(a, mine.Contains(a));
        }

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

    private Dictionary<string, HpState> Live(string hp)
    {
        var now = DateTime.UtcNow;
        if (!_holders.TryGetValue(hp, out var m)) return new();
        foreach (var k in m.Where(kv => !kv.Value.On || now - kv.Value.Seen > TimeSpan.FromSeconds(150)).Select(kv => kv.Key).ToList()) m.Remove(k);
        return new Dictionary<string, HpState>(m);
    }

    /// <summary>C'è un posto libero sulle cuffie, senza contare questo PC?</summary>
    private bool FreeSlot(string hp) => Live(hp).Count(kv => kv.Key != Me) < Slots;

    private Dictionary<string, HpState> Map(string hp)
    {
        if (!_holders.TryGetValue(hp, out var m)) _holders[hp] = m = new();
        return m;
    }

    private void Announce(string hp, bool on)
    {
        var now = DateTime.UtcNow;
        var use = _lastActive > _lastInputAt.GetValueOrDefault(Me) ? _lastActive : _lastInputAt.GetValueOrDefault(Me);
        Map(hp)[Me] = new HpState(on, _playing, use == default ? now : use, now);
        _r.Send(Proto.NewMsg("bt.state", ("hp", hp), ("on", on), ("play", _playing), ("ago", use == default ? 3_600_000L : (long)(now - use).TotalMilliseconds)));
    }

    public void OnState(JsonObject m)
    {
        var hp = m.S("hp");
        var from = m.S("from");
        if (hp.Length == 0 || from.Length == 0) return;
        var now = DateTime.UtcNow;
        Map(hp)[from] = new HpState(m["on"] is JsonValue v && v.TryGetValue<bool>(out var on) && on,
            m["play"] is JsonValue p && p.TryGetValue<bool>(out var pl) && pl, now - TimeSpan.FromMilliseconds(m.L("ago")), now);
    }

    public void Claim(string address)
    {
        var now = DateTime.UtcNow;
        Map(address)[Me] = new HpState(true, _playing, now, now);
        Holder = Me;
        _r.Send(Proto.NewMsg("bt.claim", ("hp", address), ("prio", Prio())));
        _r.Raise();
    }

    /// <summary>
    /// Qualcuno chiede le cuffie. Con un posto libero nessuno fa niente (restano collegati entrambi). Se i posti sono pieni
    /// le lascia chi vale meno: non bloccato, non in riproduzione, usato meno di recente. Tutti applicano la stessa regola
    /// sugli stessi dati, quindi se ne va solo il dispositivo giusto.
    /// </summary>
    public void OnClaim(JsonObject m)
    {
        var addr = m.S("hp");
        var from = m.S("from");
        if (addr.Length == 0) return;
        Holder = from;
        _r.Raise();
        var now = DateTime.UtcNow;
        Map(addr)[from] = new HpState(true, false, now, now);
        if (!IsConnected(addr)) return;
        var myUse = _lastActive > _lastInputAt.GetValueOrDefault(Me) ? _lastActive : _lastInputAt.GetValueOrDefault(Me);
        Map(addr)[Me] = new HpState(true, _playing, myUse, now);
        var others = Live(addr).Where(kv => kv.Key != from).ToList();
        var count = others.Count - (Slots - 1);
        if (count <= 0) return;
        var victims = others
            .OrderBy(kv => LockedBy == kv.Key ? 1 : 0).ThenBy(kv => kv.Value.Play ? 1 : 0).ThenBy(kv => kv.Value.Use).ThenBy(kv => kv.Key, StringComparer.Ordinal)
            .Take(count).Select(kv => kv.Key).ToList();
        if (!victims.Contains(Me)) return;
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
        Announce(address, false);
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
