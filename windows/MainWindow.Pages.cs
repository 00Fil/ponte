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

/// <summary>Condividi e Impostazioni: stessa struttura, stessi componenti e stesse funzioni di Android.</summary>
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
        _tickTimer.Tick += (_, _) => { foreach (var a in _ticks.Concat(_panelTicks).ToList()) try { a(); } catch (Exception) { } };
        _tickTimer.Start();
    }

    /// <summary>Testata delle pagine interne (BackRow): ritorno a sinistra, titolo a destra.</summary>
    private Grid BackRow(string parent, string title, Action back)
    {
        var b = Btn("‹  " + parent, back, "TertiaryButton");
        b.VerticalAlignment = VerticalAlignment.Center;
        var t = Text(title, "Title3");
        t.TextWrapping = TextWrapping.NoWrap;
        t.TextTrimming = TextTrimming.CharacterEllipsis;
        t.HorizontalAlignment = HorizontalAlignment.Right;
        t.VerticalAlignment = VerticalAlignment.Center;
        return Cols("a*", 12, b, t);
    }

    // ================================================================ Condividi

    private StackPanel? _shareBody;

    private void RenderShare()
    {
        var h = Reset("Condividi");
        h.Spacing = 12;

        // due azioni tonde, centrate
        var holder = new Border { Child = Center(Icons.Glyph(Tab.Clipboard, B("AccentOnSubtle"), 24)) };
        var clip = Round(holder, "Invia appunti", true, async () =>
        {
            if (!await _r.Clip.SendCurrentAsync()) return;
            holder.Child = Center(Icons.Of(Ico.Check, B("AccentOnSubtle"), 24));
            await Task.Delay(1000);
            holder.Child = Center(Icons.Glyph(Tab.Clipboard, B("AccentOnSubtle"), 24));
        });
        var acts = HStack(40, clip, Round(Tab.Upload, "Invia file", false, () => _ = PickFilesAsync()));
        acts.HorizontalAlignment = HorizontalAlignment.Center;
        acts.Margin = new Thickness(0, 20, 0, 16);
        h.Children.Add(acts);

        h.Children.Add(DriveUsage());
        h.Children.Add(Segmented("share", new[] { "Appunti", "Immagini", "File" }, _shareTab, i => { _shareTab = i; _folder = null; RenderShareAnimated(); }));
        var body = new StackPanel { Spacing = 12 };
        if (_shareTab == 0)
        {
            var list = new StackPanel { Spacing = 12 };
            h.Children.Add(SearchField(_clipQuery, "Cerca negli appunti", q => { _clipQuery = q; BuildClips(list); }));
            BuildClips(list);
            body.Children.Add(list);
        }
        else BuildFiles(body);
        h.Children.Add(body);
        _shareBody = body;
    }

    private string _clipQuery = "";

    /// <summary>Campo di ricerca a capsula con la lente.</summary>
    private Grid SearchField(string value, string placeholder, Action<string> changed)
    {
        var lens = Icons.Of(Ico.Search, B("LabelTertiary"), 16);
        lens.VerticalAlignment = VerticalAlignment.Center;
        var box = new TextBox { Text = value, PlaceholderText = placeholder, VerticalAlignment = VerticalAlignment.Center };
        PlainBox(box);
        box.TextChanged += (_, _) => changed(box.Text);
        var row = Cols("a*", 4, lens, box);
        row.Margin = new Thickness(16, 0, 8, 0);
        return new Grid { Height = 44, Children = { new Border { CornerRadius = new CornerRadius(22), Background = B("BgSurface") }, row } };
    }

    /// <summary>Campo senza fondo né bordo propri: sta dentro una capsula disegnata.</summary>
    private static void PlainBox(TextBox box)
    {
        var clear = Clear;
        foreach (var key in new[] { "TextControlBackground", "TextControlBackgroundPointerOver", "TextControlBackgroundFocused", "TextControlBorderBrush", "TextControlBorderBrushPointerOver", "TextControlBorderBrushFocused" })
            box.Resources[key] = clear;
        box.BorderThickness = new Thickness(0);
    }

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

        var big = Semi(Size(used), 25);
        var of = T(" di 50 GB", 13, "LabelTertiary");
        of.VerticalAlignment = VerticalAlignment.Bottom;
        of.Margin = new Thickness(0, 0, 0, 3);
        var count = T(items.Count + " elementi", 13, "LabelTertiary");
        count.VerticalAlignment = VerticalAlignment.Bottom;
        count.HorizontalAlignment = HorizontalAlignment.Right;
        var head = Cols("a*a", 0, big, of, count);

        var parts = new (string Name, long V, Brush Col)[] { ("Immagini", img, B("AccentDefault")), ("File", oth, Sb(Argb(255, 0xFF, 0x9F, 0x0A))), ("Appunti", clip, Sb(Argb(255, 0x34, 0xC7, 0x59))) };
        var barRow = new Grid { Height = 10, CornerRadius = new CornerRadius(5), Background = Sb(Alpha(C("LabelPrimary"), 0.07)) };
        foreach (var p in parts)
        {
            if (p.V <= 0) continue;
            barRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max((double)p.V / DriveCap, 0.004), GridUnitType.Star) });
            var seg = new Border { Background = p.Col };
            Grid.SetColumn(seg, barRow.ColumnDefinitions.Count - 1);
            barRow.Children.Add(seg);
        }
        barRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(1.0 - (double)used / DriveCap, 0.0001), GridUnitType.Star) });

        var legend = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16 };
        foreach (var p in parts)
            legend.Children.Add(HStack(6, new Ellipse { Width = 8, Height = 8, Fill = p.Col, VerticalAlignment = VerticalAlignment.Center }, T(p.Name + " " + Size(p.V), 13, "LabelSecondary")));
        var card = Widget(VStack(12, head, barRow, legend), null, new Thickness(16), 20);
        card.Margin = new Thickness(0, 0, 0, 8);
        return card;
    }

    /// <summary>Cambio scheda morbido: il contenuto vecchio sfuma, il nuovo entra sfumando e salendo appena.</summary>
    private async void RenderShareAnimated()
    {
        if (_shareBody != null) { FadeTo(_shareBody, 0, 140); await Task.Delay(150); }
        RenderShare();
        if (_shareBody != null) { _shareBody.Opacity = 0; Enter(_shareBody, 0, 10, 380); }
    }

    private void BuildClips(StackPanel body)
    {
        body.Children.Clear();
        var query = _clipQuery.Trim();
        var shown = _r.Clip.History.Where(c => query.Length == 0 || c.Text.Contains(query, StringComparison.OrdinalIgnoreCase)).Take(120).ToList();
        if (shown.Count == 0) { body.Children.Add(T(query.Length == 0 ? "Niente per ora" : "Nessun risultato", 16, "LabelTertiary")); return; }
        var last = "";
        var today = DateTime.Today;
        foreach (var c in shown)
        {
            var item = c;
            var when = Loc(c.Ts);
            var day = when >= today ? "Oggi" : when >= today.AddDays(-1) ? "Ieri" : when.ToString("d MMMM", It);
            if (day != last) { last = day; var label = T(day, 13, "LabelTertiary", true); label.Margin = new Thickness(4, 8, 0, 0); body.Children.Add(label); }
            var txt = c.Text.Trim();
            var link = (txt.StartsWith("http://") || txt.StartsWith("https://")) && !txt.Contains(' ');
            var code = !link && txt.Length is >= 4 and <= 12 && !txt.Contains(' ') && txt.Count(char.IsDigit) >= 4;
            var kind = link ? "Link" : code ? "Codice" : c.Text.Count(ch => ch == '\n') >= 2 ? "Testo lungo" : "Testo";

            var bubble = Bubble(Center(Icons.Glyph(c.Mine ? Tab.House : Tab.Devices, B("LabelPrimary"), 20)), 40, Alpha(DeviceTone(c.Mine ? "me" : c.From), 0.34));
            bubble.VerticalAlignment = VerticalAlignment.Top;
            var who = HStack(8, T(c.Mine ? "Questo PC" : _r.DeviceName(c.From), 13, "LabelSecondary", true), T("· " + kind, 13, link ? "AccentDefault" : "LabelTertiary"));
            var time = T(when.ToString("t", CultureInfo.CurrentCulture), 13, "LabelTertiary");
            var text = T(c.Text.Length > 400 ? c.Text[..400] + "…" : c.Text, 16, link ? "AccentDefault" : "LabelPrimary");
            text.TextWrapping = TextWrapping.Wrap;
            text.MaxLines = link ? 1 : 3;
            if (code) text.FontFamily = Font("FontMono");
            var row = Cols("a*", 12, bubble, VStack(2, Cols("*a", 8, who, time), text));
            row.Margin = new Thickness(12);
            body.Children.Add(CopyCard(row, double.NaN, () => { _r.Clip.SetLocal(item.Text); return true; }, null));
        }
    }

    /// <summary>Una cartella come icona con il nome sotto.</summary>
    private Button FolderTile(string name, bool on, Ico icon, Action click)
    {
        var box = new Border
        {
            Width = 54, Height = 54, CornerRadius = new CornerRadius(20), Background = B(on ? "AccentDefault" : "BgSunken"),
            Child = Center(Icons.Of(icon, on ? White : B("LabelSecondary"), 28)),
        };
        var label = T(name, 13, on ? "LabelPrimary" : "LabelSecondary");
        label.HorizontalAlignment = HorizontalAlignment.Center;
        var b = Bare(VStack(4, box, label), click, name, 20);
        b.Width = 68;
        Bounce(b);
        return b;
    }

    private void BuildFiles(StackPanel body)
    {
        var kind = _shareTab == 1 ? "screenshot" : "file";
        var folders = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        foreach (var f in new List<string?> { null }.Concat(_r.S.Folders))
        {
            var ff = f;
            var tile = FolderTile(f ?? "Tutte", _folder == f, Ico.Folder, () => { _folder = ff; RenderShareAnimated(); });
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
                tile.ContextFlyout = menu;
            }
            folders.Children.Add(tile);
        }
        folders.Children.Add(FolderTile("Nuova", false, Ico.FolderPlus, async () =>
        {
            var box = new TextBox { PlaceholderText = "Lavoro, Università…" };
            var dlg = new ContentDialog { XamlRoot = Root.XamlRoot, Title = "Nuova cartella", Content = box, PrimaryButtonText = "Crea", CloseButtonText = "Annulla", DefaultButton = ContentDialogButton.Primary };
            if (await dlg.ShowAsync() == ContentDialogResult.Primary && box.Text.Trim().Length > 0 && !_r.S.Folders.Contains(box.Text.Trim()))
            {
                _r.S.Folders.Add(box.Text.Trim());
                _folder = box.Text.Trim();
                _r.S.Save();
                RenderShare();
            }
        }));
        body.Children.Add(HScroll(folders));

        var items = _r.Files.Items.Where(f => f.Kind == kind && (_folder == null || (_r.S.FileFolders.TryGetValue(f.Fid, out var ff) && ff == _folder))).ToList();
        if (items.Count == 0)
        {
            body.Children.Add(T(_folder != null ? "Cartella vuota" : _shareTab == 1 ? "Nessuna immagine" : "Nessun file", 16, "LabelTertiary"));
            return;
        }
        body.Children.Add(new ItemsRepeater
        {
            ItemsSource = items.Select(FileTile).Cast<UIElement>().ToList(),
            Layout = new UniformGridLayout { MinItemWidth = 150, MinItemHeight = 176, MinColumnSpacing = 12, MinRowSpacing = 12, ItemsStretch = UniformGridLayoutItemsStretch.Fill },
        });
    }

    private Button FileTile(FileEntry e)
    {
        var thumb = new Border { Style = St("ThumbStyle") };
        if (e.Mime.StartsWith("image/"))
            try { thumb.Child = new Image { Stretch = Stretch.UniformToFill, Source = new BitmapImage { DecodePixelWidth = 480, UriSource = new Uri(e.Path) } }; } catch (Exception) { }
        thumb.Child ??= new TextBlock { Text = System.IO.Path.GetExtension(e.Name).TrimStart('.').ToLowerInvariant(), Style = St("Figure"), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        var name = T(e.Name, 13);
        var folder = _r.S.FileFolders.TryGetValue(e.Fid, out var ff) ? ff + " · " : "";
        var info = T(folder + FormatSize(e.Size), 13, "LabelTertiary");
        ToolTipService.SetToolTip(name, Loc(e.Ts).ToString("g", CultureInfo.CurrentCulture));
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

    // ================================================================ audio (nelle Impostazioni)

    private Border PlayerCard()
    {
        var np = _r.Np;
        var col = new StackPanel { Spacing = 16 };
        if (!np.Active) col.Children.Add(T("Silenzio", 16, "LabelTertiary", true));
        else
        {
            var art = new Border { Width = 84, Height = 84, CornerRadius = new CornerRadius(18), Background = B("BgSunken") };
            var note = Center(Icons.Glyph(Tab.Headphones, B("LabelTertiary"), 28));
            art.Child = note;
            if (np.Art is { } bytes) _ = Cover(art, note, bytes);
            var meta = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Spacing = 2 };
            var title = Semi(np.Title.Length > 0 ? np.Title : "Senza titolo", 20);
            title.TextWrapping = TextWrapping.Wrap;
            title.MaxLines = 2;
            meta.Children.Add(title);
            meta.Children.Add(T(np.Artist, 16, "LabelSecondary"));
            meta.Children.Add(T(np.App, 13, "LabelTertiary"));
            col.Children.Add(Cols("a*", 16, art, meta));

            if (np.Duration > TimeSpan.Zero)
            {
                var tl = T(Clock(np.PositionNow()), 13, "LabelTertiary");
                var tr = T("−" + Clock(np.Duration - np.PositionNow()), 13, "LabelTertiary");
                tr.HorizontalAlignment = HorizontalAlignment.Right;
                var times = new Grid { Children = { tl, tr } };
                var seeking = false;
                var slider = LineSlider(np.PositionNow() / np.Duration, f => { seeking = true; tl.Text = Clock(np.Duration * f); }, f => { np.Seek(np.Duration * f); seeking = false; });
                EnsureTicks();
                _ticks.Add(() =>
                {
                    if (seeking) return;
                    var p = np.PositionNow();
                    (slider.Tag as Action<double>)?.Invoke(p / np.Duration);
                    tl.Text = Clock(p);
                    tr.Text = "−" + Clock(np.Duration - p);
                });
                col.Children.Add(VStack(0, slider, times));
            }

            var prev = BubbleButton(Center(Icons.Player(Transport.Prev, B("LabelPrimary"), 20)), 52, np.Prev, "Indietro");
            var play = BubbleButton(Center(Icons.Player(np.Playing ? Transport.Pause : Transport.Play, White, 28)), 68, np.Toggle, np.Playing ? "Pausa" : "Riproduci", C("AccentDefault"));
            var next = BubbleButton(Center(Icons.Player(Transport.Next, B("LabelPrimary"), 20)), 52, np.Next, "Avanti");
            prev.VerticalAlignment = next.VerticalAlignment = VerticalAlignment.Center;
            var keys = HStack(32, prev, play, next);
            keys.HorizontalAlignment = HorizontalAlignment.Center;
            col.Children.Add(keys);
        }
        // volume, sempre
        var word = T("Volume", 13, "LabelSecondary");
        word.VerticalAlignment = VerticalAlignment.Center;
        var vs = LineSlider(np.Volume, f => np.Volume = (float)f);
        EnsureTicks();
        _ticks.Add(() => (vs.Tag as Action<double>)?.Invoke(np.Volume));
        col.Children.Add(Cols("a*", 12, word, vs));
        return Widget(col);
    }

    private void RenderAudio(StackPanel h)
    {
        // cuffie
        var locked = _r.Hp.LockedBy == _r.DeviceId;
        var hp = new StackPanel { Spacing = 16 };
        if (HeadphonesInfo() is { } info) hp.Children.Add(info);
        else hp.Children.Add(T("Nessuna cuffia collegata", 16, "LabelTertiary", true));
        if (_r.Hp.LockedBy is { } by && !locked) hp.Children.Add(Row("Bloccate", _r.DeviceName(by)));
        var hold = Btn(locked ? "Sblocca" : "Tienile qui", () => { _r.Hp.ToggleLock(); RenderSettings(); }, locked ? "SecondaryButton" : "PrimaryButton");
        hold.HorizontalAlignment = HorizontalAlignment.Stretch;
        Bounce(hold);
        hp.Children.Add(hold);
        h.Children.Add(Widget(hp));

        h.Children.Add(PlayerCard());

        // ascolta altrove: solo se c'è un dispositivo che può riceverlo
        var sinks = _r.Devices.Where(d => d.Caps.Contains("audio-sink")).ToList();
        if (sinks.Count > 0 || _r.Audio.SinkId != null)
        {
            var list = new List<UIElement> { Text("Ascolta altrove", "SectionTitle") };
            foreach (var d in sinks)
            {
                var dev = d;
                var active = _r.Audio.SinkId == dev.Id;
                list.Add(Row(dev.Name, active ? "In riproduzione" : null,
                    active ? Btn("Ferma", () => _r.Audio.Route(null)) : Btn("Ascolta lì", () => _r.Audio.Route(dev.Id), "PrimaryButton")));
            }
            h.Children.Add(Card(list.ToArray()));
        }
        RenderAudioSettings(h);
    }

    // ================================================================ Impostazioni

    private string? _setSub;

    private void RenderSettings()
    {
        var h = Reset("Impostazioni");
        h.Spacing = 12;
        var s = _r.S;
        if (_setSub == null)
        {
            var name1 = Text("Relay", "Title2");
            var version = T($"Versione 1.0.{_r.Update.Current}", 13, "LabelTertiary");
            var words = VStack(0, name1, version);
            words.VerticalAlignment = VerticalAlignment.Center;
            var brand = HStack(12, RelayMark(52), words);
            brand.Margin = new Thickness(4, 0, 0, 8);
            h.Children.Add(brand);
            h.Children.Add(Category(Tab.Settings, "Sistema", "Aggiornamenti, server e questo dispositivo", Argb(255, 0x8E, 0x8E, 0x93), "sys"));
            var group = T("MODULI", 13, "LabelTertiary");
            group.Margin = new Thickness(12, 8, 0, 0);
            h.Children.Add(group);
            h.Children.Add(Category(Tab.Devices, "Condividi", "Collegamento diretto, screenshot, notifiche", Argb(255, 0x2F, 0xB5, 0xC4), "share"));
            h.Children.Add(Category(Tab.Wallet, "Finanze", "Importi nascosti", Argb(255, 0x5D, 0xA8, 0x5B), "fin"));
            h.Children.Add(Category(Tab.Headphones, "Audio", "Cuffie e passaggio automatico", Argb(255, 0xE8, 0x8A, 0x2E), "audio"));
            return;
        }
        var titles = new Dictionary<string, string> { ["sys"] = "Sistema", ["share"] = "Condividi", ["fin"] = "Finanze", ["audio"] = "Audio" };
        h.Children.Add(BackRow("Impostazioni", titles[_setSub], () => { _setSub = null; RenderSettings(); }));
        switch (_setSub)
        {
            case "sys":
                var name = new TextBox { Text = s.Name, PlaceholderText = "PC della scrivania" };
                var hub = new TextBox { Text = s.HubUrl, PlaceholderText = "ponte.esempio.it", FontFamily = Font("FontMono") };
                var token = new PasswordBox { Password = s.Token, PlaceholderText = "Lo stesso impostato sul server" };
                void Save()
                {
                    s.Name = name.Text.Trim().Length > 0 ? name.Text.Trim() : Environment.MachineName;
                    s.HubUrl = hub.Text.Trim();
                    s.Token = token.Password.Trim();
                    s.Save();
                    _r.Reconfigure();
                }
                h.Children.Add(Card(Text("Aggiornamenti", "SectionTitle"),
                    Row($"Versione 1.0.{_r.Update.Current}", _r.Update.Status,
                        Btn("Cerca ora", () => { _ = Task.Run(() => _r.Update.CheckAsync(install: true, manual: true)); })),
                    Divider(),
                    Toggle("Avvia con Windows", null, GetAutostart(), SetAutostart)));
                h.Children.Add(Card(Text("Server", "SectionTitle"),
                    Row("Stato", _r.HubState == "Connesso" ? "Collegato" : _r.HubState),
                    Field("Indirizzo", hub), Field("Token", token),
                    Btn("Salva e riconnetti", Save, "PrimaryButton")));
                h.Children.Add(Card(Text("Questo dispositivo", "SectionTitle"),
                    Field("Nome", name),
                    Btn("Salva", Save, "PrimaryButton"),
                    Divider(),
                    Row("Identificativo", _r.DeviceId)));
                h.Children.Add(Btn("Rivedi la configurazione iniziale", ShowOnboarding));
                break;
            case "share":
                h.Children.Add(Card(
                    Toggle("Collegamento diretto", null, s.LanEnabled, on => { s.LanEnabled = on; Apply(); }),
                    Divider(),
                    Toggle("Screenshot automatici", null, s.Screenshots, on => { s.Screenshots = on; Apply(); }),
                    Row("Cartella degli screenshot", s.EffectiveScreenshotFolder, Btn("Cambia", async () => await PickScreenshotFolderAsync())),
                    Divider(),
                    Toggle("Notifiche del telefono", null, s.ShowPhoneNotifications, on => { s.ShowPhoneNotifications = on; Apply(); })));
                break;
            case "fin":
                h.Children.Add(Card(
                    Toggle("Nascondi gli importi", null, s.HideMoney, SetHideMoney),
                    Divider(),
                    Row("Estratto Revolut", null, Btn("Importa", () => { _finSub = "revolut"; Select(2); }))));
                break;
            case "audio":
                RenderAudio(h);
                break;
        }
    }

    /// <summary>Voce di categoria: goccia di vetro con l'icona colorata, nome e descrizione; niente contenitore.</summary>
    private Button Category(int glyph, string title, string note, Windows.UI.Color tint, string key)
    {
        var ink = Lerp(tint, C("LabelPrimary"), 0.35);
        var bubble = Bubble(Center(Icons.Glyph(glyph, Sb(ink), 22)), 46);
        var name = T(title, 16, "LabelPrimary", true);
        var line = T(note, 13, "LabelTertiary");
        var col = VStack(0, name, line);
        col.VerticalAlignment = VerticalAlignment.Center;
        var chevron = Icons.Of(Ico.Forward, B("LabelTertiary"), 16);
        chevron.VerticalAlignment = VerticalAlignment.Center;
        var row = Cols("a*a", 12, bubble, col, chevron);
        var face = new Border { CornerRadius = new CornerRadius(28), Padding = new Thickness(4, 8, 12, 8), Background = Clear, Child = row };
        var b = Bare(face, () => { _setSub = key; RenderSettings(); }, title, 28);
        b.HorizontalAlignment = HorizontalAlignment.Stretch;
        b.PointerEntered += (_, _) => face.Background = Sb(Alpha(C("LabelPrimary"), 0.04));
        b.PointerExited += (_, _) => face.Background = Clear;
        Bounce(b, 1.02f);
        return b;
    }

    private void RenderAudioSettings(StackPanel h)
    {
        var hps = _r.Hp.List;
        var pref = _r.S.PreferredHp;
        var choices = new List<string> { "" };
        choices.AddRange(hps.Select(x => x.Address));
        h.Children.Add(Card(
            Text("Passaggio automatico", "SectionTitle"),
            Row("Cuffie preferite qui", hps.FirstOrDefault(x => x.Address == pref)?.Name ?? "Nessuna", Btn("Cambia", () =>
            {
                _r.S.PreferredHp = choices[(choices.IndexOf(_r.S.PreferredHp) + 1) % choices.Count];
                _r.S.Save();
                RenderSettings();
            })),
            Toggle("Un dispositivo alla volta", null, _r.S.AutoPause, on => { _r.S.AutoPause = on; _r.S.Save(); }),
            Toggle("Muto se non ci sono", null, _r.S.FallbackMute, on => { _r.S.FallbackMute = on; _r.S.Save(); })));
        var list = new List<UIElement> { Text("Cuffie", "SectionTitle") };
        if (hps.Count == 0) list.Add(Text("Nessuna cuffia associata", "FootnoteTertiary"));
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
        list.Add(Row("Scollega dopo", _r.S.IdleMinutes == 0 ? "Mai" : $"{_r.S.IdleMinutes} minuti", Btn("Cambia", () =>
        {
            _r.S.IdleMinutes = opts[(Array.IndexOf(opts, _r.S.IdleMinutes) + 1) % opts.Length];
            _r.S.Save();
            RenderSettings();
        })));
        h.Children.Add(Card(list.ToArray()));
    }
}
