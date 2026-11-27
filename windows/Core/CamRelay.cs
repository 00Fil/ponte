using System.Text.Json.Nodes;

namespace Ponte;

/// <summary>Un avviso delle telecamere: Time in secondi, Image è l'indirizzo (firmato, a scadenza) della foto.</summary>
public sealed record CamAlert(long Time, int Channel, string Type, string Image);

/// <summary>
/// Le telecamere viste attraverso il telefono (PROTOCOLLO.md, "Telecamere"): si dice che cosa si sta guardando, tornano stato e immagini.
/// Il motore del produttore c'è solo su Android, quindi serve il telefono acceso e collegato.
/// </summary>
public sealed class CamRelay
{
    private const long Silence = 12_000;

    private readonly Router _r;
    private readonly object _lock = new();
    private readonly Dictionary<int, byte[]> _frames = new();
    private string _phone = "";
    private long _heard;
    private bool _watching;

    /// <summary>Null finché nessun telefono ha risposto; false = telecamere non configurate sul telefono.</summary>
    public bool? Ready { get; private set; }
    /// <summary>Come su Android: 0 in corso, 6 collegato, 10 password errata, il resto non raggiungibile.</summary>
    public int Status { get; private set; } = -1;
    public string Error { get; private set; } = "";
    public IReadOnlyList<int> Live { get; private set; } = Array.Empty<int>();
    public IReadOnlyList<int> Seen { get; private set; } = Array.Empty<int>();
    public IReadOnlyList<CamAlert> Alerts { get; private set; } = Array.Empty<CamAlert>();

    /// <summary>Un'immagine nuova (canale, JPEG). Arriva da un thread di rete.</summary>
    public event Action<int, byte[]>? Frame;

    public CamRelay(Router r) => _r = r;

    private static long Now => Environment.TickCount64;

    private List<Device> Phones() => _r.Devices.Where(d => d.Caps.Contains("cams")).ToList();
    public bool HasPhone => Phones().Count > 0;
    /// <summary>Il telefono ha risposto da poco.</summary>
    public bool Answering => _phone.Length > 0 && Now - Interlocked.Read(ref _heard) < Silence;

    /// <summary>L'ultima immagine ricevuta di un canale.</summary>
    public byte[]? Last(int channel)
    {
        lock (_lock) return _frames.TryGetValue(channel, out var f) ? f : null;
    }

    public void Watch(string view, int channel, bool hd, IEnumerable<int> hide, bool alerts)
    {
        var phones = Phones();
        // finché risponde si parla solo col telefono che le ha, altrimenti si chiede a tutti
        var chosen = phones.Where(p => p.Id == _phone).ToList();
        if (chosen.Count == 0 || !Answering || Ready != true) chosen = phones;
        var hidden = hide.ToList();
        foreach (var p in chosen)
            _r.Send(Proto.NewMsg("cam.watch", ("to", p.Id), ("view", view), ("ch", channel), ("hd", hd),
                ("hide", new JsonArray(hidden.Select(c => (JsonNode?)JsonValue.Create(c)).ToArray())), ("alerts", alerts)));
        _watching = true;
    }

    public void Stop()
    {
        if (!_watching) return;
        _watching = false;
        foreach (var p in Phones())
            _r.Send(Proto.NewMsg("cam.watch", ("to", p.Id), ("view", "off")));
    }

    /// <summary>Modo dell'immagine: smart, light, ir, daylight, night.</summary>
    public void Light(int channel, string mode)
    {
        if (_phone.Length > 0) _r.Send(Proto.NewMsg("cam.cmd", ("to", _phone), ("ch", channel), ("light", mode)));
    }

    public void OnState(JsonObject m)
    {
        var from = m.S("from");
        var ready = m["ready"] is JsonValue v && v.TryGetValue<bool>(out var b) && b;
        bool changed;
        lock (_lock)
        {
            // con più telefoni vince quello che ha le telecamere
            if (!ready && from != _phone && Ready == true && Answering) return;
            var live = Channels(m["live"]);
            var seen = Channels(m["seen"]);
            var status = (int)m.L("status", -1);
            var error = m.S("error");
            // "live" cambia a ogni giro del carosello: conta solo finché non ci sono ancora immagini
            changed = _phone != from || Ready != ready || Status != status || Error != error || !seen.SequenceEqual(Seen)
                || (seen.Count == 0 && !live.SequenceEqual(Live)) || !Answering;
            _phone = from;
            Interlocked.Exchange(ref _heard, Now);
            Ready = ready;
            Status = status;
            Error = error;
            Live = live;
            Seen = seen;
            if (m["alerts"] is JsonArray a)
            {
                var list = a.OfType<JsonObject>().Select(o => new CamAlert(o.L("time"), (int)o.L("channel"), o.S("type"), o.S("img"))).ToList();
                // gli indirizzi cambiano a ogni giro: si ridisegna solo se cambiano gli avvisi
                changed |= !list.Select(x => (x.Time, x.Channel)).SequenceEqual(Alerts.Select(x => (x.Time, x.Channel)));
                Alerts = list;
            }
        }
        if (changed) _r.Raise();
    }

    private static List<int> Channels(JsonNode? n) =>
        n is JsonArray a ? a.Select(x => x is JsonValue v && v.TryGetValue<int>(out var i) ? i : -1).Where(i => i is >= 0 and < 64).ToList() : new();

    public void OnFrame(JsonObject m)
    {
        if (m.S("from") != _phone) return;
        var channel = (int)m.L("ch", -1);
        if (channel is < 0 or >= 64) return;
        byte[] jpeg;
        try { jpeg = Convert.FromBase64String(m.S("jpg")); } catch (FormatException) { return; }
        if (jpeg.Length == 0) return;
        lock (_lock) _frames[channel] = jpeg;
        Interlocked.Exchange(ref _heard, Now);
        Frame?.Invoke(channel, jpeg);
    }
}
