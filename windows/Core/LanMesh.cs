using System.Buffers.Binary;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;

namespace Ponte;

/// <summary>
/// Collegamento diretto con un altro dispositivo su qualsiasi canale a flusso (TCP, Bluetooth RFCOMM).
/// Trame: [u32 BE lunghezza][u8 tipo][dati], tipo 0 = JSON, 1 = audio. Il primo messaggio è hello con proof.
/// </summary>
internal sealed class TcpLink : ILink
{
    private readonly Router _r;
    private readonly Stream _in;
    private readonly Stream _out;
    private readonly Action _close;
    private readonly object _w = new();

    public TcpLink(Router r, TcpClient c) : this(r, c.GetStream(), c.GetStream(), c.Close, "lan")
    {
        try { c.NoDelay = true; } catch (Exception) { }
    }

    public TcpLink(Router r, Stream input, Stream output, Action close, string kind)
    {
        _r = r;
        _in = input;
        _out = output;
        _close = close;
        Kind = kind;
    }

    public string Kind { get; }
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
                _out.Write(head, 0, 5);
                _out.Write(payload, 0, payload.Length);
                _out.Flush();
            }
            return true;
        }
        catch (Exception) { Close(); return false; }
    }

    public bool SendJson(string json) => Write(0, Encoding.UTF8.GetBytes(json));
    public bool SendAudio(byte[] packet) => Kind != "bt" && Write(1, packet);
    public void Close() { try { _close(); } catch (Exception) { /* già chiuso */ } }

    public void Run()
    {
        try
        {
            Write(0, Encoding.UTF8.GetBytes(_r.HelloLan().ToJsonString()));
            var lenBuf = new byte[4];
            var typeBuf = new byte[1];
            while (true)
            {
                _in.ReadExactly(lenBuf, 0, 4);
                var len = BinaryPrimitives.ReadInt32BigEndian(lenBuf);
                if (len < 1 || len > 1_048_576) break;
                _in.ReadExactly(typeBuf, 0, 1);
                var buf = new byte[len - 1];
                _in.ReadExactly(buf, 0, buf.Length);
                if (PeerId == null)
                {
                    if (typeBuf[0] != 0 || !AcceptHello(buf)) break;
                }
                else if (typeBuf[0] == 0) _r.OnText(Encoding.UTF8.GetString(buf), this);
                else if (typeBuf[0] == 1) _r.OnAudio(buf);
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
        _r.LanPeerSeen(new Device(from, m.S("name"), m.S("platform"), Proto.Caps(m), Kind));
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
            _ = Task.Run(() => RedialKnownAsync(ct));
            _ = Task.Run(AllowFirewall);
        }
    }

    /// <summary>Una sola volta chiede il permesso di amministratore per aprire le porte locali nel firewall di Windows.</summary>
    private static void AllowFirewall()
    {
        try
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Ponte");
            Directory.CreateDirectory(dir);
            var mark = Path.Combine(dir, "firewall.ok");
            var exe = Environment.ProcessPath;
            if (File.Exists(mark) || string.IsNullOrEmpty(exe)) return;
            var args = $"/c netsh advfirewall firewall delete rule name=\"Relay\" & " +
                $"netsh advfirewall firewall add rule name=\"Relay\" dir=in action=allow program=\"{exe}\" protocol=tcp localport={Proto.LanTcpPort} profile=any & " +
                $"netsh advfirewall firewall add rule name=\"Relay\" dir=in action=allow program=\"{exe}\" protocol=udp localport={Proto.LanUdpPort} profile=any";
            using var p = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("cmd.exe", args) { Verb = "runas", UseShellExecute = true, WindowStyle = System.Diagnostics.ProcessWindowStyle.Hidden });
            p?.WaitForExit(20000);
            if (p != null && p.HasExited && p.ExitCode == 0) File.WriteAllText(mark, exe);
        }
        catch (Exception) { }
    }

    /// <summary>
    /// Collegamento diretto a un dispositivo di cui si conoscono gli indirizzi (dal server o dalla rubrica salvata).
    /// Prima l'indirizzo della rete privata: funziona anche senza Wi-Fi in comune ed è cifrato da WireGuard.
    /// </summary>
    public void Dial(string id, List<string> addrs, int port = Proto.LanTcpPort)
    {
        CancellationToken ct;
        lock (_gate) { if (_cts == null) return; ct = _cts.Token; }
        if (id == _r.DeviceId) return;
        var clean = addrs.Where(a => IPAddress.TryParse(a, out _)).Distinct().OrderBy(a => Proto.IsTailnet(a) ? 0 : 1).ToList();
        if (clean.Count == 0) return;
        lock (_r.S.Peers) _r.S.Peers[id] = clean;
        try { _r.S.Save(); } catch (Exception) { }
        if (_r.HasLanLink(id)) return;
        var late = string.CompareOrdinal(_r.DeviceId, id) >= 0;
        lock (_connecting) if (!_connecting.Add(id)) return;
        _ = Task.Run(async () =>
        {
            try
            {
                if (late)
                {
                    try { await Task.Delay(8000, ct); } catch (Exception) { return; }
                    if (_r.HasLanLink(id)) return;
                }
                foreach (var a in clean)
                {
                    var c = new TcpClient();
                    try
                    {
                        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                        timeout.CancelAfter(3000);
                        await c.ConnectAsync(IPAddress.Parse(a), port, timeout.Token);
                    }
                    catch (Exception) { c.Dispose(); continue; }
                    lock (_connecting) _connecting.Remove(id);
                    new TcpLink(_r, c).Run();
                    return;
                }
            }
            finally { lock (_connecting) _connecting.Remove(id); }
        });
    }

    private async Task RedialKnownAsync(CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                await Task.Delay(60_000, ct);
                List<KeyValuePair<string, List<string>>> peers;
                lock (_r.S.Peers) peers = _r.S.Peers.ToList();
                foreach (var (id, addrs) in peers) if (!_r.HasLanLink(id)) Dial(id, addrs);
            }
        }
        catch (Exception) { }
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
                await Task.Delay(10_000, ct);
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
                var host = res.RemoteEndPoint.Address;
                var port = (int)m.L("port", Proto.LanTcpPort);
                if (string.CompareOrdinal(_r.DeviceId, id) >= 0)
                {
                    // Chi ha l'id maggiore aspetta; se l'altro non riesce a collegarsi (firewall) si collega lui.
                    _ = Task.Run(async () =>
                    {
                        try { await Task.Delay(8000, ct); } catch (Exception) { return; }
                        if (_r.HasLanLink(id)) return;
                        lock (_connecting) if (!_connecting.Add(id)) return;
                        await ConnectAsync(id, host, port, ct);
                    });
                    continue;
                }
                lock (_connecting) if (!_connecting.Add(id)) continue;
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
