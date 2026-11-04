using System.Text.Json.Nodes;
using NAudio.CoreAudioApi;
using Windows.Media.Control;

namespace Ponte;

/// <summary>
/// Passaggio istantaneo della musica: quando su questo PC parte un contenuto multimediale gli altri dispositivi
/// vengono messi in pausa (messaggio <c>media.take</c>); quando parte altrove, qui si mette in pausa ciò che suona.
/// </summary>
public sealed class MediaSwitch
{
    private readonly Router _r;
    private readonly MMDeviceEnumerator _enum = new();
    private volatile bool _playing;
    private DateTime _lastLoud = DateTime.MinValue;
    private DateTime _startedAt = DateTime.MinValue;
    private DateTime _quietUntil = DateTime.MinValue;

    public MediaSwitch(Router r) => _r = r;

    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, (string Title, DateTime At)> _remote = new();
    private int _beat;

    /// <summary>Chi sta suonando adesso (id dispositivo -> titolo). Include questo PC.</summary>
    public Dictionary<string, string> PlayingNow()
    {
        var now = DateTime.UtcNow;
        var d = _remote.Where(x => now - x.Value.At < TimeSpan.FromSeconds(40)).ToDictionary(x => x.Key, x => x.Value.Title);
        if (_playing) d[_r.DeviceId] = "";
        return d;
    }

    private async Task<GlobalSystemMediaTransportControlsSession?> CurrentAsync()
    {
        var mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        var all = mgr.GetSessions();
        return all.FirstOrDefault(x => x.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing) ?? mgr.GetCurrentSession();
    }

    private async Task AnnounceAsync()
    {
        string title = "", artist = "";
        long pos = 0, dur = 0;
        var playing = _playing;
        try
        {
            var s = await CurrentAsync();
            if (s != null)
            {
                var p = await s.TryGetMediaPropertiesAsync();
                title = p.Title ?? ""; artist = p.Artist ?? "";
                var tl = s.GetTimelineProperties();
                pos = (long)tl.Position.TotalMilliseconds; dur = (long)tl.EndTime.TotalMilliseconds;
                playing = playing || s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            }
        }
        catch (Exception) { }
        int vol = -1;
        try { using var dev = _enum.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia); vol = (int)Math.Round(dev.AudioEndpointVolume.MasterVolumeLevelScalar * 100); } catch (Exception) { }
        _r.Send(Proto.NewMsg("media.state", ("playing", playing), ("title", title), ("artist", artist), ("pos", pos), ("dur", dur), ("vol", vol)));
    }

    /// <summary>Comando dal player universale di un altro dispositivo: play, pause, next, prev, seek (ms), vol (0..100).</summary>
    public void OnCmd(JsonObject m)
    {
        var a = m.S("a");
        long v = m["v"] is JsonValue jv && jv.TryGetValue<long>(out var lv) ? lv : 0;
        _ = Task.Run(async () =>
        {
            try
            {
                if (a == "vol")
                {
                    using var dev = _enum.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                    dev.AudioEndpointVolume.MasterVolumeLevelScalar = Math.Clamp(v / 100f, 0f, 1f);
                }
                else
                {
                    if (a == "pause") { _quietUntil = DateTime.UtcNow.AddMilliseconds(1200); _playing = false; }
                    var s = await CurrentAsync();
                    if (s != null)
                    {
                        switch (a)
                        {
                            case "play": await s.TryPlayAsync(); break;
                            case "pause": await s.TryPauseAsync(); break;
                            case "next": await s.TrySkipNextAsync(); break;
                            case "prev": await s.TrySkipPreviousAsync(); break;
                            case "seek": await s.TryChangePlaybackPositionAsync(TimeSpan.FromMilliseconds(v).Ticks); break;
                        }
                    }
                }
                await Task.Delay(700);
                await AnnounceAsync();
            }
            catch (Exception) { }
        });
    }

    public void OnState(JsonObject m)
    {
        var from = m.S("from");
        if (from.Length == 0) return;
        var playing = m["playing"] is JsonValue v && v.TryGetValue<bool>(out var b) && b;
        if (playing) _remote[from] = (m.S("title"), DateTime.UtcNow); else _remote.TryRemove(from, out _);
    }

    public void OnPauseReq()
    {
        _quietUntil = DateTime.UtcNow.AddMilliseconds(1200); _playing = false;
        _ = PauseAsync();
    }

    public void StopOn(string id)
    {
        if (id == _r.DeviceId) OnPauseReq();
        else _r.Send(Proto.NewMsg("media.pause", ("to", id)));
    }

    public void Start()
    {
        _ = Task.Run(async () =>
        {
            while (true)
            {
                try
                {
                    var was = _playing;
                    Poll();
                    if (was != _playing || ++_beat % 32 == 0) await AnnounceAsync();
                }
                catch (Exception) { }
                await Task.Delay(250);
            }
        });
    }

    private bool Loud()
    {
        try
        {
            using var dev = _enum.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            return dev.AudioMeterInformation.MasterPeakValue > 0.002f;
        }
        catch (Exception) { return false; }
    }

    private void Poll()
    {
        var now = DateTime.UtcNow;
        if (Loud())
        {
            if (now < _quietUntil) return;
            _lastLoud = now;
            if (_playing) return;
            _playing = true;
            _startedAt = now;
            if (!_r.S.AutoPause) return;
            // Se questo PC sta mandando il suo audio a un altro dispositivo non è un nuovo contenuto.
            if (_r.Audio.SinkId != null) return;
            var lockedBy = _r.Hp.LockedBy;
            if (lockedBy != null && lockedBy != _r.DeviceId) return;
            _r.Send(Proto.NewMsg("media.take"));
        }
        // Le pause tra un brano e l'altro non contano: serve silenzio per qualche secondo.
        else if (_playing && now - _lastLoud > TimeSpan.FromSeconds(3)) _playing = false;
    }

    public void OnTake(JsonObject m)
    {
        if (!_r.S.AutoPause) return;
        var from = m.S("from");
        if (from.Length == 0 || !_playing) return;
        if (_r.Hp.LockedBy == _r.DeviceId) return;
        if (_r.Audio.SinkId != null) return;
        if (DateTime.UtcNow - _startedAt < TimeSpan.FromSeconds(2) && string.CompareOrdinal(_r.DeviceId, from) < 0) return;
        _quietUntil = DateTime.UtcNow.AddMilliseconds(1200); _playing = false;
        _ = PauseAsync();
    }

    /// <summary>Un altro dispositivo è diventato quello in uso: qui l'audio si ferma subito.</summary>
    public void YieldTo(string from)
    {
        if (!_r.S.AutoPause || !_playing) return;
        if (_r.Hp.LockedBy == _r.DeviceId || _r.Audio.SinkId != null) return;
        _quietUntil = DateTime.UtcNow.AddMilliseconds(1200); _playing = false;
        _ = PauseAsync();
        _r.Notify.Show("Relay", "Ora usi " + _r.DeviceName(from), "Audio fermato su questo dispositivo");
    }

    private static async Task PauseAsync()
    {
        try
        {
            var mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            foreach (var s in mgr.GetSessions())
            {
                if (s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
                    await s.TryPauseAsync();
            }
        }
        catch (Exception) { }
    }
}
