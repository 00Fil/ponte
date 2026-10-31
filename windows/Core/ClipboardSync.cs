using Microsoft.UI.Dispatching;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.ApplicationModel.DataTransfer;
using Windows.Graphics.Imaging;
using Windows.Storage;
using Windows.Storage.Streams;

namespace Ponte;

public sealed record ClipItem(string Text, string From, long Ts, bool Mine);

/// <summary>Appunti di testo condivisi. Su Windows si leggono in qualsiasi momento: l'invio è automatico a ogni copia.</summary>
public sealed class ClipboardSync
{
    private readonly Router _r;
    private readonly DispatcherQueue _ui;
    private readonly List<ClipItem> _history = new();
    private const int HistoryMax = 5000;
    private string? _lastApplied;

    public ClipboardSync(Router r, DispatcherQueue ui)
    {
        _r = r;
        _ui = ui;
    }

    public IReadOnlyList<ClipItem> History { get { lock (_history) return _history.ToList(); } }

    public void Start() => _ui.TryEnqueue(() => Clipboard.ContentChanged += OnChanged);

    private DateTime _ignoreUntil = DateTime.MinValue;
    private readonly HashSet<string> _appliedPaths = new(StringComparer.OrdinalIgnoreCase);

    private async void OnChanged(object? sender, object e)
    {
        if (DateTime.UtcNow < _ignoreUntil) return;
        await SendCurrentAsync(fromEvent: true);
    }

