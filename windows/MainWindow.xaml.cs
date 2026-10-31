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
    private static readonly string[] Tabs = { "Dispositivi", "Appunti", "Archivio", "Audio", "Impostazioni" };

    private readonly Router _r;
    private readonly ScrollViewer[] _pages;
    private readonly StackPanel[] _hosts;
    private int _tab;
    private bool _exiting;
    private string _filter = "";

    public MainWindow(Router r)
    {
        InitializeComponent();
        _r = r;
        _pages = new[] { Page0, Page1, Page2, Page3, Page4 };
        _hosts = new[] { Host0, Host1, Host2, Host3, Host4 };

        Title = "Ponte";
        AppWindow.Resize(new SizeInt32(1100, 760));
        try { AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "ponte.ico")); } catch (Exception) { }
        // Chiudere la finestra non chiude Ponte: resta nell'area di notifica e continua a sincronizzare.
        AppWindow.Closing += (_, e) =>
        {
            if (_exiting) return;
            e.Cancel = true;
            AppWindow.Hide();
        };
        Root.SizeChanged += (_, _) => ApplyLayout();
        _r.Changed += () => DispatcherQueue.TryEnqueue(Refresh);

        Select(_r.S.Token.Length == 0 ? 4 : 0);
    }

    public void InitTray()
    {
        Tray.LeftClickCommand = new Cmd(() => DispatcherQueue.TryEnqueue(ShowWindow));
        Tray.ForceCreate();
    }

    private void ShowWindow()
    {
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
    private void ApplyLayout()
    {
        var wide = Root.ActualWidth >= 1024;
        SideCol.Width = new GridLength(wide ? 240 : 0);
        Side.Visibility = wide ? Visibility.Visible : Visibility.Collapsed;
        Bottom.Visibility = wide ? Visibility.Collapsed : Visibility.Visible;
    }

    private void Select(int i)
    {
        _tab = i;
        for (var p = 0; p < _pages.Length; p++) _pages[p].Visibility = p == i ? Visibility.Visible : Visibility.Collapsed;

        SideTabs.Children.Clear();
        BottomTabs.Children.Clear();
        BottomTabs.ColumnDefinitions.Clear();
        for (var t = 0; t < Tabs.Length; t++)
        {
            var idx = t;
            var style = t == i ? "TabButtonSelected" : "TabButton";
            var sb = Btn(Tabs[t], () => Select(idx), style);
            sb.HorizontalContentAlignment = HorizontalAlignment.Left;
            SideTabs.Children.Add(sb);
            var b = Btn(Tabs[t], () => Select(idx), style);
            BottomTabs.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            Grid.SetColumn(b, t);
            BottomTabs.Children.Add(b);
        }
        ApplyLayout();
        if (i == 4) RenderSettings(); else Refresh();
    }

    private void Refresh()
    {
        switch (_tab)
        {
            case 0: RenderDevices(); break;
            case 1: RenderClips(); break;
            case 2: RenderArchive(); break;
            case 3: RenderAudio(); break;
        }
    }

    private StackPanel Reset(string title)
    {
        var h = _hosts[_tab];
        h.Children.Clear();
        var t = Text(title, "Title1");
        t.Margin = new Thickness(0, 16, 0, 8);
        h.Children.Add(t);
        return h;
    }

    // ---------------------------------------------------------------- Dispositivi

    private void RenderDevices()
    {
        var h = Reset("Dispositivi");
        var hub = _r.HubState;
        var tone = hub switch { "Connesso" => "Positive", "Non configurato" => "Neutral", "Connessione in corso" => "Caution", _ => "Critical" };
        var devices = _r.Devices;
        var near = devices.Count(d => d.Via == "lan");

        h.Children.Add(Card(
            Text("Collegamenti", "Title3"),
            Row("Server", "Per chi è fuori casa e per l'archivio", Badge(hub, tone)),
            Divider(),
            Row("Diretto", "Rete locale o rete privata, senza server",
                !_r.S.LanEnabled ? Badge("Disattivata") : near == 0 ? Badge("Nessun dispositivo") : Badge($"{near} collegati", "Positive"))));

        var others = new List<UIElement> { Text("Altri dispositivi", "Title3") };
        if (devices.Count == 0)
            others.Add(Text("Nessun altro dispositivo. Apri Ponte su un altro dispositivo, con lo stesso server e lo stesso token.", "Footnote"));
        for (var i = 0; i < devices.Count; i++)
        {
            var d = devices[i];
            if (i > 0) others.Add(Divider());
            var what = new List<string> { d.Platform == "windows" ? "Windows" : "Android" };
            if (d.Caps.Contains("audio-sink")) what.Add("riceve audio");
            if (d.Caps.Contains("notif-src")) what.Add("manda notifiche");
            others.Add(Row(d.Name, string.Join(" · ", what), Badge(d.Via == "lan" ? "Diretto" : "Server", d.Via == "lan" ? "Accent" : "Neutral")));
        }
        h.Children.Add(Card(others.ToArray()));
    }

    // ---------------------------------------------------------------- Appunti

    private void RenderClips()
    {
        var h = Reset("Appunti");
        h.Children.Add(Card(
            Text("Su Windows l'invio è automatico: ogni volta che copi, il testo arriva sugli altri dispositivi.", "Footnote"),
            Btn("Invia appunti ora", async () => await _r.Clip.SendCurrentAsync(), "PrimaryButton")));

        var history = _r.Clip.History;
        if (history.Count == 0)
        {
            h.Children.Add(Card(Text("Nessun appunto. Copia qualcosa su un dispositivo collegato e comparirà qui.", "Footnote")));
            return;
        }
        foreach (var c in history.Take(100))
        {
            var item = c;
            var who = item.Mine ? "Inviato da qui" : _r.DeviceName(item.From);
            var time = DateTimeOffset.FromUnixTimeMilliseconds(item.Ts).ToLocalTime().ToString("t", CultureInfo.CurrentCulture);
            h.Children.Add(Card(
                Row(item.Text.Length > 300 ? item.Text[..300] : item.Text, who + " · " + time),
                Btn("Copia", () => _r.Clip.SetLocal(item.Text))));
        }
    }

    // ---------------------------------------------------------------- Archivio

    private void RenderArchive()
    {
        var h = Reset("Archivio");
        h.Children.Add(Btn("Invia file", async () => await PickFilesAsync(), "PrimaryButton"));

        var chips = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var (label, key) in new[] { ("Tutto", ""), ("Screenshot", "screenshot"), ("File", "file") })
        {
            var k = key;
            chips.Children.Add(Btn(label, () => { _filter = k; Refresh(); }, _filter == k ? "ChipButtonSelected" : "ChipButton"));
        }
        h.Children.Add(chips);

        var items = _r.Files.Items.Where(f => _filter.Length == 0 || f.Kind == _filter).ToList();
        if (items.Count == 0)
        {
            h.Children.Add(Card(Text("Nessun elemento. Fai uno screenshot su un dispositivo collegato e comparirà qui.", "Footnote")));
            return;
        }
        var tiles = items.Select(Tile).Cast<UIElement>().ToList();
        h.Children.Add(new ItemsRepeater
        {
            ItemsSource = tiles,
            Layout = new UniformGridLayout
            {
                MinItemWidth = 200, MinItemHeight = 196, MinColumnSpacing = 12, MinRowSpacing = 12,
                ItemsStretch = UniformGridLayoutItemsStretch.Fill,
            },
        });
    }

    private Button Tile(FileEntry e)
    {
        var thumb = new Border { Style = St("ThumbStyle") };
        if (e.Mime.StartsWith("image/"))
        {
            try { thumb.Child = new Image { Stretch = Stretch.UniformToFill, Source = new BitmapImage { DecodePixelWidth = 480, UriSource = new Uri(e.Path) } }; }
            catch (Exception) { }
        }
        thumb.Child ??= new TextBlock
        {
            Text = Path.GetExtension(e.Name).TrimStart('.').ToLowerInvariant(), Style = St("Figure"),
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        };

        var when = DateTimeOffset.FromUnixTimeMilliseconds(e.Ts).ToLocalTime().ToString("g", CultureInfo.CurrentCulture);
        var name = Text(e.Name, "Footnote");
        name.TextWrapping = TextWrapping.NoWrap;
        name.TextTrimming = TextTrimming.CharacterEllipsis;
        var info = Text(FormatSize(e.Size) + " · " + when, "FootnoteTertiary");
        info.TextWrapping = TextWrapping.NoWrap;
        info.TextTrimming = TextTrimming.CharacterEllipsis;

        var body = new StackPanel { Spacing = 8 };
        body.Children.Add(thumb);
        body.Children.Add(new StackPanel { Margin = new Thickness(8, 0, 8, 8), Children = { name, info } });

        var tile = new Button { Style = St("TileStyle"), Content = body };
        tile.Click += (_, _) => _r.Files.Open(e);
        var menu = new MenuFlyout();
        var del = new MenuFlyoutItem { Text = "Elimina" };
        del.Click += async (_, _) => await ConfirmDeleteAsync(e);
        menu.Items.Add(del);
        tile.ContextFlyout = menu;
        return tile;
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

    // ---------------------------------------------------------------- Audio

    private void RenderAudio()
    {
        var h = Reset("Audio");
        var hps = _r.Hp.List;
        string Who(string? id) => id == null ? "nessuno" : id == _r.DeviceId ? "questo PC" : _r.DeviceName(id);
        var locked = _r.Hp.LockedBy == _r.DeviceId;
        var pref = _r.S.PreferredHp;
        var choices = new List<string> { "" };
        choices.AddRange(hps.Select(x => x.Address));
        var auto = new List<UIElement>
        {
            Text("Passaggio automatico", "Title3"),
            Text("Le cuffie vanno dove fai partire l'audio, se è il dispositivo che stai usando. Blocca per tenerle qui mentre usi altro.", "Footnote"),
            Row("Cuffie su", Who(_r.Hp.Holder)),
            Row("In uso", Who(_r.Hp.Focus)),
            Row("Blocco", _r.Hp.LockedBy == null ? "Nessuno" : "Su " + Who(_r.Hp.LockedBy),
                Btn(locked ? "Sblocca" : "Blocca qui", () => _r.Hp.ToggleLock(), locked ? "SecondaryButton" : "PrimaryButton")),
            Divider(),
            Row("Cuffie preferite qui", hps.FirstOrDefault(x => x.Address == pref)?.Name ?? "Nessuna: passaggio spento", Btn("Cambia", () =>
            {
                _r.S.PreferredHp = choices[(choices.IndexOf(_r.S.PreferredHp) + 1) % choices.Count];
                _r.S.Save();
                RenderAudio();
            })),
            Row("Se non ci sono", _r.S.FallbackMute ? "Muto" : "Casse", Btn("Cambia", () =>
            {
                _r.S.FallbackMute = !_r.S.FallbackMute;
                _r.S.Save();
                RenderAudio();
            })),
        };
        h.Children.Add(Card(auto.ToArray()));
        var marked = _r.S.Headphones;
        var hp = new List<UIElement>
        {
            Text("Cuffie", "Title3"),
            Text("Segna le cuffie associate. Ponte tiene collegato un solo dispositivo alla volta: quando l'audio parte qui gli altri le lasciano, e dopo un po' di inattività le lascia anche il PC, così non occupi i due slot.", "Footnote"),
        };
        if (hps.Count == 0) hp.Add(Text("Nessuna cuffia associata a questo PC.", "FootnoteTertiary"));
        foreach (var x in hps)
        {
            var addr = x.Address;
            hp.Add(Toggle(x.Name, x.Connected ? "Collegate qui" : "Non collegate qui", marked.Contains(addr), on =>
            {
                if (on) _r.S.Headphones.Add(addr); else _r.S.Headphones.Remove(addr);
                _r.S.Save();
                RenderAudio();
            }));
            if (marked.Contains(addr))
                hp.Add(x.Connected
                    ? Btn("Libera le cuffie", () => _r.Hp.Release(addr))
                    : Btn("Collega qui", () => _r.Hp.Connect(addr), "PrimaryButton"));
        }
        var opts = new[] { 0, 5, 10, 20, 30 };
        hp.Add(Row("Scollega dopo", _r.S.IdleMinutes == 0 ? "Mai" : $"{_r.S.IdleMinutes} minuti senza audio", Btn("Cambia", () =>
        {
            _r.S.IdleMinutes = opts[(Array.IndexOf(opts, _r.S.IdleMinutes) + 1) % opts.Length];
            _r.S.Save();
            RenderAudio();
        })));
        h.Children.Add(Card(hp.ToArray()));

        var sinks = _r.Devices.Where(d => d.Caps.Contains("audio-sink")).ToList();
        var playingTo = _r.Audio.SinkId;

        h.Children.Add(Card(
            Text("Ascolta altrove", "Title3"),
            Text("Scegli quale dispositivo deve suonare l'audio di questo PC. Le cuffie restano collegate a quel dispositivo: cambi solo da dove arriva il suono.", "Footnote"),
            Text("Il ritardo è di circa 100 ms. Va bene per video e musica, meno per i giochi.", "FootnoteTertiary")));

        if (sinks.Count == 0 && playingTo == null)
        {
            h.Children.Add(Card(Text("Nessun dispositivo in grado di riceverlo. Apri Ponte su un telefono o un tablet Android.", "Footnote")));
            return;
        }
        foreach (var d in sinks)
        {
            var dev = d;
            var active = playingTo == dev.Id;
            h.Children.Add(Card(Row(dev.Name, active ? "Sta suonando l'audio di questo PC" : "Disponibile",
                active ? Btn("Ferma", () => _r.Audio.Route(null)) : Btn("Ascolta lì", () => _r.Audio.Route(dev.Id)))));
        }
    }

    // ---------------------------------------------------------------- Impostazioni

    private void RenderSettings()
    {
        var h = Reset("Impostazioni");
        var s = _r.S;

        var name = new TextBox { Text = s.Name, PlaceholderText = "PC della scrivania" };
        var hub = new TextBox { Text = s.HubUrl, PlaceholderText = "ponte.esempio.it", FontFamily = (FontFamily)Application.Current.Resources["FontMono"] };
        var token = new PasswordBox { Password = s.Token, PlaceholderText = "Lo stesso impostato sul server" };
        h.Children.Add(Card(
            Text("Questo dispositivo", "Title3"),
            Field("Nome del dispositivo", name),
            Field("Indirizzo del server", hub),
            Field("Token", token),
            Btn("Salva", () =>
            {
                s.Name = name.Text.Trim().Length > 0 ? name.Text.Trim() : Environment.MachineName;
                s.HubUrl = hub.Text.Trim();
                s.Token = token.Password.Trim();
                s.Save();
                _r.Reconfigure();
            }, "PrimaryButton")));

        h.Children.Add(Card(
            Text("Funzioni", "Title3"),
            Toggle("Collegamento diretto", "Rete locale e rete privata Headscale", s.LanEnabled, on => { s.LanEnabled = on; Apply(); }),
            Divider(),
            Toggle("Screenshot automatici", "Ogni nuovo screenshot va nell'archivio di tutti", s.Screenshots, on => { s.Screenshots = on; Apply(); }),
            Row("Cartella degli screenshot", s.EffectiveScreenshotFolder, Btn("Cambia", async () => await PickScreenshotFolderAsync())),
            Text("Nello Strumento di cattura attiva il salvataggio automatico degli screenshot, altrimenti restano solo negli appunti.", "FootnoteTertiary"),
            Divider(),
            Toggle("Notifiche del telefono", "Le notifiche inoltrate dal telefono compaiono come notifiche di Windows", s.ShowPhoneNotifications, on => { s.ShowPhoneNotifications = on; Apply(); }),
            Divider(),
            Toggle("Avvia con Windows", "Ponte parte in background all'accesso", GetAutostart(), SetAutostart)));
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
        var col = new StackPanel { Spacing = 16 };
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
        var g = new Grid { ColumnSpacing = 12, MinHeight = 44 };
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
        var ts = new ToggleSwitch { IsOn = on, OnContent = "", OffContent = "", MinWidth = 0 };
        ts.Toggled += (_, _) => changed(ts.IsOn);
        return Row(title, note, ts);
    }

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
