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
    // Stessi moduli di Android: Home, Condividi (dispositivi, appunti, immagini, file), Finanze, Agenda, Studio, Audio, Impostazioni.
    private static readonly string[] Tabs = { "Home", "Condividi", "Finanze", "Agenda", "Corpo", "Audio", "Impostazioni" };

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
        _pages = new[] { Page5, Page0, Page1, PageA, PageB, Page2, Page3 };
        _hosts = new[] { Host5, Host0, Host1, HostA, HostB, Host2, Host3 };
        Page4.Visibility = Visibility.Collapsed;
        HomeHero.Visibility = Visibility.Collapsed;

        Title = "Relay";
        SetupFrame();
        try { AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "relay.ico")); } catch (Exception) { }
        // Chiudere la finestra non chiude Ponte: resta nell'area di notifica e continua a sincronizzare.
        AppWindow.Closing += (_, e) =>
        {
            if (_exiting) return;
            e.Cancel = true;
            AppWindow.Hide();
            // Finestra nascosta: il gradiente si ferma, niente consumo.
            HeaderWeb.Visibility = Visibility.Collapsed;
        };
        _ = InitWebAsync(HeaderWeb, "shape=band&cell=10");
        Root.ActualThemeChanged += (_, _) => OnThemeChanged();
        UpdateFades();
        Root.SizeChanged += (_, _) => ApplyLayout();
        _r.Changed += () => DispatcherQueue.TryEnqueue(Refresh);
        Root.ActualThemeChanged += (_, _) => UpdateSideStatus();

        try { Select(_r.S.Token.Length == 0 ? 6 : 0); }
        catch (Exception e) { Crash.Log(e, "Barra laterale"); }
        if (!_r.S.Onboarded) ShowOnboarding();
    }

    // ---------------------------------------------------------------- cornice

    /// <summary>Finestra senza barra di sistema: bordo e ridimensionamento restano, i tre pallini sono nostri. Dimensione e posizione seguono lo schermo.</summary>
    private void SetupFrame()
    {
        // Senza Mica (Windows 10) la barra laterale usa il suo colore pieno.
        if (!Microsoft.UI.Composition.SystemBackdrops.MicaController.IsSupported()) SideFallback.Visibility = Visibility.Visible;
        try { SystemBackdrop = new Microsoft.UI.Xaml.Media.MicaBackdrop { Kind = Microsoft.UI.Composition.SystemBackdrops.MicaKind.BaseAlt }; } catch (Exception) { }
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

    /// <summary>Il layout segue la finestra: barra laterale da 1024 in su, barra in basso sotto.</summary>
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hwnd);

    /// <summary>Titolo e pagine hanno la stessa larghezza esatta, centrata nell'area dei contenuti: niente più scarti a destra.</summary>
    private void FitColumn()
    {
        var avail = Root.ActualWidth - (Side.Visibility == Visibility.Visible ? SideCol.Width.Value : 0);
        if (avail <= 0) return;
        var w = Math.Max(260, Math.Min(840, avail - 64));
        TitleBox.MaxWidth = 100000; TitleBox.Margin = new Thickness(0); TitleBox.Width = w; TitleBox.HorizontalAlignment = HorizontalAlignment.Center;
        Col5.MaxWidth = 100000; Col5.Margin = new Thickness(0, 4, 0, 40); Col5.Width = w; Col5.HorizontalAlignment = HorizontalAlignment.Center;
        for (var i = 0; i < _hosts.Length; i++)
        {
            if (_hosts[i] == Host5) continue;
            _hosts[i].MaxWidth = 100000; _hosts[i].Margin = new Thickness(0, 4, 0, 40); _hosts[i].Width = w; _hosts[i].HorizontalAlignment = HorizontalAlignment.Center;
        }
        foreach (var pg in _pages) pg.HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled;
    }

    private void ApplyLayout()
    {
        var wide = Root.ActualWidth >= 1024;
        SideCol.Width = new GridLength(wide ? 232 : 0);
        Side.Visibility = wide ? Visibility.Visible : Visibility.Collapsed;
        Bottom.Visibility = wide ? Visibility.Collapsed : Visibility.Visible;
        FitColumn();
    }

    private readonly List<Button> _nav = new();

    private void Select(int i)
    {
        var dir = i >= _tab ? 1 : -1;
        var changed = i != _tab || PageTitle.Text.Length == 0;
        var first = _nav.Count == 0;
        if (changed && i == 0) _homeIntro = true;
        _tab = i;
        PageTitle.Text = Tabs[i];
        for (var p = 0; p < _pages.Length; p++) _pages[p].Visibility = p == i ? Visibility.Visible : Visibility.Collapsed;
        if (changed && !first)
        {
            // Titolo e pagina si muovono insieme, nella stessa direzione della voce scelta.
            Enter(PageTitle, 0, 10 * dir, 340);
            Enter(_pages[i], 0, 10 * dir, 340);
        }

        // Barra laterale: le voci restano, cambia lo stile e l'indicatore scivola con una molla.
        if (first)
        {
            for (var t = 0; t < Tabs.Length; t++)
            {
                var idx = t;
                var nb = new Button { Style = St("NavItem"), Content = NavContent(t) };
                nb.Click += (_, _) => Select(idx);
                _nav.Add(nb);
                (t < 5 ? NavMain : t == 5 ? NavTools : NavFoot).Children.Add(nb);
            }
        }
        for (var t = 0; t < _nav.Count; t++) _nav[t].Style = St(t == i ? "NavItemSelected" : "NavItem");
        MoveIndicator(i, animate: !first);
        if (first) NavLayer.SizeChanged += (_, _) => MoveIndicator(_tab, animate: false);

        BottomTabs.Children.Clear();
        BottomTabs.ColumnDefinitions.Clear();
        for (var t = 0; t < Tabs.Length; t++)
        {
            var idx = t;
            var b = Btn(Tabs[t], () => Select(idx), t == i ? "TabButtonSelected" : "TabButton");
            BottomTabs.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            Grid.SetColumn(b, t);
            BottomTabs.Children.Add(b);
        }
        ApplyLayout();
        UpdateSideStatus();
        Refresh();
    }

    private static readonly string[] Glyphs = { "\uE80F", "\uE72D", "\uE8C7", "\uE787", "\uEB51", "\uE7F6", "\uE713" };
    private readonly TextBlock[] _badge = new TextBlock[7];
    private readonly Border[] _badgeBox = new Border[7];

    private Grid NavContent(int t)
    {
        var g = new Grid { ColumnSpacing = 12 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.Children.Add(new FontIcon { Glyph = Glyphs[t], FontSize = 17, FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets") });
        var label = new TextBlock { Text = Tabs[t], VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(label, 1);
        g.Children.Add(label);
        // Contatore a destra (non per Impostazioni).
        var n = new TextBlock { FontSize = 11, FontFamily = (FontFamily)Application.Current.Resources["FontSansMedium"], Foreground = Canone.Get("LabelSecondary", Dark) };
        var box = new Border { Background = Canone.Get("BgSunken", Dark), CornerRadius = new CornerRadius(6), Padding = new Thickness(7, 1, 7, 1), VerticalAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed, Child = n };
        Grid.SetColumn(box, 2);
        g.Children.Add(box);
        _badge[t] = n;
        _badgeBox[t] = box;
        return g;
    }

    private void SetBadge(int t, int count)
    {
        if (_badge[t] == null) return;
        _badge[t].Text = count > 99 ? "99+" : count.ToString();
        _badgeBox[t].Visibility = count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>Contatori delle voci e identità di questo dispositivo, in fondo alla barra laterale.</summary>
    private void UpdateSideStatus()
    {
        try { UpdateSide(); } catch (Exception e) { Crash.Log(e, "Contatori"); }
    }

    private void UpdateSide()
    {
        SideDevice.Text = _r.S.Name.Length > 0 ? _r.S.Name : Environment.MachineName;
        SideVersion.Text = "Questo PC · " + _r.Update.Current.ToString("'1.0.'0");
        // niente contatori sulle voci (come su Android): restano solo le icone
    }

    private void Refresh()
    {
        UpdateSideStatus();
        switch (_tab)
        {
            case 0: RenderHome(); break;
            case 1: RenderShare(); break;
            case 2: RenderFinance(); break;
            case 3: if (!Typing()) RenderAgenda(); break;
            case 4: if (!Typing()) RenderBody(); break;
            case 5: RenderAudio(); break;
            case 6: RenderSettings(); break;
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

    private static Border Card(params UIElement[] children)
    {
        var col = new StackPanel { Spacing = 12 };
        foreach (var c in children) col.Children.Add(c);
        return new Border { Style = St("CardStyle"), Child = col };
    }

    private static Border Divider() => new() { Style = St("DividerStyle") };

    private static Border Badge(string text, string tone = "Neutral") => new()
    {
        Style = St("Badge" + tone),
        Child = new TextBlock { Text = text, Style = St(tone == "Neutral" ? "BadgeText" : "BadgeText" + tone) },
    };

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

    private static Grid Row(string title, string? note, FrameworkElement? trailing = null)
    {
        var g = new Grid { ColumnSpacing = 12, MinHeight = 40 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var left = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        left.Children.Add(Text(title));
        if (note != null) left.Children.Add(Text(note, "Footnote"));
        g.Children.Add(left);
        if (trailing != null)
        {
            trailing.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(trailing, 1);
            g.Children.Add(trailing);
        }
        return g;
    }

    private static Grid Toggle(string title, string note, bool on, Action<bool> changed)
    {
        var ts = new ToggleSwitch { IsOn = on, OnContent = "", OffContent = "", MinWidth = 0, Margin = new Thickness(0, 0, -8, 0) };
        ts.Toggled += (_, _) => changed(ts.IsOn);
        return Row(title, note, ts);
    }

    /// <summary>Etichetta sempre sopra e sempre visibile; il segnaposto mostra solo un esempio.</summary>
    private static StackPanel Field(string label, Control input)
    {
        var p = new StackPanel { Spacing = 6 };
        p.Children.Add(Text(label, "Footnote"));
        p.Children.Add(input);
        return p;
    }

    private static string FormatSize(long b) =>
        b < 1024 * 1024 ? $"{Math.Max(1, b / 1024)} KB" : $"{b / 1024.0 / 1024.0:0.0} MB";
}
