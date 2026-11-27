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
    private string _src = "cash";
    private static readonly (string K, string N)[] Sources = { ("cash", "Contanti"), ("revolut", "Revolut"), ("manual", "Altro") };
    private static Windows.UI.Color SrcColor(string s) => s switch { "cash" => Argb(255, 0x34, 0xC7, 0x59), "revolut" => Argb(255, 0x3F, 0x86, 0xFF), _ => Argb(255, 0xFF, 0x9F, 0x0A) };
    private static string SrcName(string s) => Sources.FirstOrDefault(x => x.K == s).N ?? "Altro";
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

    // ---------------------------------------------------------------- cruscotto: saldo, mese, azioni, andamento, dove, ultimi

    private void FinDashboard(StackPanel h)
    {
        var m = _r.Fin.State;
        var (from, to) = Range(_finYear, _finMonth);
        var inPeriod = m.Txs.Where(t => t.Ts >= from && t.Ts < to).ToList();
        var spent = -inPeriod.Where(t => t.Cents < 0).Sum(t => t.Cents);
        var earned = inPeriod.Where(t => t.Cents > 0).Sum(t => t.Cents);
        var (pf, pt) = _finMonth < 0 ? Range(_finYear - 1, -1) : _finMonth == 0 ? Range(_finYear - 1, 11) : Range(_finYear, _finMonth - 1);
        var before = -m.Txs.Where(t => t.Ts >= pf && t.Ts < pt && t.Cents < 0).Sum(t => t.Cents);

        var head = Cols("*a", 8, CardLabel("Saldo"), EyeToggle(28));
        head.Height = 28;
        var incomes = VStack(0, T("Entrate", 13, "LabelTertiary"), MoneyText("fin.in", earned, 25));
        var outcomes = VStack(0, T("Uscite", 13, "LabelTertiary"), MoneyText("fin.out", spent, 25));
        if (before > 0 && (spent - before) * 100 / before is var diff && diff != 0)
        {
            var pct = Semi((diff > 0 ? "+" : "") + diff + "%", 13, "AccentDefault");
            if (diff > 0) pct.Foreground = Sb(Third);
            outcomes.Children.Add(pct);
        }
        var line = new Border { Height = 1, Background = B("LineSeparator") };
        h.Children.Add(Panelled(VStack(16, head, MoneyText("fin.total", m.TotalCents, 44), line, PeriodRow(), Cols("**", 12, incomes, outcomes)), new Thickness(24)));

        var acts = Cols("****", 0,
            Round("−", "Spesa", true, () => { _income = false; FinGo("add"); }),
            Round("+", "Entrata", false, () => { _income = true; FinGo("add"); }),
            Round("€", "Contanti", false, () => { _cashSpend = true; _tally = new(); FinGo("cash"); }),
            Round("≡", "Movimenti", false, () => FinGo("moves")));
        h.Children.Add(acts);

        var forecast = T("", 13, "LabelTertiary");
        var chart = PaceChart(m, forecast);
        HideChart(forecast);
        h.Children.Add(Panelled(VStack(12, Cols("*a", 8, CardLabel("Andamento"), forecast), chart), new Thickness(24)));

        var by = inPeriod.Where(t => t.Cents < 0).GroupBy(t => t.Cat).Select(g => (Cat: g.Key, V: -g.Sum(t => t.Cents))).OrderByDescending(x => x.V).Take(4).ToList();
        if (by.Count > 0)
        {
            var rows = new StackPanel { Spacing = 12 };
            var top = Math.Max(1, by[0].V);
            for (var i = 0; i < by.Count; i++)
            {
                var name = T(by[i].Cat, 16);
                var value = T(Finance.Eur(by[i].V), 16, "LabelPrimary", true);
                rows.Children.Add(VStack(5, Cols("*a", 8, name, value),
                    Progress(by[i].V / (double)top, i == 0 ? B("AccentDefault") : Sb(Alpha(C("AccentDefault"), 0.45)), 6, B("BgSunken"))));
            }
            h.Children.Add(Panelled(VStack(12, CardLabel("Dove spendi"), HideChart(rows)), new Thickness(24)));
        }

        var last = m.Txs.OrderByDescending(t => t.Ts).Take(5).ToList();
        if (last.Count == 0) return;
        var chevron = Icons.Of(Ico.Forward, B("LabelTertiary"), 14);
        var list = new StackPanel();
        var title = Cols("*a", 8, CardLabel("Ultimi movimenti"), chevron);
        title.Margin = new Thickness(0, 0, 0, 8);
        list.Children.Add(title);
        for (var i = 0; i < last.Count; i++)
        {
            var t = last[i];
            if (i > 0) list.Children.Add(new Border { Height = 1, Margin = new Thickness(40, 0, 0, 0), Background = B("LineSeparator") });
            var income = t.Cents > 0;
            var sign = new Border
            {
                Width = 28, Height = 28, CornerRadius = new CornerRadius(14), VerticalAlignment = VerticalAlignment.Center,
                Background = income ? Sb(Alpha(C("AccentDefault"), 0.18)) : B("BgSunken"),
                Child = Center(T(income ? "+" : "−", 16, income ? "AccentDefault" : "LabelSecondary", true)),
            };
            var amount = MoneyText("fl." + t.Id, t.Cents, 16, income ? "AccentDefault" : "LabelPrimary", false, income ? "+" : "");
            amount.VerticalAlignment = VerticalAlignment.Center;
            var row = Cols("a*a", 12, sign, VStack(0, T(t.Note.Length > 0 ? t.Note : t.Cat, 16), T(t.Cat, 13, "LabelTertiary")), amount);
            row.Margin = new Thickness(0, 10, 0, 10);
            list.Children.Add(row);
        }
        var recent = Tile(list, () => FinGo("moves"), null, "Ultimi movimenti", new Thickness(24, 16, 24, 16));
        recent.HorizontalAlignment = HorizontalAlignment.Stretch;
        h.Children.Add(recent);
    }

    private TextBlock CardLabel(string text)
    {
        var t = Semi(text, 13, "LabelSecondary");
        t.VerticalAlignment = VerticalAlignment.Center;
        return t;
    }

    private Grid PeriodRow()
    {
        void Step(int d)
        {
            if (_finMonth < 0) _finYear += d;
            else { var mo = _finMonth + d; if (mo < 0) { mo = 11; _finYear--; } else if (mo > 11) { mo = 0; _finYear++; } _finMonth = mo; }
            RenderFinance();
        }
        Button Arrow(Ico icon, int d, string name)
        {
            var b = Bare(Center(Icons.Of(icon, B("LabelSecondary"), 18)), () => Step(d), name, 16);
            b.Width = b.Height = 32;
            Bounce(b, 1.12f);
            return b;
        }
        var title = _finMonth < 0 ? _finYear.ToString() : Cap(new DateTime(_finYear, _finMonth + 1, 1).ToString("MMMM yyyy", It));
        var t = Center(T(title, 16, "LabelPrimary", true));
        return Cols("a*a", 8, Arrow(Ico.Back, -1, "Periodo prima"), t, Arrow(Ico.Forward, 1, "Periodo dopo"));
    }

    /// <summary>Ritmo di spesa: spesa cumulata di questo periodo (linea piena) contro il periodo prima (grigia), con la previsione tratteggiata.</summary>
    private StackPanel PaceChart(Money m, TextBlock forecast)
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
        forecast.Text = proj != null ? "Previsti " + Finance.Eur((long)(proj.Value * 100)) : "";
        var readR = T("", 13, "LabelTertiary");
        readR.HorizontalAlignment = HorizontalAlignment.Right;
        read.Children.Add(readL);
        read.Children.Add(readR);
        void Idle()
        {
            var diff = (curCum.Length > 0 ? curCum[^1] : 0) - (curCum.Length > 0 ? prevCum[Math.Min(curCum.Length - 1, buckets - 1)] : 0);
            readL.Text = curCum.Length == 0 ? "Nessuna spesa ancora" : Math.Abs(diff) < 0.5 ? "Come il periodo prima" : (diff > 0 ? "+" : "−") + Finance.Eur((long)Math.Abs(diff * 100)) + " rispetto a prima";
            readL.Foreground = diff > 0.5 ? Sb(Third) : B("AccentDefault");
            readR.Text = "";
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
        p.Children.Add(T(text, 13, "LabelSecondary"));
        return p;
    }

    // ---------------------------------------------------------------- nuova spesa / entrata

    private bool _showNote;

    private void FinAdd(StackPanel host)
    {
        // contenitore proprio: i tasti da tastiera si agganciano qui e spariscono con la pagina
        var h = new StackPanel { Spacing = 12 };
        host.Children.Add(h);
        h.Children.Add(BackRow("Finanze", _income ? "Nuova entrata" : "Nuova spesa", () => FinGo(null)));
        h.Children.Add(Segmented("finadd", new[] { "Spesa", "Entrata" }, _income ? 1 : 0, i => { _income = i == 1; _cat = ""; RenderFinance(); }));
        var amt = Center(Semi("€" + (_amount.Length > 0 ? _amount : "0"), 48, _amount.Length > 0 ? "LabelPrimary" : "LabelTertiary"));
        var notes = Round(Tab.Banknote, "Banconote", false, () => { _cashSpend = !_income; _tally = new(); FinGo("cash"); });
        h.Children.Add(Cols("a*a", 0, new Border { Width = notes.Width, MinWidth = 66 }, amt, notes));

        var chips = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var k in new[] { "" }.Concat(_income ? new[] { "Entrate" } : Finance.Categories.Take(Finance.Categories.Length - 1)))
        {
            var kk = k;
            chips.Children.Add(Chip(k.Length > 0 ? k : "Auto", _cat == k, () => { _cat = kk; RenderFinance(); }));
        }
        var src = Chip(SrcName(_src) + "  ▾", false, () => { }, SrcColor(_src), Alpha(SrcColor(_src), 0.22));
        var menu = new MenuFlyout();
        foreach (var (sk, sn) in Sources)
        {
            var kk = sk;
            var item = new MenuFlyoutItem { Text = sn };
            item.Click += (_, _) => { _src = kk; RenderFinance(); };
            menu.Items.Add(item);
        }
        src.Flyout = menu;
        chips.Children.Add(src);
        chips.Children.Add(Chip(_showNote ? "Nascondi nota" : "+ Nota", false, () => { _showNote = !_showNote; RenderFinance(); }));
        h.Children.Add(HScroll(chips));
        if (_showNote)
        {
            var note = new TextBox { Text = _note, PlaceholderText = "Dove o cosa" };
            note.TextChanged += (_, _) => _note = note.Text;
            h.Children.Add(Field("Nota", note));
        }

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
        // tastierino a tutta larghezza: tre colonne, righe uguali
        var pad = new Grid { RowSpacing = 8, ColumnSpacing = 8 };
        for (var c = 0; c < 3; c++) pad.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var keys = new[] { "1", "2", "3", "4", "5", "6", "7", "8", "9", ",", "0", "⌫" };
        for (var i = 0; i < keys.Length; i++)
        {
            if (i % 3 == 0) pad.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var k = keys[i];
            var face = new Border { Height = 60, CornerRadius = new CornerRadius(20), Background = B("BgSurface"), Child = Center(T(k, 31, "LabelPrimary", true)) };
            var key = Bare(face, () => Press(k), k == "⌫" ? "Cancella" : k, 20);
            key.HorizontalAlignment = HorizontalAlignment.Stretch;
            if (k == "⌫") key.RightTapped += (_, _) => { _amount = ""; Press("⌫"); };
            Bounce(key, 1.04f);
            Grid.SetColumn(key, i % 3);
            Grid.SetRow(key, i / 3);
            pad.Children.Add(key);
        }
        h.Children.Add(pad);
        // si scrive anche da tastiera
        h.KeyDown += (_, e) =>
        {
            if (e.OriginalSource is TextBox) return;
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
            _r.Fin.Add(_income ? v.Value : -v.Value, k, _note.Trim(), _src);
            FlashMoney(before, _income ? v.Value : -v.Value);
            _amount = ""; _note = ""; _cat = ""; _showNote = false;
            FinGo(null);
        }, "PrimaryButton");
        confirm.HorizontalAlignment = HorizontalAlignment.Stretch;
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

        void Update()
        {
            var v = Tally();
            big.Text = Finance.Eur(v);
            big.Foreground = B(v == 0 ? "LabelTertiary" : _cashSpend ? "CriticalDefault" : "Positive");
        }
        Update();

        var grid = new Grid { ColumnSpacing = 12, RowSpacing = 12 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        for (var i = 0; i < Finance.Notes.Length; i++)
        {
            if (i % 2 == 0) grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var note = Banknote(Finance.Notes[i], 0, Update);
            Grid.SetColumn(note, i % 2);
            Grid.SetRow(note, i / 2);
            grid.Children.Add(note);
        }
        h.Children.Add(grid);
        if (_cashSpend)
        {
            var chips = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            foreach (var k in Finance.Categories.Take(Finance.Categories.Length - 1))
            {
                var kk = k;
                chips.Children.Add(Chip(k, _cat == k, () => { _cat = _cat == kk ? "" : kk; RenderFinance(); }));
            }
            h.Children.Add(HScroll(chips));
        }
        var ok = Btn(_cashSpend ? "Registra spesa" : "Registra entrata", () =>
        {
            var v = Tally();
            if (v == 0) return;
            var before = _r.Fin.State.TotalCents;
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
        var (from, to) = Range(_finYear, _finMonth);
        var list = _r.Fin.State.Txs.Where(t => t.Ts >= from && t.Ts < to).Take(200).ToList();
        var col = new StackPanel { Spacing = 2 };
        col.Children.Add(PeriodRow());
        if (list.Count == 0) col.Children.Add(T("Niente in questo periodo", 16, "LabelTertiary"));
        foreach (var t in list)
        {
            var tx = t;
            var r = new Grid { ColumnSpacing = 12, Padding = new Thickness(4, 8, 4, 8), Background = Sb(Microsoft.UI.Colors.Transparent) };
            r.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            r.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            r.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var dots = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
            dots.Children.Add(new Ellipse { Width = 10, Height = 10, Fill = Sb(CatColor(t.Cat)), VerticalAlignment = VerticalAlignment.Center });
            dots.Children.Add(new Border { Width = 4, Height = 30, CornerRadius = new CornerRadius(2), Background = Sb(SrcColor(t.Src)) });
            r.Children.Add(dots);
            var mid = new StackPanel();
            mid.Children.Add(T(t.Note.Length > 0 ? t.Note : t.Cat, 16));
            mid.Children.Add(T(t.Cat + " · " + DateTimeOffset.FromUnixTimeMilliseconds(t.Ts).LocalDateTime.ToString("d MMM", It) + " · " + SrcName(t.Src), 13, "LabelTertiary"));
            Grid.SetColumn(mid, 1);
            r.Children.Add(mid);
            var v = MoneyText("mv." + t.Id, t.Cents, 16, t.Cents > 0 ? "PositiveOnSubtle" : "LabelPrimary", false, t.Cents > 0 ? "+" : "");
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
        col.Children.Add(MoneyText("rev.total", _r.Fin.State.TotalCents, 39));
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
        col.Children.Add(Field("Imposta il saldo totale", bal));
        col.Children.Add(Btn("Salva saldo", () =>
        {
            var v = Finance.ToCents(bal.Text);
            if (v == null) { status.Text = "Scrivi un importo"; return; }
            _r.Fin.SetTotal(v.Value);
            bal.Text = "";
            status.Text = "Saldo aggiornato";
        }));
        h.Children.Add(Widget(col));
    }
}
