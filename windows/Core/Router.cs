using System.Text;
using System.Text.Json.Nodes;
using Microsoft.UI.Dispatching;

namespace Ponte;

/// <summary>Smista i messaggi tra i collegamenti (server e rete locale) e le funzioni.</summary>
public sealed class Router
{
    public Settings S { get; }
    public ClipboardSync Clip { get; private set; } = null!;
    public FileStore Files { get; }
    public AudioSource Audio { get; }
    public Notifier Notify { get; }
    public Headphones Hp { get; }
    public Updater Update { get; }

    private readonly HubLink _hub;
    private readonly LanMesh _lan;
    private readonly BtMesh _bt;
    private ScreenshotWatcher? _shots;

    private readonly object _lock = new();
    private readonly List<ILink> _links = new();
    private readonly HashSet<string> _seen = new();
    private readonly Queue<string> _seenOrder = new();
    private List<Device> _hubDevices = new();
    private readonly Dictionary<string, Device> _lanPeers = new();
    private string _hubState = "Non configurato";

    /// <summary>Si alza a ogni cambio di stato visibile (collegamenti, dispositivi, appunti, archivio, audio). Da qualsiasi thread.</summary>
    public event Action? Changed;

    public string DeviceId => S.DeviceId;

    public Router(Settings s)
    {
        S = s;
        Files = new FileStore(this);
        Audio = new AudioSource(this);
        Notify = new Notifier();
        Hp = new Headphones(this);
        Update = new Updater(this);
        _hub = new HubLink(this);
        _lan = new LanMesh(this);
        _bt = new BtMesh(this);
    }

    public void Start(DispatcherQueue ui)
    {
        Clip = new ClipboardSync(this, ui);
        Clip.Start();
        Hp.Start();
        _ = _bt.StartAsync();
        Update.Start();
        // Rete di sicurezza: l'arrivo istantaneo passa dal collegamento sempre aperto, questo recupera solo ciò che manca.
        _ = Task.Run(async () =>
        {
            while (true)
            {
                await Task.Delay(TimeSpan.FromMinutes(15));
                try { await SyncAll(); } catch (Exception) { }
            }
        });
        Reconfigure();
    }

    /// <summary>Da chiamare dopo ogni cambio di impostazioni.</summary>
    public void Reconfigure()
    {
        _hub.Restart();
        if (S.LanEnabled && S.Token.Length > 0) _lan.Restart(); else _lan.Stop();
        _shots?.Stop();
        _shots = null;
        if (S.Screenshots)
        {
            _shots = new ScreenshotWatcher(this);
            _shots.Start();
        }
        Changed?.Invoke();
    }

    public string HubState
    {
        get => _hubState;
        set { _hubState = value; Changed?.Invoke(); }
    }

