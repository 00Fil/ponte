using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Shapes;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Ponte;

/// <summary>
/// Telecamere come su Android (CamsScreen.kt): il riquadro della Home a carosello che tenendo premuto si allarga con i comandi,
/// la pagina con le dirette in griglia e gli avvisi, la cam a tutto schermo. Le immagini arrivano dal telefono.
/// </summary>
public sealed partial class MainWindow
{
    private bool _homeCams;
    private int _camsTab;
    private bool _camsEdit;
    private bool _camBig;
    private bool _camMenu;
    private int _camPage;
    private int _camFull = -1;
    private bool _camHd;
    private string _camMode = "smart";
    private Action? _camTileShow, _camFullTick;
    private readonly Dictionary<int, long> _camPicAt = new();
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _camTimer;
    private string _camAsked = "";
    private DateTime _camAskedAt, _camTurnedAt;
    private DateTime? _camAway;
    private bool _camAnswering;
    private readonly Dictionary<int, WriteableBitmap> _camPics = new();
    private readonly HashSet<int> _camDecoding = new();
    private readonly List<(int Channel, Image Image, UIElement Empty)> _camViews = new();

    private static readonly (string Mode, string Name, int Glyph)[] CamModes =
    {
        ("smart", "Automatico", Tab.Contrast), ("light", "Luce bianca", Tab.Sun), ("ir", "Infrarossi", Tab.Moon),
        ("daylight", "Sempre a colori", Tab.Sun), ("night", "Bianco e nero", Tab.Moon),
    };
    private static readonly Windows.UI.Color CamBlack = Argb(255, 0x0B, 0x0B, 0x0D);
    private static readonly Windows.UI.Color CamRed = Argb(255, 0xFF, 0x45, 0x3A);

    private void InitCameras()
    {
        _r.Cams.Frame += (ch, jpeg) => DispatcherQueue.TryEnqueue(() => _ = CamPicture(ch, jpeg));
        Activated += (_, e) =>
        {
            if (e.WindowActivationState == WindowActivationState.Deactivated) _camAway ??= DateTime.UtcNow;
            else _camAway = null;
        };
        _camTimer = DispatcherQueue.CreateTimer();
        _camTimer.Interval = TimeSpan.FromSeconds(1);
        _camTimer.Tick += (_, _) => { CamTurn(); CamPulse(); };
        _camTimer.Start();
    }

    /// <summary>Le cam da mostrare: quelle che hanno mandato immagini; finché nessuna l'ha fatto, quelle collegate.</summary>
    private List<int> CamList(bool all = false)
    {
        var c = _r.Cams;
        return (c.Seen.Count > 0 ? c.Seen : c.Live).OrderBy(x => x).Where(x => all || !_r.S.CamHidden.Contains(x)).ToList();
    }

    private bool CamAvailable => _r.Cams.HasPhone && _r.Cams.Ready != false && CamList().Count > 0;

    private (string Text, Brush Dot) CamStatus()
    {
        var c = _r.Cams;
        if (!c.HasPhone) return ("Telefono non collegato", B("LabelTertiary"));
        if (!c.Answering) return (_camAsked.Length > 0 ? "In attesa del telefono" : "Telefono collegato", B("LabelTertiary"));
        if (c.Ready != true) return ("Non configurate sul telefono", B("LabelTertiary"));
        if (c.Status == 6) return ("Collegate", B("Positive"));
        if (c.Status == 10) return ("Password errata", Sb(Third));
        if (c.Error.Length > 0) return (c.Error, Sb(Third));
        return c.Status is 0 or -1 ? ("Collegamento…", Sb(Third)) : ("Non raggiungibili", B("LabelTertiary"));
    }

    // ---------------------------------------------------------------- che cosa si sta guardando

