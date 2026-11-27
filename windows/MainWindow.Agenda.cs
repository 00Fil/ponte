using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace Ponte;

/// <summary>
/// Agenda come su Android: tasti Cose da fare, pomodoro e Google, mese con puntini e giorno selezionato, editor degli eventi.
/// Google Calendar entra tramite l'indirizzo segreto iCal (sola lettura). Scadenze dei compiti ed esami compaiono in automatico.
/// </summary>
public sealed partial class MainWindow
{
    private static readonly Windows.UI.Color[] PlanColors =
    {
        Windows.UI.Color.FromArgb(255, 0x2F, 0x5F, 0xFF), Windows.UI.Color.FromArgb(255, 0x5D, 0xA8, 0x5B), Windows.UI.Color.FromArgb(255, 0xE8, 0x8A, 0x2E), Windows.UI.Color.FromArgb(255, 0x8E, 0x5B, 0xB5),
        Windows.UI.Color.FromArgb(255, 0xD9, 0x53, 0x4F), Windows.UI.Color.FromArgb(255, 0x2F, 0xB5, 0xC4), Windows.UI.Color.FromArgb(255, 0xD9, 0xB7, 0x3A), Windows.UI.Color.FromArgb(255, 0x9A, 0xA1, 0x9A),
    };
    private static Windows.UI.Color PColor(int i) => PlanColors[((i % PlanColors.Length) + PlanColors.Length) % PlanColors.Length];

    private sealed record AgItem(string Key, int Kind, string Title, DateTime Start, DateTime End, bool AllDay, Windows.UI.Color Color, string Place, bool Done = false, object? Ref = null);

    private sealed class AgDraft
    {
        public string Id = "";
        public string Title = "", Place = "", Note = "";
        public DateTime Start, End;
        public bool AllDay;
        public int Color, Remind = 10;
    }

    private string? _agSub;
    private DateTime _agCursor = new(DateTime.Today.Year, DateTime.Today.Month, 1);
    private DateTime _agSel = DateTime.Today;
    private AgDraft? _agDraft;
    private AgItem? _agDetail;
    private static readonly (int Min, string Label)[] Reminders = { (-1, "Nessuno"), (0, "All'ora"), (10, "10 min prima"), (30, "30 min prima"), (60, "1 ora prima"), (1440, "1 giorno prima") };

