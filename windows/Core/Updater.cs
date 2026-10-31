using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http.Headers;
using System.Text.Json.Nodes;

namespace Ponte;

/// <summary>
/// Aggiornamenti automatici: il server di Ponte legge l'ultima release su GitHub e la gira qui.
/// Si controlla all'avvio e ogni 6 ore. Se c'è una build più nuova: si scarica, si estrae, e un piccolo
/// script aspetta che Ponte si chiuda, copia i file nuovi al posto dei vecchi e lo riavvia.
/// </summary>
public sealed class Updater
{
    private readonly Router _r;
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromMinutes(10) };
    private bool _busy;

    public string Status { get; private set; } = "";

    /// <summary>Fase corrente dell'aggiornamento: 1 scarico, 2 estrazione, 3 installazione (poi Ponte si riavvia).</summary>
    public sealed record Progress(int Step, string Version, long Done, long Total, double BytesPerSec, string Message = "");
    // Step: 0 ricerca, 1 scarico, 2 estrazione, 3 installazione, -1 già aggiornato, -2 errore.
    public event Action<Progress?>? Changed;
    private void Report(Progress? p) => Changed?.Invoke(p);
    public int Current { get; } = typeof(Updater).Assembly.GetName().Version?.Build is int b && b > 0 ? b : 0;

    public Updater(Router r) => _r = r;

    public void Start() => _ = Task.Run(async () =>
    {
        await Task.Delay(TimeSpan.FromSeconds(45));
        while (true)
        {
            await CheckAsync(install: true);
            await Task.Delay(TimeSpan.FromHours(6));
        }
    });

    private void Set(string s)
    {
        Status = s;
        _r.Raise();
    }

    public async Task CheckAsync(bool install, bool manual = false)
    {
        if (_busy) return;
        void Outcome(string text, int step = -2)
        {
            Set(text);
            if (!manual) { Report(null); return; }
            var p = new Progress(step, "", 0, 0, 0, text);
            Report(p);
            _ = Task.Delay(step == -1 ? 2400 : 4200).ContinueWith(_ => Report(null));
        }
        var baseUrl = _r.S.HttpBase();
        if (baseUrl == null) { Outcome("Serve il server per gli aggiornamenti"); return; }
        _busy = true;
        try
        {
            Set("Controllo in corso");
            if (manual) Report(new Progress(0, "", 0, 0, 0));
            var started = Stopwatch.StartNew();
            using var req = Get($"{baseUrl}/api/update?fresh=1");
            using var resp = await _http.SendAsync(req);
            if (!resp.IsSuccessStatusCode) { Outcome((int)resp.StatusCode == 404 ? "Server da aggiornare: rifai il deploy" : (int)resp.StatusCode == 502 ? "Il server non legge GitHub: manca PONTE_GITHUB_TOKEN" : $"Server senza aggiornamenti ({(int)resp.StatusCode})"); return; }
            if (JsonNode.Parse(await resp.Content.ReadAsStringAsync()) is not JsonObject info) return;
            var build = (int)info.L("build");
            var hasWin = info["windows"] is JsonValue w && w.TryGetValue<bool>(out var ok) && ok;
            if (manual && started.ElapsedMilliseconds < 900) await Task.Delay(900 - (int)started.ElapsedMilliseconds);
            if (build <= Current || !hasWin) { Outcome("Hai l'ultima versione", -1); return; }
            if (!install) { Outcome($"Disponibile la versione 1.0.{build}", -1); return; }

            var appDir = AppContext.BaseDirectory.TrimEnd('\\');
            if (!Writable(appDir)) { Outcome("La cartella di Ponte non è scrivibile: spostala ad esempio in Documenti"); return; }

            var ver = $"1.0.{build}";
            Set($"Scarico la versione {ver}");
            Report(new Progress(1, ver, 0, 0, 0));
            var work = Path.Combine(Settings.Dir, "update");
            if (Directory.Exists(work)) Directory.Delete(work, true);
            Directory.CreateDirectory(work);
            var zip = Path.Combine(work, "ponte.zip");
            using (var dreq = Get($"{baseUrl}/api/update/windows"))
            using (var dresp = await _http.SendAsync(dreq, HttpCompletionOption.ResponseHeadersRead))
            {
                if (!dresp.IsSuccessStatusCode) { Outcome("Download non riuscito"); return; }
                var total = dresp.Content.Headers.ContentLength ?? 0;
                await using var o = File.Create(zip);
                await using var i = await dresp.Content.ReadAsStreamAsync();
                var buf = new byte[64 * 1024];
                long done = 0;
                var sw = Stopwatch.StartNew();
                long lastPush = 0;
                int n;
                while ((n = await i.ReadAsync(buf)) > 0)
                {
                    await o.WriteAsync(buf.AsMemory(0, n));
                    done += n;
                    if (sw.ElapsedMilliseconds - lastPush > 80)
                    {
                        lastPush = sw.ElapsedMilliseconds;
                        Report(new Progress(1, ver, done, total, sw.Elapsed.TotalSeconds > 0.2 ? done / sw.Elapsed.TotalSeconds : 0));
                    }
                }
                Report(new Progress(1, ver, done, Math.Max(total, done), 0));
            }
            Set($"Estraggo la versione {ver}");
            Report(new Progress(2, ver, 1, 1, 0));
            var unpacked = Path.Combine(work, "nuova");
            ZipFile.ExtractToDirectory(zip, unpacked);
            if (!File.Exists(Path.Combine(unpacked, "Ponte.exe"))) { Set("Pacchetto non valido"); return; }

            // Con l'audio in viaggio verso un altro dispositivo si aspetta: l'aggiornamento non deve interromperlo.
            while (_r.Audio.SinkId != null) await Task.Delay(TimeSpan.FromMinutes(1));

            Set($"Installo la versione {ver}");
            Report(new Progress(3, ver, 1, 1, 0));
            await Task.Delay(900);
            string Q(string s) => s.Replace("'", "''");
            var script = $"Wait-Process -Id {Environment.ProcessId} -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1; " +
                         $"Copy-Item -Path '{Q(unpacked)}\\*' -Destination '{Q(appDir)}' -Recurse -Force; " +
                         $"Start-Process -FilePath '{Q(Path.Combine(appDir, "Ponte.exe"))}' -ArgumentList '--background'";
            var psi = new ProcessStartInfo("powershell.exe") { UseShellExecute = false, CreateNoWindow = true };
            foreach (var a in new[] { "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", script }) psi.ArgumentList.Add(a);
            Process.Start(psi);
            _r.Notify.Unregister();
            Environment.Exit(0);
        }
        catch (Exception) { Outcome("Aggiornamento non riuscito, riprovo più tardi"); }
        finally { _busy = false; }
    }

    private HttpRequestMessage Get(string url)
    {
        var m = new HttpRequestMessage(HttpMethod.Get, url);
        m.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _r.S.Token);
        return m;
    }

    private static bool Writable(string dir)
    {
        try
        {
            var p = Path.Combine(dir, ".ponte-scrittura");
            File.WriteAllText(p, "");
            File.Delete(p);
            return true;
        }
        catch (Exception) { return false; }
    }
}
