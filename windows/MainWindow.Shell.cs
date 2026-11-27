using System.Numerics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.System;

namespace Ponte;

/// <summary>
/// La cornice di Android: barra laterale con marchio e voci a pillola, barra di vetro in basso con la goccia che scivola,
/// e sotto "Home" la pillola dei dispositivi che si allarga nella scheda.
/// </summary>
public sealed partial class MainWindow
{
    private readonly List<(Button Button, Viewbox Icon)> _nav = new();
    private readonly List<Viewbox> _barIcons = new();
    private Border? _barPill;
    private int _barAt;

    private void InitNavigation()
    {
        BuildNav();
        BuildBottom();
        BuildMorph();
    }

    private void InitShell()
    {
        InitNavigation();
        // un clic fuori dalla scheda dei dispositivi la richiude
        Root.AddHandler(UIElement.PointerPressedEvent, new PointerEventHandler((_, e) =>
        {
            if (!_devOpen) return;
            for (var o = e.OriginalSource as DependencyObject; o != null; o = VisualTreeHelper.GetParent(o))
                if (ReferenceEquals(o, _morph)) return;
            SetDevOpen(false);
        }), true);
        var esc = new KeyboardAccelerator { Key = VirtualKey.Escape };
        esc.Invoked += (_, a) =>
        {
            if (_bdOver != null) { BdBack(); a.Handled = true; }
            else if (_panel != null) { ClosePanel(); a.Handled = true; }
            else if (_devOpen) { SetDevOpen(false); a.Handled = true; }
        };
        Root.KeyboardAccelerators.Add(esc);
        Root.KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden;
    }

    // ---------------------------------------------------------------- barra laterale

    private void BuildNav()
    {
        Side.Children.Clear();
        _nav.Clear();
        var word = T("Relay", 25, "LabelPrimary", true);
        word.VerticalAlignment = VerticalAlignment.Center;
        var brand = HStack(12, RelayMark(32), word);
        brand.Margin = new Thickness(12, 16, 12, 24);
        Side.Children.Add(brand);
        for (var t = 0; t < Tabs.Length; t++)
        {
            var idx = t;
            var icon = Icons.Glyph(TabIcons[t], B("LabelSecondary"), 22);
            var label = new TextBlock { Text = Tabs[t], VerticalAlignment = VerticalAlignment.Center };
            var b = new Button { Style = St("NavItem"), Content = HStack(12, icon, label) };
            b.Click += (_, _) => Select(idx);
            _nav.Add((b, icon));
            Side.Children.Add(b);
        }
    }

    // ---------------------------------------------------------------- barra in basso

    private void BuildBottom()
    {
        Bottom.Children.Clear();
        _barIcons.Clear();
        Bottom.Children.Add(new Border
        {
            CornerRadius = new CornerRadius(33), Background = Glass(C("BgCanvas"), 0.55), BorderBrush = Hairline, BorderThickness = new Thickness(1),
        });
        var cells = new Grid { Padding = new Thickness(6, 5, 6, 5) };
        for (var t = 0; t < Tabs.Length; t++) cells.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _barPill = new Border
        {
            CornerRadius = new CornerRadius(28), Background = Drop(Alpha(C("AccentDefault"), 0.12)), BorderBrush = Hairline, BorderThickness = new Thickness(1),
        };
        _barAt = _tab;
        Grid.SetColumn(_barPill, _tab);
        cells.Children.Add(_barPill);
        for (var t = 0; t < Tabs.Length; t++)
        {
            var idx = t;
            var icon = Icons.Glyph(TabIcons[t], B(t == _tab ? "AccentDefault" : "LabelSecondary"), 28);
            var b = Bare(icon, () => Select(idx), Tabs[t], 28);
            b.HorizontalAlignment = HorizontalAlignment.Stretch;
            b.VerticalAlignment = VerticalAlignment.Stretch;
            b.VerticalContentAlignment = VerticalAlignment.Center;
            b.HorizontalContentAlignment = HorizontalAlignment.Center;
            ToolTipService.SetToolTip(b, Tabs[t]);
            Bounce(b, 1.12f);
            Grid.SetColumn(b, t);
            cells.Children.Add(b);
            _barIcons.Add(icon);
        }
        Bottom.Children.Add(cells);
    }

