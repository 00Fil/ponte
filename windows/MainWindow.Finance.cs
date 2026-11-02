using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Ponte;

/// <summary>
/// Finanze come su Android: cruscotto di soli grafici (saldo con l'occhio, ritmo di spesa contro il mese prima con previsione,
/// entrate/uscite, mese per mese, dove vanno i soldi) e le funzioni nelle sotto-pagine: nuova spesa/entrata col tastierino rotondo,
/// contanti a banconote, movimenti, Revolut (estratto CSV e saldo). Stessi dati, sincronizzati con il telefono.
/// </summary>
public sealed partial class MainWindow
{
    private string? _finSub;
    private int _finYear = DateTime.Today.Year;
    private int _finMonth = DateTime.Today.Month - 1; // -1 = tutto l'anno
    private string _amount = "";
    private bool _income;
    private string _cat = "";
    private string _note = "";
    private bool _cashSpend = true;
    private Dictionary<int, int> _tally = new();
    private static readonly CultureInfo It = CultureInfo.GetCultureInfo("it-IT");
    private static readonly string[] Months = { "gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic" };

    private (long From, long To) Range(int y, int m)
    {
        var a = new DateTime(y, m < 0 ? 1 : m + 1, 1);
        var b = m < 0 ? a.AddYears(1) : a.AddMonths(1);
        return (new DateTimeOffset(a).ToUnixTimeMilliseconds(), new DateTimeOffset(b).ToUnixTimeMilliseconds());
    }

    private void RenderFinance()
    {
        var h = Reset("Finanze");
        switch (_finSub)
        {
            case "add": FinAdd(h); break;
            case "cash": FinCash(h); break;
            case "moves": FinMoves(h); break;
            case "revolut": FinRevolut(h); break;
            default: FinDashboard(h); break;
        }
    }

    private void FinGo(string? sub)
    {
        _finSub = sub;
        RenderFinance();
        foreach (var el in _hosts[_tab].Children.Take(3)) Enter(el, sub == null ? -12 : 12, 0, 320);
    }

    // ---------------------------------------------------------------- cruscotto

    private void FinDashboard(StackPanel h)
    {
        var m = _r.Fin.State;
        var (from, to) = Range(_finYear, _finMonth);
        var inPeriod = m.Txs.Where(t => t.Ts >= from && t.Ts < to).ToList();
        var spent = -inPeriod.Where(t => t.Cents < 0).Sum(t => t.Cents);
        var earned = inPeriod.Where(t => t.Cents > 0).Sum(t => t.Cents);

        // saldo + ritmo di spesa
        var hero = new StackPanel { Spacing = 10 };
        var head = new Grid();
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.Children.Add(T("Saldo", 13, "LabelTertiary"));
        var eye = EyeToggle();
        Grid.SetColumn(eye, 1);
        head.Children.Add(eye);
        hero.Children.Add(head);
        hero.Children.Add(MoneyText("fin.total", m.TotalCents, 38));
        hero.Children.Add(PaceChart(m));
        var split = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 28 };
        foreach (var (label, v, key) in new[] { ("Contanti", m.CashCents, "cash"), ("Revolut", m.RevolutCents, "rev"), ("Altro", m.OtherCents, "oth") })
        {
            var c = new StackPanel();
            c.Children.Add(T(label, 12, "LabelTertiary"));
            c.Children.Add(MoneyText("fin." + key, v, 15, "LabelPrimary", false));
            split.Children.Add(c);
        }
        hero.Children.Add(split);
        h.Children.Add(Widget(hero));

