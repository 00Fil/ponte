using System.Numerics;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Shapes;

namespace Ponte;

/// <summary>
/// I componenti di Android in versione Windows, con gli stessi nomi e le stesse misure: bolle di vetro (Goccia), schede piane,
/// riquadri della Home (GlassTile), interruttore, selettore che scivola, etichette, cifre che si nascondono, "Copiato", anelli e barre.
/// </summary>
public sealed partial class MainWindow
{
    private Brush B(string key) => Canone.Get(key, Dark);
    private Windows.UI.Color C(string key) => Canone.Color(key, Dark);
    private static Windows.UI.Color Argb(byte a, byte r, byte g, byte b) => Windows.UI.Color.FromArgb(a, r, g, b);
    private static SolidColorBrush Sb(Windows.UI.Color c) => new(c);
    private static Windows.UI.Color WithA(Windows.UI.Color c, byte a) => Windows.UI.Color.FromArgb(a, c.R, c.G, c.B);
    private static Windows.UI.Color Alpha(Windows.UI.Color c, double a) => WithA(c, (byte)Math.Round(Math.Clamp(a, 0, 1) * 255));
    private static SolidColorBrush White => Sb(Microsoft.UI.Colors.White);
    private static SolidColorBrush Clear => Sb(Microsoft.UI.Colors.Transparent);

    /// <summary>Il terzo colore dei riquadri, solo come dettaglio.</summary>
    private static readonly Windows.UI.Color Third = Windows.UI.Color.FromArgb(255, 0xFF, 0xB3, 0x47);
    private static readonly Windows.UI.Color TileBlue = Windows.UI.Color.FromArgb(255, 0x3E, 0x7B, 0xFF);

    private static FontFamily Font(string key) => (FontFamily)Application.Current.Resources[key];

    private TextBlock T(string text, double size, string color = "LabelPrimary", bool medium = false) => new()
    {
        Text = text, FontSize = size, Foreground = B(color), TextTrimming = TextTrimming.CharacterEllipsis,
        FontFamily = Font(medium ? "FontSansMedium" : "FontSans"),
    };

    private TextBlock Semi(string text, double size, string color = "LabelPrimary")
    {
        var t = T(text, size, color, true);
        t.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
        return t;
    }

    private static TEl Center<TEl>(TEl el) where TEl : FrameworkElement
    {
        el.HorizontalAlignment = HorizontalAlignment.Center;
        el.VerticalAlignment = VerticalAlignment.Center;
        return el;
    }

