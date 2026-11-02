using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Ponte;

public sealed record Ev(string Id, long Start, long End, bool AllDay, string Title, int Color, string Note, string Place, int Remind);
public sealed record Subject(string Id, string Name, int Color);
public sealed record StudyTask(string Id, string Title, string Subject, long Due, bool Done);
public sealed record Exam(string Id, string Title, string Subject, long Date);
public sealed record Sess(string Id, string Subject, long Start, int Minutes);
public sealed record StudyCard(string Id, string Subject, string Q, string A, int Box, long Due);
public sealed record PomoTimer(string Phase = "focus", bool Running = false, long StartedAt = 0, long EndAt = 0, string Subject = "", int Cycle = 0, string Device = "");
/// <summary>Un evento di un calendario esterno (Google, tramite l'indirizzo segreto iCal).</summary>
public sealed record ExtEvent(string Title, long Start, long End, bool AllDay, string Place, string Note);

public sealed class PlanState
{
    public List<Ev> Events { get; init; } = new();
    public List<Subject> Subjects { get; init; } = new();
    public List<StudyTask> Tasks { get; init; } = new();
    public List<Exam> Exams { get; init; } = new();
    public List<Sess> Sessions { get; init; } = new();
    public List<StudyCard> Cards { get; init; } = new();
    public Dictionary<string, int> Cfg { get; init; } = new();
    public PomoTimer Timer { get; init; } = new();
    public List<ExtEvent> Ext { get; init; } = new();
    public int Get(string k, int def) => Cfg.TryGetValue(k, out var v) ? v : def;
}

/// <summary>
/// Agenda e Studio, identici ad Android (it.ponte.Plan): gruppi di registri "l'ultimo che scrive vince" sincronizzati
/// dal server (/api/plan) e, se non risponde, tra i dispositivi con il messaggio "plan". In più legge il calendario Google
/// dall'indirizzo segreto iCal (sola lettura).
/// </summary>
public sealed class Plan
{
    private static readonly string[] Groups = { "ev", "sub", "tk", "ex", "ses", "card", "cfg", "tm", "gev" };
    private readonly Router _r;
    private readonly string _file = Path.Combine(Settings.Dir, "plan.json");
    private readonly object _lock = new();
    private readonly Dictionary<string, Dictionary<string, JsonObject>> _doc = new();
    private HttpClient? _http;
    private CancellationTokenSource? _debounce;
    private readonly HashSet<string> _fired = new();

    public PlanState State { get; private set; } = new();
    public List<ExtEvent> External { get; private set; } = new();
    /// <summary>Quelli dell'iCal più quelli che arrivano dai telefoni.</summary>
    public List<ExtEvent> AllExternal => External.Concat(State.Ext).ToList();
    public string ExternalStatus { get; private set; } = "";

    public Plan(Router r)
    {
        _r = r;
        foreach (var g in Groups) _doc[g] = new();
        try { if (File.Exists(_file) && JsonNode.Parse(File.ReadAllText(_file)) is JsonObject o) Merge(o); }
        catch (Exception) { }
        Publish();
        try { var cache = Path.Combine(Settings.Dir, "calendar.ics"); if (File.Exists(cache)) External = Ics.Parse(File.ReadAllText(cache)); } catch (Exception) { }
    }

