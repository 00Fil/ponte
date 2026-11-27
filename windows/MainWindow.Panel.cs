using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace Ponte;

/// <summary>
/// L'app che "vola" fuori da un riquadro tenuto premuto: la pagina si sfoca e al centro compare una scheda con i comandi rapidi.
/// Anche i pezzi dell'audio (dispositivi che suonano, cuffie) stanno qui: li usano il pannello e le Impostazioni.
/// </summary>
public sealed partial class MainWindow
{
    private string? _panel;
    private Border? _panelCard;
    private readonly List<Action> _panelTicks = new();

    private void OpenPanel(string kind)
    {
        if (_panel == kind) return;
        _panel = kind;
        SetDevOpen(false);
        PanelLayer.Children.Clear();
        var black = Argb(255, 0, 0, 0);
        var scrim = new Border { Background = Glass(black, 0.22) };
        scrim.Tapped += (_, _) => ClosePanel();
        _panelCard = new Border
        {
            MaxWidth = 420, Margin = new Thickness(24), HorizontalAlignment = HorizontalAlignment.Stretch, VerticalAlignment = VerticalAlignment.Center,
            CornerRadius = new CornerRadius(28), Background = B("BgAcrylic"), BorderBrush = B("TileEdge"), BorderThickness = new Thickness(1),
        };
        PanelLayer.Children.Add(scrim);
        PanelLayer.Children.Add(_panelCard);
        PanelLayer.Visibility = Visibility.Visible;
        PanelLayer.IsHitTestVisible = true;
        RenderPanel();
        PanelLayer.Opacity = 0;
        FadeTo(PanelLayer, 1, 220);
        var card = _panelCard;
        card.Loaded += (_, _) => Pop(card, 0.82f, 360);
    }

    private async void ClosePanel()
    {
        if (_panel == null) return;
        _panel = null;
        _panelTicks.Clear();
        PanelLayer.IsHitTestVisible = false;
        FadeTo(PanelLayer, 0, 160);
        await Task.Delay(180);
        if (_panel != null) return;
        PanelLayer.Visibility = Visibility.Collapsed;
        PanelLayer.Children.Clear();
        _panelCard = null;
    }

    private void RenderPanel()
    {
        if (_panelCard == null || _panel == null) return;
        _panelTicks.Clear();
        var body = new StackPanel { Spacing = 16, Padding = new Thickness(24) };
        void Title(string text) => body.Children.Add(Semi(text, 25));
        switch (_panel)
        {
            case "agenda": Title("Agenda e studio"); PanelStudy(body); break;
            case "share": Title("Condividi"); PanelShare(body); break;
            case "finance":
                Title("Finanze");
                var hide = T("Nascondi saldo", 16);
                hide.VerticalAlignment = VerticalAlignment.Center;
                body.Children.Add(Cols("*a", 12, hide, EyeToggle(40)));
                body.Children.Add(PanelButtons(Btn("Nuova spesa", () => Open("fin-add"), "PrimaryButton"), Btn("Apri", () => Open("finance"))));
                break;
            default:
                Title("Audio");
                body.Children.Add(AudioRows(8));
                if (HeadphonesInfo() is { } hp) body.Children.Add(hp);
                var pause = T("Pausa automatica", 16);
                pause.VerticalAlignment = VerticalAlignment.Center;
                var pick = Segmented("pauto", new[] { "No", "Sì" }, _r.S.AutoPause ? 1 : 0, i => { _r.S.AutoPause = i == 1; _r.S.Save(); RenderPanel(); });
                pick.Width = 120;
                body.Children.Add(Cols("*a", 12, pause, pick));
                body.Children.Add(Btn("Impostazioni audio", () => Open("audio")));
                break;
        }
        var offset = (_panelCard.Child as ScrollViewer)?.VerticalOffset ?? 0;
        var scroll = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        _panelCard.MaxHeight = Math.Max(240, Root.ActualHeight - 80);
        _panelCard.Child = scroll;
        if (offset > 0) scroll.Loaded += (_, _) => scroll.ChangeView(null, offset, null, true);
    }

    private static Grid PanelButtons(Button first, Button second)
    {
        first.HorizontalAlignment = second.HorizontalAlignment = HorizontalAlignment.Stretch;
        return Cols("**", 12, first, second);
    }

    private void PanelStudy(StackPanel body)
    {
        var t = _r.Plan.State.Timer;
        var clock = T("", 52);
        clock.FontWeight = Microsoft.UI.Text.FontWeights.Light;
        clock.HorizontalAlignment = HorizontalAlignment.Center;
        void Upd() => clock.Text = TimerText();
        Upd();
        if (t.Running)
        {
            EnsureTicks();
            _panelTicks.Add(() => { Upd(); TimerCheck(); });
        }
        body.Children.Add(clock);
        var phases = new[] { "focus", "short", "long" };
        body.Children.Add(Segmented("ptimer", new[] { "Studio", "Pausa", "Lunga" }, Math.Max(0, Array.IndexOf(phases, t.Phase)), i => _r.Plan.TimerPhase(phases[i])));
        var main = t.Running ? Btn("Ferma", () => _r.Plan.TimerStop(), "PrimaryButton") : Btn("Avvia", () => _r.Plan.TimerStart(t.Subject), "PrimaryButton");
        body.Children.Add(PanelButtons(main, Btn("Agenda", () => Open("agenda"))));
    }

