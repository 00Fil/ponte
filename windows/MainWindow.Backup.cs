using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Ponte;

/// <summary>La pagina Backup (BackupScreen su Android): spazio sul server, cartelle, copia, verifica e ripristino.</summary>
public sealed partial class MainWindow
{
    private bool _backupOpen;
    private string? _backupFrom;

    private void OpenBackup()
    {
        _backupOpen = true;
        _r.Backup.Prepare();
        _ = _r.Backup.RefreshSources();
    }

    private void RenderBackup(StackPanel h)
    {
        var b = _r.Backup;
        var busy = b.Busy;
        h.Children.Add(BackRow("Condividi", "Backup", () => { _backupOpen = false; RenderShare(); }));

        // ------------------------------------------------ spazio
        var space = new StackPanel { Spacing = 10 };
        var refresh = BubbleButton(Center(Icons.Of(Ico.Refresh, B("LabelPrimary"), 16)), 34, () => { _ = b.RefreshStorage(); _ = b.RefreshSources(); }, "Aggiorna");
        space.Children.Add(Cols("*a", 8, BackupTitle("Spazio sul server"), refresh));
        if (b.Space is { } s)
        {
            space.Children.Add(Progress((s.Total - s.Free) / (double)Math.Max(s.Total, 1), null, 10));
            space.Children.Add(Semi($"{Size(s.Free)} liberi su {Size(s.Total)}", 16));
            space.Children.Add(T($"Questo PC: {Size(s.DeviceBytes)} · {s.DeviceFiles} file", 13, "LabelSecondary"));
        }
        if (b.SpaceError.Length > 0) space.Children.Add(Wrapped(b.SpaceError, "CriticalOnSubtle"));
        else if (b.Space == null) space.Children.Add(T("Controllo il server…", 13, "LabelTertiary"));
        h.Children.Add(Panelled(space, new Thickness(20)));

        // ------------------------------------------------ cartelle
        var folders = new StackPanel { Spacing = 12 };
        var add = BubbleButton(Center(Icons.Of(Ico.FolderPlus, B("LabelPrimary"), 18)), 34, () => _ = AddBackupFolderAsync(), "Aggiungi cartella");
        add.IsEnabled = !busy || b.State == Backup.Phase.Scanning;
        folders.Children.Add(Cols("*a", 8, BackupTitle("Cartelle"), add));
        foreach (var f in b.Folders)
        {
            var folder = f;
            var icon = Icons.Of(Ico.Folder, B(f.On ? "AccentDefault" : "LabelTertiary"), 22);
            icon.VerticalAlignment = VerticalAlignment.Center;
            var path = T(f.Path, 13, "LabelTertiary");
            path.TextTrimming = TextTrimming.CharacterEllipsis;
            var text = VStack(0, Semi(f.Key, 16, f.On ? "LabelPrimary" : "LabelSecondary"), path);
            text.VerticalAlignment = VerticalAlignment.Center;
            Button? remove = null;
            if (!Backup.IsKnown(f.Key))
            {
                remove = Bare(Center(Icons.Of(Ico.Close, B("LabelTertiary"), 14)), () => { b.RemoveFolder(folder.Key); RenderShare(); }, "Togli " + f.Key, 14);
                remove.Width = remove.Height = 28;
                remove.VerticalAlignment = VerticalAlignment.Center;
                remove.IsEnabled = !busy || b.State == Backup.Phase.Scanning;
            }
            var on = GlassSwitch(f.On, v => { b.SetFolder(folder.Key, v); DispatcherQueue.TryEnqueue(RenderShare); }, f.Key);
            on.IsEnabled = !busy || b.State == Backup.Phase.Scanning;
            on.VerticalAlignment = VerticalAlignment.Center;
            folders.Children.Add(Cols("a*aa", 12, icon, text, remove, on));
        }
        h.Children.Add(Panelled(folders, new Thickness(20)));

        // ------------------------------------------------ copia e verifica
        var copy = new StackPanel { Spacing = 10 };
        copy.Children.Add(BackupTitle("Backup"));
        var noRoom = false;
        if (b.Plan is { } plan)
        {
            copy.Children.Add(Semi($"{plan.ToSend} file da copiare · {Size(plan.ToSendBytes)}", 16));
            copy.Children.Add(T($"{plan.Files} file nelle cartelle · {Size(plan.Bytes)}", 13, "LabelSecondary"));
            noRoom = b.Space is { } sp && plan.ToSendBytes + sp.Reserve > sp.Free;
            if (noRoom) copy.Children.Add(Wrapped("Spazio insufficiente sul server", "CriticalOnSubtle"));
        }
        if (_r.S.LastBackup > 0) copy.Children.Add(T("Ultimo backup: " + BackupDay(_r.S.LastBackup), 13, "LabelTertiary"));
        if (_r.S.LastVerifiedOk > 0) copy.Children.Add(T("Ultima verifica: " + BackupDay(_r.S.LastVerifiedOk), 13, "LabelTertiary"));

        if (busy && b.State != Backup.Phase.Scanning)
        {
            var bar = new ProgressBar { Maximum = 1, Height = 6 };
            var step = Wrapped(b.Step, "LabelPrimary");
            step.MaxLines = 2;
            var count = T("", 13, "LabelSecondary");
            void Upd()
            {
                bar.IsIndeterminate = b.Total == 0;
                if (b.Total > 0) bar.Value = b.Done / (double)b.Total;
                step.Text = b.Step;
                count.Text = b.Total > 0 ? $"{b.Done}/{b.Total} file · {Size(b.Bytes)}" : "";
            }
            Upd();
            EnsureTicks();
            _ticks.Add(Upd);
            copy.Children.Add(bar);
            copy.Children.Add(step);
            copy.Children.Add(count);
            copy.Children.Add(Stretched(Btn("Ferma", () => b.Cancel(), "SecondaryButton")));
        }
        else
        {
            if (b.Step.Length > 0) copy.Children.Add(Wrapped(b.Step, b.State == Backup.Phase.Failed ? "CriticalOnSubtle" : "LabelSecondary"));
            var start = Stretched(Btn("Esegui backup", () => b.Start(), "PrimaryButton"));
            var verify = Stretched(Btn("Verifica", () => b.Verify(), "SecondaryButton"));
            start.IsEnabled = !busy && !noRoom && b.Folders.Any(f => f.On);
            verify.IsEnabled = !busy && b.Folders.Any(f => f.On);
            copy.Children.Add(Cols("**", 8, start, verify));
        }
        var failed = b.Failed;
        if (failed.Count > 0)
        {
            copy.Children.Add(Semi($"{failed.Count} non copiati", 13, "CriticalOnSubtle"));
            foreach (var line in failed.Take(5)) copy.Children.Add(Wrapped(line, "LabelTertiary"));
        }
        h.Children.Add(Panelled(copy, new Thickness(20)));

        // ------------------------------------------------ esito della verifica
        if (b.Last is { } report)
        {
            var result = new StackPanel { Spacing = 8 };
            var dot = new Ellipse { Width = 10, Height = 10, Fill = B(report.Ok ? "Positive" : "Caution"), VerticalAlignment = VerticalAlignment.Center };
            result.Children.Add(HStack(8, dot, BackupTitle(report.Ok ? "Verifica superata" : "Verifica non superata")));
            foreach (var line in report.Lines) result.Children.Add(Wrapped(line, "LabelSecondary"));
            h.Children.Add(Panelled(result, new Thickness(20)));
        }

        // ------------------------------------------------ ripristino
        var back = new StackPanel { Spacing = 10 };
        back.Children.Add(BackupTitle("Ripristino"));
        var sources = b.Sources;
        var from = sources.FirstOrDefault(x => x.Device == _backupFrom) ?? sources.FirstOrDefault();
        if (sources.Count > 1)
        {
            var chips = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            foreach (var src in sources)
            {
                var id = src.Device;
                chips.Children.Add(Chip(src.Name, src == from, () => { _backupFrom = id; RenderShare(); }));
            }
            back.Children.Add(HScroll(chips));
        }
        if (from != null) back.Children.Add(T($"{from.Name} · {from.Files} file", 13, "LabelSecondary"));
        var restore = Stretched(Btn("Ripristina i file mancanti", () => b.Restore(from?.Device), "SecondaryButton"));
        restore.IsEnabled = !busy && from != null;
        back.Children.Add(restore);
        h.Children.Add(Panelled(back, new Thickness(20)));
    }

    private TextBlock BackupTitle(string text)
    {
        var t = Semi(text, 20);
        t.VerticalAlignment = VerticalAlignment.Center;
        return t;
    }

    private TextBlock Wrapped(string text, string color)
    {
        var t = T(text, 13, color);
        t.TextWrapping = TextWrapping.Wrap;
        return t;
    }

    private static Button Stretched(Button b)
    {
        b.HorizontalAlignment = HorizontalAlignment.Stretch;
        return b;
    }

    private static string BackupDay(long ms) => Loc(ms).ToString("d MMM yyyy, HH:mm", It);

    private async Task AddBackupFolderAsync()
    {
        var picker = new FolderPicker();
        picker.FileTypeFilter.Add("*");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
        var folder = await picker.PickSingleFolderAsync();
        if (folder == null || folder.Path.Length == 0) return;
        _r.Backup.AddFolder(folder.Path);
        RenderShare();
    }
}
