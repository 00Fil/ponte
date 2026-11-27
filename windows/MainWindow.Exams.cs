using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.System;

namespace Ponte;

/// <summary>Esami come su Android (ExamPlan.kt): l'argomento da fare adesso e le materie con data, avanzamento e quota di oggi.</summary>
public sealed partial class MainWindow
{
    private string? _exOpen;
    private bool _exAdding;
    private bool _exFocusName;
    private string _exName = "";
    private string _exTopics = "";

    /// <summary>Situazione di una materia rispetto al suo esame.</summary>
    private sealed record Course(Subject Subject, Exam? Exam, List<StudyTask> Topics)
    {
        public List<StudyTask> Open => Topics.Where(t => !t.Done).ToList();
        public int Done => Topics.Count(t => t.Done);
        /// <summary>Giorni interi da oggi all'esame (0 = oggi).</summary>
        public int? Days => Exam == null ? null : (Loc(Exam.Date).Date - DateTime.Today).Days;
        /// <summary>Giorni utili per studiare: l'ultimo prima dell'esame resta per il ripasso.</summary>
        public int Usable => Days is { } d ? Math.Max(d - 1, 1) : 30;
        /// <summary>Argomenti da fare ogni giorno per arrivare in tempo.</summary>
        public int PerDay => (int)Math.Ceiling(Open.Count / (double)Usable);
        /// <summary>Argomenti rimasti per giorno utile: più alto, più urgente.</summary>
        public double Pressure => Open.Count == 0 || Days < 0 ? -1 : Open.Count / (double)Usable + (Exam == null ? -100 : 0);
        public int DoneToday => Topics.Count(t => t.Done && t.DoneAt >= Ms(DateTime.Today));
    }

    private static List<Course> Courses(PlanState p)
    {
        var today = Ms(DateTime.Today);
        return p.Subjects.Select(s =>
        {
            var exams = p.Exams.Where(e => e.Subject == s.Id).ToList();
            var exam = exams.Where(e => e.Date >= today).OrderBy(e => e.Date).FirstOrDefault() ?? exams.OrderByDescending(e => e.Date).FirstOrDefault();
            return new Course(s, exam, p.Topics.Where(t => t.Subject == s.Id).ToList());
        }).OrderBy(c => c.Exam?.Date ?? long.MaxValue).ToList();
    }

    /// <summary>Le materie ancora da studiare, nell'ordine in cui conviene: prima chi non ha fatto la quota di oggi, poi chi è più stretto.</summary>
    private static IEnumerable<Course> ByUrgency(PlanState p) => Courses(p)
        .Where(c => c.Open.Count > 0 && (c.Days ?? 0) >= 0)
        .OrderBy(c => c.DoneToday < c.PerDay ? 0 : 1).ThenByDescending(c => c.Pressure).ThenBy(c => c.Exam?.Date ?? long.MaxValue);

    private static string ExamWhen(int? days) => days switch
    {
        null => "Senza data",
        < 0 => "Passato",
        0 => "Oggi",
        1 => "Domani",
        _ => $"Tra {days} giorni",
    };

