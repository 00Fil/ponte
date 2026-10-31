using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using Microsoft.Win32;

namespace Ponte;

/// <summary>
/// Installazione per utente, senza amministratore: cartella in %LOCALAPPDATA%\Programs\Ponte (scrivibile, così gli
/// aggiornamenti automatici la sostituiscono), collegamenti nel menu Start e sul desktop, avvio con Windows e voce
/// in Impostazioni > App con la sua disinstallazione.
/// </summary>
public static class Installer
{
    public static readonly string InstallDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Ponte");
    public static string InstalledExe => Path.Combine(InstallDir, "Ponte.exe");

    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Ponte";

    private static string StartMenuLink => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Relay.lnk");
    private static string DesktopLink => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Relay.lnk");

    /// <summary>I collegamenti col vecchio nome (Ponte): si tolgono a ogni installazione.</summary>
    private static void RemoveOldLinks()
    {
        TryDelete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Ponte.lnk"));
        TryDelete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Ponte.lnk"));
    }

    public static string Version
    {
        get
        {
            var b = typeof(Installer).Assembly.GetName().Version?.Build ?? 0;
            return $"1.0.{b}";
        }
    }

    /// <summary>Questa copia è quella installata?</summary>
    public static bool IsInstalledCopy =>
        string.Equals(Path.GetFullPath(AppContext.BaseDirectory).TrimEnd('\\'), InstallDir.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);

    public static bool IsInstalled => File.Exists(InstalledExe);

    /// <summary>Chiude le altre copie di Ponte (quella nell'area di notifica), così i file si possono sostituire.</summary>
    public static void StopOthers()
    {
        var me = Environment.ProcessId;
        foreach (var p in Process.GetProcessesByName("Ponte"))
        {
            if (p.Id == me) continue;
            try { p.Kill(); p.WaitForExit(5000); } catch (Exception) { }
        }
    }

    /// <summary>Copia i file, crea collegamenti e voce di disinstallazione. progress va da 0 a 1.</summary>
    public static void Install(bool autostart, bool desktop, Action<double, string> progress)
    {
        progress(0, "Chiudo Relay se è aperto");
        StopOthers();

        var src = Path.GetFullPath(AppContext.BaseDirectory).TrimEnd('\\');
        var files = Directory.GetFiles(src, "*", SearchOption.AllDirectories);
        Directory.CreateDirectory(InstallDir);
        long total = files.Sum(f => new FileInfo(f).Length), done = 0;
        foreach (var f in files)
        {
            var rel = Path.GetRelativePath(src, f);
            var dst = Path.Combine(InstallDir, rel);
            Directory.CreateDirectory(Path.GetDirectoryName(dst)!);
            for (var attempt = 0; ; attempt++)
            {
                try { File.Copy(f, dst, true); break; }
                catch (IOException) when (attempt < 10) { Thread.Sleep(300); }
            }
            done += new FileInfo(f).Length;
            progress(0.05 + 0.85 * done / Math.Max(1, total), "Copio i file");
        }

        progress(0.92, "Creo i collegamenti");
        RemoveOldLinks();
        CreateLink(StartMenuLink, InstalledExe);
        if (desktop) CreateLink(DesktopLink, InstalledExe);
        else TryDelete(DesktopLink);
        SetAutostart(autostart, InstalledExe);
        Register(total);
        progress(1, "Fatto");
    }

    /// <summary>Aggiorna la versione mostrata in Impostazioni > App (dopo un aggiornamento automatico).</summary>
    public static void TouchRegistration()
    {
        if (!IsInstalledCopy) return;
        try
        {
            using var k = Registry.CurrentUser.CreateSubKey(UninstallKey);
            k.SetValue("DisplayVersion", Version);
        }
        catch (Exception) { }
    }

    private static void Register(long bytes)
    {
        using var k = Registry.CurrentUser.CreateSubKey(UninstallKey);
        k.SetValue("DisplayName", "Relay");
        k.SetValue("DisplayVersion", Version);
        k.SetValue("Publisher", "Relay");
        k.SetValue("DisplayIcon", InstalledExe + ",0");
        k.SetValue("InstallLocation", InstallDir);
        k.SetValue("UninstallString", $"\"{InstalledExe}\" --uninstall");
        k.SetValue("QuietUninstallString", $"\"{InstalledExe}\" --uninstall --quiet");
        k.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"));
        k.SetValue("EstimatedSize", (int)(bytes / 1024), RegistryValueKind.DWord);
        k.SetValue("NoModify", 1, RegistryValueKind.DWord);
        k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
    }

    public static void SetAutostart(bool on, string exe)
    {
        using var k = Registry.CurrentUser.CreateSubKey(RunKey);
        if (on) k.SetValue("Ponte", $"\"{exe}\" --background");
        else k.DeleteValue("Ponte", false);
    }

    /// <summary>Toglie tutto. La cartella si cancella dopo l'uscita, con un comando che aspetta due secondi.</summary>
    public static void Uninstall(bool removeData)
    {
        StopOthers();
        try { SetAutostart(false, InstalledExe); } catch (Exception) { }
        try { Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false); } catch (Exception) { }
        TryDelete(StartMenuLink);
        TryDelete(DesktopLink);
        RemoveOldLinks();
        var targets = new List<string> { InstallDir };
        if (removeData) targets.Add(Settings.Dir);
        var cmd = "ping 127.0.0.1 -n 3 > nul & " + string.Join(" & ", targets.Select(t => $"rmdir /s /q \"{t}\""));
        var psi = new ProcessStartInfo("cmd.exe", "/c " + cmd)
        {
            UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Path.GetTempPath(),
        };
        Process.Start(psi);
    }

    public static void Launch(string exe, string args = "")
    {
        Process.Start(new ProcessStartInfo(exe, args) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(exe)! });
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch (Exception) { }
    }

    // ---------------------------------------------------------------- collegamenti (.lnk) via IShellLink

    private static void CreateLink(string link, string target)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(link)!);
        var sl = (IShellLinkW)new CShellLink();
        sl.SetPath(target);
        sl.SetWorkingDirectory(Path.GetDirectoryName(target)!);
        sl.SetDescription("Appunti, file, notifiche e cuffie tra i tuoi dispositivi");
        sl.SetIconLocation(target, 0);
        ((IPersistFile)sl).Save(link, true);
        Marshal.FinalReleaseComObject(sl);
    }

    [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
    private class CShellLink { }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
    private interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszFile, int cchMaxPath, IntPtr pfd, int fFlags);
        void GetIDList(out IntPtr ppidl);
        void SetIDList(IntPtr pidl);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszName, int cchMaxName);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszDir, int cchMaxPath);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszArgs, int cchMaxPath);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
        void GetHotkey(out short pwHotkey);
        void SetHotkey(short wHotkey);
        void GetShowCmd(out int piShowCmd);
        void SetShowCmd(int iShowCmd);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszIconPath, int cchIconPath, out int piIcon);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, int dwReserved);
        void Resolve(IntPtr hwnd, int fFlags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
    }
}