    private static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    private static long Num(JsonNode? n)
    {
        if (n is not JsonValue v) return 0;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<double>(out var d)) return (long)d;
        if (v.TryGetValue<int>(out var i)) return i;
        return 0;
    }
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
                    if (m.TryGetValue(id, out var old) && Num(old["u"]) >= Num(v["u"])) continue;
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
            var cfg = new Dictionary<string, int>();
            foreach (var (k, v) in Live("cfg")) cfg[k] = (int)Num(v["v"]);
            PomoTimer t = new();
            if (_doc["tm"].TryGetValue("t", out var tj))
                t = new PomoTimer(Str(tj["p"]).Length > 0 ? Str(tj["p"]) : "focus", Num(tj["n"]) == 1, Num(tj["s"]), Num(tj["e"]), Str(tj["sb"]), (int)Num(tj["c"]), Str(tj["dv"]));
            State = new PlanState
            {
                Events = Live("ev").Select(kv => new Ev(kv.Key, Num(kv.Value["t"]), Num(kv.Value["e"]), Num(kv.Value["a"]) == 1, Str(kv.Value["ti"]), (int)Num(kv.Value["c"]), Str(kv.Value["n"]), Str(kv.Value["l"]), kv.Value["r"] == null ? -1 : (int)Num(kv.Value["r"]))).OrderBy(e => e.Start).ToList(),
                Subjects = Live("sub").Select(kv => new Subject(kv.Key, Str(kv.Value["ti"]), (int)Num(kv.Value["c"]))).OrderBy(s => s.Name.ToLowerInvariant()).ToList(),
                Tasks = Live("tk").Select(kv => new StudyTask(kv.Key, Str(kv.Value["ti"]), Str(kv.Value["s"]), Num(kv.Value["due"]), Num(kv.Value["f"]) == 1)).ToList(),
                Exams = Live("ex").Select(kv => new Exam(kv.Key, Str(kv.Value["ti"]), Str(kv.Value["s"]), Num(kv.Value["t"]))).OrderBy(e => e.Date).ToList(),
                Sessions = Live("ses").Select(kv => new Sess(kv.Key, Str(kv.Value["s"]), Num(kv.Value["t"]), (int)Num(kv.Value["m"]))).OrderByDescending(s => s.Start).ToList(),
                Cards = Live("card").Select(kv => new StudyCard(kv.Key, Str(kv.Value["s"]), Str(kv.Value["q"]), Str(kv.Value["a"]), (int)Num(kv.Value["b"]), Num(kv.Value["due"]))).ToList(),
                Cfg = cfg, Timer = t,
                Ext = Live("gev").Where(kv => Str(kv.Value["dv"]) != _r.S.DeviceId).Select(kv => new ExtEvent(Str(kv.Value["ti"]), Num(kv.Value["t"]), Num(kv.Value["e"]), Num(kv.Value["a"]) == 1, Str(kv.Value["l"]), "")).ToList(),
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

    private void Mutate(Action f)
    {
        lock (_lock) f();
        Publish();
        Save();
        _debounce?.Cancel();
        var cts = _debounce = new CancellationTokenSource();
        _ = Task.Run(async () => { try { await Task.Delay(800, cts.Token); await Push(); } catch (Exception) { } });
    }

    private void Put(string g, string id, JsonObject o) { o["u"] = Now; _doc[g][id] = o; }
    private void Del(string g, string id)
    {
        if (!_doc[g].TryGetValue(id, out var old)) return;
        var c = JsonNode.Parse(old.ToJsonString())!.AsObject();
        c["d"] = 1;
        c["u"] = Now;
        _doc[g][id] = c;
    }
    private static string NewId() => Guid.NewGuid().ToString("N")[..12];

    // ---------------------------------------------------------------- sincronizzazione
    public async Task<bool> SyncServer()
    {
        var baseUrl = _r.S.HttpBase();
        if (baseUrl == null || _r.S.Token.Length == 0) return false;
        _http ??= new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Post, baseUrl + "/api/plan") { Content = new StringContent(ToJson().ToJsonString(), Encoding.UTF8, "application/json") };
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
        if (doc.ToJsonString().Length < 400_000) _r.Send(Proto.NewMsg("plan", ("doc", doc)));
    }

    public async Task Push() { await SyncServer(); Broadcast(); }
    public void OnRemote(JsonObject? doc) { if (doc != null && Merge(doc)) { Publish(); Save(); } }

    public void Start()
    {
        _ = Task.Run(async () =>
        {
            await Task.Delay(5000);
            var n = 0;
            while (true)
            {
                try { await Push(); if (n++ % 7 == 0) await RefreshExternal(); } catch (Exception) { }
                await Task.Delay(TimeSpan.FromMinutes(2));
            }
        });
        // Avvisi: fine del pomodoro, promemoria degli eventi, esami del giorno dopo.
        _ = Task.Run(async () =>
        {
            while (true)
            {
                await Task.Delay(5000);
                try { Tick(); } catch (Exception) { }
            }
        });
    }

    private void Tick()
    {
        var now = Now;
        var s = State;
        if (s.Timer.Running && s.Timer.EndAt <= now) TimerDone();
        foreach (var e in s.Events.Where(e => e.Remind >= 0 && e.Start > now - 60_000 && e.Start - e.Remind * 60_000L <= now))
            if (_fired.Add("ev" + e.Id + e.Start)) Alert(e.Title, e.AllDay ? "Oggi, tutto il giorno" : "Alle " + DateTimeOffset.FromUnixTimeMilliseconds(e.Start).LocalDateTime.ToString("HH:mm", CultureInfo.InvariantCulture) + (e.Place.Length > 0 ? " · " + e.Place : ""));
        foreach (var x in s.Exams)
        {
            var eve = DateTimeOffset.FromUnixTimeMilliseconds(x.Date).LocalDateTime.Date.AddDays(-1).AddHours(18);
            var at = new DateTimeOffset(eve).ToUnixTimeMilliseconds();
            if (at <= now && x.Date > now && _fired.Add("ex" + x.Id + x.Date)) Alert("Esame domani: " + x.Title, "Ripassa con un pomodoro");
        }
    }

    private void Alert(string title, string text)
    {
        _r.Notify.Show("Agenda e Studio", title, text, "plan");
        try { System.Media.SystemSounds.Asterisk.Play(); } catch (Exception) { }
    }

    // ---------------------------------------------------------------- calendario Google (iCal)
    public async Task<bool> RefreshExternal()
    {
        var url = _r.S.IcsUrl.Trim();
        if (url.Length == 0) { External = new(); ExternalStatus = ""; _r.Raise(); return false; }
        if (url.StartsWith("webcal://")) url = "https://" + url[9..];
        _http ??= new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        try
        {
            var text = await _http.GetStringAsync(url);
            if (!text.Contains("BEGIN:VCALENDAR")) { ExternalStatus = "L'indirizzo non è un calendario iCal"; _r.Raise(); return false; }
            External = Ics.Parse(text);
            ExternalStatus = "Aggiornato alle " + DateTime.Now.ToString("HH:mm", CultureInfo.InvariantCulture) + " · " + External.Count + " eventi";
            try { Directory.CreateDirectory(Settings.Dir); File.WriteAllText(Path.Combine(Settings.Dir, "calendar.ics"), text); } catch (Exception) { }
            _r.Raise();
            return true;
        }
        catch (Exception e) { ExternalStatus = "Non riesco a leggere il calendario (" + e.Message + ")"; _r.Raise(); return false; }
    }

    // ---------------------------------------------------------------- eventi
    public void SaveEvent(Ev e) => Mutate(() => Put("ev", e.Id.Length == 0 ? NewId() : e.Id, new JsonObject { ["t"] = e.Start, ["e"] = e.End, ["a"] = e.AllDay ? 1 : 0, ["ti"] = e.Title, ["c"] = e.Color, ["n"] = e.Note, ["l"] = e.Place, ["r"] = e.Remind }));
    public void RemoveEvent(string id) => Mutate(() => Del("ev", id));

    // ---------------------------------------------------------------- studio
    public void SaveSubject(Subject s) => Mutate(() => Put("sub", s.Id.Length == 0 ? NewId() : s.Id, new JsonObject { ["ti"] = s.Name, ["c"] = s.Color }));
    public void RemoveSubject(string id) => Mutate(() => Del("sub", id));
    public void SaveTask(StudyTask t) => Mutate(() => Put("tk", t.Id.Length == 0 ? NewId() : t.Id, new JsonObject { ["ti"] = t.Title, ["s"] = t.Subject, ["due"] = t.Due, ["f"] = t.Done ? 1 : 0 }));
    public void RemoveTask(string id) => Mutate(() => Del("tk", id));
    public void SaveExam(Exam e) => Mutate(() => Put("ex", e.Id.Length == 0 ? NewId() : e.Id, new JsonObject { ["ti"] = e.Title, ["s"] = e.Subject, ["t"] = e.Date }));
    public void RemoveExam(string id) => Mutate(() => Del("ex", id));
    public void SaveCard(StudyCard c) => Mutate(() => Put("card", c.Id.Length == 0 ? NewId() : c.Id, new JsonObject { ["s"] = c.Subject, ["q"] = c.Q, ["a"] = c.A, ["b"] = c.Box, ["due"] = c.Due }));
    public void RemoveCard(string id) => Mutate(() => Del("card", id));
    public void SetCfg(string k, int v) => Mutate(() => Put("cfg", k, new JsonObject { ["v"] = v }));

    // ---------------------------------------------------------------- pomodoro
    private int Minutes(string phase) => phase switch { "short" => State.Get("short", 5), "long" => State.Get("long", 15), _ => State.Get("focus", 25) };

    private void WriteTimer(PomoTimer t) => Put("tm", "t", new JsonObject { ["p"] = t.Phase, ["n"] = t.Running ? 1 : 0, ["s"] = t.StartedAt, ["e"] = t.EndAt, ["sb"] = t.Subject, ["c"] = t.Cycle, ["dv"] = t.Device });

    public void TimerStart(string subject) => Mutate(() =>
    {
        var t = State.Timer; var now = Now;
        WriteTimer(t with { Running = true, StartedAt = now, EndAt = now + Minutes(t.Phase) * 60_000L, Subject = subject, Device = _r.S.DeviceId });
    });
    public void TimerPenalty(long ms) => Mutate(() => { var t = State.Timer; if (t.Running) WriteTimer(t with { EndAt = t.EndAt + ms }); });
    public void TimerStop() => Mutate(() => WriteTimer(State.Timer with { Running = false, EndAt = 0, StartedAt = 0 }));
    public void TimerPhase(string p) => Mutate(() => WriteTimer(new PomoTimer(p, false, 0, 0, State.Timer.Subject, State.Timer.Cycle, _r.S.DeviceId)));

    /// <summary>Il conto alla rovescia è finito: registro la sessione (id fisso dall'inizio, nessun doppione tra dispositivi) e passo alla fase dopo.</summary>
    public void TimerDone()
    {
        var t = State.Timer;
        if (!t.Running || t.EndAt > Now + 500) return;
        Mutate(() =>
        {
            var rounds = State.Get("rounds", 4);
            if (t.Phase == "focus")
            {
                Put("ses", "s" + t.StartedAt, new JsonObject { ["s"] = t.Subject, ["t"] = t.StartedAt, ["m"] = Minutes("focus") });
                var c = t.Cycle + 1;
                WriteTimer(new PomoTimer(c % rounds == 0 ? "long" : "short", false, 0, 0, t.Subject, c, _r.S.DeviceId));
            }
            else WriteTimer(new PomoTimer("focus", false, 0, 0, t.Subject, t.Cycle, _r.S.DeviceId));
        });
        if (_fired.Add("tm" + t.StartedAt)) Alert("Studio", t.Phase == "focus" ? "Pomodoro finito: fai una pausa" : "Pausa finita: si riparte");
    }
}

