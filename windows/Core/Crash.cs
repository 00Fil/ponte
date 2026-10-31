using System.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Ponte;

/// <summary>Errori inattesi: finiscono in %APPDATA%\Ponte\crash.log e, all'avvio, in una finestra da cui si possono copiare.</summary>
public static class Crash
{
    public static string LogPath => Path.Combine(Settings.Dir, "crash.log");

    public static void Log(Exception e, string where = "")
    {
        try
        {
            Directory.CreateDirectory(Settings.Dir);
            File.AppendAllText(LogPath, $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {where}\n{e}\n\n");
        }
        catch (Exception) { }
    }

    public static void Show(Exception e, string where)
    {
        Log(e, where);
        try
        {
            var box = new TextBox
            {
                Text = $"{where}\n\n{e}", IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap,
                FontFamily = new Microsoft.UI.Xaml.Media.FontFamily("Cascadia Mono, Consolas"), FontSize = 12,
            };
            var copy = new Button { Content = "Copia l'errore", Margin = new Thickness(0, 12, 0, 0) };
            copy.Click += (_, _) =>
            {
                var dp = new Windows.ApplicationModel.DataTransfer.DataPackage();
                dp.SetText(box.Text);
                Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(dp);
            };
            var panel = new StackPanel { Padding = new Thickness(24) };
            panel.Children.Add(new TextBlock { Text = "Relay non è riuscito ad aprirsi", FontSize = 20, Margin = new Thickness(0, 0, 0, 12) });
            panel.Children.Add(new ScrollViewer { Height = 380, Content = box });
            panel.Children.Add(copy);
            new Window { Title = "Relay", Content = panel }.Activate();
        }
        catch (Exception) { }
    }
}
