using System.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace Ponte;

/// <summary>Indici delle icone di modulo: gli stessi di TabGlyph su Android.</summary>
public static class Tab
{
    public const int House = 0, Devices = 1, Clipboard = 2, Images = 3, Headphones = 4, Settings = 5, Wallet = 6, Upload = 7, Drive = 8,
        Banknote = 9, Shield = 10, Calendar = 11, Cap = 12, Sun = 13, Moon = 14, Contrast = 15, History = 16, Radio = 17, Scan = 18,
        Expand = 19, Shrink = 20, Activity = 21, Camera = 22, Flame = 23;
}

/// <summary>Icone d'azione: stessi tracciati di Ico su Android.</summary>
public enum Ico { Folder, FolderPlus, Share, Open, Trash, Eye, EyeOff, Check, Pencil, Close, Back, Forward, Down, Plus, Minus, Search, Refresh }

/// <summary>Comandi del player, pieni, come TransportGlyph su Android.</summary>
public enum Transport { Prev, Play, Pause, Next }

/// <summary>
/// Le icone dell'app Android, identiche: tracciati Lucide (lucide.dev, licenza ISC) sulla griglia di 24, tratto sottile con estremi tondi.
/// Niente font di simboli di sistema.
/// </summary>
public static class Icons
{
    private static string Rr(double x, double y, double w, double h, double r) => FormattableString.Invariant(
        $"M{x + r} {y}H{x + w - r}A{r} {r} 0 0 1 {x + w} {y + r}V{y + h - r}A{r} {r} 0 0 1 {x + w - r} {y + h}H{x + r}A{r} {r} 0 0 1 {x} {y + h - r}V{y + r}A{r} {r} 0 0 1 {x + r} {y}Z");

    private static string Circ(double cx, double cy, double r) => FormattableString.Invariant(
        $"M{cx - r} {cy}A{r} {r} 0 1 0 {cx + r} {cy}A{r} {r} 0 1 0 {cx - r} {cy}Z");