    /// <summary>Adesso: un solo argomento, con i due gesti che servono.</summary>
    private Border NowStudyCard(PlanState p)
    {
        var now = ByUrgency(p).FirstOrDefault();
        var c = new StackPanel { Spacing = 12 };
        if (now == null)
        {
            var none = p.Subjects.Count == 0;
            c.Children.Add(Semi(none ? "Esami" : "Tutto fatto", 20));
            if (none)
            {
                var add = AudioPill("+ Materia", () => { _exAdding = true; _exFocusName = true; RenderAgenda(); });
                add.HorizontalAlignment = HorizontalAlignment.Left;
                c.Children.Add(add);
            }
            return Panelled(c, new Thickness(20));
        }
        var topic = now.Open[0];
        var dot = new Ellipse { Width = 10, Height = 10, Fill = Sb(PColor(now.Subject.Color)), VerticalAlignment = VerticalAlignment.Center };
        var name = Semi(now.Subject.Name, 13, "LabelSecondary");
        var when = T("· " + ExamWhen(now.Days), 13, "LabelTertiary");
        var quota = Semi($"{now.DoneToday}/{now.PerDay} oggi", 13, now.DoneToday >= now.PerDay ? "PositiveOnSubtle" : "AccentDefault");
        name.VerticalAlignment = when.VerticalAlignment = quota.VerticalAlignment = VerticalAlignment.Center;
        c.Children.Add(Cols("aaa*a", 8, dot, name, when, null, quota));

        var title = T(topic.Title, 25, "LabelPrimary", true);
        title.TextWrapping = TextWrapping.Wrap;
        title.MaxLines = 3;
        c.Children.Add(title);

        var t = _r.Plan.State.Timer;
        var running = t.Running && t.EndAt > DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var play = BubbleButton(Center(Icons.Player(running ? Transport.Pause : Transport.Play, White, 20)), 48,
            () => { if (running) _r.Plan.TimerStop(); else _r.Plan.TimerStart(now.Subject.Id); RenderAgenda(); },
            running ? "Ferma il pomodoro" : "Avvia il pomodoro", C("AccentDefault"));
        var done = BubbleButton(Center(Icons.Of(Ico.Check, B("LabelPrimary"), 22)), 48,
            () => { _r.Plan.SaveTask(topic with { Done = true, DoneAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() }); RenderAgenda(); },
            "Argomento fatto");
        c.Children.Add(HStack(12, play, done));
        return Panelled(c, new Thickness(20));
    }

    /// <summary>Le materie: data d'esame, avanzamento, quota di oggi; un tocco apre gli argomenti.</summary>
    private Border ExamsCard(PlanState p)
    {
        var c = new StackPanel { Spacing = 12 };
        var title = Semi("Esami", 20);
        title.VerticalAlignment = VerticalAlignment.Center;
        var sign = Center(T("+", 18, "LabelPrimary", true));
        if (_exAdding) sign.Foreground = White;
        var plus = BubbleButton(sign, 34, () => { _exAdding = !_exAdding; _exFocusName = _exAdding; RenderAgenda(); }, "Nuova materia", _exAdding ? C("AccentDefault") : null);
        c.Children.Add(Cols("*a", 8, title, plus));
        if (_exAdding)
        {
            var field = ExamField("Nome della materia", false, _exName, v => _exName = v, text =>
            {
                _r.Plan.SaveSubject(new Subject("", text, _r.Plan.State.Subjects.Count));
                _exName = "";
                _exAdding = false;
                RenderAgenda();
            }, _exFocusName);
            _exFocusName = false;
            c.Children.Add(field);
            Enter(field, 0, -8, 260);
        }
        foreach (var course in Courses(p)) c.Children.Add(CourseRow(course));
        return Panelled(c, new Thickness(20));
    }

