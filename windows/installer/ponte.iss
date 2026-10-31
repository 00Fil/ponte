; Ponte-Setup.exe: contenitore. Estrae Ponte in una cartella temporanea (con la barra di avanzamento di Inno, così si vede
; subito qualcosa) e poi lancia "Ponte.exe --setup", che mostra l'installazione con lo stile dell'app.
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppName=Ponte
AppVersion={#AppVersion}
AppPublisher=Ponte
VersionInfoVersion={#AppVersion}.0
VersionInfoDescription=Installa Ponte
CreateAppDir=no
Uninstallable=no
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
OutputDir=..\..
OutputBaseFilename=Ponte-Setup
SetupIconFile=..\Assets\relay.ico
WizardStyle=modern
DisableWelcomePage=yes
DisableReadyPage=yes
DisableFinishedPage=yes
DisableDirPage=yes
DisableProgramGroupPage=yes
SetupLogging=no
; Estrazione veloce: l'eseguibile pesa un po' di più ma parte in pochi secondi.
Compression=lzma2/fast
SolidCompression=yes

[Messages]
SetupWindowTitle=Relay
WizardInstalling=Preparo Ponte
InstallingLabel=Un attimo, apro l'installazione di Ponte.

[Files]
Source: "..\..\out\*"; DestDir: "{tmp}\payload"; Flags: recursesubdirs createallsubdirs ignoreversion

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  Code: Integer;
  Base: String;
begin
  if CurStep = ssPostInstall then
  begin
    Base := ExpandConstant('{tmp}\payload');
    WizardForm.Hide;
    if not Exec(Base + '\Ponte.exe', '--setup', Base, SW_SHOWNORMAL, ewWaitUntilTerminated, Code) then
      MsgBox('Non riesco ad avviare l''installazione di Ponte (errore ' + IntToStr(Code) + ').', mbError, MB_OK);
  end;
end;
