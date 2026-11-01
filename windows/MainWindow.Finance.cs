using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Ponte;

public sealed partial class MainWindow
{
    private static readonly string[] FinanceCategories = { "Altro", "Casa", "Cibo", "Trasporti", "Tempo libero", "Salute" };
    private static readonly int[] FinanceBanknotes = { 5, 10, 20, 50, 100, 200 };
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

        var flowHeader = new Grid { ColumnSpacing = 12 };
        flowHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        flowHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        flowHeader.Children.Add(new StackPanel { Spacing = 3, Children = { Text("Entrate e uscite", "SectionTitle"), Text("Confronto mensile · ultimi 6 mesi", "Footnote") } });
        var netBadge = Badge((net >= 0 ? "+" : "−") + Euro(Math.Abs(net)), net >= 0 ? "Positive" : "Critical");
        Grid.SetColumn(netBadge, 1); flowHeader.Children.Add(netBadge);
        var flowLegend = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16, Children = { FinanceLegend("Entrate", true), FinanceLegend("Uscite", false) } };
        host.Children.Add(Card(flowHeader, MonthlyFlowChart(entries), flowLegend));
        host.Children.Add(Card(Text("Spese", "SectionTitle"), Text("Andamento giornaliero · ultimi 6 mesi", "Footnote"), Heatmap(entries)));

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
                category.Add(new ProgressBar { Minimum = 0, Maximum = 1, Value = (double)cents / max, Height = 8, Foreground = Canone.Get("CriticalOnSubtle", Dark) });
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
            MinWidth = 0,
            FontSize = 30,
            BorderThickness = new Thickness(0),
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            Padding = new Thickness(0),
            VerticalContentAlignment = VerticalAlignment.Center,
        };
        var error = new TextBlock { Text = "Inserisci un importo valido in euro.", Style = St("Footnote"), Foreground = Canone.Get("CriticalOnSubtle", Dark), Visibility = Visibility.Collapsed };
        amount.TextChanged += (_, _) => error.Visibility = Visibility.Collapsed;
        var fields = new StackPanel { Spacing = 10 };
        ComboBox? category = null;
        TextBox? note = null;
        StackPanel? noteField = null;
        var selectedKind = "expense";
        ContentDialog? dialog = null;
        if (!balanceMode)
        {
            var kindGrid = new Grid { ColumnSpacing = 8 };
            kindGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            kindGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            Button expenseButton = null!;
            Button incomeButton = null!;
            void ApplyKindStyles()
            {
                expenseButton.Background = Canone.Get(selectedKind == "expense" ? "CriticalSubtle" : "BgSurface", Dark);
                expenseButton.Foreground = Canone.Get(selectedKind == "expense" ? "CriticalOnSubtle" : "LabelSecondary", Dark);
                expenseButton.BorderBrush = Canone.Get(selectedKind == "expense" ? "CriticalOnSubtle" : "LineSeparator", Dark);
                incomeButton.Background = Canone.Get(selectedKind == "income" ? "PositiveSubtle" : "BgSurface", Dark);
                incomeButton.Foreground = Canone.Get(selectedKind == "income" ? "PositiveOnSubtle" : "LabelSecondary", Dark);
                incomeButton.BorderBrush = Canone.Get(selectedKind == "income" ? "PositiveOnSubtle" : "LineSeparator", Dark);
            }
            expenseButton = Btn("−  Uscita", () => { selectedKind = "expense"; ApplyKindStyles(); UpdateDialogAction(); }, "SecondaryButton");
            incomeButton = Btn("+  Entrata", () => { selectedKind = "income"; ApplyKindStyles(); UpdateDialogAction(); }, "SecondaryButton");
            ApplyKindStyles();
            Grid.SetColumn(incomeButton, 1);
            kindGrid.Children.Add(expenseButton); kindGrid.Children.Add(incomeButton);
            fields.Children.Add(new StackPanel { Spacing = 6, Children = { Text("Tipo di movimento", "Footnote"), kindGrid } });

            var banknoteOrder = new List<int>();
            var countLabels = new Dictionary<int, TextBlock>();
            var countPills = new Dictionary<int, Border>();
            var noteBorders = new Dictionary<int, Border>();
            var noteForegrounds = new Dictionary<int, string>();
            var settingFromBanknote = false;
            var banknoteSummary = new Grid { ColumnSpacing = 8, Margin = new Thickness(0, 2, 0, 0) };
            banknoteSummary.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            banknoteSummary.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var banknoteSummaryText = Text("Tocca un taglio per aggiungerlo al totale.", "FootnoteTertiary");
            banknoteSummary.Children.Add(banknoteSummaryText);
            var banknoteTotal = Text("", "BodyText");
            Grid.SetColumn(banknoteTotal, 1); banknoteSummary.Children.Add(banknoteTotal);
            var banknoteGrid = new Grid { ColumnSpacing = 8, RowSpacing = 8 };
            for (var column = 0; column < 3; column++) banknoteGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            for (var row = 0; row < 2; row++) banknoteGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var backgrounds = new[] { "BgSunken", "CriticalSubtle", "AccentSubtle", "CautionSubtle", "PositiveSubtle", "CautionSubtle" };
            var foregrounds = new[] { "LabelSecondary", "CriticalOnSubtle", "AccentOnSubtle", "CautionOnSubtle", "PositiveOnSubtle", "CautionOnSubtle" };
            var banknoteHeader = new Grid { ColumnSpacing = 8 };
            banknoteHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            banknoteHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            banknoteHeader.Children.Add(new StackPanel { Spacing = 2, Children = { Text("Banconote", "BodyText"), Text("Scegli i tagli per comporre l'importo", "Footnote") } });
            var banknoteActions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
            Button undoBanknote = null!;
            Button clearBanknotes = null!;
            undoBanknote = Btn("Annulla", () =>
            {
                if (banknoteOrder.Count == 0 || !TryEuro(amount.Text, out var current)) return;
                var denomination = banknoteOrder[^1];
                banknoteOrder.RemoveAt(banknoteOrder.Count - 1);
                settingFromBanknote = true;
                amount.Text = (current - denomination).ToString("0.00", Italian);
                settingFromBanknote = false;
                RefreshBanknotes();
            }, "ChipButton");
            clearBanknotes = Btn("Azzera", () =>
            {
                banknoteOrder.Clear();
                settingFromBanknote = true;
                amount.Text = "";
                settingFromBanknote = false;
                RefreshBanknotes();
            }, "ChipButton");
            banknoteActions.Children.Add(undoBanknote); banknoteActions.Children.Add(clearBanknotes);
            Grid.SetColumn(banknoteActions, 1); banknoteHeader.Children.Add(banknoteActions);
            fields.Children.Add(banknoteHeader);
            amount.TextChanged += (_, _) =>
            {
                if (settingFromBanknote) return;
                banknoteOrder.Clear();
                RefreshBanknotes(manual: amount.Text.Length > 0);
            };
            for (var index = 0; index < FinanceBanknotes.Length; index++)
            {
                var denomination = FinanceBanknotes[index];
                var foregroundKey = foregrounds[index];
                var count = new TextBlock { Text = "×0", FontSize = 10, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Foreground = Canone.Get("AccentOnAccent", Dark) };
                var countPill = new Border { Visibility = Visibility.Collapsed, Background = Canone.Get("AccentDefault", Dark), CornerRadius = new CornerRadius(9), Padding = new Thickness(6, 2, 6, 2), Child = count };
                countLabels.Add(denomination, count); countPills.Add(denomination, countPill); noteForegrounds.Add(denomination, foregroundKey);

                var art = new Grid();
                art.Children.Add(new Border { Margin = new Thickness(4), BorderBrush = new SolidColorBrush(Canone.Color(foregroundKey, Dark)), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(9), Opacity = 0.28 });
                art.Children.Add(new Border { Width = 4, Height = 42, HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(11, 0, 0, 0), Background = Canone.Get(foregroundKey, Dark), CornerRadius = new CornerRadius(2), Opacity = 0.42 });
                var denominationLabel = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Spacing = 0, Margin = new Thickness(22, 0, 48, 0) };
                denominationLabel.Children.Add(new TextBlock { Text = "€" + denomination, FontSize = 23, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Foreground = Canone.Get(foregroundKey, Dark) });
                denominationLabel.Children.Add(new TextBlock { Text = "BANCONOTA · EURO", FontSize = 9, CharacterSpacing = 40, Foreground = Canone.Get(foregroundKey, Dark), Opacity = 0.82 });
                art.Children.Add(denominationLabel);
                var sealText = new TextBlock { Text = "€", FontSize = 20, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Foreground = Canone.Get(foregroundKey, Dark), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Opacity = 0.62 };
                var seal = new Border { Width = 34, Height = 34, CornerRadius = new CornerRadius(17), BorderThickness = new Thickness(1), BorderBrush = Canone.Get(foregroundKey, Dark), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 12, 0), Child = sealText, Opacity = 0.7 };
                art.Children.Add(seal);
                countPill.HorizontalAlignment = HorizontalAlignment.Right; countPill.VerticalAlignment = VerticalAlignment.Top; countPill.Margin = new Thickness(0, 8, 8, 0);
                art.Children.Add(countPill);

                var noteBorder = new Border { Background = Canone.Get(backgrounds[index], Dark), BorderBrush = Canone.Get("LineSeparator", Dark), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(13), MinHeight = 78, Child = art };
                noteBorders.Add(denomination, noteBorder);
                var button = new Button
                {
                    Content = noteBorder,
                    Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
                    BorderBrush = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
                    BorderThickness = new Thickness(0),
                    Padding = new Thickness(0),
                    HorizontalAlignment = HorizontalAlignment.Stretch,
                };
                AutomationProperties.SetName(button, "Aggiungi una banconota da " + denomination + " euro");
                button.Click += (_, _) =>
                {
                    if (!TryEuro(amount.Text, out var current)) current = 0;
                    banknoteOrder.Add(denomination);
                    settingFromBanknote = true;
                    amount.Text = (current + denomination).ToString("0.00", Italian);
                    settingFromBanknote = false;
                    RefreshBanknotes();
                };
                var row = index / 3;
                var column = index % 3;
                Grid.SetRow(button, row); Grid.SetColumn(button, column); banknoteGrid.Children.Add(button);
            }
            void RefreshBanknotes(bool manual = false)
            {
                var counts = banknoteOrder.GroupBy(value => value).ToDictionary(group => group.Key, group => group.Count());
                foreach (var denomination in FinanceBanknotes)
                {
                    var count = counts.GetValueOrDefault(denomination);
                    countLabels[denomination].Text = "×" + count;
                    countPills[denomination].Visibility = count > 0 ? Visibility.Visible : Visibility.Collapsed;
                    noteBorders[denomination].BorderBrush = Canone.Get(count > 0 ? noteForegrounds[denomination] : "LineSeparator", Dark);
                    noteBorders[denomination].BorderThickness = new Thickness(count > 0 ? 2 : 1);
                }
                undoBanknote.IsEnabled = banknoteOrder.Count > 0;
                clearBanknotes.IsEnabled = banknoteOrder.Count > 0;
                banknoteTotal.Text = banknoteOrder.Count == 0 ? "" : (banknoteOrder.Count + (banknoteOrder.Count == 1 ? " banconota" : " banconote") + " · " + Euro(banknoteOrder.Sum(value => (long)value * 100)));
                banknoteSummaryText.Text = banknoteOrder.Count > 0 ? "Importo composto dai tagli" : manual ? "Importo inserito manualmente" : "Tocca un taglio per aggiungerlo al totale.";
            }
            fields.Children.Add(banknoteGrid);
            fields.Children.Add(banknoteSummary);
            RefreshBanknotes();

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
            fields.Children.Add(Field("Categoria", category));
            fields.Children.Add(noteToggle);
            fields.Children.Add(noteField);
        }
        var amountLine = new Grid { ColumnSpacing = 10, MinHeight = 64 };
        amountLine.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        amountLine.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        amountLine.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        amountLine.Children.Add(new TextBlock { Text = "€", FontSize = 30, Foreground = Canone.Get("AccentDefault", Dark), VerticalAlignment = VerticalAlignment.Center, FontWeight = Microsoft.UI.Text.FontWeights.Medium });
        Grid.SetColumn(amount, 1); amountLine.Children.Add(amount);
        var currency = Text("EUR", "FootnoteTertiary"); currency.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(currency, 2); amountLine.Children.Add(currency);
        var amountCard = new Border { Background = Canone.Get("BgSurface", Dark), BorderBrush = Canone.Get("LineStrong", Dark), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(16), Padding = new Thickness(16, 8, 16, 8), Child = amountLine };
        var amountSection = new StackPanel { Spacing = 6, Children = { Text("Importo", "Footnote"), amountCard } };
        fields.Children.Insert(balanceMode ? 0 : 1, amountSection);
        fields.Children.Add(error);

        var dlg = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = balanceMode ? "Aggiorna saldo" : selectedKind == "income" ? "Nuova entrata" : "Nuova uscita",
            Content = new ScrollViewer { Content = fields, MaxHeight = 640, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
            PrimaryButtonText = balanceMode ? "Salva saldo" : selectedKind == "income" ? "Aggiungi entrata" : "Aggiungi uscita",
            CloseButtonText = "Annulla",
            DefaultButton = ContentDialogButton.Primary,
        };
        dialog = dlg;
        void UpdateDialogAction()
        {
            if (dialog == null || balanceMode) return;
            dialog.Title = selectedKind == "income" ? "Nuova entrata" : "Nuova uscita";
            dialog.PrimaryButtonText = selectedKind == "income" ? "Aggiungi entrata" : "Aggiungi uscita";
        }
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
            else FinanceLedger.Add(_r.S, selectedKind, cents, category?.SelectedItem?.ToString() ?? "Altro", note?.Text ?? "");
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
        var grid = new Grid { HorizontalAlignment = HorizontalAlignment.Left, ColumnSpacing = 3, RowSpacing = 3 };
        for (var i = 0; i < 26; i++) grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(16) });
        for (var i = 0; i < 7; i++) grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(14) });
        var accent = Canone.Color("AccentOnSubtle", Dark);
        for (var week = 0; week < 26; week++) for (var row = 0; row < 7; row++)
        {
            var date = start.AddDays(week * 7 + row);
            if (date > today) continue;
            var cents = days.GetValueOrDefault(date);
            var ratio = cents <= 0 ? 0 : Math.Log(1 + (double)cents) / Math.Log(1 + (double)max);
            var alpha = cents <= 0 ? (byte)255 : (byte)Math.Clamp(80 + ratio * 175, 0, 255);
            var brush = cents <= 0 ? Canone.Get("BgSunken", Dark) : new SolidColorBrush(Windows.UI.Color.FromArgb(alpha, accent.R, accent.G, accent.B));
            var cell = new Border { Width = 14, Height = 12, CornerRadius = new CornerRadius(3), Background = brush };
            ToolTipService.SetToolTip(cell, date.ToString("d MMM", Italian) + " · " + Euro(cents));
            Grid.SetColumn(cell, week); Grid.SetRow(cell, row); grid.Children.Add(cell);
        }
        var weekTotal = entries.Where(e => e.Kind == "expense" && LocalDate(e.Ts) >= today.AddDays(-6)).Sum(e => -e.Cents);
        var halfYear = entries.Where(e => e.Kind == "expense" && LocalDate(e.Ts) >= start).Sum(e => -e.Cents);
        var legend = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 5, VerticalAlignment = VerticalAlignment.Center };
        legend.Children.Add(Text("Meno", "FootnoteTertiary"));
        foreach (var color in new[] { "BgSunken", "AccentSubtle", "AccentOnSubtle" })
            legend.Children.Add(new Border { Width = 11, Height = 11, CornerRadius = new CornerRadius(3), Background = Canone.Get(color, Dark) });
        legend.Children.Add(Text("Più", "FootnoteTertiary"));
        var labels = new StackPanel { Spacing = 4, Children =
        {
            grid,
            new Grid { ColumnSpacing = 8, ColumnDefinitions = { new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }, new ColumnDefinition { Width = GridLength.Auto } },
                Children = { Text(start.ToString("d MMM", Italian) + "     →     " + today.ToString("d MMM", Italian), "FootnoteTertiary"), legend } },
            Text("Ultimi 7 giorni " + Euro(weekTotal) + "  ·  periodo " + Euro(halfYear), "Footnote"),
        } };
        if (days.Count == 0) labels.Children.Add(Text("Registra un'uscita per popolare la mappa", "FootnoteTertiary"));
        Grid.SetColumn(legend, 1);
        return labels;
    }

    private UIElement MonthlyFlowChart(IReadOnlyList<FinanceEntry> entries)
    {
        var now = DateOnly.FromDateTime(DateTime.Today);
        var months = Enumerable.Range(0, 6).Reverse().Select(offset =>
        {
            var date = now.AddDays(1 - now.Day).AddMonths(-offset);
            var moves = entries.Where(e => LocalDate(e.Ts).Year == date.Year && LocalDate(e.Ts).Month == date.Month).ToList();
            var income = moves.Where(e => e.Kind == "income").Sum(e => e.Cents);
            var expense = moves.Where(e => e.Kind == "expense").Sum(e => -e.Cents);
            return (Date: date, Income: income, Expense: expense);
        }).ToList();
        var maximum = Math.Max(1, months.Max(p => Math.Max(p.Income, p.Expense)));
        const double chartWidth = 640;
        const double chartHeight = 92;
        const double baseline = 82;
        const double maxBarHeight = 68;
        var canvas = new Microsoft.UI.Xaml.Controls.Canvas { Width = chartWidth, Height = chartHeight };
        var axis = new Border { Width = chartWidth, Height = 1, Background = Canone.Get("LineSeparator", Dark) };
        Microsoft.UI.Xaml.Controls.Canvas.SetLeft(axis, 0);
        Microsoft.UI.Xaml.Controls.Canvas.SetTop(axis, baseline);
        canvas.Children.Add(axis);
        for (var i = 0; i < months.Count; i++)
        {
            var (date, income, expense) = months[i];
            const double width = 19;
            const double gap = 5;
            var groupWidth = width * 2 + gap;
            var startX = i * (chartWidth / months.Count) + (chartWidth / months.Count - groupWidth) / 2;
            foreach (var (amount, barIndex, kind) in new[] { (income, 0, "Entrate"), (expense, 1, "Uscite") })
            {
                var height = amount <= 0 ? 0 : Math.Max(3, amount / (double)maximum * maxBarHeight);
                var bar = new Border
                {
                    Width = width, Height = height, CornerRadius = new CornerRadius(5),
                    Background = Canone.Get(kind == "Entrate" ? "PositiveOnSubtle" : "CriticalOnSubtle", Dark),
                };
                Microsoft.UI.Xaml.Controls.Canvas.SetLeft(bar, startX + barIndex * (width + gap));
                Microsoft.UI.Xaml.Controls.Canvas.SetTop(bar, baseline - height);
                ToolTipService.SetToolTip(bar, date.ToString("MMMM", Italian) + " · " + kind + " " + Euro(amount));
                canvas.Children.Add(bar);
            }
        }
        var labels = new Grid { HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var _ in months) labels.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        for (var i = 0; i < months.Count; i++)
        {
            var label = new TextBlock { Text = months[i].Date.ToString("MMM", Italian), Style = St("FootnoteTertiary"), HorizontalAlignment = HorizontalAlignment.Center };
            Grid.SetColumn(label, i); labels.Children.Add(label);
        }
        var chart = new StackPanel { Spacing = 2, Children = { new Viewbox { Height = chartHeight, Stretch = Stretch.Fill, Child = canvas }, labels } };
        if (months.All(p => p.Income == 0 && p.Expense == 0)) chart.Children.Add(Text("Registra un movimento per vedere il confronto", "FootnoteTertiary"));
        return chart;
    }

    private UIElement FinanceLegend(string label, bool income)
    {
        var dot = new Border { Width = 8, Height = 8, CornerRadius = new CornerRadius(4), Background = Canone.Get(income ? "PositiveOnSubtle" : "CriticalOnSubtle", Dark) };
        return new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, VerticalAlignment = VerticalAlignment.Center, Children = { dot, Text(label, "Footnote") } };
    }
}
