using System.Net;
using System.Net.Http.Headers;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Ponte;

/// <summary>
/// Backup delle cartelle del PC sul tuo server, con le stesse regole di quello del telefono (Backup.kt): copia identica
/// sotto <c>files/</c>, SHA-256 confrontato con quello calcolato dal server, saltati i file già presenti con stessa
/// dimensione e data. Il ripristino scarica solo ciò che manca: non sovrascrive mai un file che c'è.
/// </summary>
public sealed class Backup
{
    public enum Phase { Idle, Scanning, Running, Verifying, Restoring, Done, Failed }

    public sealed record Storage(long Total, long Free, long Reserve, long DeviceBytes, int DeviceFiles);
    public sealed record Counts(int Files, long Bytes, int ToSend, long ToSendBytes);
    public sealed record Report(bool Ok, IReadOnlyList<string> Lines);
    /// <summary>Un backup di un PC che sta sul server: da qui si può ripristinare.</summary>
    public sealed record Source(string Device, string Name, int Files);

    private readonly Router _r;
    private readonly HttpClient _http = new() { Timeout = Timeout.InfiniteTimeSpan };
    private CancellationTokenSource? _job;
    private readonly List<string> _failed = new();

    public Phase State { get; private set; } = Phase.Idle;
    public string Step { get; private set; } = "";
    public int Done { get; private set; }
    public int Total { get; private set; }
    public long Bytes { get; private set; }
    public Storage? Space { get; private set; }
    public string SpaceError { get; private set; } = "";
    public Counts? Plan { get; private set; }
    public Report? Last { get; private set; }
    public IReadOnlyList<Source> Sources { get; private set; } = new List<Source>();
    public IReadOnlyList<string> Failed { get { lock (_failed) return _failed.ToList(); } }
    public bool Busy => State is Phase.Scanning or Phase.Running or Phase.Verifying or Phase.Restoring;

    private string Device
    {
        get
        {
            var d = new string(_r.DeviceId.Where(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_').ToArray());
            return d.Length > 64 ? d[..64] : d;
        }
    }

    public Backup(Router r) => _r = r;

    // ---------------------------------------------------------------- cartelle

    private static readonly (string Key, Func<string> Path)[] Known =
    {
        ("Desktop", () => Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory)),
        ("Documenti", () => Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments)),
        ("Immagini", () => Environment.GetFolderPath(Environment.SpecialFolder.MyPictures)),
        ("Video", () => Environment.GetFolderPath(Environment.SpecialFolder.MyVideos)),
        ("Musica", () => Environment.GetFolderPath(Environment.SpecialFolder.MyMusic)),
        ("Download", Downloads),
    };

    private static string Downloads()
    {
        try
        {
            if (SHGetKnownFolderPath(new Guid("374DE290-123F-4565-9164-39C4925E467B"), 0, IntPtr.Zero, out var p) == 0)
            {
                try { return Marshal.PtrToStringUni(p) ?? ""; }
                finally { Marshal.FreeCoTaskMem(p); }
            }
        }
        catch (Exception) { }
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
    }

