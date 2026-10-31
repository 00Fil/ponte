; Ponte-Setup.exe: solo un contenitore. Estrae Ponte in una cartella temporanea e lancia "Ponte.exe --setup",
; che mostra l'installazione con lo stile dell'app e copia i file in %LOCALAPPDATA%\Programs\Ponte.
; Inno Setup non mostra nessuna finestra: InitializeSetup restituisce False dopo che Ponte ha finito.
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
SetupIconFile=..\Assets\ponte.ico
Compression=lzma2/normal
SolidCompression=yes

[Files]
Source: "..\..\out\*"; DestDir: "{tmp}\payload"; Flags: dontcopy recursesubdirs createallsubdirs

[Code]
procedure ExtractLevel(const Pattern: String);
begin
  try
    ExtractTemporaryFiles(Pattern);
  except
    { nessun file a questo livello }
  end;
end;

function InitializeSetup(): Boolean;
var
  Code: Integer;
  Base: String;
begin
  Base := ExpandConstant('{tmp}\payload');
  ExtractLevel('{tmp}\payload\*');
  { Se * non scende nelle sottocartelle, si estraggono livello per livello. }
  if not FileExists(Base + '\Assets\Web\gradient.html') then
  begin
    ExtractLevel('{tmp}\payload\*\*');
    ExtractLevel('{tmp}\payload\*\*\*');
    ExtractLevel('{tmp}\payload\*\*\*\*');
    ExtractLevel('{tmp}\payload\*\*\*\*\*');
  end;
  if not Exec(Base + '\Ponte.exe', '--setup', Base, SW_SHOWNORMAL, ewWaitUntilTerminated, Code) then
    MsgBox('Non riesco ad avviare l''installazione di Ponte (errore ' + IntToStr(Code) + ').', mbError, MB_OK);
  Result := False;
end;
