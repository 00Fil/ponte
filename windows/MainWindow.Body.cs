using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;

namespace Ponte;

/// <summary>
/// Corpo come su Android: calorie e macro del giorno, prossimo pasto del piano, peso con andamento, diario con ricerca (CREA, Open Food Facts, Esselunga),
/// alimenti salvati, piano settimanale e obiettivi. Stessi dati del telefono. Le foto del fisico si scattano e restano sul telefono.
/// </summary>
public sealed partial class MainWindow
{
    private string? _bdSub;
    private string _bdDay = BodyStore.DayKey();
    private int _bdMeal;
    private int _bdDow = ((int)DateTime.Today.DayOfWeek + 6) % 7;
    private string _bdQuery = "";
    private List<Food>? _bdResults;
    private bool _bdBusy;
    private string _bdWeight = "";
    private static readonly string[] BdDays = { "Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom" };
    private static readonly string[] BdDaysFull = { "Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica" };
    private static readonly Windows.UI.Color MacroP = Argb(255, 0x3F, 0x86, 0xFF);
    private static readonly Windows.UI.Color MacroC = Argb(255, 0x34, 0xC7, 0x59);
    private static readonly Windows.UI.Color MacroF = Argb(255, 0xFF, 0x9F, 0x0A);

    private static string BdN(double v) => Math.Round(v).ToString(It);
    private static string BdG(double v) => v.ToString("0.#", It);
    private static string BdClock(int m) => $"{m / 60:00}:{m % 60:00}";
    private static double? BdParse(string s) =>
        double.TryParse(s.Trim().Replace(',', '.'), NumberStyles.Float, CultureInfo.InvariantCulture, out var d) ? d : null;

    private void RenderBody()
    {
        var h = Reset("Corpo");
        switch (_bdSub)
        {
            case "diary": BdDiary(h); break;
            case "add": BdAdd(h); break;
            case "foods": BdFoods(h); break;
            case "plan": BdPlan(h); break;
            case "goals": BdGoals(h); break;
            default: BdDashboard(h); break;
        }
    }

    private void BdGo(string? sub)
    {
        _bdSub = sub;
        RenderBody();
        foreach (var el in _hosts[_tab].Children.Take(3)) Enter(el, sub == null ? -12 : 12, 0, 320);
    }

    /// <summary>Un macro (MacroBar): nome a sinistra, valore e obiettivo a destra, barra sottile sotto.</summary>
    private StackPanel BdMacro(string label, double v, double goal, Windows.UI.Color color)
    {
        var value = HStack(0, Semi(BdN(v), 13), T(goal > 0 ? $" / {BdN(goal)} g" : " g", 11, "LabelTertiary"));
        value.Children.OfType<TextBlock>().Last().VerticalAlignment = VerticalAlignment.Bottom;
        var name = T(label, 13, "LabelSecondary");
        return VStack(5, Cols("*a", 8, name, value), Progress(goal > 0 ? v / goal : 0, Sb(color), 6, B("BgSunken")));
    }

    private StackPanel BdMacros(Nut tot, Goals g)
    {
        var col = VStack(12, BdMacro("Proteine", tot.P, g.P, MacroP), BdMacro("Carboidrati", tot.C, g.C, MacroC), BdMacro("Grassi", tot.F, g.F, MacroF));
        col.VerticalAlignment = VerticalAlignment.Center;
        return col;
    }

    private static Nut BdDayTotal(BodyState b, string day) => b.Entries.Where(e => e.Day == day).Aggregate(default(Nut), (a, e) => a + e.N);

    // ---------------------------------------------------------------- cruscotto

