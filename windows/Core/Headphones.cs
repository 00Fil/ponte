using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using NAudio.CoreAudioApi;

namespace Ponte;

public sealed record HpInfo(string Address, string Name, bool Connected);

/// <summary>
/// Cuffie con due soli slot Bluetooth: tiene collegato al massimo un dispositivo alla volta.
/// Quando qui parte l'audio avvisa gli altri (bt.claim), dopo un po' di inattività scollega le cuffie.
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
    private DateTime _lastActive = DateTime.MinValue;
    private bool _wasActive;
    private readonly MMDeviceEnumerator _enum = new();

    public IReadOnlyList<HpInfo> List { get; private set; } = Array.Empty<HpInfo>();

    public Headphones(Router r) => _r = r;

    public void Start() => Task.Run(async () =>
    {
        while (true)
        {
            try { Tick(); } catch (Exception) { }
            await Task.Delay(_r.S.Headphones.Count == 0 ? 60_000 : 10_000);
        }
    });

    private bool AudioIsPlaying()
    {
        try
        {
            using var dev = _enum.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            return dev.AudioMeterInformation.MasterPeakValue > 0.002f;
        }
        catch (Exception) { return false; }
    }

    private void Tick()
    {
        var before = List;
        List = Enumerate();
        var marked = _r.S.Headphones;
        var mine = List.Where(h => h.Connected && marked.Contains(h.Address)).ToList();
        var now = DateTime.UtcNow;
        foreach (var k in _since.Keys.Where(k => mine.All(h => h.Address != k)).ToList()) _since.Remove(k);
        if (!List.SequenceEqual(before)) _r.Raise();
        if (mine.Count == 0) { _wasActive = false; return; }
        foreach (var h in mine) _since.TryAdd(h.Address, now);

        var active = AudioIsPlaying();
        if (active)
        {
            _lastActive = now;
            if (!_wasActive) foreach (var h in mine) Claim(h.Address);
        }
        _wasActive = active;

        var idle = _r.S.IdleMinutes;
        if (idle > 0 && !active)
        {
            var since = _since.Values.Min();
            var reference = _lastActive > since ? _lastActive : since;
            if (now - reference > TimeSpan.FromMinutes(idle)) foreach (var h in mine) Release(h.Address);
        }
    }

    public void Claim(string address) => _r.Send(Proto.NewMsg("bt.claim", ("hp", address)));

    public void OnClaim(JsonObject m)
    {
        var addr = m.S("hp");
        if (addr.Length == 0 || !_r.S.Headphones.Contains(addr)) return;
        if (List.Any(h => h.Address == addr && h.Connected)) Release(addr);
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
