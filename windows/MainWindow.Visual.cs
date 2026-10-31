using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Shapes;
using Microsoft.Web.WebView2.Core;

namespace Ponte;

/// <summary>Gradiente ASCII, animazioni e configurazione iniziale.</summary>
public sealed partial class MainWindow
{
    private bool Dark => Root.ActualTheme == ElementTheme.Dark;

    // ---------------------------------------------------------------- gradiente ASCII

    /// <summary>Carica Assets/Web/gradient.html (lo stesso dell'app Android) in un WebView2 trasparente.</summary>
    private async Task InitWebAsync(WebView2 web, string query)
    {
        try
        {
            await web.EnsureCoreWebView2Async();
            var core = web.CoreWebView2;
            core.SetVirtualHostNameToFolderMapping("ponte.app", System.IO.Path.Combine(AppContext.BaseDirectory, "Assets", "Web"),
                CoreWebView2HostResourceAccessKind.Allow);
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.IsZoomControlEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            web.Source = new Uri($"https://ponte.app/gradient.html?{query}&light={(Dark ? 0 : 1)}");
        }
        catch (Exception)
        {
            // Senza il runtime WebView2 resta lo sfondo di Canone: tutto il resto funziona uguale.
            web.Visibility = Visibility.Collapsed;
        }
    }

    private void OnThemeChanged()
    {
        UpdateFades();
        var js = $"window.ponteSet && ponteSet({{ lightMode: {(Dark ? "false" : "true")} }})";
        foreach (var w in new[] { HeaderWeb, OnboardWeb })
            if (w.CoreWebView2 != null) _ = w.CoreWebView2.ExecuteScriptAsync(js);
    }

    /// <summary>Sfumature dal gradiente al colore di fondo, così testata e pagina si fondono.</summary>
    private void UpdateFades()
    {
        var bg = (ContentArea.Background as SolidColorBrush)?.Color ?? Microsoft.UI.Colors.Black;
        Brush Fade(double from) => new LinearGradientBrush
        {
            StartPoint = new Windows.Foundation.Point(0, 0),
            EndPoint = new Windows.Foundation.Point(0, 1),
            GradientStops =
            {
                new GradientStop { Color = Windows.UI.Color.FromArgb(0, bg.R, bg.G, bg.B), Offset = from },
                new GradientStop { Color = bg, Offset = 1 },
            },
        };
        HeaderFade.Background = Fade(0.5);
        OnboardFade.Background = Fade(0.35);
    }

    // ---------------------------------------------------------------- animazioni

    /// <summary>Entrata morbida: l'elemento compare scivolando di poco nella sua posizione.</summary>
    private static void Enter(UIElement el, double dx, double dy, int ms = 360, int delay = 0)
    {
        var tt = new TranslateTransform { X = dx, Y = dy };
        el.RenderTransform = tt;
        el.Opacity = 0;
        var sb = new Storyboard();
        var ease = new ExponentialEase { EasingMode = EasingMode.EaseOut, Exponent = 5 };
        void Add(DependencyObject target, string prop, double from)
        {
            var a = new DoubleAnimation
            {
                From = from, To = prop == "Opacity" ? 1 : 0,
                Duration = TimeSpan.FromMilliseconds(ms), BeginTime = TimeSpan.FromMilliseconds(delay), EasingFunction = ease,
            };
            Storyboard.SetTarget(a, target);
            Storyboard.SetTargetProperty(a, prop);
            sb.Children.Add(a);
        }
        Add(el, "Opacity", 0);
        Add(tt, "X", dx);
        Add(tt, "Y", dy);
        sb.Begin();
    }

