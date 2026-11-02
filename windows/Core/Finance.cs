using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Ponte;

/// <summary>Un movimento. Cents è negativo per le spese. Src: manual, cash, revolut.</summary>
public sealed record Tx(string Id, long Ts, long Cents, string Cat, string Note, string Src);

public sealed class Money
{
    public List<Tx> Txs { get; init; } = new();
    /// <summary>Banconote in tasca: taglio in centesimi → quante.</summary>
    public Dictionary<int, int> Cash { get; init; } = new();
    public long RevBase { get; init; }
    public long RevTs { get; init; }
    public long CashCents => Cash.Sum(kv => (long)kv.Key * kv.Value);
    public long RevolutCents => RevBase + Txs.Where(t => t.Src == "revolut" && t.Ts > RevTs).Sum(t => t.Cents);
    public long OtherCents => Txs.Where(t => t.Src == "manual").Sum(t => t.Cents);
    public long TotalCents => CashCents + RevolutCents + OtherCents;
}

/// <summary>
/// Finanze, identiche ad Android (it.ponte.Finance): registri "l'ultimo che scrive vince" sincronizzati dal server
/// (/api/finance) e, se il server non risponde, direttamente tra i dispositivi con il messaggio "fin".
/// </summary>
public sealed class Finance
{
    public static readonly string[] Categories = { "Cibo", "Casa", "Trasporti", "Svago", "Salute", "Shopping", "Abbonamenti", "Studio", "Altro", "Entrate" };
    public static readonly int[] Notes = { 500, 1000, 2000, 5000, 10000, 20000, 50000 };

    private sealed record Rec(long T, long C, string K, string N, string S, long U, bool D);

    private readonly Router _r;
    private readonly string _file = Path.Combine(Settings.Dir, "finance.json");
    private readonly object _lock = new();
    private readonly Dictionary<string, Rec> _tx = new();
    private readonly Dictionary<int, (int N, long U)> _cash = new();
    private (long Base, long Ts, long U) _rev;
    private HttpClient? _http;
    private CancellationTokenSource? _debounce;

    public Money State { get; private set; } = new();

    public Finance(Router r)
    {
        _r = r;
        try { if (File.Exists(_file) && JsonNode.Parse(File.ReadAllText(_file)) is JsonObject o) FromJson(o); }
        catch (Exception) { }
        Publish();
    }

    private static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private void Publish()
    {
        lock (_lock)
        {
            State = new Money
            {
                Txs = _tx.Where(kv => !kv.Value.D).Select(kv => new Tx(kv.Key, kv.Value.T, kv.Value.C, kv.Value.K, kv.Value.N, kv.Value.S)).OrderByDescending(t => t.Ts).ToList(),
                Cash = _cash.Where(kv => kv.Value.N > 0).ToDictionary(kv => kv.Key, kv => kv.Value.N),
                RevBase = _rev.Base,
                RevTs = _rev.Ts,
            };
        }
        _r.Raise();
    }

    private JsonObject ToJson()
    {
        lock (_lock)
        {
            var tx = new JsonObject();
            foreach (var (id, v) in _tx)
                tx[id] = new JsonObject { ["t"] = v.T, ["c"] = v.C, ["k"] = v.K, ["n"] = v.N, ["s"] = v.S, ["u"] = v.U, ["d"] = v.D ? 1 : 0 };
            var cash = new JsonObject();
            foreach (var (k, v) in _cash) cash[k.ToString(CultureInfo.InvariantCulture)] = new JsonArray(v.N, v.U);
            return new JsonObject { ["tx"] = tx, ["cash"] = cash, ["rev"] = new JsonArray(_rev.Base, _rev.Ts, _rev.U) };
        }
    }

