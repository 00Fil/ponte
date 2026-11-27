using System.Globalization;
using System.Windows.Input;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.Win32;
using Windows.Graphics;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Ponte;

internal sealed class Cmd : ICommand
{
    private readonly Action _a;
    public Cmd(Action a) => _a = a;
    public event EventHandler? CanExecuteChanged { add { } remove { } }
    public bool CanExecute(object? parameter) => true;
    public void Execute(object? parameter) => _a();
}

public sealed partial class MainWindow : Window
{
    // Le stesse sei voci di Android, con le stesse icone. L'audio sta in Home e nelle Impostazioni.
    private static readonly string[] Tabs = { "Home", "Condividi", "Finanze", "Agenda", "Corpo", "Impostazioni" };
    private static readonly int[] TabIcons = { Tab.House, Tab.Devices, Tab.Wallet, Tab.Calendar, Tab.Flame, Tab.Settings };
    /// <summary>Larghezza massima della colonna di ogni pagina, come su Android.</summary>
    private static readonly double[] PageMax = { 720, 1024, 720, 720, 720, 720 };

    private readonly Router _r;
    private readonly ScrollViewer[] _pages;
    private readonly StackPanel[] _hosts;
    private int _tab;
    private bool _exiting;
    private string _filter = "";

    public MainWindow(Router r)
    {
        InitializeComponent();
        InitViewerKeys();
        _r = r;
        InitUpdateCard();
        _pages = new[] { Page0, Page1, Page2, Page3, Page4, Page5 };
        _hosts = new[] { Host0, Host1, Host2, Host3, Host4, Host5 };

        Title = "Relay";
        SetupFrame();
        try { AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "relay.ico")); } catch (Exception) { }
        // Chiudere la finestra non chiude Ponte: resta nell'area di notifica e continua a sincronizzare.
        AppWindow.Closing += (_, e) =>
        {
            if (_exiting) return;
            e.Cancel = true;
            // la fotocamera non resta accesa dietro una finestra chiusa
            if (_bdOver == "camera") CloseBodyLayer();
            AppWindow.Hide();
            // Finestra nascosta: il gradiente si ferma, niente consumo.
            HeaderWeb.Visibility = Visibility.Collapsed;
        };
        _ = InitWebAsync(HeaderWeb, "shape=band&ascii=0");
        Root.ActualThemeChanged += (_, _) => OnThemeChanged();
        UpdateFades();
        Root.SizeChanged += (_, _) => ApplyLayout();
        _r.Changed += () => DispatcherQueue.TryEnqueue(Refresh);
        InitShell();
        InitCameras();
        VisibilityChanged += (_, e) => { if (!e.Visible && _bdOver == "camera") CloseBodyLayer(); };

        try { Select(_r.S.Token.Length == 0 ? 5 : 0); }
        catch (Exception e) { Crash.Log(e, "Primo disegno"); }
        if (!_r.S.Onboarded) ShowOnboarding();
    }

    // ---------------------------------------------------------------- cornice

    /// <summary>Finestra senza barra di sistema: bordo e ridimensionamento restano, i tre pallini sono nostri. Dimensione e posizione seguono lo schermo.</summary>
    private void SetupFrame()
    {
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(DragArea);
        if (AppWindow.Presenter is OverlappedPresenter p) p.SetBorderAndTitleBar(true, false);

        var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        // dimensione in pixel logici: segue la scala dello schermo, quindi su schermi ad alta densità non resta minuscola
        var scale = 1.0;
        try { scale = GetDpiForWindow(WindowNative.GetWindowHandle(this)) / 96.0; } catch (Exception) { }
        var w = (int)Math.Min(1360 * scale, area.Width * 0.92);
        var h = (int)Math.Min(900 * scale, area.Height * 0.92);
        AppWindow.MoveAndResize(new RectInt32(area.X + (area.Width - w) / 2, area.Y + (area.Height - h) / 2, w, h));

        // Finestra in secondo piano: i pallini diventano grigi, come su macOS.
        Activated += (_, a) =>
        {
            var on = a.WindowActivationState != WindowActivationState.Deactivated;
            var grey = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 0x9A, 0x9A, 0x95));
            LRed.Fill = on ? new SolidColorBrush(Windows.UI.Color.FromArgb(255, 0xFF, 0x5F, 0x57)) : grey;
            LYellow.Fill = on ? new SolidColorBrush(Windows.UI.Color.FromArgb(255, 0xFE, 0xBC, 0x2E)) : grey;
            LGreen.Fill = on ? new SolidColorBrush(Windows.UI.Color.FromArgb(255, 0x28, 0xC8, 0x40)) : grey;
        };
    }

    private void OnLightsEnter(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e) => FadeLights(1);
    private void OnLightsExit(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e) => FadeLights(0);

    private void FadeLights(double to)
    {
        foreach (var g in new UIElement[] { GRed, GYellow, GGreen }) FadeTo(g, to, 120);
    }

    private void OnLightClose(object sender, Microsoft.UI.Xaml.Input.TappedRoutedEventArgs e) => Close();

    private void OnLightMin(object sender, Microsoft.UI.Xaml.Input.TappedRoutedEventArgs e)
    {
        if (AppWindow.Presenter is OverlappedPresenter p) p.Minimize();
    }

    private void OnLightMax(object sender, Microsoft.UI.Xaml.Input.TappedRoutedEventArgs e)
    {
        if (AppWindow.Presenter is not OverlappedPresenter p) return;
        if (p.State == OverlappedPresenterState.Maximized) p.Restore(); else p.Maximize();
    }

    public void InitTray()
    {
        Tray.LeftClickCommand = new Cmd(() => DispatcherQueue.TryEnqueue(ShowWindow));
        Tray.ForceCreate();
    }

    public void Present() => ShowWindow();

    private void ShowWindow()
    {
        HeaderWeb.Visibility = Visibility.Visible;
        AppWindow.Show();
        Activate();
    }

    private void OnTrayOpen(object sender, RoutedEventArgs e) => ShowWindow();

    private void OnTrayLock(object sender, RoutedEventArgs e) => _r.Hp.ToggleLock();

    private void OnTrayExit(object sender, RoutedEventArgs e)
    {
        _exiting = true;
        _r.Notify.Unregister();
        Tray.Dispose();
        Environment.Exit(0);
    }

    // ---------------------------------------------------------------- struttura

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hwnd);

    private bool Wide => Root.ActualWidth >= 1024;

    /// <summary>Ogni pagina è una colonna centrata con la sua larghezza massima; il titolo si allinea alla stessa colonna.</summary>
    private void FitColumn()
    {
        var avail = Root.ActualWidth - (Wide ? 240 : 0);
        if (avail <= 0) return;
        var bottom = Wide ? 24 : 100;
        for (var i = 0; i < _hosts.Length; i++)
        {
            _hosts[i].Width = Math.Max(260, Math.Min(PageMax[i], avail) - 32);
            _hosts[i].HorizontalAlignment = HorizontalAlignment.Center;
            _hosts[i].Margin = new Thickness(0, 12, 0, bottom);
        }
        foreach (var pg in _pages) pg.HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled;
        TitleBox.Width = Math.Max(100, _hosts[_tab].Width - 16);
        TitleBox.HorizontalAlignment = HorizontalAlignment.Center;
        // sotto "Home" resta il posto per la pillola dei dispositivi
        TitleBox.Margin = new Thickness(0, 0, 0, _tab == 0 ? 46 : 12);
        PlaceMorph();
    }

    private void ApplyLayout()
    {
        SideCol.Width = new GridLength(Wide ? 240 : 0);
        Side.Visibility = Wide ? Visibility.Visible : Visibility.Collapsed;
        Bottom.Visibility = Wide ? Visibility.Collapsed : Visibility.Visible;
        FitColumn();
        // la Home è fatta di quadrati: se la colonna cambia larghezza si ridisegna
        if (_tab == 0 && _homeWidth > 0 && Math.Abs(_hosts[0].Width - _homeWidth) > 0.5) RenderHome();
    }

    private void Select(int i)
    {
        i = Math.Clamp(i, 0, Tabs.Length - 1);
        var dir = i >= _tab ? 1 : -1;
        var first = PageTitle.Text.Length == 0;
        var changed = first || i != _tab;
        if (changed)
        {
            ClosePanel();
            SetDevOpen(false);
        }
        _tab = i;
        PageTitle.Text = Tabs[i];
        for (var p = 0; p < _pages.Length; p++) _pages[p].Visibility = p == i ? Visibility.Visible : Visibility.Collapsed;
        if (changed && !first)
        {
            // titolo e pagina scorrono insieme, nella direzione della voce scelta; il titolo un po' di più
            Enter(PageTitle, 72 * dir, 0, 420);
            Enter(_pages[i], 48 * dir, 0, 380);
        }
        MarkNav(!first);
        FitColumn();
        Refresh();
    }

    private void Refresh()
    {
        switch (_tab)
        {
            case 0: RenderHome(); break;
            case 1: if (!Typing()) RenderShare(); break;
            case 2: RenderFinance(); break;
            case 3: if (!Typing()) RenderAgenda(); break;
            case 4: if (!Typing()) RenderBody(); break;
            default: if (!Typing()) RenderSettings(); break;
        }
        UpdateMorph();
        if (_panel != null) RenderPanel();
    }

    /// <summary>Apre una funzione direttamente, come dalla Home di Android.</summary>
    private void Open(string to)
    {
        ClosePanel();
        switch (to)
        {
            case "clips": _shareTab = 0; _folder = null; _backupOpen = false; Select(1); break;
            case "images": _shareTab = 1; _folder = null; _backupOpen = false; Select(1); break;
            case "share": _backupOpen = false; Select(1); break;
            case "backup": OpenBackup(); Select(1); break;
            case "cams": _homeCams = true; _camsTab = 0; _camsEdit = false; _homeHold = DateTime.MinValue; Select(0); CamPulse(); break;
            case "finance": _finSub = null; Select(2); break;
            case "fin-add": _finSub = "add"; Select(2); break;
            case "agenda": _agSub = null; Select(3); break;
            case "studio": _agSub = "study"; Select(3); break;
            case "todo": _agSub = "todo"; Select(3); break;
            case "body": _bdSub = null; Select(4); break;
            case "audio": _setSub = "audio"; Select(5); break;
            default: _setSub = null; Select(5); break;
        }
    }

    /// <summary>Se stai scrivendo in un campo, le pagine con moduli non si ridisegnano sotto le dita.</summary>
    private bool Typing()
    {
        try { return Microsoft.UI.Xaml.Input.FocusManager.GetFocusedElement(Root.XamlRoot) is TextBox; } catch (Exception) { return false; }
    }

    private StackPanel Reset(string title)
    {
        // Il titolo sta nella testata animata sopra la pagina.
        var h = _hosts[_tab];
        h.Children.Clear();
        _money.Clear();
        _hideCharts.Clear();
        _ticks.Clear();
        return h;
    }

    private async Task ConfirmDeleteAsync(FileEntry e)
    {
        var dlg = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = e.Kind == "screenshot" ? "Eliminare lo screenshot?" : "Eliminare il file?",
            Content = "Sparisce da questo PC. Sugli altri resta.",
            PrimaryButtonText = "Elimina",
            CloseButtonText = "Annulla",
            DefaultButton = ContentDialogButton.Close,
        };
        if (await dlg.ShowAsync() == ContentDialogResult.Primary) _r.Files.Delete(e);
    }

    private async Task PickFilesAsync()
    {
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add("*");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
        var files = await picker.PickMultipleFilesAsync();
        if (files == null) return;
        foreach (var f in files) _ = _r.Files.SendFileAsync(f.Path, "file");
    }

    private void Apply()
    {
        _r.S.Save();
        _r.Reconfigure();
    }

    private async Task PickScreenshotFolderAsync()
    {
        var picker = new FolderPicker();
        picker.FileTypeFilter.Add("*");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
        var folder = await picker.PickSingleFolderAsync();
        if (folder == null) return;
        _r.S.ScreenshotFolder = folder.Path;
        Apply();
        RenderSettings();
    }

    private static bool GetAutostart()
    {
        using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        return k?.GetValue("Ponte") != null;
    }

    private static void SetAutostart(bool on)
    {
        using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
        if (k == null) return;
        if (on) k.SetValue("Ponte", $"\"{Environment.ProcessPath}\" --background");
        else k.DeleteValue("Ponte", false);
    }

    // ---------------------------------------------------------------- elementi Canone

    private static Style St(string key) => (Style)Application.Current.Resources[key];

    private static TextBlock Text(string text, string style = "BodyText") =>
        new() { Text = text, Style = St(style), TextWrapping = TextWrapping.Wrap };

    /// <summary>Scheda (CCard): piana, raggio 28, spazio 16.</summary>
    private static Border Card(params UIElement[] children)
    {
        var col = new StackPanel { Spacing = 16 };
        foreach (var c in children) col.Children.Add(c);
        return new Border { Style = St("CardStyle"), Child = col };
    }

    private static Border Divider() => new() { Style = St("DividerStyle") };

    private static Button Btn(string label, Action click, string style = "SecondaryButton")
    {
        var b = new Button { Content = label, Style = St(style) };
        b.Click += (_, _) => click();
        return b;
    }

    private static Button Btn(string label, Func<Task> click, string style = "SecondaryButton")
    {
        var b = new Button { Content = label, Style = St(style) };
        b.Click += async (_, _) => await click();
        return b;
    }

    /// <summary>Riga di scheda (CRow / CSwitchRow): titolo, nota sotto, controllo a destra; alta almeno 48.</summary>
    private static Grid Row(string title, string? note, FrameworkElement? trailing = null)
    {
        var g = new Grid { ColumnSpacing = 12, MinHeight = 48 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var left = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        left.Children.Add(Text(title));
        if (!string.IsNullOrEmpty(note)) left.Children.Add(Text(note, "Footnote"));
        g.Children.Add(left);
        if (trailing != null)
        {
            trailing.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(trailing, 1);
            g.Children.Add(trailing);
        }
        return g;
    }

    private static Grid Toggle(string title, string? note, bool on, Action<bool> changed) => Row(title, note, GlassSwitch(on, changed, title));

    /// <summary>Etichetta sempre sopra e sempre visibile; il segnaposto mostra solo un esempio.</summary>
    private static StackPanel Field(string label, Control input)
    {
        var p = new StackPanel { Spacing = 8 };
        p.Children.Add(Text(label, "Footnote"));
        p.Children.Add(input);
        return p;
    }

    private static string FormatSize(long b) =>
        b < 1024 * 1024 ? $"{Math.Max(1, b / 1024)} KB" : $"{b / 1024.0 / 1024.0:0.0} MB";
}
