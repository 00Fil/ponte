using System.Numerics;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Shapes;

namespace Ponte;

/// <summary>
/// Kit di Goccia per Windows, come su Android: bolle di vetro, tocco che si gonfia e ondeggia, cifre che si trasformano solo quando cambiano,
/// importi sfocati che si dissolvono, "Copiato" a velo, cursori a sola linea, selettore che scivola, animazione dei soldi a tutto schermo.
/// </summary>
public sealed partial class MainWindow
{
    private const string Fluent = "Segoe Fluent Icons, Segoe MDL2 Assets";

    private Brush B(string key) => Canone.Get(key, Dark);
    private Windows.UI.Color C(string key) => Canone.Color(key, Dark);
    private static Windows.UI.Color Argb(byte a, byte r, byte g, byte b) => Windows.UI.Color.FromArgb(a, r, g, b);
    private static SolidColorBrush Sb(Windows.UI.Color c) => new(c);
    private static Windows.UI.Color WithA(Windows.UI.Color c, byte a) => Windows.UI.Color.FromArgb(a, c.R, c.G, c.B);

    private static FontIcon Icon(string glyph, double size, Brush fg) =>
        new() { Glyph = glyph, FontSize = size, FontFamily = new FontFamily(Fluent), Foreground = fg, IsHitTestVisible = false };

