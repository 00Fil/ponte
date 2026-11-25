using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;

namespace Ponte;

/// <summary>Valori nutrizionali: kcal e grammi. Per 100 g negli alimenti, per la quantità nel diario.</summary>
public readonly record struct Nut(double Kcal, double P, double C, double Sugar, double F, double Fiber, double Salt)
{
    public static Nut operator +(Nut a, Nut b) => new(a.Kcal + b.Kcal, a.P + b.P, a.C + b.C, a.Sugar + b.Sugar, a.F + b.F, a.Fiber + b.Fiber, a.Salt + b.Salt);
    public static Nut operator *(Nut a, double k) => new(a.Kcal * k, a.P * k, a.C * k, a.Sugar * k, a.F * k, a.Fiber * k, a.Salt * k);
    public JsonArray ToJson() => new(Kcal, P, C, Sugar, F, Fiber, Salt);
    public static Nut Of(JsonNode? n)
    {
        if (n is not JsonArray a) return default;
        double V(int i) => i < a.Count && a[i] is JsonValue v && v.TryGetValue<double>(out var d) ? d : 0;
        return new Nut(V(0), V(1), V(2), V(3), V(4), V(5), V(6));
    }
}

public sealed record Food(string Id, string Name, string Brand, int Serving, Nut Per100);
/// <summary>Una voce del diario: Meal 0 colazione, 1 pranzo, 2 cena, 3 spuntino.</summary>
public sealed record Entry(string Id, string Day, int Meal, long Ts, string Name, double Grams, Nut N, string FoodId);
public sealed record PlanItem(string FoodId, string Name, double Grams, Nut N);
/// <summary>Un pasto del piano: Dow 0 lunedì … 6 domenica, Minute dalla mezzanotte.</summary>
public sealed record Slot(string Id, int Dow, int Minute, int Meal, string Label, List<PlanItem> Items)
{
    public Nut Total => Items.Aggregate(default(Nut), (a, i) => a + i.N);
}
public sealed record Weigh(string Day, int Kg10) { public double Kg => Kg10 / 10.0; }
public sealed record Goals(int Kcal = 2200, int P = 140, int C = 250, int F = 70, int Fiber = 30, int GoalKg10 = 0, int Height = 0, int Sex = 0, int Age = 0, int Act = 1);

public sealed class BodyState
{
    public List<Food> Foods { get; init; } = new();
    public List<Entry> Entries { get; init; } = new();
    public List<Slot> Slots { get; init; } = new();
    public List<Weigh> Weights { get; init; } = new();
    public Goals Goals { get; init; } = new();
}

/// <summary>
/// Corpo, identico ad Android (it.ponte.Body): alimenti, diario, piano settimanale, pesate e obiettivi come registri "l'ultimo che scrive vince",
/// sincronizzati dal server (/api/body) e tra i dispositivi col messaggio "body". Le foto restano sul telefono.
/// </summary>
public sealed class BodyStore
{
    public static readonly string[] Meals = { "Colazione", "Pranzo", "Cena", "Spuntino" };
    private static readonly string[] Groups = { "fd", "lg", "sl", "wt", "cfg" };

    private readonly Router _r;
    private readonly string _file = Path.Combine(Settings.Dir, "body.json");
    private readonly object _lock = new();
    private readonly Dictionary<string, Dictionary<string, JsonObject>> _doc = new();
    private HttpClient? _http;
    private CancellationTokenSource? _debounce;

    public BodyState State { get; private set; } = new();

    public BodyStore(Router r)
    {
        _r = r;
        foreach (var g in Groups) _doc[g] = new();
        try { if (File.Exists(_file) && JsonNode.Parse(File.ReadAllText(_file)) is JsonObject o) Merge(o); } catch (Exception) { }
        Publish();
    }

    public static string DayKey(DateTime? d = null) => (d ?? DateTime.Now).ToString("yyyyMMdd", CultureInfo.InvariantCulture);
    public static DateTime KeyTime(string k) => DateTime.TryParseExact(k, "yyyyMMdd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d : DateTime.MinValue;
    private static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private static long Num(JsonNode? n)
    {
        if (n is not JsonValue v) return 0;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<double>(out var d)) return (long)d;
        if (v.TryGetValue<int>(out var i)) return i;
        return 0;
    }
    private static double Dbl(JsonNode? n, double def = 0) => n is JsonValue v && v.TryGetValue<double>(out var d) ? d : n is JsonValue w && w.TryGetValue<long>(out var l) ? l : def;
    private static string Str(JsonNode? n) => n is JsonValue v && v.TryGetValue<string>(out var s) ? s : "";

