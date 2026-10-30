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

    private readonly HubLink _hub;
    private readonly LanMesh _lan;
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
        _hub = new HubLink(this);
        _lan = new LanMesh(this);
    }

    public void Start(DispatcherQueue ui)
    {
        Clip = new ClipboardSync(this, ui);
        Clip.Start();
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
        ("caps", new JsonArray(Caps().Select(c => (JsonNode?)JsonValue.Create(c)).ToArray())));

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
            if (l.Kind == "lan" && l.PeerId != null) _lanPeers.Remove(l.PeerId);
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

    public void OnHubConnected() => _ = Task.Run(Files.CatchUp);

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
            if (to == null) return _links.ToList();
            var direct = _links.Where(l => l.PeerId == to).ToList();
            return direct.Count > 0 ? direct : _links.Where(l => l.Kind == "hub").ToList();
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
        return targets.Count > 0 && targets[0].SendAudio(packet);
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
                break;
            case "clip":
                Clip.OnRemote(m.S("text"), m.S("from"));
                break;
            case "file.begin" or "file.chunk" or "file.end":
                Files.OnMessage(m);
                break;
            case "notif" when S.ShowPhoneNotifications:
                Notify.Show(m.S("app"), m.S("title"), m.S("text"));
                break;
            case "audio.route":
                Audio.Route(m["sink"] is JsonValue v && v.TryGetValue<string>(out var sink) ? sink : null);
                break;
        }
    }

    public void OnAudio(byte[] _) { /* Windows è solo sorgente audio */ }

    public void Raise() => Changed?.Invoke();
}
