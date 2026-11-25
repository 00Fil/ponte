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

    private Grid BdBar(double frac, Windows.UI.Color color)
    {
        frac = double.IsNaN(frac) ? 0 : Math.Clamp(frac, 0, 1);
        var g = new Grid { Height = 8 };
        g.Children.Add(new Border { Background = B("BgSunken"), CornerRadius = new CornerRadius(4) });
        var fill = new Border { Background = Sb(color), CornerRadius = new CornerRadius(4), HorizontalAlignment = HorizontalAlignment.Left };
        g.SizeChanged += (_, e) => fill.Width = Math.Max(0, e.NewSize.Width * frac);
        g.Children.Add(fill);
        return g;
    }

    private StackPanel BdMacro(string label, double v, double goal, Windows.UI.Color c)
    {
        var p = new StackPanel { Spacing = 4 };
        p.Children.Add(T(label, 12, "LabelTertiary"));
        p.Children.Add(T(goal > 0 ? $"{BdN(v)} / {BdN(goal)} g" : $"{BdN(v)} g", 15, "LabelPrimary", true));
        p.Children.Add(BdBar(goal > 0 ? v / goal : 0, c));
        return p;
    }

    private Grid BdMacros(Nut tot, Goals g)
    {
        var grid = new Grid { ColumnSpacing = 14 };
        for (var i = 0; i < 3; i++) grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var cols = new[] { BdMacro("Proteine", tot.P, g.P, MacroP), BdMacro("Carboidrati", tot.C, g.C, MacroC), BdMacro("Grassi", tot.F, g.F, MacroF) };
        for (var i = 0; i < 3; i++) { Grid.SetColumn(cols[i], i); grid.Children.Add(cols[i]); }
        return grid;
    }

    private static Nut BdDayTotal(BodyState b, string day) => b.Entries.Where(e => e.Day == day).Aggregate(default(Nut), (a, e) => a + e.N);

    // ---------------------------------------------------------------- cruscotto

    private void BdDashboard(StackPanel h)
    {
        var b = _r.Bd.State;
        var today = BodyStore.DayKey();
        var tot = BdDayTotal(b, today);
        var g = b.Goals;

        var hero = new StackPanel { Spacing = 12 };
        hero.Children.Add(T("Oggi", 13, "LabelTertiary"));
        hero.Children.Add(T($"{BdN(tot.Kcal)} / {g.Kcal} kcal", 34, "LabelPrimary", true));
        hero.Children.Add(BdBar(g.Kcal > 0 ? tot.Kcal / g.Kcal : 0, tot.Kcal > g.Kcal ? MacroF : C("AccentDefault")));
        hero.Children.Add(T(tot.Kcal > g.Kcal ? $"{BdN(tot.Kcal - g.Kcal)} kcal oltre l'obiettivo" : $"{BdN(Math.Max(0, g.Kcal - tot.Kcal))} kcal rimaste", 13, "LabelSecondary"));
        hero.Children.Add(BdMacros(tot, g));
        h.Children.Add(Widget(hero));

        var acts = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 26, HorizontalAlignment = HorizontalAlignment.Center };
        acts.Children.Add(ActionBubble("", "Diario", true, () => { _bdDay = today; BdGo("diary"); return Task.CompletedTask; }));
        acts.Children.Add(ActionBubble("", "Piano", false, () => { BdGo("plan"); return Task.CompletedTask; }));
        acts.Children.Add(ActionBubble("", "Alimenti", false, () => { BdGo("foods"); return Task.CompletedTask; }));
        acts.Children.Add(ActionBubble("", "Obiettivi", false, () => { BdGo("goals"); return Task.CompletedTask; }));
        h.Children.Add(acts);

        // prossimo pasto del piano
        var dow = ((int)DateTime.Today.DayOfWeek + 6) % 7;
        var nowMin = DateTime.Now.Hour * 60 + DateTime.Now.Minute;
        var next = b.Slots.FirstOrDefault(s => s.Dow == dow && s.Minute >= nowMin - 30);
        var tomorrow = false;
        if (next == null) { next = b.Slots.Where(s => s.Dow == (dow + 1) % 7).OrderBy(s => s.Minute).FirstOrDefault(); tomorrow = next != null; }
        if (next != null)
        {
            var p = new StackPanel { Spacing = 8 };
            p.Children.Add(T(tomorrow ? "Domani" : "Prossimo pasto", 13, "LabelTertiary"));
            p.Children.Add(T($"{BdClock(next.Minute)}  {next.Label}", 20, "LabelPrimary", true));
            var line = T(string.Join(" · ", next.Items.Select(i => $"{i.Name} {BdN(i.Grams)} g")), 13, "LabelSecondary");
            line.TextWrapping = TextWrapping.Wrap;
            line.TextTrimming = TextTrimming.None;
            p.Children.Add(line);
            var t = next.Total;
            p.Children.Add(T($"{BdN(t.Kcal)} kcal · P {BdN(t.P)} · C {BdN(t.C)} · G {BdN(t.F)}", 13, "LabelTertiary"));
            if (!tomorrow)
            {
                var slot = next;
                var log = Btn("Segna come mangiato", () => { _r.Bd.LogSlot(slot, today); }, "PrimaryButton");
                log.HorizontalAlignment = HorizontalAlignment.Left;
                p.Children.Add(log);
            }
            h.Children.Add(Widget(p));
        }

        // peso
        var w = new StackPanel { Spacing = 10 };
        w.Children.Add(T("Peso", 13, "LabelTertiary"));
        if (b.Weights.Count > 0)
        {
            var last = b.Weights[^1];
            var first = b.Weights[0];
            var delta = last.Kg - first.Kg;
            var head = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 14 };
            head.Children.Add(T(BdG(last.Kg) + " kg", 34, "LabelPrimary", true));
            if (b.Weights.Count > 1) head.Children.Add(new TextBlock { Text = (delta > 0 ? "+" : delta < 0 ? "−" : "") + BdG(Math.Abs(delta)) + " kg dall'inizio", FontSize = 13, Foreground = B("LabelSecondary"), VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 6) });
            w.Children.Add(head);
            if (g.GoalKg10 > 0) w.Children.Add(T($"Obiettivo {BdG(g.GoalKg10 / 10.0)} kg · mancano {BdG(Math.Max(0, last.Kg - g.GoalKg10 / 10.0))} kg", 13, "LabelSecondary"));
            if (b.Weights.Count > 1) w.Children.Add(BdWeightChart(b.Weights));
        }
        var box = new TextBox { PlaceholderText = "Peso di oggi (kg)", Text = _bdWeight, Width = 200 };
        box.TextChanged += (_, _) => _bdWeight = box.Text;
        var save = Btn("Registra", () =>
        {
            var v = BdParse(_bdWeight);
            if (v == null || v < 30 || v > 300) return;
            _r.Bd.SetWeight(today, (int)Math.Round(v.Value * 10));
            _bdWeight = "";
        }, "SecondaryButton");
        var rowW = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        rowW.Children.Add(box);
        rowW.Children.Add(save);
        w.Children.Add(rowW);
        h.Children.Add(Widget(w));
    }

    private FrameworkElement BdWeightChart(List<Weigh> ws)
    {
        var tail = ws.Count > 60 ? ws.Skip(ws.Count - 60).ToList() : ws;
        var lo = tail.Min(x => x.Kg); var hi = tail.Max(x => x.Kg);
        var span = Math.Max(0.5, hi - lo);
        var pts = new PointCollection();
        for (var i = 0; i < tail.Count; i++) pts.Add(new Point(100.0 * i / Math.Max(1, tail.Count - 1), 100 - (tail[i].Kg - lo) / span * 100));
        return new Polyline { Points = pts, Stroke = B("AccentDefault"), StrokeThickness = 2.5, StrokeLineJoin = PenLineJoin.Round, Stretch = Stretch.Fill, Height = 90, Margin = new Thickness(0, 6, 0, 6) };
    }

    // ---------------------------------------------------------------- diario

    private void BdDiary(StackPanel h)
    {
        var b = _r.Bd.State;
        h.Children.Add(BackRow("Corpo", "Diario", () => BdGo(null)));
        var day = BodyStore.KeyTime(_bdDay);
        var nav = new Grid { ColumnSpacing = 10 };
        nav.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        nav.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        nav.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        nav.Children.Add(Btn("‹", () => { _bdDay = BodyStore.DayKey(day.AddDays(-1)); RenderBody(); }));
        var title = T(day.Date == DateTime.Today ? "Oggi" : day.ToString("dddd d MMMM", It), 16, "LabelPrimary", true);
        title.HorizontalAlignment = HorizontalAlignment.Center; title.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(title, 1); nav.Children.Add(title);
        var nx = Btn("›", () => { _bdDay = BodyStore.DayKey(day.AddDays(1)); RenderBody(); });
        Grid.SetColumn(nx, 2); nav.Children.Add(nx);
        h.Children.Add(nav);

        var tot = BdDayTotal(b, _bdDay);
        var sum = new StackPanel { Spacing = 10 };
        sum.Children.Add(T($"{BdN(tot.Kcal)} / {b.Goals.Kcal} kcal", 24, "LabelPrimary", true));
        sum.Children.Add(BdBar(b.Goals.Kcal > 0 ? tot.Kcal / b.Goals.Kcal : 0, tot.Kcal > b.Goals.Kcal ? MacroF : C("AccentDefault")));
        sum.Children.Add(BdMacros(tot, b.Goals));
        h.Children.Add(Widget(sum));

        for (var meal = 0; meal < 4; meal++)
        {
            var m = meal;
            var list = b.Entries.Where(e => e.Day == _bdDay && e.Meal == m).ToList();
            var card = new StackPanel { Spacing = 8 };
            var head = new Grid();
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            head.Children.Add(T(BodyStore.Meals[m], 16, "LabelPrimary", true));
            var kc = T($"{BdN(list.Sum(e => e.N.Kcal))} kcal", 13, "LabelTertiary");
            Grid.SetColumn(kc, 1); head.Children.Add(kc);
            card.Children.Add(head);
            foreach (var e in list)
            {
                var en = e;
                var tr = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
                tr.Children.Add(Btn("−", () => _r.Bd.SetGrams(en, Math.Max(10, en.Grams - 10))));
                tr.Children.Add(Btn("+", () => _r.Bd.SetGrams(en, en.Grams + 10)));
                tr.Children.Add(Btn("Elimina", () => _r.Bd.RemoveEntry(en.Id)));
                card.Children.Add(Row(e.Name, $"{BdG(e.Grams)} g · {BdN(e.N.Kcal)} kcal · P {BdN(e.N.P)} · C {BdN(e.N.C)} · G {BdN(e.N.F)}", tr));
            }
            var add = Btn("Aggiungi", () => { _bdMeal = m; _bdQuery = ""; _bdResults = null; BdGo("add"); });
            add.HorizontalAlignment = HorizontalAlignment.Left;
            card.Children.Add(add);
            h.Children.Add(Widget(card));
        }
    }

    // ---------------------------------------------------------------- aggiungi un alimento

    private void BdAdd(StackPanel h)
    {
        var b = _r.Bd.State;
        h.Children.Add(BackRow("Diario", BodyStore.Meals[_bdMeal], () => BdGo("diary")));

        var q = new TextBox { PlaceholderText = "Cerca un alimento, esselunga … o codice a barre", Text = _bdQuery };
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
        h.Children.Add(Widget(bar));

        var qt = _bdQuery.Trim();
        var saved = b.Foods.Where(f => qt.Length == 0 || (f.Name + " " + f.Brand).Contains(qt, StringComparison.CurrentCultureIgnoreCase)).Take(30).ToList();
        if (saved.Count > 0) { h.Children.Add(T("I miei alimenti", 13, "LabelTertiary")); h.Children.Add(Widget(BdFoodList(saved, true))); }
        if (_bdResults != null)
        {
            h.Children.Add(T("Risultati", 13, "LabelTertiary"));
            h.Children.Add(_bdResults.Count == 0 ? Widget(T("Nessun risultato", 14, "LabelSecondary")) : Widget(BdFoodList(_bdResults, false)));
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
        if (b.Foods.Count == 0) { h.Children.Add(Widget(T("Nessun alimento salvato", 14, "LabelSecondary"))); return; }
        var list = new StackPanel { Spacing = 10 };
        foreach (var f in b.Foods)
        {
            var food = f;
            var n = food.Per100;
            list.Children.Add(Row(food.Name + (food.Brand.Length > 0 ? " · " + food.Brand : ""), $"{BdN(n.Kcal)} kcal · P {BdN(n.P)} · C {BdN(n.C)} · G {BdN(n.F)} per 100 g · porzione {food.Serving} g", Btn("Elimina", () => _r.Bd.RemoveFood(food.Id))));
        }
        h.Children.Add(Widget(list));
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
        h.Children.Add(Widget(sum));
        if (slots.Count == 0) h.Children.Add(Widget(T("Nessun pasto in questo giorno", 14, "LabelSecondary")));
        foreach (var s in slots)
        {
            var slot = s;
            var p = new StackPanel { Spacing = 6 };
            p.Children.Add(T($"{BdClock(slot.Minute)}  {slot.Label}", 17, "LabelPrimary", true));
            foreach (var i in slot.Items) p.Children.Add(T($"{i.Name} · {BdN(i.Grams)} g · {BdN(i.N.Kcal)} kcal", 13, "LabelSecondary"));
            var t = slot.Total;
            p.Children.Add(T($"{BdN(t.Kcal)} kcal · P {BdN(t.P)} · C {BdN(t.C)} · G {BdN(t.F)}", 13, "LabelTertiary"));
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            row.Children.Add(Btn("Segna come mangiato", () => _r.Bd.LogSlot(slot, BodyStore.DayKey()), "SecondaryButton"));
            row.Children.Add(Btn("Elimina", () => _r.Bd.RemoveSlot(slot.Id)));
            p.Children.Add(row);
            h.Children.Add(Widget(p));
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
        h.Children.Add(Widget(form));
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
