using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Shapes;

namespace Ponte;

/// <summary>
/// Home a widget, come su Android: saldo con l'occhio e le ultime operazioni, ultimo screenshot e ultimo testo (un tocco copia, due aprono),
/// musica in un quadrato con i tasti-bolla accanto all'attività della settimana, cuffie solo se c'è qualcosa di attivo.
/// </summary>
public sealed partial class MainWindow
{
    private string SurfaceHex()
    {
        var c = Canone.Color("BgSurface", Dark);
        return $"{c.R:X2}{c.G:X2}{c.B:X2}";
    }

    private void RenderHome()
    {
        var h = Reset("Home");
        var devices = _r.Devices;
        var sub = T(devices.Count == 0 ? "Solo questo PC per ora" : devices.Count == 1 ? "Collegato con 1 dispositivo" : $"Collegato con {devices.Count} dispositivi", 13, "LabelTertiary");
        h.Children.Add(sub);

        h.Children.Add(MoneyWidget());

        // ultimo screenshot e ultimo testo copiato
        var row = new Grid { ColumnSpacing = 12 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var shot = ShotWidget();
        var clip = ClipWidget();
        Grid.SetColumn(clip, 1);
        row.Children.Add(shot);
        row.Children.Add(clip);
        h.Children.Add(row);

        // musica in un quadrato + attività della settimana
        var row2 = new Grid { ColumnSpacing = 12 };
        row2.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var np = _r.Np;
        var holder = _r.Hp.Holder;
        var act = ActivityWidget();
        if (np.Active)
        {
            row2.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var music = MusicSquare();
            row2.Children.Add(music);
            Grid.SetColumn(act, 1);
        }
        else if (holder != null || _r.Audio.SinkId != null)
        {
            row2.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var hp = new StackPanel { Spacing = 6 };
            hp.Children.Add(Bubble("", 40));
            hp.Children.Add(T(_r.Audio.SinkId != null ? "Audio su " + _r.DeviceName(_r.Audio.SinkId) : "Cuffie", 13, "LabelTertiary"));
            hp.Children.Add(T(holder == _r.DeviceId ? "Su questo PC" : holder == null ? "Libere" : "Su " + _r.DeviceName(holder), 16, "LabelPrimary", true));
            var w = Widget(hp, 170);
            w.Tapped += (_, _) => Select(3);
            Grid.SetColumn(w, 1);
            row2.Children.Add(w);
        }
        row2.Children.Add(act);
        h.Children.Add(row2);

        var i = 0;
        foreach (var el in h.Children) Enter(el, 0, 8, 360, 40 * i++);
    }

    // ---------------------------------------------------------------- saldo

    private Border MoneyWidget()
    {
        var m = _r.Fin.State;
        var col = new StackPanel { Spacing = 8 };
        var head = new Grid();
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.Children.Add(T("Saldo", 13, "LabelTertiary"));
        var eye = EyeToggle();
        Grid.SetColumn(eye, 1);
        head.Children.Add(eye);
        col.Children.Add(head);
        var bal = MoneyText("home.total", m.TotalCents, 40);
        bal.Tapped += (_, _) => Select(2);
        col.Children.Add(bal);

        // cassetto con le ultime operazioni, sfumate in basso
        var ops = new StackPanel { Spacing = 0 };
        foreach (var t in m.Txs.Take(4))
        {
            var r = new Grid { Height = 34, ColumnSpacing = 10 };
            r.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            r.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            r.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            r.Children.Add(new Ellipse { Width = 8, Height = 8, Fill = Sb(CatColor(t.Cat)), VerticalAlignment = VerticalAlignment.Center });
            var n = T(t.Note.Length > 0 ? t.Note : t.Cat, 13, "LabelSecondary");
            n.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(n, 1);
            r.Children.Add(n);
            var v = MoneyText("home.op." + t.Id, t.Cents, 13, t.Cents > 0 ? "PositiveOnSubtle" : "LabelPrimary", true, t.Cents > 0 ? "+" : "");
            v.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(v, 2);
            r.Children.Add(v);
            ops.Children.Add(r);
        }
        if (m.Txs.Count == 0) ops.Children.Add(T("Nessuna operazione", 13, "LabelTertiary"));
        var drawer = new Border
        {
            Background = B("BgSunken"), CornerRadius = new CornerRadius(14), Padding = new Thickness(14, 6, 14, 6), Child = ops,
            OpacityMask = new LinearGradientBrush
            {
                StartPoint = new Windows.Foundation.Point(0, 0), EndPoint = new Windows.Foundation.Point(0, 1),
                GradientStops = { new GradientStop { Color = Argb(255, 0, 0, 0), Offset = 0.6 }, new GradientStop { Color = Argb(60, 0, 0, 0), Offset = 1 } },
            },
        };
        col.Children.Add(drawer);
        return Widget(col);
    }

    // ---------------------------------------------------------------- screenshot e testo

    private Grid ShotWidget()
    {
        var e = _r.Files.Items.FirstOrDefault(f => f.Mime.StartsWith("image/"));
        var g = new Grid();
        if (e != null)
        {
            try { g.Children.Add(new Image { Stretch = Stretch.UniformToFill, Source = new BitmapImage { DecodePixelWidth = 480, UriSource = new Uri(e.Path) } }); } catch (Exception) { }
            g.Children.Add(new Border
            {
                Background = new LinearGradientBrush
                {
                    StartPoint = new Windows.Foundation.Point(0, 0), EndPoint = new Windows.Foundation.Point(0, 1),
                    GradientStops = { new GradientStop { Color = Argb(0, 0, 0, 0), Offset = 0.45 }, new GradientStop { Color = Argb(170, 0, 0, 0), Offset = 1 } },
                },
            });
        }
        var txt = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(14) };
        var t1 = T(e == null ? "Nessuno screenshot" : "Ultimo screenshot", 13, "LabelTertiary");
        if (e != null) t1.Foreground = Sb(Microsoft.UI.Colors.White);
        txt.Children.Add(t1);
        if (e != null) { var t2 = T("Tocca per copiare · due volte per aprire", 11); t2.Foreground = Sb(Argb(190, 255, 255, 255)); txt.Children.Add(t2); }
        g.Children.Add(txt);
        var clipG = new Grid { CornerRadius = new CornerRadius(20), Children = { g } };
        return CopyCard(clipG, 190, () =>
        {
            if (e == null) return false;
            _r.Clip.ApplyFile(e.Path, e.Mime);
            return true;
        }, () => { _shareTab = 1; Select(1); });
    }

