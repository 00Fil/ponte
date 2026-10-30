using System.Buffers.Binary;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace Ponte;

/// <summary>
/// Cattura tutto l'audio che esce da questo PC (WASAPI loopback) e lo manda a un altro dispositivo
/// in PCM 16 bit, 48 kHz, stereo, a pacchetti da 20 ms. Il dispositivo che riceve lo suona nelle sue cuffie.
/// </summary>
public sealed class AudioSource
{
    private readonly Router _r;
    private readonly object _gate = new();
    private WasapiLoopbackCapture? _cap;
    private CancellationTokenSource? _cts;
    private uint _sid;

    public AudioSource(Router r) => _r = r;

    /// <summary>Id del dispositivo a cui sta arrivando l'audio, o null.</summary>
    public string? SinkId { get; private set; }

    /// <summary>Avvia lo streaming verso <paramref name="sinkId"/>, o lo ferma con null. Chiamabile da qualunque dispositivo tramite audio.route.</summary>
    public void Route(string? sinkId)
    {
        lock (_gate)
        {
            Stop();
            if (sinkId == null) { _r.Raise(); return; }
            try { Start(sinkId); }
            catch (Exception) { Stop(); }
        }
        _r.Raise();
    }

    private void Start(string sinkId)
    {
        _sid = (uint)Random.Shared.NextInt64(1, uint.MaxValue);
        SinkId = sinkId;
        _r.Send(Proto.NewMsg("audio.start", ("sid", _sid), ("to", sinkId), ("rate", Proto.AudioRate), ("ch", 2)));

        var cap = new WasapiLoopbackCapture();
        var buffer = new BufferedWaveProvider(cap.WaveFormat)
        {
            BufferDuration = TimeSpan.FromSeconds(1),
            DiscardOnBufferOverflow = true,
            ReadFully = false,
        };
        cap.DataAvailable += (_, e) => buffer.AddSamples(e.Buffer, 0, e.BytesRecorded);
        cap.RecordingStopped += (_, _) => { lock (_gate) { if (_cap == cap) Stop(); } _r.Raise(); };

        var samples = buffer.ToSampleProvider();
        if (samples.WaveFormat.Channels == 1) samples = new MonoToStereoSampleProvider(samples);
        else if (samples.WaveFormat.Channels > 2)
        {
            var mux = new MultiplexingSampleProvider(new[] { samples }, 2);
            mux.ConnectInputToOutput(0, 0);
            mux.ConnectInputToOutput(1, 1);
            samples = mux;
        }
        if (samples.WaveFormat.SampleRate != Proto.AudioRate) samples = new WdlResamplingSampleProvider(samples, Proto.AudioRate);
        var pcm = new SampleToWaveProvider16(samples);

        _cts = new CancellationTokenSource();
        var ct = _cts.Token;
        var sid = _sid;
        _ = Task.Run(() => Pump(pcm, sinkId, sid, ct));
        _cap = cap;
        cap.StartRecording();
    }

    private void Pump(IWaveProvider pcm, string sinkId, uint sid, CancellationToken ct)
    {
        var packet = new byte[4 + Proto.AudioPacketBytes];
        BinaryPrimitives.WriteUInt32BigEndian(packet, sid);
        var have = 0;
        while (!ct.IsCancellationRequested)
        {
            var n = pcm.Read(packet, 4 + have, Proto.AudioPacketBytes - have);
            have += n;
            if (have < Proto.AudioPacketBytes)
            {
                Thread.Sleep(n == 0 ? 5 : 0); // il loopback non dà dati finché non c'è audio in riproduzione
                continue;
            }
            have = 0;
            _r.SendAudio(sinkId, (byte[])packet.Clone());
        }
    }

    private void Stop()
    {
        _cts?.Cancel();
        _cts = null;
        var cap = _cap;
        _cap = null;
        if (cap != null)
        {
            try { cap.StopRecording(); } catch (Exception) { }
            cap.Dispose();
        }
        if (SinkId != null)
        {
            _r.Send(Proto.NewMsg("audio.stop", ("sid", _sid), ("to", SinkId)));
            SinkId = null;
        }
    }
}