    public IReadOnlyList<Device> Devices
    {
        get
        {
            lock (_lock)
            {
                return _lanPeers.Values.Concat(_hubDevices.Where(d => !_lanPeers.ContainsKey(d.Id)))
                    .OrderBy(d => d.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
            }
        }
    }

    public string DeviceName(string id) => Devices.FirstOrDefault(d => d.Id == id)?.Name ?? "un altro dispositivo";

    public List<string> Caps() => new() { "clip", "files", "audio-src", "notif-sink" };

    public JsonObject Hello() => Proto.NewMsg(
        "hello",
        ("from", DeviceId), ("name", S.Name), ("platform", "windows"),
        ("caps", new JsonArray(Caps().Select(c => (JsonNode?)JsonValue.Create(c)).ToArray())),
        ("addrs", new JsonArray(LocalAddrs().Select(a => (JsonNode?)JsonValue.Create(a)).ToArray())),
        ("port", Proto.LanTcpPort));

    /// <summary>Indirizzi IPv4 di questo PC, prima quelli della rete privata Headscale/Tailscale (100.64.0.0/10).</summary>
    public static List<string> LocalAddrs()
    {
        try
        {
            return System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == System.Net.NetworkInformation.OperationalStatus.Up
                            && n.NetworkInterfaceType != System.Net.NetworkInformation.NetworkInterfaceType.Loopback)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Where(a => a.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
                .Select(a => a.Address.ToString())
                .Where(a => !a.StartsWith("169.254."))
                .OrderBy(a => Proto.IsTailnet(a) ? 0 : 1)
                .Take(8).ToList();
        }
        catch (Exception) { return new(); }
    }

    public JsonObject HelloLan()
    {
        var h = Hello();
        h["proof"] = Lan.Proof(S.Token, DeviceId);
        return h;
    }

    // ---- collegamenti ----

    public void AddLink(ILink l)
    {
        lock (_lock)
        {
            if (l.PeerId != null)
                foreach (var old in _links.Where(x => x.PeerId == l.PeerId).ToList()) { _links.Remove(old); old.Close(); }
            _links.Add(l);
        }
    }

    public void RemoveLink(ILink l)
    {
        lock (_lock)
        {
            _links.Remove(l);
            if (l.Kind != "hub" && l.PeerId != null && !_links.Any(x => x.PeerId == l.PeerId && x.Kind != "hub")) _lanPeers.Remove(l.PeerId);
            if (l.Kind == "hub") _hubDevices = new();
        }
        Changed?.Invoke();
    }

    public bool HasLanLink(string id) { lock (_lock) return _links.Any(l => l.Kind == "lan" && l.PeerId == id); }

    public void CloseLanLinks()
    {
        List<ILink> lan;
        lock (_lock) lan = _links.Where(l => l.Kind == "lan").ToList();
        foreach (var l in lan) { l.Close(); RemoveLink(l); }
    }

    public void LanPeerSeen(Device d)
    {
        lock (_lock) _lanPeers[d.Id] = d;
        Changed?.Invoke();
    }

    public long MaxQueued() { lock (_lock) return _links.Count == 0 ? 0 : _links.Max(l => l.Queued); }

    public void OnHubConnected() => _ = Task.Run(SyncAll);

    /// <summary>Recupera dal server ciò che si fosse perso: archivio e cronologia degli appunti.</summary>
    public async Task SyncAll()
    {
        await Files.CatchUp();
        await Clip.CatchUp();
    }

    // ---- invio e ricezione ----

    private bool MarkSeen(string id)
    {
        lock (_lock)
        {
            if (!_seen.Add(id)) return false;
            _seenOrder.Enqueue(id);
            while (_seenOrder.Count > 4000) _seen.Remove(_seenOrder.Dequeue());
            return true;
        }
    }

    private List<ILink> Targets(string? to)
    {
        lock (_lock)
        {
            // Il Bluetooth è lento: si usa solo quando quel dispositivo non è raggiungibile in altro modo.
            var hubUp = _links.Any(l => l.Kind == "hub");
            var usable = _links.Where(l => l.Kind != "bt" || (!hubUp && !_links.Any(x => x.Kind == "lan" && x.PeerId == l.PeerId))).ToList();
            if (to == null) return usable;
            var direct = usable.Where(l => l.PeerId == to).ToList();
            return direct.Count > 0 ? direct : usable.Where(l => l.Kind == "hub").ToList();
        }
    }

    public void Send(JsonObject m)
    {
        m["from"] = DeviceId;
        MarkSeen(m.S("id"));
        var text = m.ToJsonString();
        var to = m.S("to");
        foreach (var l in Targets(to.Length > 0 ? to : null)) l.SendJson(text);
    }

    public bool SendAudio(string sinkId, byte[] packet)
    {
        var targets = Targets(sinkId);
        var t = targets.FirstOrDefault(l => l.Kind != "bt");
        return t != null && t.SendAudio(packet);
    }

    public void OnText(string text, ILink link)
    {
        JsonObject? m;
        try { m = JsonNode.Parse(text) as JsonObject; } catch (Exception) { return; }
        if (m == null) return;
        var id = m.S("id");
        var t = m.S("t");
        if (id.Length == 0 || t.Length == 0 || !MarkSeen(id)) return;
        var to = m.S("to");
        if (to.Length > 0 && to != DeviceId) return;

        // Un messaggio che manda in errore un modulo non deve far cadere il collegamento.
        try { Dispatch(m, t, link); }
        catch (Exception e) { Crash.Log(e, "Messaggio " + t); }
    }

    private void Dispatch(JsonObject m, string t, ILink link)
    {
        switch (t)
        {
            case "devices" when link.Kind == "hub":
                var list = new List<Device>();
                if (m["list"] is JsonArray a)
                    foreach (var n in a)
                        if (n is JsonObject d && d.S("id") != DeviceId)
                            list.Add(new Device(d.S("id"), d.S("name"), d.S("platform"), Proto.Caps(d), "server"));
                lock (_lock) _hubDevices = list;
                Changed?.Invoke();
                // Il server fa da rubrica: con gli indirizzi diretti ci si collega senza passare da lui.
                if (m["list"] is JsonArray all)
                    foreach (var n in all)
                        if (n is JsonObject d && d.S("id") != DeviceId && d["addrs"] is JsonArray ad && ad.Count > 0)
                            _lan.Dial(d.S("id"), ad.Select(x => x?.GetValue<string>() ?? "").ToList(), (int)d.L("port", Proto.LanTcpPort));
                break;
            case "clip":
                Clip.OnRemote(m.S("text"), m.S("from"));
                break;
            case "file.begin" or "file.chunk" or "file.end":
                Files.OnMessage(m);
                break;
            case "notif" when S.ShowPhoneNotifications:
                Notify.Show(m.S("app"), m.S("title"), m.S("text"), m.S("key"));
                break;
            case "bt.claim":
                Hp.OnClaim(m);
                break;
            case "bt.state":
                Hp.OnState(m);
                break;
            case "focus":
                Hp.OnFocus(m);
                break;
            case "audio.lock":
                Hp.OnLock(m);
                break;
            case "audio.route":
                Audio.Route(m["sink"] is JsonValue v && v.TryGetValue<string>(out var sink) ? sink : null);
                break;
        }
    }

    public void OnAudio(byte[] _) { /* Windows è solo sorgente audio */ }

    public void Raise() => Changed?.Invoke();
}