    private Grid ClipWidget()
    {
        var last = _r.Clip.History.FirstOrDefault();
        var col = new Grid { Margin = new Thickness(14) };
        col.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        col.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        col.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        col.Children.Add(T("Ultimo copiato", 13, "LabelTertiary"));
        var body = T(last?.Text ?? "Niente per ora", 15, last == null ? "LabelTertiary" : "LabelPrimary");
        body.TextWrapping = TextWrapping.Wrap;
        body.MaxLines = 5;
        body.Margin = new Thickness(0, 6, 0, 0);
        Grid.SetRow(body, 1);
        col.Children.Add(body);
        if (last != null)
        {
            var hint = T("Tocca per copiare · due volte per aprire", 11, "LabelTertiary");
            Grid.SetRow(hint, 2);
            col.Children.Add(hint);
        }
        return CopyCard(col, 190, () =>
        {
            if (last == null) return false;
            _r.Clip.SetLocal(last.Text);
            return true;
        }, () => { _shareTab = 0; Select(1); });
    }

    // ---------------------------------------------------------------- attività

    private int _actSel = 6;

    private Border ActivityWidget()
    {
        var day = TimeSpan.FromDays(1);
        var today = DateTime.Today;
        var ts = _r.Clip.History.Select(c => c.Ts).Concat(_r.Files.Items.Select(f => f.Ts))
            .Select(x => DateTimeOffset.FromUnixTimeMilliseconds(x).LocalDateTime).ToList();
        var counts = Enumerable.Range(0, 7).Select(k => { var a = today - (6 - k) * day; return ts.Count(x => x >= a && x < a + day); }).ToArray();
        var max = Math.Max(1, counts.Max());
        var col = new Grid();
        col.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        col.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        var head = new StackPanel();
        var lbl = T(_actSel == 6 ? "Oggi" : _actSel == 5 ? "Ieri" : $"{6 - _actSel} giorni fa", 13, "LabelTertiary");
        var num = T($"{counts[_actSel]} condivisi", 22, "LabelPrimary", true);
        SetMorph(num, "act", num.Text);
        head.Children.Add(lbl);
        head.Children.Add(num);
        col.Children.Add(head);
        var bars = new Grid { ColumnSpacing = 6, Margin = new Thickness(0, 10, 0, 0) };
        for (var i = 0; i < 7; i++)
        {
            var idx = i;
            bars.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var cell = new Grid { Background = Sb(Microsoft.UI.Colors.Transparent) };
            var bar = new Border
            {
                VerticalAlignment = VerticalAlignment.Bottom, CornerRadius = new CornerRadius(5),
                Background = i == _actSel ? B("AccentDefault") : Sb(WithA(C("AccentDefault"), 90)),
            };
            cell.SizeChanged += (_, _) => bar.Height = Math.Max(4, cell.ActualHeight * counts[idx] / max);
            cell.Children.Add(bar);
            cell.Tapped += (_, _) => { _actSel = idx; RenderHome(); };
            ToolTipService.SetToolTip(cell, $"{counts[idx]} condivisi");
            Grid.SetColumn(cell, i);
            bars.Children.Add(cell);
        }
        Grid.SetRow(bars, 1);
        col.Children.Add(bars);
        return Widget(col, 170);
    }

