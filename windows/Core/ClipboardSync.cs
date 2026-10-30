using Microsoft.UI.Dispatching;
using Windows.ApplicationModel.DataTransfer;

namespace Ponte;

public sealed record ClipItem(string Text, string From, long Ts, bool Mine);

/// <summary>Appunti di testo condivisi. Su Windows si leggono in qualsiasi momento: l'invio è automatico a ogni copia.</summary>
public sealed class ClipboardSync
{
    private readonly Router _r;
    private readonly DispatcherQueue _ui;
    private readonly List<ClipItem> _history = new();
    private string? _lastApplied;

    public ClipboardSync(Router r, DispatcherQueue ui)
    {
        _r = r;
        _ui = ui;
    }

    public IReadOnlyList<ClipItem> History { get { lock (_history) return _history.ToList(); } }

    public void Start() => _ui.TryEnqueue(() => Clipboard.ContentChanged += OnChanged);

    private async void OnChanged(object? sender, object e) => await SendCurrentAsync();

    /// <summary>Legge gli appunti e li invia. Va chiamato sul thread dell'interfaccia.</summary>
    public async Task<bool> SendCurrentAsync()
    {
        // Un'altra app può tenere gli appunti bloccati per un istante: si riprova qualche volta.
        for (var i = 0; i < 5; i++)
        {
            try
            {
                var c = Clipboard.GetContent();
                // I gestori di password segnano ciò che copiano come da non elaborare: lo rispettiamo.
                if (c.AvailableFormats.Contains("ExcludeClipboardContentFromMonitorProcessing")) return false;
                if (!c.Contains(StandardDataFormats.Text)) return false;
                var text = await c.GetTextAsync();
                if (string.IsNullOrEmpty(text) || text == _lastApplied || text.Length > 1_000_000) return false;
                Push(new ClipItem(text, _r.DeviceId, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), true));
                _r.Send(Proto.NewMsg("clip", ("text", text)));
                return true;
            }
            catch (Exception) { await Task.Delay(60); }
        }
        return false;
    }

    public void SendText(string text)
    {
        Push(new ClipItem(text, _r.DeviceId, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), true));
        _r.Send(Proto.NewMsg("clip", ("text", text)));
    }

    public void OnRemote(string text, string from)
    {
        if (text.Length == 0 || text.Length > 1_000_000) return;
        Push(new ClipItem(text, from, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), false));
        SetLocal(text);
    }

    /// <summary>Mette il testo negli appunti di questo PC senza rimandarlo agli altri.</summary>
    public void SetLocal(string text)
    {
        _lastApplied = text;
        _ui.TryEnqueue(() =>
        {
            try
            {
                var dp = new DataPackage();
                dp.SetText(text);
                Clipboard.SetContent(dp);
            }
            catch (Exception) { /* appunti occupati: la prossima copia riparte */ }
        });
    }

    private void Push(ClipItem item)
    {
        lock (_history)
        {
            _history.RemoveAll(h => h.Text == item.Text);
            _history.Insert(0, item);
            if (_history.Count > 50) _history.RemoveRange(50, _history.Count - 50);
        }
        _r.Raise();
    }
}