    private static void FadeTo(UIElement el, double to, int ms)
    {
        var a = new DoubleAnimation { To = to, Duration = TimeSpan.FromMilliseconds(ms), EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut } };
        Storyboard.SetTarget(a, el);
        Storyboard.SetTargetProperty(a, "Opacity");
        var sb = new Storyboard();
        sb.Children.Add(a);
        sb.Begin();
    }

    // ---------------------------------------------------------------- configurazione iniziale

    private int _step;
    private bool _onboardWebReady;

    private void ShowOnboarding()
    {
        Onboard.Visibility = Visibility.Visible;
        Onboard.Opacity = 1;
        if (!_onboardWebReady)
        {
            _onboardWebReady = true;
            _ = InitWebAsync(OnboardWeb, "shape=horizon&cell=16");
        }
        _step = 0;
        RenderStep(1);
    }

    private void Go(int step)
    {
        var dir = step >= _step ? 1 : -1;
        _step = step;
        RenderStep(dir);
    }

    private void RenderStep(int dir)
    {
        OnboardHost.Children.Clear();
        // Nei passi con molto testo il gradiente si attenua.
        FadeTo(OnboardWeb, _step is 0 or 3 ? 1 : 0.35, 700);
        var items = _step switch
        {
            0 => StepWelcome(),
            1 => StepConnect(),
            2 => StepOptions(),
            _ => StepReady(),
        };
        for (var i = 0; i < items.Count; i++)
        {
            OnboardHost.Children.Add(items[i]);
            Enter(items[i], 32 * dir, 18, 620, 80 + i * 90);
        }
        OnboardDots.Children.Clear();
        for (var i = 0; i < 4; i++)
        {
            OnboardDots.Children.Add(new Border
            {
                Height = 8, Width = i == _step ? 24 : 8, CornerRadius = new CornerRadius(4),
                Background = i == _step ? (Brush)Application.Current.Resources["BrandGradient"] : (Brush)Application.Current.Resources["LineStrong"],
            });
        }
    }

    private List<UIElement> StepWelcome()
    {
        var title = Text("Ponte", "Display");
        title.FontSize = 64;
        title.LineHeight = 68;
        return new List<UIElement>
        {
            title,
            Text("I tuoi dispositivi, un solo spazio.", "Title3"),
            Text("Appunti, file, screenshot, notifiche e cuffie passano da un dispositivo all'altro senza che tu ci pensi.", "BodyText"),
            Btn("Inizia", () => Go(1), "PrimaryButton"),
        };
    }

    private List<UIElement> StepConnect()
    {
        var s = _r.S;
        var name = new TextBox { Text = s.Name, PlaceholderText = "PC della scrivania" };
        var hub = new TextBox { Text = s.HubUrl, PlaceholderText = "ponte.esempio.it", FontFamily = (FontFamily)Application.Current.Resources["FontMono"] };
        var token = new PasswordBox { Password = s.Token, PlaceholderText = "Lo stesso del server" };
        var title = Text("Collega i tuoi dispositivi", "Display");
        title.FontSize = 31;
        return new List<UIElement>
        {
            title,
            Text("Stesso server e stesso token su tutti i dispositivi. Senza server funzionano comunque rete locale e Bluetooth.", "BodyText"),
            Card(Field("Nome di questo PC", name), Field("Indirizzo del server", hub), Field("Token", token)),
            Btn("Continua", () =>
            {
                s.Name = name.Text.Trim().Length > 0 ? name.Text.Trim() : Environment.MachineName;
                s.HubUrl = hub.Text.Trim();
                s.Token = token.Password.Trim();
                s.Save();
                _r.Reconfigure();
                Go(2);
            }, "PrimaryButton"),
            Btn("Indietro", () => Go(0)),
        };
    }

    private List<UIElement> StepOptions()
    {
        var s = _r.S;
        var title = Text("Come vuoi usarlo", "Display");
        title.FontSize = 31;
        return new List<UIElement>
        {
            title,
            Card(
                Toggle("Avvia con Windows", "Ponte parte in background all'accesso", GetAutostart(), SetAutostart),
                Divider(),
                Toggle("Screenshot automatici", "Ogni nuovo screenshot va nell'archivio di tutti", s.Screenshots, on => { s.Screenshots = on; Apply(); }),
                Divider(),
                Toggle("Notifiche del telefono", "Le vedi qui come notifiche di Windows", s.ShowPhoneNotifications, on => { s.ShowPhoneNotifications = on; Apply(); })),
            Text("Quando Windows chiede del firewall, consenti Ponte anche sulle reti pubbliche: serve ai collegamenti diretti. " +
                 "Nello Strumento di cattura attiva il salvataggio automatico degli screenshot.", "FootnoteTertiary"),
            Btn("Continua", () => Go(3), "PrimaryButton"),
            Btn("Indietro", () => Go(1)),
        };
    }

    private List<UIElement> StepReady()
    {
        var check = new Grid { Width = 96, Height = 96, HorizontalAlignment = HorizontalAlignment.Left };
        check.Children.Add(new Ellipse { Fill = (Brush)Application.Current.Resources["BrandGradient"] });
        check.Children.Add(new TextBlock
        {
            Text = "✓", FontSize = 48, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        });
        return new List<UIElement>
        {
            check,
            Text("Tutto pronto", "Display"),
            Text("Copia qualcosa qui e incollalo sul telefono. Il resto lo fa Ponte.", "BodyText"),
            Btn("Apri Ponte", FinishOnboarding, "PrimaryButton"),
        };
    }

    private void FinishOnboarding()
    {
        _r.S.Onboarded = true;
        _r.S.Save();
        FadeTo(Onboard, 0, 420);
        DispatcherQueue.TryEnqueue(async () =>
        {
            await Task.Delay(450);
            Onboard.Visibility = Visibility.Collapsed;
            Select(_tab);
        });
    }
}
