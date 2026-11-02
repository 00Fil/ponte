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

    private void StGo(string? sub)
    {
        _stSub = sub;
        RenderStudy();
        foreach (var el in _hosts[_tab].Children.Take(3)) Enter(el, sub == null ? -12 : 12, 0, 320);
    }

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

    private void RenderStudy()
    {
        var h = Reset("Studio");
        var p = _r.Plan.State;
        switch (_stSub)
        {
            case "subjects": StSubjects(h, p); break;
            case "tasks": StTasks(h, p); break;
            case "exams": StExams(h, p); break;
            case "cards": StCards(h, p); break;
            case "review": StReview(h, p); break;
            case "stats": StStats(h, p); break;
            case "set": StSettings(h, p); break;
            default: StDashboard(h, p); break;
        }
    }

    private void StBack(StackPanel h, string title, string? to = null, string parent = "Studio") => h.Children.Add(BackRow(parent, title, () => StGo(to)));

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

        var acts = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 24, HorizontalAlignment = HorizontalAlignment.Center };
        acts.Children.Add(ActionBubble("", "Materie", false, () => { StGo("subjects"); return Task.CompletedTask; }));
        acts.Children.Add(ActionBubble("", "Compiti", true, () => { StGo("tasks"); return Task.CompletedTask; }));
        acts.Children.Add(ActionBubble("", "Esami", false, () => { StGo("exams"); return Task.CompletedTask; }));
        acts.Children.Add(ActionBubble("", "Flashcard", false, () => { StGo("cards"); return Task.CompletedTask; }));
        acts.Children.Add(ActionBubble("", "Dati", false, () => { StGo("stats"); return Task.CompletedTask; }));
        h.Children.Add(acts);

        var soon = p.Exams.Where(e => Loc(e.Date).Date >= DateTime.Today).Take(3).ToList();
        var open = p.Tasks.Where(t => !t.Done).OrderBy(t => t.Due == 0 ? long.MaxValue : t.Due).Take(4).ToList();
        if (soon.Count > 0 || open.Count > 0)
        {
            var c = new StackPanel { Spacing = 10 };
            if (soon.Count > 0)
            {
                c.Children.Add(T("Esami in arrivo", 17, "LabelPrimary", true));
                foreach (var e in soon) c.Children.Add(SubLine(p, e.Subject, e.Title, DaysTo(e.Date) + " · " + Loc(e.Date).ToString("d MMM HH:mm", It)));
            }
            if (open.Count > 0)
            {
                c.Children.Add(T("Da fare", 17, "LabelPrimary", true));
                foreach (var t in open) c.Children.Add(SubLine(p, t.Subject, t.Title, t.Due > 0 ? "Scadenza " + DaysTo(t.Due) : "Senza scadenza"));
            }
            h.Children.Add(Widget(c));
        }
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
        if (_stSubject.Length == 0 && t.Subject.Length > 0 && !t.Running) _stSubject = t.Subject;

        var c = new StackPanel { Spacing = 14 };
        var head = new Grid();
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.Children.Add(T(t.Phase switch { "short" => "Pausa", "long" => "Pausa lunga", _ => "Concentrazione" }, 15, tint, true));
        var dur = Btn("Durate", () => StGo("set"));
        Grid.SetColumn(dur, 1); head.Children.Add(dur);
        c.Children.Add(head);

        // anello
        const double Size = 232, W = 14, R = (Size - W) / 2;
        var ring = new Grid { Width = Size, Height = Size, HorizontalAlignment = HorizontalAlignment.Center };
        ring.Children.Add(new Ellipse { Width = Size - W, Height = Size - W, Stroke = B("BgSunken"), StrokeThickness = W });
        var arc = new Path { Stroke = B(tint), StrokeThickness = W, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, Width = Size, Height = Size };
        ring.Children.Add(arc);
        var inner = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
        var clock = T("00:00", 52, "LabelPrimary", true);
        clock.HorizontalAlignment = HorizontalAlignment.Center;
        var sub = T(t.Running && !mine ? "su un altro dispositivo" : p.Subjects.FirstOrDefault(x => x.Id == _stSubject)?.Name ?? "Nessuna materia", 12, "LabelTertiary");
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
            if (frac < 0.002) { arc.Data = null; return; }
            frac = Math.Min(frac, 0.9999);
            var ang = frac * Math.PI * 2;
            var cx = Size / 2; var cy = Size / 2;
            var fig = new PathFigure { StartPoint = new Windows.Foundation.Point(cx, cy - R), IsClosed = false };
            fig.Segments.Add(new ArcSegment { Point = new Windows.Foundation.Point(cx + R * Math.Sin(ang), cy - R * Math.Cos(ang)), Size = new Windows.Foundation.Size(R, R), SweepDirection = SweepDirection.Clockwise, IsLargeArc = frac > 0.5 });
            var geo = new PathGeometry(); geo.Figures.Add(fig);
            arc.Data = geo;
        }
        Update();
        EnsureTicks();
        _ticks.Add(Update);

        var dots = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, HorizontalAlignment = HorizontalAlignment.Center };
        for (var i = 0; i < rounds; i++)
            dots.Children.Add(new Ellipse { Width = 8, Height = 8, Fill = i < t.Cycle % rounds || (t.Phase == "long" && t.Cycle > 0) ? B(tint) : B("BgSunken") });
        c.Children.Add(dots);

        if (focus && !t.Running)
        {
            var chips = new List<UIElement> { Chip("Nessuna", _stSubject.Length == 0, () => { _stSubject = ""; RenderStudy(); }) };
            chips.AddRange(p.Subjects.Select(s => (UIElement)Chip(s.Name, _stSubject == s.Id, () => { _stSubject = s.Id; RenderStudy(); })));
            c.Children.Add(ChipScroll(chips));
        }

        var btns = new Grid { ColumnSpacing = 12 };
        btns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        btns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var main = t.Running ? Btn("Ferma", () => _r.Plan.TimerStop()) : Btn("Avvia", () => _r.Plan.TimerStart(_stSubject), "PrimaryButton");
        main.HorizontalAlignment = HorizontalAlignment.Stretch;
        var skip = Btn(focus ? "Salta alla pausa" : "Salta la pausa", () => _r.Plan.TimerPhase(focus ? ((t.Cycle + 1) % rounds == 0 ? "long" : "short") : "focus"));
        skip.HorizontalAlignment = HorizontalAlignment.Stretch;
        Grid.SetColumn(skip, 1);
        btns.Children.Add(main); btns.Children.Add(skip);
        c.Children.Add(btns);
        return Widget(c, null, new Thickness(20));
    }

    // ---------------------------------------------------------------- moduli comuni
    private UIElement SubjectChips(PlanState p, Func<string> get, Action<string> set, string none)
    {
        var chips = new List<UIElement> { Chip(none, get().Length == 0, () => { set(""); RenderStudy(); }) };
        chips.AddRange(p.Subjects.Select(s => (UIElement)Chip(s.Name, get() == s.Id, () => { set(s.Id); RenderStudy(); })));
        return ChipScroll(chips);
    }

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

    // ---------------------------------------------------------------- materie
    private void StSubjects(StackPanel h, PlanState p)
    {
        StBack(h, "Materie");
        var f = new StackPanel { Spacing = 12 };
        f.Children.Add(Field("Nuova materia", Entry(_fTitle, "Es. Analisi 1", v => _fTitle = v)));
        f.Children.Add(ColorDots(_fColor % PlanColors.Length, i => { _fColor = i; RenderStudy(); }));
        var add = Btn("Aggiungi", () => { if (_fTitle.Trim().Length == 0) return; _r.Plan.SaveSubject(new Subject("", _fTitle.Trim(), _fColor % PlanColors.Length)); _fTitle = ""; _fColor = p.Subjects.Count + 1; }, "PrimaryButton");
        add.HorizontalAlignment = HorizontalAlignment.Stretch;
        f.Children.Add(add);
        h.Children.Add(Widget(f));
        if (p.Subjects.Count == 0) h.Children.Add(T("Aggiungi le materie: servono a dividere le ore di studio, i compiti e le flashcard.", 12, "LabelTertiary"));
        var from = DateTime.Today.AddDays(-6);
        foreach (var s in p.Subjects)
        {
            var week = p.Sessions.Where(x => x.Subject == s.Id && Loc(x.Start) >= from).Sum(x => x.Minutes);
            var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            col.Children.Add(T(s.Name, 15, "LabelPrimary", true)); col.Children.Add(T("Ultimi 7 giorni: " + Hm(week), 12, "LabelTertiary"));
            var row = new Grid { ColumnSpacing = 12 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.Children.Add(new Ellipse { Width = 14, Height = 14, Fill = Sb(PColor(s.Color)) });
            Grid.SetColumn(col, 1); row.Children.Add(col);
            var x = Cross(() => _r.Plan.RemoveSubject(s.Id)); Grid.SetColumn(x, 2); row.Children.Add(x);
            h.Children.Add(ListItem(row));
        }
    }

    // ---------------------------------------------------------------- compiti
    private void StTasks(StackPanel h, PlanState p)
    {
        StBack(h, "Compiti");
        var f = new StackPanel { Spacing = 12 };
        f.Children.Add(Field("Nuovo compito", Entry(_fTitle, "Es. Esercizi capitolo 4", v => _fTitle = v)));
        if (p.Subjects.Count > 0) f.Children.Add(SubjectChips(p, () => _fSubject, v => _fSubject = v, "Nessuna materia"));
        var due = new CalendarDatePicker { PlaceholderText = "Nessuna", FirstDayOfWeek = Windows.Globalization.DayOfWeek.Monday, Date = _fDue > 0 ? new DateTimeOffset(Loc(_fDue).Date) : null };
        due.DateChanged += (_, a) => _fDue = a.NewDate is { } d ? Ms(d.Date) : 0;
        f.Children.Add(Field("Scadenza", due));
        var add = Btn("Aggiungi", () => { if (_fTitle.Trim().Length == 0) return; _r.Plan.SaveTask(new StudyTask("", _fTitle.Trim(), _fSubject, _fDue, false)); _fTitle = ""; _fDue = 0; }, "PrimaryButton");
        add.HorizontalAlignment = HorizontalAlignment.Stretch;
        f.Children.Add(add);
        h.Children.Add(Widget(f));
        if (p.Tasks.Count == 0) h.Children.Add(T("Nessun compito. Quelli con scadenza compaiono anche nell'Agenda.", 12, "LabelTertiary"));
        foreach (var t in p.Tasks.OrderBy(t => t.Done).ThenBy(t => t.Due == 0 ? long.MaxValue : t.Due))
        {
            var s = p.Subjects.FirstOrDefault(x => x.Id == t.Subject);
            var row = new Grid { ColumnSpacing = 12 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var box = new Border { Width = 24, Height = 24, CornerRadius = new CornerRadius(12), Background = t.Done ? B("AccentDefault") : B("BgSunken"), VerticalAlignment = VerticalAlignment.Center };
            if (t.Done) box.Child = new TextBlock { Text = "✓", Foreground = Sb(Microsoft.UI.Colors.White), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, FontSize = 13 };
            row.Children.Add(box);
            var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            var title = T(t.Title, 15, t.Done ? "LabelTertiary" : "LabelPrimary"); title.TextWrapping = TextWrapping.Wrap;
            if (t.Done) title.TextDecorations = Windows.UI.Text.TextDecorations.Strikethrough;
            col.Children.Add(title);
            var over = !t.Done && t.Due > 0 && Loc(t.Due).Date < DateTime.Today;
            col.Children.Add(T(string.Join(" · ", new[] { s?.Name, t.Due > 0 ? DaysTo(t.Due) : null }.Where(x => !string.IsNullOrEmpty(x))), 12, over ? "CriticalOnSubtle" : "LabelTertiary"));
            Grid.SetColumn(col, 1); row.Children.Add(col);
            var x = Cross(() => _r.Plan.RemoveTask(t.Id)); Grid.SetColumn(x, 2); row.Children.Add(x);
            h.Children.Add(ListItem(row, () => _r.Plan.SaveTask(t with { Done = !t.Done })));
        }
    }

    // ---------------------------------------------------------------- esami
    private void StExams(StackPanel h, PlanState p)
    {
        StBack(h, "Esami");
        var f = new StackPanel { Spacing = 12 };
        f.Children.Add(Field("Nuovo esame", Entry(_fTitle, "Es. Scritto di Fisica", v => _fTitle = v)));
        if (p.Subjects.Count > 0) f.Children.Add(SubjectChips(p, () => _fSubject, v => _fSubject = v, "Nessuna materia"));
        var day = new CalendarDatePicker { Date = new DateTimeOffset(_fExam.Date), FirstDayOfWeek = Windows.Globalization.DayOfWeek.Monday };
        day.DateChanged += (_, a) => { if (a.NewDate is { } d) _fExam = d.Date + _fExam.TimeOfDay; };
        var time = new TimePicker { Time = _fExam.TimeOfDay, ClockIdentifier = "24HourClock", MinuteIncrement = 5 };
        time.TimeChanged += (_, a) => _fExam = _fExam.Date + a.NewTime;
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 14 };
        row.Children.Add(Field("Giorno", day)); row.Children.Add(Field("Ora", time));
        f.Children.Add(row);
        var add = Btn("Aggiungi", () => { if (_fTitle.Trim().Length == 0) return; _r.Plan.SaveExam(new Exam("", _fTitle.Trim(), _fSubject, Ms(_fExam))); _fTitle = ""; }, "PrimaryButton");
        add.HorizontalAlignment = HorizontalAlignment.Stretch;
        f.Children.Add(add);
        h.Children.Add(Widget(f));
        if (p.Exams.Count == 0) h.Children.Add(T("Gli esami compaiono nell'Agenda e ti avvisano la sera prima.", 12, "LabelTertiary"));
        foreach (var e in p.Exams)
        {
            var s = p.Subjects.FirstOrDefault(x => x.Id == e.Subject);
            var n = (Loc(e.Date).Date - DateTime.Today).Days;
            var row2 = new Grid { ColumnSpacing = 12 };
            row2.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row2.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row2.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var badge = new StackPanel { Width = 52, Height = 52, VerticalAlignment = VerticalAlignment.Center };
            badge.Children.Add(T(n < 0 ? "–" : n.ToString(), 22, n < 0 ? "LabelTertiary" : "AccentDefault", true));
            badge.Children.Add(T("giorni", 10, "LabelTertiary"));
            row2.Children.Add(new Border { Width = 56, Height = 56, CornerRadius = new CornerRadius(14), Background = n < 0 ? B("BgSunken") : B("AccentSubtle"), Child = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center, Children = { CenterT(n < 0 ? "–" : n.ToString(), 22, n < 0 ? "LabelTertiary" : "AccentDefault", true), CenterT("giorni", 10, "LabelTertiary", false) } } });
            var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            col.Children.Add(T(e.Title, 15, "LabelPrimary", true));
            col.Children.Add(T(string.Join(" · ", new[] { s?.Name, Loc(e.Date).ToString("ddd d MMM · HH:mm", It) }.Where(x => !string.IsNullOrEmpty(x))), 12, "LabelTertiary"));
            Grid.SetColumn(col, 1); row2.Children.Add(col);
            var x = Cross(() => _r.Plan.RemoveExam(e.Id)); Grid.SetColumn(x, 2); row2.Children.Add(x);
            h.Children.Add(ListItem(row2));
        }
    }

    private TextBlock CenterT(string text, double size, string color, bool medium)
    {
        var t = T(text, size, color, medium);
        t.HorizontalAlignment = HorizontalAlignment.Center;
        return t;
    }

    // ---------------------------------------------------------------- flashcard
    private void StCards(StackPanel h, PlanState p)
    {
        StBack(h, "Flashcard");
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var due = p.Cards.Where(k => k.Due <= now && (_fSubject.Length == 0 || k.Subject == _fSubject)).ToList();
        var top = new StackPanel { Spacing = 12 };
        top.Children.Add(T($"{p.Cards.Count} carte · {p.Cards.Count(k => k.Due <= now)} da ripassare", 15, "LabelPrimary", true));
        if (p.Subjects.Count > 0) top.Children.Add(SubjectChips(p, () => _fSubject, v => _fSubject = v, "Tutte"));
        var go = Btn(due.Count == 0 ? "Niente da ripassare" : $"Ripassa {due.Count}", () =>
        {
            if (due.Count == 0) return;
            _queue = due.OrderBy(_ => Guid.NewGuid()).Select(k => k.Id).ToList(); _shown = false; _known = 0; StGo("review");
        }, "PrimaryButton");
        go.HorizontalAlignment = HorizontalAlignment.Stretch;
        go.IsEnabled = due.Count > 0;
        top.Children.Add(go);
        h.Children.Add(Widget(top));

        var f = new StackPanel { Spacing = 12 };
        f.Children.Add(Field("Domanda", Entry(_fQ, "Cosa dice il teorema di…?", v => _fQ = v)));
        f.Children.Add(Field("Risposta", Entry(_fA, "La risposta", v => _fA = v)));
        var add = Btn("Aggiungi carta", () => { if (_fQ.Trim().Length == 0 || _fA.Trim().Length == 0) return; _r.Plan.SaveCard(new StudyCard("", _fSubject, _fQ.Trim(), _fA.Trim(), 0, 0)); _fQ = ""; _fA = ""; });
        add.HorizontalAlignment = HorizontalAlignment.Stretch;
        f.Children.Add(add);
        h.Children.Add(Widget(f));

        foreach (var k in p.Cards.Where(k => _fSubject.Length == 0 || k.Subject == _fSubject))
        {
            var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            var q = T(k.Q, 15); q.TextWrapping = TextWrapping.Wrap; q.MaxLines = 2;
            col.Children.Add(q); col.Children.Add(T(k.A, 12, "LabelTertiary"));
            var right = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            right.Children.Add(new TextBlock { Text = "Livello " + k.Box, FontSize = 11, Foreground = B("LabelTertiary"), VerticalAlignment = VerticalAlignment.Center });
            right.Children.Add(Cross(() => _r.Plan.RemoveCard(k.Id)));
            h.Children.Add(ListItem(Pair(col, right)));
        }
    }

    private void StReview(StackPanel h, PlanState p)
    {
        StBack(h, "Ripasso", "cards", "Flashcard");
        var card = _queue.Count > 0 ? p.Cards.FirstOrDefault(k => k.Id == _queue[0]) : null;
        if (card == null && _queue.Count > 0) { _queue.RemoveAt(0); RenderStudy(); return; }
        if (card == null)
        {
            var d = new StackPanel { Spacing = 12 };
            d.Children.Add(T("Ripasso finito", 22, "LabelPrimary", true));
            d.Children.Add(T($"Ne hai sapute {_known}. Le altre torneranno presto.", 13, "LabelSecondary"));
            var back = Btn("Torna alle carte", () => StGo("cards"), "PrimaryButton");
            back.HorizontalAlignment = HorizontalAlignment.Stretch;
            d.Children.Add(back);
            h.Children.Add(Widget(d));
            return;
        }
        void Answer(int level)
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var box = level == 0 ? 0 : level == 1 ? card.Box : Math.Min(card.Box + 1, BoxDays.Length - 1);
            var next = level == 0 ? now + 5 * 60_000L : level == 1 ? now + Math.Max(BoxDays[box], 1) * 86_400_000L / 2 : now + BoxDays[box] * 86_400_000L;
            _r.Plan.SaveCard(card with { Box = box, Due = next });
            if (level == 2) _known++;
            _queue.RemoveAt(0);
            if (level == 0) _queue.Add(card.Id);
            _shown = false;
            RenderStudy();
        }
        h.Children.Add(T($"Restano {_queue.Count}", 12, "LabelTertiary"));
        var c = new StackPanel { Spacing = 14 };
        c.Children.Add(T(p.Subjects.FirstOrDefault(s => s.Id == card.Subject)?.Name ?? "Domanda", 12, "LabelTertiary"));
        var q = T(card.Q, 22, "LabelPrimary", true); q.TextWrapping = TextWrapping.Wrap;
        c.Children.Add(q);
        if (_shown)
        {
            c.Children.Add(Divider());
            var a = T(card.A, 16); a.TextWrapping = TextWrapping.Wrap;
            c.Children.Add(a);
        }
        h.Children.Add(Widget(c, null, new Thickness(20)));
        if (!_shown)
        {
            var show = Btn("Mostra la risposta", () => { _shown = true; RenderStudy(); }, "PrimaryButton");
            show.HorizontalAlignment = HorizontalAlignment.Stretch;
            h.Children.Add(show);
        }
        else
        {
            var row = new Grid { ColumnSpacing = 8 };
            for (var i = 0; i < 3; i++) row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var names = new[] { "Non la so", "Quasi", "Facile" };
            for (var i = 0; i < 3; i++)
            {
                var lv = i;
                var b = Btn(names[i], () => Answer(lv), i == 2 ? "PrimaryButton" : "SecondaryButton");
                b.HorizontalAlignment = HorizontalAlignment.Stretch;
                Grid.SetColumn(b, i); row.Children.Add(b);
            }
            h.Children.Add(row);
        }
    }

    // ---------------------------------------------------------------- statistiche
    private void StStats(StackPanel h, PlanState p)
    {
        StBack(h, "Statistiche");
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

        var by = p.Sessions.Where(s => Loc(s.Start) >= today.AddDays(-6)).GroupBy(s => s.Subject).Select(g => (Id: g.Key, Min: g.Sum(s => s.Minutes))).OrderByDescending(x => x.Min).ToList();
        if (by.Count > 0)
        {
            var sc = new StackPanel { Spacing = 10 };
            sc.Children.Add(T("Per materia (7 giorni)", 17, "LabelPrimary", true));
            var max = Math.Max(by[0].Min, 1);
            foreach (var (id, min) in by)
            {
                var s = p.Subjects.FirstOrDefault(x => x.Id == id);
                var row = new StackPanel { Spacing = 4 };
                row.Children.Add(Pair(T(s?.Name ?? "Senza materia", 13, "LabelSecondary"), T(Hm(min), 13, "LabelTertiary")));
                var bar = Bar((double)min / max, "AccentDefault", 8);
                if (s != null && bar.Child is Grid g && g.Children[0] is Border fill) fill.Background = Sb(PColor(s.Color));
                row.Children.Add(bar);
                sc.Children.Add(row);
            }
            h.Children.Add(Widget(sc));
        }
        var tot = new StackPanel();
        tot.Children.Add(T("Totale", 12, "LabelTertiary"));
        tot.Children.Add(T($"{Hm(p.Sessions.Sum(s => s.Minutes))} in {p.Sessions.Count} pomodori", 15, "LabelPrimary", true));
        h.Children.Add(Widget(tot));
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
        StBack(h, "Impostazioni");
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
