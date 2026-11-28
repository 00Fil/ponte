using System.Numerics;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Ponte;

/// <summary>Foto del fisico, come su Android (BodyPhoto.kt): time lapse nel cruscotto, galleria col peso, foto a tutto schermo con confronto a cursore.</summary>
public sealed partial class MainWindow
{
    /// <summary>Cosa c'è sopra l'app: "camera", "photo" (Corpo), "cam" (telecamera a tutto schermo) o niente.</summary>
    private string? _bdOver;
    private int _bdRange = 1;
    private readonly Dictionary<string, BitmapImage> _bdImages = new();

    private static string BdKg(double kg) => kg.ToString("0.0", It);
    private static string BdSigned(double d) => (d > 0.05 ? "+" : d < -0.05 ? "−" : "") + BdKg(Math.Abs(d)) + " kg";
    private static string BdDate(string day, string format)
    {
        var s = BodyStore.KeyTime(day).ToString(format, It);
        return s.Length == 0 ? s : char.ToUpper(s[0], It) + s[1..];
    }

    private static Dictionary<string, double> BdWeights(BodyState b)
    {
        var w = new Dictionary<string, double>();
        foreach (var x in b.Weights) w[x.Day] = x.Kg;
        return w;
    }

    private BitmapImage BdImage(string path)
    {
        if (_bdImages.TryGetValue(path, out var cached)) return cached;
        // IgnoreImageCache: rifatta la foto di oggi, il file ha lo stesso nome
        var image = new BitmapImage { CreateOptions = BitmapCreateOptions.IgnoreImageCache, UriSource = new Uri(path) };
        _bdImages[path] = image;
        return image;
    }

    private void BdPhotosChanged()
    {
        _bdImages.Clear();
        if (_tab == 4) RenderBody();
    }

    /// <summary>Velo scuro in basso, perché la scritta bianca si legga sopra qualsiasi foto.</summary>
    private static Border BdCaption(UIElement child, double radius) => new()
    {
        VerticalAlignment = VerticalAlignment.Bottom, Padding = new Thickness(10, 16, 10, 7), CornerRadius = new CornerRadius(0, 0, radius, radius),
        Background = Vertical((Argb(0, 0, 0, 0), 0), (Argb(160, 0, 0, 0), 1)), Child = child, IsHitTestVisible = false,
    };

    // ---------------------------------------------------------------- time lapse

    /// <summary>Riquadro del cruscotto: il time lapse a sinistra, i numeri della trasformazione a destra.</summary>
    private Button BdLapseCard(BodyState b)
    {
        var days = BodyPhotos.Days();
        var w = BdWeights(b);
        var stage = new Grid { Width = 162, Height = 216, Background = B("BgSunken") };
        if (days.Count == 0)
        {
            var shoot = Bare(Center(Icons.Glyph(Tab.Camera, B("LabelTertiary"), 34)), OpenBodyCamera, "Scatta la prima foto", 20);
            shoot.HorizontalAlignment = HorizontalAlignment.Stretch;
            shoot.VerticalAlignment = VerticalAlignment.Stretch;
            stage.Children.Add(shoot);
        }
        else BdTimeLapse(stage, days, w);
        RoundClip(stage, 20);

        var top = VStack(2, Semi("Trasformazione", 13, "LabelSecondary"));
        var info = new Grid { Height = 216, RowDefinitions = { new RowDefinition { Height = GridLength.Auto }, new RowDefinition() } };
        info.Children.Add(top);
        FrameworkElement low;
        if (days.Count == 0) low = T("Scatta la prima foto", 16, "LabelTertiary");
        else
        {
            var span = (int)(BodyStore.KeyTime(days[^1]) - BodyStore.KeyTime(days[0])).TotalDays + 1;
            top.Children.Add(T(span == 1 ? "1 giorno" : $"{span} giorni", 20, "LabelPrimary", true));
            var stats = VStack(6, BdStat("Foto", days.Count.ToString(It)), BdStat("Dal", BodyStore.KeyTime(days[0]).ToString("d MMM yyyy", It)));
            if (days.Count > 1 && w.TryGetValue(days[0], out var a) && w.TryGetValue(days[^1], out var z))
            {
                stats.Children.Add(BdStat("Peso", $"{BdKg(a)} → {BdKg(z)} kg"));
                stats.Children.Add(BdStat("Variazione", BdSigned(z - a), C("AccentDefault")));
            }
            low = stats;
        }
        low.VerticalAlignment = VerticalAlignment.Bottom;
        Grid.SetRow(low, 1);
        info.Children.Add(low);

        var card = Tile(Cols("a*", 16, stage, info), () => BdGo("gallery"), null, "Galleria", new Thickness(16));
        card.HorizontalAlignment = HorizontalAlignment.Stretch;
        return card;
    }

