using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace Ponte;

/// <summary>
/// Studio come su Android: pomodoro (anello, materia, durate), obiettivo di oggi e serie, materie, compiti con scadenza,
/// esami con conto alla rovescia, flashcard a ripetizione dilazionata, statistiche. Gli stessi dati di Android, sincronizzati.
/// </summary>
public sealed partial class MainWindow
{
    private string? _stSub;
    private string _stSubject = "";
    private static readonly int[] BoxDays = { 0, 1, 3, 7, 14, 30 };
    // moduli
    private string _fTitle = "", _fQ = "", _fA = "", _fSubject = "";
    private int _fColor;
    private long _fDue;
    private DateTime _fExam = DateTime.Today.AddDays(7).AddHours(9);
    // ripasso
    private List<string> _queue = new();
    private bool _shown;
    private int _known;

    private static string Hm(int min) => min >= 60 ? $"{min / 60} h {min % 60} min" : $"{min} min";
    private static int MinutesOn(PlanState p, DateTime from, DateTime to) => p.Sessions.Where(s => Loc(s.Start) >= from && Loc(s.Start) < to).Sum(s => s.Minutes);
    private static int Streak(PlanState p)
    {
        var days = p.Sessions.Select(s => Loc(s.Start).Date).ToHashSet();
        var d = DateTime.Today;
        if (!days.Contains(d)) d = d.AddDays(-1);
        var n = 0;
        while (days.Contains(d)) { n++; d = d.AddDays(-1); }
        return n;
    }
    private static string DaysTo(long ms)
    {
        var n = (Loc(ms).Date - DateTime.Today).Days;
        return n < 0 ? "passato" : n == 0 ? "oggi" : n == 1 ? "domani" : $"tra {n} giorni";
    }

    private void StBack(StackPanel h, string title, string? to = null, string parent = "Studio") => h.Children.Add(BackRow(parent, title, () => AgGo(null)));

    // ---------------------------------------------------------------- cruscotto
    private void StDashboard(StackPanel h, PlanState p)
    {
        h.Children.Add(Pomodoro(p));
        var today = MinutesOn(p, DateTime.Today, DateTime.MaxValue);
        var goal = p.Get("goal", 120);
        var tc = new StackPanel { Spacing = 10 };
        var hd = new Grid();
        hd.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        hd.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var l = new StackPanel(); l.Children.Add(T("Oggi", 12, "LabelTertiary")); l.Children.Add(T(Hm(today), 22, "LabelPrimary", true));
        var r = new StackPanel { HorizontalAlignment = HorizontalAlignment.Right }; r.Children.Add(T("Serie", 12, "LabelTertiary")); var s = Streak(p); r.Children.Add(T(s == 1 ? "1 giorno" : s + " giorni", 22, "LabelPrimary", true));
        hd.Children.Add(l); Grid.SetColumn(r, 1); hd.Children.Add(r);
        tc.Children.Add(hd);
        tc.Children.Add(Bar(Math.Min(1.0, (double)today / goal), today >= goal ? "PositiveOnSubtle" : "AccentDefault", 8));
        tc.Children.Add(T("Obiettivo " + Hm(goal) + (today >= goal ? " · raggiunto" : ""), 12, "LabelTertiary"));
        h.Children.Add(Widget(tc));
        StStatsBody(h, p);
    }