    private bool Merge(JsonObject o)
    {
        var changed = false;
        lock (_lock)
            foreach (var g in Groups)
            {
                if (o[g] is not JsonObject j) continue;
                var m = _doc[g];
                foreach (var (id, node) in j)
                {
                    if (node is not JsonObject v) continue;
                    var u = Num(v["u"]);
                    if (m.TryGetValue(id, out var old) && Num(old["u"]) >= u) continue;
                    m[id] = JsonNode.Parse(v.ToJsonString())!.AsObject();
                    changed = true;
                }
            }
        return changed;
    }

    private List<KeyValuePair<string, JsonObject>> Live(string g) => _doc[g].Where(kv => Num(kv.Value["d"]) != 1).ToList();

    private void Publish()
    {
        lock (_lock)
        {
            var cfg = _doc["cfg"].TryGetValue("g", out var c) && Num(c["d"]) != 1 ? c : null;
            int CI(string k, int def) => cfg != null && cfg[k] != null ? (int)Num(cfg[k]) : def;
            State = new BodyState
            {
                Foods = Live("fd").Select(kv => new Food(kv.Key, Str(kv.Value["n"]), Str(kv.Value["b"]), kv.Value["s"] != null ? (int)Num(kv.Value["s"]) : 100, Nut.Of(kv.Value["v"]))).OrderBy(f => f.Name, StringComparer.CurrentCultureIgnoreCase).ToList(),
                Entries = Live("lg").Select(kv => new Entry(kv.Key, Str(kv.Value["d"]), (int)Num(kv.Value["m"]), Num(kv.Value["t"]), Str(kv.Value["n"]), Dbl(kv.Value["g"]), Nut.Of(kv.Value["v"]), Str(kv.Value["f"]))).OrderBy(e => e.Ts).ToList(),
                Slots = Live("sl").Select(kv =>
                {
                    var items = new List<PlanItem>();
                    if (kv.Value["it"] is JsonArray a)
                        foreach (var x in a.OfType<JsonObject>()) items.Add(new PlanItem(Str(x["f"]), Str(x["n"]), Dbl(x["g"]), Nut.Of(x["v"])));
                    return new Slot(kv.Key, (int)Num(kv.Value["w"]), (int)Num(kv.Value["h"]), (int)Num(kv.Value["m"]), Str(kv.Value["l"]), items);
                }).OrderBy(s => s.Dow).ThenBy(s => s.Minute).ToList(),
                Weights = Live("wt").Select(kv => new Weigh(kv.Key, (int)Num(kv.Value["k"]))).Where(w => w.Kg10 > 0).OrderBy(w => w.Day, StringComparer.Ordinal).ToList(),
                Goals = cfg == null ? new Goals() : new Goals(CI("k", 2200), CI("p", 140), CI("c", 250), CI("f", 70), CI("fi", 30), CI("gw", 0), CI("h", 0), CI("sx", 0), CI("a", 0), CI("ac", 1)),
            };
        }
        _r.Raise();
    }