    private void BdDashboard(StackPanel h)
    {
        var b = _r.Bd.State;
        var today = BodyStore.DayKey();
        var tot = BdDayTotal(b, today);
        var g = b.Goals;

        // oggi
        var goals = Bare(Semi("Obiettivi", 13, "AccentDefault"), () => BdGo("goals"), "Obiettivi", 8);
        var over = tot.Kcal > g.Kcal;
        var left = Semi(BdN(Math.Abs(g.Kcal - tot.Kcal)), 31);
        var leftWord = T(over ? "kcal oltre" : "kcal rimaste", 11, "LabelTertiary");
        left.HorizontalAlignment = leftWord.HorizontalAlignment = HorizontalAlignment.Center;
        var ring = Ring(g.Kcal > 0 ? tot.Kcal / g.Kcal : 0, 132, 13, VStack(0, left, leftWord), over ? Sb(Third) : null);
        var stats = Cols("****", 8,
            BdStat("Mangiate", BdN(tot.Kcal) + " kcal"), BdStat("Fibre", $"{BdN(tot.Fiber)} / {g.Fiber} g"),
            BdStat("Zuccheri", BdN(tot.Sugar) + " g"), BdStat("Sale", BdG(tot.Salt) + " g"));
        var hero = VStack(16,
            Cols("*a", 8, Semi("Oggi", 13, "LabelSecondary"), goals),
            Cols("a*", 24, ring, BdMacros(tot, g)),
            new Border { Height = 1, Background = B("LineSeparator") },
            stats);
        h.Children.Add(BdTile(hero, () => { _bdDay = today; BdGo("diary"); }, "Diario"));

        h.Children.Add(BdNow(b, today));

        // azioni
        var acts = HStack(48,
            Round("+", "Pasto", true, () => { _bdDay = today; BdGo("diary"); }),
            Round(Tab.Calendar, "Piano", false, () => BdGo("plan")),
            Round(Tab.Clipboard, "Alimenti", false, () => BdGo("foods")));
        acts.HorizontalAlignment = HorizontalAlignment.Center;
        acts.Margin = new Thickness(0, 4, 0, 4);
        h.Children.Add(acts);

        h.Children.Add(BdWeight(b, today));

        // ultimi sette giorni
        var days = Enumerable.Range(0, 7).Select(i => DateTime.Today.AddDays(i - 6)).ToList();
        var logged = days.Select(d => BdDayTotal(b, BodyStore.DayKey(d)).Kcal).Where(v => v > 0).ToList();
        h.Children.Add(Panelled(VStack(16,
            Cols("*a", 8, Semi("Ultimi 7 giorni", 13, "LabelSecondary"), T(logged.Count > 0 ? $"media {BdN(logged.Average())} kcal" : "", 13, "LabelTertiary")),
            BdWeek(b, days)), new Thickness(24)));
    }

    /// <summary>Riquadro di vetro cliccabile a tutta larghezza.</summary>
    private Button BdTile(UIElement content, Action click, string name)
    {
        var t = Tile(content, click, null, name, new Thickness(24));
        t.HorizontalAlignment = HorizontalAlignment.Stretch;
        return t;
    }

    private StackPanel BdStat(string label, string value, Windows.UI.Color? tint = null)
    {
        var name = T(label, 13, "LabelTertiary");
        if (tint is { } c) name.Foreground = Sb(c);
        return VStack(1, name, T(value, 16, "LabelPrimary", true));
    }

