using System.Buffers.Binary;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;

namespace Ponte;

internal sealed class TcpLink : ILink
{
    private readonly Router _r;
    private readonly TcpClient _c;
    private readonly NetworkStream _s;
    private readonly object _w = new();

    public TcpLink(Router r, TcpClient c)
    {
        _r = r;
        _c = c;
        _s = c.GetStream();
    }

    public string Kind => "lan";
    public string? PeerId { get; set; }
    public long Queued => 0;

    private bool Write(byte type, byte[] payload)
    {
        try
        {
            var head = new byte[5];
            BinaryPrimitives.WriteInt32BigEndian(head, payload.Length + 1);
            head[4] = type;
            lock (_w)
            {
                _s.Write(head, 0, 5);
                _s.Write(payload, 0, payload.Length);
            }
            return true;
        }
        catch (Exception) { Close(); return false; }
    }

    public bool SendJson(string json) => Write(0, Encoding.UTF8.GetBytes(json));
    public bool SendAudio(byte[] packet) => Write(1, packet);
    public void Close() { try { _c.Close(); } catch (Exception) { /* già chiuso */ } }

    public void Run()
    {
        try
        {
            _c.NoDelay = true;
            Write(0, Encoding.UTF8.GetBytes(_r.HelloLan().ToJsonString()));
            var lenBuf = new byte[4];
            while (true)
            {
                _s.ReadExactly(lenBuf, 0, 4);
                var len = BinaryPrimitives.ReadInt32BigEndian(lenBuf);
                if (len < 1 || len > 1_048_576) break;
                var type = _s.ReadByte();
                if (type < 0) break;
                var buf = new byte[len - 1];
                _s.ReadExactly(buf, 0, buf.Length);
                if (PeerId == null)
                {
                    if (type != 0 || !AcceptHello(buf)) break;
                }
                else if (type == 0) _r.OnText(Encoding.UTF8.GetString(buf), this);
                else if (type == 1) _r.OnAudio(buf);
            }
        }
        catch (Exception) { /* collegamento caduto */ }
        finally
        {
            Close();
            _r.RemoveLink(this);
        }
    }

    private bool AcceptHello(byte[] buf)
    {
        if (JsonNode.Parse(buf) is not JsonObject m) return false;
        var from = m.S("from");
        if (m.S("t") != "hello" || from.Length == 0 || from == _r.DeviceId) return false;
        if (!Lan.Verify(_r.S.Token, from, m.S("proof"))) return false;
        PeerId = from;
        _r.AddLink(this);
        _r.LanPeerSeen(new Device(from, m.S("name"), m.S("platform"), Proto.Caps(m), "lan"));
        return true;
    }
}

/// <summary>Scoperta in UDP broadcast e collegamenti TCP diretti tra i dispositivi di casa.</summary>
public sealed class LanMesh
{
    private readonly Router _r;
    private readonly object _gate = new();
    private CancellationTokenSource? _cts;
    private TcpListener? _listener;
    private UdpClient? _udp;
    private readonly HashSet<string> _connecting = new();

    public LanMesh(Router r) => _r = r;

    public void Restart()
    {
        Stop();
        lock (_gate)
        {
            _cts = new CancellationTokenSource();
            var ct = _cts.Token;
            try
            {
                _listener = new TcpListener(IPAddress.Any, Proto.LanTcpPort);
                _listener.Start();
                _udp = new UdpClient { ExclusiveAddressUse = false, EnableBroadcast = true };
                _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                _udp.Client.Bind(new IPEndPoint(IPAddress.Any, Proto.LanUdpPort));
            }
            catch (SocketException)
            {
                return; // porta occupata: la rete locale resta spenta, il server funziona lo stesso
            }
            _ = Task.Run(() => AcceptAsync(_listener, ct));
            _ = Task.Run(() => ListenAsync(_udp, ct));
            _ = Task.Run(() => AnnounceAsync(_udp, ct));
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            _cts?.Cancel();
            try { _listener?.Stop(); } catch (Exception) { }
            try { _udp?.Close(); } catch (Exception) { }
            _listener = null;
            _udp = null;
        }
        _r.CloseLanLinks();
    }

    private async Task AcceptAsync(TcpListener l, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var c = await l.AcceptTcpClientAsync(ct);
                _ = Task.Run(() => new TcpLink(_r, c).Run());
            }
        }
        catch (Exception) { }
    }

    private static IEnumerable<IPAddress> BroadcastTargets()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var ua in nic.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                var ip = ua.Address.GetAddressBytes();
                var mask = ua.IPv4Mask.GetAddressBytes();
                var b = new byte[4];
                for (var i = 0; i < 4; i++) b[i] = (byte)(ip[i] | ~mask[i]);
                yield return new IPAddress(b);
            }
        }
    }

    private async Task AnnounceAsync(UdpClient udp, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var data = Encoding.UTF8.GetBytes(new JsonObject
                {
                    ["p"] = "ponte", ["id"] = _r.DeviceId, ["name"] = _r.S.Name, ["port"] = Proto.LanTcpPort,
                }.ToJsonString());
                // Una trasmissione per ogni scheda di rete: con VPN o WSL il broadcast generico esce da una sola.
                foreach (var target in BroadcastTargets().Distinct())
                {
                    try { await udp.SendAsync(data, data.Length, new IPEndPoint(target, Proto.LanUdpPort)); }
                    catch (Exception) { }
                }
                await Task.Delay(5000, ct);
            }
        }
        catch (Exception) { }
    }

    private async Task ListenAsync(UdpClient udp, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var res = await udp.ReceiveAsync(ct);
                JsonObject? m;
                try { m = JsonNode.Parse(res.Buffer) as JsonObject; } catch (Exception) { continue; }
                if (m == null || m.S("p") != "ponte") continue;
                var id = m.S("id");
                if (id.Length == 0 || id == _r.DeviceId || _r.HasLanLink(id)) continue;
                // Si collega solo chi ha l'id minore: nasce un solo collegamento per coppia.
                if (string.CompareOrdinal(_r.DeviceId, id) >= 0) continue;
                lock (_connecting) if (!_connecting.Add(id)) continue;
                var host = res.RemoteEndPoint.Address;
                var port = (int)m.L("port", Proto.LanTcpPort);
                _ = Task.Run(() => ConnectAsync(id, host, port, ct));
            }
        }
        catch (Exception) { }
    }

    private async Task ConnectAsync(string id, IPAddress host, int port, CancellationToken ct)
    {
        try
        {
            var c = new TcpClient();
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(3000);
            await c.ConnectAsync(host, port, timeout.Token);
            new TcpLink(_r, c).Run();
        }
        catch (Exception) { }
        finally { lock (_connecting) _connecting.Remove(id); }
    }
}