    /// <summary>Le foto in sequenza, avanti e indietro in loop con dissolvenza fra una e l'altra. Lo muove il compositore.</summary>
    private void BdTimeLapse(Grid stage, List<string> days, Dictionary<string, double> weights)
    {
        var picks = days.Count <= 40 ? days : Enumerable.Range(0, 40).Select(i => days[(int)Math.Round(i * (days.Count - 1) / 39.0)]).ToList();
        var n = picks.Count;
        var images = new List<Image>();
        var labels = new List<TextBlock>();
        var names = new Grid();
        for (var i = 0; i < n; i++)
        {
            var image = new Image { Source = BdImage(BodyPhotos.Thumb(picks[i])), Stretch = Stretch.UniformToFill, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            images.Add(image);
            stage.Children.Add(image);
            var text = T(BdDate(picks[i], "ddd d MMM") + (weights.TryGetValue(picks[i], out var kg) ? $" · {BdKg(kg)} kg" : ""), 11);
            text.Foreground = White;
            labels.Add(text);
            names.Children.Add(text);
        }
        stage.Children.Add(BdCaption(names, 20));
        if (n < 2) return;

        var length = TimeSpan.FromMilliseconds(Math.Clamp(n * 190, 2200, 9000));
        stage.Loaded += (_, _) =>
        {
            try
            {
                for (var i = 0; i < n; i++)
                {
                    BdLoop(labels[i], length, BdStepKeys(i, n));
                    if (i == 0) continue;
                    // ogni foto sta sopra la precedente e compare fra il suo turno e quello di prima
                    BdLoop(images[i], length, new[] { (0f, 0f), (BdEaseTime((i - 1.0) / (n - 1)), 0f), (BdEaseTime((double)i / (n - 1)), 1f), (1f, 1f) });
                }
            }
            catch (Exception e) { Crash.Log(e, "Time lapse"); }
        };
    }

    /// <summary>La scritta di una foto si vede solo nel suo turno.</summary>
    private static (float, float)[] BdStepKeys(int i, int n)
    {
        const float eps = 0.0005f;
        var keys = new List<(float, float)>();
        if (i == 0) keys.Add((0f, 1f));
        else
        {
            var from = BdEaseTime((i - 0.5) / (n - 1));
            keys.Add((0f, 0f)); keys.Add((from - eps, 0f)); keys.Add((from, 1f));
        }
        if (i == n - 1) keys.Add((1f, 1f));
        else
        {
            var to = BdEaseTime((i + 0.5) / (n - 1));
            keys.Add((to - eps, 1f)); keys.Add((to, 0f)); keys.Add((1f, 0f));
        }
        return keys.ToArray();
    }

    private static void BdLoop(UIElement el, TimeSpan length, (float Time, float Value)[] keys)
    {
        var v = ElementCompositionPreview.GetElementVisual(el);
        var a = v.Compositor.CreateScalarKeyFrameAnimation();
        var linear = v.Compositor.CreateLinearEasingFunction();
        foreach (var (time, value) in keys) a.InsertKeyFrame(Math.Clamp(time, 0f, 1f), value, linear);
        a.Duration = length;
        a.IterationBehavior = AnimationIterationBehavior.Forever;
        a.Direction = Microsoft.UI.Composition.AnimationDirection.Alternate;
        v.StartAnimation("Opacity", a);
    }

    /// <summary>Quando l'andamento morbido (0,45 · 0 · 0,55 · 1) arriva al valore dato: la curva al contrario.</summary>
    private static float BdEaseTime(double value)
    {
        double lo = 0, hi = 1;
        for (var k = 0; k < 24; k++)
        {
            var s = (lo + hi) / 2;
            if (3 * (1 - s) * s * s + s * s * s < value) lo = s; else hi = s;
        }
        var t = (lo + hi) / 2;
        return (float)(3 * (1 - t) * (1 - t) * t * 0.45 + 3 * (1 - t) * t * t * 0.55 + t * t * t);
    }

    // ---------------------------------------------------------------- galleria

    private void BdGallery(StackPanel h)
    {
        var b = _r.Bd.State;
        var today = BodyStore.DayKey();
        var photos = BodyPhotos.Days();
        var w = BdWeights(b);
        h.Children.Add(BackRow("Corpo", "Galleria", () => BdGo(null)));

        var shoot = Btn(photos.Contains(today) ? "Rifai la foto" : "Foto di oggi", OpenBodyCamera, "PrimaryButton");
        var weigh = Btn("Peso", () => BdWeightDialog(today));
        var import = Btn("Importa", BdImport);
        foreach (var x in new[] { shoot, weigh, import }) x.HorizontalAlignment = HorizontalAlignment.Stretch;
        // pagina larga: tasti e andamento a sinistra, le foto a destra
        var large = Spread > 1;
        var side = large ? new StackPanel { Spacing = 12 } : h;
        var shots = large ? new StackPanel { Spacing = 12 } : h;
        if (large) h.Children.Add(Beside((Part(), side), (Part(1.5), shots)));
        side.Children.Add(Cols("***", 10, shoot, weigh, import));

        var card = VStack(16, Segmented("bdrange", new[] { "30 giorni", "90 giorni", "Tutto" }, _bdRange, i => { _bdRange = i; RenderBody(); }));
        var from = _bdRange == 2 ? DateTime.MinValue : DateTime.Today.AddDays(_bdRange == 0 ? -30 : -90);
        var pts = b.Weights.Where(x => BodyStore.KeyTime(x.Day) >= from).ToList();
        if (pts.Count >= 2)
        {
            card.Children.Add(BdWeightChart(pts, b.Goals.GoalKg10 / 10.0, 0, 160));
            card.Children.Add(Cols("****", 8,
                BdStat("Attuale", BdKg(pts[^1].Kg) + " kg"), BdStat("Variazione", BdSigned(pts[^1].Kg - pts[0].Kg), C("AccentDefault")),
                BdStat("Minimo", BdKg(pts.Min(x => x.Kg)) + " kg"), BdStat("Massimo", BdKg(pts.Max(x => x.Kg)) + " kg")));
        }
        else card.Children.Add(T("Servono due pesate", 16, "LabelTertiary"));
        side.Children.Add(Panelled(card, new Thickness(24)));

        var all = photos.Concat(b.Weights.Select(x => x.Day)).Distinct().OrderByDescending(d => d, StringComparer.Ordinal).ToList();
        var have = photos.ToHashSet();
        var grid = new StackPanel { Spacing = 8 };
        var built = 0.0;
        void Fill(double width)
        {
            if (width < 1 || Math.Abs(width - built) < 1) return;
            built = width;
            grid.Children.Clear();
            var cols = Math.Max(3, (int)((width + 8) / 176));
            var side = Math.Floor((width - 8 * (cols - 1)) / cols);
            foreach (var row in all.Chunk(cols))
                grid.Children.Add(HStack(8, row.Select(d => (UIElement)BdCell(d, have.Contains(d), w, side)).ToArray()));
        }
        // la larghezza si conosce già dalla pagina: le foto escono subito della misura giusta
        var page = double.IsNaN(h.Width) ? 688 : h.Width;
        Fill(large ? Math.Floor((page - Gap) * 0.6) : page);
        grid.SizeChanged += (_, e) => Fill(e.NewSize.Width);
        shots.Children.Add(grid);
    }

    private Button BdCell(string day, bool photo, Dictionary<string, double> w, double width)
    {
        var has = w.TryGetValue(day, out var kg);
        var content = new Grid();
        if (!photo) content.Children.Add(Center(T(has ? BdKg(kg) : "—", 20, "LabelTertiary", true)));
        var label = T(BdDate(day, "d MMM") + (has ? " · " + BdKg(kg) : ""), 11);
        label.Foreground = White;
        content.Children.Add(BdCaption(label, 16));
        var face = new Border
        {
            CornerRadius = new CornerRadius(16), Child = content,
            Background = photo ? new ImageBrush { ImageSource = BdImage(BodyPhotos.Thumb(day)), Stretch = Stretch.UniformToFill } : B("BgSunken"),
        };
        var cell = Bare(face, () => BdOpenPhoto(day), BdDate(day, "d MMMM yyyy"), 16);
        cell.Width = width;
        cell.Height = Math.Round(width * 4 / 3);
        Bounce(cell, 0.97f);
        return cell;
    }

    // ---------------------------------------------------------------- sopra l'app

    private void BdShowLayer(string mode, UIElement content)
    {
        ClosePanel();
        SetDevOpen(false);
        var fresh = _bdOver == null;
        _bdOver = mode;
        BodyLayer.Children.Clear();
        BodyLayer.Children.Add(content);
        BodyLayer.Visibility = Visibility.Visible;
        if (!fresh) return;
        BodyLayer.Opacity = 0;
        FadeTo(BodyLayer, 1, 240);
    }

    private void CloseBodyLayer()
    {
        if (_bdOver == null) return;
        var was = _bdOver;
        if (was == "camera") BdStopCamera();
        _camShot = null;
        _bdOver = null;
        BodyLayer.Visibility = Visibility.Collapsed;
        BodyLayer.Children.Clear();
        if (was == "cam") { _camHd = false; CamPulse(); }
    }

    /// <summary>Esc: dallo scatto si torna all'anteprima, altrimenti si chiude.</summary>
    private void BdBack()
    {
        if (_bdOver == "camera" && _camShot != null) BdRetake();
        else CloseBodyLayer();
    }

    private Button BdGlass(UIElement icon, double size, Action click, string name)
    {
        var face = new Border { Width = size, Height = size, CornerRadius = new CornerRadius(size / 2), Background = Sb(Argb(40, 255, 255, 255)), Child = Center((FrameworkElement)icon) };
        var b = Bare(face, click, name, size / 2);
        ToolTipService.SetToolTip(b, name);
        Bounce(b, 1.1f);
        return b;
    }

    private TextBlock BdWhite(string text, double size, bool medium = false, byte alpha = 255)
    {
        var t = T(text, size, "LabelPrimary", medium);
        t.Foreground = Sb(Argb(alpha, 255, 255, 255));
        return t;
    }

    /// <summary>Tasto a pillola sul nero.</summary>
    private Button BdPill(string text, bool primary, Action click)
    {
        var face = new Border
        {
            Height = 44, MinWidth = 112, CornerRadius = new CornerRadius(22), Padding = new Thickness(24, 0, 24, 0),
            Background = Sb(primary ? C("AccentDefault") : Argb(40, 255, 255, 255)), Child = Center(BdWhite(text, 15, true)),
        };
        var b = Bare(face, click, text, 22);
        Bounce(b);
        return b;
    }

    /// <summary>Peso del giorno: numero grande con passi da 1 kg e da 100 g.</summary>
    private Grid BdStepper(int start, Action<int> changed, bool dark)
    {
        var v = start;
        var value = dark ? BdWhite(BdKg(v / 10.0), 44, true) : T(BdKg(v / 10.0), 44, "LabelPrimary", true);
        var unit = dark ? BdWhite(" kg", 16, false, 160) : T(" kg", 16, "LabelTertiary");
        unit.VerticalAlignment = VerticalAlignment.Bottom;
        unit.Margin = new Thickness(0, 0, 0, 9);
        var middle = HStack(0, value, unit);
        middle.HorizontalAlignment = HorizontalAlignment.Center;
        Button Step(string label, int d, string name)
        {
            void Apply()
            {
                v = Math.Clamp(v + d, 200, 3000);
                value.Text = BdKg(v / 10.0);
                changed(v);
            }
            var b = dark ? BdGlass(BdWhite(label, 14), 40, Apply, name) : BubbleButton(Center(T(label, 14)), 40, Apply, name);
            b.VerticalAlignment = VerticalAlignment.Center;
            return b;
        }
        var g = Cols("aa*aa", 10, Step("−1", -10, "Un chilo in meno"), Step("−", -1, "Un etto in meno"), middle, Step("+", 1, "Un etto in più"), Step("+1", 10, "Un chilo in più"));
        g.MinWidth = 340;
        return g;
    }

    private int BdDefaultKg10(string day)
    {
        var ws = _r.Bd.State.Weights;
        return ws.FirstOrDefault(x => x.Day == day)?.Kg10 ?? (ws.Count > 0 ? ws[^1].Kg10 : 700);
    }

    private async Task BdWeightDialog(string day)
    {
        var kg10 = BdDefaultKg10(day);
        var dialog = new ContentDialog
        {
            XamlRoot = Root.XamlRoot, RequestedTheme = Root.ActualTheme, Title = "Peso", Content = BdStepper(kg10, v => kg10 = v, false),
            PrimaryButtonText = "Salva", CloseButtonText = "Annulla", DefaultButton = ContentDialogButton.Primary,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        _r.Bd.SetWeight(day, kg10);
        if (_tab == 4) RenderBody();
        if (_bdOver == "photo") BdOpenPhoto(day);
    }

    private async Task BdAlert(string title) =>
        await new ContentDialog { XamlRoot = Root.XamlRoot, RequestedTheme = Root.ActualTheme, Title = title, CloseButtonText = "Chiudi" }.ShowAsync();

    /// <summary>Una foto già fatta (per esempio col telefono): va nel giorno in cui è stata scattata.</summary>
    private async Task BdImport()
    {
        try
        {
            var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.PicturesLibrary };
            foreach (var ext in new[] { ".jpg", ".jpeg", ".png", ".heic", ".webp", ".bmp" }) picker.FileTypeFilter.Add(ext);
            InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
            var file = await picker.PickSingleFileAsync();
            if (file == null) return;
            var day = BodyStore.DayKey(await BodyPhotos.TakenOn(file));
            if (BodyPhotos.Days().Contains(day))
            {
                var ask = new ContentDialog
                {
                    XamlRoot = Root.XamlRoot, RequestedTheme = Root.ActualTheme, Title = $"Sostituire la foto del {BodyStore.KeyTime(day).ToString("d MMMM yyyy", It)}?",
                    PrimaryButtonText = "Sostituisci", CloseButtonText = "Annulla", DefaultButton = ContentDialogButton.Close,
                };
                if (await ask.ShowAsync() != ContentDialogResult.Primary) return;
            }
            using var input = await file.OpenAsync(FileAccessMode.Read);
            using var photo = await BodyPhotos.Read(input);
            if (!await BodyPhotos.Save(day, photo)) { await BdAlert("Foto non salvata"); return; }
            BdPhotosChanged();
            BdOpenPhoto(day);
        }
        catch (Exception e)
        {
            Crash.Log(e, "Importazione foto");
            await BdAlert("Immagine non leggibile");
        }
    }

    // ---------------------------------------------------------------- una foto a tutto schermo

    private void BdOpenPhoto(string day)
    {
        var b = _r.Bd.State;
        var photos = BodyPhotos.Days();
        var has = photos.Contains(day);
        var first = photos.Count > 0 && photos[0] != day ? photos[0] : null;
        var w = BdWeights(b);
        var now = w.TryGetValue(day, out var kg) ? BdKg(kg) + " kg" : "";

        var compare = false;
        var frac = 0.5;
        var sure = false;

        var sub = BdWhite(now, 13, false, 180);
        var close = BdGlass(Icons.Of(Ico.Close, White, 18), 40, CloseBodyLayer, "Chiudi");
        close.Loaded += (_, _) => close.Focus(FocusState.Programmatic);
        var head = Cols("*a", 12, VStack(2, BdWhite(BdDate(day, "dddd d MMMM yyyy"), 17, true), sub), close);
        head.Margin = new Thickness(24, 52, 24, 12);

        var stage = new Grid { Background = Clear };
        Grid? before = null;
        var line = new Rectangle { Width = 2, Fill = White, HorizontalAlignment = HorizontalAlignment.Left, Visibility = Visibility.Collapsed, IsHitTestVisible = false };
        var handle = new Grid
        {
            Width = 28, Height = 28, HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Center,
            Visibility = Visibility.Collapsed, IsHitTestVisible = false,
            Children = { new Ellipse { Fill = White }, Center(new Ellipse { Width = 8, Height = 8, Fill = Sb(Microsoft.UI.Colors.Black) }) },
        };
        if (has)
        {
            stage.Children.Add(new Image { Source = BdImage(BodyPhotos.Photo(day)), Stretch = Stretch.Uniform });
            if (first != null)
            {
                before = new Grid { Visibility = Visibility.Collapsed, Children = { new Image { Source = BdImage(BodyPhotos.Photo(first)), Stretch = Stretch.Uniform } } };
                stage.Children.Add(before);
            }
        }
        else if (now.Length > 0) stage.Children.Add(Center(BdWhite(now, 56, true)));
        stage.Children.Add(line);
        stage.Children.Add(handle);

        // il cursore corre sulla larghezza della foto, non su quella della finestra
        (double Left, double Width) Shown()
        {
            var width = Math.Min(stage.ActualWidth, stage.ActualHeight * 3 / 4);
            return ((stage.ActualWidth - width) / 2, width);
        }
        void Place()
        {
            if (before == null) return;
            var (left, width) = Shown();
            var x = left + width * frac;
            before.Clip = new RectangleGeometry { Rect = new Rect(0, 0, Math.Max(0, x), stage.ActualHeight) };
            line.Margin = new Thickness(x - 1, 0, 0, 0);
            handle.Margin = new Thickness(x - 14, 0, 0, 0);
        }
        var dragging = false;
        void Drag(Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
        {
            var (left, width) = Shown();
            if (width < 1) return;
            frac = Math.Clamp((e.GetCurrentPoint(stage).Position.X - left) / width, 0, 1);
            Place();
        }
        stage.SizeChanged += (_, _) => Place();
        stage.PointerPressed += (_, e) => { if (!compare) return; dragging = stage.CapturePointer(e.Pointer); Drag(e); };
        stage.PointerMoved += (_, e) => { if (dragging) Drag(e); };
        stage.PointerReleased += (_, e) => { dragging = false; stage.ReleasePointerCapture(e.Pointer); };
        stage.PointerCaptureLost += (_, _) => dragging = false;

        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(16, 16, 16, 24) };
        void Actions()
        {
            actions.Children.Clear();
            if (before != null) actions.Children.Add(BdPill(compare ? "Foto" : "Confronta", true, () =>
            {
                compare = !compare;
                before.Visibility = line.Visibility = handle.Visibility = compare ? Visibility.Visible : Visibility.Collapsed;
                sub.Text = compare
                    ? "Prima " + BdDate(first!, "ddd d MMM") + (w.TryGetValue(first!, out var f) ? $" · {BdKg(f)} kg" : "")
                    : now;
                Place();
                Actions();
            }));
            actions.Children.Add(BdPill("Peso", false, () => _ = BdWeightDialog(day)));
            var delete = BdPill(sure ? "Conferma" : "Elimina", false, () =>
            {
                if (!sure) { sure = true; Actions(); return; }
                BodyPhotos.Delete(day);
                _r.Bd.ClearWeight(day);
                CloseBodyLayer();
                BdPhotosChanged();
            });
            if (delete.Content is Border { Child: TextBlock label }) label.Foreground = Sb(Canone.Color("CriticalOnSubtle", true));
            actions.Children.Add(delete);
        }
        Actions();

        var page = new Grid { RowDefinitions = { new RowDefinition { Height = GridLength.Auto }, new RowDefinition(), new RowDefinition { Height = GridLength.Auto } } };
        Grid.SetRow(stage, 1);
        Grid.SetRow(actions, 2);
        page.Children.Add(head);
        page.Children.Add(stage);
        page.Children.Add(actions);
        BdShowLayer("photo", page);
    }
}
