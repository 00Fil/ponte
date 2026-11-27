using System.Numerics;
using Microsoft.UI.Composition;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Microsoft.Web.WebView2.Core;
using Windows.Graphics;

namespace Ponte;

/// <summary>
/// Installazione e disinstallazione con lo stile dell'app: finestra senza cornice di sistema, tre pallini,
/// fibre luminose sullo sfondo, testo e controlli di Canone. Niente procedura guidata a pagine: una scelta e via.
/// </summary>
public sealed class SetupWindow : Window
{
    private readonly bool _uninstall;
    private readonly bool _fromInstaller;
    private readonly Action? _runPortable;
    private readonly Grid _root = new();
    private readonly StackPanel _panel = new() { Spacing = 16, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(44, 56, 44, 40), MaxWidth = 380, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly WebView2 _web = new();
    private readonly Border _fade = new() { IsHitTestVisible = false };

    public SetupWindow(bool uninstall, bool fromInstaller, Action? runPortable)
    {
        _uninstall = uninstall;
        _fromInstaller = fromInstaller;
        _runPortable = runPortable;
        Title = uninstall ? "Disinstalla Relay" : "Installa Relay";

        _root.Background = Res("BgCanvas");
        _root.Children.Add(_web);
        _root.Children.Add(_fade);
        _root.Children.Add(_panel);
        _root.Children.Add(BuildTitleBar());
        Content = _root;
        _root.ActualThemeChanged += (_, _) => { UpdateFade(); SetWebTheme(); };
        _root.Loaded += (_, _) => { UpdateFade(); _ = InitWebAsync(); };

        try { AppWindow.SetIcon(System.IO.Path.Combine(AppContext.BaseDirectory, "Assets", "relay.ico")); } catch (Exception) { }
        ExtendsContentIntoTitleBar = true;
        if (AppWindow.Presenter is OverlappedPresenter p)
        {
            p.SetBorderAndTitleBar(true, false);
            p.IsResizable = false;
            p.IsMaximizable = false;
        }
        var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        var scale = GetDpiScale();
        int w = (int)(820 * scale), h = (int)(540 * scale);
        AppWindow.MoveAndResize(new RectInt32(area.X + (area.Width - w) / 2, area.Y + (area.Height - h) / 2, w, h));

        if (uninstall) ShowUninstall(); else ShowInstall();
    }

    // ---------------------------------------------------------------- installazione

    private void ShowInstall()
    {
        var update = Installer.IsInstalled;
        var autostart = MainWindow.GlassSwitch(true, _ => { }, "Avvia con Windows");
        var desktop = MainWindow.GlassSwitch(false, _ => { }, "Icona sul desktop");
        var install = Btn(update ? "Aggiorna" : "Installa", "PrimaryButton");
        install.Click += async (_, _) => await RunInstallAsync(autostart.IsChecked == true, desktop.IsChecked == true);

        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        actions.Children.Add(install);
        if (!_fromInstaller && _runPortable != null)
        {
            var portable = Btn("Apri senza installare", "SecondaryButton");
            portable.Click += (_, _) => { _runPortable(); Close(); };
            actions.Children.Add(portable);
        }

        Show(
            Brand(),
            Text(update ? "Aggiorna Relay" : "Installa Relay", "Title1"),
            Card(
                Row("Avvia con Windows", autostart),
                Divider(),
                Row("Icona sul desktop", desktop)),
            actions,
            Text($"Versione {Installer.Version}", "FootnoteTertiary"));
    }

    private async Task RunInstallAsync(bool autostart, bool desktop)
    {
        var bar = new ProgressBar { Minimum = 0, Maximum = 1, Height = 4, CornerRadius = new CornerRadius(2), Foreground = Res("AccentDefault"), Background = Res("BgSunken") };
        var status = Text("Preparo", "Footnote");
        Show(Brand(), Text("Installazione", "Title1"), bar, status);
        var dq = DispatcherQueue;
        try
        {
            await Task.Run(() => Installer.Install(autostart, desktop, (v, s) =>
                dq.TryEnqueue(() => { bar.Value = v; status.Text = s; })));
        }
        catch (Exception e)
        {
            ShowError("Installazione non riuscita", e.Message);
            return;
        }
        var check = Check();
        Show(Brand(), check, Text("Relay è installato", "Title1"));
        await Task.Delay(1100);
        Installer.Launch(Installer.InstalledExe);
        Environment.Exit(0);
    }

    // ---------------------------------------------------------------- disinstallazione

    private void ShowUninstall()
    {
        var data = new CheckBox { Content = "Elimina anche i dati di questo PC", IsChecked = false };
        var go = Btn("Disinstalla", "DestructiveButton");
        var cancel = Btn("Annulla", "SecondaryButton");
        cancel.Click += (_, _) => Environment.Exit(0);
        go.Click += async (_, _) =>
        {
            var removeData = data.IsChecked == true;
            Show(Brand(), Text("Disinstallo", "Title1"), new ProgressBar { IsIndeterminate = true, Height = 4, Foreground = Res("AccentDefault"), Background = Res("BgSunken") });
            try { await Task.Run(() => Installer.Uninstall(removeData)); }
            catch (Exception e) { ShowError("Disinstallazione non riuscita", e.Message); return; }
            Show(Brand(), Check(), Text("Relay è stato rimosso", "Title1"), Text(removeData ? "Dati eliminati" : "Dati conservati", "Footnote"));
            await Task.Delay(1400);
            Environment.Exit(0);
        };
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        actions.Children.Add(go);
        actions.Children.Add(cancel);
        Show(
            Brand(),
            Text("Disinstallare Relay?", "Title1"),
            data,
            actions);
    }

    private void ShowError(string title, string detail)
    {
        var close = Btn("Chiudi", "SecondaryButton");
        close.Click += (_, _) => Environment.Exit(1);
        Show(Brand(), Text(title, "Title1"), Text(detail, "Footnote"), close);
    }

    // ---------------------------------------------------------------- composizione

    /// <summary>Sostituisce il contenuto: gli elementi entrano insieme, con un piccolo sfalsamento, dalla stessa direzione.</summary>
    private void Show(params UIElement[] items)
    {
        _panel.Children.Clear();
        for (var i = 0; i < items.Length; i++)
        {
            _panel.Children.Add(items[i]);
            Enter(items[i], 14, 0, 420, i * 40);
        }
    }

    private static void Enter(UIElement el, float dx, float dy, int ms, int delay)
    {
        var v = ElementCompositionPreview.GetElementVisual(el);
        var c = v.Compositor;
        ElementCompositionPreview.SetIsTranslationEnabled(el, true);
        var ease = c.CreateCubicBezierEasingFunction(new Vector2(0.2f, 0f), new Vector2(0f, 1f));
        var t = c.CreateVector3KeyFrameAnimation();
        t.InsertKeyFrame(0f, new Vector3(dx, dy, 0f));
        t.InsertKeyFrame(1f, Vector3.Zero, ease);
        t.Duration = TimeSpan.FromMilliseconds(ms);
        var o = c.CreateScalarKeyFrameAnimation();
        o.InsertKeyFrame(0f, 0f);
        o.InsertKeyFrame(1f, 1f, c.CreateCubicBezierEasingFunction(new Vector2(0.3f, 0f), new Vector2(0.4f, 1f)));
        o.Duration = TimeSpan.FromMilliseconds(ms * 0.7);
        if (delay > 0)
        {
            t.DelayTime = o.DelayTime = TimeSpan.FromMilliseconds(delay);
            t.DelayBehavior = o.DelayBehavior = AnimationDelayBehavior.SetInitialValueBeforeDelay;
        }
        v.StartAnimation("Translation", t);
        v.StartAnimation("Opacity", o);
    }

    private UIElement BuildTitleBar()
    {
        var bar = new Grid { Height = 44, VerticalAlignment = VerticalAlignment.Top };
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(86) });
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var lights = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 2, Margin = new Thickness(14, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
        var glyphs = new List<TextBlock>();
        void Light(byte r, byte g, byte b, string glyph, bool enabled, Action click)
        {
            var cell = new Grid { Width = 18, Height = 24, Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
            var color = enabled ? Windows.UI.Color.FromArgb(255, r, g, b) : Windows.UI.Color.FromArgb(255, 0x9A, 0x9A, 0x95);
            cell.Children.Add(new Ellipse { Width = 12, Height = 12, Fill = new SolidColorBrush(color), Opacity = enabled ? 1 : 0.5 });
            var t = new TextBlock { Text = glyph, FontSize = 11, FontWeight = Microsoft.UI.Text.FontWeights.Bold, Opacity = 0, IsHitTestVisible = false,
                Foreground = new SolidColorBrush(Windows.UI.Color.FromArgb(160, 0, 0, 0)), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, -2, 0, 0) };
            if (enabled) glyphs.Add(t);
            cell.Children.Add(t);
            if (enabled) cell.Tapped += (_, _) => click();
            lights.Children.Add(cell);
        }
        Light(0xFF, 0x5F, 0x57, "×", true, () => Environment.Exit(0));
        Light(0xFE, 0xBC, 0x2E, "–", true, () => { if (AppWindow.Presenter is OverlappedPresenter p) p.Minimize(); });
        Light(0x28, 0xC8, 0x40, "+", false, () => { });
        lights.PointerEntered += (_, _) => { foreach (var g in glyphs) g.Opacity = 1; };
        lights.PointerExited += (_, _) => { foreach (var g in glyphs) g.Opacity = 0; };
        bar.Children.Add(lights);
        var drag = new Border { Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
        Grid.SetColumn(drag, 1);
        bar.Children.Add(drag);
        SetTitleBar(drag);
        return bar;
    }

    private static UIElement Brand()
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        row.Children.Add(MainWindow.RelayMark(28));
        row.Children.Add(new TextBlock { Text = "Relay", Style = St("SectionTitle"), VerticalAlignment = VerticalAlignment.Center });
        return row;
    }

