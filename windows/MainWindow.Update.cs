using System.Numerics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Media;

namespace Ponte;

/// <summary>
/// Scheda dell'aggiornamento, sempre nello stesso ordine: anello a sinistra, titolo e dettagli, poi i tre passi in fila.
/// Ricerca e installazione girano; il download riempie l'anello; l'esito mostra una spunta o un punto esclamativo.
/// </summary>
public sealed partial class MainWindow
{
    private int _lastTenth = -1;
    private Updater.Progress? _shownUpdate;

    private void InitUpdateCard() => _r.Update.Changed += p => DispatcherQueue.TryEnqueue(() => ShowUpdate(p));

    private static readonly string[] UpdateStepNames = { "Controllo", "Download", "Installazione" };

    private void ShowUpdate(Updater.Progress? p)
    {
        _shownUpdate = p;
        if (p == null)
        {
            if (UpdateCard.Visibility != Visibility.Visible) return;
            FadeTo(UpdateCard, 0, 220);
            var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(240) };
            t.Tick += (_, _) => { t.Stop(); if (_shownUpdate == null) UpdateCard.Visibility = Visibility.Collapsed; };
            t.Start();
            return;
        }
        if (UpdateCard.Visibility != Visibility.Visible || UpdateCard.Opacity < 1)
        {
            UpdateCard.Visibility = Visibility.Visible;
            Enter(UpdateCard, 0, 24, 380);
        }
        var dark = Root.ActualTheme == ElementTheme.Dark;
        var frac = p.Step == 1 ? (p.Total > 0 ? (double)p.Done / p.Total : 0) : 0;

        // Anello o esito
        var outcome = p.Step < 0;
        UpdateRing.Visibility = outcome ? Visibility.Collapsed : Visibility.Visible;
        UpdateRing.IsIndeterminate = p.Step is 0 or 2 or 3;
        if (p.Step == 1) UpdateRing.Value = frac * 100;
        if (outcome)
        {
            UpdatePercent.FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets");
            UpdatePercent.FontSize = 26;
            UpdatePercent.Text = p.Step == -1 ? "" : "";
            UpdatePercent.Foreground = Canone.Get(p.Step == -1 ? "AccentDefault" : "CriticalDefault", dark);
            Enter(UpdatePercent, 0, 0, 300);
        }
        else
        {
            UpdatePercent.ClearValue(TextBlock.FontFamilyProperty);
            UpdatePercent.ClearValue(TextBlock.FontSizeProperty);
            UpdatePercent.ClearValue(TextBlock.ForegroundProperty);
            UpdatePercent.Text = p.Step == 1 ? $"{(int)(frac * 100)}%" : "";
        }

        UpdateTitle.Text = p.Step switch
        {
            0 => "Cerco aggiornamenti",
            1 => $"Scarico la versione {p.Version}",
            2 or 3 => $"Installo la versione {p.Version}",
            -1 => p.Message,
            _ => "Aggiornamento non riuscito",
        };
        UpdateDetail.Text = p.Step switch
        {
            0 => "Chiedo al server l'ultima release",
            1 => $"{Mb(p.Done)}" + (p.Total > 0 ? $" di {Mb(p.Total)}" : "") +
                 (p.BytesPerSec > 1 ? $" · {Mb((long)p.BytesPerSec)}/s" : "") +
                 (p.BytesPerSec > 1 && p.Total > p.Done ? $" · {Eta((p.Total - p.Done) / p.BytesPerSec)}" : ""),
            2 => "Preparo i file nuovi",
            3 => "Ponte si chiude e riparte da solo",
            -1 => $"Versione installata: 1.0.{_r.Update.Current}",
            _ => p.Message,
        };

        // Rimbalzo "gelatina" dell'anello a ogni 10 % scaricato.
        var tenth = (int)(frac * 10);
        if (p.Step == 1 && tenth != _lastTenth && tenth > 0)
        {
            _lastTenth = tenth;
            var v = ElementCompositionPreview.GetElementVisual(UpdateRingBox);
            v.CenterPoint = new Vector3(28, 28, 0);
            var sp = v.Compositor.CreateSpringVector3Animation();
            sp.InitialValue = new Vector3(1.07f, 0.94f, 1f);
            sp.FinalValue = Vector3.One;
            sp.DampingRatio = 0.3f;
            sp.Period = TimeSpan.FromMilliseconds(46);
            v.StartAnimation("Scale", sp);
        }
        if (p.Step != 1) _lastTenth = -1;

        // Passi: tre colonne uguali, barretta sopra e nome sotto.
        UpdateSteps.Children.Clear();
        UpdateSteps.Visibility = outcome ? Visibility.Collapsed : Visibility.Visible;
        if (outcome) return;
        var current = p.Step switch { 0 => 0, 1 => 1, _ => 2 };
        var grid = new Grid { ColumnSpacing = 8 };
        for (var i = 0; i < 3; i++)
        {
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var on = i <= current;
            var col = new StackPanel { Spacing = 6 };
            col.Children.Add(new Border
            {
                Height = 3, CornerRadius = new CornerRadius(1.5),
                Background = on ? (Brush)Application.Current.Resources["BrandGradient"] : Canone.Get("FillControl", dark),
            });
            var tb = new TextBlock { Text = UpdateStepNames[i], Style = (Style)Application.Current.Resources["Footnote"], Opacity = i == current ? 1 : on ? 0.7 : 0.45 };
            col.Children.Add(tb);
            Grid.SetColumn(col, i);
            grid.Children.Add(col);
        }
        UpdateSteps.Children.Add(grid);
    }

    private static string Mb(long b) => b >= 1024 * 1024 ? $"{b / 1048576.0:0.0} MB" : $"{b / 1024.0:0} KB";
    private static string Eta(double s) => s >= 60 ? $"{(int)(s / 60)} min" : $"{(int)Math.Ceiling(s)} s";
}