    /// <summary>Il prossimo pasto del piano: quello di adesso (fino a 45 minuti dopo) o il primo dei giorni seguenti.</summary>
    private Button BdNow(BodyState b, string today)
    {
        var dow = ((int)DateTime.Today.DayOfWeek + 6) % 7;
        var nowMin = DateTime.Now.Hour * 60 + DateTime.Now.Minute;
        var next = b.Slots.Where(s => s.Dow == dow && s.Minute >= nowMin - 45).MinBy(s => s.Minute);
        var ahead = 0;
        for (var k = 1; next == null && k <= 7; k++)
        {
            next = b.Slots.Where(s => s.Dow == (dow + k) % 7).MinBy(s => s.Minute);
            ahead = k;
        }
        if (next == null)
            return BdTile(Cols("*a", 8, Semi("Piano settimanale", 13, "LabelSecondary"), Semi("Imposta", 13, "AccentDefault")), () => BdGo("plan"), "Piano settimanale");

        var slot = next;
        var tot = slot.Total;
        var when = ahead == 0 ? "Adesso" : ahead == 1 ? "Domani" : It.TextInfo.ToTitleCase(DateTime.Today.AddDays(ahead).ToString("dddd", It));
        var items = VStack(2);
        foreach (var i in slot.Items) items.Children.Add(Cols("*a", 12, T(i.Name, 16), T(BdN(i.Grams) + " g", 16, "LabelTertiary")));
        var p = VStack(12,
            Cols("*a", 8, Semi($"{when} · {BdClock(slot.Minute)}", 13, "LabelSecondary"), T(BdN(tot.Kcal) + " kcal", 13, "LabelTertiary")),
            T(string.IsNullOrWhiteSpace(slot.Label) ? BodyStore.Meals[slot.Meal] : slot.Label, 25, "LabelPrimary", true),
            items);
        if (ahead == 0 && slot.Items.Count > 0)
        {
            var done = slot.Items.All(i => b.Entries.Any(e => e.Day == today && e.Meal == slot.Meal && e.Name == i.Name && e.FoodId == i.FoodId));
            var macros = HStack(16, BdStat("P", BdN(tot.P) + " g", MacroP), BdStat("C", BdN(tot.C) + " g", MacroC), BdStat("G", BdN(tot.F) + " g", MacroF));
            FrameworkElement end = done ? Semi("Registrato", 13, "AccentDefault") : Btn("Registra", () => { _r.Bd.LogSlot(slot, today); }, "PrimaryButton");
            end.VerticalAlignment = VerticalAlignment.Center;
            p.Children.Add(Cols("*a", 16, macros, end));
        }
        return BdTile(p, () => BdGo("plan"), "Piano");
    }

    private Border BdWeight(BodyState b, string today)
    {
        var w = VStack(12, Semi("Peso", 13, "LabelSecondary"));
        var last = b.Weights.Count > 0 ? b.Weights[^1] : null;
        var value = Semi(last != null ? BdG(last.Kg) + " kg" : "—", 31);
        var head = Cols("*a", 12, value, null);
        if (last != null)
        {
            // confronto con la pesata di circa una settimana fa
            var limit = BodyStore.KeyTime(last.Day).AddDays(-6);
            var prev = b.Weights.LastOrDefault(x => BodyStore.KeyTime(x.Day) <= limit);
            if (prev != null)
            {
                var d = last.Kg - prev.Kg;
                var delta = T((d > 0 ? "+" : d < 0 ? "−" : "") + BdG(Math.Abs(d)) + " kg in 7 giorni", 13, "LabelTertiary");
                delta.VerticalAlignment = VerticalAlignment.Bottom;
                delta.Margin = new Thickness(0, 0, 0, 6);
                Grid.SetColumn(delta, 1);
                head.Children.Add(delta);
            }
        }
        w.Children.Add(head);
        if (b.Weights.Count > 1) w.Children.Add(BdWeightChart(b.Weights, b.Goals.GoalKg10 / 10.0));
        var box = new TextBox { PlaceholderText = "Peso di oggi (kg)", Text = _bdWeight, VerticalAlignment = VerticalAlignment.Center };
        box.TextChanged += (_, _) => _bdWeight = box.Text;
        var save = Btn("Registra", () =>
        {
            var v = BdParse(_bdWeight);
            if (v == null || v < 30 || v > 300) return;
            _r.Bd.SetWeight(today, (int)Math.Round(v.Value * 10));
            _bdWeight = "";
        }, "SecondaryButton");
        w.Children.Add(Cols("*a", 10, box, save));
        return Panelled(w, new Thickness(24));
    }