    private StackPanel CourseRow(Course course)
    {
        var id = course.Subject.Id;
        var expanded = _exOpen == id;
        var color = PColor(course.Subject.Color);
        var total = course.Topics.Count;
        var open = course.Open.Count;
        var late = course.PerDay > 2 || (course.Days <= 3 && open > 0);

        var bar = new Border { Width = 4, Height = 38, CornerRadius = new CornerRadius(2), Background = Sb(color) };
        var line = ExamWhen(course.Days) + (course.Exam == null ? "" : " · " + Loc(course.Exam.Date).ToString("d MMM", It)) + $" · {course.Done}/{total}";
        var text = VStack(0, Semi(course.Subject.Name, 16), T(line, 13, "LabelTertiary"));
        text.VerticalAlignment = VerticalAlignment.Center;
        FrameworkElement? side = null;
        if (open > 0)
        {
            var n = Semi(course.PerDay.ToString(), 20, "AccentDefault");
            if (late) n.Foreground = Sb(Third);
            var unit = T("al giorno", 13, "LabelTertiary");
            n.HorizontalAlignment = unit.HorizontalAlignment = HorizontalAlignment.Right;
            side = VStack(0, n, unit);
        }
        else if (total > 0) side = Icons.Of(Ico.Check, B("PositiveOnSubtle"), 20);
        if (side != null) side.VerticalAlignment = VerticalAlignment.Center;

        var head = Cols("a*a", 12, bar, text, side);
        head.Padding = new Thickness(0, 6, 0, 6);
        var tap = Bare(head, () => { _exOpen = expanded ? null : id; _exTopics = ""; RenderAgenda(); }, course.Subject.Name, 12);
        tap.HorizontalAlignment = HorizontalAlignment.Stretch;

        var row = VStack(8, tap, Progress(total == 0 ? 0 : course.Done / (double)total, Sb(color), 6));
        if (!expanded) return row;

        var date = course.Exam == null ? "Data esame" : "Esame " + Loc(course.Exam.Date).ToString("ddd d MMM", It);
        var pick = AudioPill(date, () => { });
        var calendar = new CalendarView { SelectionMode = CalendarViewSelectionMode.Single, Language = "it-IT", FirstDayOfWeek = Windows.Globalization.DayOfWeek.Monday };
        var start = course.Exam == null ? DateTime.Today.AddDays(30) : Loc(course.Exam.Date).Date;
        calendar.SetDisplayDate(new DateTimeOffset(start));
        if (course.Exam != null) calendar.SelectedDates.Add(new DateTimeOffset(start));
        var flyout = new Flyout { Content = calendar };
        calendar.SelectedDatesChanged += (_, e) =>
        {
            if (e.AddedDates.Count == 0) return;
            var day = e.AddedDates[0].Date;
            flyout.Hide();
            _r.Plan.SaveExam(new Exam(course.Exam?.Id ?? "", course.Subject.Name, id, Ms(day.AddHours(9))));
            DispatcherQueue.TryEnqueue(RenderAgenda);
        };
        pick.Flyout = flyout;
        pick.HorizontalAlignment = HorizontalAlignment.Left;

        var word = Semi("Elimina", 13, "CriticalOnSubtle");
        word.Margin = new Thickness(8, 4, 8, 4);
        var remove = Bare(word, () => _ = RemoveCourse(course), "Elimina " + course.Subject.Name, 12);
        remove.VerticalAlignment = VerticalAlignment.Center;

        var body = VStack(8, Cols("a*a", 8, pick, null, remove));
        foreach (var t in course.Topics) body.Children.Add(TopicRow(t));
        body.Children.Add(ExamField("Argomenti, uno per riga", true, _exTopics, v => _exTopics = v, value =>
        {
            var lines = value.Split('\n', '\r').Select(l => l.Trim().TrimStart('-', '•', '*', ' ')).Where(l => l.Length > 0).ToList();
            _exTopics = "";
            _r.Plan.AddTopics(id, lines);
            RenderAgenda();
        }, false));
        body.Margin = new Thickness(0, 4, 0, 4);
        row.Children.Add(body);
        Enter(body, 0, -8, 300);
        return row;
    }

    private async Task RemoveCourse(Course course)
    {
        var dlg = new ContentDialog
        {
            XamlRoot = Root.XamlRoot, Title = $"Eliminare {course.Subject.Name}?", PrimaryButtonText = "Elimina",
            CloseButtonText = "Annulla", DefaultButton = ContentDialogButton.Close,
        };
        if (await dlg.ShowAsync() != ContentDialogResult.Primary) return;
        foreach (var t in course.Topics) _r.Plan.RemoveTask(t.Id);
        foreach (var e in _r.Plan.State.Exams.Where(e => e.Subject == course.Subject.Id).ToList()) _r.Plan.RemoveExam(e.Id);
        _r.Plan.RemoveSubject(course.Subject.Id);
        _exOpen = null;
        RenderAgenda();
    }

