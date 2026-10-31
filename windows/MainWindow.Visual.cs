using System.Numerics;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Hosting;
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
        Brush Fade(double from, double to, bool horizontal) => new LinearGradientBrush
        {
            StartPoint = new Windows.Foundation.Point(0, 0),
            EndPoint = horizontal ? new Windows.Foundation.Point(1, 0) : new Windows.Foundation.Point(0, 1),
            GradientStops =
            {
                new GradientStop { Color = horizontal ? bg : Windows.UI.Color.FromArgb(0, bg.R, bg.G, bg.B), Offset = from },
                new GradientStop { Color = horizontal ? Windows.UI.Color.FromArgb(0, bg.R, bg.G, bg.B) : bg, Offset = to },
            },
        };
        HeaderFade.Background = Fade(0.45, 1, false);
        // Configurazione iniziale: testo a sinistra sul fondo pieno, fibre libere a destra.
        OnboardFade.Background = Fade(0.36, 0.75, true);
    }

    // ---------------------------------------------------------------- animazioni

    // Una sola curva per tutto: parte veloce e si posa piano (decelerazione enfatizzata), come un oggetto che frena.
    private static CompositionEasingFunction Ease(Compositor c) =>
        c.CreateCubicBezierEasingFunction(new Vector2(0.2f, 0f), new Vector2(0f, 1f));

    /// <summary>
    /// Entrata: l'elemento arriva da pochi pixel di distanza e si posa. Animazione sul compositore (fuori dal thread della UI,
    /// quindi niente scatti) e valori iniziali applicati subito, prima di un eventuale ritardo.
    /// </summary>
    private static void Enter(UIElement el, double dx, double dy, int ms = 340, int delay = 0)
    {
        var v = ElementCompositionPreview.GetElementVisual(el);
        var c = v.Compositor;
        ElementCompositionPreview.SetIsTranslationEnabled(el, true);
        el.Opacity = 1;
        var t = c.CreateVector3KeyFrameAnimation();
        t.InsertKeyFrame(0f, new Vector3((float)dx, (float)dy, 0f));
        t.InsertKeyFrame(1f, Vector3.Zero, Ease(c));
        t.Duration = TimeSpan.FromMilliseconds(ms);
        var o = c.CreateScalarKeyFrameAnimation();
        o.InsertKeyFrame(0f, 0f);
        o.InsertKeyFrame(1f, 1f, c.CreateCubicBezierEasingFunction(new Vector2(0.3f, 0f), new Vector2(0.4f, 1f)));
        o.Duration = TimeSpan.FromMilliseconds(ms * 0.7);
        if (delay > 0)
        {
            t.DelayTime = o.DelayTime = TimeSpan.FromMilliseconds(delay);
            t.DelayBehavior = o.DelayBehavior = AnimationDelayBehavior.SetInitialValueBeforeDelay;
        }
        v.StartAnimation("Translation", t);
        v.StartAnimation("Opacity", o);
    }

    /// <summary>Indicatore della barra laterale: una molla smorzata, così se cambi voce a metà corsa riparte dalla velocità che ha.</summary>
    private void MoveIndicator(int index, bool animate)
    {
        const float pitch = 42f; // voce 40 + spaziatura 2
        var v = ElementCompositionPreview.GetElementVisual(NavIndicator);
        var c = v.Compositor;
        ElementCompositionPreview.SetIsTranslationEnabled(NavIndicator, true);
        var target = new Vector3(0f, index * pitch, 0f);
        if (!animate)
        {
            var snap = c.CreateVector3KeyFrameAnimation();
            snap.InsertKeyFrame(1f, target);
            snap.Duration = TimeSpan.FromMilliseconds(1);
            v.StartAnimation("Translation", snap);
            return;
        }
        var spring = c.CreateSpringVector3Animation();
        spring.FinalValue = target;
        spring.DampingRatio = 0.78f;
        spring.Period = TimeSpan.FromMilliseconds(48);
        v.StartAnimation("Translation", spring);
    }

    private static void FadeTo(UIElement el, double to, int ms)
    {
        var a = new DoubleAnimation { To = to, Duration = TimeSpan.FromMilliseconds(ms), EasingFunction = new ExponentialEase { EasingMode = EasingMode.EaseOut, Exponent = 4 } };
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
            _ = InitWebAsync(OnboardWeb, "shape=hero");
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
            Enter(items[i], 16 * dir, 0, 420, i * 45);
        }
        OnboardDots.Children.Clear();
        for (var i = 0; i < 4; i++)
        {
            OnboardDots.Children.Add(new Border
            {
                Height = 3, Width = 28, CornerRadius = new CornerRadius(1.5),
                Background = i <= _step ? (Brush)Application.Current.Resources["AccentDefault"] : (Brush)Application.Current.Resources["LineStrong"],
            });
        }
    }

    private List<UIElement> StepWelcome()
    {
        var title = Text("Ponte", "Display");
        title.FontSize = 48;
        title.LineHeight = 52;
        return new List<UIElement>
        {
            title,
            Text("I tuoi dispositivi, un solo spazio.", "SectionTitle"),
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
        title.FontSize = 30;
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
        title.FontSize = 30;
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
        var check = new Grid { Width = 48, Height = 48, HorizontalAlignment = HorizontalAlignment.Left };
        check.Children.Add(new Ellipse { Fill = (Brush)Application.Current.Resources["AccentDefault"] });
        check.Children.Add(new FontIcon
        {
            Glyph = "\uE73E", FontSize = 20, Foreground = (Brush)Application.Current.Resources["AccentOnAccent"],
            FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets"),
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