    /// <summary>Andamento degli ultimi 60 giorni, con la linea tratteggiata dell'obiettivo.</summary>
    private FrameworkElement BdWeightChart(List<Weigh> ws, double goal)
    {
        const double height = 120;
        var from = DateTime.Today.AddDays(-60);
        var tail = ws.Where(x => BodyStore.KeyTime(x.Day) >= from).ToList();
        if (tail.Count < 2) tail = ws.TakeLast(2).ToList();
        var lo = tail.Min(x => x.Kg); var hi = tail.Max(x => x.Kg);
        if (goal > 0) { lo = Math.Min(lo, goal); hi = Math.Max(hi, goal); }
        var span = Math.Max(0.5, hi - lo);
        double Y(double kg) => 4 + (height - 8) * (1 - (kg - lo) / span);
        var t0 = BodyStore.KeyTime(tail[0].Day);
        var total = Math.Max(1, (BodyStore.KeyTime(tail[^1].Day) - t0).TotalDays);
        var line = new Polyline { Stroke = B("AccentDefault"), StrokeThickness = 2.5, StrokeLineJoin = PenLineJoin.Round, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round };
        var target = new Line { Stroke = B("LabelTertiary"), StrokeThickness = 1, StrokeDashArray = new DoubleCollection { 5, 5 }, Visibility = goal > 0 ? Visibility.Visible : Visibility.Collapsed };
        var canvas = new Canvas { Height = height, Children = { target, line } };
        canvas.SizeChanged += (_, e) =>
        {
            var pts = new PointCollection();
            foreach (var x in tail) pts.Add(new Point(2 + (e.NewSize.Width - 4) * (BodyStore.KeyTime(x.Day) - t0).TotalDays / total, Y(x.Kg)));
            line.Points = pts;
            target.X2 = e.NewSize.Width;
            target.Y1 = target.Y2 = Y(goal);
        };
        return canvas;
    }

    /// <summary>Le calorie degli ultimi sette giorni (WeekBars): oggi pieno, oltre l'obiettivo col terzo colore.</summary>
    private Grid BdWeek(BodyState b, List<DateTime> days)
    {
        const double height = 96;
        var goal = (double)b.Goals.Kcal;
        var vals = days.Select(d => BdDayTotal(b, BodyStore.DayKey(d)).Kcal).ToList();
        var top = Math.Max(Math.Max(vals.Max(), goal * 1.15), 1);
        var bars = Cols("*******", 10);
        var names = Cols("*******", 10);
        for (var i = 0; i < 7; i++)
        {
            var v = vals[i];
            var bar = new Border
            {
                Height = v > 0 ? Math.Max(4, height * v / top) : 4, VerticalAlignment = VerticalAlignment.Bottom,
                CornerRadius = new CornerRadius(v > 0 ? 6 : 2),
                Background = v <= 0 ? B("BgSunken") : i == 6 ? B("AccentDefault") : v > goal * 1.05 ? Sb(Alpha(Third, 0.7)) : Sb(Alpha(C("AccentDefault"), 0.35)),
            };
            Grid.SetColumn(bar, i); bars.Children.Add(bar);
            var name = T(days[i].ToString("ddd", It)[..1].ToUpper(It), 11, i == 6 ? "LabelPrimary" : "LabelTertiary");
            name.HorizontalAlignment = HorizontalAlignment.Center;
            Grid.SetColumn(name, i); names.Children.Add(name);
        }
        var plot = new Grid { Height = height, Children = { bars } };
        if (goal > 0)
        {
            var target = new Line { Stroke = Sb(Alpha(C("LabelTertiary"), 0.6)), StrokeThickness = 1, StrokeDashArray = new DoubleCollection { 5, 5 }, Y1 = height * (1 - goal / top), Y2 = height * (1 - goal / top) };
            plot.SizeChanged += (_, e) => target.X2 = e.NewSize.Width;
            plot.Children.Add(target);
        }
        var rows = new Grid { RowSpacing = 6 };
        rows.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        rows.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        Grid.SetRow(names, 1);
        rows.Children.Add(plot);
        rows.Children.Add(names);
        return rows;
    }

    // ---------------------------------------------------------------- diario