    /// <summary>Legge gli appunti (file, testo o immagine) e li invia. Va chiamato sul thread dell'interfaccia.</summary>
    public async Task<bool> SendCurrentAsync(bool fromEvent = false)
    {
        // Un'altra app può tenere gli appunti bloccati per un istante: si riprova qualche volta.
        for (var i = 0; i < 5; i++)
        {
            try
            {
                var c = Clipboard.GetContent();
                // I gestori di password segnano ciò che copiano come da non elaborare: lo rispettiamo.
                if (c.AvailableFormats.Contains("ExcludeClipboardContentFromMonitorProcessing")) return false;

                // 1) File copiati in Esplora risorse.
                if (c.Contains(StandardDataFormats.StorageItems))
                {
                    var files = (await c.GetStorageItemsAsync()).OfType<StorageFile>().Take(5).ToList();
                    if (files.Count > 0)
                    {
                        if (files.All(f => _appliedPaths.Contains(f.Path))) return false;
                        foreach (var f in files) _ = _r.Files.SendClipFileAsync(f.Path);
                        return true;
                    }
                }

                // 2) Testo.
                if (c.Contains(StandardDataFormats.Text))
                {
                    var text = await c.GetTextAsync();
                    if (string.IsNullOrEmpty(text) || text == _lastApplied || text.Length > 1_000_000) return false;
                    Push(new ClipItem(text, _r.DeviceId, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), true));
                    _r.Send(Proto.NewMsg("clip", ("text", text)));
                    return true;
                }

                // 3) Immagine (ritaglio con Win+Maiusc+S, "Copia immagine" del browser...).
                if (c.Contains(StandardDataFormats.Bitmap))
                {
                    var bmp = await c.GetBitmapAsync();
                    // Lo Strumento di cattura salva anche il file: se tra poco compare, quello è già lo screenshot condiviso.
                    if (fromEvent)
                    {
                        await Task.Delay(4000);
                        if (DateTime.UtcNow - _r.Files.LastScreenshot < TimeSpan.FromSeconds(10)) return false;
                    }
                    var path = await SaveBitmapAsync(bmp);
                    if (path == null) return false;
                    _appliedPaths.Add(path);
                    _ = _r.Files.SendClipFileAsync(path);
                    return true;
                }
                return false;
            }
            catch (Exception) { await Task.Delay(60); }
        }
        return false;
    }

    private static async Task<string?> SaveBitmapAsync(RandomAccessStreamReference bmp)
    {
        using var src = await bmp.OpenReadAsync();
        var decoder = await BitmapDecoder.CreateAsync(src);
        using var soft = await decoder.GetSoftwareBitmapAsync();
        using var mem = new InMemoryRandomAccessStream();
        var enc = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, mem);
        enc.SetSoftwareBitmap(soft);
        await enc.FlushAsync();
        var bytes = new byte[mem.Size];
        mem.Seek(0);
        await mem.ReadAsync(bytes.AsBuffer(), (uint)mem.Size, InputStreamOptions.None);
        var dir = FileStore.FileDir;
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, $"Appunti-{DateTime.Now:yyyyMMdd-HHmmss}.png");
        await File.WriteAllBytesAsync(path, bytes);
        return path;
    }

    /// <summary>Un file copiato su un altro dispositivo arriva anche negli appunti di questo PC (e le immagini come immagine).</summary>
    public void ApplyFile(string path, string mime)
    {
        _ignoreUntil = DateTime.UtcNow.AddSeconds(3);
        _appliedPaths.Add(path);
        _ui.TryEnqueue(async () =>
        {
            try
            {
                var f = await StorageFile.GetFileFromPathAsync(path);
                var dp = new DataPackage();
                dp.SetStorageItems(new IStorageItem[] { f });
                if (mime.StartsWith("image/")) dp.SetBitmap(RandomAccessStreamReference.CreateFromFile(f));
                Clipboard.SetContent(dp);
            }
            catch (Exception) { /* appunti occupati: il file resta comunque nell'archivio */ }
        });
    }

    public void SendText(string text)
    {
        Push(new ClipItem(text, _r.DeviceId, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), true));
        _r.Send(Proto.NewMsg("clip", ("text", text)));
    }

    public void OnRemote(string text, string from)
    {
        if (text.Length == 0 || text.Length > 1_000_000) return;
        Push(new ClipItem(text, from, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), false));
        SetLocal(text);
    }

    /// <summary>Mette il testo negli appunti di questo PC senza rimandarlo agli altri.</summary>
    public void SetLocal(string text)
    {
        _lastApplied = text;
        _ui.TryEnqueue(() =>
        {
            try
            {
                var dp = new DataPackage();
                dp.SetText(text);
                Clipboard.SetContent(dp);
            }
            catch (Exception) { /* appunti occupati: la prossima copia riparte */ }
        });
    }

    private HttpClient? _http;

    /// <summary>Recupera dal server la cronologia degli appunti. Aggiorna solo la cronologia: gli appunti del PC non si toccano.</summary>
    public async Task CatchUp()
    {
        var baseUrl = _r.S.HttpBase();
        if (baseUrl == null) return;
        _http ??= new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, $"{baseUrl}/api/clips");
            req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", _r.S.Token);
            using var resp = await _http.SendAsync(req);
            if (!resp.IsSuccessStatusCode) return;
            if (System.Text.Json.Nodes.JsonNode.Parse(await resp.Content.ReadAsStringAsync()) is not System.Text.Json.Nodes.JsonArray list) return;
            lock (_history)
            {
                var known = _history.Select(h => h.Text).ToHashSet();
                foreach (var n in list)
                {
                    if (n is not System.Text.Json.Nodes.JsonObject o) continue;
                    var text = o.S("text");
                    if (text.Length == 0 || known.Contains(text)) continue;
                    _history.Add(new ClipItem(text, o.S("from"), o.L("ts"), o.S("from") == _r.DeviceId));
                }
                _history.Sort((a, b) => b.Ts.CompareTo(a.Ts));
                if (_history.Count > HistoryMax) _history.RemoveRange(HistoryMax, _history.Count - HistoryMax);
            }
            _r.Raise();
        }
        catch (Exception) { /* senza rete si riprova alla prossima connessione */ }
    }

    private void Push(ClipItem item)
    {
        lock (_history)
        {
            _history.RemoveAll(h => h.Text == item.Text);
            _history.Insert(0, item);
            if (_history.Count > HistoryMax) _history.RemoveRange(HistoryMax, _history.Count - HistoryMax);
        }
        _r.Raise();
    }
}