    private TextBlock T(string text, double size, string color = "LabelPrimary", bool medium = false) => new()
    {
        Text = text, FontSize = size, Foreground = B(color), TextTrimming = TextTrimming.CharacterEllipsis,
        FontFamily = (FontFamily)Application.Current.Resources[medium ? "FontSansMedium" : "FontSans"],
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

    // ---------------------------------------------------------------- vetro

    /// <summary>Bordo di vetro: più chiaro in alto (luce), tonale in basso. Niente bianco latte sul corpo.</summary>
    private Brush GlassEdge() => new LinearGradientBrush
    {
        StartPoint = new Windows.Foundation.Point(0, 0), EndPoint = new Windows.Foundation.Point(0, 1),
        GradientStops =
        {
            new GradientStop { Color = Dark ? Argb(70, 255, 255, 255) : Argb(220, 255, 255, 255), Offset = 0 },
            new GradientStop { Color = WithA(C("LineSeparator"), 255), Offset = 0.55 },
            new GradientStop { Color = Dark ? Argb(40, 255, 255, 255) : Argb(120, 255, 255, 255), Offset = 1 },
        },
    };

    private Brush GlassBody() => new LinearGradientBrush
    {
        StartPoint = new Windows.Foundation.Point(0, 0), EndPoint = new Windows.Foundation.Point(0, 1),
        GradientStops =
        {
            new GradientStop { Color = Dark ? Argb(34, 157, 182, 240) : Argb(26, 58, 91, 192), Offset = 0 },
            new GradientStop { Color = Dark ? Argb(14, 157, 182, 240) : Argb(10, 58, 91, 192), Offset = 1 },
        },
    };

    /// <summary>Bolla rotonda di vetro (come la pillola della barra) con dentro un'icona.</summary>
    private Border Bubble(string glyph, double size, Brush? fg = null, bool accent = false)
    {
        var b = new Border
        {
            Width = size, Height = size, CornerRadius = new CornerRadius(size / 2),
            Background = accent ? (Brush)Application.Current.Resources["BrandGradient"] : GlassBody(),
            BorderBrush = GlassEdge(), BorderThickness = new Thickness(1),
            Child = Icon(glyph, size * 0.4, fg ?? (accent ? Sb(Microsoft.UI.Colors.White) : B("LabelPrimary"))),
        };
        return b;
    }

    /// <summary>Tasto-bolla: bolla di vetro che si gonfia e ondeggia al tocco.</summary>
    private Grid BubbleButton(string glyph, double size, Action click, bool accent = false, string? tip = null)
    {
        var g = new Grid { Width = size, Height = size, Background = Sb(Microsoft.UI.Colors.Transparent) };
        g.Children.Add(Bubble(glyph, size, null, accent));
        g.Tapped += (_, e) => { e.Handled = true; click(); };
        if (tip != null) ToolTipService.SetToolTip(g, tip);
        Bounce(g, 1.12f);
        return g;
    }

    /// <summary>Scheda (widget): superficie, raggio 20, bordo di vetro.</summary>
    private Border Widget(UIElement child, double? height = null, Thickness? pad = null) => new()
    {
        Background = B("BgSurface"), BorderBrush = GlassEdge(), BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(20), Padding = pad ?? new Thickness(16), Child = child,
        Height = height ?? double.NaN,
    };

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
        var sharp = T(text, size, color, semibold);
        if (semibold) sharp.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
        SetMorph(sharp, key, text);
        var smear = new Grid { IsHitTestVisible = false };
        var r = size * 0.22;
        for (var i = 0; i < 16; i++)
        {
            var ang = i * Math.PI / 8;
            var rr = i % 2 == 0 ? r : r * 0.5;
            var copy = T(text, size, color, semibold);
            copy.TextTrimming = TextTrimming.None;
            if (semibold) copy.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
            copy.Opacity = 0.10;
            copy.RenderTransform = new TranslateTransform { X = Math.Cos(ang) * rr, Y = Math.Sin(ang) * rr };
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

    private Grid EyeToggle()
    {
        var g = BubbleButton(_r.S.HideMoney ? "" : "", 36, () => { }, tip: "Nascondi o mostra gli importi");
        g.Tapped += (_, _) =>
        {
            SetHideMoney(!_r.S.HideMoney);
            if (g.Children[0] is Border b) b.Child = Icon(_r.S.HideMoney ? "" : "", 14, B("LabelPrimary"));
        };
        return g;
    }

    // ---------------------------------------------------------------- copia a velo

    /// <summary>
    /// Scheda che copia al primo tocco (subito, senza aspettare) e apre con il doppio tocco. Il contenuto si sfoca e sopra sale un velo di vetro
    /// leggermente opaco con la stessa forma, "Copiato" si mette a fuoco, poi tutto torna com'era.
    /// </summary>
    private Grid CopyCard(UIElement content, double height, Func<bool> copy, Action open, double radius = 20)
    {
        var host = new Grid { Height = height };
        var inner = new Grid();
        inner.Children.Add(content);
        var card = new Border
        {
            Background = B("BgSurface"), BorderBrush = GlassEdge(), BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(radius), Child = inner,
        };
        var veil = new Border
        {
            CornerRadius = new CornerRadius(radius), Opacity = 0, IsHitTestVisible = false,
            Background = Sb(WithA(C("BgSurface"), (byte)(Dark ? 150 : 170))), BorderBrush = GlassEdge(), BorderThickness = new Thickness(1),
            Child = new TextBlock
            {
                Text = "Copiato", FontSize = 22, Foreground = B("LabelPrimary"), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
                FontFamily = (FontFamily)Application.Current.Resources["FontSansMedium"],
            },
        };
        host.Children.Add(card);
        host.Children.Add(veil);
        host.Tapped += async (_, e) =>
        {
            e.Handled = true;
            if (!copy()) return;
            FadeTo(veil, 1, 260);
            FadeTo(inner, 0.25, 260);
            await Task.Delay(900);
            FadeTo(veil, 0, 480);
            FadeTo(inner, 1, 480);
        };
        host.DoubleTapped += (_, e) => { e.Handled = true; open(); };
        Bounce(host, 1.025f);
        return host;
    }

    // ---------------------------------------------------------------- selettore

    private readonly Dictionary<string, int> _segPrev = new();

    /// <summary>Selettore a goccia: la bolla di vetro scivola dall'opzione precedente a quella scelta con una molla.</summary>
    private Grid Segmented(string key, string[] options, int selected, Action<int> onSelect)
    {
        var g = new Grid { Height = 40, Background = B("BgSunken"), CornerRadius = new CornerRadius(20), Padding = new Thickness(3) };
        for (var i = 0; i < options.Length; i++) g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var pill = new Border { CornerRadius = new CornerRadius(17), Background = B("BgSurface"), BorderBrush = GlassEdge(), BorderThickness = new Thickness(1) };
        Grid.SetColumn(pill, selected);
        g.Children.Add(pill);
        for (var i = 0; i < options.Length; i++)
        {
            var idx = i;
            var t = T(options[i], 13, i == selected ? "LabelPrimary" : "LabelSecondary", i == selected);
            t.HorizontalAlignment = HorizontalAlignment.Center;
            t.VerticalAlignment = VerticalAlignment.Center;
            var cell = new Grid { Background = Sb(Microsoft.UI.Colors.Transparent), Children = { t } };
            Grid.SetColumn(cell, i);
            cell.Tapped += (_, _) => { if (idx != selected) onSelect(idx); };
            g.Children.Add(cell);
        }
        var prev = _segPrev.TryGetValue(key, out var p) ? p : selected;
        _segPrev[key] = selected;
        if (prev != selected)
            pill.Loaded += (_, _) =>
            {
                try
                {
                    var v = ElementCompositionPreview.GetElementVisual(pill);
                    ElementCompositionPreview.SetIsTranslationEnabled(pill, true);
                    var w = (float)pill.ActualWidth + 0;
                    var a = v.Compositor.CreateVector3KeyFrameAnimation();
                    a.InsertKeyFrame(0f, new Vector3((prev - selected) * w, 0, 0));
                    a.InsertKeyFrame(1f, Vector3.Zero, v.Compositor.CreateCubicBezierEasingFunction(new Vector2(0.3f, 1.3f), new Vector2(0.5f, 1f)));
                    a.Duration = TimeSpan.FromMilliseconds(420);
                    v.StartAnimation("Translation", a);
                }
                catch (Exception) { }
            };
        return g;
    }

    /// <summary>Etichetta-filtro tonda (categorie, cartelle).</summary>
    private Border Chip(string text, bool on, Action click)
    {
        var b = new Border
        {
            CornerRadius = new CornerRadius(16), Padding = new Thickness(12, 6, 12, 6),
            Background = on ? B("AccentDefault") : B("BgSunken"),
            Child = new TextBlock { Text = text, FontSize = 12, Foreground = on ? Sb(Microsoft.UI.Colors.White) : B("LabelSecondary") },
        };
        b.Tapped += (_, _) => click();
        Bounce(b);
        return b;
    }

    // ---------------------------------------------------------------- cursore a linea

    /// <summary>Cursore a sola linea, senza pallino: si ispessisce sotto il dito; segue il puntatore e chiama [onFinish] al rilascio.</summary>
    private Grid LineSlider(double value, Action<double> onChange, Action<double>? onFinish = null)
    {
        var g = new Grid { Height = 26, Background = Sb(Microsoft.UI.Colors.Transparent) };
        var track = new Border { Height = 6, CornerRadius = new CornerRadius(3), VerticalAlignment = VerticalAlignment.Center, Background = Sb(WithA(C("LabelPrimary"), 30)) };
        var fill = new Border { Height = 6, CornerRadius = new CornerRadius(3), VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Left, Background = B("LabelSecondary") };
        g.Children.Add(track);
        g.Children.Add(fill);
        var cur = Math.Clamp(value, 0, 1);
        var active = false;
        void Draw() { fill.Width = Math.Max(0, g.ActualWidth * cur); }
        g.SizeChanged += (_, _) => Draw();
        g.Tag = new Action<double>(v => { if (!active) { cur = Math.Clamp(v, 0, 1); Draw(); } });
        double At(PointerRoutedEventArgs e) => Math.Clamp(e.GetCurrentPoint(g).Position.X / Math.Max(1, g.ActualWidth), 0, 1);
        void Thick(bool on) { track.Height = fill.Height = on ? 10 : 6; track.CornerRadius = fill.CornerRadius = new CornerRadius(on ? 5 : 3); fill.Background = on ? B("LabelPrimary") : B("LabelSecondary"); }
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
        var col = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Spacing = 6 };
        var lbl = T("Saldo", 13, "LabelTertiary");
        lbl.HorizontalAlignment = HorizontalAlignment.Center;
        var big = T(Finance.Eur(before), 56, "LabelPrimary", true);
        big.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
        big.HorizontalAlignment = HorizontalAlignment.Center;
        big.FontFamily = (FontFamily)Application.Current.Resources["FontMono"];
        var op = T((delta > 0 ? "+ " : "− ") + Finance.Eur(Math.Abs(delta)), 22);
        op.Foreground = Sb(delta > 0 ? Argb(255, 0x1F, 0x9A, 0x5A) : WithA(C("CriticalDefault"), 255));
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

    private static Windows.UI.Color CatColor(string cat)
    {
        var i = Math.Max(0, Array.IndexOf(Finance.Categories, cat)) % CatArgb.Length;
        var v = CatArgb[i];
        return Windows.UI.Color.FromArgb((byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v);
    }

    /// <summary>Un colore stabile per dispositivo, per distinguere da dove arriva ogni copia (come su Android).</summary>
    private static Windows.UI.Color DeviceTone(string id)
    {
        uint[] p = { 0xFF5C9DFF, 0xFF34C7A5, 0xFFF0A54A, 0xFFB38CFF, 0xFFFF7AA8, 0xFF7BD66B };
        var h = 0;
        foreach (var ch in id) h = unchecked(h * 31 + ch);
        var v = id == "me" ? p[0] : p[(h & 0x7fffffff) % (p.Length - 1) + 1];
        return Windows.UI.Color.FromArgb(255, (byte)(v >> 16), (byte)(v >> 8), (byte)v);
    }

    private static string Clock(TimeSpan t) => $"{(int)t.TotalMinutes}:{t.Seconds:00}";
}