    private void BdDiary(StackPanel h)
    {
        var b = _r.Bd.State;
        h.Children.Add(BackRow("Corpo", "Diario", () => BdGo(null)));
        var day = BodyStore.KeyTime(_bdDay);
        Button Step(Ico icon, int d, string name) =>
            BubbleButton(Center(Icons.Of(icon, B("LabelSecondary"), 18)), 36, () => { _bdDay = BodyStore.DayKey(day.AddDays(d)); RenderBody(); }, name);
        var title = T(day.Date == DateTime.Today ? "Oggi" : day.ToString("dddd d MMMM", It), 16, "LabelPrimary", true);
        h.Children.Add(Cols("a*a", 10, Step(Ico.Back, -1, "Giorno precedente"), Center(title), Step(Ico.Forward, 1, "Giorno successivo")));

        var tot = BdDayTotal(b, _bdDay);
        var kcal = Semi(BdN(tot.Kcal), 25);
        var of = T($"/ {b.Goals.Kcal}", 11, "LabelTertiary");
        kcal.HorizontalAlignment = of.HorizontalAlignment = HorizontalAlignment.Center;
        var ring = Ring(b.Goals.Kcal > 0 ? tot.Kcal / b.Goals.Kcal : 0, 96, 10, VStack(0, kcal, of), tot.Kcal > b.Goals.Kcal ? Sb(Third) : null);
        h.Children.Add(Panelled(Cols("a*", 24, ring, BdMacros(tot, b.Goals)), new Thickness(24)));

        for (var meal = 0; meal < 4; meal++)
        {
            var m = meal;
            var list = b.Entries.Where(e => e.Day == _bdDay && e.Meal == m).ToList();
            var names = VStack(1, T(BodyStore.Meals[m], 16, "LabelPrimary", true));
            if (list.Count > 0) names.Children.Add(T($"{BdN(list.Sum(e => e.N.Kcal))} kcal", 13, "LabelTertiary"));
            names.VerticalAlignment = VerticalAlignment.Center;
            var add = BubbleButton(Center(Icons.Of(Ico.Plus, White, 18)), 36, () => { _bdMeal = m; _bdQuery = ""; _bdResults = null; BdGo("add"); }, "Aggiungi a " + BodyStore.Meals[m], Alpha(C("AccentDefault"), 0.85));
            var card = VStack(8, Cols("*a", 12, names, add));
            foreach (var e in list)
            {
                var en = e;
                Button Small(Ico icon, Action click, string name) => BubbleButton(Center(Icons.Of(icon, B("LabelSecondary"), 15)), 32, click, name);
                var tools = HStack(6,
                    Small(Ico.Minus, () => _r.Bd.SetGrams(en, Math.Max(10, en.Grams - 10)), "10 g in meno"),
                    Small(Ico.Plus, () => _r.Bd.SetGrams(en, en.Grams + 10), "10 g in più"),
                    Small(Ico.Trash, () => _r.Bd.RemoveEntry(en.Id), "Elimina"));
                tools.VerticalAlignment = VerticalAlignment.Center;
                var info = VStack(1, T(e.Name, 16),
                    T((e.Grams > 0 ? BdG(e.Grams) + " g · " : "") + $"P {BdN(e.N.P)} · C {BdN(e.N.C)} · G {BdN(e.N.F)}", 12, "LabelTertiary"));
                var amount = T(BdN(e.N.Kcal), 16);
                amount.VerticalAlignment = VerticalAlignment.Center;
                card.Children.Add(new Border { Height = 1, Background = B("LineSeparator") });
                card.Children.Add(Cols("*aa", 12, info, amount, tools));
            }
            h.Children.Add(Panelled(card, new Thickness(24, 16, 24, 16)));
        }
    }

    // ---------------------------------------------------------------- aggiungi un alimento

