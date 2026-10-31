using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Ponte;

public sealed record FileEntry(string Fid, string Name, string Kind, string Mime, long Size, long Ts, string From, string Path);

/// <summary>Archivio di screenshot e file: invio e ricezione a blocchi, recupero da offline, indice su disco.</summary>
public sealed partial class FileStore
{
    public const long MaxFile = 200L * 1024 * 1024;

    private readonly Router _r;
    private readonly object _lock = new();
    private readonly string _indexPath = Path.Combine(Settings.Dir, "files.json");
    private readonly string _tmpDir = Path.Combine(Settings.Dir, "tmp");
    private List<FileEntry> _items = new();
    private readonly ConcurrentDictionary<string, Incoming> _incoming = new();
    private readonly ConcurrentDictionary<string, List<JsonObject>> _orphans = new();
    private HttpClient? _http;

    [GeneratedRegex("^[A-Za-z0-9_-]{8,64}$")]
    private static partial Regex FidRe();

    private sealed class Incoming
    {
        public required FileStream Fs;
        public required string Part;
        public required FileEntry Meta;
        public long Received;
        public bool Ended;
    }

    public FileStore(Router r)
    {
        _r = r;
        Directory.CreateDirectory(_tmpDir);
        foreach (var f in Directory.GetFiles(_tmpDir, "*.part")) { try { File.Delete(f); } catch (IOException) { } }
        Load();
    }

    public IReadOnlyList<FileEntry> Items { get { lock (_lock) return _items.ToList(); } }

