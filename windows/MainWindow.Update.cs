using System.Numerics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;

namespace Ponte;

/// <summary>Scheda dell'aggiornamento: anello con la percentuale, quanto è stato scaricato, velocità, tempo che manca e le tre fasi.</summary>
public sealed partial class MainWindow
{
    private int _lastUpdateStep;
    private int _lastTenth = -1;

    private void InitUpdateCard() => _r.Update.Changed += p => DispatcherQueue.TryEnqueue(() => ShowUpdate(p));

    private static readonly string[] UpdateStepNames = { "Scarico il pacchetto", "Estraggo i file", "Installo e riavvio" };

    private void ShowUpdate(Updater.Progress? p)
    {
        if (p == null)
        {
            if (UpdateCard.Visibility == Visibility.Visible)
            {
                FadeTo(UpdateCard, 0, 240);
                var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(260) };
                t.Tick += (_, _) => { t.Stop(); UpdateCard.Visibility = Visibility.Collapsed; _lastUpdateStep = 0; };
                t.Start();
            }
            return;
        }
        if (UpdateCard.Visibility != Visibility.Visible)
        {
            UpdateCard.Visibility = Visibility.Visible;
            Enter(UpdateCard, 0, 24, 380);
        }

        var frac = p.Step == 1 ? (p.Total > 0 ? (double)p.Done / p.Total : 0) : 1;
        UpdateRing.Value = frac * 100;
        UpdatePercent.Text = p.Step == 1 ? $"{(int)(frac * 100)}%" : "";
        UpdateTitle.Text = $"Aggiornamento alla {p.Version}";
        UpdateDetail.Text = p.Step switch
        {
            1 => $"{Mb(p.Done)}" + (p.Total > 0 ? $" di {Mb(p.Total)}" : "") + (p.BytesPerSec > 1
                    ? $" · {Mb((long)p.BytesPerSec)}/s" + (p.Total > p.Done ? $" · mancano circa {Eta((p.Total - p.Done) / p.BytesPerSec)}" : "")
                    : ""),
            2 => "Preparo i file nuovi",
            _ => "Ponte si chiude e riparte da solo tra un attimo",
        };

        // Piccolo rimbalzo elastico dell'anello a ogni 10 %: la stessa molla della barra laterale, più morbida.
        var tenth = (int)(frac * 10);
        if (tenth != _lastTenth)
        {
            _lastTenth = tenth;
            var v = ElementCompositionPreview.GetElementVisual(UpdateRingBox);
            v.CenterPoint = new Vector3(28, 28, 0);
            var sp = v.Compositor.CreateSpringVector3Animation();
            sp.InitialValue = new Vector3(1.09f, 0.93f, 1f);
            sp.FinalValue = Vector3.One;
            sp.DampingRatio = 0.28f;
            sp.Period = TimeSpan.FromMilliseconds(44);
            v.StartAnimation("Scale", sp);
        }

        if (p.Step != _lastUpdateStep || UpdateSteps.Children.Count == 0)
        {
            _lastUpdateStep = p.Step;
            UpdateSteps.Children.Clear();
            for (var i = 0; i < 3; i++)
            {
                var done = i + 1 < p.Step;
                var cur = i + 1 == p.Step;
                var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Opacity = done || cur ? 1 : 0.45 };
                row.Children.Add(new FontIcon
                {
                    Glyph = done ? "" : cur ? "" : "", FontSize = 13,
                    Foreground = done || cur ? Canone.Get("AccentDefault", Root.ActualTheme == ElementTheme.Dark) : Canone.Get("LabelTertiary", Root.ActualTheme == ElementTheme.Dark),
                });
                var tb = new TextBlock { Text = UpdateStepNames[i], Style = (Style)Application.Current.Resources["Footnote"] };
                if (cur) tb.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
                row.Children.Add(tb);
                UpdateSteps.Children.Add(row);
                if (done || cur) Enter(row, 0, 6, 260, i * 40);
            }
        }
    }

    private static string Mb(long b) => b >= 1024 * 1024 ? $"{b / 1048576.0:0.0} MB" : $"{b / 1024.0:0} KB";
    private static string Eta(double s) => s >= 60 ? $"{(int)(s / 60)} min" : $"{(int)Math.Ceiling(s)} s";
}
