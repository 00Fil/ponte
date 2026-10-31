using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Ponte;

public sealed class Settings
{
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Ponte");
    private static readonly string FilePath = Path.Combine(Dir, "settings.json");

    public string DeviceId { get; set; } = Guid.NewGuid().ToString();
    public string Name { get; set; } = Environment.MachineName;
    public string HubUrl { get; set; } = "";
    public string TokenProtected { get; set; } = "";
    public bool LanEnabled { get; set; } = true;
    public bool Screenshots { get; set; } = true;
    public string ScreenshotFolder { get; set; } = "";
    public bool ShowPhoneNotifications { get; set; } = true;
    public HashSet<string> Headphones { get; set; } = new();
    public int IdleMinutes { get; set; } = 10;
    /// <summary>Cuffie in cui far andare l'audio di questo PC ("" = passaggio automatico spento).</summary>
    public string PreferredHp { get; set; } = "";
    /// <summary>Se le cuffie preferite non ci sono: true = muto, false = casse.</summary>
    public bool FallbackMute { get; set; }
    /// <summary>Configurazione iniziale completata.</summary>
    public bool Onboarded { get; set; }
    /// <summary>Ultimi indirizzi diretti noti di ogni dispositivo: servono quando il server non è raggiungibile.</summary>
    public Dictionary<string, List<string>> Peers { get; set; } = new();

    /// <summary>Il token sta su disco cifrato con DPAPI: si decifra solo con questo account Windows.</summary>
    [JsonIgnore]
    public string Token
    {
        get
        {
            if (TokenProtected.Length == 0) return "";
            try { return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(TokenProtected), null, DataProtectionScope.CurrentUser)); }
            catch (CryptographicException) { return ""; }
        }
        set => TokenProtected = value.Length == 0 ? "" : Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(value), null, DataProtectionScope.CurrentUser));
    }

    [JsonIgnore]
    public string EffectiveScreenshotFolder =>
        ScreenshotFolder.Length > 0 ? ScreenshotFolder : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "Screenshots");

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath)) return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
        }
        catch (Exception) { /* file rovinato: si riparte da zero */ }
        var s = new Settings();
        s.Save();
        return s;
    }

    public void Save()
    {
        Directory.CreateDirectory(Dir);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }

    /// <summary>"wss://host/ws" a partire da quello che scrive l'utente (host, https://host, wss://host/ws...).</summary>
    public string? WsUrl()
    {
        var u = HubUrl.Trim().TrimEnd('/');
        if (u.Length == 0) return null;
        if (u.StartsWith("https://")) u = "wss://" + u[8..];
        else if (u.StartsWith("http://")) u = "ws://" + u[7..];
        else if (!u.StartsWith("wss://") && !u.StartsWith("ws://")) u = "wss://" + u;
        return u.EndsWith("/ws") ? u : u + "/ws";
    }

    public string? HttpBase()
    {
        var w = WsUrl();
        if (w == null) return null;
        w = w[..^3];
        return w.StartsWith("wss://") ? "https://" + w[6..] : "http://" + w[5..];
    }
}