        // azioni
        var acts = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 26, HorizontalAlignment = HorizontalAlignment.Center };
        acts.Children.Add(ActionBubble("", "Spesa", true, () => { _income = false; FinGo("add"); return Task.CompletedTask; }));
        acts.Children.Add(ActionBubble("", "Entrata", false, () => { _income = true; FinGo("add"); return Task.CompletedTask; }));
        acts.Children.Add(ActionBubble("", "Contanti", false, () => { _cashSpend = true; _tally = new(); FinGo("cash"); return Task.CompletedTask; }));
        acts.Children.Add(ActionBubble("", "Revolut", false, () => { FinGo("revolut"); return Task.CompletedTask; }));
        acts.Children.Add(ActionBubble("", "Movimenti", false, () => { FinGo("moves"); return Task.CompletedTask; }));
        h.Children.Add(acts);

        // periodo
        h.Children.Add(Segmented("finper", new[] { "Mese", "Anno" }, _finMonth < 0 ? 1 : 0, i => { _finMonth = i == 1 ? -1 : DateTime.Today.Month - 1; RenderFinance(); }));
        h.Children.Add(PeriodRow());

        // KPI
        var k = new Grid { ColumnSpacing = 12, RowSpacing = 12 };
        k.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        k.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        k.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        k.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var days = _finMonth < 0 ? (_finYear == DateTime.Today.Year ? DateTime.Today.DayOfYear : 365)
            : (_finYear == DateTime.Today.Year && _finMonth == DateTime.Today.Month - 1 ? DateTime.Today.Day : DateTime.DaysInMonth(_finYear, _finMonth + 1));
        var kpis = new[] { ("Entrate", earned), ("Uscite", spent), ("Risparmio", earned - spent), ("Al giorno", days > 0 ? spent / days : 0) };
        for (var i = 0; i < 4; i++)
        {
            var c = new StackPanel { Spacing = 2 };
            c.Children.Add(T(kpis[i].Item1, 12, "LabelTertiary"));
            c.Children.Add(MoneyText("fin.kpi" + i, kpis[i].Item2, 20, kpis[i].Item2 < 0 ? "CriticalOnSubtle" : "LabelPrimary"));
            var w = Widget(c);
            Grid.SetColumn(w, i % 2);
            Grid.SetRow(w, i / 2);
            k.Children.Add(w);
        }
        h.Children.Add(k);

        // mese per mese
        var bars = new List<(string L, long In, long Out)>();
        for (var i = 0; i < (_finMonth < 0 ? 12 : 6); i++)
        {
            int y = _finYear, mo;
            if (_finMonth < 0) mo = i;
            else { mo = _finMonth - 5 + i; while (mo < 0) { mo += 12; y--; } }
            var (a, b) = Range(y, mo);
            var l = m.Txs.Where(t => t.Ts >= a && t.Ts < b).ToList();
            bars.Add((Months[mo], l.Where(t => t.Cents > 0).Sum(t => t.Cents), -l.Where(t => t.Cents < 0).Sum(t => t.Cents)));
        }
        var bc = new StackPanel { Spacing = 10 };
        bc.Children.Add(T(_finMonth < 0 ? "Mese per mese" : "Ultimi mesi", 17, "LabelPrimary", true));
        bc.Children.Add(HideChart(BarChart(bars)));
        h.Children.Add(Widget(bc));

        // dove vanno i soldi
        var dc = new StackPanel { Spacing = 10 };
        dc.Children.Add(T("Dove vanno i soldi", 17, "LabelPrimary", true));
        dc.Children.Add(HideChart(Donut(inPeriod.Where(t => t.Cents < 0).ToList())));
        h.Children.Add(Widget(dc));
    }

    private Grid PeriodRow()
    {
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        void Step(int d)
        {
            if (_finMonth < 0) _finYear += d;
            else { var mo = _finMonth + d; if (mo < 0) { mo = 11; _finYear--; } else if (mo > 11) { mo = 0; _finYear++; } _finMonth = mo; }
            RenderFinance();
        }
        g.Children.Add(BubbleButton("", 36, () => Step(-1)));
        var title = _finMonth < 0 ? _finYear.ToString() : It.TextInfo.ToTitleCase(new DateTime(_finYear, _finMonth + 1, 1).ToString("MMMM yyyy", It));
        var t = T(title, 20, "LabelPrimary", true);
        t.HorizontalAlignment = HorizontalAlignment.Center;
        t.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(t, 1);
        g.Children.Add(t);
        var n = BubbleButton("", 36, () => Step(1));
        Grid.SetColumn(n, 2);
        g.Children.Add(n);
        return g;
    }

    /// <summary>Ritmo di spesa: spesa cumulata di questo periodo (linea piena) contro il periodo prima (grigia), con la previsione tratteggiata.</summary>
    private StackPanel PaceChart(Money m)
    {
        var year = _finMonth < 0;
        var buckets = year ? 12 : DateTime.DaysInMonth(_finYear, _finMonth + 1);
        var (from, to) = Range(_finYear, _finMonth);
        var (pf, pt) = year ? Range(_finYear - 1, -1) : _finMonth == 0 ? Range(_finYear - 1, 11) : Range(_finYear, _finMonth - 1);
        int Bucket(long ts) { var d = DateTimeOffset.FromUnixTimeMilliseconds(ts).LocalDateTime; return Math.Clamp(year ? d.Month - 1 : d.Day - 1, 0, buckets - 1); }
        var cur = new double[buckets];
        var prev = new double[buckets];
        foreach (var t in m.Txs)
        {
            if (t.Cents >= 0) continue;
            if (t.Ts >= from && t.Ts < to) cur[Bucket(t.Ts)] += -t.Cents / 100.0;
            else if (t.Ts >= pf && t.Ts < pt) prev[Bucket(t.Ts)] += -t.Cents / 100.0;
        }
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var ongoing = now >= from && now < to;
        var last = ongoing ? Bucket(now) + 1 : now < from ? 0 : buckets;
        double acc = 0;
        var curCum = Enumerable.Range(0, last).Select(i => acc += cur[i]).ToArray();
        double acc2 = 0;
        var prevCum = Enumerable.Range(0, buckets).Select(i => acc2 += prev[i]).ToArray();
        double? proj = ongoing && last >= 3 && last < buckets ? curCum[^1] / last * buckets : null;

        var col = new StackPanel { Spacing = 6 };
        var read = new Grid { Height = 20 };
        var readL = T("", 13, "LabelSecondary", true);
        var readR = T("", 13, "LabelTertiary");
        readR.HorizontalAlignment = HorizontalAlignment.Right;
        read.Children.Add(readL);
        read.Children.Add(readR);
        void Idle()
        {
            var diff = (curCum.Length > 0 ? curCum[^1] : 0) - (curCum.Length > 0 ? prevCum[Math.Min(curCum.Length - 1, buckets - 1)] : 0);
            readL.Text = curCum.Length == 0 ? "Nessuna spesa ancora" : Math.Abs(diff) < 0.5 ? "Come il periodo prima" : (diff > 0 ? "+" : "−") + Finance.Eur((long)Math.Abs(diff * 100)) + " rispetto a prima";
            readL.Foreground = diff > 0.5 ? B("CriticalDefault") : Sb(Argb(255, 0x1F, 0x9A, 0x5A));
            readR.Text = proj != null ? "a fine " + (year ? "anno" : "mese") + " ~" + Finance.Eur((long)(proj.Value * 100)) : "";
        }
        Idle();
        col.Children.Add(read);

        var canvas = new Canvas { Height = 120, Background = Sb(Microsoft.UI.Colors.Transparent) };
        var accent = C("AccentDefault");
        void Draw()
        {
            canvas.Children.Clear();
            var w = canvas.ActualWidth;
            var hh = canvas.Height;
            if (w < 10 || buckets < 2) return;
            var hi = Math.Max(1, new[] { curCum.DefaultIfEmpty(0).Max(), prevCum.DefaultIfEmpty(0).Max(), proj ?? 0 }.Max()) * 1.08;
            double X(int i) => w * i / (buckets - 1);
            double Y(double v) => hh * (1 - v / hi);
            for (var k = 1; k <= 3; k++) canvas.Children.Add(new Line { X1 = 0, X2 = w, Y1 = hh * k / 4, Y2 = hh * k / 4, Stroke = B("LineSeparator"), StrokeThickness = 1 });
            var pp = new Polyline { Stroke = Sb(WithA(C("LabelTertiary"), 150)), StrokeThickness = 2, StrokeLineJoin = PenLineJoin.Round };
            for (var i = 0; i < buckets; i++) pp.Points.Add(new Point(X(i), Y(prevCum[i])));
            canvas.Children.Add(pp);
            if (curCum.Length > 0)
            {
                var area = new Polygon { Fill = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(0, 1), GradientStops = { new GradientStop { Color = WithA(accent, 80), Offset = 0 }, new GradientStop { Color = WithA(accent, 0), Offset = 1 } } } };
                area.Points.Add(new Point(X(0), hh));
                var line = new Polyline { Stroke = Sb(accent), StrokeThickness = 3, StrokeLineJoin = PenLineJoin.Round, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round };
                for (var i = 0; i < curCum.Length; i++) { var p = new Point(X(i), Y(curCum[i])); line.Points.Add(p); area.Points.Add(p); }
                area.Points.Add(new Point(X(curCum.Length - 1), hh));
                canvas.Children.Add(area);
                canvas.Children.Add(line);
                if (proj != null)
                    canvas.Children.Add(new Line { X1 = X(curCum.Length - 1), Y1 = Y(curCum[^1]), X2 = X(buckets - 1), Y2 = Y(proj.Value), Stroke = Sb(WithA(accent, 150)), StrokeThickness = 2.4, StrokeDashArray = new DoubleCollection { 3, 3 } });
                var dot = new Ellipse { Width = 10, Height = 10, Fill = Sb(accent), Stroke = Sb(Microsoft.UI.Colors.White), StrokeThickness = 2 };
                Canvas.SetLeft(dot, X(curCum.Length - 1) - 5);
                Canvas.SetTop(dot, Y(curCum[^1]) - 5);
                canvas.Children.Add(dot);
            }
        }
        canvas.SizeChanged += (_, _) => Draw();
        canvas.PointerMoved += (_, e) =>
        {
            var x = e.GetCurrentPoint(canvas).Position.X;
            var i = (int)Math.Clamp(Math.Round(x / Math.Max(1, canvas.ActualWidth) * (buckets - 1)), 0, buckets - 1);
            readL.Text = (year ? Months[i] : $"giorno {i + 1}") + (i < curCum.Length ? "   " + Finance.Eur((long)(curCum[i] * 100)) : "");
            readL.Foreground = Sb(accent);
            readR.Text = "prima " + Finance.Eur((long)(prevCum[i] * 100));
        };
        canvas.PointerExited += (_, _) => Idle();
        col.Children.Add(HideChart(canvas));
        var legend = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16 };
        legend.Children.Add(Legend(year ? "Quest'anno" : "Questo mese", accent));
        legend.Children.Add(Legend(year ? "Anno prima" : "Mese prima", C("LabelTertiary")));
        if (proj != null) legend.Children.Add(Legend("Previsione", WithA(accent, 140)));
        col.Children.Add(legend);
        return col;
    }

    private StackPanel Legend(string text, Windows.UI.Color color)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        p.Children.Add(new Ellipse { Width = 8, Height = 8, Fill = Sb(color), VerticalAlignment = VerticalAlignment.Center });
        p.Children.Add(T(text, 12, "LabelSecondary"));
        return p;
    }

    /// <summary>Colonne entrate/uscite; passa sopra o tocca una colonna per il valore preciso.</summary>
    private StackPanel BarChart(List<(string L, long In, long Out)> bars)
    {
        var col = new StackPanel { Spacing = 8 };
        var read = T("Passa sopra una colonna", 13, "LabelTertiary");
        col.Children.Add(read);
        var max = Math.Max(1, bars.Max(b => Math.Max(b.In, b.Out)));
        var g = new Grid { Height = 140, ColumnSpacing = 8 };
        for (var i = 0; i < bars.Count; i++)
        {
            var b = bars[i];
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var cell = new Grid { Background = Sb(Microsoft.UI.Colors.Transparent) };
            cell.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            cell.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var pair = new Grid { ColumnSpacing = 3 };
            pair.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            pair.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var rin = new Border { VerticalAlignment = VerticalAlignment.Bottom, CornerRadius = new CornerRadius(4), Background = B("AccentDefault") };
            var rout = new Border { VerticalAlignment = VerticalAlignment.Bottom, CornerRadius = new CornerRadius(4), Background = B("LabelTertiary") };
            Grid.SetColumn(rout, 1);
            pair.Children.Add(rin);
            pair.Children.Add(rout);
            pair.SizeChanged += (_, _) => { rin.Height = Math.Max(3, pair.ActualHeight * b.In / max); rout.Height = Math.Max(3, pair.ActualHeight * b.Out / max); };
            cell.Children.Add(pair);
            var lbl = T(b.L, 11, "LabelTertiary");
            lbl.HorizontalAlignment = HorizontalAlignment.Center;
            Grid.SetRow(lbl, 1);
            cell.Children.Add(lbl);
            void Show() { read.Text = $"{b.L}   +{Finance.Eur(b.In)}   −{Finance.Eur(b.Out)}"; read.Foreground = B("LabelPrimary"); }
            cell.PointerEntered += (_, _) => Show();
            cell.Tapped += (_, _) => Show();
            Grid.SetColumn(cell, i);
            g.Children.Add(cell);
        }
        col.Children.Add(g);
        var legend = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16 };
        legend.Children.Add(Legend("Entrate", C("AccentDefault")));
        legend.Children.Add(Legend("Uscite", C("LabelTertiary")));
        col.Children.Add(legend);
        return col;
    }

    /// <summary>Ciambella per categoria: passa o tocca uno spicchio o una voce e al centro compare l'importo preciso.</summary>
    private UIElement Donut(List<Tx> list)
    {
        var by = list.GroupBy(t => t.Cat).Select(g => (Cat: g.Key, V: Math.Abs(g.Sum(t => t.Cents)))).OrderByDescending(x => x.V).ToList();
        if (by.Count == 0) return T("Nessuna spesa in questo periodo.", 13, "LabelTertiary");
        var total = Math.Max(1, by.Sum(x => x.V));
        var row = new Grid { ColumnSpacing = 20 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        const double size = 140, thick = 22;
        var box = new Grid { Width = size, Height = size };
        var center = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        var cl = T("Totale", 12, "LabelTertiary"); cl.HorizontalAlignment = HorizontalAlignment.Center;
        var cv = T(Finance.Eur(total), 15, "LabelPrimary", true); cv.HorizontalAlignment = HorizontalAlignment.Center;
        center.Children.Add(cl);
        center.Children.Add(cv);
        void Pick(int i) { cl.Text = i < 0 ? "Totale" : by[i].Cat; cv.Text = Finance.Eur(i < 0 ? total : by[i].V); }
        double a0 = -90;
        var r = (size - thick) / 2;
        var c0 = new Point(size / 2, size / 2);
        Point P(double deg) => new(c0.X + r * Math.Cos(deg * Math.PI / 180), c0.Y + r * Math.Sin(deg * Math.PI / 180));
        for (var i = 0; i < by.Count; i++)
        {
            var idx = i;
            var sweep = Math.Max(0.5, 360.0 * by[i].V / total - (by.Count > 1 ? 2 : 0));
            if (sweep >= 359.9) sweep = 359.9;
            var fig = new PathFigure { StartPoint = P(a0), IsClosed = false };
            fig.Segments.Add(new ArcSegment { Point = P(a0 + sweep), Size = new Size(r, r), IsLargeArc = sweep > 180, SweepDirection = SweepDirection.Clockwise });
            var geo = new PathGeometry();
            geo.Figures.Add(fig);
            var path = new Microsoft.UI.Xaml.Shapes.Path { Data = geo, Stroke = Sb(CatColor(by[i].Cat)), StrokeThickness = thick, StrokeStartLineCap = PenLineCap.Flat, StrokeEndLineCap = PenLineCap.Flat };
            path.PointerEntered += (_, _) => Pick(idx);
            path.Tapped += (_, _) => Pick(idx);
            path.PointerExited += (_, _) => Pick(-1);
            box.Children.Add(path);
            a0 += 360.0 * by[i].V / total;
        }
        box.Children.Add(center);
        row.Children.Add(box);
        var leg = new StackPanel { Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
        for (var i = 0; i < Math.Min(6, by.Count); i++)
        {
            var idx = i;
            var lr = new Grid { ColumnSpacing = 8, Background = Sb(Microsoft.UI.Colors.Transparent), Padding = new Thickness(4, 2, 4, 2) };
            lr.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            lr.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            lr.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            lr.Children.Add(new Ellipse { Width = 8, Height = 8, Fill = Sb(CatColor(by[i].Cat)), VerticalAlignment = VerticalAlignment.Center });
            var n = T(by[i].Cat, 13, "LabelSecondary"); Grid.SetColumn(n, 1); lr.Children.Add(n);
            var pc = T($"{by[i].V * 100 / total}%", 13, "LabelTertiary"); Grid.SetColumn(pc, 2); lr.Children.Add(pc);
            lr.PointerEntered += (_, _) => Pick(idx);
            lr.PointerExited += (_, _) => Pick(-1);
            lr.Tapped += (_, _) => Pick(idx);
            leg.Children.Add(lr);
        }
        Grid.SetColumn(leg, 1);
        row.Children.Add(leg);
        return row;
    }

    // ---------------------------------------------------------------- nuova spesa / entrata

    private void FinAdd(StackPanel h)
    {
        h.Children.Add(BackRow("Finanze", _income ? "Nuova entrata" : "Nuova spesa", () => FinGo(null)));
        h.Children.Add(Segmented("finadd", new[] { "Spesa", "Entrata" }, _income ? 1 : 0, i => { _income = i == 1; RenderFinance(); }));
        var amt = T("€" + (_amount.Length > 0 ? _amount : "0"), 52, _amount.Length > 0 ? "LabelPrimary" : "LabelTertiary", true);
        amt.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
        amt.HorizontalAlignment = HorizontalAlignment.Center;
        amt.FontFamily = (FontFamily)Application.Current.Resources["FontMono"];
        h.Children.Add(amt);
        var chips = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var k in new[] { "" }.Concat(_income ? new[] { "Entrate" } : Finance.Categories.Take(Finance.Categories.Length - 1)))
        {
            var kk = k;
            chips.Children.Add(Chip(k.Length > 0 ? k : "Auto", _cat == k, () => { _cat = kk; RenderFinance(); }));
        }
        h.Children.Add(new ScrollViewer { HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, HorizontalScrollMode = ScrollMode.Auto, VerticalScrollMode = ScrollMode.Disabled, Content = chips });
        var note = new TextBox { Text = _note, PlaceholderText = "Nota (dove o cosa)", MaxWidth = 420 };
        note.TextChanged += (_, _) => _note = note.Text;
        h.Children.Add(note);

        void Press(string k)
        {
            if (k == "⌫") _amount = _amount.Length > 0 ? _amount[..^1] : "";
            else if (k == ",") { if (!_amount.Contains(',')) _amount = _amount.Length == 0 ? "0," : _amount + ","; }
            else
            {
                var dec = _amount.Contains(',') ? _amount[(_amount.IndexOf(',') + 1)..] : "";
                if (!(_amount.Contains(',') && dec.Length >= 2) && _amount.Length < 9) _amount = _amount == "0" ? k : _amount + k;
            }
            amt.Text = "€" + (_amount.Length > 0 ? _amount : "0");
            amt.Foreground = B(_amount.Length > 0 ? "LabelPrimary" : "LabelTertiary");
        }
        // tastierino vero: tre colonne strette e centrate, tasti rotondi di vetro
        var pad = new Grid { Width = 276, HorizontalAlignment = HorizontalAlignment.Center, RowSpacing = 12 };
        for (var c = 0; c < 3; c++) pad.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var keys = new[] { "1", "2", "3", "4", "5", "6", "7", "8", "9", ",", "0", "⌫" };
        for (var i = 0; i < keys.Length; i++)
        {
            if (i % 3 == 0) pad.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var k = keys[i];
            var key = new Grid { Width = 72, Height = 72, Background = Sb(Microsoft.UI.Colors.Transparent) };
            key.Children.Add(new Border { CornerRadius = new CornerRadius(36), Background = GlassBody(), BorderBrush = GlassEdge(), BorderThickness = new Thickness(1) });
            var kt = T(k, 26, "LabelPrimary", true);
            kt.HorizontalAlignment = HorizontalAlignment.Center;
            kt.VerticalAlignment = VerticalAlignment.Center;
            key.Children.Add(kt);
            key.Tapped += (_, _) => Press(k);
            Bounce(key, 1.12f);
            Grid.SetColumn(key, i % 3);
            Grid.SetRow(key, i / 3);
            pad.Children.Add(key);
        }
        h.Children.Add(pad);
        // si scrive anche da tastiera
        h.KeyDown += (_, e) =>
        {
            var vk = (int)e.Key;
            if (vk >= 48 && vk <= 57) Press(((char)vk).ToString());
            else if (vk >= 96 && vk <= 105) Press((vk - 96).ToString());
            else if (e.Key == Windows.System.VirtualKey.Back) Press("⌫");
            else if (vk == 188 || vk == 110 || vk == 190) Press(",");
        };
        var confirm = Btn(_income ? "Aggiungi entrata" : "Aggiungi spesa", () =>
        {
            var v = Finance.ToCents(_amount);
            if (v == null || v == 0) return;
            var k = _cat.Length > 0 ? _cat : _income ? "Entrate" : Finance.Guess(_note);
            var before = _r.Fin.State.TotalCents;
            _r.Fin.Add(_income ? v.Value : -v.Value, k, _note.Trim());
            FlashMoney(before, _income ? v.Value : -v.Value);
            _amount = ""; _note = ""; _cat = "";
            FinGo(null);
        }, "PrimaryButton");
        confirm.HorizontalAlignment = HorizontalAlignment.Center;
        confirm.MinWidth = 276;
        Bounce(confirm);
        h.Children.Add(confirm);
    }

    // ---------------------------------------------------------------- contanti

    private static Windows.UI.Color NoteColor(int d) => d switch
    {
        500 => Argb(255, 0x9A, 0xA1, 0x9A), 1000 => Argb(255, 0xD9, 0x53, 0x4F), 2000 => Argb(255, 0x3F, 0x7F, 0xD6), 5000 => Argb(255, 0xE8, 0x8A, 0x2E),
        10000 => Argb(255, 0x5D, 0xA8, 0x5B), 20000 => Argb(255, 0xD9, 0xB7, 0x3A), _ => Argb(255, 0x8E, 0x5B, 0xB5),
    };

    private void FinCash(StackPanel h)
    {
        var m = _r.Fin.State;
        h.Children.Add(BackRow("Finanze", "Contanti", () => { _tally = new(); FinGo(null); }));
        h.Children.Add(Segmented("cash", new[] { "Ho speso", "Ho ricevuto" }, _cashSpend ? 0 : 1, i => { _cashSpend = i == 0; _tally = new(); RenderFinance(); }));
        long Tally() => _tally.Sum(kv => (long)kv.Key * kv.Value);
        var big = T(Finance.Eur(Tally()), 48, "LabelTertiary", true);
        big.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
        big.FontFamily = (FontFamily)Application.Current.Resources["FontMono"];
        big.HorizontalAlignment = HorizontalAlignment.Center;
        h.Children.Add(big);
        var wallet = MoneyText("cash.wallet", m.CashCents, 13, "LabelTertiary", false, "Nel portafoglio ");
        wallet.HorizontalAlignment = HorizontalAlignment.Center;
        h.Children.Add(wallet);

        void Update()
        {
            var v = Tally();
            big.Text = Finance.Eur(v);
            big.Foreground = v == 0 ? B("LabelTertiary") : _cashSpend ? B("CriticalDefault") : Sb(Argb(255, 0x1F, 0x9A, 0x5A));
        }
        Update();

        var grid = new Grid { ColumnSpacing = 12, RowSpacing = 12 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        for (var i = 0; i < Finance.Notes.Length; i++)
        {
            if (i % 2 == 0) grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var note = Banknote(Finance.Notes[i], m.Cash.TryGetValue(Finance.Notes[i], out var own) ? own : 0, Update);
            Grid.SetColumn(note, i % 2);
            Grid.SetRow(note, i / 2);
            grid.Children.Add(note);
        }
        h.Children.Add(grid);
        h.Children.Add(T("Ogni clic conta una banconota. Il − sul contatore ne toglie una.", 13, "LabelTertiary"));

        if (_cashSpend)
        {
            var chips = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            foreach (var k in Finance.Categories.Take(Finance.Categories.Length - 1))
            {
                var kk = k;
                chips.Children.Add(Chip(k, _cat == k, () => { _cat = _cat == kk ? "" : kk; RenderFinance(); }));
            }
            h.Children.Add(new ScrollViewer { HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, HorizontalScrollMode = ScrollMode.Auto, VerticalScrollMode = ScrollMode.Disabled, Content = chips });
        }
        var ok = Btn(_cashSpend ? "Registra spesa" : "Registra entrata", () =>
        {
            var v = Tally();
            if (v == 0) return;
            var before = _r.Fin.State.TotalCents;
            foreach (var (d, n) in _tally) if (n > 0) _r.Fin.Bump(d, _cashSpend ? -n : n);
            _r.Fin.Add(_cashSpend ? -v : v, _cashSpend ? (_cat.Length > 0 ? _cat : "Altro") : "Entrate", "Contanti", "cash");
            FlashMoney(before, _r.Fin.State.TotalCents - before);
            _tally = new(); _cat = "";
            FinGo(null);
        }, "PrimaryButton");
        ok.HorizontalAlignment = HorizontalAlignment.Stretch;
        Bounce(ok, 1.03f);
        h.Children.Add(ok);
    }

    private Grid Banknote(int denom, int owned, Action changed)
    {
        var col = NoteColor(denom);
        var g = new Grid { Height = 96, Background = Sb(Microsoft.UI.Colors.Transparent) };
        var face = new Border
        {
            CornerRadius = new CornerRadius(10),
            Background = new LinearGradientBrush
            {
                StartPoint = new Point(0, 0), EndPoint = new Point(1, 1),
                GradientStops = { new GradientStop { Color = col, Offset = 0 }, new GradientStop { Color = Windows.UI.Color.FromArgb(255, (byte)((col.R + 255) / 2), (byte)((col.G + 255) / 2), (byte)((col.B + 255) / 2)), Offset = 1 } },
            },
        };
        var art = new Grid();
        art.Children.Add(new Border { Margin = new Thickness(8), CornerRadius = new CornerRadius(6), BorderBrush = Sb(Argb(90, 255, 255, 255)), BorderThickness = new Thickness(1.5) });
        for (var i = 1; i <= 4; i++)
            art.Children.Add(new Ellipse { Width = 22 * i, Height = 22 * i, Stroke = Sb(Argb(34, 255, 255, 255)), StrokeThickness = 1.2, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 40 - 11 * i, 0) });
        art.Children.Add(new Border { Width = 34, Height = 46, CornerRadius = new CornerRadius(17, 17, 2, 2), Background = Sb(Argb(70, 255, 255, 255)), HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(18, 0, 0, 0) });
        var val = new TextBlock { Text = (denom / 100).ToString(), FontSize = 34, FontWeight = Microsoft.UI.Text.FontWeights.Bold, Foreground = Sb(Microsoft.UI.Colors.White), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 18, 0) };
        art.Children.Add(val);
        art.Children.Add(new TextBlock { Text = "EURO", FontSize = 9, CharacterSpacing = 150, Foreground = Sb(Argb(200, 255, 255, 255)), VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(14, 0, 0, 8) });
        if (owned > 0) art.Children.Add(new TextBlock { Text = $"hai {owned}", FontSize = 10, Foreground = Sb(Argb(220, 255, 255, 255)), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 6, 10, 0) });
        face.Child = art;
        g.Children.Add(face);

        var badge = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(6) };
        var minus = new Border { Background = Sb(Argb(140, 0, 0, 0)), CornerRadius = new CornerRadius(12, 0, 0, 12), Padding = new Thickness(10, 2, 8, 2), Child = new TextBlock { Text = "−", Foreground = Sb(Microsoft.UI.Colors.White), FontWeight = Microsoft.UI.Text.FontWeights.Bold } };
        var count = new Border { Background = Sb(Argb(140, 0, 0, 0)), CornerRadius = new CornerRadius(0, 12, 12, 0), Padding = new Thickness(4, 2, 10, 2), Child = new TextBlock { Foreground = Sb(Microsoft.UI.Colors.White) } };
        badge.Children.Add(minus);
        badge.Children.Add(count);
        g.Children.Add(badge);
        void Show()
        {
            var n = _tally.TryGetValue(denom, out var x) ? x : 0;
            badge.Visibility = n > 0 ? Visibility.Visible : Visibility.Collapsed;
            ((TextBlock)count.Child).Text = "×" + n;
            face.Opacity = n > 0 ? 1 : 0.7;
        }
        Show();
        minus.Tapped += (_, e) =>
        {
            e.Handled = true;
            var n = _tally.TryGetValue(denom, out var x) ? x : 0;
            if (n > 0) _tally[denom] = n - 1;
            Show(); changed();
        };
        // ogni clic conta una sola banconota: niente pressione lunga, niente gesti
        g.Tapped += (_, _) => { _tally[denom] = (_tally.TryGetValue(denom, out var x) ? x : 0) + 1; Show(); changed(); };
        g.DoubleTapped += (_, _) => { _tally[denom] = (_tally.TryGetValue(denom, out var x) ? x : 0) + 1; Show(); changed(); };
        Bounce(g, 1.04f);
        return g;
    }

    // ---------------------------------------------------------------- movimenti

    private void FinMoves(StackPanel h)
    {
        h.Children.Add(BackRow("Finanze", "Movimenti", () => FinGo(null)));
        h.Children.Add(Segmented("finper", new[] { "Mese", "Anno" }, _finMonth < 0 ? 1 : 0, i => { _finMonth = i == 1 ? -1 : DateTime.Today.Month - 1; RenderFinance(); }));
        h.Children.Add(PeriodRow());
        var (from, to) = Range(_finYear, _finMonth);
        var list = _r.Fin.State.Txs.Where(t => t.Ts >= from && t.Ts < to).Take(200).ToList();
        var col = new StackPanel { Spacing = 2 };
        if (list.Count == 0) col.Children.Add(T("Niente in questo periodo.", 13, "LabelTertiary"));
        else col.Children.Add(T("Clic destro su un movimento per eliminarlo.", 12, "LabelTertiary"));
        foreach (var t in list)
        {
            var tx = t;
            var r = new Grid { ColumnSpacing = 12, Padding = new Thickness(4, 8, 4, 8), Background = Sb(Microsoft.UI.Colors.Transparent) };
            r.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            r.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            r.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            r.Children.Add(new Ellipse { Width = 10, Height = 10, Fill = Sb(CatColor(t.Cat)), VerticalAlignment = VerticalAlignment.Center });
            var mid = new StackPanel();
            mid.Children.Add(T(t.Note.Length > 0 ? t.Note : t.Cat, 14));
            mid.Children.Add(T(t.Cat + " · " + DateTimeOffset.FromUnixTimeMilliseconds(t.Ts).LocalDateTime.ToString("d MMM", It) + (t.Src == "revolut" ? " · Revolut" : t.Src == "cash" ? " · Contanti" : ""), 12, "LabelTertiary"));
            Grid.SetColumn(mid, 1);
            r.Children.Add(mid);
            var v = MoneyText("mv." + t.Id, t.Cents, 14, t.Cents > 0 ? "PositiveOnSubtle" : "LabelPrimary", false, t.Cents > 0 ? "+" : "");
            v.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(v, 2);
            r.Children.Add(v);
            var menu = new MenuFlyout();
            var del = new MenuFlyoutItem { Text = "Elimina" };
            del.Click += (_, _) => _r.Fin.Remove(tx.Id);
            menu.Items.Add(del);
            r.ContextFlyout = menu;
            r.PointerEntered += (_, _) => r.Background = Sb(WithA(C("LabelPrimary"), 10));
            r.PointerExited += (_, _) => r.Background = Sb(Microsoft.UI.Colors.Transparent);
            col.Children.Add(r);
        }
        h.Children.Add(Widget(col));
    }

    // ---------------------------------------------------------------- Revolut

    private void FinRevolut(StackPanel h)
    {
        h.Children.Add(BackRow("Finanze", "Revolut", () => FinGo(null)));
        var col = new StackPanel { Spacing = 12 };
        col.Children.Add(MoneyText("rev.total", _r.Fin.State.RevolutCents, 34));
        var status = T("", 13, "LabelTertiary");
        var imp = Btn("Importa estratto (CSV)", async () =>
        {
            var picker = new FileOpenPicker();
            picker.FileTypeFilter.Add(".csv");
            picker.FileTypeFilter.Add("*");
            InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
            var f = await picker.PickSingleFileAsync();
            if (f == null) return;
            try { var n = _r.Fin.ImportCsv(await File.ReadAllTextAsync(f.Path)); status.Text = $"{n} movimenti importati"; }
            catch (Exception) { status.Text = "File non leggibile"; }
        }, "PrimaryButton");
        col.Children.Add(imp);
        col.Children.Add(status);
        var bal = new TextBox { PlaceholderText = "0,00", MaxWidth = 240, HorizontalAlignment = HorizontalAlignment.Left };
        col.Children.Add(Field("Imposta il saldo a mano", bal));
        col.Children.Add(Btn("Salva saldo", () =>
        {
            var v = Finance.ToCents(bal.Text);
            if (v == null) { status.Text = "Scrivi un importo"; return; }
            _r.Fin.SetRevolut(v.Value);
            bal.Text = "";
            status.Text = "Saldo aggiornato";
        }));
        col.Children.Add(T("Revolut non ha un'API per i conti personali. I pagamenti arrivano dalle notifiche sul telefono; lo storico dall'estratto: in Revolut apri Conto → Estratti → Excel/CSV.", 12, "LabelTertiary"));
        foreach (var c in col.Children) if (c is TextBlock tb) tb.TextWrapping = TextWrapping.Wrap;
        h.Children.Add(Widget(col));
    }
}
