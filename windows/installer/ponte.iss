; Installer di Ponte: per utente (niente amministratore), cartella scrivibile così gli aggiornamenti automatici funzionano.
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{6E1D0C2A-7B5F-4C33-9A41-50E7E0A1C0DE}
AppName=Ponte
AppVersion={#AppVersion}
AppVerName=Ponte {#AppVersion}
AppPublisher=Ponte
VersionInfoVersion={#AppVersion}.0
DefaultDirName={autopf}\Ponte
DefaultGroupName=Ponte
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\..
OutputBaseFilename=Ponte-Setup
SetupIconFile=..\Assets\ponte.ico
UninstallDisplayIcon={app}\Ponte.exe
WizardStyle=modern
Compression=lzma2/max
SolidCompression=yes
CloseApplications=no
RestartApplications=no

[Languages]
Name: "it"; MessagesFile: "compiler:Languages\Italian.isl"

[Tasks]
Name: "autostart"; Description: "Avvia Ponte all'accesso di Windows"
Name: "desktopicon"; Description: "Icona sul desktop"; Flags: unchecked

[Files]
Source: "..\..\out\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion createallsubdirs

[Icons]
Name: "{autoprograms}\Ponte"; Filename: "{app}\Ponte.exe"
Name: "{autodesktop}\Ponte"; Filename: "{app}\Ponte.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Ponte"; ValueData: """{app}\Ponte.exe"" --background"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "{app}\Ponte.exe"; Description: "Apri Ponte"; Flags: nowait postinstall skipifsilent

[Code]
procedure CloseRunning();
var R: Integer;
begin
  Exec('taskkill.exe', '/IM Ponte.exe /F', '', SW_HIDE, ewWaitUntilTerminated, R);
end;

function InitializeSetup(): Boolean;
begin
  CloseRunning();
  Result := True;
end;

function InitializeUninstall(): Boolean;
begin
  CloseRunning();
  Result := True;
end;
