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

    public void Show(string app, string title, string text)
    {
        if (!_registered || (title.Length == 0 && text.Length == 0)) return;
        try
        {
            var b = new AppNotificationBuilder();
            if (app.Length > 0) b.AddText(app, new AppNotificationTextProperties().SetMaxLines(1));
            if (title.Length > 0) b.AddText(title);
            if (text.Length > 0) b.AddText(text);
            AppNotificationManager.Default.Show(b.BuildNotification());
        }
        catch (Exception) { /* le notifiche non devono mai far cadere l'app */ }
    }
}
