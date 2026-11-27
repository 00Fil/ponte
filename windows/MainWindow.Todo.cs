using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.System;

namespace Ponte;

/// <summary>Cose da fare come su Android (Todo.kt): vita o università, campo per aggiungere, righe con la bolla di spunta.</summary>
public sealed partial class MainWindow
{
    private bool _todoUni;
    private string _todoText = "";

    private static List<StudyTask> TodoLife(PlanState p) =>
        p.Tasks.Where(t => !t.Done).OrderBy(t => t.Due > 0 ? t.Due : long.MaxValue).ThenBy(t => t.Title).ToList();

    /// <summary>Prima le cose libere dell'università, poi gli argomenti d'esame nell'ordine in cui conviene farli.</summary>
    private static List<StudyTask> TodoUni(PlanState p)
    {
        var today = Ms(DateTime.Today);
        var free = p.Topics.Where(t => t.Subject == "uni" && !t.Done);
        var next = p.Subjects.Select(s =>
        {
            var exams = p.Exams.Where(e => e.Subject == s.Id).ToList();
            var exam = exams.Where(e => e.Date >= today).OrderBy(e => e.Date).FirstOrDefault() ?? exams.OrderByDescending(e => e.Date).FirstOrDefault();
            var topics = p.Topics.Where(t => t.Subject == s.Id).ToList();
            var open = topics.Where(t => !t.Done).ToList();
            int? days = exam == null ? null : (Loc(exam.Date).Date - DateTime.Today).Days;
            var usable = days is { } d ? Math.Max(d - 1, 1) : 30;
            var perDay = (int)Math.Ceiling(open.Count / (double)usable);
            var pressure = open.Count == 0 || days < 0 ? -1 : open.Count / (double)usable + (exam == null ? -100 : 0);
            var doneToday = topics.Count(t => t.Done && t.DoneAt >= today);
            return (Open: open, Days: days, Pressure: pressure, Later: doneToday < perDay ? 0 : 1, Date: exam?.Date ?? long.MaxValue);
        })
            .Where(c => c.Open.Count > 0 && (c.Days ?? 0) >= 0)
            .OrderBy(c => c.Later).ThenByDescending(c => c.Pressure).ThenBy(c => c.Date)
            .SelectMany(c => c.Open);
        return free.Concat(next).ToList();
    }

    private List<StudyTask> TodoOpen(PlanState p) => _todoUni ? TodoUni(p) : TodoLife(p);

    /// <summary>Una riga: bolla di spunta e testo.</summary>
    private Grid TodoRow(StudyTask t, bool compact = false)
    {
        var size = compact ? 22.0 : 26.0;
        var circle = t.Done
            ? Bubble(Icons.Of(Ico.Check, White, 14), size, C("AccentDefault"))
            : new Border { Width = size, Height = size, CornerRadius = new CornerRadius(size / 2), BorderThickness = new Thickness(1.5), BorderBrush = B("LabelTertiary"), Background = Clear };
        var check = Bare(circle, () => _r.Plan.SaveTask(t with { Done = !t.Done, DoneAt = t.Done ? 0 : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() }),
            (t.Done ? "Da rifare: " : "Fatta: ") + t.Title, size / 2);
        check.Width = check.Height = size;
        check.VerticalAlignment = VerticalAlignment.Center;
        Bounce(check, 1.12f);

        var title = T(t.Title, compact ? 13 : 16, t.Done ? "LabelTertiary" : "LabelPrimary");
        title.VerticalAlignment = VerticalAlignment.Center;
        if (t.Done) title.TextDecorations = Windows.UI.Text.TextDecorations.Strikethrough;
        if (!compact) { title.TextWrapping = TextWrapping.Wrap; title.MaxLines = 3; }

        TextBlock? due = null;
        if (!compact && t.Subject.Length == 0 && t.Due > 0)
        {
            due = T(DayLabel(Loc(t.Due)), 13, "LabelTertiary");
            due.VerticalAlignment = VerticalAlignment.Center;
            if (!t.Done && Loc(t.Due).Date < DateTime.Today) due.Foreground = Sb(Third);
        }
        var row = Cols("a*a", 12, check, title, due);
        row.Padding = new Thickness(0, compact ? 2 : 8, 0, compact ? 2 : 8);
        return row;
    }

