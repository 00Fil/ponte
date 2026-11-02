using System.Globalization;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Shapes;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Ponte;

/// <summary>Condividi, Finanze, Audio e Impostazioni: stessa struttura e stesse funzioni di Android.</summary>
public sealed partial class MainWindow
{
    private int _shareTab;
    private string? _folder;
    private readonly List<Action> _ticks = new();
    private DispatcherQueueTimer? _tickTimer;

    private void EnsureTicks()
    {
        if (_tickTimer != null) return;
        _tickTimer = DispatcherQueue.CreateTimer();
        _tickTimer.Interval = TimeSpan.FromMilliseconds(500);
        _tickTimer.Tick += (_, _) => { foreach (var a in _ticks.ToList()) try { a(); } catch (Exception) { } };
        _tickTimer.Start();
    }

    private Grid BackRow(string parent, string title, Action back)
    {
        var g = new Grid { ColumnSpacing = 12 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var b = BubbleButton("", 40, back, tip: "Torna a " + parent);
        g.Children.Add(b);
        var t = T(title, 22, "LabelPrimary", true);
        t.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(t, 1);
        g.Children.Add(t);
        return g;
    }

    // ================================================================ Condividi

    private void RenderShare()
    {
        var h = Reset("Condividi");
        h.Children.Add(DevicesStrip());

        // tre azioni a bolla
        var acts = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 28, HorizontalAlignment = HorizontalAlignment.Center };
        acts.Children.Add(ActionBubble("", "Invia appunti", true, async () => await _r.Clip.SendCurrentAsync()));
        acts.Children.Add(ActionBubble("", "Invia file", false, async () => await PickFilesAsync()));
        h.Children.Add(acts);

        h.Children.Add(DriveUsage());
        h.Children.Add(Segmented("share", new[] { "Appunti", "Immagini", "File" }, _shareTab, i => { _shareTab = i; _folder = null; RenderShareAnimated(); }));
        var body = new StackPanel { Spacing = 10 };
        if (_shareTab == 0) BuildClips(body); else BuildFiles(body);
        h.Children.Add(body);
        _shareBody = body;
    }

    private StackPanel? _shareBody;

    private const long DriveCap = 50L * 1024 * 1024 * 1024;

    private static string Size(long n) => n switch { >= 1L << 30 => (n / (double)(1L << 30)).ToString("0.0", It) + " GB", >= 1L << 20 => (n / (double)(1L << 20)).ToString("0.#", It) + " MB", >= 1L << 10 => (n / 1024.0).ToString("0", It) + " KB", _ => n + " B" };