    // ---------------------------------------------------------------- musica

    private Border MusicSquare()
    {
        var np = _r.Np;
        var g = new Grid();
        var art = new Image { Stretch = Stretch.UniformToFill, Opacity = 0.9 };
        _ = SetArt(art, np.Art);
        g.Children.Add(art);
        g.Children.Add(new Border
        {
            Background = new LinearGradientBrush
            {
                StartPoint = new Windows.Foundation.Point(0, 0), EndPoint = new Windows.Foundation.Point(0, 1),
                GradientStops = { new GradientStop { Color = WithA(C("BgSurface"), 0), Offset = 0.2 }, new GradientStop { Color = WithA(C("BgSurface"), 235), Offset = 1 } },
            },
        });
        var col = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(14), Spacing = 2 };
        col.Children.Add(T(np.Title.Length > 0 ? np.Title : "Senza titolo", 15, "LabelPrimary", true));
        col.Children.Add(T(np.Artist, 12, "LabelSecondary"));
        var btns = new Grid { Margin = new Thickness(0, 8, 0, 0) };
        for (var i = 0; i < 3; i++) btns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var b0 = BubbleButton("", 38, np.Prev, tip: "Indietro");
        var b1 = BubbleButton(np.Playing ? "" : "", 48, np.Toggle, tip: "Riproduci o metti in pausa");
        var b2 = BubbleButton("", 38, np.Next, tip: "Avanti");
        Grid.SetColumn(b1, 1); Grid.SetColumn(b2, 2);
        b0.HorizontalAlignment = HorizontalAlignment.Left; b2.HorizontalAlignment = HorizontalAlignment.Right;
        btns.Children.Add(b0); btns.Children.Add(b1); btns.Children.Add(b2);
        col.Children.Add(btns);
        g.Children.Add(col);
        var w = new Border
        {
            Height = 170, CornerRadius = new CornerRadius(20), BorderBrush = GlassEdge(), BorderThickness = new Thickness(1),
            Background = B("BgSurface"), Child = g,
        };
        w.DoubleTapped += (_, _) => Select(3);
        return w;
    }

    private static async Task SetArt(Image img, byte[]? bytes)
    {
        var bmp = await BitmapFrom(bytes);
        if (bmp != null) img.Source = bmp;
    }
}