    private void RenderTodo(StackPanel h)
    {
        var p = _r.Plan.State;
        h.Children.Add(BackRow("Agenda", "Cose da fare", () => AgGo(null)));
        h.Children.Add(Segmented("todo", new[] { "Life", "Uni" }, _todoUni ? 1 : 0, i => { _todoUni = i == 1; RenderAgenda(); }));
        h.Children.Add(TodoField());

        var open = TodoOpen(p);
        var list = new StackPanel();
        if (open.Count == 0)
        {
            var none = T("Tutto fatto", 16, "LabelTertiary");
            none.Margin = new Thickness(0, 12, 0, 12);
            list.Children.Add(none);
        }
        for (var i = 0; i < open.Count; i++)
        {
            if (i > 0) list.Children.Add(new Border { Height = 1, Margin = new Thickness(38, 0, 0, 0), Background = B("LineSeparator") });
            list.Children.Add(TodoRow(open[i]));
        }
        h.Children.Add(Panelled(list));

        var done = _todoUni
            ? p.Topics.Where(t => t.Done).OrderByDescending(t => t.DoneAt).ToList()
            : p.Tasks.Where(t => t.Done).OrderByDescending(t => t.DoneAt).ToList();
        if (done.Count == 0) return;
        var head = Semi("Fatte", 13, "LabelSecondary");
        head.VerticalAlignment = VerticalAlignment.Center;
        Button? clear = null;
        if (!_todoUni)
        {
            var word = Semi("Svuota", 13, "AccentDefault");
            word.Margin = new Thickness(8, 4, 8, 4);
            clear = Bare(word, () => { foreach (var t in done) _r.Plan.RemoveTask(t.Id); }, "Svuota", 12);
        }
        var top = Cols("*a", 8, head, clear);
        top.MinHeight = 36;
        var old = VStack(0, top);
        foreach (var t in done.Take(20)) old.Children.Add(TodoRow(t));
        h.Children.Add(Panelled(old));
    }

    /// <summary>Superficie di vetro che contiene un elenco (GlassTile senza tocco).</summary>
    private Border Panelled(UIElement content, Thickness? pad = null) => new()
    {
        CornerRadius = new CornerRadius(28), Background = B("TileFill"), BorderBrush = B("TileEdge"), BorderThickness = new Thickness(1),
        Padding = pad ?? new Thickness(16, 8, 16, 8), Child = content,
    };

    private Grid TodoField()
    {
        var box = new TextBox { Text = _todoText, PlaceholderText = "Nuova cosa da fare", VerticalAlignment = VerticalAlignment.Center };
        // il campo sta dentro la capsula di vetro: niente fondo né bordo propri
        foreach (var key in new[] { "TextControlBackground", "TextControlBackgroundPointerOver", "TextControlBackgroundFocused", "TextControlBorderBrush", "TextControlBorderBrushPointerOver", "TextControlBorderBrushFocused" })
            box.Resources[key] = Clear;
        box.BorderThickness = new Thickness(0);
        var holder = new Border { Width = 40, Height = 40 };
        void Add()
        {
            var text = box.Text.Trim();
            if (text.Length == 0) return;
            box.Text = "";
            _todoText = "";
            _r.Plan.SaveTask(new StudyTask("", text, _todoUni ? "uni" : "", 0, false));
            RenderAgenda();
        }
        void Plus()
        {
            var on = box.Text.Trim().Length > 0;
            var sign = Center(T("+", 25, "LabelPrimary", true));
            if (on) sign.Foreground = White;
            holder.Child = BubbleButton(sign, 40, Add, "Aggiungi", on ? C("AccentDefault") : null);
        }
        box.TextChanged += (_, _) => { var was = _todoText.Trim().Length > 0; _todoText = box.Text; if (was != _todoText.Trim().Length > 0) Plus(); };
        box.KeyDown += (_, e) => { if (e.Key == VirtualKey.Enter) { e.Handled = true; Add(); } };
        Plus();
        var capsule = new Border { CornerRadius = new CornerRadius(24), Background = Drop(), BorderBrush = Hairline, BorderThickness = new Thickness(1) };
        var row = Cols("*a", 8, box, holder);
        row.Margin = new Thickness(6, 0, 4, 0);
        return new Grid { Height = 48, Children = { capsule, row } };
    }
}