    private void BdAdd(StackPanel h)
    {
        var b = _r.Bd.State;
        h.Children.Add(BackRow("Diario", BodyStore.Meals[_bdMeal], () => BdGo("diary")));

        var q = new TextBox { PlaceholderText = "Alimento o codice a barre", Text = _bdQuery };
        q.TextChanged += (_, _) => _bdQuery = q.Text;
        async Task Go()
        {
            if (_bdQuery.Trim().Length == 0 || _bdBusy) return;
            _bdBusy = true;
            try { _bdResults = await Foods.Search(_bdQuery); }
            catch (Exception) { _bdResults = new(); }
            _bdBusy = false;
            RenderBody();
        }
        q.KeyDown += async (_, e) => { if (e.Key == Windows.System.VirtualKey.Enter) await Go(); };
        var bar = new Grid { ColumnSpacing = 10 };
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        bar.Children.Add(q);
        var go = Btn(_bdBusy ? "Cerco…" : "Cerca", Go, "PrimaryButton");
        Grid.SetColumn(go, 1); bar.Children.Add(go);
        h.Children.Add(Panelled(bar));

        var qt = _bdQuery.Trim();
        var saved = b.Foods.Where(f => qt.Length == 0 || (f.Name + " " + f.Brand).Contains(qt, StringComparison.CurrentCultureIgnoreCase)).Take(30).ToList();
        if (saved.Count > 0) { h.Children.Add(T("I miei alimenti", 13, "LabelTertiary")); h.Children.Add(Panelled(BdFoodList(saved, true))); }
        if (_bdResults != null)
        {
            h.Children.Add(T("Risultati", 13, "LabelTertiary"));
            h.Children.Add(_bdResults.Count == 0 ? Panelled(T("Nessun risultato", 14, "LabelSecondary")) : Panelled(BdFoodList(_bdResults, false)));
        }
    }

    private StackPanel BdFoodList(List<Food> foods, bool isSaved)
    {
        var list = new StackPanel { Spacing = 10 };
        foreach (var f in foods)
        {
            var food = f;
            var grams = new TextBox { Text = food.Serving.ToString(It), Width = 70, TextAlignment = TextAlignment.Center };
            var tr = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            tr.Children.Add(grams);
            var unit = T("g", 13, "LabelTertiary");
            unit.VerticalAlignment = VerticalAlignment.Center;
            tr.Children.Add(unit);
            tr.Children.Add(Btn("Aggiungi", () =>
            {
                var g = BdParse(grams.Text);
                if (g == null || g <= 0) return;
                var id = isSaved ? food.Id : _r.Bd.SaveFood(food);
                _r.Bd.AddEntry(_bdDay, _bdMeal, food.Name, g.Value, food.Per100 * (g.Value / 100.0), id);
                BdGo("diary");
            }, "PrimaryButton"));
            var n = food.Per100;
            list.Children.Add(Row(food.Name + (food.Brand.Length > 0 ? " · " + food.Brand : ""), $"{BdN(n.Kcal)} kcal · P {BdN(n.P)} · C {BdN(n.C)} · G {BdN(n.F)} per 100 g", tr));
        }
        return list;
    }

    // ---------------------------------------------------------------- alimenti salvati

    private void BdFoods(StackPanel h)
    {
        var b = _r.Bd.State;
        h.Children.Add(BackRow("Corpo", "Alimenti", () => BdGo(null)));
        if (b.Foods.Count == 0) { h.Children.Add(Panelled(T("Nessun alimento salvato", 14, "LabelSecondary"))); return; }
        var list = new StackPanel { Spacing = 10 };
        foreach (var f in b.Foods)
        {
            var food = f;
            var n = food.Per100;
            list.Children.Add(Row(food.Name + (food.Brand.Length > 0 ? " · " + food.Brand : ""), $"{BdN(n.Kcal)} kcal · P {BdN(n.P)} · C {BdN(n.C)} · G {BdN(n.F)} per 100 g · porzione {food.Serving} g", Btn("Elimina", () => _r.Bd.RemoveFood(food.Id))));
        }
        h.Children.Add(Panelled(list));
    }

    // ---------------------------------------------------------------- piano settimanale