    [DllImport("shell32.dll")]
    private static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid id, uint flags, IntPtr token, out IntPtr path);

    [DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint flags);

    /// <summary>Le cartelle scelte; la prima volta sono quelle dell'utente di Windows, tutte accese.</summary>
    public IReadOnlyList<BackupFolder> Folders
    {
        get
        {
            if (_r.S.BackupFolders == null)
            {
                _r.S.BackupFolders = Known.Select(k => new BackupFolder { Key = k.Key, Path = k.Path() }).Where(f => f.Path.Length > 0 && Directory.Exists(f.Path)).ToList();
                _r.S.Save();
            }
            return _r.S.BackupFolders;
        }
    }

    public static bool IsKnown(string key) => Known.Any(k => k.Key == key);

    public void SetFolder(string key, bool on)
    {
        var f = Folders.FirstOrDefault(x => x.Key == key);
        if (f == null) return;
        f.On = on;
        _r.S.Save();
        Prepare();
    }

    public void AddFolder(string path)
    {
        var full = Path.TrimEndingDirectorySeparator(Path.GetFullPath(path));
        _ = Folders;
        if (_r.S.BackupFolders!.Any(f => string.Equals(Path.TrimEndingDirectorySeparator(f.Path), full, StringComparison.OrdinalIgnoreCase))) return;
        // il nome sul server resta lo stesso per sempre: è quello della cartella, numerato se ce n'è già uno uguale
        var name = new string(Path.GetFileName(full).Where(c => !"\\/:*?\"<>|".Contains(c)).ToArray()).Trim(' ', '.');
        if (name.Length == 0) name = "Disco " + full[..1];
        var key = name;
        for (var i = 2; IsKnown(key) || _r.S.BackupFolders!.Any(f => string.Equals(f.Key, key, StringComparison.OrdinalIgnoreCase)); i++) key = $"{name} {i}";
        _r.S.BackupFolders!.Add(new BackupFolder { Key = key, Path = full });
        _r.S.Save();
        Prepare();
    }

    public void RemoveFolder(string key)
    {
        _r.S.BackupFolders?.RemoveAll(f => f.Key == key);
        _r.S.Save();
        Prepare();
    }

    // ---------------------------------------------------------------- server

    private string Url(params string[] segments)
    {
        var b = _r.S.HttpBase() ?? throw new InvalidOperationException("Server non configurato");
        return b + "/api/" + string.Join('/', segments.Select(Uri.EscapeDataString));
    }

    private string FileUrl(string device, string path) => Url(new[] { "backup", device }.Concat(path.Split('/')).ToArray());

    private HttpRequestMessage Request(HttpMethod method, string url)
    {
        var m = new HttpRequestMessage(method, url);
        m.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _r.S.Token);
        return m;
    }

    private async Task<JsonObject> GetJson(string url, CancellationToken ct)
    {
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limit.CancelAfter(TimeSpan.FromMinutes(5));
        using var resp = await _http.SendAsync(Request(HttpMethod.Get, url), limit.Token);
        if (!resp.IsSuccessStatusCode) throw new InvalidOperationException($"Il server ha risposto {(int)resp.StatusCode}");
        return JsonNode.Parse(await resp.Content.ReadAsStringAsync(limit.Token)) as JsonObject ?? throw new InvalidOperationException("Risposta non valida");
    }

    private static long Num(JsonNode? n) => n is JsonValue v ? v.TryGetValue<long>(out var l) ? l : v.TryGetValue<double>(out var d) ? (long)d : 0 : 0;

    public async Task RefreshStorage()
    {
        try
        {
            var j = await GetJson(Url("storage"), CancellationToken.None);
            var all = j["backup"] as JsonObject;
            var mine = all?[Device] as JsonObject;
            Space = new Storage(Num(j["total"]), Num(j["free"]), Num(j["reserve"]), Num(mine?["bytes"]), (int)Num(mine?["files"]));
            SpaceError = "";
        }
        catch (Exception e) { SpaceError = e.Message; }
        _r.Raise();
    }

    private async Task<Dictionary<string, (long Size, long Time)>> Manifest(string device, CancellationToken ct)
    {
        var j = await GetJson(Url("backup", device), ct);
        var map = new Dictionary<string, (long, long)>(StringComparer.Ordinal);
        if (j["files"] is JsonArray a)
            foreach (var n in a)
                if (n is JsonObject o) map[o.S("p")] = (Num(o["s"]), Num(o["m"]));
        return map;
    }

    /// <summary>I backup di PC presenti sul server (hanno l'elenco delle cartelle), questo per primo.</summary>
    public async Task RefreshSources()
    {
        try
        {
            var j = await GetJson(Url("storage"), CancellationToken.None);
            var found = new List<Source>();
            foreach (var (device, node) in j["backup"] as JsonObject ?? new JsonObject())
            {
                try
                {
                    var map = await ReadFolderMap(device, CancellationToken.None);
                    if (map == null) continue;
                    found.Add(new Source(device, device == Device ? "Questo PC" : map.S("name"), (int)Num((node as JsonObject)?["files"])));
                }
                catch (Exception) { }
            }
            Sources = found.OrderBy(s => s.Device == Device ? 0 : 1).ThenBy(s => s.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
        }
        catch (Exception) { }
        _r.Raise();
    }

    private async Task<JsonObject?> ReadFolderMap(string device, CancellationToken ct)
    {
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limit.CancelAfter(TimeSpan.FromMinutes(1));
        using var resp = await _http.SendAsync(Request(HttpMethod.Get, FileUrl(device, "_relay/cartelle.json")), limit.Token);
        if (!resp.IsSuccessStatusCode) return null;
        return JsonNode.Parse(await resp.Content.ReadAsStringAsync(limit.Token)) as JsonObject;
    }

    // ---------------------------------------------------------------- file locali

    private sealed record Local(string Path, FileInfo File);

    /// <summary>Segnaposto di OneDrive non scaricato: leggerlo lo scaricherebbe.</summary>
    private const FileAttributes CloudOnly = (FileAttributes)0x00400000 | FileAttributes.Offline;

    private List<Local> LocalFiles(List<string> unreadable, CancellationToken ct)
    {
        var outList = new List<Local>();
        var on = Folders.Where(f => f.On).ToList();
        var roots = on.Select(f => Path.TrimEndingDirectorySeparator(f.Path)).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var options = new EnumerationOptions { IgnoreInaccessible = true, AttributesToSkip = 0, ReturnSpecialDirectories = false };

        void Walk(DirectoryInfo dir, string rel)
        {
            ct.ThrowIfCancellationRequested();
            foreach (var entry in dir.EnumerateFileSystemInfos("*", options))
            {
                if ((entry.Attributes & (FileAttributes.System | FileAttributes.Hidden)) != 0) continue;
                // collegamenti e giunzioni no (i file di OneDrive già scaricati non lo sono)
                if ((entry.Attributes & FileAttributes.ReparsePoint) != 0 && entry.LinkTarget != null) continue;
                var path = rel + "/" + entry.Name;
                if (entry is DirectoryInfo sub)
                {
                    // una cartella scelta anche per conto suo si copia una volta sola
                    if (!roots.Contains(sub.FullName)) Walk(sub, path);
                }
                else if (entry is FileInfo file)
                {
                    if ((file.Attributes & CloudOnly) != 0) continue;
                    var n = file.Name;
                    if (n.EndsWith(".part", StringComparison.Ordinal) || n.StartsWith("~$") || n.Equals("desktop.ini", StringComparison.OrdinalIgnoreCase) || n.Equals("Thumbs.db", StringComparison.OrdinalIgnoreCase)) continue;
                    outList.Add(new Local(path, file));
                }
            }
        }

        foreach (var f in on)
        {
            try
            {
                var dir = new DirectoryInfo(Path.TrimEndingDirectorySeparator(f.Path));
                if (!dir.Exists) { unreadable.Add(f.Key + ": cartella non trovata"); continue; }
                Walk(dir, "files/" + f.Key);
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception e) { unreadable.Add(f.Key + ": " + e.Message); }
        }
        return outList;
    }

    private static long Mtime(FileInfo f) => new DateTimeOffset(f.LastWriteTimeUtc, TimeSpan.Zero).ToUnixTimeMilliseconds();

    private static bool Same(Dictionary<string, (long Size, long Time)> server, Local l) =>
        server.TryGetValue(l.Path, out var s) && s.Size == l.File.Length && Math.Abs(s.Time - Mtime(l.File)) < 2000;

    // ---------------------------------------------------------------- lavori

    private void Set(Phase phase, string step)
    {
        State = phase;
        Step = step;
        _r.Raise();
    }

    /// <summary>Un lavoro alla volta, fuori dal thread dell'interfaccia; il PC non va in sospensione finché copia.</summary>
    private void Run(Phase phase, string step, Func<CancellationToken, Task> work, bool awake)
    {
        if (Busy) return;
        var job = _job = new CancellationTokenSource();
        Done = Total = 0;
        Bytes = 0;
        lock (_failed) _failed.Clear();
        Set(phase, step);
        var finished = new ManualResetEventSlim();
        if (awake)
            new Thread(() =>
            {
                // ES_CONTINUOUS | ES_SYSTEM_REQUIRED vale finché questo thread è vivo
                try { SetThreadExecutionState(0x80000001); } catch (Exception) { return; }
                finished.Wait();
                SetThreadExecutionState(0x80000000);
            }) { IsBackground = true }.Start();
        _ = Task.Run(async () =>
        {
            try { await work(job.Token); }
            catch (OperationCanceledException) when (job.IsCancellationRequested) { Set(Phase.Idle, "Interrotto"); }
            catch (NoSpace) { Set(Phase.Failed, "Spazio insufficiente sul server"); }
            catch (OperationCanceledException) { Set(Phase.Failed, "Il server non risponde"); }
            catch (Exception e) { Crash.Log(e, "Backup"); Set(Phase.Failed, e.Message); }
            finally { finished.Set(); }
        });
    }

    public void Cancel() => _job?.Cancel();

    /// <summary>Quanto c'è da copiare.</summary>
    public void Prepare()
    {
        // una nuova scelta di cartelle annulla il conteggio in corso
        if (State == Phase.Scanning) { _job?.Cancel(); State = Phase.Idle; }
        if (Busy) return;
        Plan = null;
        Last = null;
        var mine = new CancellationTokenSource();
        _job = mine;
        Set(Phase.Scanning, "Conto i file…");
        _ = Task.Run(async () =>
        {
            try
            {
                await RefreshStorage();
                var files = LocalFiles(new List<string>(), mine.Token);
                Dictionary<string, (long, long)> server;
                try { server = await Manifest(Device, mine.Token); }
                catch (Exception) when (!mine.IsCancellationRequested) { server = new(); }
                var send = files.Where(f => !Same(server, f)).ToList();
                if (_job != mine) return;
                Plan = new Counts(files.Count, files.Sum(f => f.File.Length), send.Count, send.Sum(f => f.File.Length));
                Set(Phase.Idle, "");
            }
            catch (OperationCanceledException) { if (_job == mine) Set(Phase.Idle, ""); }
            catch (Exception e) { if (_job == mine) Set(Phase.Failed, e.Message); }
        });
    }

    private void Fail(string line) { lock (_failed) _failed.Add(line); }

    public void Start() => Run(Phase.Running, "Controllo il server…", async ct =>
    {
        Last = null;
        await RefreshStorage();
        var server = await Manifest(Device, ct);

        var map = new JsonObject { ["name"] = _r.S.Name };
        var folders = new JsonObject();
        foreach (var f in Folders) folders[f.Key] = f.Path;
        map["folders"] = folders;
        await UploadText("_relay/cartelle.json", map.ToJsonString(), ct);

        Set(Phase.Running, "Elenco dei file…");
        var unreadable = new List<string>();
        var todo = LocalFiles(unreadable, ct).Where(f => !Same(server, f)).ToList();
        foreach (var u in unreadable) Fail(u);
        Total = todo.Count;
        foreach (var f in todo)
        {
            ct.ThrowIfCancellationRequested();
            Step = f.File.Name;
            try
            {
                f.File.Refresh();
                var length = f.File.Length;
                await Upload(f.Path, f.File, ct);
                Bytes += length;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (NoSpace) { throw; }
            catch (Exception e) { Fail($"{f.Path[6..]}: {e.Message}"); }
            Done++;
        }
        _r.S.LastBackup = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        _r.S.Save();
        await RefreshStorage();
        Plan = null;
        Set(Phase.Done, "Copia finita");
    }, true);

    private sealed class NoSpace : Exception { }

    /// <summary>Il contenuto di un file, con l'SHA-256 calcolato su ciò che parte davvero.</summary>
    private sealed class Hashed : HttpContent
    {
        private readonly Stream _source;
        private readonly long _length;
        private readonly CancellationTokenSource _stall;
        public string Sha { get; private set; } = "";

        public Hashed(Stream source, CancellationTokenSource stall)
        {
            _source = source;
            _length = source.Length;
            _stall = stall;
            Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        }

        protected override bool TryComputeLength(out long length) { length = _length; return true; }

        protected override async Task SerializeToStreamAsync(Stream stream, TransportContext? context)
        {
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var buffer = new byte[256 * 1024];
            var left = _length;
            while (left > 0)
            {
                var n = await _source.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, left)), _stall.Token);
                if (n == 0) throw new IOException("il file è cambiato durante la copia");
                hash.AppendData(buffer, 0, n);
                await stream.WriteAsync(buffer.AsMemory(0, n), _stall.Token);
                left -= n;
                // finché i byte passano il tempo massimo riparte
                _stall.CancelAfter(TimeSpan.FromMinutes(10));
            }
            Sha = Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
        }
    }

    /// <summary>Carica un file e confronta l'SHA-256 calcolato qui con quello del server su ciò che ha ricevuto.</summary>
    private async Task Upload(string path, FileInfo file, CancellationToken ct)
    {
        var url = FileUrl(Device, path);
        Exception? last = null;
        for (var attempt = 0; attempt < 4; attempt++)
        {
            // se il file è aperto in esclusiva si salta subito: riprovare non serve
            await using var source = new FileStream(file.FullName, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 4096, true);
            try
            {
                using var stall = CancellationTokenSource.CreateLinkedTokenSource(ct);
                stall.CancelAfter(TimeSpan.FromMinutes(10));
                var body = new Hashed(source, stall);
                var request = Request(HttpMethod.Put, url);
                file.Refresh();
                request.Headers.Add("x-mtime", Mtime(file).ToString());
                request.Content = body;
                using var resp = await _http.SendAsync(request, stall.Token);
                if (resp.StatusCode == HttpStatusCode.InsufficientStorage) throw new NoSpace();
                if (!resp.IsSuccessStatusCode) throw new InvalidOperationException($"server {(int)resp.StatusCode}");
                var got = (JsonNode.Parse(await resp.Content.ReadAsStringAsync(stall.Token)) as JsonObject)?.S("sha256");
                if (got != body.Sha) throw new InvalidOperationException("controllo SHA-256 non coincide");
                return;
            }
            catch (NoSpace) { throw; }
            catch (Exception e) when (!ct.IsCancellationRequested)
            {
                last = e;
                await Task.Delay(1500 * (attempt + 1), ct);
            }
        }
        ct.ThrowIfCancellationRequested();
        throw last is OperationCanceledException ? new IOException("il server non risponde") : last ?? new IOException("errore");
    }

    private async Task UploadText(string path, string text, CancellationToken ct)
    {
        var temp = Path.Combine(Path.GetTempPath(), "relay-" + Guid.NewGuid().ToString("N"));
        await File.WriteAllTextAsync(temp, text, new UTF8Encoding(false), ct);
        try { await Upload(path, new FileInfo(temp), ct); }
        finally { try { File.Delete(temp); } catch (Exception) { } }
    }

    /// <summary>Confronta ogni file delle cartelle scelte con quello sul server (esistenza, dimensione, data).</summary>
    public void Verify() => Run(Phase.Verifying, "Leggo l'elenco dal server…", async ct =>
    {
        Last = null;
        var server = await Manifest(Device, ct);
        var unreadable = new List<string>();
        var files = LocalFiles(unreadable, ct);
        var missing = files.Count(f => !server.ContainsKey(f.Path));
        var different = files.Count(f => server.ContainsKey(f.Path) && !Same(server, f));
        var lines = new List<string> { $"File: {files.Count - missing - different} su {files.Count} identici sul server" };
        if (missing > 0) lines.Add($"{missing} file mancanti sul server");
        if (different > 0) lines.Add($"{different} file diversi da quelli sul server");
        lines.AddRange(unreadable);
        var ok = missing == 0 && different == 0 && unreadable.Count == 0;
        if (ok) { _r.S.LastVerifiedOk = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(); _r.S.Save(); }
        Last = new Report(ok, lines);
        Set(Phase.Done, "");
    }, false);

    /// <summary>Rimette al loro posto i file che mancano.</summary>
    public void Restore(string? device = null) => Run(Phase.Restoring, "Leggo l'elenco dal server…", async ct =>
    {
        Last = null;
        var from = device ?? Device;
        var server = await Manifest(from, ct);
        var map = (await ReadFolderMap(from, ct))?["folders"] as JsonObject ?? throw new InvalidOperationException("Sul server non c'è un backup di questo PC");
        var todo = server.Where(x => x.Key.StartsWith("files/", StringComparison.Ordinal)).ToList();
        Total = todo.Count;
        foreach (var (path, info) in todo)
        {
            ct.ThrowIfCancellationRequested();
            var rel = path[6..];
            Step = rel[(rel.LastIndexOf('/') + 1)..];
            try
            {
                var target = Target(rel, map);
                if (!File.Exists(target))
                {
                    await Download(from, path, target, ct);
                    Bytes += info.Size;
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception e) { Fail($"{rel}: {e.Message}"); }
            Done++;
        }
        Set(Phase.Done, "Ripristino finito");
    }, true);

    /// <summary>Dove va un file: le cartelle di Windows in quelle di questo utente, le altre dov'erano. Mai fuori dalla cartella.</summary>
    private string Target(string rel, JsonObject map)
    {
        var parts = rel.Split('/');
        if (parts.Length < 2 || parts.Skip(1).Any(p => p.Length == 0 || p is "." or ".." || p.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0))
            throw new InvalidOperationException("percorso non valido");
        var key = parts[0];
        var known = Known.FirstOrDefault(k => k.Key == key);
        var root = known.Path != null ? known.Path()
            : Folders.FirstOrDefault(f => f.Key == key)?.Path ?? map.S(key);
        if (root.Length == 0 || !Path.IsPathFullyQualified(root)) throw new InvalidOperationException("cartella di destinazione sconosciuta");
        root = Path.GetFullPath(root);
        var full = Path.GetFullPath(Path.Combine(new[] { root }.Concat(parts.Skip(1)).ToArray()));
        if (!full.StartsWith(Path.TrimEndingDirectorySeparator(root) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("percorso non valido");
        return full;
    }

    private async Task Download(string device, string path, string target, CancellationToken ct)
    {
        using var stall = CancellationTokenSource.CreateLinkedTokenSource(ct);
        stall.CancelAfter(TimeSpan.FromMinutes(10));
        using var resp = await _http.SendAsync(Request(HttpMethod.Get, FileUrl(device, path)), HttpCompletionOption.ResponseHeadersRead, stall.Token);
        if (!resp.IsSuccessStatusCode) throw new InvalidOperationException($"server {(int)resp.StatusCode}");
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        var part = target + ".part";
        try
        {
            await using (var input = await resp.Content.ReadAsStreamAsync(stall.Token))
            await using (var output = File.Create(part))
            {
                var buffer = new byte[256 * 1024];
                int n;
                while ((n = await input.ReadAsync(buffer, stall.Token)) > 0)
                {
                    await output.WriteAsync(buffer.AsMemory(0, n), stall.Token);
                    stall.CancelAfter(TimeSpan.FromMinutes(10));
                }
            }
            File.Move(part, target);
            if (resp.Headers.TryGetValues("x-mtime", out var values) && long.TryParse(values.FirstOrDefault(), out var ms))
                File.SetLastWriteTimeUtc(target, DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime);
        }
        finally { try { if (File.Exists(part)) File.Delete(part); } catch (Exception) { } }
    }
}

/// <summary>Una cartella del backup: il nome che ha sul server, dove sta su questo PC, se va copiata.</summary>
public sealed class BackupFolder
{
    public string Key { get; set; } = "";
    public string Path { get; set; } = "";
    public bool On { get; set; } = true;
}
