using Microsoft.UI.Xaml;

namespace Ponte;

public partial class App : Application
{
    private static Mutex? _single;
    private MainWindow? _window;

    public App() => InitializeComponent();

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        // Una sola copia alla volta: Ponte vive nell'area di notifica.
        _single = new Mutex(true, @"Local\Ponte-single-instance", out var first);
        var show = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\Ponte-show");
        if (!first)
        {
            // Già in esecuzione (magari solo nell'area di notifica): la finestra esistente torna in primo piano.
            if (!Environment.GetCommandLineArgs().Contains("--background")) show.Set();
            Environment.Exit(0);
            return;
        }

        // WebView2 trasparente (gradiente ASCII sopra il fondo di Canone) e dati del browser nella cartella di Ponte.
        Environment.SetEnvironmentVariable("WEBVIEW2_DEFAULT_BACKGROUND_COLOR", "00000000");
        Environment.SetEnvironmentVariable("WEBVIEW2_USER_DATA_FOLDER", Path.Combine(Settings.Dir, "WebView2"));
        var router = new Router(Settings.Load());
        router.Notify.Register();
        var background = Environment.GetCommandLineArgs().Contains("--background");

        _window = new MainWindow(router);
        router.Start(_window.DispatcherQueue);
        _window.InitTray();
        if (!background) _window.Activate();
        new Thread(() =>
        {
            while (true) { show.WaitOne(); _window.DispatcherQueue.TryEnqueue(_window.Present); }
        }) { IsBackground = true }.Start();
    }
}
