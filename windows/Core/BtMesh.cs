using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace Ponte;

/// <summary>
/// Riserva via Bluetooth classico (RFCOMM), per quando non c'è né server né rete locale.
/// Il PC espone il servizio Ponte e aspetta: sono i telefoni e i tablet associati a chiamarlo.
/// È lento: bastano appunti, notifiche, segnali delle cuffie e file piccoli; l'audio non passa di qui.
/// </summary>
public sealed class BtMesh
{
    /// <summary>Stesso UUID dell'app Android.</summary>
    public static readonly Guid Service = new("7f2a0c1e-6b1d-4b8e-9a51-50e7e0a1c0de");

    private readonly Router _r;
    private RfcommServiceProvider? _provider;
    private StreamSocketListener? _listener;

    public BtMesh(Router r) => _r = r;

    public async Task StartAsync()
    {
        try
        {
            _provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(Service));
            _listener = new StreamSocketListener();
            _listener.ConnectionReceived += (_, e) => Task.Run(() =>
            {
                var sock = e.Socket;
                new TcpLink(_r, sock.InputStream.AsStreamForRead(), sock.OutputStream.AsStreamForWrite(), sock.Dispose, "bt").Run();
            });
            await _listener.BindServiceNameAsync(_provider.ServiceId.AsString(), SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);

            // Nome del servizio nel registro SDP (attributo 0x100), così compare come "Ponte".
            var w = new DataWriter { UnicodeEncoding = Windows.Storage.Streams.UnicodeEncoding.Utf8 };
            w.WriteByte(0x25);
            w.WriteByte(5);
            w.WriteString("Ponte");
            _provider.SdpRawAttributes.Add(0x100, w.DetachBuffer());
            _provider.StartAdvertising(_listener, true);
        }
        catch (Exception)
        {
            // Niente Bluetooth o radio spenta: la riserva resta spenta, il resto funziona.
        }
    }
}