    /// <summary>Colonne: "*" prende lo spazio, "a" si adatta al contenuto; i figli vanno nelle colonne in ordine.</summary>
    private static Grid Cols(string spec, double gap, params FrameworkElement?[] children)
    {
        var g = new Grid { ColumnSpacing = gap };
        for (var i = 0; i < spec.Length; i++)
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = spec[i] == 'a' ? GridLength.Auto : new GridLength(1, GridUnitType.Star) });
        for (var i = 0; i < children.Length; i++)
        {
            if (children[i] is not { } el) continue;
            Grid.SetColumn(el, i);
            g.Children.Add(el);
        }
        return g;
    }

    private static StackPanel VStack(double gap, params UIElement[] children)
    {
        var p = new StackPanel { Spacing = gap };
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    private static StackPanel HStack(double gap, params UIElement[] children)
    {
        var p = VStack(gap, children);
        p.Orientation = Orientation.Horizontal;
        return p;
    }

    private static ScrollViewer HScroll(UIElement content) => new()
    {
        HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
        HorizontalScrollMode = ScrollMode.Auto, VerticalScrollMode = ScrollMode.Disabled, Content = content,
    };

    // ---------------------------------------------------------------- tocco

    /// <summary>La goccia: premuta si gonfia, al rilascio torna ondeggiando (molla poco smorzata). Funziona anche sui pulsanti.</summary>
    private static void Bounce(FrameworkElement el, float press = 1.07f)
    {
        void Spring(float to, float damping, int periodMs)
        {
            try
            {
                var v = ElementCompositionPreview.GetElementVisual(el);
                v.CenterPoint = new Vector3((float)el.ActualWidth / 2, (float)el.ActualHeight / 2, 0);
                var a = v.Compositor.CreateSpringVector3Animation();
                a.FinalValue = new Vector3(to, to, 1);
                a.DampingRatio = damping;
                a.Period = TimeSpan.FromMilliseconds(periodMs);
                v.StartAnimation("Scale", a);
            }
            catch (Exception) { }
        }
        el.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((_, _) => Spring(press, 0.72f, 55)), true);
        el.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler((_, _) => Spring(1f, 0.38f, 62)), true);
        el.AddHandler(UIElement.PointerCaptureLostEvent, new PointerEventHandler((_, _) => Spring(1f, 0.38f, 62)), true);
        el.AddHandler(UIElement.PointerExitedEvent, new PointerEventHandler((_, _) => Spring(1f, 0.5f, 62)), true);
    }

    /// <summary>Un pulsante senza aspetto proprio: si disegna il contenuto, restano tastiera, fuoco e lettori di schermo.</summary>
    private static Button Bare(UIElement content, Action? click, string? name = null, double radius = 0)
    {
        var b = new Button
        {
            Style = St("BareButton"), Content = content, CornerRadius = new CornerRadius(radius),
            HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch,
        };
        if (click != null) b.Click += (_, _) => click();
        // il tocco su un tasto non deve arrivare anche al riquadro che lo contiene
        b.Tapped += (_, e) => e.Handled = true;
        b.DoubleTapped += (_, e) => e.Handled = true;
        if (name != null) AutomationProperties.SetName(b, name);
        return b;
    }

    /// <summary>Tieni premuto (dito o mouse) o clic destro: come la pressione lunga su Android. Ritorna true se il clic va ignorato.</summary>
    private Func<bool> LongPress(FrameworkElement el, Action action)
    {
        var fired = false;
        var timer = DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(450);
        timer.IsRepeating = false;
        var origin = new Windows.Foundation.Point();
        timer.Tick += (_, _) => { fired = true; action(); };
        el.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((_, e) =>
        {
            // un tasto interno al riquadro fa il suo, non apre il pannello
            for (var o = e.OriginalSource as DependencyObject; o != null && !ReferenceEquals(o, el); o = VisualTreeHelper.GetParent(o))
                if (o is ButtonBase) return;
            var p = e.GetCurrentPoint(el);
            if (p.Properties.IsRightButtonPressed) return;
            fired = false;
            origin = p.Position;
            timer.Start();
        }), true);
        el.AddHandler(UIElement.PointerMovedEvent, new PointerEventHandler((_, e) =>
        {
            var p = e.GetCurrentPoint(el).Position;
            if (Math.Abs(p.X - origin.X) + Math.Abs(p.Y - origin.Y) > 12) timer.Stop();
        }), true);
        el.AddHandler(UIElement.PointerReleasedEvent, new PointerEventHandler((_, _) => timer.Stop()), true);
        el.AddHandler(UIElement.PointerCaptureLostEvent, new PointerEventHandler((_, _) => timer.Stop()), true);
        el.RightTapped += (_, e) => { e.Handled = true; timer.Stop(); action(); };
        el.Unloaded += (_, _) => timer.Stop();
        return () => { var f = fired; fired = false; return f; };
    }

    // ---------------------------------------------------------------- vetro

    private static Windows.UI.Color Lighten(Windows.UI.Color c, double white)
    {
        var a = c.A / 255.0;
        var outA = white + a * (1 - white);
        if (outA <= 0) return c;
        byte Ch(byte v) => (byte)Math.Round((255 * white + v * a * (1 - white)) / outA);
        return Argb((byte)Math.Round(outA * 255), Ch(c.R), Ch(c.G), Ch(c.B));
    }

    private static LinearGradientBrush Vertical(params (Windows.UI.Color Color, double Offset)[] stops)
    {
        var g = new LinearGradientBrush { StartPoint = new Windows.Foundation.Point(0, 0), EndPoint = new Windows.Foundation.Point(0, 1) };
        foreach (var s in stops) g.GradientStops.Add(new GradientStop { Color = s.Color, Offset = s.Offset });
        return g;
    }

    /// <summary>Il corpo di una goccia: velo trasparente (o tinta), luce in alto e appena in basso.</summary>
    private Brush Drop(Windows.UI.Color? tint = null)
    {
        if (tint is not { } t) return B("DropFill");
        return Vertical((Lighten(t, Dark ? 0.09 : 0.24), 0), (t, 0.32), (t, 0.55), (Lighten(t, Dark ? 0.03 : 0.06), 1));
    }

    private Brush Hairline => B("DropEdge");

    /// <summary>Bolla rotonda di vetro con dentro un contenuto (icona, immagine o segno).</summary>
    private Border Bubble(UIElement? content, double size, Windows.UI.Color? tint = null) => new()
    {
        Width = size, Height = size, CornerRadius = new CornerRadius(size / 2),
        Background = Drop(tint), BorderBrush = Hairline, BorderThickness = new Thickness(1), Child = content,
    };

    /// <summary>Tasto-bolla: goccia che si gonfia e ondeggia al tocco.</summary>
    private Button BubbleButton(UIElement content, double size, Action click, string name, Windows.UI.Color? tint = null)
    {
        var b = Bare(Bubble(content, size, tint), click, name, size / 2);
        b.Width = b.Height = size;
        ToolTipService.SetToolTip(b, name);
        Bounce(b, 1.1f);
        return b;
    }

    /// <summary>Tasto tondo della dashboard (Action su Android): bolla da 58 con il nome sotto. Con <paramref name="accent"/> è velata di blu.</summary>
    private StackPanel Round(UIElement icon, string label, bool accent, Action click)
    {
        var b = BubbleButton(icon, 58, click, label, accent ? Alpha(C("AccentDefault"), Dark ? 0.38 : 0.26) : null);
        ToolTipService.SetToolTip(b, null);
        b.HorizontalAlignment = HorizontalAlignment.Center;
        var t = T(label, 13, "LabelSecondary");
        t.HorizontalAlignment = HorizontalAlignment.Center;
        return VStack(8, b, t);
    }

    private StackPanel Round(int glyph, string label, bool accent, Action click) =>
        Round(Icons.Glyph(glyph, B(accent ? "AccentOnSubtle" : "LabelPrimary"), 24), label, accent, click);

    /// <summary>Azione con un segno di testo (−, +, €) al posto dell'icona.</summary>
    private StackPanel Round(string symbol, string label, bool accent, Action click) =>
        Round(Center(T(symbol, 31, accent ? "AccentOnSubtle" : "LabelPrimary", true)), label, accent, click);

    /// <summary>Icona in una piccola bolla blu, davanti al titolo di un riquadro.</summary>
    private Border IconBubble(int glyph, double size = 28) =>
        Bubble(Icons.Glyph(glyph, White, size * 0.57), size, Alpha(C("AccentDefault"), 0.85));

    // ---------------------------------------------------------------- superfici

    /// <summary>Scheda (CCard): piana, raggio 28, senza bordo.</summary>
    private Border Widget(UIElement child, double? height = null, Thickness? pad = null, double radius = 28) => new()
    {
        Background = B("BgSurface"), CornerRadius = new CornerRadius(radius), Padding = pad ?? new Thickness(24), Child = child,
        Height = height ?? double.NaN,
    };

    /// <summary>Riquadro di vetro della Home (GlassTile): sfumatura appena accennata, filo di luce, si abbassa premuto.</summary>
    private Button Tile(UIElement content, Action? click = null, Action? longPress = null, string? name = null, Thickness? pad = null)
    {
        var light = new Border { CornerRadius = new CornerRadius(28), Background = White, Opacity = 0, IsHitTestVisible = false };
        var inner = new Border { Padding = pad ?? new Thickness(16), Child = content };
        var body = new Border
        {
            CornerRadius = new CornerRadius(28), Background = B("TileFill"), BorderBrush = B("TileEdge"), BorderThickness = new Thickness(1),
            Child = new Grid { Children = { light, inner } },
        };
        Func<bool>? skip = null;
        var b = Bare(body, null, name, 28);
        b.Click += (_, _) => { if (skip?.Invoke() != true) click?.Invoke(); };
        if (longPress != null) skip = LongPress(b, longPress);
        b.PointerEntered += (_, _) => FadeTo(light, Dark ? 0.025 : 0.35, 160);
        b.PointerExited += (_, _) => FadeTo(light, 0, 240);
        Bounce(b, 0.97f);
        return b;
    }

    // ---------------------------------------------------------------- anello e barre

    /// <summary>Anello di avanzamento: pista tenue, arco blu con estremi tondi, contenuto al centro.</summary>
    private Grid Ring(double fraction, double size, double stroke, UIElement? center = null, Brush? color = null)
    {
        var g = new Grid { Width = size, Height = size };
        g.Children.Add(new Ellipse { Stroke = Sb(Alpha(C("LabelPrimary"), 0.12)), StrokeThickness = stroke });
        var f = Math.Clamp(fraction, 0, 1);
        if (f >= 0.999) g.Children.Add(new Ellipse { Stroke = color ?? B("AccentDefault"), StrokeThickness = stroke });
        else if (f > 0.001) g.Children.Add(Arc(0, f, size, stroke, color ?? B("AccentDefault"), true));
        if (center is FrameworkElement el) g.Children.Add(Center(el));
        return g;
    }

    /// <summary>Un arco di cerchio, da <paramref name="from"/> a <paramref name="to"/> (frazioni di giro, da mezzogiorno).</summary>
    private static Microsoft.UI.Xaml.Shapes.Path Arc(double from, double to, double size, double stroke, Brush color, bool round)
    {
        var r = (size - stroke) / 2;
        Windows.Foundation.Point At(double f)
        {
            var a = f * 2 * Math.PI - Math.PI / 2;
            return new Windows.Foundation.Point(size / 2 + r * Math.Cos(a), size / 2 + r * Math.Sin(a));
        }
        to = Math.Min(to, from + 0.9999);
        var fig = new PathFigure { StartPoint = At(from), IsClosed = false };
        fig.Segments.Add(new ArcSegment { Point = At(to), Size = new Windows.Foundation.Size(r, r), SweepDirection = SweepDirection.Clockwise, IsLargeArc = to - from > 0.5 });
        var geo = new PathGeometry();
        geo.Figures.Add(fig);
        var cap = round ? PenLineCap.Round : PenLineCap.Flat;
        return new Microsoft.UI.Xaml.Shapes.Path { Data = geo, Stroke = color, StrokeThickness = stroke, StrokeStartLineCap = cap, StrokeEndLineCap = cap };
    }

    /// <summary>Barra di avanzamento (GlassProgress): pista tenue e riempimento blu.</summary>
    private Grid Progress(double fraction, Brush? color = null, double height = 8, Brush? track = null)
    {
        var f = Math.Clamp(fraction, 0, 1);
        var g = new Grid { Height = height };
        g.Children.Add(new Border { CornerRadius = new CornerRadius(height / 2), Background = track ?? B("TrackFill") });
        var fill = new Border { CornerRadius = new CornerRadius(height / 2), Background = color ?? B("AccentDefault"), HorizontalAlignment = HorizontalAlignment.Left };
        g.SizeChanged += (_, _) => fill.Width = f <= 0 ? 0 : Math.Max(height, g.ActualWidth * f);
        g.Children.Add(fill);
        return g;
    }

    // ---------------------------------------------------------------- interruttore

    /// <summary>L'interruttore di vetro: pista 54×32 che si colora del gradiente di marca, pomello bianco da 26 che scivola con una molla.</summary>
    internal static ToggleButton GlassSwitch(bool on, Action<bool> changed, string name)
    {
        var dark = Canone.IsDark;
        var off = new Border
        {
            CornerRadius = new CornerRadius(16), BorderThickness = new Thickness(1), BorderBrush = Canone.Get("DropEdge", dark),
            Background = new SolidColorBrush(Alpha(Canone.Color("FillControl", dark), dark ? 0.55 : 0.65)),
        };
        var lit = new Border { CornerRadius = new CornerRadius(16), Background = (Brush)Application.Current.Resources["BrandGradient"], Opacity = on ? 1 : 0 };
        var thumb = new Ellipse
        {
            Width = 26, Height = 26, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(3),
            Fill = new SolidColorBrush(Alpha(Microsoft.UI.Colors.White, dark ? 0.9 : 0.98)),
        };
        var t = new ToggleButton
        {
            Style = St("BareToggle"), IsChecked = on, Width = 54, Height = 32, CornerRadius = new CornerRadius(16),
            Content = new Grid { Width = 54, Height = 32, Children = { off, lit, thumb } },
        };
        AutomationProperties.SetName(t, name);
        ElementCompositionPreview.SetIsTranslationEnabled(thumb, true);
        void Move(bool value, bool animate)
        {
            var v = ElementCompositionPreview.GetElementVisual(thumb);
            var to = new Vector3(value ? 22 : 0, 0, 0);
            if (animate)
            {
                var a = v.Compositor.CreateSpringVector3Animation();
                a.FinalValue = to;
                a.DampingRatio = 0.6f;
                a.Period = TimeSpan.FromMilliseconds(70);
                v.StartAnimation("Translation", a);
                FadeTo(lit, value ? 1 : 0, 220);
            }
            else
            {
                v.Properties.InsertVector3("Translation", to);
                lit.Opacity = value ? 1 : 0;
            }
        }
        thumb.Loaded += (_, _) => Move(t.IsChecked == true, false);
        t.Checked += (_, _) => { Move(true, true); changed(true); };
        t.Unchecked += (_, _) => { Move(false, true); changed(false); };
        Bounce(t, 1.05f);
        return t;
    }

    // ---------------------------------------------------------------- cifre

    private readonly Dictionary<string, string> _lastText = new();

    /// <summary>Cambia il testo e, solo se è davvero cambiato rispetto all'ultima volta (non al primo disegno), lo fa comparire con un morph.</summary>
    private void SetMorph(TextBlock tb, string key, string text)
    {
        tb.Text = text;
        var changed = _lastText.TryGetValue(key, out var old) && old != text;
        _lastText[key] = text;
        if (!changed) return;
        tb.Loaded += (_, _) =>
        {
            try
            {
                var v = ElementCompositionPreview.GetElementVisual(tb);
                var c = v.Compositor;
                v.CenterPoint = new Vector3(0, (float)tb.ActualHeight / 2, 0);
                var s = c.CreateVector3KeyFrameAnimation();
                s.InsertKeyFrame(0f, new Vector3(0.9f, 0.9f, 1));
                s.InsertKeyFrame(1f, Vector3.One, Ease(c));
                s.Duration = TimeSpan.FromMilliseconds(320);
                var o = c.CreateScalarKeyFrameAnimation();
                o.InsertKeyFrame(0f, 0f);
                o.InsertKeyFrame(1f, 1f);
                o.Duration = TimeSpan.FromMilliseconds(260);
                v.StartAnimation("Scale", s);
                v.StartAnimation("Opacity", o);
            }
            catch (Exception) { }
        };
    }

    // ---------------------------------------------------------------- importi nascosti

    private readonly List<(UIElement Sharp, UIElement Smear)> _money = new();
    private readonly List<UIElement> _hideCharts = new();

    /// <summary>
    /// Importo che si può nascondere. Nascosto = sfocato: tante copie leggere del testo, spostate tutt'intorno, così la sfocatura
    /// esce oltre le cifre e sfuma senza bordo. Si passa dalla vista nitida alla sfocatura con una dissolvenza.
    /// </summary>
    private Grid MoneyText(string key, long cents, double size, string color = "LabelPrimary", bool semibold = true, string prefix = "")
    {
        var text = prefix + Finance.Eur(cents);
        var g = new Grid();
        var sharp = semibold ? Semi(text, size, color) : T(text, size, color);
        SetMorph(sharp, key, text);
        var smear = new Grid { IsHitTestVisible = false };
        var r = size * 0.22;
        for (var i = 0; i < 16; i++)
        {
            var ang = i * Math.PI / 8;
            var rr = i % 2 == 0 ? r : r * 0.5;
            var copy = semibold ? Semi(text, size, color) : T(text, size, color);
            copy.TextTrimming = TextTrimming.None;
            copy.Opacity = 0.10;
            copy.RenderTransform = new TranslateTransform { X = Math.Cos(ang) * rr, Y = Math.Sin(ang) * rr };
            AutomationProperties.SetAccessibilityView(copy, Microsoft.UI.Xaml.Automation.Peers.AccessibilityView.Raw);
            smear.Children.Add(copy);
        }
        var hidden = _r.S.HideMoney;
        sharp.Opacity = hidden ? 0 : 1;
        smear.Opacity = hidden ? 1 : 0;
        g.Children.Add(smear);
        g.Children.Add(sharp);
        _money.Add((sharp, smear));
        return g;
    }

    /// <summary>Grafico che si sfuma quando gli importi sono nascosti.</summary>
    private TEl HideChart<TEl>(TEl el) where TEl : UIElement
    {
        el.Opacity = _r.S.HideMoney ? 0.12 : 1;
        _hideCharts.Add(el);
        return el;
    }

    private void SetHideMoney(bool on)
    {
        _r.S.HideMoney = on;
        _r.S.Save();
        foreach (var (sharp, smear) in _money.ToList())
        {
            FadeTo(sharp, on ? 0 : 1, 650);
            FadeTo(smear, on ? 1 : 0, 650);
        }
        foreach (var c in _hideCharts.ToList()) FadeTo(c, on ? 0.12 : 1, 650);
    }

    private Button EyeToggle(double size = 28)
    {
        FrameworkElement Eye() => Icons.Of(_r.S.HideMoney ? Ico.EyeOff : Ico.Eye, B("LabelSecondary"), size * 0.6);
        var holder = new Border { Child = Eye() };
        Button? b = null;
        b = BubbleButton(holder, size, () => { SetHideMoney(!_r.S.HideMoney); holder.Child = Eye(); }, "Nascondi o mostra gli importi");
        return b;
    }

    // ---------------------------------------------------------------- copia a velo

    /// <summary>La pillola "Copiato": bolla blu con la spunta e la parola.</summary>
    private Border CopiedPill()
    {
        var check = Bubble(Icons.Of(Ico.Check, White, 14), 24, C("AccentDefault"));
        var word = Center(T("Copiato", 16, "LabelPrimary", true));
        return Center(new Border
        {
            CornerRadius = new CornerRadius(20), Background = B("BgSurface"), BorderBrush = Hairline, BorderThickness = new Thickness(1),
            Padding = new Thickness(8, 7, 16, 7), Child = HStack(8, check, word),
        });
    }

    /// <summary>
    /// Scheda che copia al primo tocco (subito, senza aspettare) e apre con il doppio tocco. Il contenuto si attenua e sopra sale un velo
    /// con la stessa forma e la pillola "Copiato", poi tutto torna com'era.
    /// </summary>
    private Button CopyCard(UIElement content, double height, Func<bool> copy, Action? open, double radius = 20, string name = "Copia")
    {
        var inner = new Border { Child = content };
        var pill = CopiedPill();
        var veil = new Border
        {
            CornerRadius = new CornerRadius(radius), Opacity = 0, IsHitTestVisible = false,
            Background = Sb(Alpha(C("BgSurface"), Dark ? 0.84 : 0.86)), Child = pill,
        };
        var host = new Grid();
        host.Children.Add(new Border { Background = B("BgSurface"), CornerRadius = new CornerRadius(radius), Child = inner });
        host.Children.Add(veil);
        var busy = false;
        var b = Bare(host, null, name, radius);
        b.Height = height;
        b.HorizontalAlignment = HorizontalAlignment.Stretch;
        b.Click += async (_, _) =>
        {
            if (busy || !copy()) return;
            busy = true;
            FadeTo(veil, 1, 320);
            FadeTo(inner, 0.25, 320);
            Pop(pill, 0.92f, 320);
            await Task.Delay(1020);
            FadeTo(veil, 0, 520);
            FadeTo(inner, 1, 520);
            await Task.Delay(520);
            busy = false;
        };
        if (open != null) b.AddHandler(UIElement.DoubleTappedEvent, new DoubleTappedEventHandler((_, _) => open()), true);
        Bounce(b, 0.98f);
        return b;
    }

    /// <summary>Compare ingrandendosi appena.</summary>
    private static void Pop(FrameworkElement el, float from, int ms)
    {
        try
        {
            var v = ElementCompositionPreview.GetElementVisual(el);
            v.CenterPoint = new Vector3((float)el.ActualWidth / 2, (float)el.ActualHeight / 2, 0);
            var s = v.Compositor.CreateVector3KeyFrameAnimation();
            s.InsertKeyFrame(0f, new Vector3(from, from, 1));
            s.InsertKeyFrame(1f, Vector3.One, Ease(v.Compositor));
            s.Duration = TimeSpan.FromMilliseconds(ms);
            v.StartAnimation("Scale", s);
        }
        catch (Exception) { }
    }

    // ---------------------------------------------------------------- selettore

    private readonly Dictionary<string, int> _segPrev = new();

    /// <summary>Selettore (GlassSegmented): la bolla velata di blu scivola dall'opzione precedente a quella scelta con una molla.</summary>
    private Grid Segmented(string key, string[] options, int selected, Action<int> onSelect)
    {
        var g = new Grid { Height = 42 };
        var back = new Border { CornerRadius = new CornerRadius(21), Background = B("BgCanvas") };
        var glass = new Border { CornerRadius = new CornerRadius(21), Background = Drop(), BorderBrush = Hairline, BorderThickness = new Thickness(1) };
        g.Children.Add(back);
        g.Children.Add(glass);
        var cells = new Grid { Margin = new Thickness(3) };
        for (var i = 0; i < options.Length; i++) cells.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var pill = new Border
        {
            CornerRadius = new CornerRadius(18), Background = Drop(Alpha(C("AccentDefault"), Dark ? 0.16 : 0.10)),
            BorderBrush = Hairline, BorderThickness = new Thickness(1),
        };
        Grid.SetColumn(pill, selected);
        cells.Children.Add(pill);
        for (var i = 0; i < options.Length; i++)
        {
            var idx = i;
            var t = Center(T(options[i], 13, i == selected ? "AccentOnSubtle" : "LabelSecondary", i == selected));
            var cell = Bare(t, () => { if (idx != selected) onSelect(idx); }, options[i], 18);
            cell.HorizontalAlignment = HorizontalAlignment.Stretch;
            cell.VerticalAlignment = VerticalAlignment.Stretch;
            Grid.SetColumn(cell, i);
            cells.Children.Add(cell);
        }
        g.Children.Add(cells);
        var prev = _segPrev.TryGetValue(key, out var p) ? p : selected;
        _segPrev[key] = selected;
        if (prev != selected)
            pill.Loaded += (_, _) =>
            {
                try
                {
                    var v = ElementCompositionPreview.GetElementVisual(pill);
                    ElementCompositionPreview.SetIsTranslationEnabled(pill, true);
                    var a = v.Compositor.CreateVector3KeyFrameAnimation();
                    a.InsertKeyFrame(0f, new Vector3((prev - selected) * (float)pill.ActualWidth, 0, 0));
                    a.InsertKeyFrame(1f, Vector3.Zero, v.Compositor.CreateCubicBezierEasingFunction(new Vector2(0.3f, 1.3f), new Vector2(0.5f, 1f)));
                    a.Duration = TimeSpan.FromMilliseconds(420);
                    v.StartAnimation("Translation", a);
                }
                catch (Exception) { }
            };
        return g;
    }

    // ---------------------------------------------------------------- etichette

    /// <summary>Etichetta-filtro tonda (Chip): scelta piena di blu, le altre incassate; con un pallino di colore se serve.</summary>
    private Button Chip(string text, bool on, Action click, Windows.UI.Color? dot = null, Windows.UI.Color? fill = null)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Center };
        if (dot is { } d) row.Children.Add(new Ellipse { Width = 8, Height = 8, Fill = Sb(d), VerticalAlignment = VerticalAlignment.Center });
        var t = T(text, 13, on ? "AccentOnAccent" : "LabelSecondary");
        t.VerticalAlignment = VerticalAlignment.Center;
        row.Children.Add(t);
        var face = new Border
        {
            Height = 36, CornerRadius = new CornerRadius(18), Padding = new Thickness(12, 0, 12, 0),
            Background = fill is { } f ? Sb(f) : on ? B("AccentDefault") : B("BgSunken"), Child = row,
        };
        var b = Bare(face, click, text, 18);
        Bounce(b);
        return b;
    }

    // ---------------------------------------------------------------- aiuti di forma e di movimento

    /// <summary>Anima un valore da 0 a 1 un fotogramma alla volta (per misure che il compositore non sa animare).</summary>
    private static void Tween(int ms, Action<double> step, Action? done = null)
    {
        var start = DateTime.UtcNow;
        EventHandler<object>? frame = null;
        frame = (_, _) =>
        {
            var k = Math.Min(1, (DateTime.UtcNow - start).TotalMilliseconds / ms);
            try { step(1 - Math.Pow(1 - k, 3)); } catch (Exception) { k = 1; }
            if (k < 1) return;
            CompositionTarget.Rendering -= frame;
            done?.Invoke();
        };
        CompositionTarget.Rendering += frame;
    }

    /// <summary>Taglia un elemento (immagini, sfondi animati) sugli angoli tondi di chi lo contiene.</summary>
    private static TEl RoundClip<TEl>(TEl el, double radius) where TEl : FrameworkElement
    {
        var v = ElementCompositionPreview.GetElementVisual(el);
        var geo = v.Compositor.CreateRoundedRectangleGeometry();
        geo.CornerRadius = new Vector2((float)radius);
        v.Clip = v.Compositor.CreateGeometricClip(geo);
        el.SizeChanged += (_, e) => geo.Size = new Vector2((float)e.NewSize.Width, (float)e.NewSize.Height);
        return el;
    }

    /// <summary>Vetro vero: sfoca quello che sta sotto.</summary>
    private static AcrylicBrush Glass(Windows.UI.Color tint, double opacity) => new()
    {
        TintColor = tint, TintOpacity = opacity, TintLuminosityOpacity = opacity, FallbackColor = Alpha(tint, Math.Min(1, opacity + 0.35)),
    };

    /// <summary>Il marchio di Relay: il gradiente di marca con il segno bianco.</summary>
    internal static Border RelayMark(double size)
    {
        var radius = new CornerRadius(size * 0.225);
        var sign = new ImageBrush
        {
            ImageSource = new BitmapImage(new Uri("ms-appx:///Assets/mark.png")), Stretch = Stretch.Uniform,
            Transform = new ScaleTransform { ScaleX = 1.6, ScaleY = 1.6, CenterX = size / 2, CenterY = size / 2 },
        };
        return new Border
        {
            Width = size, Height = size, CornerRadius = radius, Background = (Brush)Application.Current.Resources["BrandGradient"],
            Child = new Border { CornerRadius = radius, Background = sign },
        };
    }

    // ---------------------------------------------------------------- cursore a linea

    /// <summary>Cursore a sola linea, senza pallino: si ispessisce sotto il dito; segue il puntatore e chiama [onFinish] al rilascio.</summary>
    private Grid LineSlider(double value, Action<double> onChange, Action<double>? onFinish = null)
    {
        var g = new Grid { Height = 26, Background = Clear };
        var track = new Border { Height = 6, CornerRadius = new CornerRadius(3), VerticalAlignment = VerticalAlignment.Center, Background = B("TrackFill") };
        var fill = new Border { Height = 6, CornerRadius = new CornerRadius(3), VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Left, Background = B("AccentDefault") };
        g.Children.Add(track);
        g.Children.Add(fill);
        var cur = Math.Clamp(value, 0, 1);
        var active = false;
        void Draw() { fill.Width = Math.Max(0, g.ActualWidth * cur); }
        g.SizeChanged += (_, _) => Draw();
        g.Tag = new Action<double>(v => { if (!active) { cur = Math.Clamp(v, 0, 1); Draw(); } });
        double At(PointerRoutedEventArgs e) => Math.Clamp(e.GetCurrentPoint(g).Position.X / Math.Max(1, g.ActualWidth), 0, 1);
        void Thick(bool on) { track.Height = fill.Height = on ? 10 : 6; track.CornerRadius = fill.CornerRadius = new CornerRadius(on ? 5 : 3); }
        g.PointerPressed += (_, e) => { active = true; g.CapturePointer(e.Pointer); Thick(true); cur = At(e); Draw(); onChange(cur); };
        g.PointerMoved += (_, e) => { if (!active) return; cur = At(e); Draw(); onChange(cur); };
        void End() { if (!active) return; active = false; Thick(false); onFinish?.Invoke(cur); }
        g.PointerReleased += (_, e) => { End(); g.ReleasePointerCapture(e.Pointer); };
        g.PointerCaptureLost += (_, _) => End();
        return g;
    }

    // ---------------------------------------------------------------- animazione dei soldi

    private Grid? _flash;

    /// <summary>A tutto schermo dopo un'entrata o un'uscita: il saldo al centro, sotto l'operazione in verde o rosso, poi il saldo corre al risultato.</summary>
    private void FlashMoney(long before, long delta)
    {
        if (delta == 0) return;
        if (_flash == null)
        {
            _flash = new Grid { Visibility = Visibility.Collapsed, Opacity = 0 };
            Grid.SetRowSpan(_flash, 2);
            Grid.SetColumnSpan(_flash, 2);
            Root.Children.Add(_flash);
        }
        _flash.Children.Clear();
        _flash.Background = Sb(WithA(C("BgCanvas"), 245));
        var col = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Spacing = 8 };
        var lbl = T("Saldo", 13, "LabelTertiary");
        lbl.HorizontalAlignment = HorizontalAlignment.Center;
        var big = Semi(Finance.Eur(before), 56);
        big.HorizontalAlignment = HorizontalAlignment.Center;
        big.FontFamily = Font("FontMono");
        var op = T((delta > 0 ? "+ " : "− ") + Finance.Eur(Math.Abs(delta)), 25, delta > 0 ? "Positive" : "CriticalDefault", true);
        op.HorizontalAlignment = HorizontalAlignment.Center;
        col.Children.Add(lbl);
        col.Children.Add(big);
        col.Children.Add(op);
        _flash.Children.Add(col);
        _flash.Tapped += (_, _) => { FadeTo(_flash, 0, 260); };
        _flash.Visibility = Visibility.Visible;
        FadeTo(_flash, 1, 220);
        Enter(col, 0, 12, 380);
        var start = DateTime.Now.AddMilliseconds(450);
        var timer = DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(16);
        timer.Tick += async (t, _) =>
        {
            var k = (DateTime.Now - start).TotalMilliseconds / 520.0;
            if (k < 0) return;
            var e = 1 - Math.Pow(1 - Math.Min(1, k), 3);
            big.Text = Finance.Eur(before + (long)Math.Round(delta * e));
            if (k >= 1)
            {
                t.Stop();
                await Task.Delay(1100);
                FadeTo(_flash, 0, 320);
                await Task.Delay(340);
                _flash.Visibility = Visibility.Collapsed;
            }
        };
        timer.Start();
    }

    // ---------------------------------------------------------------- immagini

    private static async Task<BitmapImage?> BitmapFrom(byte[]? bytes)
    {
        if (bytes == null || bytes.Length == 0) return null;
        try
        {
            var ras = new Windows.Storage.Streams.InMemoryRandomAccessStream();
            var dw = new Windows.Storage.Streams.DataWriter(ras);
            dw.WriteBytes(bytes);
            await dw.StoreAsync();
            dw.DetachStream();
            ras.Seek(0);
            var bmp = new BitmapImage();
            await bmp.SetSourceAsync(ras);
            return bmp;
        }
        catch (Exception) { return null; }
    }

    private static readonly uint[] CatArgb = { 0xFF3F86FF, 0xFF5DA85B, 0xFFE88A2E, 0xFF8E5BB5, 0xFFD9534F, 0xFF2FB5C4, 0xFFD9B73A, 0xFF6D7BFF, 0xFF9AA19A, 0xFF5DA85B };

    private static Windows.UI.Color Rgb(uint v) => Windows.UI.Color.FromArgb((byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v);

    private static Windows.UI.Color CatColor(string cat) => Rgb(CatArgb[Math.Max(0, Array.IndexOf(Finance.Categories, cat)) % CatArgb.Length]);

    /// <summary>Un colore stabile per dispositivo, per distinguere da dove arriva ogni copia (come su Android).</summary>
    private static Windows.UI.Color DeviceTone(string id)
    {
        uint[] p = { 0xFF5C9DFF, 0xFF34C7A5, 0xFFF0A54A, 0xFFB38CFF, 0xFFFF7AA8, 0xFF7BD66B };
        var h = 0;
        foreach (var ch in id) h = unchecked(h * 31 + ch);
        return Rgb(id == "me" ? p[0] : p[(h & 0x7fffffff) % (p.Length - 1) + 1]);
    }

    private static string Clock(TimeSpan t) => $"{(int)t.TotalMinutes}:{t.Seconds:00}";
}
