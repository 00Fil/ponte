using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;

namespace Ponte;

public sealed partial class MainWindow
{
    private static readonly string[] FinanceCategories = { "Casa", "Cibo", "Trasporti", "Tempo libero", "Salute", "Altro" };
    private static readonly CultureInfo Italian = CultureInfo.GetCultureInfo("it-IT");

    private static string Euro(long cents) => (cents / 100m).ToString("C2", Italian);

    private static DateOnly LocalDate(long timestamp) => DateOnly.FromDateTime(DateTimeOffset.FromUnixTimeMilliseconds(timestamp).ToLocalTime().DateTime);

    private void RenderFinances()
    {
        var host = Reset("Finanze");
        var entries = _r.S.FinanceEntries;
        var balance = FinanceLedger.Balance(entries);
        var month = DateOnly.FromDateTime(DateTime.Today);
        var monthEntries = entries.Where(e => LocalDate(e.Ts).Year == month.Year && LocalDate(e.Ts).Month == month.Month).ToList();
        var income = monthEntries.Where(e => e.Kind == "income").Sum(e => e.Cents);
        var expenses = monthEntries.Where(e => e.Kind == "expense").Sum(e => -e.Cents);

        var wallet = new List<UIElement>
        {
            Text("SALDO DISPONIBILE", "FootnoteTertiary"),
            new TextBlock { Text = entries.Count == 0 ? "Saldo non impostato" : Euro(balance), Style = St("Title1"), Foreground = Canone.Get("LabelPrimary", Dark), TextTrimming = TextTrimming.CharacterEllipsis },
            Row("Entrate", "Questo mese", Badge(Euro(income), "Positive")),
            Row("Uscite", "Questo mese", Badge(Euro(expenses), "Critical")),
            Btn("Aggiorna saldo", async () => await FinanceDialogAsync("balance", balance), "PrimaryButton"),
        };
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        actions.Children.Add(Btn("− Uscita", async () => await FinanceDialogAsync("expense", 0)));
        actions.Children.Add(Btn("+ Entrata", async () => await FinanceDialogAsync("income", 0)));
        wallet.Add(actions);
        wallet.Add(Text("I dati sono salvati solo su questo PC.", "FootnoteTertiary"));
        host.Children.Add(Card(wallet.ToArray()));

        host.Children.Add(Card(Text("Spese giornaliere", "SectionTitle"),
            Text("Heatmap · ultime 26 settimane · più verde = più spese", "Footnote"), Heatmap(entries)));

        var trend = new List<UIElement> { Text("Andamento del saldo", "SectionTitle"), Text("Chiusura mensile · ultimi 6 mesi", "Footnote") };
        if (entries.Count == 0) trend.Add(Text("Aggiorna il saldo per iniziare a vedere l'andamento.", "Footnote"));
        else
        {
            trend.Add(BalanceChart(entries));
            var months = new Grid { HorizontalAlignment = HorizontalAlignment.Stretch };
            for (var i = 0; i < 6; i++) months.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            foreach (var (offset, index) in Enumerable.Range(0, 6).Reverse().Select((offset, index) => (offset, index)))
            {
                var label = new TextBlock
                {
                    Text = DateTime.Today.AddMonths(-offset).ToString("MMM", Italian), Style = St("FootnoteTertiary"),
                    HorizontalAlignment = HorizontalAlignment.Center,
                };
                Grid.SetColumn(label, index); months.Children.Add(label);
            }
            trend.Add(months);
        }
        host.Children.Add(Card(trend.ToArray()));

        var category = new List<UIElement> { Text("Uscite per categoria", "SectionTitle"), Text(DateTime.Today.ToString("MMMM yyyy", Italian), "Footnote") };
        var groups = monthEntries.Where(e => e.Kind == "expense").GroupBy(e => e.Category)
            .Select(g => (Name: g.Key, Cents: g.Sum(e => -e.Cents))).OrderByDescending(g => g.Cents).ToList();
        if (groups.Count == 0) category.Add(Text("Nessuna uscita registrata questo mese.", "Footnote"));
        else
        {
            var max = Math.Max(1, groups[0].Cents);
            foreach (var (name, cents) in groups)
            {
                category.Add(Row(name, Euro(cents)));
                category.Add(new ProgressBar { Minimum = 0, Maximum = 1, Value = (double)cents / max, Height = 6 });
            }
        }
        host.Children.Add(Card(category.ToArray()));

        var recent = new List<UIElement> { Text("Movimenti recenti", "SectionTitle") };
        var latest = entries.OrderByDescending(e => e.Ts).Take(12).ToList();
        if (latest.Count == 0) recent.Add(Text("Registra la prima entrata o uscita per popolare il riepilogo.", "Footnote"));
        else
        {
            foreach (var (entry, index) in latest.Select((entry, index) => (entry, index)))
            {
                if (index > 0) recent.Add(Divider());
                var title = entry.Kind switch { "opening" => "Saldo iniziale", "adjustment" => "Saldo aggiornato", _ => entry.Category };
                var date = LocalDate(entry.Ts).ToString("d MMM yyyy", Italian);
                var note = entry.Note.Length > 0 && entry.Note != "Saldo aggiornato" ? entry.Note + " · " + date : date;
                var value = Text((entry.Cents >= 0 ? "+" : "−") + Euro(Math.Abs(entry.Cents)), "BodyText");
                value.Foreground = Canone.Get(entry.Cents >= 0 ? "PositiveOnSubtle" : "CriticalOnSubtle", Dark);
                var movement = new Grid { ColumnSpacing = 12, MinHeight = 46 };
                movement.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                movement.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                movement.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var description = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Children = { Text(title), Text(note, "FootnoteTertiary") } };
                movement.Children.Add(description);
                value.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(value, 1); movement.Children.Add(value);
                var remove = Btn("Elimina", async () => await ConfirmFinanceDeleteAsync(entry));
                Grid.SetColumn(remove, 2); movement.Children.Add(remove);
                recent.Add(movement);
            }
        }
        host.Children.Add(Card(recent.ToArray()));
    }

    private async Task ConfirmFinanceDeleteAsync(FinanceEntry entry)
    {
        var dlg = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = "Eliminare il movimento?",
            Content = "L'importo verrà ricalcolato dal saldo.",
            PrimaryButtonText = "Elimina",
            CloseButtonText = "Annulla",
            DefaultButton = ContentDialogButton.Close,
        };
        if (await dlg.ShowAsync() == ContentDialogResult.Primary)
        {
            FinanceLedger.Delete(_r.S, entry.Id);
            _r.Raise();
        }
    }

    private async Task FinanceDialogAsync(string mode, long currentBalance)
    {
        var balanceMode = mode == "balance";
        var amount = new TextBox
        {
            PlaceholderText = "0,00",
            Text = balanceMode && currentBalance != 0 ? (currentBalance / 100m).ToString("0.00", Italian) : "",
            MinWidth = 280,
        };
        var error = new TextBlock { Text = "Inserisci un importo valido in euro.", Style = St("Footnote"), Foreground = Canone.Get("CriticalOnSubtle", Dark), Visibility = Visibility.Collapsed };
        var fields = new StackPanel { Spacing = 10 };
        fields.Children.Add(Field("Importo · EUR", amount));
        ComboBox? category = null;
        TextBox? note = null;
        if (!balanceMode)
        {
            category = new ComboBox { ItemsSource = FinanceCategories, SelectedIndex = 0, MinWidth = 280 };
            note = new TextBox { PlaceholderText = "Facoltativa", MaxLength = 80, MinWidth = 280 };
            fields.Children.Add(Field("Categoria", category));
            fields.Children.Add(Field("Nota", note));
        }
        fields.Children.Add(error);

        var dlg = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = balanceMode ? "Aggiorna saldo" : mode == "income" ? "Nuova entrata" : "Nuova uscita",
            Content = fields,
            PrimaryButtonText = balanceMode ? "Salva saldo" : "Registra",
            CloseButtonText = "Annulla",
            DefaultButton = ContentDialogButton.Primary,
        };
        dlg.PrimaryButtonClick += (_, args) =>
        {
            if (!TryEuro(amount.Text, out var value)
                || (!balanceMode && value <= 0)
                || value > long.MaxValue / 100m || value < long.MinValue / 100m)
            {
                args.Cancel = true;
                error.Visibility = Visibility.Visible;
                return;
            }
            var cents = decimal.ToInt64(decimal.Round(value * 100m, 0, MidpointRounding.AwayFromZero));
            if (balanceMode) FinanceLedger.SetBalance(_r.S, cents);
            else FinanceLedger.Add(_r.S, mode, cents, category?.SelectedItem?.ToString() ?? "Altro", note?.Text ?? "");
            _r.Raise();
        };
        await dlg.ShowAsync();
    }

    private static bool TryEuro(string raw, out decimal value)
    {
        var text = raw.Trim().Replace("€", "").Replace(" ", "");
        if (text.Contains(','))
            return decimal.TryParse(text.Replace(".", ""), NumberStyles.Number, Italian, out value);
        var dot = text.LastIndexOf('.');
        if (dot >= 0 && text.Length - dot - 1 <= 2)
            return decimal.TryParse(text, NumberStyles.Number, CultureInfo.InvariantCulture, out value);
        return decimal.TryParse(text.Replace(".", ""), NumberStyles.Number, Italian, out value);
    }

    private UIElement Heatmap(IReadOnlyList<FinanceEntry> entries)
    {
        var today = DateOnly.FromDateTime(DateTime.Today);
        var mondayOffset = ((int)today.DayOfWeek + 6) % 7;
        var start = today.AddDays(-mondayOffset).AddDays(-25 * 7);
        var days = entries.Where(e => e.Kind == "expense" && LocalDate(e.Ts) >= start && LocalDate(e.Ts) <= today).GroupBy(e => LocalDate(e.Ts))
            .ToDictionary(g => g.Key, g => g.Sum(e => -e.Cents));
        var max = Math.Max(1, days.Values.DefaultIfEmpty(1).Max());
        var grid = new Grid { HorizontalAlignment = HorizontalAlignment.Left, ColumnSpacing = 2, RowSpacing = 2 };
        for (var i = 0; i < 26; i++) grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(12) });
        for (var i = 0; i < 7; i++) grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(12) });
        var positive = Canone.Color("PositiveOnSubtle", Dark);
        for (var week = 0; week < 26; week++) for (var row = 0; row < 7; row++)
        {
            var date = start.AddDays(week * 7 + row);
            if (date > today) continue;
            var cents = days.GetValueOrDefault(date);
            var ratio = cents <= 0 ? 0 : Math.Log(1 + (double)cents) / Math.Log(1 + (double)max);
            var alpha = cents <= 0 ? (byte)255 : (byte)Math.Clamp(80 + ratio * 175, 0, 255);
            var brush = cents <= 0 ? Canone.Get("BgSunken", Dark) : new SolidColorBrush(Windows.UI.Color.FromArgb(alpha, positive.R, positive.G, positive.B));
            var cell = new Border { Width = 10, Height = 10, CornerRadius = new CornerRadius(2), Background = brush };
            ToolTipService.SetToolTip(cell, date.ToString("d MMM", Italian) + " · " + Euro(cents));
            Grid.SetColumn(cell, week); Grid.SetRow(cell, row); grid.Children.Add(cell);
        }
        var weekTotal = entries.Where(e => e.Kind == "expense" && LocalDate(e.Ts) >= today.AddDays(-6)).Sum(e => -e.Cents);
        var halfYear = entries.Where(e => e.Kind == "expense" && LocalDate(e.Ts) >= start).Sum(e => -e.Cents);
        var labels = new StackPanel { Spacing = 4, Children =
        {
            grid,
            Text(start.ToString("d MMM", Italian) + "     →     " + today.ToString("d MMM", Italian), "FootnoteTertiary"),
            Text("Ultimi 7 giorni " + Euro(weekTotal) + "  ·  periodo " + Euro(halfYear), "Footnote"),
        } };
        return labels;
    }

    private UIElement BalanceChart(IReadOnlyList<FinanceEntry> entries)
    {
        var now = DateOnly.FromDateTime(DateTime.Today);
        var balances = Enumerable.Range(0, 6).Reverse().Select(offset =>
        {
            var close = now.AddMonths(-offset).AddDays(1 - now.Day).AddMonths(1).ToDateTime(TimeOnly.MinValue).AddTicks(-1);
            var cutoff = new DateTimeOffset(close, TimeZoneInfo.Local.GetUtcOffset(close)).ToUnixTimeMilliseconds();
            return entries.Where(e => e.Ts <= cutoff).Sum(e => e.Cents);
        }).ToList();
        var min = balances.Min(); var max = balances.Max();
        if (min == max) { min -= 100; max += 100; }
        var canvas = new Microsoft.UI.Xaml.Controls.Canvas { Width = 640, Height = 132, HorizontalAlignment = HorizontalAlignment.Stretch };
        var line = new Polyline { Stroke = Canone.Get("AccentDefault", Dark), StrokeThickness = 3, StrokeLineJoin = PenLineJoin.Round };
        var positions = new List<Point>();
        for (var i = 0; i < balances.Count; i++)
        {
            var x = 16 + i * (608.0 / (balances.Count - 1));
            var y = 112 - (balances[i] - min) / (double)(max - min) * 92;
            var point = new Point(x, y);
            line.Points.Add(point); positions.Add(point);
        }
        canvas.Children.Add(line);
        foreach (var point in positions)
        {
            var dot = new Ellipse { Width = 8, Height = 8, Fill = Canone.Get("AccentDefault", Dark) };
            Microsoft.UI.Xaml.Controls.Canvas.SetLeft(dot, point.X - 4);
            Microsoft.UI.Xaml.Controls.Canvas.SetTop(dot, point.Y - 4);
            canvas.Children.Add(dot);
        }
        return new Viewbox { Height = 132, Stretch = Stretch.Fill, Child = canvas };
    }
}