    private JsonObject ToJson()
    {
        lock (_lock)
        {
            var o = new JsonObject();
            foreach (var g in Groups)
            {
                var j = new JsonObject();
                foreach (var (id, v) in _doc[g]) j[id] = JsonNode.Parse(v.ToJsonString());
                o[g] = j;
            }
            return o;
        }
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

    private void Put(string g, string id, JsonObject rec)
    {
        rec["u"] = Now;
        _doc[g][id] = rec;
    }

    private void Del(string g, string id)
    {
        if (_doc[g].TryGetValue(id, out var v)) { var c = JsonNode.Parse(v.ToJsonString())!.AsObject(); c["d"] = 1; c["u"] = Now; _doc[g][id] = c; }
    }

    private void Mutate(Action f)
    {
        lock (_lock) f();
        Publish();
        Save();
        _debounce?.Cancel();
        var cts = _debounce = new CancellationTokenSource();
        _ = Task.Run(async () => { try { await Task.Delay(1200, cts.Token); await Push(); } catch (Exception) { } });
    }

    // ---------------------------------------------------------------- sincronizzazione

    public async Task<bool> SyncServer()
    {
        var baseUrl = _r.S.HttpBase();
        if (baseUrl == null || _r.S.Token.Length == 0) return false;
        _http ??= new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Post, baseUrl + "/api/body") { Content = new StringContent(ToJson().ToJsonString(), Encoding.UTF8, "application/json") };
            req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", _r.S.Token);
            using var resp = await _http.SendAsync(req);
            if (!resp.IsSuccessStatusCode) return false;
            if (JsonNode.Parse(await resp.Content.ReadAsStringAsync()) is JsonObject doc && Merge(doc)) { Publish(); Save(); }
            return true;
        }
        catch (Exception) { return false; }
    }

    private void Broadcast()
    {
        var doc = ToJson();
        if (doc.ToJsonString().Length < 400_000) _r.Send(Proto.NewMsg("body", ("doc", doc)));
    }

    public async Task Push() { await SyncServer(); Broadcast(); }

    public void OnRemote(JsonObject? doc) { if (doc != null && Merge(doc)) { Publish(); Save(); } }

    public void Start()
    {
        _ = Task.Run(async () =>
        {
            await Task.Delay(5000);
            var first = true;
            while (true)
            {
                try { await Push(); if (first) { first = false; LoadPreset(); } } catch (Exception) { }
                await Task.Delay(TimeSpan.FromMinutes(5));
            }
        });
    }

    // ---------------------------------------------------------------- operazioni

    private static string NewId() => Guid.NewGuid().ToString("N")[..12];

    public string SaveFood(Food f)
    {
        var id = f.Id.Length > 0 ? f.Id : NewId();
        Mutate(() => Put("fd", id, new JsonObject { ["n"] = f.Name.Trim(), ["b"] = f.Brand.Trim(), ["s"] = f.Serving, ["v"] = f.Per100.ToJson() }));
        return id;
    }

    public void RemoveFood(string id) => Mutate(() => Del("fd", id));

    public void AddEntry(string day, int meal, string name, double grams, Nut n, string foodId = "") =>
        Mutate(() => Put("lg", NewId(), new JsonObject { ["d"] = day, ["m"] = meal, ["t"] = Now, ["n"] = name, ["g"] = grams, ["v"] = n.ToJson(), ["f"] = foodId }));

    public void RemoveEntry(string id) => Mutate(() => Del("lg", id));

    public void SetGrams(Entry e, double grams)
    {
        if (grams <= 0) return;
        var food = State.Foods.FirstOrDefault(f => f.Id == e.FoodId);
        var n = food != null ? food.Per100 * (grams / 100.0) : e.Grams > 0 ? e.N * (grams / e.Grams) : e.N;
        Mutate(() => Put("lg", e.Id, new JsonObject { ["d"] = e.Day, ["m"] = e.Meal, ["t"] = e.Ts, ["n"] = e.Name, ["g"] = grams, ["v"] = n.ToJson(), ["f"] = e.FoodId }));
    }

    private static JsonObject SlotJson(Slot s) => new()
    {
        ["w"] = s.Dow, ["h"] = s.Minute, ["m"] = s.Meal, ["l"] = s.Label,
        ["it"] = new JsonArray(s.Items.Select(i => (JsonNode)new JsonObject { ["f"] = i.FoodId, ["n"] = i.Name, ["g"] = i.Grams, ["v"] = i.N.ToJson() }).ToArray()),
    };

    public void RemoveSlot(string id) => Mutate(() => Del("sl", id));

    public void LogSlot(Slot s, string day) => Mutate(() =>
    {
        foreach (var i in s.Items) Put("lg", NewId(), new JsonObject { ["d"] = day, ["m"] = s.Meal, ["t"] = Now, ["n"] = i.Name, ["g"] = i.Grams, ["v"] = i.N.ToJson(), ["f"] = i.FoodId });
    });

    public void SetWeight(string day, int kg10) => Mutate(() => Put("wt", day, new JsonObject { ["k"] = kg10 }));
    public void ClearWeight(string day) => Mutate(() => Del("wt", day));

    public void SaveGoals(Goals g) => Mutate(() => Put("cfg", "g", new JsonObject
    {
        ["k"] = g.Kcal, ["p"] = g.P, ["c"] = g.C, ["f"] = g.F, ["fi"] = g.Fiber, ["gw"] = g.GoalKg10, ["h"] = g.Height, ["sx"] = g.Sex, ["a"] = g.Age, ["ac"] = g.Act,
    });

    // ---------------------------------------------------------------- piano su misura

    private static int JavaHash(string s) { var h = 0; foreach (var ch in s) h = unchecked(31 * h + ch); return h; }

    /// <summary>Una sola volta, a piano vuoto: stessi obiettivi, alimenti CREA e pasti caricati su Android.</summary>
    private void LoadPreset()
    {
        var flag = Path.Combine(Settings.Dir, "body_preset_v1");
        if (File.Exists(flag)) return;
        try { Directory.CreateDirectory(Settings.Dir); File.WriteAllText(flag, "1"); } catch (Exception) { }
        if (State.Slots.Count > 0) return;
        var foods = new Dictionary<string, Food>();
        PlanItem Pi(string display, string crea, int g)
        {
            var id = "";
            var b = Foods.Crea(crea);
            if (b != null) { id = "p" + ((uint)JavaHash(crea)).ToString("x"); foods[id] = b with { Id = id, Name = display, Brand = "CREA" }; }
            return new PlanItem(id, display, g, (b?.Per100 ?? default) * (g / 100.0));
        }
        PlanItem Frutta(int d) => (d % 3) switch { 0 => Pi("Mela", "Mele, fresche, golden", 150), 1 => Pi("Banana", "Banane, fresche", 120), _ => Pi("Arancia", "Arance Tarocco, fresche", 150) };
        var slots = new List<Slot>();
        for (var d = 0; d <= 6; d++)
        {
            var toast = d % 2 == 0;
            var colaz = toast
                ? new List<PlanItem> { Pi("Pane bianco", "Pane bianco", 80), Pi("Prosciutto cotto", "Prosciutto cotto, sgrassato", 50), Pi("Grana Padano", "Grana Padano, DOP", 20) }
                : new List<PlanItem> { Pi("Fette biscottate", "Fette biscottate", 50), Pi("Marmellata", "Marmellata", 30), Frutta(d) };
            slots.Add(new Slot($"pl{d}a", d, 7 * 60, 0, toast ? "Toast" : "Colazione dolce", colaz));
            slots.Add(new Slot($"pl{d}b", d, 10 * 60 + 30, 3, "Spuntino", new() { Frutta(d + 1), Pi("Grana Padano", "Grana Padano, DOP", 30) }));
            var carb = d % 2 == 0 ? Pi("Pasta", "Pasta di semola", 100) : Pi("Riso", "Riso, brillato", 90);
            var prot = d >= 5 ? Pi("Tacchino", "Tacchino, fesa, crudo", 200) : Pi("Pollo", "Pollo, petto, crudo", 200);
            slots.Add(new Slot($"pl{d}c", d, 13 * 60, 1, "Pranzo", new() { carb, prot, Pi("Olio d'oliva", "Olio di oliva extra vergine", 10), Pi("Pomodori", "Pomodori, da insalata, freschi", 200) }));
            slots.Add(new Slot($"pl{d}d", d, 16 * 60, 3, "Merenda", new() { Pi("Pane bianco", "Pane bianco", 80), Pi("Tacchino arrosto", "Tacchino, fesa, cotta, al forno", 60), Frutta(d + 2) }));
            var uova = d == 0 || d == 3 || d == 6;
            var cena = uova
                ? new List<PlanItem> { Pi("Uova", "Uova di gallina, intero", 180), Pi("Patate", "Patate, crude", 300), Pi("Olio d'oliva", "Olio di oliva extra vergine", 10), Pi("Zucchine", "Zucchine, crude", 200), Pi("Pane bianco", "Pane bianco", 40) }
                : new List<PlanItem> { Pi("Pollo", "Pollo, petto, crudo", 200), Pi("Patate", "Patate, crude", 300), Pi("Olio d'oliva", "Olio di oliva extra vergine", 10), Pi("Carote", "Carote, crude", 200), Pi("Pane bianco", "Pane bianco", 40) };
            slots.Add(new Slot($"pl{d}e", d, 20 * 60 + 30, 2, uova ? "Frittata" : "Cena", cena));
        }
        var noGoals = State.Goals.Height == 0;
        var today = DayKey();
        var noWeight = State.Weights.All(w => w.Day != today);
        Mutate(() =>
        {
            foreach (var f in foods.Values) Put("fd", f.Id, new JsonObject { ["n"] = f.Name, ["b"] = f.Brand, ["s"] = f.Serving, ["v"] = f.Per100.ToJson() });
            foreach (var s in slots) Put("sl", s.Id, SlotJson(s));
            if (noGoals) Put("cfg", "g", new JsonObject { ["k"] = 2350, ["p"] = 165, ["c"] = 260, ["f"] = 75, ["fi"] = 30, ["gw"] = 795, ["h"] = 186, ["sx"] = 0, ["a"] = 19, ["ac"] = 3 });
            if (noWeight) Put("wt", today, new JsonObject { ["k"] = 886 });
        });
    }
}
