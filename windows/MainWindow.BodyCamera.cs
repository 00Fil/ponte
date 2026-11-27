using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Shapes;
using Windows.Devices.Enumeration;
using Windows.Foundation;
using Windows.Graphics.Imaging;
using Windows.Media.Capture;
using Windows.Media.Capture.Frames;
using Windows.Media.Core;
using Windows.Media.MediaProperties;
using Windows.Media.Playback;
using Windows.Storage.Streams;
using Windows.System;

namespace Ponte;

/// <summary>
/// Fotocamera per la foto fisica, come su Android (BodyCamera). Per rifare sempre la stessa inquadratura: la foto precedente in trasparenza
/// sopra l'anteprima, sagoma o griglia, linea di mezzeria e timer per scattare a distanza. Camera, timer e guida si ricordano.
/// L'anteprima è a specchio, la foto salvata no.
/// </summary>
public sealed partial class MainWindow
{
    private MediaCapture? _cam;
    private MediaPlayer? _camPlayer;
    private MediaSource? _camMedia;
    private MediaFrameSource? _camSource;
    private List<DeviceInformation> _camList = new();
    private SoftwareBitmap? _camShot;
    /// <summary>Cambia a ogni avvio e arresto: quello che finisce in ritardo si riconosce e si scarta.</summary>
    private int _camGen;
    private bool _camBusy;
    private int _camKg10;

    private MediaPlayerElement _camView = null!;
    private Grid _camLive = null!;
    private Image _camGhost = null!;
    private Image _camStill = null!;
    private FrameworkElement _camSilhouette = null!;
    private FrameworkElement _camThirds = null!;
    private StackPanel _camNote = null!;
    private TextBlock _camCount = null!;
    private StackPanel _camBar = null!;
    private Button _camBackButton = null!;

