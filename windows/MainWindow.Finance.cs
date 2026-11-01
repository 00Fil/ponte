using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Ponte;

public sealed partial class MainWindow
{
    private static readonly string[] FinanceCategories = { "Altro", "Casa", "Cibo", "Trasporti", "Tempo libero", "Salute" };
    private static readonly CultureInfo Italian = CultureInfo.GetCultureInfo("it-IT");
    private bool _showAllFinance;

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

        var net = income - expenses;
        var heroBody = new StackPanel { Spacing = 8 };
        var heroLabel = Text("SALDO ATTUALE", "Footnote");
        heroLabel.Foreground = new SolidColorBrush(Microsoft.UI.Colors.White);
        heroBody.Children.Add(heroLabel);
        heroBody.Children.Add(new TextBlock
        {
            Text = entries.Count == 0 ? "Da impostare" : Euro(balance),
            FontSize = 38, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            Foreground = new SolidColorBrush(Microsoft.UI.Colors.White), TextTrimming = TextTrimming.CharacterEllipsis,
        });
        var flow = Text((net >= 0 ? "↑ " : "↓ ") + Euro(Math.Abs(net)) + " questo mese", "BodyText");
        flow.Foreground = new SolidColorBrush(Microsoft.UI.Colors.White);
        heroBody.Children.Add(flow);
        var totals = Text("Entrate " + Euro(income) + "   ·   Uscite " + Euro(expenses), "Footnote");
        totals.Foreground = new SolidColorBrush(Windows.UI.Color.FromArgb(220, 255, 255, 255));
        heroBody.Children.Add(totals);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        actions.Children.Add(Btn("Saldo", async () => await FinanceDialogAsync("balance", balance), "SecondaryButton"));
        actions.Children.Add(Btn("+ Movimento", async () => await FinanceDialogAsync("entry", 0), "PrimaryButton"));
        heroBody.Children.Add(actions);
        var privacy = Text("Dati salvati solo su questo PC", "Footnote");
        privacy.Foreground = new SolidColorBrush(Windows.UI.Color.FromArgb(200, 255, 255, 255));
        heroBody.Children.Add(privacy);
        var heroBrush = new LinearGradientBrush
        {
            StartPoint = new Windows.Foundation.Point(0, 0), EndPoint = new Windows.Foundation.Point(1, 1),
            GradientStops =
            {
                new GradientStop { Color = Canone.Color("AccentDefault", Dark), Offset = 0 },
                new GradientStop { Color = Windows.UI.Color.FromArgb(255, 30, 63, 216), Offset = 1 },
            },
        };
        host.Children.Add(new Border { Background = heroBrush, CornerRadius = new CornerRadius(24), Padding = new Thickness(24), Child = heroBody });

