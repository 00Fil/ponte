using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Ponte;

/// <summary>
/// Le larghezze delle pagine. Finestra stretta: una colonna, come sul telefono. Su schermi larghi la pagina si allarga
/// e i blocchi si affiancano in due o tre colonne; elenchi e moduli semplici restano a una larghezza comoda da leggere.
/// </summary>
public sealed partial class MainWindow
{
    private int _spread = 1;

    private bool Wide => Root.ActualWidth >= 1024;

    /// <summary>Quante colonne ci stanno accanto alla barra laterale.</summary>
    private int Spread
    {
        get
        {
            if (!Wide) return 1;
            var avail = Root.ActualWidth - 240;
            return avail >= 1440 ? 3 : avail >= 960 ? 2 : 1;
        }
    }

    /// <summary>Larghezza massima (margini compresi) della pagina che la scheda sta mostrando.</summary>
    private double PageMax(int tab)
    {
        var n = Spread;
        // una colonna: le stesse misure di Android
        if (n == 1) return tab == 1 ? 1024 : 720;
        const double read = 752, pair = 1112, board = 1240;
        var dash = n == 3 ? 1592 : board;
        return tab switch
        {
            // Home: quattro riquadri in riga, mai così grandi da sembrare manifesti
            0 => _homeCams ? board : 1348,
            1 => _backupOpen ? pair : board,
            2 => _finSub switch { null => dash, "add" or "cash" => 1040, _ => read },
            3 => _agSub switch { null => board, "study" => dash, "todo" => pair, _ => read },
            4 => _bdSub switch { null => dash, "gallery" => board, "diary" or "plan" => pair, _ => read },
            _ => pair,
        };
    }

    /// <summary>Ogni pagina è una colonna centrata con la sua larghezza massima; il titolo si allinea alla stessa colonna.</summary>
    private void FitColumn()
    {
        var avail = Root.ActualWidth - (Wide ? 240 : 0);
        if (avail <= 0) return;
        var bottom = Wide ? 24 : 100;
        for (var i = 0; i < _hosts.Length; i++)
        {
            _hosts[i].Width = Math.Max(260, Math.Min(PageMax(i), avail) - 32);
            _hosts[i].HorizontalAlignment = HorizontalAlignment.Center;
            _hosts[i].Margin = new Thickness(0, 12, 0, bottom);
        }
        foreach (var pg in _pages) pg.HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled;
        TitleBox.Width = Math.Max(100, _hosts[_tab].Width - 16);
        TitleBox.HorizontalAlignment = HorizontalAlignment.Center;
        // sotto "Home" resta il posto per la pillola dei dispositivi
        TitleBox.Margin = new Thickness(0, 0, 0, _tab == 0 ? 46 : 12);
        PlaceMorph();
    }

    private void ApplyLayout()
    {
        SideCol.Width = new GridLength(Wide ? 240 : 0);
        Side.Visibility = Wide ? Visibility.Visible : Visibility.Collapsed;
        Bottom.Visibility = Wide ? Visibility.Collapsed : Visibility.Visible;
        var changed = Spread != _spread;
        _spread = Spread;
        var before = _hosts[_tab].Width;
        FitColumn();
        // cambiato il numero di colonne si ridispone la pagina; la Home, fatta di quadrati, e la galleria anche se cambia solo la larghezza
        if (changed) Refresh();
        else if (_tab == 4 && _bdSub == "gallery" && Math.Abs(_hosts[4].Width - before) > 0.5) Refresh();
        else if (_tab == 0 && _homeWidth > 0 && Math.Abs(_hosts[0].Width - _homeWidth) > 0.5) RenderHome();
    }

    // ---------------------------------------------------------------- colonne

    private static GridLength Part(double k = 1) => new(k, GridUnitType.Star);
    private static GridLength Px(double px) => new(px);

    /// <summary>Una colonna di blocchi impilati; quelli assenti si saltano.</summary>
    private static StackPanel Column(params UIElement?[] blocks)
    {
        var p = new StackPanel { Spacing = Gap, VerticalAlignment = VerticalAlignment.Top };
        foreach (var b in blocks)
            if (b != null) p.Children.Add(b);
        return p;
    }

    /// <summary>Colonne affiancate, allineate in alto, con lo stesso spazio che separa i blocchi.</summary>
    private static Grid Beside(params (GridLength Width, StackPanel Column)[] columns)
    {
        var g = new Grid { ColumnSpacing = Gap };
        for (var i = 0; i < columns.Length; i++)
        {
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = columns[i].Width });
            Grid.SetColumn(columns[i].Column, i);
            g.Children.Add(columns[i].Column);
        }
        return g;
    }

    /// <summary>Colonne uguali; quelle rimaste vuote non occupano posto.</summary>
    private static Grid Even(params UIElement?[][] columns) =>
        Beside(columns.Where(c => c.Any(b => b != null)).Select(c => (Part(), Column(c))).ToArray());
}
