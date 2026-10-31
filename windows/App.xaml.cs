using Microsoft.UI.Xaml;

namespace Ponte;

public partial class App : Application
{
    private static Mutex? _single;
    private MainWindow? _window;
    private Window? _setup;

    public App() => InitializeComponent();

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var argv = Environment.GetCommandLineArgs();
        Environment.SetEnvironmentVariable("WEBVIEW2_DEFAULT_BACKGROUND_COLOR", "00000000");

        // Disinstallazione (da Impostazioni > App) e installazione: finestre di Ponte, non procedure guidate di sistema.
        if (argv.Contains("--uninstall"))
        {
            Environment.SetEnvironmentVariable("WEBVIEW2_USER_DATA_FOLDER", Path.Combine(Path.GetTempPath(), "Ponte-Setup-WebView2"));
            if (argv.Contains("--quiet")) { Installer.Uninstall(false); Environment.Exit(0); }
            _setup = new SetupWindow(uninstall: true, fromInstaller: false, runPortable: null);
            _setup.Activate();
            return;
        }
        var fromInstaller = argv.Contains("--setup");
        if (fromInstaller || (!Installer.IsInstalledCopy && !argv.Contains("--portable") && !argv.Contains("--background")))
        {
            Environment.SetEnvironmentVariable("WEBVIEW2_USER_DATA_FOLDER", Path.Combine(Path.GetTempPath(), "Ponte-Setup-WebView2"));
            _setup = new SetupWindow(uninstall: false, fromInstaller: fromInstaller, runPortable: () => Run(argv));
            _setup.Activate();
            return;
        }
        Run(argv);
    }

    private void Run(string[] argv)
    {
        // Una sola copia alla volta: Ponte vive nell'area di notifica.
        _single = new Mutex(true, @"Local\Ponte-single-instance", out var first);
        var show = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\Ponte-show");
        if (!first)
        {
            // Già in esecuzione (magari solo nell'area di notifica): la finestra esistente torna in primo piano.
            if (!argv.Contains("--background")) show.Set();
            Environment.Exit(0);
            return;
        }

        Environment.SetEnvironmentVariable("WEBVIEW2_USER_DATA_FOLDER", Path.Combine(Settings.Dir, "WebView2"));
        Installer.TouchRegistration();
        var router = new Router(Settings.Load());
        router.Notify.Register();
        var background = argv.Contains("--background");

        _window = new MainWindow(router);
        router.Start(_window.DispatcherQueue);
        _window.InitTray();
        if (!background) _window.Activate();
        var w = _window;
        new Thread(() =>
        {
            while (true) { show.WaitOne(); w.DispatcherQueue.TryEnqueue(w.Present); }
        }) { IsBackground = true }.Start();
    }
}