/// <summary>Lettore minimo di iCal (Google Calendar): eventi singoli e ripetizioni semplici (giorno, settimana, mese, anno).</summary>
public static class Ics
{
    public static List<ExtEvent> Parse(string text)
    {
        var lines = new List<string>();
        foreach (var raw in text.Replace("\r\n", "\n").Split('\n'))
        {
            if (raw.StartsWith(' ') || raw.StartsWith('\t')) { if (lines.Count > 0) lines[^1] += raw[1..]; }
            else lines.Add(raw);
        }
        var from = DateTime.Today.AddDays(-45);
        var to = DateTime.Today.AddDays(400);
        var res = new List<ExtEvent>();
        Dictionary<string, (string Params, string Val)>? cur = null;
        var ex = new List<DateTime>();
        foreach (var l in lines)
        {
            if (l == "BEGIN:VEVENT") { cur = new(); ex = new(); continue; }
            if (l == "END:VEVENT")
            {
                if (cur != null) try { Expand(cur, ex, from, to, res); } catch (Exception) { }
                cur = null;
                continue;
            }
            if (cur == null) continue;
            var colon = l.IndexOf(':');
            if (colon < 0) continue;
            var head = l[..colon]; var val = l[(colon + 1)..];
            var semi = head.IndexOf(';');
            var name = (semi < 0 ? head : head[..semi]).ToUpperInvariant();
            var pars = semi < 0 ? "" : head[(semi + 1)..];
            if (name == "EXDATE") { foreach (var p in val.Split(',')) if (ParseDate(pars, p, out var d, out _)) ex.Add(d); }
            else cur[name] = (pars, val);
        }
        return res.OrderBy(e => e.Start).ToList();
    }