    /// <summary>Durata della fase corrente del pomodoro, in millisecondi.</summary>
    private long TimerTotal()
    {
        var p = _r.Plan.State;
        return (p.Timer.Phase switch { "short" => p.Get("short", 5), "long" => p.Get("long", 15), _ => p.Get("focus", 25) }) * 60_000L;
    }

    private string TimerText()
    {
        var t = _r.Plan.State.Timer;
        var total = TimerTotal();
        var left = t.Running ? Math.Clamp(t.EndAt - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), 0, total) : total;
        var secs = (int)((left + 999) / 1000);
        return $"{secs / 60:00}:{secs % 60:00}";
    }

    private void TimerCheck()
    {
        var t = _r.Plan.State.Timer;
        if (t.Running && t.EndAt <= DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()) _r.Plan.TimerDone();
    }

    private void PanelShare(StackPanel body)
    {
        var holder = new Border { Child = Center(Icons.Glyph(Tab.Clipboard, B("AccentOnSubtle"), 24)) };
        var clip = Round(holder, "Invia appunti", true, async () =>
        {
            if (!await _r.Clip.SendCurrentAsync()) return;
            holder.Child = Center(Icons.Of(Ico.Check, B("AccentOnSubtle"), 24));
            await Task.Delay(1000);
            holder.Child = Center(Icons.Glyph(Tab.Clipboard, B("AccentOnSubtle"), 24));
        });
        var file = Round(Tab.Upload, "Invia file", false, () => _ = PickFilesAsync());
        var row = HStack(40, clip, file);
        row.HorizontalAlignment = HorizontalAlignment.Center;
        body.Children.Add(row);
        body.Children.Add(PanelButtons(Btn("Appunti", () => Open("clips")), Btn("Immagini", () => Open("images"))));
    }

    // ---------------------------------------------------------------- audio

    /// <summary>I dispositivi, prima quelli che suonano: si ferma o si riprende da qui.</summary>
    private StackPanel AudioRows(int max)
    {
        var playing = _r.Media.PlayingNow();
        var known = _r.Media.Known;
        var ids = new List<string> { _r.DeviceId };
        ids.AddRange(_r.Devices.Select(d => d.Id).Where(id => id != _r.DeviceId));
        var list = new StackPanel { Spacing = 12 };
        foreach (var id in ids.OrderByDescending(IsPlaying).Take(max))
        {
            var me = id == _r.DeviceId;
            var on = IsPlaying(id);
            var track = me ? (_r.Np.Active ? _r.Np.Title : "") : known.TryGetValue(id, out var m) ? m.Title : playing.TryGetValue(id, out var pt) ? pt : "";
            var head = _r.Hp.Holder == id;
            var status = on ? (track.Length > 0 ? track : "In riproduzione") : head ? "Cuffie collegate" : "Fermo";
            var face = Bubble(Icons.Glyph(Tab.Devices, B(on ? "AccentDefault" : "LabelSecondary"), 16), 30, Alpha(C("AccentDefault"), on ? 0.22 : 0.08));
            face.VerticalAlignment = VerticalAlignment.Center;
            var text = VStack(0, Semi(me ? "Questo PC" : _r.DeviceName(id), 13), T(status, 11, on ? "AccentDefault" : "LabelTertiary"));
            text.VerticalAlignment = VerticalAlignment.Center;
            Button? act = null;
            if (on) act = AudioPill("Ferma", () => { if (me) _r.Np.Toggle(); else _r.Media.StopOn(id); });
            else if (me ? _r.Np.Active : known.ContainsKey(id) && track.Length > 0) act = AudioPill("Riprendi", () => { if (me) _r.Np.Toggle(); else _r.Media.Cmd(id, "play"); });
            list.Children.Add(Cols("a*a", 12, face, text, act));
        }
        return list;

        bool IsPlaying(string id) => id == _r.DeviceId ? _r.Np.Playing : playing.ContainsKey(id);
    }

    private Button AudioPill(string text, Action click)
    {
        var face = new Border
        {
            CornerRadius = new CornerRadius(16), Padding = new Thickness(14, 7, 14, 7), Background = Sb(Alpha(C("AccentDefault"), 0.16)),
            Child = Semi(text, 13, "AccentDefault"),
        };
        var b = Bare(face, click, text, 16);
        b.VerticalAlignment = VerticalAlignment.Center;
        Bounce(b);
        return b;
    }

    /// <summary>Chi ha le cuffie; niente se non ce ne sono.</summary>
    private Grid? HeadphonesInfo()
    {
        var connected = _r.Hp.List.FirstOrDefault(x => x.Connected);
        var holder = _r.Hp.Holder;
        if (connected == null && holder == null) return null;
        var ring = Ring(connected != null ? 1 : 0, 52, 4, Icons.Glyph(Tab.Headphones, B("LabelPrimary"), 22));
        var text = VStack(0, Semi(connected?.Name ?? "Cuffie", 16),
            T(holder == null ? "Libere" : holder == _r.DeviceId ? "Questo PC" : _r.DeviceName(holder), 13, "LabelSecondary"));
        text.VerticalAlignment = VerticalAlignment.Center;
        return Cols("a*", 16, ring, text);
    }
}
