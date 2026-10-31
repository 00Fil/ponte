using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Ponte;

/// <summary>Vedi PROTOCOLLO.md nella radice del progetto.</summary>
public static class Proto
{
    public const int LanTcpPort = 47800;
    public const int LanUdpPort = 47801;
    public const int ChunkBytes = 48 * 1024;
    public const int AudioRate = 48000;
    public const int AudioPacketBytes = 3840; // 20 ms, 48 kHz, stereo, 16 bit

    /// <summary>Rete privata Headscale/Tailscale: 100.64.0.0/10.</summary>
    public static bool IsTailnet(string ip)
    {
        var p = ip.Split('.');
        return p.Length == 4 && p[0] == "100" && int.TryParse(p[1], out var b) && b >= 64 && b <= 127;
    }

    public static JsonObject NewMsg(string t, params (string Key, JsonNode? Value)[] extra)
    {
        var o = new JsonObject
        {
            ["t"] = t,
            ["id"] = Guid.NewGuid().ToString(),
            ["ts"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        };
        foreach (var (k, v) in extra) o[k] = v;
        return o;
    }

    public static string S(this JsonObject o, string key) =>
        o[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : "";

    public static long L(this JsonObject o, string key, long fallback = 0)
    {
        if (o[key] is not JsonValue v) return fallback;
        if (v.TryGetValue<long>(out var l)) return l;
        if (v.TryGetValue<double>(out var d)) return (long)d;
        return fallback;
    }

    public static HashSet<string> Caps(JsonObject o) =>
        o["caps"] is JsonArray a
            ? a.Select(x => x is JsonValue v && v.TryGetValue<string>(out var s) ? s : "").Where(s => s.Length > 0).ToHashSet()
            : new HashSet<string>();
}

public sealed record Device(string Id, string Name, string Platform, IReadOnlySet<string> Caps, string Via);

/// <summary>Un collegamento verso un altro dispositivo (rete locale) o verso il server.</summary>
public interface ILink
{
    string Kind { get; } // "hub" oppure "lan"
    string? PeerId { get; set; }
    long Queued { get; }
    bool SendJson(string json);
    bool SendAudio(byte[] packet); // [u32 BE sid][PCM]
    void Close();
}

public static class Lan
{
    public static string Proof(string token, string id) =>
        Convert.ToHexString(HMACSHA256.HashData(Encoding.UTF8.GetBytes(token), Encoding.UTF8.GetBytes("ponte-lan|" + id))).ToLowerInvariant();

    public static bool Verify(string token, string id, string proof)
    {
        if (token.Length == 0) return false;
        var a = Encoding.UTF8.GetBytes(Proof(token, id));
        var b = Encoding.UTF8.GetBytes(proof);
        return a.Length == b.Length && CryptographicOperations.FixedTimeEquals(a, b);
    }
}