    private Grid SubLine(PlanState p, string subject, string title, string note)
    {
        var s = p.Subjects.FirstOrDefault(x => x.Id == subject);
        var g = new Grid { ColumnSpacing = 12 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.Children.Add(new Ellipse { Width = 10, Height = 10, Fill = s != null ? Sb(PColor(s.Color)) : B("LabelTertiary"), VerticalAlignment = VerticalAlignment.Center });
        var col = new StackPanel(); col.Children.Add(T(title, 15)); col.Children.Add(T(note, 12, "LabelTertiary"));
        Grid.SetColumn(col, 1); g.Children.Add(col);
        return g;
    }

    private Border Bar(double frac, string color, double height)
    {
        var track = new Grid { Height = height };
        var outer = new Border { Background = B("BgSunken"), CornerRadius = new CornerRadius(height / 2), Child = track };
        var fill = new Border { Background = B(color), CornerRadius = new CornerRadius(height / 2), HorizontalAlignment = HorizontalAlignment.Left };
        track.Children.Add(fill);
        outer.SizeChanged += (_, e) => fill.Width = Math.Max(height, e.NewSize.Width * Math.Clamp(frac, 0, 1));
        return outer;
    }

    // ---------------------------------------------------------------- pomodoro
    private Border Pomodoro(PlanState p)
    {
        var t = p.Timer;
        var focus = t.Phase == "focus";
        var tint = focus ? "AccentDefault" : "PositiveOnSubtle";
        var rounds = p.Get("rounds", 4);
        var total = (t.Phase switch { "short" => p.Get("short", 5), "long" => p.Get("long", 15), _ => p.Get("focus", 25) }) * 60_000L;
        var mine = t.Device.Length == 0 || t.Device == _r.S.DeviceId;

        var c = new StackPanel { Spacing = 14 };
        var head = new Grid();
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.Children.Add(T("Studio", 15, "LabelSecondary", true));
        var dur = Btn("Durate", () => AgGo("set"));
        Grid.SetColumn(dur, 1); head.Children.Add(dur);
        c.Children.Add(head);

        // anello
        const double Size = 264, W = 10, R = (Size - W) / 2;
        var ring = new Grid { Width = Size, Height = Size, HorizontalAlignment = HorizontalAlignment.Center };
        ring.Children.Add(new Ellipse { Width = Size - W, Height = Size - W, Stroke = Sb(WithA(C("LabelPrimary"), 18)), StrokeThickness = W });
        var glow = new Microsoft.UI.Xaml.Shapes.Path { Stroke = Sb(WithA(C(tint), 56)), StrokeThickness = W + 10, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, Width = Size, Height = Size };
        ring.Children.Add(glow);
        var arc = new Microsoft.UI.Xaml.Shapes.Path { Stroke = B(tint), StrokeThickness = W, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, Width = Size, Height = Size };
        ring.Children.Add(arc);
        var inner = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
        var clock = T("00:00", 64, "LabelPrimary", false);
        clock.HorizontalAlignment = HorizontalAlignment.Center;
        var sub = T(t.Running && !mine ? "su un altro dispositivo" : t.Phase switch { "short" => "Pausa", "long" => "Pausa lunga", _ => "Concentrazione" }, 12, tint);
        sub.CharacterSpacing = 120;
        sub.HorizontalAlignment = HorizontalAlignment.Center;
        inner.Children.Add(clock); inner.Children.Add(sub);
        ring.Children.Add(inner);
        c.Children.Add(ring);

        void Update()
        {
            var st = _r.Plan.State.Timer;
            var left = st.Running ? Math.Clamp(st.EndAt - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), 0, total) : total;
            if (st.Running && left <= 0) { _r.Plan.TimerDone(); return; }
            var secs = (int)((left + 999) / 1000);
            clock.Text = $"{secs / 60:00}:{secs % 60:00}";
            var frac = st.Running ? 1.0 - (double)left / total : 0;
            if (frac < 0.002) { arc.Data = null; glow.Data = null; return; }
            frac = Math.Min(frac, 0.9999);
            var ang = frac * Math.PI * 2;
            var cx = Size / 2; var cy = Size / 2;
            var fig = new PathFigure { StartPoint = new Windows.Foundation.Point(cx, cy - R), IsClosed = false };
            fig.Segments.Add(new ArcSegment { Point = new Windows.Foundation.Point(cx + R * Math.Sin(ang), cy - R * Math.Cos(ang)), Size = new Windows.Foundation.Size(R, R), SweepDirection = SweepDirection.Clockwise, IsLargeArc = frac > 0.5 });
            var geo = new PathGeometry(); geo.Figures.Add(fig);
            arc.Data = geo;
            glow.Data = geo;
        }
        Update();
        EnsureTicks();
        _ticks.Add(Update);

        var dots = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, HorizontalAlignment = HorizontalAlignment.Center };
        for (var i = 0; i < rounds; i++)
            dots.Children.Add(new Ellipse { Width = 8, Height = 8, Fill = i < t.Cycle % rounds || (t.Phase == "long" && t.Cycle > 0) ? B(tint) : B("BgSunken") });
        c.Children.Add(dots);