    private static string Unesc(string s) => s.Replace("\\n", "\n").Replace("\\N", "\n").Replace("\\,", ",").Replace("\\;", ";").Replace("\\\\", "\\");

    private static bool ParseDate(string pars, string val, out DateTime local, out bool allDay)
    {
        allDay = false; local = default;
        val = val.Trim();
        if (val.Length == 8 && int.TryParse(val, out _)) { allDay = true; local = DateTime.ParseExact(val, "yyyyMMdd", CultureInfo.InvariantCulture); return true; }
        var utc = val.EndsWith('Z');
        var core = utc ? val[..^1] : val;
        if (!DateTime.TryParseExact(core, "yyyyMMdd'T'HHmmss", CultureInfo.InvariantCulture, DateTimeStyles.None, out var d)) return false;
        if (utc) local = DateTime.SpecifyKind(d, DateTimeKind.Utc).ToLocalTime();
        else
        {
            var m = Regex.Match(pars, "TZID=([^;:]+)");
            if (m.Success)
            {
                try { local = TimeZoneInfo.ConvertTime(new DateTimeOffset(d, TimeZoneInfo.FindSystemTimeZoneById(m.Groups[1].Value.Trim('"')).GetUtcOffset(d)), TimeZoneInfo.Local).DateTime; }
                catch (Exception) { local = d; }
            }
            else local = d;
        }
        return true;
    }