    private void BdPlan(StackPanel h)
    {
        var b = _r.Bd.State;
        h.Children.Add(BackRow("Corpo", "Piano", () => BdGo(null)));
        h.Children.Add(ChipScroll(BdDays.Select((d, i) => (UIElement)Chip(d, _bdDow == i, () => { _bdDow = i; RenderBody(); }))));
        var slots = b.Slots.Where(s => s.Dow == _bdDow).OrderBy(s => s.Minute).ToList();
        var tot = slots.Aggregate(default(Nut), (a, s) => a + s.Total);
        var sum = new StackPanel { Spacing = 10 };
        sum.Children.Add(T(BdDaysFull[_bdDow], 20, "LabelPrimary", true));
        sum.Children.Add(T($"{BdN(tot.Kcal)} / {b.Goals.Kcal} kcal", 15, "LabelSecondary"));
        sum.Children.Add(BdMacros(tot, b.Goals));
        h.Children.Add(Panelled(sum));
        if (slots.Count == 0) h.Children.Add(Panelled(T("Nessun pasto in questo giorno", 14, "LabelSecondary")));
        foreach (var s in slots)
        {
            var slot = s;
            var p = new StackPanel { Spacing = 6 };
            p.Children.Add(T($"{BdClock(slot.Minute)}  {slot.Label}", 17, "LabelPrimary", true));
            foreach (var i in slot.Items) p.Children.Add(T($"{i.Name} · {BdN(i.Grams)} g · {BdN(i.N.Kcal)} kcal", 13, "LabelSecondary"));
            var t = slot.Total;
            p.Children.Add(T($"{BdN(t.Kcal)} kcal · P {BdN(t.P)} · C {BdN(t.C)} · G {BdN(t.F)}", 13, "LabelTertiary"));
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            row.Children.Add(Btn("Registra", () => _r.Bd.LogSlot(slot, BodyStore.DayKey()), "SecondaryButton"));
            row.Children.Add(Btn("Elimina", () => _r.Bd.RemoveSlot(slot.Id)));
            p.Children.Add(row);
            h.Children.Add(Panelled(p));
        }
    }

    // ---------------------------------------------------------------- obiettivi

    private void BdGoals(StackPanel h)
    {
        var g0 = _r.Bd.State.Goals;
        h.Children.Add(BackRow("Corpo", "Obiettivi", () => BdGo(null)));
        TextBox Box(string v) => new() { Text = v, InputScope = new Microsoft.UI.Xaml.Input.InputScope { Names = { new Microsoft.UI.Xaml.Input.InputScopeName(Microsoft.UI.Xaml.Input.InputScopeNameValue.Number) } } };
        var kcal = Box(g0.Kcal.ToString(It)); var p = Box(g0.P.ToString(It)); var c = Box(g0.C.ToString(It)); var f = Box(g0.F.ToString(It)); var fi = Box(g0.Fiber.ToString(It));
        var goal = Box(g0.GoalKg10 > 0 ? BdG(g0.GoalKg10 / 10.0) : "");
        var height = Box(g0.Height > 0 ? g0.Height.ToString(It) : "");
        var age = Box(g0.Age > 0 ? g0.Age.ToString(It) : "");
        var form = new StackPanel { Spacing = 14 };
        form.Children.Add(Field("Calorie al giorno", kcal));
        form.Children.Add(Field("Proteine (g)", p));
        form.Children.Add(Field("Carboidrati (g)", c));
        form.Children.Add(Field("Grassi (g)", f));
        form.Children.Add(Field("Fibre (g)", fi));
        form.Children.Add(Field("Peso obiettivo (kg)", goal));
        form.Children.Add(Field("Altezza (cm)", height));
        form.Children.Add(Field("Età", age));
        h.Children.Add(Panelled(form));
        int I(TextBox t, int def) => BdParse(t.Text) is { } d ? (int)Math.Round(d) : def;
        var save = Btn("Salva", () =>
        {
            _r.Bd.SaveGoals(g0 with
            {
                Kcal = I(kcal, g0.Kcal), P = I(p, g0.P), C = I(c, g0.C), F = I(f, g0.F), Fiber = I(fi, g0.Fiber),
                GoalKg10 = BdParse(goal.Text) is { } gw ? (int)Math.Round(gw * 10) : 0, Height = I(height, 0), Age = I(age, 0),
            });
            BdGo(null);
        }, "PrimaryButton");
        save.HorizontalAlignment = HorizontalAlignment.Stretch;
        h.Children.Add(save);
    }
}