    private static UIElement Check() => new Border
    {
        Width = 44, Height = 44, CornerRadius = new CornerRadius(22), HorizontalAlignment = HorizontalAlignment.Left,
        Background = Res("AccentDefault"), Child = Icons.Of(Ico.Check, new SolidColorBrush(Microsoft.UI.Colors.White), 22),
    };

    private static Style St(string key) => (Style)Application.Current.Resources[key];
    private static Brush Res(string key) => Canone.Get(key);

    private static TextBlock Text(string text, string style) => new() { Text = text, Style = St(style), TextWrapping = TextWrapping.Wrap };

    private static Button Btn(string label, string style) => new() { Content = label, Style = St(style) };

    private static Border Card(params UIElement[] children)
    {
        var col = new StackPanel { Spacing = 8 };
        foreach (var c in children) col.Children.Add(c);
        return new Border { Style = St("CardStyle"), Child = col };
    }

    private static Border Divider() => new() { Style = St("DividerStyle") };

    private static Grid Row(string title, FrameworkElement trailing)
    {
        var g = new Grid { ColumnSpacing = 12, MinHeight = 36 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var left = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        left.Children.Add(Text(title, "BodyText"));
        g.Children.Add(left);
        trailing.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(trailing, 1);
        g.Children.Add(trailing);
        return g;
    }

    // ---------------------------------------------------------------- sfondo

    private string BgHex()
    {
        var c = (_root.Background as SolidColorBrush)?.Color ?? Microsoft.UI.Colors.Black;
        return $"{c.R:X2}{c.G:X2}{c.B:X2}";
    }

    private bool Dark => _root.ActualTheme == ElementTheme.Dark;

    private async Task InitWebAsync()
    {
        try
        {
            await _web.EnsureCoreWebView2Async();
            var core = _web.CoreWebView2;
            core.SetVirtualHostNameToFolderMapping("ponte.app", System.IO.Path.Combine(AppContext.BaseDirectory, "Assets", "Web"),
                CoreWebView2HostResourceAccessKind.Allow);
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.IsZoomControlEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            _web.Source = new Uri($"https://ponte.app/gradient.html?shape=hero&light={(Dark ? 0 : 1)}&bg={BgHex()}");
        }
        catch (Exception) { _web.Visibility = Visibility.Collapsed; }
    }

    private void SetWebTheme()
    {
        if (_web.CoreWebView2 != null)
            _ = _web.CoreWebView2.ExecuteScriptAsync($"window.ponteSet && ponteSet({{ lightMode: {(Dark ? "false" : "true")}, bg: '{BgHex()}' }})");
    }

    /// <summary>A sinistra il fondo pieno sotto il testo, a destra le fibre libere.</summary>
    private void UpdateFade()
    {
        var bg = (_root.Background as SolidColorBrush)?.Color ?? Microsoft.UI.Colors.Black;
        _fade.Background = new LinearGradientBrush
        {
            StartPoint = new Windows.Foundation.Point(0, 0),
            EndPoint = new Windows.Foundation.Point(1, 0),
            GradientStops =
            {
                new GradientStop { Color = bg, Offset = 0.38 },
                new GradientStop { Color = Windows.UI.Color.FromArgb(0, bg.R, bg.G, bg.B), Offset = 0.78 },
            },
        };
    }

    private double GetDpiScale()
    {
        try { return GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(this)) / 96.0; }
        catch (Exception) { return 1; }
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hwnd);
}