    private static long Ms(DateTime local) => new DateTimeOffset(DateTime.SpecifyKind(local, DateTimeKind.Local)).ToUnixTimeMilliseconds();

    private static void Expand(Dictionary<string, (string Params, string Val)> e, List<DateTime> ex, DateTime from, DateTime to, List<ExtEvent> res)
    {
        if (!e.TryGetValue("DTSTART", out var ds) || !ParseDate(ds.Params, ds.Val, out var start, out var allDay)) return;
        DateTime end;
        if (e.TryGetValue("DTEND", out var de) && ParseDate(de.Params, de.Val, out var en, out _)) end = en;
        else end = allDay ? start.AddDays(1) : start.AddHours(1);
        if (end <= start) end = start.AddHours(1);
        var dur = end - start;
        var title = e.TryGetValue("SUMMARY", out var s) ? Unesc(s.Val) : "(senza titolo)";
        var place = e.TryGetValue("LOCATION", out var lo) ? Unesc(lo.Val) : "";
        var note = e.TryGetValue("DESCRIPTION", out var no) ? Unesc(no.Val) : "";
        void Add(DateTime st) { if (ex.Any(x => x == st)) return; if (st + dur < from || st > to) return; res.Add(new ExtEvent(title, Ms(st), Ms(st + dur), allDay, place, note)); }

        if (!e.TryGetValue("RRULE", out var rr)) { Add(start); return; }
        var rule = rr.Val.Split(';').Select(p => p.Split('=')).Where(p => p.Length == 2).ToDictionary(p => p[0].ToUpperInvariant(), p => p[1]);
        var freq = rule.GetValueOrDefault("FREQ", "");
        var interval = rule.TryGetValue("INTERVAL", out var iv) && int.TryParse(iv, out var ii) ? Math.Max(1, ii) : 1;
        var count = rule.TryGetValue("COUNT", out var cv) && int.TryParse(cv, out var cc) ? cc : int.MaxValue;
        var until = DateTime.MaxValue;
        if (rule.TryGetValue("UNTIL", out var uv) && ParseDate("", uv, out var u, out _)) until = u;
        var days = new List<DayOfWeek>();
        if (rule.TryGetValue("BYDAY", out var bd))
            foreach (var t in bd.Split(','))
            {
                var code = t.Length >= 2 ? t[^2..] : t;
                var idx = Array.IndexOf(new[] { "SU", "MO", "TU", "WE", "TH", "FR", "SA" }, code);
                if (idx >= 0) days.Add((DayOfWeek)idx);
            }
        var n = 0; var guard = 0;
        if (freq == "WEEKLY")
        {
            if (days.Count == 0) days.Add(start.DayOfWeek);
            var weekStart = start.Date.AddDays(-(((int)start.DayOfWeek + 6) % 7));
            for (var w = weekStart; w <= to && n < count && guard++ < 5000; w = w.AddDays(7 * interval))
                foreach (var d in days.OrderBy(d => ((int)d + 6) % 7))
                {
                    var day = w.AddDays(((int)d + 6) % 7);
                    var st = day + start.TimeOfDay;
                    if (st < start) continue;
                    if (st > until || n >= count) break;
                    n++; Add(st);
                }
            return;
        }
        for (var cur = start; cur <= to && cur <= until && n < count && guard++ < 5000;)
        {
            n++; Add(cur);
            cur = freq switch
            {
                "DAILY" => start.AddDays(interval * n),
                "MONTHLY" => start.AddMonths(interval * n),
                "YEARLY" => start.AddYears(interval * n),
                _ => DateTime.MaxValue,
            };
            if (cur == DateTime.MaxValue) break;
        }
    }
}