    private Grid TopicRow(StudyTask t)
    {
        var circle = t.Done
            ? Bubble(Center(Icons.Of(Ico.Check, White, 14)), 24, C("AccentDefault"))
            : new Border { Width = 24, Height = 24, CornerRadius = new CornerRadius(12), BorderThickness = new Thickness(1.5), BorderBrush = B("LabelTertiary"), Background = Clear };
        var check = Bare(circle, () =>
        {
            _r.Plan.SaveTask(t with { Done = !t.Done, DoneAt = t.Done ? 0 : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() });
            RenderAgenda();
        }, (t.Done ? "Da rifare: " : "Fatto: ") + t.Title, 12);
        check.Width = check.Height = 24;
        check.VerticalAlignment = VerticalAlignment.Center;
        Bounce(check, 1.12f);

        var title = T(t.Title, 16, t.Done ? "LabelTertiary" : "LabelPrimary");
        title.TextWrapping = TextWrapping.Wrap;
        title.MaxLines = 2;
        title.VerticalAlignment = VerticalAlignment.Center;
        if (t.Done) title.TextDecorations = Windows.UI.Text.TextDecorations.Strikethrough;

        var cross = Bare(Center(Icons.Of(Ico.Close, B("LabelTertiary"), 14)), () => { _r.Plan.RemoveTask(t.Id); RenderAgenda(); }, "Elimina " + t.Title, 14);
        cross.Width = cross.Height = 28;
        cross.VerticalAlignment = VerticalAlignment.Center;
        var row = Cols("a*a", 12, check, title, cross);
        row.Padding = new Thickness(0, 4, 0, 4);
        return row;
    }

    /// <summary>Campo nella capsula con il tasto +. Su più righe accetta un elenco incollato (Ctrl+Invio per aggiungere).</summary>
    private Border ExamField(string hint, bool lines, string value, Action<string> typed, Action<string> done, bool focus)
    {
        var box = new TextBox
        {
            PlaceholderText = hint, AcceptsReturn = lines, TextWrapping = lines ? TextWrapping.Wrap : TextWrapping.NoWrap,
            MaxHeight = 180, VerticalAlignment = VerticalAlignment.Center, BorderThickness = new Thickness(0),
        };
        box.Text = value;
        foreach (var key in new[] { "TextControlBackground", "TextControlBackgroundPointerOver", "TextControlBackgroundFocused", "TextControlBorderBrush", "TextControlBorderBrushPointerOver", "TextControlBorderBrushFocused" })
            box.Resources[key] = Clear;
        AutomationProperties.SetName(box, hint);
        void Send()
        {
            var text = box.Text.Trim();
            if (text.Length == 0) return;
            box.Text = "";
            done(text);
        }
        var holder = new Border { Width = 38, Height = 38, VerticalAlignment = VerticalAlignment.Bottom };
        void Plus()
        {
            var on = box.Text.Trim().Length > 0;
            var sign = Center(T("+", 20, "LabelPrimary", true));
            if (on) sign.Foreground = White;
            holder.Child = BubbleButton(sign, 38, Send, "Aggiungi", on ? C("AccentDefault") : null);
        }
        Plus();
        var had = value.Trim().Length > 0;
        box.TextChanged += (_, _) =>
        {
            typed(box.Text);
            var has = box.Text.Trim().Length > 0;
            if (has != had) { had = has; Plus(); }
        };
        box.PreviewKeyDown += (_, e) =>
        {
            if (e.Key != VirtualKey.Enter) return;
            var ctrl = Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Control).HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down);
            if (lines && !ctrl) return;
            e.Handled = true;
            Send();
        };
        if (focus) box.Loaded += (_, _) => box.Focus(FocusState.Programmatic);
        return new Border
        {
            MinHeight = 46, CornerRadius = new CornerRadius(20), Background = Sb(Alpha(C("LabelPrimary"), 0.06)),
            Padding = new Thickness(6, 4, 4, 4), Child = Cols("*a", 8, box, holder),
        };
    }
}
