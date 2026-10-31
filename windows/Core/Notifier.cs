using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;

namespace Ponte;

/// <summary>Mostra come notifiche di Windows quelle inoltrate dal telefono.</summary>
public sealed class Notifier
{
    private bool _registered;

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