    private static DateTime Loc(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
    private static long Ms(DateTime local) => new DateTimeOffset(DateTime.SpecifyKind(local, DateTimeKind.Local)).ToUnixTimeMilliseconds();
    private static string Cap(string s) => s.Length == 0 ? s : char.ToUpper(s[0], It) + s[1..];
    private static string DayLabel(DateTime d)
    {
        var t = DateTime.Today;
        return d.Date == t ? "Oggi" : d.Date == t.AddDays(1) ? "Domani" : d.Date == t.AddDays(-1) ? "Ieri" : Cap(d.ToString("dddd d MMMM", It));
    }

    private void AgGo(string? sub)
    {
        _agSub = sub;
        RenderAgenda();
        foreach (var el in _hosts[_tab].Children.Take(3)) Enter(el, sub == null ? -12 : 12, 0, 320);
    }

    private List<AgItem> AgItems(DateTime from, DateTime to)
    {
        var p = _r.Plan.State;
        var subj = p.Subjects.ToDictionary(s => s.Id);
        var list = new List<AgItem>();
        foreach (var e in p.Events)
        {
            var s = Loc(e.Start); var en = e.AllDay ? Loc(e.End) : Loc(e.End);
            if (s < to && (en > from || s >= from)) list.Add(new AgItem("r" + e.Id, 0, string.IsNullOrWhiteSpace(e.Title) ? "(senza titolo)" : e.Title, s, en, e.AllDay, PColor(e.Color), e.Place, false, e));
        }
        foreach (var e in _r.Plan.AllExternal)
        {
            var s = Loc(e.Start); var en = Loc(e.End);
            if (s < to && en > from) list.Add(new AgItem("g" + e.Start + e.Title, 1, e.Title, s, en, e.AllDay, Argb(255, 0x1A, 0x73, 0xE8), e.Place, false, e));
        }
        foreach (var t in p.Tasks)
        {
            if (t.Due <= 0) continue;
            var d = Loc(t.Due).Date;
            if (d >= from && d < to) list.Add(new AgItem("t" + t.Id, 2, t.Title, d, d.AddDays(1), true, subj.TryGetValue(t.Subject, out var sj) ? PColor(sj.Color) : PColor(7), subj.TryGetValue(t.Subject, out var sj2) ? sj2.Name : "", t.Done, t));
        }
        foreach (var e in p.Exams)
        {
            var d = Loc(e.Date);
            if (d >= from && d < to) list.Add(new AgItem("x" + e.Id, 3, "Esame: " + e.Title, d, d.AddHours(1), false, PColor(4), subj.TryGetValue(e.Subject, out var sj) ? sj.Name : "", false, e));
        }
        return list.OrderBy(i => !i.AllDay).ThenBy(i => i.Start).ToList();
    }

    private static List<AgItem> AgOn(List<AgItem> all, DateTime day) =>
        all.Where(i => i.Start < day.AddDays(1) && (i.End > day || i.Start >= day)).OrderBy(i => !i.AllDay).ThenBy(i => i.Start).ToList();

    private void RenderAgenda()
    {
        var h = Reset("Agenda");
        switch (_agSub)
        {
            case "detail": AgDetail(h); break;
            case "edit": AgEditor(h); break;
            case "ics": AgIcs(h); break;
            case "set": StSettings(h, _r.Plan.State); break;
            case "study":
                h.Children.Add(BackRow("Agenda", "Studio", () => AgGo(null)));
                StDashboard(h, _r.Plan.State);
                break;
            case "todo": RenderTodo(h); break;
            default: AgMain(h); break;
        }
    }

    private void AgMain(StackPanel h)
    {
        var top = HStack(12, AgTodoButton(), AgTomatoButton(), Btn("Google", () => AgGo("ics"), "SecondaryButton"));
        top.HorizontalAlignment = HorizontalAlignment.Right;
        h.Children.Add(top);

        var first = _agCursor;
        var offset = ((int)first.DayOfWeek + 6) % 7;
        var gridStart = first.AddDays(-offset);
        var all = AgItems(gridStart, gridStart.AddDays(42));

        var card = new StackPanel { Spacing = 10 };
        var head = new Grid();
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.Children.Add(BubbleButton(Center(Icons.Of(Ico.Back, B("LabelSecondary"), 18)), 36, () => { _agCursor = _agCursor.AddMonths(-1); RenderAgenda(); }, "Mese precedente"));
        var title = T(Cap(_agCursor.ToString("MMMM yyyy", It)), 20, "LabelPrimary", true);
        title.HorizontalAlignment = HorizontalAlignment.Center; title.VerticalAlignment = VerticalAlignment.Center;
        title.Tapped += (_, _) => { _agCursor = new DateTime(DateTime.Today.Year, DateTime.Today.Month, 1); _agSel = DateTime.Today; RenderAgenda(); };
        Grid.SetColumn(title, 1);
        head.Children.Add(title);
        var next = BubbleButton(Center(Icons.Of(Ico.Forward, B("LabelSecondary"), 18)), 36, () => { _agCursor = _agCursor.AddMonths(1); RenderAgenda(); }, "Mese successivo");
        Grid.SetColumn(next, 2);
        head.Children.Add(next);
        card.Children.Add(head);

        var grid = new Grid { RowSpacing = 2 };
        for (var i = 0; i < 7; i++) grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        for (var i = 0; i < 7; i++) grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var wd = new[] { "L", "M", "M", "G", "V", "S", "D" };
        for (var i = 0; i < 7; i++)
        {
            var t = T(wd[i], 12, "LabelTertiary"); t.HorizontalAlignment = HorizontalAlignment.Center; t.Margin = new Thickness(0, 0, 0, 4);
            Grid.SetColumn(t, i);
            grid.Children.Add(t);
        }
        for (var k = 0; k < 42; k++)
        {
            var day = gridStart.AddDays(k);
            var inMonth = day.Month == _agCursor.Month;
            var sel = day.Date == _agSel.Date;
            var today = day.Date == DateTime.Today;
            var cell = new Border { Height = 52, CornerRadius = new CornerRadius(14), Margin = new Thickness(1), Background = sel ? B("AccentDefault") : Sb(Microsoft.UI.Colors.Transparent) };
            var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Spacing = 3 };
            var num = T(day.Day.ToString(), 15, sel ? "LabelPrimary" : today ? "AccentDefault" : inMonth ? "LabelPrimary" : "LabelDisabled", today);
            num.HorizontalAlignment = HorizontalAlignment.Center;
            if (sel) num.Foreground = Sb(Microsoft.UI.Colors.White);
            col.Children.Add(num);
            var dots = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 3, HorizontalAlignment = HorizontalAlignment.Center, Height = 6 };
            foreach (var c in AgOn(all, day.Date).Select(i => i.Color).Distinct().Take(3))
                dots.Children.Add(new Ellipse { Width = 5, Height = 5, Fill = sel ? Sb(Microsoft.UI.Colors.White) : Sb(c) });
            col.Children.Add(dots);
            cell.Child = col;
            var d = day;
            cell.Tapped += (_, _) => { _agSel = d.Date; if (d.Month != _agCursor.Month) _agCursor = new DateTime(d.Year, d.Month, 1); RenderAgenda(); };
            Bounce(cell, 1.05f);
            Grid.SetColumn(cell, k % 7);
            Grid.SetRow(cell, 1 + k / 7);
            grid.Children.Add(cell);
        }
        card.Children.Add(grid);
        h.Children.Add(Panelled(card, new Thickness(16)));

