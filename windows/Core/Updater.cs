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

    public async Task CheckAsync(bool install)
    {
        if (_busy) return;
        var baseUrl = _r.S.HttpBase();
        if (baseUrl == null) { Set("Serve il server per gli aggiornamenti"); return; }
        _busy = true;
        try
        {
            Set("Controllo in corso");
            using var req = Get($"{baseUrl}/api/update?fresh=1");
            using var resp = await _http.SendAsync(req);
            if (!resp.IsSuccessStatusCode) { Set((int)resp.StatusCode == 404 ? "Server da aggiornare: rifai il deploy" : (int)resp.StatusCode == 502 ? "Il server non legge GitHub: manca PONTE_GITHUB_TOKEN" : $"Server senza aggiornamenti ({(int)resp.StatusCode})"); return; }
            if (JsonNode.Parse(await resp.Content.ReadAsStringAsync()) is not JsonObject info) return;
            var build = (int)info.L("build");
            var hasWin = info["windows"] is JsonValue w && w.TryGetValue<bool>(out var ok) && ok;
            if (build <= Current || !hasWin) { Set("Hai l'ultima versione"); return; }
            if (!install) { Set($"Disponibile la versione 1.0.{build}"); return; }

            var appDir = AppContext.BaseDirectory.TrimEnd('\\');
            if (!Writable(appDir)) { Set("La cartella di Ponte non è scrivibile: spostala ad esempio in Documenti"); return; }

            Set($"Scarico la versione 1.0.{build}");
            var work = Path.Combine(Settings.Dir, "update");
            if (Directory.Exists(work)) Directory.Delete(work, true);
            Directory.CreateDirectory(work);
            var zip = Path.Combine(work, "ponte.zip");
            using (var dreq = Get($"{baseUrl}/api/update/windows"))
            using (var dresp = await _http.SendAsync(dreq, HttpCompletionOption.ResponseHeadersRead))
            {
                if (!dresp.IsSuccessStatusCode) { Set("Download non riuscito"); return; }
                await using var o = File.Create(zip);
                await dresp.Content.CopyToAsync(o);
            }
            var unpacked = Path.Combine(work, "nuova");
            ZipFile.ExtractToDirectory(zip, unpacked);
            if (!File.Exists(Path.Combine(unpacked, "Ponte.exe"))) { Set("Pacchetto non valido"); return; }

            // Con l'audio in viaggio verso un altro dispositivo si aspetta: l'aggiornamento non deve interromperlo.
            while (_r.Audio.SinkId != null) await Task.Delay(TimeSpan.FromMinutes(1));

            Set($"Installo la versione 1.0.{build}");
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
        catch (Exception) { Set("Aggiornamento non riuscito, riprovo più tardi"); }
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