    /// <summary>La voce attiva: pillola azzurra a lato, goccia che scivola in basso.</summary>
    private void MarkNav(bool animate)
    {
        for (var t = 0; t < _nav.Count; t++)
        {
            _nav[t].Button.Style = St(t == _tab ? "NavItemSelected" : "NavItem");
            Icons.Tint(_nav[t].Icon, B(t == _tab ? "AccentOnSubtle" : "LabelSecondary"));
        }
        for (var t = 0; t < _barIcons.Count; t++) Icons.Tint(_barIcons[t], B(t == _tab ? "AccentDefault" : "LabelSecondary"));
        if (_barPill == null) return;
        var from = _barAt;
        _barAt = _tab;
        Grid.SetColumn(_barPill, _tab);
        var width = (float)_barPill.ActualWidth;
        if (!animate || from == _tab || width < 1) return;
        try
        {
            var v = ElementCompositionPreview.GetElementVisual(_barPill);
            ElementCompositionPreview.SetIsTranslationEnabled(_barPill, true);
            var a = v.Compositor.CreateVector3KeyFrameAnimation();
            a.InsertKeyFrame(0f, new Vector3((from - _tab) * width, 0, 0));
            a.InsertKeyFrame(1f, Vector3.Zero, v.Compositor.CreateCubicBezierEasingFunction(new Vector2(0.3f, 1.25f), new Vector2(0.5f, 1f)));
            a.Duration = TimeSpan.FromMilliseconds(420);
            v.StartAnimation("Translation", a);
        }
        catch (Exception e) { Crash.Log(e, "Barra in basso"); }
    }

    // ---------------------------------------------------------------- dispositivi

    private Border? _morph;
    private TextBlock _morphText = new();
    private Border _morphPill = new();
    private ScrollViewer _morphCard = new();
    private bool _devOpen;
    private string? _devPick;
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _devTimer;