        var btns = new Grid { ColumnSpacing = 12 };
        btns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        btns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var main = t.Running ? Btn("Ferma", () => _r.Plan.TimerStop()) : Btn("Avvia", () => _r.Plan.TimerStart(""), "PrimaryButton");
        main.HorizontalAlignment = HorizontalAlignment.Stretch; main.Height = 52;
        var skip = Btn("Salta", () => _r.Plan.TimerPhase(focus ? ((t.Cycle + 1) % rounds == 0 ? "long" : "short") : "focus"));
        skip.HorizontalAlignment = HorizontalAlignment.Stretch; skip.Height = 52;
        Grid.SetColumn(skip, 1);
        btns.Children.Add(main); btns.Children.Add(skip);
        c.Children.Add(btns);
        if (t.Running && focus)
        {
            var song = Btn("⏭  +1:00", () => { _r.Np.Next(); _r.Plan.TimerPenalty(60_000); });
            song.HorizontalAlignment = HorizontalAlignment.Center;
            c.Children.Add(song);
        }
        var card = Widget(c, null, new Thickness(24));
        card.CornerRadius = new CornerRadius(36);
        return card;
    }

    // ---------------------------------------------------------------- moduli comuni
    private TextBox Entry(string value, string placeholder, Action<string> set)
    {
        var tb = new TextBox { Text = value, PlaceholderText = placeholder };
        tb.TextChanged += (_, _) => set(tb.Text);
        return tb;
    }

    private Border ListItem(UIElement content, Action? tap = null)
    {
        var w = Widget(content, null, new Thickness(12));
        w.CornerRadius = new CornerRadius(16);
        if (tap != null) { w.Tapped += (_, _) => tap(); Bounce(w, 1.02f); }
        return w;
    }

    private Button Cross(Action click)
    {
        var b = Btn("×", click, "ChipButton");
        b.VerticalAlignment = VerticalAlignment.Center;
        return b;
    }

    private Grid Pair(UIElement left, UIElement? right)
    {
        var g = new Grid { ColumnSpacing = 12 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.Children.Add(left);
        if (right != null) { Grid.SetColumn((FrameworkElement)right, 1); g.Children.Add(right); }
        return g;
    }

    // ---------------------------------------------------------------- statistiche
    private void StStatsBody(StackPanel h, PlanState p)
    {
        var today = DateTime.Today;
        var week = Enumerable.Range(0, 7).Select(i => today.AddDays(i - 6)).ToList();
        var per = week.Select(d => MinutesOn(p, d, d.AddDays(1))).ToList();
        var c = new StackPanel { Spacing = 14 };
        var kp = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 32 };
        foreach (var (l, v) in new[] { ("Oggi", Hm(per[6])), ("7 giorni", Hm(per.Sum())), ("Serie", Streak(p) + " g") })
        { var col = new StackPanel(); col.Children.Add(T(l, 12, "LabelTertiary")); col.Children.Add(T(v, 20, "LabelPrimary", true)); kp.Children.Add(col); }
        c.Children.Add(kp);
        var goal = p.Get("goal", 120);
        var top = Math.Max(Math.Max(per.Max(), goal), 1);
        var bars = new Grid { Height = 130, ColumnSpacing = 10 };
        for (var i = 0; i < 7; i++)
        {
            bars.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var bar = new Border { VerticalAlignment = VerticalAlignment.Bottom, Height = Math.Max(3, 130.0 * per[i] / top), CornerRadius = new CornerRadius(6), Background = B("AccentDefault"), Opacity = i == 6 ? 1 : 0.45 };
            Grid.SetColumn(bar, i); bars.Children.Add(bar);
        }
        var line = new Border { Height = 1, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 130.0 * goal / top), Background = B("LabelTertiary"), Opacity = 0.5, IsHitTestVisible = false };
        Grid.SetColumnSpan(line, 7); bars.Children.Add(line);
        c.Children.Add(bars);
        var days = new Grid { ColumnSpacing = 10 };
        for (var i = 0; i < 7; i++)
        {
            days.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var t = T(week[i].ToString("ddd", It)[..1].ToUpperInvariant(), 12, "LabelTertiary"); t.HorizontalAlignment = HorizontalAlignment.Center;
            Grid.SetColumn(t, i); days.Children.Add(t);
        }
        c.Children.Add(days);
        h.Children.Add(Widget(c, null, new Thickness(20)));


    }

    // ---------------------------------------------------------------- impostazioni
    private Grid Stepper(string label, int value, string unit, int min, int max, int step, Action<int> set)
    {
        var g = new Grid { ColumnSpacing = 8, MinHeight = 44 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(96) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var l = T(label, 15); l.VerticalAlignment = VerticalAlignment.Center;
        g.Children.Add(l);
        var m = Btn("−", () => set(Math.Max(min, value - step))); Grid.SetColumn(m, 1); g.Children.Add(m);
        var v = T((value + " " + unit).Trim(), 15, "LabelPrimary", true); v.HorizontalAlignment = HorizontalAlignment.Center; v.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(v, 2); g.Children.Add(v);
        var pl = Btn("+", () => set(Math.Min(max, value + step))); Grid.SetColumn(pl, 3); g.Children.Add(pl);
        return g;
    }

    private void StSettings(StackPanel h, PlanState p)
    {
        StBack(h, "Pomodoro", null, "Agenda");
        var c = new StackPanel { Spacing = 6 };
        c.Children.Add(Stepper("Concentrazione", p.Get("focus", 25), "min", 5, 90, 5, v => _r.Plan.SetCfg("focus", v)));
        c.Children.Add(Stepper("Pausa", p.Get("short", 5), "min", 1, 30, 1, v => _r.Plan.SetCfg("short", v)));
        c.Children.Add(Stepper("Pausa lunga", p.Get("long", 15), "min", 5, 60, 5, v => _r.Plan.SetCfg("long", v)));
        c.Children.Add(Stepper("Pomodori per la pausa lunga", p.Get("rounds", 4), "", 2, 8, 1, v => _r.Plan.SetCfg("rounds", v)));
        c.Children.Add(Stepper("Obiettivo di oggi", p.Get("goal", 120), "min", 30, 600, 30, v => _r.Plan.SetCfg("goal", v)));
        h.Children.Add(Widget(c));
        h.Children.Add(T("Le durate valgono dal prossimo pomodoro e sono le stesse su tutti i tuoi dispositivi.", 12, "LabelTertiary"));
    }
}