    private static long Num(JsonNode? n)
    {
        if (n is not JsonValue v) return 0;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<double>(out var d)) return (long)d;
        if (v.TryGetValue<int>(out var i)) return i;
        return 0;
    }

    private static string Str(JsonNode? n, string fallback = "") => n is JsonValue v && v.TryGetValue<string>(out var s) ? s : fallback;

    /// <summary>Unisce un documento ricevuto: per ogni voce vince la modifica più recente. True se qualcosa è cambiato.</summary>
    private bool FromJson(JsonObject o)
    {
        var changed = false;
        lock (_lock)
        {
            if (o["tx"] is JsonObject tx)
                foreach (var (id, node) in tx)
                {
                    if (node is not JsonObject v) continue;
                    var u = Num(v["u"]);
                    if (_tx.TryGetValue(id, out var old) && old.U >= u) continue;
                    _tx[id] = new Rec(Num(v["t"]), Num(v["c"]), Str(v["k"], "Altro"), Str(v["n"]), Str(v["s"], "manual"), u, Num(v["d"]) == 1);
                    changed = true;
                }
            if (o["cash"] is JsonObject cash)
                foreach (var (k, node) in cash)
                {
                    if (node is not JsonArray a || a.Count < 2 || !int.TryParse(k, out var denom)) continue;
                    var u = Num(a[1]);
                    if (_cash.TryGetValue(denom, out var old) && old.U >= u) continue;
                    _cash[denom] = ((int)Num(a[0]), u);
                    changed = true;
                }
            if (o["rev"] is JsonArray r && r.Count >= 3 && Num(r[2]) > _rev.U)
            {
                _rev = (Num(r[0]), Num(r[1]), Num(r[2]));
                changed = true;
            }
        }
        return changed;
    }

    private void Save()
    {
        try
        {
            Directory.CreateDirectory(Settings.Dir);
            var tmp = _file + ".part";
            File.WriteAllText(tmp, ToJson().ToJsonString());
            File.Move(tmp, _file, true);
        }
        catch (Exception) { }
    }

    private void Mutate(Action f)
    {
        lock (_lock) f();
        Publish();
        Save();
        _debounce?.Cancel();
        var cts = _debounce = new CancellationTokenSource();
        _ = Task.Run(async () =>
        {
            try { await Task.Delay(1200, cts.Token); await Push(); } catch (Exception) { }
        });
    }

    // ---------------------------------------------------------------- sincronizzazione

    public async Task<bool> SyncServer()
    {
        var baseUrl = _r.S.HttpBase();
        if (baseUrl == null || _r.S.Token.Length == 0) return false;
        _http ??= new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Post, baseUrl + "/api/finance")
            {
                Content = new StringContent(ToJson().ToJsonString(), Encoding.UTF8, "application/json"),
            };
            req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", _r.S.Token);
            using var resp = await _http.SendAsync(req);
            if (!resp.IsSuccessStatusCode) return false;
            if (JsonNode.Parse(await resp.Content.ReadAsStringAsync()) is JsonObject doc && FromJson(doc)) { Publish(); Save(); }
            return true;
        }
        catch (Exception) { return false; }
    }

    /// <summary>Ai dispositivi raggiungibili (server come relè, rete locale, Bluetooth): il ripiego se il server non risponde.</summary>
    private void Broadcast()
    {
        var doc = ToJson();
        if (doc.ToJsonString().Length < 400_000) _r.Send(Proto.NewMsg("fin", ("doc", doc)));
    }

    public async Task Push()
    {
        await SyncServer();
        Broadcast();
    }

    public void OnRemote(JsonObject? doc)
    {
        if (doc != null && FromJson(doc)) { Publish(); Save(); }
    }

    public void Start()
    {
        _ = Task.Run(async () =>
        {
            await Task.Delay(4000);
            while (true)
            {
                try { await Push(); } catch (Exception) { }
                await Task.Delay(TimeSpan.FromMinutes(2));
            }
        });
    }

    // ---------------------------------------------------------------- operazioni

    public void Add(long cents, string cat, string note, string src = "manual", long? ts = null)
    {
        var t = ts ?? Now;
        lock (_lock)
            if (_tx.Values.Any(v => !v.D && v.S == src && v.C == cents && v.N == note && Math.Abs(v.T - t) < 120_000)) return;
        var id = Guid.NewGuid().ToString("N")[..12];
        Mutate(() => _tx[id] = new Rec(t, cents, cat, note, src, Now, false));
    }

    public void Remove(string id) => Mutate(() =>
    {
        if (_tx.TryGetValue(id, out var v)) _tx[id] = v with { U = Now, D = true };
    });

    public void Bump(int denom, int by) => Mutate(() =>
    {
        var cur = _cash.TryGetValue(denom, out var v) ? v.N : 0;
        _cash[denom] = (Math.Max(0, cur + by), Now);
    });

    public void SetRevolut(long cents) => Mutate(() => _rev = (cents, Now, Now));

    public static long? ToCents(string raw)
    {
        var t = raw.Trim().TrimEnd('.', ',');
        if (t.Length == 0) return null;
        int lc = t.LastIndexOf(','), ld = t.LastIndexOf('.');
        if (lc >= 0 && ld >= 0) t = lc > ld ? t.Replace(".", "").Replace(',', '.') : t.Replace(",", "");
        else if (lc >= 0) t = t.Replace(',', '.');
        return decimal.TryParse(t, NumberStyles.Number, CultureInfo.InvariantCulture, out var d) ? (long)Math.Round(d * 100) : null;
    }

    public static string Guess(string note, bool income = false)
    {
        if (income) return "Entrate";
        var n = note.ToLowerInvariant();
        bool Any(params string[] k) => k.Any(n.Contains);
        if (Any("esselunga", "coop", "conad", "carrefour", "lidl", "eurospin", "bar ", "ristor", "pizz", "mcdonald", "burger", "panific", "supermerc", "just eat", "glovo", "deliveroo", "caff")) return "Cibo";
        if (Any("affitto", "bolletta", "enel", "eni ", "a2a", "ikea", "luce", "gas ", "condomin")) return "Casa";
        if (Any("trenitalia", "italo", "atm", "uber", "taxi", "bolt", "benzin", "q8", "shell", "autostrad", "flixbus", "ryanair", "easyjet", "bus")) return "Trasporti";
        if (Any("netflix", "spotify", "disney", "prime", "youtube", "icloud", "google one", "apple.com", "chatgpt", "openai", "anthropic")) return "Abbonamenti";
        if (Any("farmaci", "pharm", "ospedale", "medic", "dentist", "clinic")) return "Salute";
        if (Any("cinema", "steam", "playstation", "xbox", "nintendo", "concert", "ticket", "bowling", "discoteca")) return "Svago";
        if (Any("amazon", "zalando", "h&m", "zara", "decathlon", "mediaworld", "unieuro")) return "Shopping";
        if (Any("univers", "unibs", "libri", "libreria", "tasse", "cancelleria")) return "Studio";
        return "Altro";
    }

    /// <summary>Estratto Revolut CSV (Tipo, Prodotto, Data inizio, Data completamento, Descrizione, Importo, Costo, Valuta, Stato, Saldo).</summary>
    public int ImportCsv(string text)
    {
        var before = State.Txs.Count(t => t.Src == "revolut");
        long lastTs = 0;
        long? lastBal = null;
        foreach (var line in text.Split('\n').Skip(1))
        {
            if (string.IsNullOrWhiteSpace(line)) continue;
            var c = SplitCsv(line.TrimEnd('\r'));
            if (c.Count < 9 || !c[8].ToUpperInvariant().StartsWith("COMPLET")) continue;
            if (!DateTime.TryParseExact(c[3].Length > 0 ? c[3] : c[2], "yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture, DateTimeStyles.AssumeLocal, out var when)) continue;
            var ts = new DateTimeOffset(when).ToUnixTimeMilliseconds();
            var amt = ToCents(c[5].TrimStart('-'));
            if (amt == null) continue;
            var neg = c[5].Trim().StartsWith("-");
            var fee = c.Count > 6 ? ToCents(c[6].TrimStart('-')) ?? 0 : 0;
            var cents = (neg ? -amt.Value : amt.Value) - fee;
            var note = c[4].Length > 60 ? c[4][..60] : c[4];
            Add(cents, Guess(note, cents > 0), note, "revolut", ts);
            if (ts >= lastTs && c.Count > 9)
            {
                lastTs = ts;
                var b = ToCents(c[9].TrimStart('-'));
                if (b != null) lastBal = c[9].Trim().StartsWith("-") ? -b : b;
            }
        }
        if (lastBal != null) { var bal = lastBal.Value; var ts = lastTs; Mutate(() => _rev = (bal, ts, Now)); }
        return State.Txs.Count(t => t.Src == "revolut") - before;
    }

    private static List<string> SplitCsv(string line)
    {
        var o = new List<string>();
        var sb = new StringBuilder();
        var q = false;
        foreach (var ch in line)
        {
            if (ch == '"') q = !q;
            else if (ch == ',' && !q) { o.Add(sb.ToString()); sb.Clear(); }
            else sb.Append(ch);
        }
        o.Add(sb.ToString());
        return o;
    }

    public static string Eur(long cents) => (cents / 100.0).ToString("C", CultureInfo.GetCultureInfo("it-IT"));
}
