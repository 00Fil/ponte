using NAudio.CoreAudioApi;
using Windows.Media.Control;

namespace Ponte;

/// <summary>Cosa suona su questo PC (controlli multimediali di Windows) e volume di sistema: come il player di Android.</summary>
public sealed class NowPlaying
{
    private readonly Router _r;
    private GlobalSystemMediaTransportControlsSessionManager? _mgr;
    private GlobalSystemMediaTransportControlsSession? _session;
    private readonly MMDeviceEnumerator _enum = new();

    public string Title { get; private set; } = "";
    public string Artist { get; private set; } = "";
    public string App { get; private set; } = "";
    public bool Playing { get; private set; }
    public byte[]? Art { get; private set; }
    public TimeSpan Position { get; private set; }
    public TimeSpan Duration { get; private set; }
    private DateTimeOffset _posAt;
    public bool Active => _session != null && (Title.Length > 0 || Playing);

    public NowPlaying(Router r) => _r = r;

    public async Task StartAsync()
    {
        try
        {
            _mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            _mgr.CurrentSessionChanged += (_, _) => _ = Pick();
            _mgr.SessionsChanged += (_, _) => _ = Pick();
            await Pick();
            // rete di sicurezza: alcune app non avvisano dei cambi
            _ = Task.Run(async () => { while (true) { await Task.Delay(3000); try { await Pick(); } catch (Exception) { } } });
        }
        catch (Exception) { }
    }

    private async Task Pick()
    {
        if (_mgr == null) return;
        var sessions = _mgr.GetSessions();
        var s = sessions.FirstOrDefault(x => x.GetPlaybackInfo()?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
                ?? _mgr.GetCurrentSession() ?? sessions.FirstOrDefault();
        if (!ReferenceEquals(s, _session))
        {
            if (_session != null)
            {
                _session.MediaPropertiesChanged -= OnProps;
                _session.PlaybackInfoChanged -= OnInfo;
                _session.TimelinePropertiesChanged -= OnTimeline;
            }
            _session = s;
            if (s != null)
            {
                s.MediaPropertiesChanged += OnProps;
                s.PlaybackInfoChanged += OnInfo;
                s.TimelinePropertiesChanged += OnTimeline;
            }
        }
        await Read();
    }

    private void OnProps(GlobalSystemMediaTransportControlsSession s, MediaPropertiesChangedEventArgs e) => _ = Read();
    private void OnInfo(GlobalSystemMediaTransportControlsSession s, PlaybackInfoChangedEventArgs e) => _ = Read();
    private void OnTimeline(GlobalSystemMediaTransportControlsSession s, TimelinePropertiesChangedEventArgs e) => _ = Read();

    private string _lastKey = "";

    private async Task Read()
    {
        var s = _session;
        if (s == null)
        {
            if (Title.Length > 0 || Playing) { Title = Artist = App = ""; Playing = false; Art = null; _r.Raise(); }
            return;
        }
        try
        {
            var p = await s.TryGetMediaPropertiesAsync();
            var info = s.GetPlaybackInfo();
            var tl = s.GetTimelineProperties();
            var title = p?.Title ?? "";
            var artist = p?.Artist ?? "";
            var playing = info?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            if (title != Title || artist != Artist)
            {
                Art = null;
                if (p?.Thumbnail != null)
                {
                    using var st = await p.Thumbnail.OpenReadAsync();
                    using var ms = new MemoryStream();
                    await st.AsStreamForRead().CopyToAsync(ms);
                    Art = ms.ToArray();
                }
            }
            Title = title;
            Artist = artist;
            App = s.SourceAppUserModelId.Split('!')[0].Split('.').LastOrDefault() ?? "";
            Playing = playing;
            if (tl != null)
            {
                Duration = tl.EndTime - tl.StartTime;
                Position = tl.Position;
                _posAt = tl.LastUpdatedTime;
            }
            var key = $"{Title}|{Artist}|{Playing}";
            if (key != _lastKey) { _lastKey = key; _r.Raise(); }
        }
        catch (Exception) { }
    }

    /// <summary>Posizione stimata adesso (avanza da sola mentre suona).</summary>
    public TimeSpan PositionNow()
    {
        if (!Playing || _posAt == default) return Position;
        var p = Position + (DateTimeOffset.Now - _posAt);
        return Duration > TimeSpan.Zero && p > Duration ? Duration : p;
    }

    public void Prev() => _ = _session?.TrySkipPreviousAsync().AsTask();
    public void Toggle() => _ = _session?.TryTogglePlayPauseAsync().AsTask();
    public void Next() => _ = _session?.TrySkipNextAsync().AsTask();
    public void Seek(TimeSpan to) => _ = _session?.TryChangePlaybackPositionAsync(to.Ticks).AsTask();

    public float Volume
    {
        get { try { using var d = _enum.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia); return d.AudioEndpointVolume.MasterVolumeLevelScalar; } catch (Exception) { return 0; } }
        set { try { using var d = _enum.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia); d.AudioEndpointVolume.MasterVolumeLevelScalar = Math.Clamp(value, 0f, 1f); } catch (Exception) { } }
    }

    public string OutputName
    {
        get { try { using var d = _enum.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia); return d.FriendlyName; } catch (Exception) { return "Altoparlanti"; } }
    }
}