    private void BuildMorph()
    {
        MorphLayer.Children.Clear();
        _devOpen = false;
        _morphText = Center(T("", 13, "LabelSecondary"));
        _morphPill = new Border { Height = 30, VerticalAlignment = VerticalAlignment.Top, Child = _morphText };
        _morphCard = new ScrollViewer
        {
            HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top, Opacity = 0, Visibility = Visibility.Collapsed,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollMode = ScrollMode.Disabled,
        };
        _morph = new Border
        {
            Width = 206, Height = 30, CornerRadius = new CornerRadius(15), HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
            Background = B("BgAcrylic"), BorderBrush = Hairline, BorderThickness = new Thickness(1),
            Child = new Grid { Children = { _morphCard, _morphPill } },
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(_morph, "Dispositivi");
        _morph.Tapped += (_, _) => { if (!_devOpen) SetDevOpen(true); };
        // finché il puntatore è sopra, la scheda resta aperta
        _morph.PointerEntered += (_, _) => _devTimer?.Stop();
        _morph.PointerExited += (_, _) => { if (_devOpen) _devTimer?.Start(); };
        MorphLayer.Children.Add(_morph);
        UpdateMorph();
    }

    private void UpdateMorph()
    {
        if (_morph == null) return;
        _morph.Visibility = _tab == 0 ? Visibility.Visible : Visibility.Collapsed;
        var n = _r.Devices.Count;
        _morphText.Inlines.Clear();
        _morphText.Inlines.Add(new Run { Text = n == 0 ? "Solo questo dispositivo" : "Collegato a " });
        if (n > 0) _morphText.Inlines.Add(new Run { Text = n == 1 ? "1 dispositivo" : $"{n} dispositivi", Foreground = B("AccentDefault"), FontFamily = Font("FontSansMedium") });
        if (_devOpen) { FillDevices(); _morph.Height = MorphHeight(); }
    }

    /// <summary>Posizione della pillola: allineata alla colonna del titolo; aperta, copre tutta la colonna.</summary>
    private void PlaceMorph()
    {
        if (_morph == null) return;
        var avail = Root.ActualWidth - (Wide ? 240 : 0);
        var w = _hosts[0].Width;
        if (avail <= 0 || double.IsNaN(w)) return;
        var left = Math.Max(0, (avail - w) / 2);
        _morph.Margin = new Thickness(_devOpen ? left : left + 8, 90, 0, 0);
        _morphCard.Width = w - 2;
        if (!_devOpen) return;
        _morph.Width = w;
        _morph.Height = MorphHeight();
    }

    private double MorphHeight()
    {
        if (_morphCard.Content is not FrameworkElement card) return 120;
        card.Measure(new Windows.Foundation.Size(_morphCard.Width, double.PositiveInfinity));
        // oltre il 60% della finestra la scheda scorre
        return Math.Clamp(card.DesiredSize.Height + 2, 120, Math.Max(160, Root.ActualHeight * 0.6));
    }

    private void SetDevOpen(bool open)
    {
        if (_morph == null || open == _devOpen) return;
        _devOpen = open;
        if (_devTimer == null)
        {
            _devTimer = DispatcherQueue.CreateTimer();
            _devTimer.Interval = TimeSpan.FromSeconds(7);
            _devTimer.IsRepeating = false;
            _devTimer.Tick += (_, _) => SetDevOpen(false);
        }
        _devTimer.Stop();
        var w0 = _morph.Width;
        var h0 = _morph.Height;
        var l0 = _morph.Margin.Left;
        var avail = Root.ActualWidth - (Wide ? 240 : 0);
        var col = double.IsNaN(_hosts[0].Width) ? 600 : _hosts[0].Width;
        var left = Math.Max(0, (avail - col) / 2);
        if (open)
        {
            _devPick = null;
            _morphCard.Width = col - 2;
            FillDevices();
            _morphCard.Visibility = Visibility.Visible;
            _devTimer.Start();
        }
        else _morphPill.Visibility = Visibility.Visible;
        var w1 = open ? col : 206;
        var h1 = open ? MorphHeight() : 30;
        var l1 = open ? left : left + 8;
        Tween(360, k =>
        {
            if (_devOpen != open) return;
            _morph.Width = w0 + (w1 - w0) * k;
            _morph.Height = h0 + (h1 - h0) * k;
            _morph.Margin = new Thickness(l0 + (l1 - l0) * k, 90, 0, 0);
            _morph.CornerRadius = new CornerRadius(open ? 15 + 13 * k : 28 - 13 * k);
            var shown = open ? k : 1 - k;
            _morphPill.Opacity = Math.Clamp(1 - shown * 2, 0, 1);
            _morphCard.Opacity = Math.Clamp((shown - 0.45) * 2, 0, 1);
        }, () =>
        {
            if (_devOpen != open) return;
            if (open) _morphPill.Visibility = Visibility.Collapsed;
            else _morphCard.Visibility = Visibility.Collapsed;
        });
    }

    private void FillDevices() => _morphCard.Content = DevicesCard();

    /// <summary>
    /// Tutti i dispositivi: in alto stato del server e del collegamento diretto, poi le bolle in fila.
    /// Toccandone una, dal bordo di sotto esce il cassetto con i dettagli.
    /// </summary>
    private StackPanel DevicesCard()
    {
        var devices = _r.Devices;
        var near = devices.Count(d => d.Via != "server");
        var hub = _r.HubState;
        var all = new List<Device> { new(_r.DeviceId, _r.S.Name, "windows", new HashSet<string>(), "self") };
        all.AddRange(devices);

        var title = T("Dispositivi", 16, "LabelPrimary", true);
        var count = T((devices.Count + 1).ToString(), 13, "LabelTertiary");
        title.VerticalAlignment = count.VerticalAlignment = VerticalAlignment.Center;
        var head = Cols("aa*aa", 8, title, count, null,
            StatusBubble(hub == "Connesso" ? "Server" : "Server off", hub == "Connesso" ? "Positive" : hub == "Non configurato" ? "LabelDisabled" : "Caution", hub == "Connesso"),
            StatusBubble(near > 0 ? $"P2P {near}" : "P2P", near > 0 ? "Positive" : "LabelDisabled", near > 0));
        head.Margin = new Thickness(4, 0, 4, 0);

        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var d in all)
        {
            var dev = d;
            var sel = _devPick == d.Id;
            var bubble = Bubble(DeviceFace(d.Id, 56, 5, Icons.Glyph(Tab.Devices, B(sel ? "AccentDefault" : "LabelTertiary"), 24)), 56, sel ? Alpha(C("AccentDefault"), 0.22) : null);
            var face = new Grid { Width = 56, Height = 56, HorizontalAlignment = HorizontalAlignment.Center, Children = { bubble } };
            if (d.Id != _r.DeviceId)
                face.Children.Add(new Ellipse
                {
                    Width = 10, Height = 10, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 3, 3),
                    Fill = B(d.Via == "lan" ? "Positive" : d.Via == "bt" ? "AccentDefault" : "LabelDisabled"),
                });
            var name = T(d.Id == _r.DeviceId ? "Questo" : d.Name, 13, sel ? "LabelPrimary" : "LabelSecondary");
            name.HorizontalAlignment = HorizontalAlignment.Center;
            var cell = Bare(VStack(4, face, name), () =>
            {
                _devPick = _devPick == dev.Id ? null : dev.Id;
                FillDevices();
                if (_morph != null) _morph.Height = MorphHeight();
            }, d.Id == _r.DeviceId ? "Questo PC" : d.Name, 20);
            cell.Width = 76;
            Bounce(cell, 1.06f);
            row.Children.Add(cell);
        }