    /// <summary>Si chiede al telefono solo ciò che è davvero a schermo, e solo finché la finestra è in uso.</summary>
    private (string View, int Channel, bool Hd, bool Alerts)? CamWant()
    {
        if (!AppWindow.IsVisible || AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized }) return null;
        // finestra lasciata in disparte: dopo due minuti il telefono torna a riposare
        if (_camAway is { } away && DateTime.UtcNow - away > TimeSpan.FromMinutes(2)) return null;
        if (_bdOver == "cam") return ("full", _camFull, _camHd, false);
        if (_bdOver != null || _tab != 0 || _panel != null || Onboard.Visibility == Visibility.Visible) return null;
        if (_homeCams) return _camsTab == 1 ? ("tile", -1, false, true) : ("grid", -1, false, false);
        var list = CamList();
        return ("tile", list.Count > 0 ? list[_camPage % list.Count] : -1, false, false);
    }

    private void CamPulse()
    {
        if (CamWant() is not { } w)
        {
            _r.Cams.Stop();
            _camAsked = "";
            return;
        }
        // mentre si sceglie che cosa nascondere servono le immagini di tutte
        var hide = _camsEdit ? new List<int>() : _r.S.CamHidden;
        var key = $"{w.View}/{w.Channel}/{w.Hd}/{w.Alerts}/{string.Join(',', hide)}";
        if (key != _camAsked || DateTime.UtcNow - _camAskedAt >= TimeSpan.FromSeconds(4))
        {
            _camAsked = key;
            _camAskedAt = DateTime.UtcNow;
            _r.Cams.Watch(w.View, w.Channel, w.Hd, hide, w.Alerts);
        }
        // il telefono ha smesso (o ripreso) di rispondere: si aggiorna la riga di stato
        if (_r.Cams.Answering != _camAnswering)
        {
            _camAnswering = _r.Cams.Answering;
            if (_tab == 0) RenderHome();
        }
        if (_bdOver == "cam") _camFullTick?.Invoke();
    }

    /// <summary>Il carosello della Home gira ogni 4 secondi, salvo quando il riquadro è allargato.</summary>
    private void CamTurn()
    {
        if (_camBig || _camTileShow == null || _tab != 0 || _homeCams || DateTime.UtcNow - _camTurnedAt < TimeSpan.FromSeconds(4)) return;
        var n = CamList().Count;
        if (n < 2) return;
        _camPage = (_camPage + 1) % n;
        _camTurnedAt = DateTime.UtcNow;
        _camTileShow();
    }

    private void CamRender()
    {
        _homeHold = DateTime.MinValue;
        if (_tab == 0) RenderHome();
    }

    // ---------------------------------------------------------------- immagini

    /// <summary>Si scrive sempre nella stessa bitmap del canale: niente sfarfallii. Se la decodifica è indietro, l'immagine si salta.</summary>
    private async Task CamPicture(int channel, byte[] jpeg)
    {
        if (!_camDecoding.Add(channel)) return;
        try
        {
            var (pixels, width, height) = await Task.Run(() => CamDecode(jpeg));
            var fresh = !_camPics.TryGetValue(channel, out var pic) || pic.PixelWidth != width || pic.PixelHeight != height;
            if (fresh) pic = _camPics[channel] = new WriteableBitmap(width, height);
            pixels.AsBuffer().CopyTo(pic!.PixelBuffer);
            pic.Invalidate();
            _camPicAt[channel] = Environment.TickCount64;
            if (fresh)
                foreach (var v in _camViews.Where(v => v.Channel == channel)) CamFill(v.Image, v.Empty, channel);
        }
        catch (Exception e) { Crash.Log(e, "Immagine telecamera"); }
        finally { _camDecoding.Remove(channel); }
    }

    private static async Task<(byte[] Pixels, int Width, int Height)> CamDecode(byte[] jpeg)
    {
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(jpeg.AsBuffer());
        stream.Seek(0);
        var decoder = await BitmapDecoder.CreateAsync(stream);
        if (decoder.PixelWidth > 4096 || decoder.PixelHeight > 4096) throw new InvalidDataException("Immagine troppo grande");
        var data = await decoder.GetPixelDataAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, new BitmapTransform(),
            ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.DoNotColorManage);
        return (data.DetachPixelData(), (int)decoder.PixelWidth, (int)decoder.PixelHeight);
    }

    private void CamFill(Image image, UIElement empty, int channel)
    {
        var has = _camPics.TryGetValue(channel, out var pic);
        image.Source = has ? pic : null;
        empty.Visibility = has ? Visibility.Collapsed : Visibility.Visible;
    }

    /// <summary>Una diretta: l'ultima immagine arrivata, nero finché non c'è.</summary>
    private Grid CamView(int channel, Stretch stretch = Stretch.UniformToFill)
    {
        var empty = new Ellipse { Width = 10, Height = 10, Fill = Sb(Argb(64, 255, 255, 255)), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        var image = new Image { Stretch = stretch, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        var view = (channel, image, (UIElement)empty);
        _camViews.Add(view);
        image.Unloaded += (_, _) => _camViews.Remove(view);
        CamFill(image, empty, channel);
        if (!_camPics.ContainsKey(channel) && _r.Cams.Last(channel) is { } jpeg) _ = CamPicture(channel, jpeg);
        return new Grid { Background = Sb(CamBlack), Children = { empty, image } };
    }

    /// <summary>Etichetta su vetro scuro sopra il video, col pallino rosso della diretta.</summary>
    private Border CamChip(string text)
    {
        var label = BdWhite(text, 13, true);
        var dot = new Ellipse { Width = 6, Height = 6, Fill = Sb(CamRed), VerticalAlignment = VerticalAlignment.Center };
        return new Border
        {
            CornerRadius = new CornerRadius(13), Background = Sb(Argb(115, 0, 0, 0)), Padding = new Thickness(9, 4, 9, 4), Margin = new Thickness(10),
            HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top, Child = HStack(6, dot, label), IsHitTestVisible = false,
        };
    }

    // ---------------------------------------------------------------- riquadro della Home

    /// <summary>La riga di audio e telecamere: allargando le cam, l'audio si fa da parte.</summary>
    private StackPanel CamRow(Button audio, double cell, double full)
    {
        // senza immagini non c'è niente da allargare
        if (!CamAvailable) _camBig = _camMenu = false;
        // quadrati, ma mai più bassi di quanto serve ai comandi dell'audio
        var tall = Math.Max(cell, 172);
        var side = new Grid { Children = { audio } };
        audio.Width = cell;
        audio.Height = tall;
        audio.HorizontalAlignment = HorizontalAlignment.Left;
        audio.VerticalAlignment = VerticalAlignment.Top;
        Button? cams = null;
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        void Shape(double k)
        {
            side.Width = cell * (1 - k);
            side.Margin = new Thickness(0, 0, Gap * (1 - k), 0);
            side.Opacity = 1 - k;
            side.Visibility = k > 0.98 ? Visibility.Collapsed : Visibility.Visible;
            cams!.Width = cell + (full - cell) * k;
            cams.Height = tall + (Math.Max(cell * 1.45, tall) - tall) * k;
        }
        cams = CamsTile(() =>
        {
            _camBig = !_camBig;
            _camMenu = false;
            HoldHome(700);
            var big = _camBig;
            Tween(360, k => Shape(big ? k : 1 - k), CamRender);
        });
        Shape(_camBig ? 1 : 0);
        row.Children.Add(side);
        row.Children.Add(cams);
        return row;
    }

    private Button CamsTile(Action grow)
    {
        if (!CamAvailable)
        {
            _camTileShow = null;
            var (text, dot) = CamStatus();
            var mark = new Ellipse { Width = 8, Height = 8, Fill = dot, VerticalAlignment = VerticalAlignment.Center };
            var state = T(text, 13, "LabelTertiary");
            state.TextWrapping = TextWrapping.Wrap;
            var low = VStack(4, Semi("Telecamere", 16), Cols("a*", 8, mark, state));
            low.VerticalAlignment = VerticalAlignment.Bottom;
            var icon = IconBubble(Tab.Camera);
            icon.HorizontalAlignment = HorizontalAlignment.Left;
            return Tile(Rows("a*", 8, icon, low), () => Open("cams"), null, "Telecamere", new Thickness(16));
        }

        var holder = new Grid { CornerRadius = new CornerRadius(27) };
        RoundClip(holder, 27);
        void Show()
        {
            var now = CamList();
            if (now.Count == 0) return;
            _camPage %= now.Count;
            var channel = now[_camPage];
            holder.Children.Clear();
            holder.Children.Add(CamView(channel));
            holder.Children.Add(CamChip((channel + 1).ToString(It)));
            if (now.Count > 1)
            {
                var dots = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 10), IsHitTestVisible = false };
                for (var i = 0; i < now.Count; i++)
                    dots.Children.Add(new Border { Width = i == _camPage ? 12 : 5, Height = 5, CornerRadius = new CornerRadius(2.5), Background = Sb(Argb(i == _camPage ? (byte)255 : (byte)115, 255, 255, 255)) });
                holder.Children.Add(dots);
            }
            if (_camBig) holder.Children.Add(CamBubble(channel, grow));
        }
        Show();
        _camTileShow = () => { Show(); Enter(holder.Children[0], 24, 0, 320); };

        Func<bool>? swipe = null;
        var tile = Tile(holder, () => { if (swipe?.Invoke() != true) Open("cams"); }, grow, "Telecamere", new Thickness(0));
        swipe = SideSwipe(tile, d =>
        {
            var n = CamList().Count;
            if (n < 2) return;
            _camPage = (_camPage + d + n) % n;
            _camTurnedAt = DateTime.UtcNow;
            _camTileShow?.Invoke();
            CamPulse();
        });
        return tile;
    }

    /// <summary>Comandi del riquadro allargato: una bolla nell'angolo che si allarga in barra.</summary>
    private Border CamBubble(int channel, Action grow)
    {
        var keys = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        var bar = new Border
        {
            Height = 46, CornerRadius = new CornerRadius(23), Background = Sb(Argb(180, 0x1C, 0x1C, 0x1E)), Margin = new Thickness(12),
            HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Bottom, Child = keys,
        };
        Button Key(UIElement icon, double size, Action click, string name)
        {
            var b = Bare(new Border { Width = size, Height = size, Background = Clear, Child = Center((FrameworkElement)icon) }, click, name, size / 2);
            ToolTipService.SetToolTip(b, name);
            Bounce(b, 1.12f);
            return b;
        }
        void Fill()
        {
            keys.Children.Clear();
            if (_camMenu)
            {
                foreach (var m in CamModes.Take(3))
                    keys.Children.Add(Key(Icons.Glyph(m.Glyph, White, 20), 40, () => _r.Cams.Light(channel, m.Mode), m.Name));
                keys.Children.Add(Key(Icons.Glyph(Tab.Expand, White, 18), 40, () => CamOpen(channel), "Tutto schermo"));
                keys.Children.Add(Key(Icons.Glyph(Tab.Shrink, White, 18), 40, grow, "Rimpicciolisci"));
            }
            var dots = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 3 };
            for (var i = 0; i < 3; i++) dots.Children.Add(new Ellipse { Width = 4, Height = 4, Fill = White });
            keys.Children.Add(Key(_camMenu ? Icons.Of(Ico.Close, White, 16) : dots, 46, () =>
            {
                _camMenu = !_camMenu;
                Fill();
                var from = bar.ActualWidth;
                var to = _camMenu ? 246 : 46;
                bar.Width = from;
                Tween(280, k => bar.Width = from + (to - from) * k);
            }, _camMenu ? "Chiudi i comandi" : "Comandi"));
        }
        Fill();
        bar.Width = _camMenu ? 246 : 46;
        return bar;
    }

    // ---------------------------------------------------------------- pagina Telecamere

    private void RenderCams(StackPanel h)
    {
        h.Children.Add(BackRow("Home", "Telecamere", () => { _homeCams = false; _camsEdit = false; CamRender(); CamPulse(); }));

        var (text, dot) = CamStatus();
        var mark = new Ellipse { Width = 8, Height = 8, Fill = dot, VerticalAlignment = VerticalAlignment.Center };
        var state = T(text, 13, "LabelSecondary");
        state.VerticalAlignment = VerticalAlignment.Center;
        Button? eye = null;
        if (_camsTab == 0 && CamList(true).Count > 0)
            eye = BubbleButton(Center(Icons.Of(_camsEdit ? Ico.Check : Ico.Eye, B(_camsEdit ? "AccentOnSubtle" : "LabelPrimary"), 17)), 42,
                () => { _camsEdit = !_camsEdit; CamRender(); CamPulse(); }, _camsEdit ? "Fatto" : "Nascondi telecamere", _camsEdit ? Alpha(C("AccentDefault"), 0.3) : null);
        h.Children.Add(Cols("a*a", 10, mark, state, eye));
        h.Children.Add(Segmented("cams", new[] { "Dal vivo", "Avvisi" }, _camsTab, i => { _camsTab = i; _camsEdit = false; CamRender(); CamPulse(); }));

        if (_camsTab == 1) CamAlerts(h);
        else CamGrid(h);
    }

    private void CamGrid(StackPanel h)
    {
        var width = double.IsNaN(h.Width) ? 688 : h.Width;
        _homeWidth = width;
        var cell = Math.Floor((width - 10) / 2);
        var list = CamList(_camsEdit);
        foreach (var row in list.Chunk(2))
        {
            var line = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
            foreach (var ch in row)
            {
                var channel = ch;
                var off = _r.S.CamHidden.Contains(channel);
                var view = CamView(channel);
                view.Opacity = _camsEdit && off ? 0.3 : 1;
                var body = new Grid { CornerRadius = new CornerRadius(20), Children = { view, CamChip((channel + 1).ToString(It)) } };
                RoundClip(body, 20);
                if (_camsEdit)
                {
                    var badge = Bubble(Icons.Of(off ? Ico.EyeOff : Ico.Eye, White, 20), 44, off ? Argb(205, 0x1C, 0x1C, 0x1E) : C("AccentDefault"));
                    body.Children.Add(Center(badge));
                }
                var tile = Bare(body, () =>
                {
                    if (!_camsEdit) { CamOpen(channel); return; }
                    if (!_r.S.CamHidden.Remove(channel)) _r.S.CamHidden.Add(channel);
                    _r.S.Save();
                    CamRender();
                }, $"Telecamera {channel + 1}", 20);
                tile.Width = cell;
                tile.Height = Math.Round(cell * 10 / 16);
                Bounce(tile, 0.97f);
                line.Children.Add(tile);
            }
            h.Children.Add(line);
        }
    }

    private static string CamKind(string type) => type switch { "hd" => "Persona rilevata", "md" or "motion" => "Movimento", _ => "Avviso" };

    private void CamAlerts(StackPanel h)
    {
        var alerts = _r.Cams.Alerts;
        if (alerts.Count == 0) { h.Children.Add(T("Nessun avviso", 16, "LabelTertiary")); return; }
        foreach (var a in alerts)
        {
            var picture = new Border { Width = 112, Height = 70, CornerRadius = new CornerRadius(14), Background = Sb(CamBlack) };
            if (Uri.TryCreate(a.Image, UriKind.Absolute, out var url) && (url.Scheme == Uri.UriSchemeHttp || url.Scheme == Uri.UriSchemeHttps))
                picture.Background = new ImageBrush { ImageSource = new BitmapImage { DecodePixelWidth = 224, UriSource = url }, Stretch = Stretch.UniformToFill };
            var when = DateTimeOffset.FromUnixTimeSeconds(a.Time).ToLocalTime().ToString("d MMM · HH:mm", It);
            var info = VStack(2, Semi(CamKind(a.Type), 16, a.Type == "hd" ? "AccentDefault" : "LabelPrimary"), T($"Telecamera {a.Channel + 1}", 13, "LabelSecondary"), T(when, 13, "LabelTertiary"));
            info.VerticalAlignment = VerticalAlignment.Center;
            var tile = Tile(Cols("a*", 14, picture, info), () => CamOpen(a.Channel), null, CamKind(a.Type), new Thickness(12));
            tile.HorizontalAlignment = HorizontalAlignment.Stretch;
            h.Children.Add(tile);
        }
    }

    // ---------------------------------------------------------------- tutto schermo

    private void CamOpen(int channel)
    {
        _camFull = channel;
        _camHd = false;
        _camMode = "smart";

        var live = BdWhite("", 13, false, 190);
        var liveDot = new Ellipse { Width = 7, Height = 7, VerticalAlignment = VerticalAlignment.Center };
        var saved = DateTime.MinValue;
        // "Dal vivo" solo se le immagini stanno davvero arrivando
        void Lamp()
        {
            if (DateTime.UtcNow < saved) return;
            var on = _camPicAt.TryGetValue(channel, out var at) && Environment.TickCount64 - at < 5_000;
            live.Text = on ? "Dal vivo" : "In attesa";
            liveDot.Fill = Sb(on ? CamRed : Argb(120, 255, 255, 255));
        }
        Lamp();
        _camFullTick = Lamp;
        var title = VStack(2, BdWhite($"Telecamera {channel + 1}", 17, true), HStack(7, liveDot, live));
        title.VerticalAlignment = VerticalAlignment.Center;
        var back = BdGlass(Icons.Of(Ico.Back, White, 18), 44, CloseBodyLayer, "Indietro");
        back.Loaded += (_, _) => back.Focus(FocusState.Programmatic);
        var shot = BdGlass(Icons.Glyph(Tab.Camera, White, 20), 44, async () =>
        {
            if (!await CamSnapshot(channel)) return;
            saved = DateTime.UtcNow.AddMilliseconds(1500);
            live.Text = "Foto salvata";
            liveDot.Fill = Sb(Argb(255, 0x34, 0xC7, 0x59));
        }, "Salva foto");
        var head = Cols("a*a", 14, back, title, shot);
        head.Margin = new Thickness(20, 48, 20, 8);

        var modes = new StackPanel { Spacing = 2 };
        var menu = new Border
        {
            Width = 280, Padding = new Thickness(6), CornerRadius = new CornerRadius(26), Background = Sb(Argb(232, 0x1C, 0x1C, 0x1E)),
            HorizontalAlignment = HorizontalAlignment.Left, Visibility = Visibility.Collapsed, Child = modes,
        };
        var bar = new Grid { ColumnSpacing = 4 };
        bar.ColumnDefinitions.Add(new ColumnDefinition());
        bar.ColumnDefinitions.Add(new ColumnDefinition());
        var barBack = new Border { Height = 62, Padding = new Thickness(6), CornerRadius = new CornerRadius(31), Background = Sb(Argb(215, 0x1C, 0x1C, 0x1E)), Child = bar };
        var foot = VStack(12, menu, barBack);
        foot.MaxWidth = 460;
        foot.Margin = new Thickness(16, 12, 16, 24);

        void Bar()
        {
            bar.Children.Clear();
            var mode = CamModes.First(m => m.Mode == _camMode).Name;
            var image = CamBarKey(Tab.Contrast, "Immagine", mode, menu.Visibility == Visibility.Visible, () =>
            {
                menu.Visibility = menu.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
                if (menu.Visibility == Visibility.Visible) Enter(menu, 0, 16, 260);
                Bar();
            });
            var quality = CamBarKey(Tab.Scan, "Qualità", _camHd ? "Alta (HD)" : "Normale", _camHd, () => { _camHd = !_camHd; Bar(); CamPulse(); });
            Grid.SetColumn(quality, 1);
            bar.Children.Add(image);
            bar.Children.Add(quality);
        }
        void Modes()
        {
            modes.Children.Clear();
            foreach (var m in CamModes)
            {
                var on = m.Mode == _camMode;
                var name = BdWhite(m.Name, 15, true);
                name.VerticalAlignment = VerticalAlignment.Center;
                var check = Icons.Of(Ico.Check, B("AccentDefault"), 18);
                check.Opacity = on ? 1 : 0;
                var face = new Border
                {
                    CornerRadius = new CornerRadius(16), Padding = new Thickness(12, 11, 12, 11),
                    Background = Sb(on ? Alpha(C("AccentDefault"), 0.22) : Argb(0, 0, 0, 0)),
                    Child = Cols("a*a", 12, Icons.Glyph(m.Glyph, White, 20), name, check),
                };
                modes.Children.Add(Bare(face, () =>
                {
                    _camMode = m.Mode;
                    _r.Cams.Light(channel, m.Mode);
                    menu.Visibility = Visibility.Collapsed;
                    Modes();
                    Bar();
                }, m.Name, 16));
            }
        }
        Modes();
        Bar();

        var video = CamView(channel, Stretch.Uniform);
        video.Background = Sb(Microsoft.UI.Colors.Black);
        video.Tapped += (_, _) => { if (menu.Visibility == Visibility.Visible) { menu.Visibility = Visibility.Collapsed; Bar(); } };

        var page = new Grid { RowDefinitions = { new RowDefinition { Height = GridLength.Auto }, new RowDefinition(), new RowDefinition { Height = GridLength.Auto } } };
        Grid.SetRow(video, 1);
        Grid.SetRow(foot, 2);
        page.Children.Add(head);
        page.Children.Add(video);
        page.Children.Add(foot);
        BdShowLayer("cam", page);
        CamPulse();
    }

    /// <summary>Tasto della barra: icona, titolo piccolo e valore attuale; acceso quando è attivo.</summary>
    private Button CamBarKey(int glyph, string title, string value, bool on, Action click)
    {
        var text = VStack(0, BdWhite(title, 11, false, 180), BdWhite(value, 13, true));
        text.VerticalAlignment = VerticalAlignment.Center;
        var face = new Border
        {
            CornerRadius = new CornerRadius(25), Padding = new Thickness(16, 0, 12, 0), Background = Sb(on ? Alpha(C("AccentDefault"), 0.85) : Argb(0, 0, 0, 0)),
            Child = Cols("a*", 10, Icons.Glyph(glyph, White, 20), text),
        };
        var b = Bare(face, click, title, 25);
        b.HorizontalAlignment = HorizontalAlignment.Stretch;
        b.VerticalAlignment = VerticalAlignment.Stretch;
        Bounce(b, 1.04f);
        return b;
    }

    /// <summary>Salva l'immagine attuale della cam in Immagini\Relay.</summary>
    private async Task<bool> CamSnapshot(int channel)
    {
        if (_r.Cams.Last(channel) is not { } jpeg) return false;
        try
        {
            var dir = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "Relay");
            Directory.CreateDirectory(dir);
            await File.WriteAllBytesAsync(System.IO.Path.Combine(dir, $"Cam{channel + 1}_{DateTime.Now:yyyyMMdd_HHmmss}.jpg"), jpeg);
            return true;
        }
        catch (Exception e) { Crash.Log(e, "Foto telecamera"); return false; }
    }
}
