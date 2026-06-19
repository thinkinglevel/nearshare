#define AppName "NearShare"
#ifndef AppVersion
#define AppVersion "1.0"
#endif
#ifndef SourceDir
#define SourceDir "..\publish\win-x64"
#endif
#ifndef OutputDir
#define OutputDir "..\..\..\dist\release"
#endif

[Setup]
AppId={{C9783411-CE58-43E0-9E83-F19AFB268F57}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=ThinkingLevel
AppPublisherURL=https://github.com/thinkinglevel/nearshare
AppSupportURL=https://github.com/thinkinglevel/nearshare/issues
AppUpdatesURL=https://github.com/thinkinglevel/nearshare/releases
DefaultDirName={localappdata}\Programs\NearShare
DefaultGroupName=NearShare
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename=nearshare-windows-v{#AppVersion}-x64
SetupIconFile=..\src\PCMobileLink.Windows.App\Assets\AppIcon.ico
UninstallDisplayIcon={app}\NearShare.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\NearShare"; Filename: "{app}\NearShare.exe"
Name: "{autodesktop}\NearShare"; Filename: "{app}\NearShare.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\NearShare.exe"; Description: "Launch NearShare"; Flags: nowait postinstall skipifsilent