        var top = VStack(8, head, HScroll(row));
        top.Padding = new Thickness(12);
        var card = VStack(0, top);
        if (all.FirstOrDefault(x => x.Id == _devPick) is { } open)
        {
            var me = open.Id == _r.DeviceId;
            var info = VStack(8, Row("Collegamento", me ? "Questo dispositivo" : open.Via switch { "lan" => "Diretto, rete locale o privata", "bt" => "Bluetooth", _ => "Tramite il server" }));
            if (me) info.Children.Add(Row("Identificativo", _r.DeviceId[..Math.Min(13, _r.DeviceId.Length)] + "…"));
            else
            {
                var what = new List<string> { "appunti", "file" };
                if (open.Caps.Contains("audio-src")) what.Add("sorgente audio");
                if (open.Caps.Contains("audio-sink")) what.Add("riceve audio");
                if (open.Caps.Contains("notif-src")) what.Add("notifiche");
                info.Children.Add(Row("Funzioni", string.Join(" · ", what)));
            }
            var has = DevicePics.Get(open.Id) != null;
            var pick = Btn(has ? "Cambia immagine" : "Scegli immagine", () => _ = PickDevicePicAsync(open.Id), "SecondaryButton");
            pick.HorizontalAlignment = HorizontalAlignment.Stretch;
            var drop = has ? Btn("Rimuovi", () => { DevicePics.Remove(open.Id); DevicePicChanged(); }) : null;
            info.Children.Add(Cols("*a", 8, pick, drop));
            var drawer = new Border { Background = Sb(Alpha(C("LabelPrimary"), 0.07)), Padding = new Thickness(16), Child = info };
            card.Children.Add(drawer);
        }
        return card;
    }

    /// <summary>Stato semplice: pallino colorato (che pulsa se acceso) e nome.</summary>
    private Border StatusBubble(string label, string tone, bool pulse)
    {
        var dot = new Ellipse { Width = 8, Height = 8, Fill = B(tone), VerticalAlignment = VerticalAlignment.Center };
        if (pulse) dot.Loaded += (_, _) => Pulse(dot);
        var t = T(label, 13, "LabelSecondary");
        t.VerticalAlignment = VerticalAlignment.Center;
        return new Border
        {
            Height = 28, CornerRadius = new CornerRadius(14), Padding = new Thickness(12, 0, 12, 0),
            Background = Sb(Alpha(C("LabelPrimary"), 0.07)), Child = HStack(6, dot, t),
        };
    }

    private static void Pulse(UIElement el)
    {
        try
        {
            var v = ElementCompositionPreview.GetElementVisual(el);
            var a = v.Compositor.CreateScalarKeyFrameAnimation();
            a.InsertKeyFrame(0f, 1f);
            a.InsertKeyFrame(0.5f, 0.35f);
            a.InsertKeyFrame(1f, 1f);
            a.Duration = TimeSpan.FromMilliseconds(2200);
            a.IterationBehavior = Microsoft.UI.Composition.AnimationIterationBehavior.Forever;
            v.StartAnimation("Opacity", a);
        }
        catch (Exception) { }
    }
}
