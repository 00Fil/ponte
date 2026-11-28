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
    private string _todoUniText = "";
    private int _todoSlide;

    private static List<StudyTask> TodoLife(PlanState p) =>
        p.Tasks.Where(t => !t.Done).OrderBy(t => t.Due > 0 ? t.Due : long.MaxValue).ThenBy(t => t.Title).ToList();

    /// <summary>Prima le cose libere dell'università, poi gli argomenti d'esame nell'ordine in cui conviene farli.</summary>
    private static List<StudyTask> TodoUni(PlanState p) =>
        p.Topics.Where(t => t.Subject == "uni" && !t.Done).Concat(ByUrgency(p).SelectMany(c => c.Open)).ToList();

    private List<StudyTask> TodoOpen(PlanState p) => TodoOpen(p, _todoUni);
    private static List<StudyTask> TodoOpen(PlanState p, bool uni) => uni ? TodoUni(p) : TodoLife(p);

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
        h.Children.Add(BackRow("Agenda", "To-do", () => AgGo(null)));
        if (Spread > 1)
        {
            // pagina larga: Life e Uni affiancate, senza dover passare dall'una all'altra
            StackPanel List(string name, bool uni)
            {
                var title = Semi(name, 20);
                title.Margin = new Thickness(8, 4, 0, 0);
                var column = Column(title);
                TodoList(column, p, uni);
                return column;
            }
            _todoSlide = 0;
            h.Children.Add(Beside((Part(), List("Life", false)), (Part(), List("Uni", true))));
            return;
        }
        h.Children.Add(Segmented("todo", new[] { "Life", "Uni" }, _todoUni ? 1 : 0, i => TodoPage(i == 1)));

        // Life e Uni sono due pagine: si passa dall'una all'altra anche scorrendo di lato
        var page = new StackPanel { Spacing = 12, Background = Clear, MinHeight = 360 };
        SideSwipe(page, d => TodoPage(d > 0));
        h.Children.Add(page);
        if (_todoSlide != 0) { Enter(page, 40 * _todoSlide, 0, 320); _todoSlide = 0; }
        TodoList(page, p, _todoUni);
    }

    /// <summary>Campo per aggiungere, cose aperte e cose fatte di una delle due liste.</summary>
    private void TodoList(StackPanel page, PlanState p, bool uni)
    {
        page.Children.Add(TodoField(uni));

        var open = TodoOpen(p, uni);
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
        page.Children.Add(Panelled(list));

        var done = uni
            ? p.Topics.Where(t => t.Done).OrderByDescending(t => t.DoneAt).ToList()
            : p.Tasks.Where(t => t.Done).OrderByDescending(t => t.DoneAt).ToList();
        if (done.Count == 0) return;
        var head = Semi("Fatte", 13, "LabelSecondary");
        head.VerticalAlignment = VerticalAlignment.Center;
        Button? clear = null;
        if (!uni)
        {
            var word = Semi("Svuota", 13, "AccentDefault");
            word.Margin = new Thickness(8, 4, 8, 4);
            clear = Bare(word, () => { foreach (var t in done) _r.Plan.RemoveTask(t.Id); }, "Svuota", 12);
        }
        var top = Cols("*a", 8, head, clear);
        top.MinHeight = 36;
        var old = VStack(0, top);
        foreach (var t in done.Take(20)) old.Children.Add(TodoRow(t));
        page.Children.Add(Panelled(old));
    }

    private void TodoPage(bool uni)
    {
        if (uni == _todoUni) return;
        _todoUni = uni;
        _todoSlide = uni ? 1 : -1;
        DispatcherQueue.TryEnqueue(RenderAgenda);
    }

    /// <summary>Superficie di vetro che contiene un elenco (GlassTile senza tocco).</summary>
    private Border Panelled(UIElement content, Thickness? pad = null) => new()
    {
        CornerRadius = new CornerRadius(28), Background = B("TileFill"), BorderBrush = B("TileEdge"), BorderThickness = new Thickness(1),
        Padding = pad ?? new Thickness(16, 8, 16, 8), Child = content,
    };

    private Grid TodoField(bool uni)
    {
        // affiancate, ogni lista tiene il proprio testo in corso
        var own = uni && Spread > 1;
        var draft = own ? _todoUniText : _todoText;
        void Keep(string text) { if (own) _todoUniText = text; else _todoText = text; }
        var box = new TextBox { Text = draft, PlaceholderText = "Nuova cosa da fare", VerticalAlignment = VerticalAlignment.Center };
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
            Keep("");
            _r.Plan.SaveTask(new StudyTask("", text, uni ? "uni" : "", 0, false));
            RenderAgenda();
        }
        void Plus()
        {
            var on = box.Text.Trim().Length > 0;
            var sign = Center(T("+", 25, "LabelPrimary", true));
            if (on) sign.Foreground = White;
            holder.Child = BubbleButton(sign, 40, Add, "Aggiungi", on ? C("AccentDefault") : null);
        }
        var filled = draft.Trim().Length > 0;
        box.TextChanged += (_, _) => { Keep(box.Text); var now = box.Text.Trim().Length > 0; if (now == filled) return; filled = now; Plus(); };
        box.KeyDown += (_, e) => { if (e.Key == VirtualKey.Enter) { e.Handled = true; Add(); } };
        Plus();
        var capsule = new Border { CornerRadius = new CornerRadius(24), Background = Drop(), BorderBrush = Hairline, BorderThickness = new Thickness(1) };
        var row = Cols("*a", 8, box, holder);
        row.Margin = new Thickness(6, 0, 4, 0);
        return new Grid { Height = 48, Children = { capsule, row } };
    }
}
