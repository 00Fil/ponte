using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace Ponte;

/// <summary>
/// Home: scheda grande con MicroSlats dietro il saluto, quattro riquadri uguali (il colore sta solo nei bollini a gradiente
/// blu) che portano alle sezioni, e l'ultimo appunto. Stessa struttura della Home di Android.
/// </summary>
public sealed partial class MainWindow
{
    private bool _homeWebReady;

    private string SurfaceHex()
    {
        var c = Canone.Color("BgSurface", Dark);
        return $"{c.R:X2}{c.G:X2}{c.B:X2}";
    }

    private void RenderHome()
    {
        if (!_homeWebReady)
        {
            _homeWebReady = true;
            _ = InitWebAsync(HomeWeb, "ink=1", "slats.html", SurfaceHex());
        }
        HomeFade.Background = new LinearGradientBrush
        {
            StartPoint = new Windows.Foundation.Point(0, 0), EndPoint = new Windows.Foundation.Point(0, 1),
            GradientStops =
            {
                new GradientStop { Color = Windows.UI.Color.FromArgb(0, 14, 40, 170), Offset = 0.4 },
                new GradientStop { Color = Windows.UI.Color.FromArgb(150, 14, 40, 170), Offset = 1 },
            },
        };

        var hour = DateTime.Now.Hour;
        HomeHello.Text = hour is >= 5 and <= 12 ? "Buongiorno" : hour is >= 13 and <= 17 ? "Buon pomeriggio" : "Buonasera";
        var devices = _r.Devices;
        HomeSub.Text = devices.Count == 0 ? "Solo questo PC per ora"
            : devices.Count == 1 ? $"Collegato con {devices[0].Name}" : $"Collegato con {devices.Count} dispositivi";

        var h = Reset("Home");
        var holder = _r.Hp.Holder;
        var audio = holder == null ? "Libere" : holder == _r.DeviceId ? "Qui" : _r.DeviceName(holder);
        var shots = _r.Files.Items.Count(f => f.Kind == "screenshot");

        var grid = new Grid { ColumnSpacing = 12, RowSpacing = 12 };
        var wide = Root.ActualWidth >= 1000;
        var colsN = wide ? 4 : 2;
        for (var c = 0; c < colsN; c++) grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var tiles = new[]
        {
            HomeTile(1, "Dispositivi", (devices.Count + 1).ToString(), "con questo PC", "#2F5FFF", "#6FB4FF"),
            HomeTile(2, "Appunti", _r.Clip.History.Count.ToString(), "in cronologia", "#1B34D6", "#3F86FF"),
            HomeTile(3, "Archivio", _r.Files.Items.Count.ToString(), shots > 0 ? $"{shots} screenshot" : "file condivisi", "#3F86FF", "#7DB4FF"),
            HomeTile(4, "Cuffie", audio, "dove suonano ora", "#5B7BFF", "#2F5FFF"),
        };
        for (var i = 0; i < tiles.Length; i++)
        {
            if (i % colsN == 0) grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            Grid.SetColumn(tiles[i], i % colsN);
            Grid.SetRow(tiles[i], i / colsN);
            grid.Children.Add(tiles[i]);
            Enter(tiles[i], 0, 8, 360, 40 * i);
        }
        h.Children.Add(grid);

        var last = _r.Clip.History.FirstOrDefault();
        var card = new List<UIElement> { Text("Ultimo appunto", "SectionTitle") };
        if (last == null) card.Add(Text("Niente di copiato per ora. Copia un testo e compare qui e sugli altri dispositivi.", "Footnote"));
        else
        {
            var body = Text(last.Text.Length > 300 ? last.Text[..300] + "…" : last.Text, "BodyText");
            body.MaxLines = 3;
            body.TextTrimming = TextTrimming.CharacterEllipsis;
            card.Add(body);
            card.Add(Text(last.Mine ? "Da questo PC" : "Da " + _r.DeviceName(last.From), "FootnoteTertiary"));
        }
        h.Children.Add(Card(card.ToArray()));
    }

    private Button HomeTile(int target, string title, string value, string note, string c0, string c1)
    {
        static Windows.UI.Color Hex(string s) => Windows.UI.Color.FromArgb(255,
            Convert.ToByte(s.Substring(1, 2), 16), Convert.ToByte(s.Substring(3, 2), 16), Convert.ToByte(s.Substring(5, 2), 16));
        var chip = new Grid { Width = 32, Height = 32 };
        chip.Children.Add(new Ellipse
        {
            Fill = new LinearGradientBrush
            {
                StartPoint = new Windows.Foundation.Point(0, 0), EndPoint = new Windows.Foundation.Point(1, 1),
                GradientStops = { new GradientStop { Color = Hex(c0), Offset = 0 }, new GradientStop { Color = Hex(c1), Offset = 1 } },
            },
        });
        chip.Children.Add(new FontIcon
        {
            Glyph = Glyphs[target], FontSize = 14, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
            FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets"),
        });
        var head = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        head.Children.Add(chip);
        var t = Text(title, "Footnote");
        t.VerticalAlignment = VerticalAlignment.Center;
        head.Children.Add(t);

        var v = new TextBlock { Text = value, FontSize = 28, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis, Foreground = Canone.Get("LabelPrimary", Dark) };
        var n = Text(note, "FootnoteTertiary");
        n.TextTrimming = TextTrimming.CharacterEllipsis;
        var body = new Grid { Height = 108 };
        body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        body.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        body.Children.Add(head);
        var bottom = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Children = { v, n } };
        Grid.SetRow(bottom, 1);
        body.Children.Add(bottom);

        var b = new Button { Style = St("TileStyle"), Content = new Border { Padding = new Thickness(10), Child = body } };
        b.Click += (_, _) => Select(target);
        return b;
    }
}