        var dh = new Grid();
        dh.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        dh.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var dl = new StackPanel();
        dl.Children.Add(T(DayLabel(_agSel), 20, "LabelPrimary", true));
        dl.Children.Add(T(Cap(_agSel.ToString("d MMMM yyyy", It)), 12, "LabelTertiary"));
        dh.Children.Add(dl);
        var add = BubbleButton(Center(Icons.Of(Ico.Plus, White, 20)), 44, () => { AgNew(_agSel); AgGo("edit"); }, "Nuovo evento", Alpha(C("AccentDefault"), 0.85));
        Grid.SetColumn(add, 1);
        dh.Children.Add(add);
        h.Children.Add(dh);

        var list = AgOn(all, _agSel.Date);
        if (list.Count == 0) h.Children.Add(T("Niente in programma", 13, "LabelTertiary"));
        foreach (var i in list) h.Children.Add(AgRow(i));
    }

    /// <summary>Pillola di vetro alta 44 con un segno e, se serve, un numero accanto.</summary>
    private Button AgPill(FrameworkElement icon, string value, Action click, string name)
    {
        icon.VerticalAlignment = VerticalAlignment.Center;
        var row = HStack(8, icon);
        if (value.Length > 0)
        {
            var t = T(value, 16, "LabelPrimary", true);
            t.VerticalAlignment = VerticalAlignment.Center;
            row.Children.Add(t);
        }
        row.HorizontalAlignment = HorizontalAlignment.Center;
        var face = new Border
        {
            Height = 44, MinWidth = 46, CornerRadius = new CornerRadius(22), Padding = new Thickness(11, 0, 11, 0),
            Background = Drop(), BorderBrush = Hairline, BorderThickness = new Thickness(1), Child = row,
        };
        var b = Bare(face, click, name, 22);
        ToolTipService.SetToolTip(b, name);
        Bounce(b, 1.08f);
        return b;
    }

    /// <summary>Spunta e quante cose restano.</summary>
    private Button AgTodoButton()
    {
        var n = _r.Plan.State.Tasks.Count(t => !t.Done);
        return AgPill(Bubble(Icons.Of(Ico.Check, White, 13), 22, C("AccentDefault")), n > 0 ? n.ToString(It) : "", () => AgGo("todo"), "Cose da fare");
    }

    /// <summary>Il pomodoro disegnato: porta allo Studio e col timer acceso mostra il tempo che resta.</summary>
    private Button AgTomatoButton()
    {
        const double w = 22;
        var art = new Canvas { Width = w, Height = w };
        void Dot(double r, double x, double y, Windows.UI.Color color)
        {
            var e = new Ellipse { Width = r * 2, Height = r * 2, Fill = Sb(color) };
            Canvas.SetLeft(e, x - r);
            Canvas.SetTop(e, y - r);
            art.Children.Add(e);
        }
        Dot(w * 0.40, w / 2, w * 0.56, Argb(255, 0xE5, 0x48, 0x3B));
        Dot(w * 0.10, w * 0.36, w * 0.44, Argb(90, 255, 255, 255));
        var leaf = new Polygon { Fill = Sb(Argb(255, 0x3F, 0xAE, 0x5A)) };
        foreach (var (x, y) in new[] { (0.50, 0.22), (0.26, 0.10), (0.42, 0.26), (0.50, 0.04), (0.58, 0.26), (0.76, 0.10) })
            leaf.Points.Add(new Windows.Foundation.Point(w * x, w * y));
        art.Children.Add(leaf);

        var running = _r.Plan.State.Timer.Running;
        var pill = AgPill(art, running ? TimerText() : "", () => AgGo("study"), "Studio");
        if (running && ((Border)pill.Content).Child is StackPanel row && row.Children[^1] is TextBlock clock)
        {
            EnsureTicks();
            _ticks.Add(() => { clock.Text = TimerText(); TimerCheck(); });
        }
        return pill;
    }

    private Border AgRow(AgItem i)
    {
        var g = new Grid { ColumnSpacing = 12 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.Children.Add(new Border { Width = 4, Height = 38, CornerRadius = new CornerRadius(2), Background = Sb(i.Color) });
        var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        var title = T(i.Title, 15, i.Done ? "LabelTertiary" : "LabelPrimary", true);
        if (i.Done) title.TextDecorations = Windows.UI.Text.TextDecorations.Strikethrough;
        col.Children.Add(title);
        var when = i.Kind == 2 ? "Scadenza" : i.AllDay ? "Tutto il giorno" : i.Start.ToString("HH:mm", It) + " – " + i.End.ToString("HH:mm", It);
        if (i.Place.Length > 0) when += " · " + i.Place;
        col.Children.Add(T(when, 12, "LabelTertiary"));
        Grid.SetColumn(col, 1);
        g.Children.Add(col);
        var tag = T(i.Kind switch { 0 => "Relay", 1 => "Google", 2 => i.Done ? "Fatto" : "Compito", _ => "Esame" }, 11, "LabelTertiary");
        tag.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(tag, 2);
        g.Children.Add(tag);
        var w = Widget(g, null, new Thickness(12));
        w.CornerRadius = new CornerRadius(16);
        w.Tapped += (_, _) =>
        {
            switch (i.Kind)
            {
                case 0:
                case 1:
                    _agDetail = i;
                    AgGo("detail");
                    break;
                case 2:
                    var t = (StudyTask)i.Ref!;
                    _r.Plan.SaveTask(t with { Done = !t.Done });
                    break;
            }
        };
        Bounce(w, 1.02f);
        return w;
    }

    /// <summary>Il dettaglio di un evento: titolo, quando, dove, note. La matita apre la modifica (solo per gli eventi di Relay).</summary>
    private void AgDetail(StackPanel h)
    {
        var i = _agDetail;
        if (i == null) { AgGo(null); return; }
        var bar = new Grid { ColumnSpacing = 12 };
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        bar.Children.Add(BackRow("Agenda", "Evento", () => AgGo(null)));
        if (i.Kind == 0)
        {
            var pen = BubbleButton(Center(Icons.Of(Ico.Pencil, White, 18)), 44, () =>
            {
                var e = (Ev)i.Ref!;
                _agDraft = new AgDraft { Id = e.Id, Title = e.Title, Place = e.Place, Note = e.Note, Start = Loc(e.Start), End = e.AllDay ? Loc(e.End).AddDays(-1) : Loc(e.End), AllDay = e.AllDay, Color = e.Color, Remind = e.Remind };
                AgGo("edit");
            }, "Modifica", C("AccentDefault"));
            Grid.SetColumn(pen, 1);
            bar.Children.Add(pen);
        }
        h.Children.Add(bar);

        var when = i.AllDay
            ? (i.End.AddDays(-1).Date <= i.Start.Date ? Cap(i.Start.ToString("dddd d MMMM", It)) : Cap(i.Start.ToString("d MMM", It)) + " – " + i.End.AddDays(-1).ToString("d MMM", It))
            : Cap(i.Start.ToString("dddd d MMMM", It)) + "\n" + i.Start.ToString("HH:mm", It) + " – " + i.End.ToString("HH:mm", It);
        var head = new Grid { ColumnSpacing = 12 };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.Children.Add(new Border { Width = 5, Height = 48, CornerRadius = new CornerRadius(3), Background = Sb(i.Color), VerticalAlignment = VerticalAlignment.Top });
        var tt = new StackPanel { Spacing = 2 };
        var title = T(i.Title, 25, "LabelPrimary", true); title.TextWrapping = TextWrapping.Wrap; title.TextTrimming = TextTrimming.None;
        var wt = T(when, 14, "LabelSecondary"); wt.TextWrapping = TextWrapping.Wrap;
        tt.Children.Add(title); tt.Children.Add(wt);
        Grid.SetColumn(tt, 1);
        head.Children.Add(tt);
        h.Children.Add(Panelled(head, new Thickness(20)));

        string note = i.Ref switch { Ev e => e.Note, ExtEvent x => x.Note, _ => "" };
        Border Info(string label, string value)
        {
            var c = new StackPanel { Spacing = 2 };
            c.Children.Add(T(label, 12, "LabelTertiary"));
            var v = T(value, 14); v.TextWrapping = TextWrapping.Wrap; v.TextTrimming = TextTrimming.None;
            c.Children.Add(v);
            return new Border { Background = B("BgSunken"), CornerRadius = new CornerRadius(20), Padding = new Thickness(16, 12, 16, 12), Child = c };
        }
        if (i.Place.Length > 0) h.Children.Add(Info("Luogo", i.Place));
        if (note.Length > 0) h.Children.Add(Info("Note", note));
        h.Children.Add(Info("Calendario", i.Kind == 0 ? "Relay" : "Google"));
    }

    private void AgNew(DateTime day)
    {
        var s = day.Date == DateTime.Today ? DateTime.Now.AddHours(1).Date.AddHours(DateTime.Now.AddHours(1).Hour) : day.Date.AddHours(9);
        _agDraft = new AgDraft { Start = s, End = s.AddHours(1) };
    }

    private StackPanel ColorDots(int selected, Action<int> pick)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        for (var i = 0; i < PlanColors.Length; i++)
        {
            var idx = i;
            var dot = new Grid { Width = 30, Height = 30, Background = Sb(Microsoft.UI.Colors.Transparent) };
            dot.Children.Add(new Ellipse { Width = i == selected ? 28 : 24, Height = i == selected ? 28 : 24, Fill = Sb(PlanColors[i]) });
            if (i == selected) dot.Children.Add(new Ellipse { Width = 10, Height = 10, Fill = Sb(Microsoft.UI.Colors.White) });
            dot.Tapped += (_, _) => pick(idx);
            Bounce(dot, 1.15f);
            row.Children.Add(dot);
        }
        return row;
    }

    private static StackPanel Flow(params UIElement[] kids)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var k in kids) p.Children.Add(k);
        return p;
    }

    private ScrollViewer ChipScroll(IEnumerable<UIElement> chips)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var c in chips) row.Children.Add(c);
        return new ScrollViewer { HorizontalScrollBarVisibility = ScrollBarVisibility.Hidden, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, HorizontalScrollMode = ScrollMode.Auto, Content = row };
    }

    private void AgEditor(StackPanel h)
    {
        var d = _agDraft ??= new AgDraft { Start = DateTime.Now, End = DateTime.Now.AddHours(1) };
        var existing = d.Id.Length > 0;
        h.Children.Add(BackRow("Agenda", existing ? "Modifica" : "Nuovo evento", () => AgGo(null)));

        var title = new TextBox { Text = d.Title, PlaceholderText = "Titolo" };
        title.TextChanged += (_, _) => d.Title = title.Text;
        var place = new TextBox { Text = d.Place, PlaceholderText = "Aula, indirizzo o link" };
        place.TextChanged += (_, _) => d.Place = place.Text;
        var note = new TextBox { Text = d.Note, PlaceholderText = "Facoltative", AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MinHeight = 64 };
        note.TextChanged += (_, _) => d.Note = note.Text;

        var date = new CalendarDatePicker { Date = new DateTimeOffset(d.Start.Date), FirstDayOfWeek = Windows.Globalization.DayOfWeek.Monday, DateFormat = "{dayofweek.abbreviated} {day.integer} {month.abbreviated} {year.full}" };
        date.DateChanged += (_, a) => { if (a.NewDate is { } nd) { var len = d.End - d.Start; d.Start = nd.Date + d.Start.TimeOfDay; if (d.AllDay) { if (d.End < d.Start) d.End = d.Start; } else d.End = d.Start + len; } };
        var endDate = new CalendarDatePicker { Date = new DateTimeOffset(d.End.Date), FirstDayOfWeek = Windows.Globalization.DayOfWeek.Monday, DateFormat = "{dayofweek.abbreviated} {day.integer} {month.abbreviated} {year.full}" };
        endDate.DateChanged += (_, a) => { if (a.NewDate is { } nd) d.End = nd.Date > d.Start.Date ? nd.Date : d.Start.Date; };
        var t1 = new TimePicker { Time = d.Start.TimeOfDay, ClockIdentifier = "24HourClock", MinuteIncrement = 5 };
        t1.TimeChanged += (_, a) => { var len = d.End - d.Start; if (len <= TimeSpan.Zero) len = TimeSpan.FromHours(1); d.Start = d.Start.Date + a.NewTime; d.End = d.Start + len; };
        var t2 = new TimePicker { Time = d.End.TimeOfDay, ClockIdentifier = "24HourClock", MinuteIncrement = 5 };
        t2.TimeChanged += (_, a) => { var e = d.Start.Date + a.NewTime; d.End = e <= d.Start ? e.AddDays(1) : e; };

        var form = new StackPanel { Spacing = 14 };
        form.Children.Add(Field("Titolo", title));
        form.Children.Add(Toggle("Tutto il giorno", "", d.AllDay, on => { d.AllDay = on; if (on) { d.Start = d.Start.Date; d.End = d.End.Date; } else { d.End = d.Start.AddHours(1); } RenderAgenda(); }));
        var dates = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 14 };
        dates.Children.Add(Field(d.AllDay ? "Dal" : "Giorno", date));
        if (d.AllDay) dates.Children.Add(Field("Al", endDate));
        form.Children.Add(dates);
        if (!d.AllDay)
        {
            var times = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 14 };
            times.Children.Add(Field("Inizio", t1));
            times.Children.Add(Field("Fine", t2));
            form.Children.Add(times);
        }
        form.Children.Add(Field("Luogo", place));
        form.Children.Add(Field("Note", note));
        var cs = new StackPanel { Spacing = 6 };
        cs.Children.Add(Text("Colore", "Footnote"));
        cs.Children.Add(ColorDots(d.Color, i => { d.Color = i; RenderAgenda(); }));
        form.Children.Add(cs);
        var rs = new StackPanel { Spacing = 6 };
        rs.Children.Add(Text("Promemoria", "Footnote"));
        rs.Children.Add(ChipScroll(Reminders.Select(r => (UIElement)Chip(r.Label, d.Remind == r.Min, () => { d.Remind = r.Min; RenderAgenda(); }))));
        form.Children.Add(rs);
        h.Children.Add(Panelled(form, new Thickness(24)));

        var save = Btn(existing ? "Salva" : "Aggiungi", () =>
        {
            if (string.IsNullOrWhiteSpace(d.Title)) { _r.Notify.Show("Agenda", "Scrivi un titolo", "", "ag"); return; }
            var s = d.AllDay ? d.Start.Date : d.Start;
            var e = d.AllDay ? d.End.Date.AddDays(1) : (d.End > d.Start ? d.End : d.Start.AddMinutes(15));
            _r.Plan.SaveEvent(new Ev(d.Id, Ms(s), Ms(e), d.AllDay, d.Title.Trim(), d.Color, d.Note.Trim(), d.Place.Trim(), d.Remind));
            AgGo(null);
        }, "PrimaryButton");
        save.HorizontalAlignment = HorizontalAlignment.Stretch;
        h.Children.Add(save);
        if (existing)
        {
            var del = Btn("Elimina", () => { _r.Plan.RemoveEvent(d.Id); AgGo(null); }, "DestructiveButton");
            del.HorizontalAlignment = HorizontalAlignment.Stretch;
            h.Children.Add(del);
        }
    }

    private void AgIcs(StackPanel h)
    {
        h.Children.Add(BackRow("Agenda", "Google Calendar", () => AgGo(null)));
        var c = new StackPanel { Spacing = 12 };
        var url = new TextBox { Text = _r.S.IcsUrl, PlaceholderText = "https://calendar.google.com/calendar/ical/…/basic.ics" };
        c.Children.Add(Field("Indirizzo iCal", url));
        var status = Text(_r.Plan.ExternalStatus.Length > 0 ? _r.Plan.ExternalStatus : "Non collegato", "Footnote");
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        row.Children.Add(Btn("Collega e aggiorna", async () =>
        {
            _r.S.IcsUrl = url.Text.Trim();
            _r.S.Save();
            status.Text = "Leggo il calendario…";
            await _r.Plan.RefreshExternal();
            status.Text = _r.Plan.ExternalStatus;
        }, "PrimaryButton"));
        if (_r.S.IcsUrl.Length > 0)
            row.Children.Add(Btn("Scollega", async () => { _r.S.IcsUrl = ""; _r.S.Save(); await _r.Plan.RefreshExternal(); AgGo("ics"); }));
        c.Children.Add(row);
        c.Children.Add(status);
        h.Children.Add(Panelled(c, new Thickness(24)));
    }
}
