namespace Ponte;

/// <summary>
/// Guarda la cartella degli screenshot (di solito Immagini\Screenshots, dove salva lo Strumento di cattura
/// con il salvataggio automatico attivo e dove finisce Win+Stamp) e manda ogni nuova immagine agli altri.
/// </summary>
public sealed class ScreenshotWatcher
{
    private static readonly HashSet<string> Ext = new(StringComparer.OrdinalIgnoreCase) { ".png", ".jpg", ".jpeg", ".bmp", ".gif", ".webp" };
    private readonly Router _r;
    private readonly HashSet<string> _done = new(StringComparer.OrdinalIgnoreCase);
    private FileSystemWatcher? _w;

    public ScreenshotWatcher(Router r) => _r = r;

    public void Start()
    {
        var dir = _r.S.EffectiveScreenshotFolder;
        try
        {
            Directory.CreateDirectory(dir);
            _w = new FileSystemWatcher(dir) { IncludeSubdirectories = false, NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite };
            _w.Created += (_, e) => Handle(e.FullPath);
            _w.Renamed += (_, e) => Handle(e.FullPath);
            _w.EnableRaisingEvents = true;
        }
        catch (Exception) { /* cartella non accessibile: lo schermo resta senza archivio automatico */ }
    }

    public void Stop()
    {
        _w?.Dispose();
        _w = null;
    }

    private void Handle(string path)
    {
        if (!Ext.Contains(Path.GetExtension(path))) return;
        lock (_done) if (!_done.Add(path)) return;
        _ = Task.Run(async () =>
        {
            await _r.Files.SendFileAsync(path, "screenshot");
            await Task.Delay(TimeSpan.FromMinutes(1));
            lock (_done) _done.Remove(path);
        });
    }
}