        host.Children.Add(Card(Text("Entrate e uscite", "SectionTitle"),
            Text("Saldo netto per mese · 6 mesi", "Footnote"), MonthlyFlowChart(entries),
            new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { Badge("↑ Entrate superiori", "Positive"), Badge("↓ Uscite superiori", "Critical") } }));
        host.Children.Add(Card(Text("Spese", "SectionTitle"), Text("Giorno per giorno · più verde = più spese", "Footnote"), Heatmap(entries)));

        var category = new List<UIElement> { Text("Dove spendi", "SectionTitle"), Text(DateTime.Today.ToString("MMMM", Italian), "Footnote") };
        var groups = monthEntries.Where(e => e.Kind == "expense").GroupBy(e => e.Category)
            .Select(g => (Name: g.Key, Cents: g.Sum(e => -e.Cents))).OrderByDescending(g => g.Cents).ToList();
        if (groups.Count == 0) category.Add(Text("Nessuna uscita registrata questo mese.", "Footnote"));
        else
        {
            var max = Math.Max(1, groups[0].Cents);
            foreach (var (name, cents) in groups.Take(4))
            {
                category.Add(Row(name, Euro(cents)));
                category.Add(new ProgressBar { Minimum = 0, Maximum = 1, Value = (double)cents / max, Height = 6 });
            }
            var remaining = groups.Skip(4).Sum(group => group.Cents);
            if (remaining > 0) category.Add(Row("Altre categorie", Euro(remaining)));
        }
        host.Children.Add(Card(category.ToArray()));

        var recent = new List<UIElement>();
        var recentHeader = new Grid { ColumnSpacing = 12 };
        recentHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        recentHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        recentHeader.Children.Add(Text("Movimenti", "SectionTitle"));
        if (entries.Count > 4)
        {
            var toggle = Btn(_showAllFinance ? "Meno" : "Tutti", () => { _showAllFinance = !_showAllFinance; RenderFinances(); }, "ChipButton");
            Grid.SetColumn(toggle, 1); recentHeader.Children.Add(toggle);
        }
        recent.Add(recentHeader);
        var latest = entries.OrderByDescending(e => e.Ts).Take(_showAllFinance ? entries.Count : 4).ToList();
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
                var remove = Btn("×", async () => await ConfirmFinanceDeleteAsync(entry), "ChipButton");
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
        ComboBox? kind = null;
        TextBox? note = null;
        StackPanel? noteField = null;
        if (!balanceMode)
        {
            kind = new ComboBox { ItemsSource = new[] { "Uscita", "Entrata" }, SelectedIndex = 0, MinWidth = 280 };
            category = new ComboBox { ItemsSource = FinanceCategories, SelectedIndex = 0, MinWidth = 280 };
            note = new TextBox { PlaceholderText = "Facoltativa", MaxLength = 80, MinWidth = 280, Visibility = Visibility.Collapsed };
            noteField = Field("Nota", note);
            noteField.Visibility = Visibility.Collapsed;
            var noteToggle = new Button { Content = "+ Aggiungi una nota", Style = St("ChipButton"), HorizontalAlignment = HorizontalAlignment.Left };
            noteToggle.Click += (_, _) =>
            {
                noteField.Visibility = noteField.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
                note.Visibility = noteField.Visibility;
                noteToggle.Content = noteField.Visibility == Visibility.Visible ? "Nascondi nota" : "+ Aggiungi una nota";
            };
            fields.Children.Add(Field("Tipo", kind));
            fields.Children.Add(Field("Categoria", category));
            fields.Children.Add(noteToggle);
            fields.Children.Add(noteField);
        }
        fields.Children.Add(error);

        var dlg = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = balanceMode ? "Aggiorna saldo" : "Nuovo movimento",
            Content = fields,
            PrimaryButtonText = balanceMode ? "Salva" : "Aggiungi",
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
            else FinanceLedger.Add(_r.S, kind?.SelectedIndex == 1 ? "income" : "expense", cents, category?.SelectedItem?.ToString() ?? "Altro", note?.Text ?? "");
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

    private UIElement MonthlyFlowChart(IReadOnlyList<FinanceEntry> entries)
    {
        var now = DateOnly.FromDateTime(DateTime.Today);
        var months = Enumerable.Range(0, 6).Reverse().Select(offset =>
        {
            var date = now.AddDays(1 - now.Day).AddMonths(-offset);
            var moves = entries.Where(e => LocalDate(e.Ts).Year == date.Year && LocalDate(e.Ts).Month == date.Month).ToList();
            var net = moves.Where(e => e.Kind == "income").Sum(e => e.Cents) - moves.Where(e => e.Kind == "expense").Sum(e => -e.Cents);
            return (Date: date, Net: net);
        }).ToList();
        var maximum = Math.Max(1, months.Max(p => Math.Abs(p.Net)));
        const double chartWidth = 640;
        const double chartHeight = 112;
        const double zeroY = 58;
        const double maxBarHeight = 43;
        var canvas = new Microsoft.UI.Xaml.Controls.Canvas { Width = chartWidth, Height = chartHeight };
        var axis = new Border { Width = chartWidth, Height = 1, Background = Canone.Get("LineSeparator", Dark) };
        Microsoft.UI.Xaml.Controls.Canvas.SetLeft(axis, 0);
        Microsoft.UI.Xaml.Controls.Canvas.SetTop(axis, zeroY);
        canvas.Children.Add(axis);
        for (var i = 0; i < months.Count; i++)
        {
            var (date, net) = months[i];
            var height = Math.Abs(net) == 0 ? 4 : Math.Max(4, Math.Abs(net) / (double)maximum * maxBarHeight);
            var width = 44d;
            var bar = new Border
            {
                Width = width, Height = height, CornerRadius = new CornerRadius(5),
                Background = net > 0 ? Canone.Get("PositiveOnSubtle", Dark) : net < 0 ? Canone.Get("CriticalOnSubtle", Dark) : Canone.Get("BgSunken", Dark),
            };
            Microsoft.UI.Xaml.Controls.Canvas.SetLeft(bar, i * (chartWidth / months.Count) + (chartWidth / months.Count - width) / 2);
            Microsoft.UI.Xaml.Controls.Canvas.SetTop(bar, net >= 0 ? zeroY - height : zeroY + 2);
            ToolTipService.SetToolTip(bar, date.ToString("MMMM", Italian) + " · " + (net < 0 ? "−" : "+") + Euro(Math.Abs(net)));
            canvas.Children.Add(bar);
        }
        var labels = new Grid { HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var _ in months) labels.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        for (var i = 0; i < months.Count; i++)
        {
            var label = new TextBlock { Text = months[i].Date.ToString("MMM", Italian), Style = St("FootnoteTertiary"), HorizontalAlignment = HorizontalAlignment.Center };
            Grid.SetColumn(label, i); labels.Children.Add(label);
        }
        return new StackPanel { Spacing = 2, Children = { new Viewbox { Height = chartHeight, Stretch = Stretch.Fill, Child = canvas }, labels } };
    }
}