    private void OpenBodyCamera()
    {
        _camShot = null;
        _camBusy = false;
        _camKg10 = BdDefaultKg10(BodyStore.DayKey());
        var days = BodyPhotos.Days();
        var today = BodyStore.DayKey();
        // foto di riferimento: l'ultima, meglio se non è quella di oggi
        var reference = days.LastOrDefault(d => d != today) ?? days.LastOrDefault();

        _camView = new MediaPlayerElement { Stretch = Stretch.UniformToFill, AreTransportControlsEnabled = false, IsTabStop = false };
        _camGhost = new Image { Stretch = Stretch.UniformToFill, Opacity = 0.4 };
        if (reference != null) _camGhost.Source = BdImage(BodyPhotos.Photo(reference));
        _camSilhouette = BdSilhouette();
        _camThirds = BdThirds();
        _camLive = new Grid
        {
            RenderTransformOrigin = new Point(0.5, 0.5), RenderTransform = new ScaleTransform { ScaleX = -1 },
            Children = { _camView, BdCenterLine(), _camGhost, _camThirds, _camSilhouette },
        };
        _camStill = new Image { Stretch = Stretch.UniformToFill, Visibility = Visibility.Collapsed };
        _camNote = new StackPanel { Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        _camCount = BdWhite("", 120, true);
        _camCount.HorizontalAlignment = HorizontalAlignment.Center;
        _camCount.VerticalAlignment = VerticalAlignment.Center;

        // l'anteprima resta 3:4 e si adatta allo spazio: i comandi sotto hanno sempre il loro posto
        var frame = new Grid { Background = Sb(Argb(255, 0x0B, 0x0B, 0x0C)), Width = 360, Height = 480, Children = { _camLive, _camStill, _camNote, _camCount } };
        frame.SizeChanged += (_, e) => frame.Clip = new RectangleGeometry { Rect = new Rect(0, 0, e.NewSize.Width, e.NewSize.Height) };
        var room = new Grid { Children = { frame } };
        room.SizeChanged += (_, e) =>
        {
            var height = Math.Max(120, Math.Min(e.NewSize.Height, e.NewSize.Width * 4 / 3));
            frame.Height = Math.Floor(height);
            frame.Width = Math.Floor(height * 3 / 4);
        };

        _camBackButton = BdGlass(Icons.Of(Ico.Close, White, 18), 40, BdBack, "Chiudi");
        _camBackButton.HorizontalAlignment = HorizontalAlignment.Left;
        _camBackButton.Margin = new Thickness(20, 48, 0, 10);
        _camBar = new StackPanel { Spacing = 14, Margin = new Thickness(16, 14, 16, 20), MinHeight = 126 };

        var page = new Grid { RowDefinitions = { new RowDefinition { Height = GridLength.Auto }, new RowDefinition(), new RowDefinition { Height = GridLength.Auto } } };
        Grid.SetRow(room, 1);
        Grid.SetRow(_camBar, 2);
        page.Children.Add(_camBackButton);
        page.Children.Add(room);
        page.Children.Add(_camBar);
        BdShowLayer("camera", page);
        BdGuides();
        _ = BdStartCamera();
    }

    // ---------------------------------------------------------------- guide

    private static Microsoft.UI.Xaml.Shapes.Line BdGuideLine(double x1, double y1, double x2, double y2, bool dashed) => new()
    {
        X1 = x1, Y1 = y1, X2 = x2, Y2 = y2, Stroke = Sb(Argb(90, 255, 255, 255)), StrokeThickness = 0.8,
        StrokeDashArray = dashed ? new DoubleCollection { 7, 6 } : null,
    };

    /// <summary>Le guide si disegnano su un foglio 300 × 400 che si allarga con l'anteprima.</summary>
    private static Viewbox BdSheet(params UIElement[] shapes)
    {
        var canvas = new Canvas { Width = 300, Height = 400 };
        foreach (var s in shapes) canvas.Children.Add(s);
        return new Viewbox { Child = canvas, Stretch = Stretch.Fill, IsHitTestVisible = false };
    }

    private static Viewbox BdCenterLine() => BdSheet(BdGuideLine(150, 0, 150, 400, true));

    private static Viewbox BdThirds() => BdSheet(
        BdGuideLine(100, 0, 100, 400, false), BdGuideLine(200, 0, 200, 400, false),
        BdGuideLine(0, 400.0 / 3, 300, 400.0 / 3, false), BdGuideLine(0, 800.0 / 3, 300, 800.0 / 3, false));

    /// <summary>Sagoma tratteggiata: testa, spalle, busto, gambe.</summary>
    private static Viewbox BdSilhouette()
    {
        var ink = Sb(Argb(140, 255, 255, 255));
        var head = new Ellipse { Width = 36, Height = 36, Stroke = ink, StrokeThickness = 1.5, StrokeDashArray = new DoubleCollection { 4, 3.4 } };
        Canvas.SetLeft(head, 132);
        Canvas.SetTop(head, 26);
        var shapes = new List<UIElement> { head };
        double[][] strokes =
        {
            new[] { .47, .17, .47, .2, .30, .23, .24, .50 },
            new[] { .53, .17, .53, .2, .70, .23, .76, .50 },
            new[] { .34, .24, .36, .52, .38, .93, .48, .93, .5, .55 },
            new[] { .66, .24, .64, .52, .62, .93, .52, .93, .5, .55 },
        };
        foreach (var s in strokes)
        {
            var points = new PointCollection();
            for (var i = 0; i < s.Length; i += 2) points.Add(new Point(s[i] * 300, s[i + 1] * 400));
            shapes.Add(new Polyline
            {
                Points = points, Stroke = ink, StrokeThickness = 1.5, StrokeDashArray = new DoubleCollection { 4, 3.4 },
                StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, StrokeLineJoin = PenLineJoin.Round,
            });
        }
        return BdSheet(shapes.ToArray());
    }

    private void BdGuides()
    {
        var guide = _r.S.BodyGuide;
        var ghost = _camGhost.Source != null;
        _camGhost.Visibility = guide == 0 && ghost ? Visibility.Visible : Visibility.Collapsed;
        _camSilhouette.Visibility = guide == 1 || guide == 0 && !ghost ? Visibility.Visible : Visibility.Collapsed;
        _camThirds.Visibility = guide == 1 ? Visibility.Visible : Visibility.Collapsed;
    }

    // ---------------------------------------------------------------- comandi

    private void BdCamBar()
    {
        _camBar.Children.Clear();
        if (_camShot != null)
        {
            var stepper = BdStepper(_camKg10, v => _camKg10 = v, true);
            stepper.HorizontalAlignment = HorizontalAlignment.Center;
            var row = HStack(12, BdPill("Rifai", false, BdRetake), BdPill("Salva", true, () => _ = BdSaveShot()));
            row.HorizontalAlignment = HorizontalAlignment.Center;
            _camBar.Children.Add(stepper);
            _camBar.Children.Add(row);
            return;
        }

        var chips = HStack(8);
        string[] guides = { "Sagoma", "Griglia", "Niente" };
        for (var i = 0; i < guides.Length; i++)
        {
            var idx = i;
            chips.Children.Add(BdDarkChip(guides[i], _r.S.BodyGuide == i, () => { _r.S.BodyGuide = idx; _r.S.Save(); BdGuides(); BdCamBar(); }));
        }
        chips.Children.Add(new Border { Width = 12 });
        foreach (var (seconds, name) in new[] { (0, "Subito"), (5, "5 s"), (10, "10 s") })
            chips.Children.Add(BdDarkChip(name, _r.S.BodyTimer == seconds, () => { _r.S.BodyTimer = seconds; _r.S.Save(); BdCamBar(); }));
        chips.HorizontalAlignment = HorizontalAlignment.Center;
        _camBar.Children.Add(HScroll(chips));

        var ready = _cam != null && !_camBusy;
        var face = new Grid
        {
            Width = 78, Height = 78, Opacity = ready ? 1 : 0.35,
            Children =
            {
                new Ellipse { Fill = White },
                new Ellipse { Width = 68, Height = 68, Fill = Sb(Microsoft.UI.Colors.Black) },
                new Ellipse { Width = 62, Height = 62, Fill = White },
            },
        };
        var shutter = Bare(face, () => _ = BdShoot(), "Scatta", 39);
        Bounce(shutter, 1.08f);
        var import = BdPill("Importa", false, () => { CloseBodyLayer(); _ = BdImport(); });
        import.HorizontalAlignment = HorizontalAlignment.Right;
        import.VerticalAlignment = VerticalAlignment.Center;
        FrameworkElement? turn = null;
        if (_camList.Count > 1)
        {
            turn = BdGlass(Icons.Glyph(Tab.Camera, White, 22), 48, BdNextCamera, "Cambia fotocamera");
            turn.HorizontalAlignment = HorizontalAlignment.Left;
            turn.VerticalAlignment = VerticalAlignment.Center;
        }
        var bottom = Cols("*a*", 32, import, shutter, turn);
        bottom.MaxWidth = 520;
        _camBar.Children.Add(bottom);
        // lo scatto parte anche da tastiera
        var target = _cam != null ? shutter : _camBackButton;
        if (target.IsLoaded) target.Focus(FocusState.Programmatic);
        else target.Loaded += (_, _) => target.Focus(FocusState.Programmatic);
    }

    private Button BdDarkChip(string text, bool on, Action click)
    {
        var label = BdWhite(text, 13, on);
        if (on) label.Foreground = Sb(Microsoft.UI.Colors.Black);
        var face = new Border
        {
            Height = 36, CornerRadius = new CornerRadius(18), Padding = new Thickness(14, 0, 14, 0),
            Background = on ? White : Sb(Argb(40, 255, 255, 255)), Child = Center(label),
        };
        var b = Bare(face, click, text, 18);
        Bounce(b);
        return b;
    }

    private void BdNextCamera()
    {
        if (_camBusy || _camList.Count < 2) return;
        var at = _camList.FindIndex(d => d.Id == _r.S.BodyCamId);
        _r.S.BodyCamId = _camList[(Math.Max(0, at) + 1) % _camList.Count].Id;
        _r.S.Save();
        _ = BdStartCamera();
    }

    private void BdNote(string? text, params Button[] buttons)
    {
        _camNote.Children.Clear();
        if (text == null) return;
        var label = BdWhite(text, 16, false, 180);
        label.HorizontalAlignment = HorizontalAlignment.Center;
        label.TextWrapping = TextWrapping.Wrap;
        label.TextAlignment = TextAlignment.Center;
        label.MaxWidth = 260;
        _camNote.Children.Add(label);
        foreach (var b in buttons)
        {
            b.HorizontalAlignment = HorizontalAlignment.Center;
            _camNote.Children.Add(b);
        }
    }

    private Button BdRetry() => BdPill("Riprova", false, () => _ = BdStartCamera());

    // ---------------------------------------------------------------- fotocamera

    private async Task BdStartCamera()
    {
        BdStopCamera();
        var gen = _camGen;
        _camBusy = false;
        _camCount.Text = "";
        BdNote(null);
        BdCamBar();
        MediaCapture? capture = null;
        try
        {
            var found = await DeviceInformation.FindAllAsync(DeviceClass.VideoCapture);
            if (gen != _camGen) return;
            _camList = found.ToList();
            if (_camList.Count == 0) { BdNote("Nessuna fotocamera", BdRetry()); BdCamBar(); return; }
            var device = _camList.FirstOrDefault(d => d.Id == _r.S.BodyCamId) ?? _camList[0];
            capture = new MediaCapture();
            await capture.InitializeAsync(new MediaCaptureInitializationSettings
            {
                VideoDeviceId = device.Id, StreamingCaptureMode = StreamingCaptureMode.Video,
                SharingMode = MediaCaptureSharingMode.ExclusiveControl, MemoryPreference = MediaCaptureMemoryPreference.Cpu,
            });
            if (gen != _camGen) { capture.Dispose(); return; }
            var source = capture.FrameSources.Values
                .Where(s => s.Info.SourceKind == MediaFrameSourceKind.Color)
                .OrderBy(s => s.Info.MediaStreamType == MediaStreamType.VideoPreview ? 0 : 1)
                .FirstOrDefault() ?? throw new InvalidOperationException("La fotocamera non offre un flusso a colori");
            // il formato più alto che resta fluido: la foto 3:4 usa tutta l'altezza
            var best = source.SupportedFormats
                .Where(f => f.VideoFormat.Height <= 2160 && f.FrameRate.Denominator > 0 && f.FrameRate.Numerator / f.FrameRate.Denominator >= 15)
                .OrderByDescending(f => f.VideoFormat.Height).ThenByDescending(f => f.VideoFormat.Width).FirstOrDefault();
            if (best != null) { try { await source.SetFormatAsync(best); } catch (Exception) { /* resta il formato attuale */ } }
            if (gen != _camGen) { capture.Dispose(); return; }

            _cam = capture;
            _camSource = source;
            capture.Failed += (_, e) => DispatcherQueue.TryEnqueue(() =>
            {
                if (gen != _camGen) return;
                BdStopCamera();
                _camBusy = false;
                _camCount.Text = "";
                BdNote("La fotocamera si è interrotta", BdRetry());
                BdCamBar();
            });
            _camMedia = MediaSource.CreateFromMediaFrameSource(source);
            _camPlayer = new MediaPlayer { RealTimePlayback = true, AutoPlay = true, Source = _camMedia };
            _camView.SetMediaPlayer(_camPlayer);
            BdCamBar();
        }
        catch (UnauthorizedAccessException)
        {
            capture?.Dispose();
            if (gen != _camGen) return;
            BdNote("Fotocamera non consentita", BdPill("Consenti", true, () => _ = Launcher.LaunchUriAsync(new Uri("ms-settings:privacy-webcam"))), BdRetry());
        }
        catch (Exception e)
        {
            Crash.Log(e, "Fotocamera");
            capture?.Dispose();
            if (gen != _camGen) return;
            BdStopCamera();
            BdNote("Fotocamera occupata o non disponibile", BdRetry());
            BdCamBar();
        }
    }

    private void BdStopCamera()
    {
        _camGen++;
        try
        {
            _camView?.SetMediaPlayer(null);
            _camPlayer?.Dispose();
            _camMedia?.Dispose();
            _cam?.Dispose();
        }
        catch (Exception e) { Crash.Log(e, "Chiusura fotocamera"); }
        _camPlayer = null;
        _camMedia = null;
        _camSource = null;
        _cam = null;
    }

    private async Task BdShoot()
    {
        if (_camBusy || _cam == null || _camShot != null) return;
        var gen = _camGen;
        _camBusy = true;
        BdNote(null);
        BdCamBar();
        try
        {
            for (var i = _r.S.BodyTimer; i > 0; i--)
            {
                _camCount.Text = i.ToString(It);
                Enter(_camCount, 0, 14, 300);
                await Task.Delay(1000);
                if (gen != _camGen) return;
            }
            _camCount.Text = "";
            var shot = await BdGrab(_cam!, _camSource!);
            var still = new SoftwareBitmapSource();
            await still.SetBitmapAsync(shot);
            if (gen != _camGen) return;
            _camShot = shot;
            _camStill.Source = still;
            _camStill.Visibility = Visibility.Visible;
            _camLive.Visibility = Visibility.Collapsed;
            BdBackIcon(Ico.Back);
        }
        catch (Exception e)
        {
            Crash.Log(e, "Scatto");
            if (gen != _camGen) return;
            _camCount.Text = "";
            BdNote("Scatto non riuscito");
        }
        _camBusy = false;
        BdCamBar();
    }

    /// <summary>Un fotogramma dallo stesso flusso dell'anteprima, così l'inquadratura è identica; se non arriva, uno scatto normale.</summary>
    private static async Task<SoftwareBitmap> BdGrab(MediaCapture capture, MediaFrameSource source)
    {
        try
        {
            using var reader = await capture.CreateFrameReaderAsync(source, MediaEncodingSubtypes.Bgra8);
            var arrived = new TaskCompletionSource<SoftwareBitmap>(TaskCreationOptions.RunContinuationsAsynchronously);
            reader.FrameArrived += (sender, _) =>
            {
                try
                {
                    using var f = sender.TryAcquireLatestFrame();
                    if (f?.VideoMediaFrame?.SoftwareBitmap is { } bitmap) arrived.TrySetResult(SoftwareBitmap.Copy(bitmap));
                }
                catch (Exception e) { arrived.TrySetException(e); }
            };
            if (await reader.StartAsync() == MediaFrameReaderStartStatus.Success)
            {
                var first = await Task.WhenAny(arrived.Task, Task.Delay(3000));
                await reader.StopAsync();
                if (first == arrived.Task)
                {
                    using var raw = await arrived.Task;
                    return await Task.Run(() => BodyPhotos.Frame(raw));
                }
            }
        }
        catch (Exception e) { Crash.Log(e, "Fotogramma"); }
        using var stream = new InMemoryRandomAccessStream();
        await capture.CapturePhotoToStreamAsync(ImageEncodingProperties.CreateJpeg(), stream);
        stream.Seek(0);
        return await BodyPhotos.Read(stream);
    }

    private void BdBackIcon(Ico icon)
    {
        if (_camBackButton.Content is Border { Child: not null } face) face.Child = Center(Icons.Of(icon, White, 18));
    }

    private void BdRetake()
    {
        _camShot = null;
        _camStill.Source = null;
        _camStill.Visibility = Visibility.Collapsed;
        _camLive.Visibility = Visibility.Visible;
        BdBackIcon(Ico.Close);
        BdCamBar();
    }

    private async Task BdSaveShot()
    {
        if (_camBusy || _camShot is not { } shot) return;
        _camBusy = true;
        var today = BodyStore.DayKey();
        var kg10 = _camKg10;
        if (!await BodyPhotos.Save(today, shot))
        {
            _camBusy = false;
            if (_bdOver == "camera") BdNote("Foto non salvata");
            return;
        }
        _r.Bd.SetWeight(today, kg10);
        _camBusy = false;
        CloseBodyLayer();
        BdPhotosChanged();
    }
}