    private static string Home => Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
    public static string ScreenshotDir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "Ponte");
    public static string FileDir => Path.Combine(Home, "Downloads", "Ponte");
    private static string DirFor(string kind) => kind == "screenshot" ? ScreenshotDir : FileDir;

    // ---- invio ----

    /// <summary>Registra nell'archivio un file già presente sul disco (senza copiarlo). Se è ancora in scrittura aspetta che finisca.</summary>
    public async Task<FileEntry?> ImportAsync(string path, string kind)
    {
        if (!await WaitReadyAsync(path)) return null;
        var info = new FileInfo(path);
        if (info.Length > MaxFile) return null;
        var fid = "f" + Guid.NewGuid().ToString("N");
        var e = new FileEntry(fid, SafeName(info.Name), kind, MimeFor(info.Extension), info.Length,
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), _r.DeviceId, path);
        Add(e);
        return e;
    }

    /// <summary>Ultimo screenshot visto sul disco: serve a non mandare due volte lo stesso screenshot (file e appunti).</summary>
    public DateTime LastScreenshot { get; private set; } = DateTime.MinValue;

    private readonly ConcurrentDictionary<string, byte> _clipFids = new();
    public const long ClipMax = 50L * 1024 * 1024;

    /// <summary>File copiato negli appunti: va nell'archivio e agli altri dispositivi, che lo mettono nei loro appunti.</summary>
    public async Task SendClipFileAsync(string path)
    {
        var e = await ImportAsync(path, "file");
        if (e != null && e.Size <= ClipMax) await TransmitAsync(e, clip: true);
    }

    public async Task SendFileAsync(string path, string kind)
    {
        if (kind == "screenshot") LastScreenshot = DateTime.UtcNow;
        var e = await ImportAsync(path, kind);
        if (e != null) await TransmitAsync(e);
    }

    private static async Task<bool> WaitReadyAsync(string path)
    {
        long prev = -1;
        for (var i = 0; i < 40; i++)
        {
            try
            {
                using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
                if (fs.Length > 0 && fs.Length == prev) return true;
                prev = fs.Length;
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { return false; }
            await Task.Delay(250);
        }
        return false;
    }

    public async Task TransmitAsync(FileEntry e, bool clip = false)
    {
        _r.Send(Proto.NewMsg("file.begin", ("fid", e.Fid), ("name", e.Name), ("kind", e.Kind), ("mime", e.Mime), ("size", e.Size), ("clip", clip)));
        var buf = new byte[Proto.ChunkBytes];
        var seq = 0;
        await using (var fs = new FileStream(e.Path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
        {
            while (true)
            {
                var n = 0;
                while (n < buf.Length)
                {
                    var k = await fs.ReadAsync(buf.AsMemory(n, buf.Length - n));
                    if (k == 0) break;
                    n += k;
                }
                if (n == 0) break;
                _r.Send(Proto.NewMsg("file.chunk", ("fid", e.Fid), ("seq", seq++), ("data", Convert.ToBase64String(buf, 0, n))));
                while (_r.MaxQueued() > 2_000_000) await Task.Delay(20);
                if (n < buf.Length) break;
            }
        }
        _r.Send(Proto.NewMsg("file.end", ("fid", e.Fid)));
    }

    // ---- ricezione ----

    public void OnMessage(JsonObject m)
    {
        var fid = m.S("fid");
        if (!FidRe().IsMatch(fid)) return;
        switch (m.S("t"))
        {
            case "file.begin": Begin(fid, m); break;
            case "file.chunk" or "file.end":
                if (_incoming.TryGetValue(fid, out var inc)) Apply(fid, inc, m);
                else Stash(fid, m);
                break;
        }
    }

    private void Begin(string fid, JsonObject m)
    {
        lock (_lock) if (_items.Any(i => i.Fid == fid)) return;
        if (_incoming.ContainsKey(fid)) return;
        var size = m.L("size", -1);
        if (size < 0 || size > MaxFile) return;
        var kind = m.S("kind") == "screenshot" ? "screenshot" : "file";
        var part = Path.Combine(_tmpDir, fid + ".part");
        var fs = new FileStream(part, FileMode.Create, FileAccess.ReadWrite, FileShare.None);
        fs.SetLength(size);
        var mime = m.S("mime");
        var meta = new FileEntry(fid, SafeName(m.S("name")), kind, mime.Length > 0 ? mime : "application/octet-stream", size,
            m.L("ts", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()), m.S("from"), "");
        var inc = new Incoming { Fs = fs, Part = part, Meta = meta };
        _incoming[fid] = inc;
        if (m["clip"] is JsonValue cv && cv.TryGetValue<bool>(out var isClip) && isClip) _clipFids[fid] = 0;
        if (_orphans.TryRemove(fid, out var pending)) foreach (var p in pending) Apply(fid, inc, p);
    }

    private void Stash(string fid, JsonObject m)
    {
        if (_orphans.Count >= 16 && !_orphans.ContainsKey(fid)) return;
        var l = _orphans.GetOrAdd(fid, _ => new List<JsonObject>());
        lock (l) if (l.Count < 64) l.Add(m);
    }

    private void Apply(string fid, Incoming inc, JsonObject m)
    {
        lock (inc)
        {
            if (!_incoming.TryGetValue(fid, out var cur) || !ReferenceEquals(cur, inc)) return;
            if (m.S("t") == "file.chunk")
            {
                byte[] data;
                try { data = Convert.FromBase64String(m.S("data")); } catch (FormatException) { Drop(fid, inc); return; }
                var off = m.L("seq", -1) * Proto.ChunkBytes;
                if (off < 0 || off + data.Length > inc.Meta.Size) { Drop(fid, inc); return; }
                inc.Fs.Seek(off, SeekOrigin.Begin);
                inc.Fs.Write(data, 0, data.Length);
                inc.Received += data.Length;
            }
            else inc.Ended = true;

            if (inc.Ended && inc.Received >= inc.Meta.Size) Finish(fid, inc);
        }
    }

    private void Drop(string fid, Incoming inc)
    {
        _incoming.TryRemove(fid, out _);
        _clipFids.TryRemove(fid, out _);
        try { inc.Fs.Dispose(); File.Delete(inc.Part); } catch (IOException) { }
    }

    private void Finish(string fid, Incoming inc)
    {
        _incoming.TryRemove(fid, out _);
        inc.Fs.Dispose();
        try
        {
            var dir = DirFor(inc.Meta.Kind);
            Directory.CreateDirectory(dir);
            var final = UniquePath(dir, inc.Meta.Name);
            File.Move(inc.Part, final);
            Add(inc.Meta with { Path = final });
            // Copiato su un altro dispositivo: ora è anche negli appunti di questo.
            if (_clipFids.TryRemove(fid, out _)) _r.Clip.ApplyFile(final, inc.Meta.Mime);
        }
        catch (IOException) { try { File.Delete(inc.Part); } catch (IOException) { } }
    }

    // ---- recupero da offline ----

    /// <summary>Scarica dal server ciò che è stato caricato mentre questo PC era spento.</summary>
    public async Task CatchUp()
    {
        var baseUrl = _r.S.HttpBase();
        if (baseUrl == null) return;
        _http ??= new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        var auth = new AuthenticationHeaderValue("Bearer", _r.S.Token);
        var sincePath = Path.Combine(Settings.Dir, "since.txt");
        try
        {
            var since = long.TryParse(File.Exists(sincePath) ? File.ReadAllText(sincePath) : "", out var s)
                ? s : DateTimeOffset.UtcNow.AddDays(-7).ToUnixTimeMilliseconds();
            using var req = new HttpRequestMessage(HttpMethod.Get, $"{baseUrl}/api/files?since={since}") { Headers = { Authorization = auth } };
            using var resp = await _http.SendAsync(req);
            if (!resp.IsSuccessStatusCode) return;
            if (JsonNode.Parse(await resp.Content.ReadAsStringAsync()) is not JsonArray list) return;
            var maxTs = since;
            foreach (var n in list)
            {
                if (n is not JsonObject e) continue;
                maxTs = Math.Max(maxTs, e.L("ts"));
                var fid = e.S("fid");
                if (!FidRe().IsMatch(fid) || _incoming.ContainsKey(fid)) continue;
                lock (_lock) if (_items.Any(i => i.Fid == fid)) continue;
                var kind = e.S("kind") == "screenshot" ? "screenshot" : "file";
                var dir = DirFor(kind);
                Directory.CreateDirectory(dir);
                var name = SafeName(e.S("name"));
                using var dreq = new HttpRequestMessage(HttpMethod.Get, $"{baseUrl}/api/files/{fid}") { Headers = { Authorization = auth } };
                using var dresp = await _http.SendAsync(dreq, HttpCompletionOption.ResponseHeadersRead);
                if (!dresp.IsSuccessStatusCode) continue;
                var final = UniquePath(dir, name);
                await using (var o = File.Create(final)) await dresp.Content.CopyToAsync(o);
                var mime = e.S("mime");
                Add(new FileEntry(fid, name, kind, mime.Length > 0 ? mime : "application/octet-stream", new FileInfo(final).Length, e.L("ts"), e.S("from"), final));
            }
            File.WriteAllText(sincePath, maxTs.ToString());
        }
        catch (Exception) { /* senza rete si riprova alla prossima connessione */ }
    }

    // ---- archivio ----

    public void Open(FileEntry e)
    {
        try { Process.Start(new ProcessStartInfo(e.Path) { UseShellExecute = true }); }
        catch (Exception) { }
    }

    /// <summary>Toglie dall'archivio. Cancella il file solo se l'ha scaricato Ponte: gli originali dell'utente non si toccano.</summary>
    public void Delete(FileEntry e)
    {
        if (e.Path.StartsWith(ScreenshotDir, StringComparison.OrdinalIgnoreCase) || e.Path.StartsWith(FileDir, StringComparison.OrdinalIgnoreCase))
        {
            try { File.Delete(e.Path); } catch (IOException) { }
        }
        lock (_lock) _items = _items.Where(i => i.Fid != e.Fid).ToList();
        Persist();
        _r.Raise();
    }

    private void Add(FileEntry e)
    {
        lock (_lock) _items = _items.Where(i => i.Fid != e.Fid).Append(e).OrderByDescending(i => i.Ts).ToList();
        Persist();
        _r.Raise();
    }

    private void Load()
    {
        try
        {
            if (!File.Exists(_indexPath)) return;
            var list = JsonSerializer.Deserialize<List<FileEntry>>(File.ReadAllText(_indexPath)) ?? new();
            _items = list.Where(i => File.Exists(i.Path)).OrderByDescending(i => i.Ts).ToList();
        }
        catch (Exception) { }
    }

    private void Persist()
    {
        try
        {
            List<FileEntry> snapshot;
            lock (_lock) snapshot = _items.ToList();
            File.WriteAllText(_indexPath, JsonSerializer.Serialize(snapshot));
        }
        catch (IOException) { }
    }

    private static string SafeName(string n)
    {
        var s = Path.GetFileName(n.Replace('\\', '/'));
        foreach (var c in Path.GetInvalidFileNameChars()) s = s.Replace(c, '_');
        s = s.Trim().TrimEnd('.');
        return s.Length == 0 ? "file" : s.Length > 120 ? s[..120] : s;
    }

    private static string UniquePath(string dir, string name)
    {
        var p = Path.Combine(dir, name);
        if (!File.Exists(p)) return p;
        var stem = Path.GetFileNameWithoutExtension(name);
        var ext = Path.GetExtension(name);
        for (var i = 2; ; i++)
        {
            p = Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(p)) return p;
        }
    }

    private static string MimeFor(string ext) => ext.ToLowerInvariant() switch
    {
        ".png" => "image/png",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".gif" => "image/gif",
        ".bmp" => "image/bmp",
        ".webp" => "image/webp",
        ".pdf" => "application/pdf",
        ".txt" => "text/plain",
        _ => "application/octet-stream",
    };
}