    private static readonly string[][] Tabs =
    {
        new[] { "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8", "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" },
        new[] { "M18 8V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h8", "M10 19v-3.96 3.15", "M7 19h5", Rr(16, 12, 6, 10, 2) },
        new[] { Rr(8, 2, 8, 4, 1), "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2", "M12 11h4", "M12 16h4", "M8 11h.01", "M8 16h.01" },
        new[] { "m22 11-1.296-1.296a2.4 2.4 0 0 0-3.408 0L11 16", "M4 8a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2", Circ(13, 7, 1), Rr(8, 2, 14, 14, 2) },
        new[] { "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3" },
        new[] { "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915", Circ(12, 12, 3) },
        new[] { "M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1", "M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4" },
        new[] { "M12 3v12", "m17 8-5-5-5 5", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" },
        new[] { "M22 12H2", "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z", "M6 16h.01", "M10 16h.01" },
        new[] { Rr(2, 6, 20, 12, 2), Circ(12, 12, 2), "M6 12h.01", "M18 12h.01" },
        new[] { "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4" },
        new[] { "M8 2v4", "M16 2v4", Rr(3, 4, 18, 18, 2), "M3 10h18", "M8 14h.01", "M12 14h.01", "M16 14h.01", "M8 18h.01", "M12 18h.01" },
        new[] { "M21.42 10.922a1 1 0 0 0-.019-1.838L12.83 5.18a2 2 0 0 0-1.66 0L2.6 9.08a1 1 0 0 0 0 1.832l8.57 3.908a2 2 0 0 0 1.66 0z", "M22 10v6", "M6 12.5V16a6 3 0 0 0 12 0v-3.5" },
        new[] { Circ(12, 12, 4), "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41", "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41" },
        new[] { "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" },
        new[] { Circ(12, 12, 10), "M12 18a6 6 0 0 0 0-12v12z" },
        new[] { "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2" },
        new[] { "M4.9 19.1C1 15.2 1 8.8 4.9 4.9", "M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.5", Circ(12, 12, 2), "M16.2 7.8c2.3 2.3 2.3 6.1 0 8.5", "M19.1 4.9C23 8.8 23 15.1 19.1 19" },
        new[] { "M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2", Rr(7, 8, 10, 8, 1) },
        new[] { "m15 3 6 0 0 6", "m9 21-6 0 0-6", "M21 3l-7 7", "M3 21l7-7" },
        new[] { "m14 10 7-7", "M20 10h-6V4", "m3 21 7-7", "M4 14h6v6" },
        new[] { "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2" },
        new[] { "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z", Circ(12, 13, 3) },
        new[] { "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z" },
    };

    private const string FolderPath = "M3 8Q3 6 5 6H9L11 8.5H19Q21 8.5 21 10.5V17Q21 19 19 19H5Q3 19 3 17Z";
    private const string EyePath = "M2.5 12C5 7.2 8.4 5.5 12 5.5C15.6 5.5 19 7.2 21.5 12C19 16.8 15.6 18.5 12 18.5C8.4 18.5 5 16.8 2.5 12Z";

    private static (string[] Paths, double Stroke) Action(Ico kind) => kind switch
    {
        Ico.Folder => (new[] { FolderPath }, 1.8),
        Ico.FolderPlus => (new[] { FolderPath, "M12 11.5V16.5", "M9.5 14H14.5" }, 1.8),
        Ico.Share => (new[] { "M12 15V4M8 7.5L12 3.8L16 7.5", "M7 11H6Q4.5 11 4.5 12.5V18Q4.5 20 6.5 20H17.5Q19.5 20 19.5 18V12.5Q19.5 11 18 11H17" }, 1.8),
        Ico.Open => (new[] { "M10 5H6Q4 5 4 7V18Q4 20 6 20H17Q19 20 19 18V14", "M14 4H20V10M20 4L11 13" }, 1.8),
        Ico.Trash => (new[] { "M4 7H20", "M9 7V5Q9 4 10 4H14Q15 4 15 5V7", "M6 7L7 18.5Q7.1 20 8.6 20H15.4Q16.9 20 17 18.5L18 7" }, 1.8),
        Ico.Eye => (new[] { EyePath, Circ(12, 12, 3.2) }, 1.8),
        Ico.EyeOff => (new[] { EyePath, Circ(12, 12, 3.2), "M4.5 4.5L19.5 19.5" }, 1.8),
        Ico.Check => (new[] { "M5 12.5L10 17.5L19 7" }, 2.2),
        Ico.Pencil => (new[] { "M4 20L4.8 15.6L16.2 4.2Q17.2 3.2 18.2 4.2L19.8 5.8Q20.8 6.8 19.8 7.8L8.4 19.2Z", "M14.2 6.2L17.8 9.8" }, 1.8),
        Ico.Close => (new[] { "M6 6L18 18", "M18 6L6 18" }, 1.98),
        Ico.Back => (new[] { "M14.5 5L7.5 12L14.5 19" }, 2.1),
        Ico.Forward => (new[] { "M9.5 5L16.5 12L9.5 19" }, 2.1),
        Ico.Down => (new[] { "M5 9.5L12 16.5L19 9.5" }, 2.1),
        Ico.Plus => (new[] { "M12 5V19", "M5 12H19" }, 2.0),
        Ico.Minus => (new[] { "M5 12H19" }, 2.0),
        Ico.Search => (new[] { Circ(11, 11, 7), "M20 20L16.1 16.1" }, 1.8),
        _ => (new[] { "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5" }, 1.8),
    };

    /// <summary>Icona di modulo, tratto 1,6.</summary>
    public static Viewbox Glyph(int index, Brush fg, double size) => Stroked(Tabs[Math.Clamp(index, 0, Tabs.Length - 1)], 1.6, fg, size);

    /// <summary>Icona d'azione.</summary>
    public static Viewbox Of(Ico kind, Brush fg, double size)
    {
        var (paths, stroke) = Action(kind);
        return Stroked(paths, stroke, fg, size);
    }

    public static Viewbox Player(Transport kind, Brush fg, double size)
    {
        var paths = kind switch
        {
            Transport.Play => new[] { "M5.76 4.32L20.64 12L5.76 19.68Z" },
            Transport.Pause => new[] { Rr(5.28, 4.32, 4.8, 15.36, 1.4), Rr(13.92, 4.32, 4.8, 15.36, 1.4) },
            Transport.Next => new[] { "M1.92 4.32L12.48 12L1.92 19.68Z", "M11.04 4.32L21.6 12L11.04 19.68Z" },
            _ => new[] { "M22.08 4.32L11.52 12L22.08 19.68Z", "M12.96 4.32L2.4 12L12.96 19.68Z" },
        };
        var canvas = new Canvas { Width = 24, Height = 24 };
        foreach (var data in paths) canvas.Children.Add(new Microsoft.UI.Xaml.Shapes.Path { Data = Parse(data), Fill = fg });
        return Box(canvas, size);
    }

    /// <summary>Ricolora un'icona già costruita.</summary>
    public static void Tint(FrameworkElement icon, Brush fg)
    {
        if (icon is not Viewbox { Child: Canvas canvas }) return;
        foreach (var p in canvas.Children.OfType<Microsoft.UI.Xaml.Shapes.Path>())
        {
            if (p.Fill != null) p.Fill = fg; else p.Stroke = fg;
        }
    }

    private static Viewbox Stroked(string[] paths, double stroke, Brush fg, double size)
    {
        var canvas = new Canvas { Width = 24, Height = 24 };
        foreach (var data in paths)
            canvas.Children.Add(new Microsoft.UI.Xaml.Shapes.Path
            {
                Data = Parse(data), Stroke = fg, StrokeThickness = stroke,
                StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, StrokeLineJoin = PenLineJoin.Round,
            });
        return Box(canvas, size);
    }

    private static Viewbox Box(Canvas canvas, double size) =>
        new() { Width = size, Height = size, Child = canvas, IsHitTestVisible = false, VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };

    private static Geometry Parse(string data) => (Geometry)XamlBindingHelper.ConvertValue(typeof(Geometry), Spaced(data));

    /// <summary>I tracciati SVG compatti ("1-.48", ".25.25") vanno riscritti con i numeri separati: il lettore di XAML è più rigido.</summary>
    private static string Spaced(string d)
    {
        var o = new StringBuilder(d.Length * 2);
        var i = 0;
        while (i < d.Length)
        {
            var ch = d[i];
            if (char.IsLetter(ch)) { o.Append(ch).Append(' '); i++; continue; }
            if (ch != '-' && ch != '.' && !char.IsDigit(ch)) { i++; continue; }
            var start = i;
            if (d[i] == '-') i++;
            var dot = false;
            while (i < d.Length && (char.IsDigit(d[i]) || (d[i] == '.' && !dot))) { if (d[i] == '.') dot = true; i++; }
            var num = d.AsSpan(start, i - start);
            if (num.StartsWith(".")) o.Append('0').Append(num);
            else if (num.StartsWith("-.")) o.Append("-0").Append(num[1..]);
            else o.Append(num);
            o.Append(' ');
        }
        return o.ToString().TrimEnd();
    }
}