    /// <summary>Lo spazio condiviso: una barra con immagini, file e appunti rispetto ai 50 GB.</summary>
    private Border DriveUsage()
    {
        var items = _r.Files.Items;
        var img = items.Where(f => f.Mime.StartsWith("image/")).Sum(f => f.Size);
        var oth = items.Sum(f => f.Size) - img;
        var clip = _r.Clip.History.Sum(c => (long)c.Text.Length);
        var used = img + oth + clip;
        var col = new StackPanel { Spacing = 12 };
        var head = new Grid();
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var big = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        big.Children.Add(T(Size(used), 22, "LabelPrimary", true));
        var of = T("di 50 GB", 12, "LabelTertiary"); of.VerticalAlignment = VerticalAlignment.Bottom; of.Margin = new Thickness(0, 0, 0, 3);
        big.Children.Add(of);
        head.Children.Add(big);
        var cnt = T(items.Count + " elementi", 12, "LabelTertiary"); Grid.SetColumn(cnt, 1); cnt.VerticalAlignment = VerticalAlignment.Bottom;
        head.Children.Add(cnt);
        col.Children.Add(head);

        var parts = new (string Name, long V, Windows.UI.Color Col)[] { ("Immagini", img, C("AccentDefault")), ("File", oth, Argb(255, 0xFF, 0x9F, 0x0A)), ("Appunti", clip, Argb(255, 0x34, 0xC7, 0x59)) };
        var bar = new Grid { Height = 10, CornerRadius = new CornerRadius(5), Background = B("BgSunken") };
        var barRow = new Grid { CornerRadius = new CornerRadius(5) };
        foreach (var p in parts)
        {
            if (p.V <= 0) continue;
            barRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max((double)p.V / DriveCap, 0.004), GridUnitType.Star) });
            var seg = new Border { Background = Sb(p.Col) };
            Grid.SetColumn(seg, barRow.ColumnDefinitions.Count - 1);
            barRow.Children.Add(seg);
        }
        barRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(1.0 - (double)used / DriveCap, 0.0001), GridUnitType.Star) });
        bar.Children.Add(barRow);
        col.Children.Add(bar);

        var leg = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16 };
        foreach (var p in parts)
        {
            var l = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
            l.Children.Add(new Ellipse { Width = 8, Height = 8, Fill = Sb(p.Col), VerticalAlignment = VerticalAlignment.Center });
            l.Children.Add(T(p.Name + " " + Size(p.V), 12, "LabelSecondary"));
            leg.Children.Add(l);
        }
        col.Children.Add(leg);
        return Widget(col);
    }

    /// <summary>Cambio scheda morbido: il contenuto vecchio sfuma, il nuovo entra sfumando e salendo appena.</summary>
    private async void RenderShareAnimated()
    {
        if (_shareBody != null) { FadeTo(_shareBody, 0, 140); await Task.Delay(150); }
        RenderShare();
        if (_shareBody != null) { _shareBody.Opacity = 0; Enter(_shareBody, 0, 10, 380); }
    }

    private StackPanel ActionBubble(string glyph, string label, bool accent, Func<Task> click)
    {
        var p = new StackPanel { Spacing = 6, HorizontalAlignment = HorizontalAlignment.Center };
        var b = BubbleButton(glyph, 54, () => _ = click(), accent);
        b.HorizontalAlignment = HorizontalAlignment.Center;
        p.Children.Add(b);
        var t = T(label, 12, "LabelSecondary");
        t.HorizontalAlignment = HorizontalAlignment.Center;
        p.Children.Add(t);
        return p;
    }

    private string? _devOpen;
    private Border? _devStripEl;
    private bool _devDismissHooked;

    /// <summary>Un clic fuori dalla scheda dei dispositivi chiude i dettagli aperti.</summary>
    private void HookDevDismiss()
    {
        if (_devDismissHooked) return;
        _devDismissHooked = true;
        Root.AddHandler(UIElement.PointerPressedEvent, new Microsoft.UI.Xaml.Input.PointerEventHandler((_, e) =>
        {
            if (_devOpen == null || _tab != 1) return;
            for (var o = e.OriginalSource as DependencyObject; o != null; o = Microsoft.UI.Xaml.Media.VisualTreeHelper.GetParent(o))
                if (ReferenceEquals(o, _devStripEl)) return;
            _devOpen = null;
            DispatcherQueue.TryEnqueue(RenderShare);
        }), true);
    }

    /// <summary>Scheda compatta dei dispositivi: stato di server e P2P, i dispositivi in fila come bolle; un tocco apre i dettagli.</summary>
    private Border DevicesStrip()
    {
        var devices = _r.Devices;
        var near = devices.Count(d => d.Via != "server");
        var col = new StackPanel { Spacing = 10 };
        var head = new Grid { ColumnSpacing = 8 };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.Children.Add(T("Dispositivi  " + (devices.Count + 1), 15, "LabelPrimary", true));
        var stat = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        stat.Children.Add(StatusPill(_r.HubState == "Connesso" ? "Server" : "Server off", _r.HubState == "Connesso"));
        stat.Children.Add(StatusPill(near > 0 ? $"P2P {near}" : "P2P", near > 0));
        Grid.SetColumn(stat, 2);
        head.Children.Add(stat);
        col.Children.Add(head);

        var strip = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var all = new List<Device> { new(_r.DeviceId, _r.S.Name, "windows", new HashSet<string>(), "self") };
        all.AddRange(devices);
        foreach (var d in all)
        {
            var dev = d;
            var p = new StackPanel { Width = 76, Spacing = 4, Background = Sb(Microsoft.UI.Colors.Transparent) };
            var bub = Bubble(d.Platform == "windows" ? "" : "", 54, _devOpen == d.Id ? B("AccentDefault") : B("LabelSecondary"));
            bub.HorizontalAlignment = HorizontalAlignment.Center;
            var gg = new Grid { HorizontalAlignment = HorizontalAlignment.Center, Children = { bub } };
            if (d.Id != _r.DeviceId)
                gg.Children.Add(new Ellipse
                {
                    Width = 10, Height = 10, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 3, 3),
                    Fill = d.Via == "lan" ? Sb(Argb(255, 0x34, 0xC7, 0x59)) : d.Via == "bt" ? B("AccentDefault") : B("LabelDisabled"),
                });
            p.Children.Add(gg);
            var n = T(d.Id == _r.DeviceId ? "Questo" : d.Name, 12, _devOpen == d.Id ? "LabelPrimary" : "LabelSecondary");
            n.HorizontalAlignment = HorizontalAlignment.Center;
            p.Children.Add(n);
            p.Tapped += (_, _) => { _devOpen = _devOpen == dev.Id ? null : dev.Id; RenderShare(); };
            Bounce(p);
            strip.Children.Add(p);
        }
        col.Children.Add(new ScrollViewer { HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, HorizontalScrollMode = ScrollMode.Auto, VerticalScrollMode = ScrollMode.Disabled, Content = strip });

        var open = all.FirstOrDefault(x => x.Id == _devOpen);
        if (open != null)
        {
            var info = new StackPanel { Spacing = 4 };
            info.Children.Add(Row("Collegamento", open.Id == _r.DeviceId ? "Questo dispositivo" : open.Via switch { "lan" => "Diretto, rete locale o privata", "bt" => "Bluetooth", _ => "Tramite il server" }));
            if (open.Id != _r.DeviceId)
            {
                var what = new List<string> { "appunti", "file" };
                if (open.Caps.Contains("audio-sink")) what.Add("riceve audio");
                if (open.Caps.Contains("audio-src")) what.Add("sorgente audio");
                if (open.Caps.Contains("notif-src")) what.Add("notifiche");
                info.Children.Add(Row("Funzioni", string.Join(" · ", what)));
            }
            var drawer = new Border { Background = B("BgSunken"), CornerRadius = new CornerRadius(14), Padding = new Thickness(14, 8, 14, 8), Child = info };
            col.Children.Add(drawer);
            Enter(drawer, 0, -6, 300);
        }
        var strip2 = Widget(col, null, new Thickness(14, 12, 14, 12));
        _devStripEl = strip2;
        HookDevDismiss();
        return strip2;
    }

    private Border StatusPill(string label, bool on)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        p.Children.Add(new Ellipse { Width = 8, Height = 8, VerticalAlignment = VerticalAlignment.Center, Fill = on ? Sb(Argb(255, 0x34, 0xC7, 0x59)) : B("LabelDisabled") });
        p.Children.Add(T(label, 12, "LabelSecondary"));
        return new Border { Background = B("BgSunken"), CornerRadius = new CornerRadius(14), Padding = new Thickness(10, 4, 10, 4), Child = p };
    }

    private void BuildClips(StackPanel body)
    {
        var history = _r.Clip.History;
        if (history.Count == 0) { body.Children.Add(T("Niente per ora. Copia su un altro dispositivo e arriva qui.", 13, "LabelTertiary")); return; }
        string last = "";
        var today = DateTime.Today;
        foreach (var c in history.Take(120))
        {
            var item = c;
            var when = DateTimeOffset.FromUnixTimeMilliseconds(c.Ts).LocalDateTime;
            var day = when >= today ? "Oggi" : when >= today.AddDays(-1) ? "Ieri" : when.ToString("d MMMM", CultureInfo.GetCultureInfo("it-IT"));
            if (day != last) { last = day; var dl = T(day, 12, "LabelTertiary", true); dl.Margin = new Thickness(4, 8, 0, 0); body.Children.Add(dl); }
            var txt = c.Text.Trim();
            var link = (txt.StartsWith("http://") || txt.StartsWith("https://")) && !txt.Contains(' ');
            var code = !link && txt.Length is >= 4 and <= 12 && !txt.Contains(' ') && txt.Count(char.IsDigit) >= 4;
            var kind = link ? "Link" : code ? "Codice" : c.Text.Count(ch => ch == '\n') >= 2 ? "Testo lungo" : "Testo";
            var tone = DeviceTone(c.Mine ? "me" : c.From);

            var row = new Grid { ColumnSpacing = 12, Margin = new Thickness(12) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var bub = new Border
            {
                Width = 40, Height = 40, CornerRadius = new CornerRadius(20), VerticalAlignment = VerticalAlignment.Top,
                Background = Sb(WithA(tone, 60)), BorderBrush = GlassEdge(), BorderThickness = new Thickness(1),
                Child = Icon(c.Mine ? "" : "", 16, B("LabelPrimary")),
            };
            row.Children.Add(bub);
            var right = new StackPanel { Spacing = 2 };
            var meta = new Grid();
            meta.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            meta.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var who = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
            who.Children.Add(T(c.Mine ? "Questo PC" : _r.DeviceName(c.From), 12, "LabelSecondary", true));
            var k = T("· " + kind, 12, link ? "AccentDefault" : "LabelTertiary");
            who.Children.Add(k);
            meta.Children.Add(who);
            var tm = T(when.ToString("t", CultureInfo.CurrentCulture), 12, "LabelTertiary");
            Grid.SetColumn(tm, 1);
            meta.Children.Add(tm);
            right.Children.Add(meta);
            var body1 = T(c.Text.Length > 400 ? c.Text[..400] + "…" : c.Text, 14, link ? "AccentDefault" : "LabelPrimary");
            body1.TextWrapping = TextWrapping.Wrap;
            body1.MaxLines = link ? 1 : 3;
            if (code) body1.FontFamily = (FontFamily)Application.Current.Resources["FontMono"];
            right.Children.Add(body1);
            Grid.SetColumn(right, 1);
            row.Children.Add(right);
            var card = CopyCard(row, double.NaN, () => { _r.Clip.SetLocal(item.Text); return true; }, () => { }, 16);
            body.Children.Add(card);
        }
    }

    private void BuildFiles(StackPanel body)
    {
        var kind = _shareTab == 1 ? "screenshot" : "file";
        // cartelle
        var chips = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var f in new List<string?> { null }.Concat(_r.S.Folders))
        {
            var ff = f;
            var chip = Chip(f ?? "Tutte", _folder == f, () => { _folder = ff; RenderShareAnimated(); });
            if (f != null)
            {
                var menu = new MenuFlyout();
                var del = new MenuFlyoutItem { Text = "Elimina la cartella" };
                del.Click += (_, _) =>
                {
                    _r.S.Folders.Remove(ff!);
                    foreach (var key in _r.S.FileFolders.Where(kv => kv.Value == ff).Select(kv => kv.Key).ToList()) _r.S.FileFolders.Remove(key);
                    _r.S.Save();
                    if (_folder == ff) _folder = null;
                    RenderShare();
                };
                menu.Items.Add(del);
                chip.ContextFlyout = menu;
            }
            chips.Children.Add(chip);
        }
        chips.Children.Add(Chip("+ Cartella", false, async () =>
        {
            var box = new TextBox { PlaceholderText = "Nome della cartella" };
            var dlg = new ContentDialog { XamlRoot = Root.XamlRoot, Title = "Nuova cartella", Content = box, PrimaryButtonText = "Crea", CloseButtonText = "Annulla", DefaultButton = ContentDialogButton.Primary };
            if (await dlg.ShowAsync() == ContentDialogResult.Primary && box.Text.Trim().Length > 0 && !_r.S.Folders.Contains(box.Text.Trim()))
            {
                _r.S.Folders.Add(box.Text.Trim());
                _r.S.Save();
                RenderShare();
            }
        }));
        body.Children.Add(new ScrollViewer { HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, HorizontalScrollMode = ScrollMode.Auto, VerticalScrollMode = ScrollMode.Disabled, Content = chips });

        var items = _r.Files.Items.Where(f => f.Kind == kind && (_folder == null || (_r.S.FileFolders.TryGetValue(f.Fid, out var ff) && ff == _folder))).ToList();
        if (items.Count == 0)
        {
            body.Children.Add(T(_folder != null ? "Cartella vuota. Clic destro su un elemento per spostarlo qui." : _shareTab == 1 ? "Nessuna immagine. Fai uno screenshot su un dispositivo collegato e arriva qui." : "Nessun file. Mandane uno da qualsiasi dispositivo.", 13, "LabelTertiary"));
            return;
        }
        body.Children.Add(new ItemsRepeater
        {
            ItemsSource = items.Select(Tile).Cast<UIElement>().ToList(),
            Layout = new UniformGridLayout { MinItemWidth = 184, MinItemHeight = 188, MinColumnSpacing = 12, MinRowSpacing = 12, ItemsStretch = UniformGridLayoutItemsStretch.Fill },
        });
    }

    private Button Tile(FileEntry e)
    {
        var thumb = new Border { Style = St("ThumbStyle") };
        if (e.Mime.StartsWith("image/"))
            try { thumb.Child = new Image { Stretch = Stretch.UniformToFill, Source = new BitmapImage { DecodePixelWidth = 480, UriSource = new Uri(e.Path) } }; } catch (Exception) { }
        thumb.Child ??= new TextBlock { Text = System.IO.Path.GetExtension(e.Name).TrimStart('.').ToLowerInvariant(), Style = St("Figure"), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        var name = T(e.Name, 13);
        var folder = _r.S.FileFolders.TryGetValue(e.Fid, out var ff) ? ff + " · " : "";
        var info = T(folder + FormatSize(e.Size) + " · " + DateTimeOffset.FromUnixTimeMilliseconds(e.Ts).ToLocalTime().ToString("g", CultureInfo.CurrentCulture), 12, "LabelTertiary");
        var bodyP = new StackPanel { Spacing = 8 };
        bodyP.Children.Add(thumb);
        bodyP.Children.Add(new StackPanel { Margin = new Thickness(8, 0, 8, 8), Children = { name, info } });
        var tile = new Button { Style = St("TileStyle"), Content = bodyP };
        tile.Click += (_, _) => { if (e.Mime.StartsWith("image/")) OpenViewer(e); else _r.Files.Open(e); };
        Bounce(tile, 1.03f);
        var menu = new MenuFlyout();
        if (e.Mime.StartsWith("image/"))
        {
            var cp = new MenuFlyoutItem { Text = "Copia immagine" };
            cp.Click += (_, _) => _r.Clip.ApplyFile(e.Path, e.Mime);
            menu.Items.Add(cp);
        }
        var move = new MenuFlyoutSubItem { Text = "Sposta in cartella" };
        foreach (var f in new List<string?> { null }.Concat(_r.S.Folders))
        {
            var target = f;
            var mi = new MenuFlyoutItem { Text = f ?? "Nessuna cartella" };
            mi.Click += (_, _) =>
            {
                if (target == null) _r.S.FileFolders.Remove(e.Fid); else _r.S.FileFolders[e.Fid] = target;
                _r.S.Save();
                RenderShare();
            };
            move.Items.Add(mi);
        }
        menu.Items.Add(move);
        var del = new MenuFlyoutItem { Text = "Elimina" };
        del.Click += async (_, _) => await ConfirmDeleteAsync(e);
        menu.Items.Add(del);
        tile.ContextFlyout = menu;
        return tile;
    }

    // ================================================================ Audio

    private void RenderAudio()
    {
        var h = Reset("Audio");
        EnsureTicks();
        var np = _r.Np;
        string Who(string? id) => id == null ? "Libere" : id == _r.DeviceId ? "Su questo PC" : "Su " + _r.DeviceName(id);

        // cuffie
        var hpRow = new Grid { ColumnSpacing = 16 };
        hpRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        hpRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        hpRow.Children.Add(Bubble("", 72, _r.Hp.Holder == _r.DeviceId ? B("AccentDefault") : null));
        var info = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        info.Children.Add(T(_r.Hp.List.FirstOrDefault(x => x.Connected)?.Name ?? "Cuffie", 13, "LabelTertiary"));
        info.Children.Add(T(Who(_r.Hp.Holder), 22, "LabelPrimary", true));
        if (_r.Hp.LockedBy != null) info.Children.Add(T("Bloccate " + Who(_r.Hp.LockedBy).ToLowerInvariant(), 13, "LabelTertiary"));
        Grid.SetColumn(info, 1);
        hpRow.Children.Add(info);
        var locked = _r.Hp.LockedBy == _r.DeviceId;
        var hpCol = new StackPanel { Spacing = 12 };
        hpCol.Children.Add(hpRow);
        var lockBtn = Btn(locked ? "Sblocca" : "Tienile qui", () => { _r.Hp.ToggleLock(); RenderAudio(); }, locked ? "SecondaryButton" : "PrimaryButton");
        Bounce(lockBtn);
        hpCol.Children.Add(lockBtn);
        h.Children.Add(Widget(hpCol));

        // player (copertina, tempo, tasti-bolla, volume) in una sola scheda
        h.Children.Add(PlayerCard());

        // ascolta altrove: solo se c'è un dispositivo che può riceverlo
        var sinks = _r.Devices.Where(d => d.Caps.Contains("audio-sink")).ToList();
        if (sinks.Count > 0 || _r.Audio.SinkId != null)
        {
            var col = new StackPanel { Spacing = 10 };
            col.Children.Add(T("Ascolta altrove", 17, "LabelPrimary", true));
            col.Children.Add(T("L'audio di questo PC suona su un altro dispositivo. Ritardo circa 100 ms.", 13, "LabelTertiary"));
            foreach (var d in sinks)
            {
                var dev = d;
                var active = _r.Audio.SinkId == dev.Id;
                col.Children.Add(Row(dev.Name, active ? "Sta suonando l'audio di questo PC" : "Disponibile",
                    active ? Btn("Ferma", () => _r.Audio.Route(null)) : Btn("Ascolta lì", () => _r.Audio.Route(dev.Id), "PrimaryButton")));
            }
            h.Children.Add(Widget(col));
        }
    }

    private Border PlayerCard()
    {
        var np = _r.Np;
        var g = new Grid();
        if (np.Active && np.Art != null)
        {
            var bg = new Image { Stretch = Stretch.UniformToFill, Opacity = 0.35 };
            _ = SetArt(bg, np.Art);
            g.Children.Add(bg);
            g.Children.Add(new Border { Background = Sb(WithA(C("BgSurface"), 170)) });
        }
        var col = new StackPanel { Spacing = 14, Margin = new Thickness(18) };
        if (!np.Active)
        {
            col.Children.Add(T("In riproduzione", 17, "LabelPrimary", true));
            col.Children.Add(T("Fai partire la musica su questo PC: qui compaiono copertina, comandi e volume.", 13, "LabelTertiary"));
        }
        else
        {
            var top = new Grid { ColumnSpacing = 14 };
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var art = new Image { Width = 84, Height = 84, Stretch = Stretch.UniformToFill };
            _ = SetArt(art, np.Art);
            top.Children.Add(new Border { Width = 84, Height = 84, CornerRadius = new CornerRadius(16), Background = B("BgSunken"), Child = art });
            var meta = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Spacing = 2 };
            var title = T(np.Title.Length > 0 ? np.Title : "Senza titolo", 19, "LabelPrimary", true);
            title.TextWrapping = TextWrapping.Wrap;
            title.MaxLines = 2;
            meta.Children.Add(title);
            meta.Children.Add(T(np.Artist, 15, "LabelSecondary"));
            meta.Children.Add(T(np.App, 12, "LabelTertiary"));
            Grid.SetColumn(meta, 1);
            top.Children.Add(meta);
            col.Children.Add(top);

            if (np.Duration > TimeSpan.Zero)
            {
                var times = new Grid();
                var tl = T(Clock(np.PositionNow()), 12, "LabelTertiary");
                var tr = T("−" + Clock(np.Duration - np.PositionNow()), 12, "LabelTertiary");
                tr.HorizontalAlignment = HorizontalAlignment.Right;
                times.Children.Add(tl);
                times.Children.Add(tr);
                var seeking = false;
                var slider = LineSlider(np.PositionNow() / np.Duration, f => { seeking = true; tl.Text = Clock(np.Duration * f); }, f => { np.Seek(np.Duration * f); seeking = false; });
                _ticks.Add(() =>
                {
                    if (seeking) return;
                    var p = np.PositionNow();
                    (slider.Tag as Action<double>)?.Invoke(p / np.Duration);
                    tl.Text = Clock(p);
                    tr.Text = "−" + Clock(np.Duration - p);
                });
                col.Children.Add(new StackPanel { Children = { slider, times } });
            }

            var btns = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 34, HorizontalAlignment = HorizontalAlignment.Center };
            var b0 = BubbleButton("", 52, np.Prev, tip: "Indietro");
            var b1 = BubbleButton(np.Playing ? "" : "", 68, np.Toggle, tip: "Riproduci o metti in pausa");
            var b2 = BubbleButton("", 52, np.Next, tip: "Avanti");
            b0.VerticalAlignment = b2.VerticalAlignment = VerticalAlignment.Center;
            btns.Children.Add(b0); btns.Children.Add(b1); btns.Children.Add(b2);
            col.Children.Add(btns);
        }
        // volume, sempre
        var vol = new Grid { ColumnSpacing = 10 };
        vol.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        vol.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        vol.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var lo = Icon("", 14, B("LabelTertiary"));
        lo.VerticalAlignment = VerticalAlignment.Center;
        vol.Children.Add(lo);
        var vs = LineSlider(np.Volume, f => np.Volume = (float)f);
        Grid.SetColumn(vs, 1);
        vol.Children.Add(vs);
        _ticks.Add(() => (vs.Tag as Action<double>)?.Invoke(np.Volume));
        var hi = Icon("", 14, B("LabelTertiary"));
        hi.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(hi, 2);
        vol.Children.Add(hi);
        col.Children.Add(vol);
        g.Children.Add(col);
        return new Border { CornerRadius = new CornerRadius(20), BorderBrush = GlassEdge(), BorderThickness = new Thickness(1), Background = B("BgSurface"), Child = g };
    }

    // ================================================================ Impostazioni

    private string? _setSub;

    private void RenderSettings()
    {
        var h = Reset("Impostazioni");
        var s = _r.S;
        if (_setSub == null)
        {
            var brand = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Margin = new Thickness(0, 0, 0, 8) };
            brand.Children.Add(new Border { Width = 52, Height = 52, CornerRadius = new CornerRadius(12), Child = new Image { Source = new BitmapImage(new Uri("ms-appx:///Assets/logo.png")), Stretch = Stretch.UniformToFill } });
            var bt = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            bt.Children.Add(T("Relay", 24, "LabelPrimary", true));
            bt.Children.Add(T($"Versione 1.0.{_r.Update.Current}", 13, "LabelTertiary"));
            brand.Children.Add(bt);
            h.Children.Add(brand);
            h.Children.Add(Category("", "Sistema", "Aggiornamenti, server e questo dispositivo", Argb(255, 0x8E, 0x8E, 0x93), "sys"));
            var g = T("MODULI", 11, "LabelTertiary", true);
            g.Margin = new Thickness(4, 12, 0, 0);
            h.Children.Add(g);
            h.Children.Add(Category("", "Condividi", "Collegamento diretto, screenshot, notifiche", Argb(255, 0x2F, 0xB5, 0xC4), "share"));
            h.Children.Add(Category("", "Finanze", "Importi nascosti", Argb(255, 0x5D, 0xA8, 0x5B), "fin"));
            h.Children.Add(Category("", "Audio", "Cuffie e passaggio automatico", Argb(255, 0xE8, 0x8A, 0x2E), "audio"));
            return;
        }
        var titles = new Dictionary<string, string> { ["sys"] = "Sistema", ["share"] = "Condividi", ["fin"] = "Finanze", ["audio"] = "Audio" };
        h.Children.Add(BackRow("Impostazioni", titles[_setSub], () => { _setSub = null; RenderSettings(); }));
        switch (_setSub)
        {
            case "sys":
                var name = new TextBox { Text = s.Name, PlaceholderText = "PC della scrivania" };
                var hub = new TextBox { Text = s.HubUrl, PlaceholderText = "ponte.esempio.it", FontFamily = (FontFamily)Application.Current.Resources["FontMono"] };
                var token = new PasswordBox { Password = s.Token, PlaceholderText = "Lo stesso impostato sul server" };
                h.Children.Add(Card(Text("Questo dispositivo", "SectionTitle"), Field("Nome del dispositivo", name), Field("Indirizzo del server", hub), Field("Token", token),
                    Btn("Salva", () =>
                    {
                        s.Name = name.Text.Trim().Length > 0 ? name.Text.Trim() : Environment.MachineName;
                        s.HubUrl = hub.Text.Trim();
                        s.Token = token.Password.Trim();
                        s.Save();
                        _r.Reconfigure();
                    }, "PrimaryButton"),
                    Row("Stato", _r.HubState == "Connesso" ? "Collegato" : _r.HubState)));
                h.Children.Add(Card(Text("Aggiornamenti", "SectionTitle"),
                    Row($"Versione 1.0.{_r.Update.Current}", _r.Update.Status.Length > 0 ? _r.Update.Status : "Si aggiorna da sola dalle release su GitHub, tramite il tuo server",
                        Btn("Cerca ora", () => { _ = Task.Run(() => _r.Update.CheckAsync(install: true, manual: true)); })),
                    Divider(),
                    Toggle("Avvia con Windows", "Relay parte in background all'accesso", GetAutostart(), SetAutostart)));
                h.Children.Add(Btn("Rivedi la configurazione iniziale", ShowOnboarding));
                break;
            case "share":
                h.Children.Add(Card(
                    Toggle("Collegamento diretto", "Rete locale e rete privata Headscale", s.LanEnabled, on => { s.LanEnabled = on; Apply(); }),
                    Divider(),
                    Toggle("Screenshot automatici", "Ogni nuovo screenshot va a tutti i dispositivi", s.Screenshots, on => { s.Screenshots = on; Apply(); }),
                    Row("Cartella degli screenshot", s.EffectiveScreenshotFolder, Btn("Cambia", async () => await PickScreenshotFolderAsync())),
                    Text("Nello Strumento di cattura attiva il salvataggio automatico degli screenshot, altrimenti restano solo negli appunti.", "FootnoteTertiary"),
                    Divider(),
                    Toggle("Notifiche del telefono", "Le notifiche inoltrate dal telefono compaiono come notifiche di Windows", s.ShowPhoneNotifications, on => { s.ShowPhoneNotifications = on; Apply(); })));
                break;
            case "fin":
                h.Children.Add(Card(Toggle("Nascondi gli importi", "Nelle dashboard le cifre si sfocano (anche dall'occhio accanto al saldo)", s.HideMoney, SetHideMoney),
                    Text("I pagamenti Revolut dalle notifiche arrivano dal telefono; qui puoi importare l'estratto CSV dalla pagina Finanze → Revolut.", "FootnoteTertiary")));
                break;
            case "audio":
                RenderAudioSettings(h);
                break;
        }
    }

    private Grid Category(string glyph, string title, string note, Windows.UI.Color tint, string key)
    {
        var g = new Grid { ColumnSpacing = 14, Padding = new Thickness(6, 8, 6, 8), Background = Sb(Microsoft.UI.Colors.Transparent), CornerRadius = new CornerRadius(20) };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var mixed = Windows.UI.Color.FromArgb(255, (byte)((tint.R * 2 + C("LabelPrimary").R) / 3), (byte)((tint.G * 2 + C("LabelPrimary").G) / 3), (byte)((tint.B * 2 + C("LabelPrimary").B) / 3));
        g.Children.Add(Bubble(glyph, 46, Sb(mixed)));
        var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        col.Children.Add(T(title, 15, "LabelPrimary", true));
        col.Children.Add(T(note, 13, "LabelTertiary"));
        Grid.SetColumn(col, 1);
        g.Children.Add(col);
        var chev = Icon("", 12, B("LabelTertiary"));
        chev.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(chev, 2);
        g.Children.Add(chev);
        g.PointerEntered += (_, _) => g.Background = Sb(WithA(C("LabelPrimary"), 10));
        g.PointerExited += (_, _) => g.Background = Sb(Microsoft.UI.Colors.Transparent);
        g.Tapped += (_, _) => { _setSub = key; RenderSettings(); };
        Bounce(g, 1.02f);
        return g;
    }

    private void RenderAudioSettings(StackPanel h)
    {
        var hps = _r.Hp.List;
        var pref = _r.S.PreferredHp;
        var choices = new List<string> { "" };
        choices.AddRange(hps.Select(x => x.Address));
        h.Children.Add(Card(
            Text("Passaggio automatico", "SectionTitle"),
            Text("Le cuffie vanno dove fai partire l'audio, se è il dispositivo che stai usando.", "Footnote"),
            Row("Cuffie preferite qui", hps.FirstOrDefault(x => x.Address == pref)?.Name ?? "Nessuna: passaggio spento", Btn("Cambia", () =>
            {
                _r.S.PreferredHp = choices[(choices.IndexOf(_r.S.PreferredHp) + 1) % choices.Count];
                _r.S.Save();
                RenderSettings();
            })),
            Toggle("Un dispositivo alla volta", "Gli altri vanno in pausa quando la musica parte qui", _r.S.AutoPause, on => { _r.S.AutoPause = on; _r.S.Save(); }),
            Toggle("Muto se non ci sono", "Se le cuffie preferite mancano: muto invece delle casse", _r.S.FallbackMute, on => { _r.S.FallbackMute = on; _r.S.Save(); })));
        var list = new List<UIElement> { Text("Cuffie", "SectionTitle") };
        if (hps.Count == 0) list.Add(Text("Nessuna cuffia associata a questo PC.", "FootnoteTertiary"));
        foreach (var x in hps)
        {
            var addr = x.Address;
            list.Add(Toggle(x.Name, x.Connected ? "Collegate qui" : "Non collegate qui", _r.S.Headphones.Contains(addr), on =>
            {
                if (on) _r.S.Headphones.Add(addr); else _r.S.Headphones.Remove(addr);
                _r.S.Save();
            }));
        }
        var opts = new[] { 0, 5, 10, 20, 30 };
        list.Add(Row("Scollega dopo", _r.S.IdleMinutes == 0 ? "Mai" : $"{_r.S.IdleMinutes} minuti senza audio", Btn("Cambia", () =>
        {
            _r.S.IdleMinutes = opts[(Array.IndexOf(opts, _r.S.IdleMinutes) + 1) % opts.Length];
            _r.S.Save();
            RenderSettings();
        })));
        h.Children.Add(Card(list.ToArray()));
    }
}
