using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;

namespace Ponte;

/// <summary>Mostra come notifiche di Windows quelle inoltrate dal telefono.</summary>
public sealed class Notifier
{
    private bool _registered;
    private readonly Dictionary<string, long> _recent = new();

    public void Register()
    {
        try
        {
            AppNotificationManager.Default.Register();
            _registered = true;
        }
        catch (Exception) { _registered = false; }
    }

    public void Unregister()
    {
        try { if (_registered) AppNotificationManager.Default.Unregister(); } catch (Exception) { }
    }

    public void Show(string app, string title, string text, string key = "")
    {
        if (!_registered || (title.Length == 0 && text.Length == 0)) return;
        // Stesso contenuto già mostrato da pochi secondi (arrivato da due strade o da due notifiche del telefono): si ignora.
        var content = $"{app}\u0000{title}\u0000{text}";
        var now = Environment.TickCount64;
        lock (_recent)
        {
            if (_recent.TryGetValue(content, out var last) && now - last < 10_000) return;
            _recent[content] = now;
            if (_recent.Count > 200)
                foreach (var k in _recent.Where(x => now - x.Value > 10_000).Select(x => x.Key).ToList()) _recent.Remove(k);
        }
        try
        {
            var b = new AppNotificationBuilder();
            if (app.Length > 0) b.AddText(app, new AppNotificationTextProperties().SetMaxLines(1));
            if (title.Length > 0) b.AddText(title);
            if (text.Length > 0) b.AddText(text);
            var n = b.BuildNotification();
            // Stessa notifica del telefono aggiornata: sostituisce quella già mostrata invece di aggiungerne un'altra.
            if (key.Length > 0)
            {
                n.Tag = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(key)))[..32];
                n.Group = "telefono";
            }
            AppNotificationManager.Default.Show(n);
        }
        catch (Exception) { /* le notifiche non devono mai far cadere l'app */ }
    }
}
