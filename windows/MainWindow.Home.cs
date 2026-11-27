using System.Numerics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Shapes;

namespace Ponte;

/// <summary>
/// La Home di Android: una griglia di riquadri di vetro con gli spazi tutti uguali, uno per servizio.
/// Un tocco apre, tenendo premuto "vola fuori" l'app con i comandi rapidi.
/// </summary>
public sealed partial class MainWindow
{
    private const double Gap = 12;

    private bool _homeIntro = true;
    private double _homeWidth;
    private DateTime _homeHold = DateTime.MinValue;
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _homeLater;
    private bool _homeHooked;
    private int _planPage = -1;

    private void RenderHome()
    {
        // mentre un dito è giù o un riquadro sta mostrando "Copiato" non si ridisegna sotto
        if (DateTime.UtcNow < _homeHold) { HomeLater(); return; }
        var h = Reset("Home");
        h.Spacing = Gap;
        HookHome(h);
        var width = double.IsNaN(_hosts[0].Width) ? 688 : _hosts[0].Width;
        _homeWidth = width;
        var cell = Math.Floor((width - Gap) / 2);

        var plan = PlanTile(cell);
        var pair = PairCube(cell);
        var balance = BalanceTile(cell);
        var todo = TodoTile(cell);
        var body = BodyTile(width, Math.Max(cell * 0.62, 136));
        var audio = AudioTile(width, Math.Max(cell * 0.62, 184));
        h.Children.Add(Cols("**", Gap, plan, pair));
        h.Children.Add(Cols("**", Gap, balance, todo));
        h.Children.Add(body);
        h.Children.Add(audio);

        // l'ingresso a cascata solo la prima volta, non a ogni aggiornamento
        if (!_homeIntro || Root.ActualWidth <= 0) return;
        _homeIntro = false;
        var i = 0;
        foreach (var el in new FrameworkElement[] { plan, pair, balance, todo, body, audio }) Intro(el, 70 * i++);
    }

