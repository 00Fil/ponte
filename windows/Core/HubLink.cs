using System.Net.WebSockets;
using System.Text;
using System.Threading.Channels;

namespace Ponte;

internal sealed class WsLink : ILink
{
    private readonly ClientWebSocket _ws;
    private readonly Channel<(byte[] Data, bool Text)> _q = Channel.CreateUnbounded<(byte[], bool)>(new UnboundedChannelOptions { SingleReader = true });
    private long _queued;

    public WsLink(ClientWebSocket ws) => _ws = ws;

    public string Kind => "hub";
    public string? PeerId { get; set; }
    public long Queued => Interlocked.Read(ref _queued);

    public bool SendJson(string json) => Enqueue(Encoding.UTF8.GetBytes(json), true);

    public bool SendAudio(byte[] packet) => Queued <= 512 * 1024 && Enqueue(packet, false); // si scarta se la coda cresce

    private bool Enqueue(byte[] data, bool text)
    {
        Interlocked.Add(ref _queued, data.Length);
        return _q.Writer.TryWrite((data, text));
    }

    /// <summary>Un solo scrittore alla volta: ClientWebSocket non ammette invii concorrenti.</summary>
    public async Task PumpAsync(CancellationToken ct)
    {
        try
        {
            await foreach (var (data, text) in _q.Reader.ReadAllAsync(ct))
            {
                await _ws.SendAsync(data, text ? WebSocketMessageType.Text : WebSocketMessageType.Binary, true, ct);
                Interlocked.Add(ref _queued, -data.Length);
            }
        }
        catch (Exception) { Close(); }
    }

    public void Close()
    {
        _q.Writer.TryComplete();
        try { _ws.Abort(); } catch (Exception) { /* già chiuso */ }
    }
}

/// <summary>Collegamento con il server sul VPS. Si riconnette da solo, con attesa crescente.</summary>
public sealed class HubLink
{
    private readonly Router _r;
    private readonly object _gate = new();
    private CancellationTokenSource? _cts;

    public HubLink(Router r) => _r = r;

    public void Restart()
    {
        lock (_gate)
        {
            _cts?.Cancel();
            _cts = new CancellationTokenSource();
            var ct = _cts.Token;
            _ = Task.Run(() => LoopAsync(ct));
        }
    }

    private async Task LoopAsync(CancellationToken ct)
    {
        var backoff = 1000;
        while (!ct.IsCancellationRequested)
        {
            var url = _r.S.WsUrl();
            var token = _r.S.Token;
            if (url == null || token.Length == 0)
            {
                _r.HubState = "Non configurato";
                if (!await DelayAsync(3000, ct)) return;
                continue;
            }

            using var ws = new ClientWebSocket();
            ws.Options.SetRequestHeader("Authorization", "Bearer " + token);
            ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(55);
            WsLink? link = null;
            try
            {
                _r.HubState = "Connessione in corso";
                await ws.ConnectAsync(new Uri(url), ct);
                link = new WsLink(ws);
                _r.AddLink(link);
                _ = link.PumpAsync(ct);
                link.SendJson(_r.Hello().ToJsonString());
                _r.HubState = "Connesso";
                backoff = 1000;
                _r.OnHubConnected();
                await ReceiveAsync(ws, link, ct);
            }
            catch (OperationCanceledException) { }
            catch (WebSocketException ex) { _r.HubState = ex.Message.Contains("401") ? "Token non accettato" : "Server non raggiungibile"; }
            catch (Exception) { _r.HubState = "Server non raggiungibile"; }
            finally
            {
                if (link != null) { _r.RemoveLink(link); link.Close(); }
            }
            if (ct.IsCancellationRequested) return;
            if (_r.HubState == "Connesso") _r.HubState = "Disconnesso";
            if (!await DelayAsync(backoff, ct)) return;
            backoff = Math.Min(backoff * 2, 30000);
        }
    }

    private async Task ReceiveAsync(ClientWebSocket ws, WsLink link, CancellationToken ct)
    {
        var buf = new byte[64 * 1024];
        using var ms = new MemoryStream();
        while (ws.State == WebSocketState.Open && !ct.IsCancellationRequested)
        {
            ms.SetLength(0);
            WebSocketReceiveResult res;
            do
            {
                res = await ws.ReceiveAsync(new ArraySegment<byte>(buf), ct);
                if (res.MessageType == WebSocketMessageType.Close) return;
                ms.Write(buf, 0, res.Count);
                if (ms.Length > 2 * 1024 * 1024) return;
            } while (!res.EndOfMessage);

            if (res.MessageType == WebSocketMessageType.Text) _r.OnText(Encoding.UTF8.GetString(ms.GetBuffer(), 0, (int)ms.Length), link);
            else _r.OnAudio(ms.ToArray());
        }
    }

    private static async Task<bool> DelayAsync(int ms, CancellationToken ct)
    {
        try { await Task.Delay(ms, ct); return true; }
        catch (OperationCanceledException) { return false; }
    }
}