    private void HookHome(StackPanel h)
    {
        if (_homeHooked) return;
        _homeHooked = true;
        h.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((_, _) => HoldHome(1200)), true);
        h.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler((_, _) => { if (_homeHold < DateTime.UtcNow.AddMilliseconds(1200)) _homeHold = DateTime.MinValue; }), true);
    }

    private void HoldHome(int ms)
    {
        var until = DateTime.UtcNow.AddMilliseconds(ms);
        if (until > _homeHold) _homeHold = until;
    }

    /// <summary>Il disegno rimandato parte appena il riquadro ha finito.</summary>
    private void HomeLater()
    {
        if (_homeLater == null)
        {
            _homeLater = DispatcherQueue.CreateTimer();
            _homeLater.Interval = TimeSpan.FromMilliseconds(300);
            _homeLater.IsRepeating = false;
            _homeLater.Tick += (_, _) => { if (_tab == 0) RenderHome(); };
        }
        _homeLater.Stop();
        _homeLater.Start();
    }

    private static void Intro(FrameworkElement el, int delay)
    {
        Enter(el, 0, 30, 520, delay);
        el.Loaded += (_, _) =>
        {
            try
            {
                var v = ElementCompositionPreview.GetElementVisual(el);
                v.CenterPoint = new Vector3((float)el.ActualWidth / 2, (float)el.ActualHeight / 2, 0);
                var s = v.Compositor.CreateVector3KeyFrameAnimation();
                s.InsertKeyFrame(0f, new Vector3(0.9f, 0.9f, 1));
                s.InsertKeyFrame(1f, Vector3.One, Ease(v.Compositor));
                s.Duration = TimeSpan.FromMilliseconds(520);
                s.DelayTime = TimeSpan.FromMilliseconds(delay);
                s.DelayBehavior = Microsoft.UI.Composition.AnimationDelayBehavior.SetInitialValueBeforeDelay;
                v.StartAnimation("Scale", s);
            }
            catch (Exception) { }
        };
    }

    private Button HomeTile(UIElement content, double height, Action click, Action longPress, string name, double pad = 16)
    {
        var b = Tile(content, click, longPress, name, new Thickness(pad));
        b.Height = height;
        b.HorizontalAlignment = HorizontalAlignment.Stretch;
        return b;
    }

    private Grid TileHead(int glyph, string label, FrameworkElement? trailing = null)
    {
        var icon = IconBubble(glyph);
        var t = Semi(label, 13, "LabelSecondary");
        t.VerticalAlignment = VerticalAlignment.Center;
        return Cols("a*a", 8, icon, t, trailing);
    }

    private static Grid Rows(string spec, double gap, params FrameworkElement[] children)
    {
        var g = new Grid { RowSpacing = gap };
        for (var i = 0; i < spec.Length; i++)
        {
            g.RowDefinitions.Add(new RowDefinition { Height = spec[i] == 'a' ? GridLength.Auto : new GridLength(1, GridUnitType.Star) });
            if (i >= children.Length) continue;
            Grid.SetRow(children[i], i);
            g.Children.Add(children[i]);
        }
        return g;
    }

    // ---------------------------------------------------------------- agenda e studio

    /// <summary>Un riquadro, due pagine: il prossimo impegno e il pomodoro. Si cambia trascinando di lato o dai punti.</summary>
    private Button PlanTile(double cell)
    {
        if (_planPage < 0) _planPage = _r.Plan.State.Timer.Running ? 1 : 0;
        var holder = new Grid { Height = cell - 2, CornerRadius = new CornerRadius(27) };
        RoundClip(holder, 27);
        var ticks = new List<Action>();
        var swiped = false;

        void Show(int page)
        {
            _planPage = page;
            holder.Children.Clear();
            foreach (var a in ticks) _ticks.Remove(a);
            ticks.Clear();
            var study = page == 1;
            if (study) holder.Children.Add(StudyBackdrop(cell));
            var content = study ? StudyPage(ticks) : AgendaPage();
            content.Margin = new Thickness(16, 16, 16, 24);
            holder.Children.Add(content);

            var dots = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 3) };
            for (var i = 0; i < 2; i++)
            {
                var idx = i;
                var on = i == page;
                var mark = new Border
                {
                    Width = on ? 14 : 5, Height = 5, CornerRadius = new CornerRadius(2.5), Margin = new Thickness(3, 6, 3, 6),
                    Background = study ? Sb(Alpha(Microsoft.UI.Colors.White, on ? 0.95 : 0.4)) : B(on ? "LabelSecondary" : "LabelDisabled"),
                };
                dots.Children.Add(Bare(mark, () => { if (idx != _planPage) { Show(idx); Enter(holder, 0, 0, 260); } }, i == 0 ? "Agenda" : "Studio", 8));
            }
            holder.Children.Add(dots);
        }

        Show(_planPage);
        var tile = HomeTile(holder, cell, () => { if (swiped) { swiped = false; return; } Open(_planPage == 0 ? "agenda" : "studio"); }, () => OpenPanel("agenda"), "Agenda e studio", 0);

        double? from = null;
        tile.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((_, e) => { swiped = false; from = e.GetCurrentPoint(tile).Position.X; }), true);
        tile.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler((_, _) => from = null), true);
        tile.AddHandler(UIElement.PointerMovedEvent, new PointerEventHandler((_, e) =>
        {
            if (from is not { } x) return;
            var dx = e.GetCurrentPoint(tile).Position.X - x;
            if (Math.Abs(dx) < 40) return;
            from = null;
            swiped = true;
            var to = dx < 0 ? 1 : 0;
            if (to == _planPage) return;
            Show(to);
            Enter(holder, dx < 0 ? 40 : -40, 0, 320);
        }), true);
        tile.PointerWheelChanged += (_, e) =>
        {
            var p = e.GetCurrentPoint(tile).Properties;
            if (!p.IsHorizontalMouseWheel) return;
            e.Handled = true;
            var to = p.MouseWheelDelta > 0 ? 1 : 0;
            if (to != _planPage) { Show(to); Enter(holder, to == 1 ? 40 : -40, 0, 320); }
        };
        return tile;
    }

    private Grid AgendaPage()
    {
        var p = _r.Plan.State;
        var now = DateTime.Now;
        var next = new List<(string Title, DateTime When, bool AllDay)>();
        next.AddRange(p.Events.Where(e => Loc(e.End) > now).Select(e => (string.IsNullOrWhiteSpace(e.Title) ? "(senza titolo)" : e.Title, Loc(e.Start), e.AllDay)));
        next.AddRange(_r.Plan.AllExternal.Where(e => Loc(e.End) > now && Loc(e.Start) < now.AddDays(14)).Select(e => (e.Title, Loc(e.Start), e.AllDay)));
        next.AddRange(p.Exams.Where(e => Loc(e.Date) > now).Select(e => ("Esame: " + e.Title, Loc(e.Date), false)));
        next.AddRange(p.Tasks.Where(t => !t.Done && t.Due > 0 && Loc(t.Due).Date >= DateTime.Today).Select(t => (t.Title, Loc(t.Due).Date, true)));
        var sorted = next.OrderBy(n => n.AllDay && n.When < now ? DateTime.Today : n.When).ToList();

        var bottom = new StackPanel { Spacing = 6, VerticalAlignment = VerticalAlignment.Bottom };
        if (sorted.Count == 0) bottom.Children.Add(T("Niente in programma", 16, "LabelTertiary", true));
        else
        {
            var n = sorted[0];
            var title = Semi(n.Title, 25);
            title.TextWrapping = TextWrapping.Wrap;
            title.MaxLines = 3;
            bottom.Children.Add(title);
            var dot = new Ellipse { Width = 8, Height = 8, Fill = Sb(Third), VerticalAlignment = VerticalAlignment.Center };
            var when = T(DayLabel(n.When) + (n.AllDay ? "" : " " + n.When.ToString("HH:mm", It)), 13, "LabelSecondary");
            bottom.Children.Add(Cols("a*", 8, dot, when));
        }
        return Rows("a*", 8, TileHead(Tab.Calendar, "Agenda"), bottom);
    }

    private Grid StudyPage(List<Action> ticks)
    {
        var p = _r.Plan.State;
        var t = p.Timer;
        var icon = IconBubble(Tab.Cap);
        var label = Semi(t.Running ? (t.Phase == "focus" ? "Concentrazione" : "Pausa") : "Studio", 13);
        label.Foreground = Sb(Alpha(Microsoft.UI.Colors.White, 0.85));
        label.VerticalAlignment = VerticalAlignment.Center;
        var clock = T(TimerText(), 39, "LabelPrimary", true);
        clock.Foreground = White;
        var today = MinutesOn(p, DateTime.Today, DateTime.MaxValue);
        var sub = T(today > 0 ? $"{today} min oggi" : "Oggi niente", 13);
        sub.Foreground = Sb(Alpha(Microsoft.UI.Colors.White, 0.75));
        var bottom = VStack(0, clock, sub);
        bottom.VerticalAlignment = VerticalAlignment.Bottom;
        if (t.Running)
        {
            void Upd() { clock.Text = TimerText(); TimerCheck(); }
            EnsureTicks();
            ticks.Add(Upd);
            _ticks.Add(Upd);
        }
        return Rows("a*", 8, Cols("a*", 8, icon, label), bottom);
    }

    /// <summary>Fondo notturno con due onde che scorrono: azzurre in concentrazione, verdi in pausa.</summary>
    private Grid StudyBackdrop(double size)
    {
        var t = _r.Plan.State.Timer;
        var tone = t.Running && t.Phase != "focus" ? Argb(255, 0x34, 0xC7, 0x59) : TileBlue;
        var waves = new Canvas { Width = size, Height = size, HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top };
        waves.Children.Add(Wave(size, size * 0.52, size * 0.09, Alpha(tone, 0.38), 9000));
        waves.Children.Add(Wave(size, size * 0.64, size * 0.07, Alpha(tone, 0.55), 6200));
        var shade = new Border { Background = Vertical((Argb(26, 0, 0, 0), 0), (Argb(102, 0, 0, 0), 1)) };
        return new Grid { Background = Sb(Argb(255, 0x07, 0x0A, 0x14)), Children = { waves, shade } };
    }

    /// <summary>Un'onda larga due volte il riquadro: scorrendo di una larghezza torna identica, quindi gira senza stacchi.</summary>
    private static Microsoft.UI.Xaml.Shapes.Path Wave(double width, double level, double amp, Windows.UI.Color color, int ms)
    {
        Windows.Foundation.Point P(double x, double y) => new(x, y);
        var fig = new PathFigure { StartPoint = P(0, level), IsClosed = true };
        var half = width / 2;
        for (var i = 0; i < 4; i++)
        {
            var x = i * half;
            var y = level + (i % 2 == 0 ? -amp : amp);
            fig.Segments.Add(new BezierSegment { Point1 = P(x + half / 3, y), Point2 = P(x + half * 2 / 3, y), Point3 = P(x + half, level) });
        }
        fig.Segments.Add(new LineSegment { Point = P(width * 2, width) });
        fig.Segments.Add(new LineSegment { Point = P(0, width) });
        var geo = new PathGeometry();
        geo.Figures.Add(fig);
        var path = new Microsoft.UI.Xaml.Shapes.Path { Data = geo, Fill = Sb(color) };
        path.Loaded += (_, _) =>
        {
            try
            {
                var v = ElementCompositionPreview.GetElementVisual(path);
                ElementCompositionPreview.SetIsTranslationEnabled(path, true);
                var a = v.Compositor.CreateScalarKeyFrameAnimation();
                a.InsertKeyFrame(0f, 0f);
                a.InsertKeyFrame(1f, (float)-width, v.Compositor.CreateLinearEasingFunction());
                a.Duration = TimeSpan.FromMilliseconds(ms);
                a.IterationBehavior = Microsoft.UI.Composition.AnimationIterationBehavior.Forever;
                v.StartAnimation("Translation.X", a);
            }
            catch (Exception) { }
        };
        return path;
    }

    // ---------------------------------------------------------------- ultima immagine e ultimo testo

    private string? _shotPath;
    private BitmapImage? _shotBitmap;

    /// <summary>
    /// Due riquadri impilati: un tocco copia e quello toccato cresce fino a riempire il quadrato con "Copiato";
    /// due tocchi aprono, tenendo premuto escono i comandi di condivisione.
    /// </summary>
    private Grid PairCube(double cell)
    {
        var half = Math.Floor((cell - 8) / 2);
        var image = _r.Files.Items.FirstOrDefault(f => f.Mime.StartsWith("image/"));
        var last = _r.Clip.History.FirstOrDefault();

        var top = new Border { Background = B("BgSunken") };
        if (image != null)
        {
            try
            {
                if (_shotPath != image.Path) { _shotBitmap = new BitmapImage { DecodePixelWidth = 720, UriSource = new Uri(image.Path) }; _shotPath = image.Path; }
                top.Background = new ImageBrush { ImageSource = _shotBitmap, Stretch = Stretch.UniformToFill };
            }
            catch (Exception) { }
        }
        else top.Child = Center(Icons.Glyph(Tab.Images, B("LabelTertiary"), 22));

        var accent = C("AccentDefault");
        var tint = new Border { Background = Vertical((Alpha(accent, 0.12), 0), (Alpha(accent, 0), 1)), Padding = new Thickness(12) };
        if (last != null)
        {
            var text = T(last.Text.Length > 200 ? last.Text[..200] : last.Text, 16);
            text.TextWrapping = TextWrapping.Wrap;
            text.MaxLines = 2;
            text.VerticalAlignment = VerticalAlignment.Center;
            tint.Child = text;
        }
        else tint.Child = Center(Icons.Glyph(Tab.Clipboard, B("LabelTertiary"), 22));
        var bottom = new Border { Background = B("BgSurface"), Child = tint };

        var cube = new Grid { Height = cell };
        Button? a = null, b = null;
        a = Half(top, "Copia l'ultima immagine", VerticalAlignment.Top, () =>
        {
            if (image == null) return false;
            _r.Clip.ApplyFile(image.Path, image.Mime);
            return true;
        }, "images", () => b!);
        b = Half(bottom, "Copia l'ultimo testo", VerticalAlignment.Bottom, () =>
        {
            if (last == null) return false;
            _r.Clip.SetLocal(last.Text);
            return true;
        }, "clips", () => a!);
        cube.Children.Add(a);
        cube.Children.Add(b);
        return cube;

        Button Half(Border face, string name, VerticalAlignment side, Func<bool> copy, string open, Func<Button> other)
        {
            face.CornerRadius = new CornerRadius(20);
            var pill = CopiedPill();
            var veil = new Border { CornerRadius = new CornerRadius(20), Background = Sb(Alpha(C("BgSurface"), 0.86)), Opacity = 0, IsHitTestVisible = false, Child = pill };
            var edge = new Border { CornerRadius = new CornerRadius(20), BorderBrush = B("LineSeparator"), BorderThickness = new Thickness(1), IsHitTestVisible = false };
            var body = new Grid { Children = { face, veil, edge } };
            var button = Bare(body, null, name, 20);
            button.Height = half;
            button.HorizontalAlignment = HorizontalAlignment.Stretch;
            button.VerticalAlignment = side;
            var busy = false;
            var skip = LongPress(button, () => OpenPanel("share"));
            button.Click += async (_, _) =>
            {
                if (skip() || busy || !copy()) return;
                busy = true;
                HoldHome(2000);
                var rest = other();
                Canvas.SetZIndex(button, 1);
                Canvas.SetZIndex(rest, 0);
                void Shape(double k)
                {
                    button.Height = half + (cell - half) * k;
                    var r = new CornerRadius(20 + 8 * k);
                    face.CornerRadius = veil.CornerRadius = edge.CornerRadius = r;
                    rest.Opacity = 1 - 0.7 * k;
                    Scale(rest, 1 - 0.05 * k);
                }
                FadeTo(veil, 1, 260);
                Tween(320, Shape);
                Pop(pill, 0.9f, 320);
                await Task.Delay(1100);
                FadeTo(veil, 0, 320);
                Tween(360, k => Shape(1 - k), () => busy = false);
            };
            button.AddHandler(UIElement.DoubleTappedEvent, new DoubleTappedEventHandler((_, _) => Open(open)), true);
            Bounce(button, 0.97f);
            return button;
        }
    }

    private static void Scale(UIElement el, double to)
    {
        var v = ElementCompositionPreview.GetElementVisual(el);
        var size = el.ActualSize;
        v.CenterPoint = new Vector3(size.X / 2, size.Y / 2, 0);
        v.Scale = new Vector3((float)to, (float)to, 1);
    }

    // ---------------------------------------------------------------- saldo

    /// <summary>Saldo al centro di una ciambella con le prime cinque categorie di spesa del mese; l'occhio sfoca le cifre.</summary>
    private Button BalanceTile(double cell)
    {
        var money = _r.Fin.State;
        var start = Ms(new DateTime(DateTime.Today.Year, DateTime.Today.Month, 1));
        var spent = money.Txs.Where(t => t.Ts >= start && t.Cents < 0)
            .GroupBy(t => t.Cat).Select(g => -g.Sum(t => t.Cents)).OrderByDescending(v => v).Take(5).ToList();
        var total = spent.Sum();

        var size = Math.Max(72, cell - 66);
        var stroke = size * 0.15;
        var donut = new Grid { Width = size, Height = size };
        if (total == 0) donut.Children.Add(new Ellipse { Stroke = B("BgSunken"), StrokeThickness = size * 0.10 });
        else
        {
            var accent = C("AccentDefault");
            var surface = C("BgSurface");
            double[] mix = { 0, 0.38, 0.58, 0.72, 0.82 };
            var at = 0.0;
            for (var i = 0; i < spent.Count; i++)
            {
                var share = spent[i] / (double)total;
                var gap = spent.Count > 1 ? 2.0 / 360 : 0;
                if (share > gap) donut.Children.Add(Arc(at + gap / 2, at + share - gap / 2, size, stroke, Sb(Lerp(accent, surface, mix[i])), false));
                at += share;
            }
        }
        HideChart(donut);
        var amount = new Viewbox
        {
            StretchDirection = StretchDirection.DownOnly, Stretch = Stretch.Uniform, MaxWidth = size - stroke * 2 - 8,
            Child = MoneyText("home-total", money.TotalCents, 16),
        };
        var chart = Center(new Grid { Width = size, Height = size, Children = { donut, Center(amount) } });
        var content = Rows("a*", 2, TileHead(Tab.Wallet, "Saldo", EyeToggle(28)), chart);
        return HomeTile(content, cell, () => Open("finance"), () => OpenPanel("finance"), "Saldo", 12);
    }

    private static Windows.UI.Color Lerp(Windows.UI.Color a, Windows.UI.Color b, double k)
    {
        byte Ch(byte x, byte y) => (byte)Math.Round(x + (y - x) * k);
        return Argb(255, Ch(a.R, b.R), Ch(a.G, b.G), Ch(a.B, b.B));
    }

    // ---------------------------------------------------------------- cose da fare

    private Button TodoTile(double cell)
    {
        var open = TodoOpen(_r.Plan.State);
        var word = Semi(_todoUni ? "Uni" : "Life", 13, "LabelSecondary");
        var pick = Bare(word, () => { _todoUni = !_todoUni; _homeHold = DateTime.MinValue; RenderHome(); }, "Vita o università", 12);
        pick.HorizontalAlignment = HorizontalAlignment.Stretch;
        pick.VerticalAlignment = VerticalAlignment.Center;
        TextBlock? count = null;
        if (open.Count > 0)
        {
            count = Semi(open.Count.ToString(), 13, "AccentDefault");
            count.VerticalAlignment = VerticalAlignment.Center;
        }
        var list = new StackPanel { Spacing = 2 };
        if (open.Count == 0) list.Children.Add(T("Tutto fatto", 16, "LabelTertiary", true));
        var fit = Math.Clamp((int)((cell - 70) / 28), 1, 5);
        foreach (var t in open.Take(fit)) list.Children.Add(TodoRow(t, true));
        var content = Rows("a*", 6, Cols("a*a", 8, IconBubble(Tab.Cap), pick, count), list);
        return HomeTile(content, cell, () => Open("todo"), () => OpenPanel("agenda"), "Cose da fare");
    }

    // ---------------------------------------------------------------- corpo

    private Button BodyTile(double width, double height)
    {
        var b = _r.Bd.State;
        var tot = BdDayTotal(b, BodyStore.DayKey());
        var g = b.Goals;
        var dow = ((int)DateTime.Today.DayOfWeek + 6) % 7;
        var nowMin = DateTime.Now.Hour * 60 + DateTime.Now.Minute;
        var next = b.Slots.Where(s => s.Dow == dow && s.Minute >= nowMin - 30).OrderBy(s => s.Minute).FirstOrDefault()
            ?? b.Slots.Where(s => s.Dow == (dow + 1) % 7).OrderBy(s => s.Minute).FirstOrDefault();

        var size = height - 34;
        var kcal = Semi(BdN(tot.Kcal), Math.Clamp(size * 0.19, 14, 31));
        var ring = Ring(g.Kcal > 0 ? tot.Kcal / g.Kcal : 0, size, 12, kcal);
        ring.VerticalAlignment = VerticalAlignment.Center;

        var info = new StackPanel { Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        var head = Cols("aa", 8, IconBubble(Tab.Flame), Semi("Corpo", 13, "LabelSecondary"));
        ((FrameworkElement)head.Children[1]).VerticalAlignment = VerticalAlignment.Center;
        info.Children.Add(head);
        info.Children.Add(Semi($"{BdN(tot.Kcal)} / {g.Kcal} kcal", 20));
        info.Children.Add(T($"P {BdN(tot.P)} · C {BdN(tot.C)} · G {BdN(tot.F)}", 13, "LabelSecondary"));
        if (next != null) info.Children.Add(T($"{BdClock(next.Minute)}  {next.Label}", 13, "LabelTertiary"));
        return HomeTile(Cols("a*", 16, ring, info), height, () => Open("body"), () => Open("body"), "Corpo");
    }

    // ---------------------------------------------------------------- musica

    private byte[]? _artBytes;
    private BitmapImage? _artBitmap;

    /// <summary>La musica di qualunque dispositivo: copertina, comandi e, sotto, chi sta suonando.</summary>
    private Button AudioTile(double width, double height)
    {
        var np = _r.Np;
        var remotes = _r.Media.Known.Where(x => x.Key != _r.DeviceId).ToList();
        var playingRemote = remotes.Where(x => x.Value.Playing).Select(x => x.Key).FirstOrDefault();
        var remote = np.Playing ? null : playingRemote ?? (np.Active ? null : remotes.Select(x => x.Key).FirstOrDefault());
        RemoteMedia? other = remote == null ? null : remotes.First(x => x.Key == remote).Value;
        var title = other?.Title ?? (np.Active ? np.Title : "");
        var artist = other?.Artist ?? (np.Active ? np.Artist : "");
        var playing = other?.Playing ?? np.Playing;
        var has = other != null || np.Active;
        if (has && title.Length == 0) title = "Senza titolo";

        // copertina
        var side = height - 34;
        var cover = new Border { Width = side, Height = side, CornerRadius = new CornerRadius(18), Background = B("BgSunken") };
        var art = new Grid { Width = side, Height = side, Children = { cover } };
        var note = Bubble(Icons.Glyph(Tab.Headphones, White, 24), 44, C("AccentDefault"));
        art.Children.Add(Center(note));
        if (remote == null && np.Active && np.Art is { } bytes) _ = Cover(cover, note, bytes);
        if (playing)
        {
            var bars = EqBars();
            bars.HorizontalAlignment = HorizontalAlignment.Right;
            bars.VerticalAlignment = VerticalAlignment.Bottom;
            bars.Margin = new Thickness(0, 0, 10, 10);
            art.Children.Add(bars);
        }

        void Send(string a)
        {
            if (remote != null) _r.Media.Cmd(remote, a == "toggle" ? (playing ? "pause" : "play") : a);
            else if (a == "prev") np.Prev();
            else if (a == "next") np.Next();
            else np.Toggle();
        }

        var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        info.Children.Add(T(remote != null ? _r.DeviceName(remote) : "Questo PC", 13, playing ? "AccentDefault" : "LabelSecondary"));
        info.Children.Add(Semi(has ? title : "Silenzio", 16, has ? "LabelPrimary" : "LabelTertiary"));
        if (artist.Length > 0) info.Children.Add(T(artist, 13, "LabelSecondary"));
        if (has)
        {
            var prev = BubbleButton(Center(Icons.Player(Transport.Prev, B("LabelPrimary"), 16)), 38, () => Send("prev"), "Indietro");
            var play = BubbleButton(Center(Icons.Player(playing ? Transport.Pause : Transport.Play, White, 20)), 46, () => Send("toggle"), playing ? "Pausa" : "Riproduci", C("AccentDefault"));
            var next = BubbleButton(Center(Icons.Player(Transport.Next, B("LabelPrimary"), 16)), 38, () => Send("next"), "Avanti");
            prev.VerticalAlignment = next.VerticalAlignment = VerticalAlignment.Center;
            var keys = HStack(12, prev, play, next);
            keys.Margin = new Thickness(0, 4, 0, 4);
            info.Children.Add(keys);
        }
        var now = _r.Media.PlayingNow();
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(0, 4, 0, 0) };
        foreach (var id in new[] { _r.DeviceId }.Concat(_r.Devices.Select(d => d.Id).Where(d => d != _r.DeviceId)).Take(6))
        {
            var dev = id;
            var me = id == _r.DeviceId;
            var on = me ? np.Playing : now.ContainsKey(id);
            var bubble = Bubble(Icons.Glyph(Tab.Devices, B(on ? "AccentDefault" : "LabelSecondary"), 15), 30);
            if (on) { bubble.BorderBrush = B("AccentDefault"); bubble.BorderThickness = new Thickness(1.5); }
            var name = me ? "Questo PC" : _r.DeviceName(id);
            var b = BubbleButton(bubble, 30, () =>
            {
                if (me) np.Toggle();
                else if (on) _r.Media.StopOn(dev);
                else _r.Media.Cmd(dev, "play");
            }, name);
            row.Children.Add(b);
        }
        info.Children.Add(row);
        return HomeTile(Cols("a*", 16, art, info), height, () => Open("audio"), () => OpenPanel("audio"), "Audio");
    }

    private async Task Cover(Border cover, UIElement placeholder, byte[] bytes)
    {
        if (!ReferenceEquals(bytes, _artBytes))
        {
            var bmp = await BitmapFrom(bytes);
            if (bmp == null) return;
            _artBytes = bytes;
            _artBitmap = bmp;
        }
        cover.Background = new ImageBrush { ImageSource = _artBitmap, Stretch = Stretch.UniformToFill };
        placeholder.Visibility = Visibility.Collapsed;
    }

    /// <summary>Quattro barre che salgono e scendono mentre qualcosa suona.</summary>
    private static StackPanel EqBars()
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 3, Height = 16 };
        int[] speed = { 520, 680, 440, 600 };
        foreach (var ms in speed)
        {
            var bar = new Border { Width = 3, Height = 16, CornerRadius = new CornerRadius(1.5), Background = White };
            bar.Loaded += (_, _) =>
            {
                try
                {
                    var v = ElementCompositionPreview.GetElementVisual(bar);
                    v.CenterPoint = new Vector3(1.5f, 16, 0);
                    var a = v.Compositor.CreateScalarKeyFrameAnimation();
                    a.InsertKeyFrame(0f, 0.25f);
                    a.InsertKeyFrame(0.5f, 1f);
                    a.InsertKeyFrame(1f, 0.25f);
                    a.Duration = TimeSpan.FromMilliseconds(ms);
                    a.IterationBehavior = Microsoft.UI.Composition.AnimationIterationBehavior.Forever;
                    v.StartAnimation("Scale.Y", a);
                }
                catch (Exception) { }
            };
            p.Children.Add(bar);
        }
        return p;
    }

    private static async Task SetArt(Image img, byte[]? bytes)
    {
        var bmp = await BitmapFrom(bytes);
        if (bmp != null) img.Source = bmp;
    }
}
