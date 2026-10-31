; Dream OS for Windows: the setup wizard (Inno Setup 6.7). Built by tools/build-installer.sh, which passes:
;   /DAppVersion=4.3.5            the version (root build.gradle.kts dreamVersion)
;   /DAppImage=<folder>           jpackage's app image (:app:createDistributable): Dream OS.exe, app\, runtime\
;   /DOutputDir=<folder>          where DreamOS-Windows-Setup.exe goes
; A test build (/DTestBuild=<scratch folder>) installs under its own AppId, puts its shortcuts and its "settings"
; in that scratch folder and looks for a different old MSI, so testing it never touches a real Dream OS.
;
; Per user, no admin prompt: the in-app updater runs the next setup silently (/SILENT /SUPPRESSMSGBOXES /NORESTART
; /CLOSEAPPLICATIONS) and it must never stop on a Windows admin prompt. For the same reason there is no all-users
; option: an all-users copy would need admin rights for every update, and both kinds side by side would be two
; entries in Settings > Apps.

#ifndef AppVersion
  #error Pass /DAppVersion=<version>
#endif
#ifndef AppImage
  #error Pass /DAppImage=<jpackage app image folder>
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif

#define AppName "Dream OS"
#define AppExe "Dream OS.exe"
#define AppUrl "https://github.com/DenseCow/dream-os"
; Created by the running app (app/.../selfcheck/RunningMark.kt). Case sensitive.
#define RunningMutex "DreamOS.Running"

#ifdef TestBuild
  #define AppGuid "40D99BB9-F7CC-485B-980A-30F8AE298D02"
  #define DisplayName "Dream OS (Setup Test)"
  #define DesktopDir TestBuild + "\Desktop"
  #define ProgramsDir TestBuild + "\Start Menu"
  #define SettingsDir TestBuild + "\Roaming\Dream OS"
  #define CacheDir TestBuild + "\Local\Dream OS"
  ; The test MSI made by the test (tools: see docs/port/package.md), never the real one.
  #ifndef OldMsiUpgradeCode
    #define OldMsiUpgradeCode "{00000000-0000-0000-0000-000000000000}"
  #endif
#else
  ; Never change it: this id is how a newer setup finds and updates the installed Dream OS.
  #define AppGuid "CDDCD539-1289-495C-BA93-93AE8FAA736B"
  #define DisplayName "Dream OS"
  #define DesktopDir "{autodesktop}"
  #define ProgramsDir "{autoprograms}"
  #define SettingsDir "{userappdata}\Dream OS"
  #define CacheDir "{localappdata}\Dream OS"
  ; Dream OS 4.3.x was a per-user MSI made by jpackage (upgrade code below, in %LOCALAPPDATA%\Dream OS).
  #define OldMsiUpgradeCode "{DC5E1C06-37F1-4EFD-8BB7-4FF298B9B024}"
#endif

[Setup]
AppId={{{#AppGuid}}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Dream OS
AppPublisherURL={#AppUrl}
AppSupportURL={#AppUrl}/issues
AppUpdatesURL={#AppUrl}/releases
AppCopyright=Dream OS contributors. GPL-3.0.
VersionInfoVersion={#AppVersion}
VersionInfoProductName={#AppName}
VersionInfoDescription=Dream OS Setup
VersionInfoCompany=Dream OS

PrivilegesRequired=lowest
; No space in the folder name: Java 17 only shares the app's own classes from the startup archive when the install path
; has none (docs/port/startup.md), about 1 s faster to the first frame. Everything the viewer sees still says Dream OS.
DefaultDirName={autopf}\DreamOS
UsePreviousAppDir=yes
; The folder page shows on a first install; an update keeps the folder chosen then.
DisableDirPage=auto
DisableProgramGroupPage=yes
DisableWelcomePage=no
UninstallDisplayName={#DisplayName}
UninstallDisplayIcon={app}\{#AppExe}
SetupIconFile=..\app\icons\dream_os.ico
SetupMutex=DreamOS.Setup
CloseApplications=yes
RestartApplications=no
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0

WizardStyle=modern dark includetitlebar
WizardImageFile=images\wizard-*.png
WizardSmallImageFile=images\small-*.png

OutputDir={#OutputDir}
#ifdef TestBuild
OutputBaseFilename=DreamOS-Windows-Setup-Test
#else
OutputBaseFilename=DreamOS-Windows-Setup
#endif
Compression=lzma2/max
SolidCompression=yes
LZMAUseSeparateProcess=yes

[Messages]
SetupWindowTitle=%1 Setup
UninstallAppTitle=Uninstall Dream OS
UninstallAppFullTitle=Uninstall %1
ButtonBack=&Back
ButtonNext=&Next
ButtonBrowse=&Browse
ExitSetupTitle=Leave Setup
ExitSetupMessage=Dream OS isn't installed yet. If you leave now, nothing is changed on this PC.%n%nLeave Setup?
WelcomeLabel1=Welcome to Dream OS
WelcomeLabel2=Setup will install Dream OS {#AppVersion} on this PC and add it to your Start menu.
WizardSelectDir=Choose Where to Install
SelectDirDesc=Pick the folder for Dream OS.
SelectDirLabel3=Dream OS will be installed in this folder.
SelectDirBrowseLabel=Dream OS and its updates stay in this folder.
DiskSpaceMBLabel=Dream OS needs [mb] MB of free space.
DiskSpaceGBLabel=Dream OS needs [gb] GB of free space.
WizardSelectTasks=Shortcuts
SelectTasksDesc=Choose how you'd like to open Dream OS.
SelectTasksLabel2=Dream OS always gets a Start menu entry. You can add one to your desktop too.
WizardReady=Ready to Install
ReadyLabel1=Setup has everything it needs.
ReadyLabel2a=Here's what Setup will do.
ReadyLabel2b=Here's what Setup will do.
ReadyMemoDir=Install folder:
ReadyMemoTasks=Shortcuts:
WizardPreparing=Getting Ready
PreparingDesc=Setup is getting ready to install Dream OS.
ApplicationsFound=These apps are using files Setup needs to replace. Setup can close them for you.
ApplicationsFound2=These apps are using files Setup needs to replace. Setup can close them for you.
CloseApplications=&Close Them for Me
DontCloseApplications=&Leave Them Open (You May Need to Restart Your PC)
ErrorCloseApplications=Setup couldn't close every app. Close Dream OS yourself, then click Next.
StatusClosingApplications=Closing Dream OS...
WizardInstalling=Installing
InstallingLabel=Setup is copying Dream OS to this PC.
StatusRunProgram=Finishing up...
FinishedHeadingLabel=Dream OS Is Installed
FinishedLabelNoIcons=Dream OS {#AppVersion} is installed on this PC.
FinishedLabel=Dream OS {#AppVersion} is installed on this PC. You'll find it in your Start menu.
ClickFinish=
ClickNext=
ConfirmUninstall=Remove Dream OS from this PC?
UninstallStatusLabel=Removing Dream OS from this PC.
WizardUninstalling=Uninstalling
StatusUninstalling=Removing Dream OS...
UninstalledAll=Dream OS was removed from this PC.
UninstalledMost=Dream OS was removed from this PC.%n%nA few files couldn't be deleted. You can delete them yourself from the install folder.
UninstallAppRunningError=Dream OS is open. Close it, then click OK.

[Tasks]
Name: desktopicon; Description: "Create a Desktop Shortcut"

[InstallDelete]
; An update replaces the program files whole: the jar names change from version to version, so a plain copy over the
; top would leave the old ones behind.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Excludes: "__pycache__"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{#ProgramsDir}\{#AppName}"; Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"
Name: "{#DesktopDir}\{#AppName}"; Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
; The startup archive for this folder (StartArchive, about 15 s, no window); also during silent updates. Without it the
; app writes one itself in the background later.
Filename: "{app}\{#AppExe}"; Parameters: "--prepare"; StatusMsg: "Getting Dream OS ready to start quickly…"; Flags: runhidden waituntilterminated
; Checked on the Finish page. A silent update (the in-app updater) opens Dream OS again when it's done; /NOLAUNCH
; stops that (tests).
Filename: "{app}\{#AppExe}"; Description: "Launch Dream OS"; Flags: postinstall nowait; Check: MayLaunch

[UninstallDelete]
; Python writes __pycache__ folders next to the bundled packages on first use; these folders are Dream OS's own.
Type: filesandordirs; Name: "{app}\app\resources\python"
Type: filesandordirs; Name: "{app}\app\resources\python-site"
; The startup archive written on this PC (--prepare or the app's own background write).
Type: files; Name: "{app}\app\dream.jsa*"

[Code]
const
  RunningMutex = '{#RunningMutex}';
  OldMsiUpgradeCode = '{#OldMsiUpgradeCode}';

function MsiEnumRelatedProducts(UpgradeCode: String; Reserved: Cardinal; Index: Cardinal; ProductBuf: String): Cardinal;
  external 'MsiEnumRelatedProductsW@msi.dll stdcall';
function MsiGetProductInfo(Product: String; Prop: String; Value: String; var ValueLen: Cardinal): Cardinal;
  external 'MsiGetProductInfoW@msi.dll stdcall';

var
  OldMsiCode: String;      // product code of an installed 4.3.x MSI, '' when there is none
  OldMsiFolder: String;    // where that MSI installed Dream OS
  RemoveSettings: Boolean; // uninstall: the viewer ticked Also Remove My Settings
  RemovingPage: TOutputMarqueeProgressWizardPage;

function UntilNul(S: String): String;
var
  I: Integer;
begin
  I := Pos(#0, S);
  if I > 0 then Result := Copy(S, 1, I - 1) else Result := Trim(S);
end;

// The first installed product of the old MSI's upgrade code, through Windows Installer (msi.dll).
procedure FindOldMsi;
var
  Buf, Loc: String;
  Len: Cardinal;
begin
  OldMsiCode := '';
  OldMsiFolder := '';
  Buf := StringOfChar(#0, 39);
  if MsiEnumRelatedProducts(OldMsiUpgradeCode, 0, 0, Buf) <> 0 then Exit;
  OldMsiCode := UntilNul(Buf);
  Len := 1024;
  Loc := StringOfChar(#0, Len);
  if MsiGetProductInfo(OldMsiCode, 'InstallLocation', Loc, Len) = 0 then
    OldMsiFolder := RemoveBackslashUnlessRoot(UntilNul(Loc));
  Log('Old Dream OS MSI: ' + OldMsiCode + ' in ' + OldMsiFolder);
end;

function HasParam(Name: String): Boolean;
var
  I: Integer;
begin
  Result := False;
  for I := 1 to ParamCount do
    if CompareText(ParamStr(I), Name) = 0 then Result := True;
end;

function MayLaunch: Boolean;
begin
  Result := not (WizardSilent and HasParam('/NOLAUNCH'));
end;

// Waits up to Seconds for a closing Dream OS to finish. True once it's gone.
function WaitForAppToClose(Seconds: Integer): Boolean;
var
  I: Integer;
begin
  for I := 1 to Seconds * 4 do begin
    if not CheckForMutexes(RunningMutex) then begin
      Result := True;
      Exit;
    end;
    Sleep(250);
  end;
  Result := not CheckForMutexes(RunningMutex);
end;

function InitializeSetup: Boolean;
begin
  FindOldMsi;
  Result := True;
end;

procedure InitializeWizard;
begin
  RemovingPage := CreateOutputMarqueeProgressPage('Removing the Older Dream OS',
    'Your profiles and settings stay as they are.');
  if OldMsiCode <> '' then
    WizardForm.WelcomeLabel2.Caption :=
      'Setup will replace the Dream OS on this PC with version {#AppVersion}. Your profiles and settings stay as they are.'
  else if WizardForm.PrevAppDir <> '' then
    WizardForm.WelcomeLabel2.Caption :=
      'Setup will update Dream OS to version {#AppVersion}. Your profiles and settings stay as they are.';
end;

function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo, MemoComponentsInfo,
  MemoGroupInfo, MemoTasksInfo: String): String;
begin
  Result := MemoDirInfo;
  if MemoTasksInfo <> '' then
    Result := Result + NewLine + NewLine + MemoTasksInfo;
  if OldMsiCode <> '' then
    Result := Result + NewLine + NewLine + 'First, Setup removes the older Dream OS from:' + NewLine + Space + OldMsiFolder;
end;

// Moves Name (a folder in the old install folder) aside so the MSI's uninstall can't delete it. jpackage's MSI
// deletes its whole install folder on uninstall (WixRemoveFolderEx), and the app keeps its Cache and stream folders
// there.
procedure KeepAside(Keep, Name: String);
begin
  if DirExists(OldMsiFolder + '\' + Name) then begin
    ForceDirectories(Keep);
    if not RenameFile(OldMsiFolder + '\' + Name, Keep + '\' + Name) then
      Log('Couldn''t keep ' + Name + ' aside');
  end;
end;

procedure PutBack(Keep, Name: String);
begin
  if DirExists(Keep + '\' + Name) then begin
    ForceDirectories(OldMsiFolder);
    if not RenameFile(Keep + '\' + Name, OldMsiFolder + '\' + Name) then
      Log('Couldn''t put ' + Name + ' back');
  end;
end;

function RemoveOldMsi: String;
var
  Code: Integer;
  Keep: String;
begin
  Result := '';
  Keep := '';
  if OldMsiFolder <> '' then begin
    Keep := OldMsiFolder + '.setup-keep';
    KeepAside(Keep, 'Cache');
    KeepAside(Keep, 'stream');
  end;
  RemovingPage.Show;
  RemovingPage.Animate;
  try
    if not Exec(ExpandConstant('{sys}\msiexec.exe'), '/x ' + OldMsiCode + ' /qn /norestart REBOOT=ReallySuppress', '',
                SW_HIDE, ewWaitUntilTerminated, Code) then
      Code := -1;
  finally
    RemovingPage.Hide;
  end;
  Log('msiexec /x ' + OldMsiCode + ' exit ' + IntToStr(Code));
  if Keep <> '' then begin
    PutBack(Keep, 'Cache');
    PutBack(Keep, 'stream');
    RemoveDir(Keep);
  end;
  // 1605: it was already gone. 3010 / 1641: removed, Windows wants a restart for a file it couldn't delete.
  if (Code <> 0) and (Code <> 1605) and (Code <> 3010) and (Code <> 1641) then
    Result := 'Setup couldn''t remove the older Dream OS (Windows Installer error ' + IntToStr(Code) + ').' + #13#10#13#10 +
              'Uninstall Dream OS in Settings > Apps, then run this setup again.';
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  // The in-app updater starts this setup and then quits: give the closing app a moment before Windows is asked to
  // close it.
  if WizardSilent and CheckForMutexes(RunningMutex) then
    WaitForAppToClose(30);
  if OldMsiCode <> '' then
    Result := RemoveOldMsi;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if (CurPageID = wpFinished) and WizardIsTaskSelected('desktopicon') then
    WizardForm.FinishedLabel.Caption := 'Dream OS {#AppVersion} is installed on this PC. You''ll find it in your Start menu and on your desktop.';
end;

// ---- Uninstall -------------------------------------------------------------------------------------------------

// Asks once, before anything is removed: a checkbox, off unless the viewer ticks it.
function AskUninstall: Boolean;
var
  Form: TSetupForm;
  Info, Detail: TNewStaticText;
  Box: TNewCheckBox;
  Ok, Cancel: TNewButton;
begin
  Form := CreateCustomForm(ScaleX(440), ScaleY(196), False, True);
  try
    Form.Caption := 'Uninstall Dream OS';

    Info := TNewStaticText.Create(Form);
    Info.Parent := Form;
    Info.Left := ScaleX(20);
    Info.Top := ScaleY(20);
    Info.Width := Form.ClientWidth - ScaleX(40);
    Info.AutoSize := False;
    Info.WordWrap := True;
    Info.Height := ScaleY(48);
    Info.Caption := 'Dream OS will be removed from this PC. Your profiles, accounts and settings stay, so they''re all back if you install it again.';

    Box := TNewCheckBox.Create(Form);
    Box.Parent := Form;
    Box.Left := ScaleX(20);
    Box.Top := Info.Top + Info.Height + ScaleY(12);
    Box.Width := Form.ClientWidth - ScaleX(40);
    Box.Height := ScaleY(20);
    Box.Caption := 'Also Remove My Settings';
    Box.Checked := False;

    Detail := TNewStaticText.Create(Form);
    Detail.Parent := Form;
    Detail.Left := Box.Left + ScaleX(18);
    Detail.Top := Box.Top + Box.Height + ScaleY(2);
    Detail.Width := Box.Width - ScaleX(18);
    Detail.AutoSize := False;
    Detail.WordWrap := True;
    Detail.Height := ScaleY(32);
    Detail.Caption := 'Deletes your profiles, accounts, settings and saved images from this PC.';

    Cancel := TNewButton.Create(Form);
    Cancel.Parent := Form;
    Cancel.Width := ScaleX(88);
    Cancel.Height := ScaleY(26);
    Cancel.Left := Form.ClientWidth - ScaleX(20) - Cancel.Width;
    Cancel.Top := Form.ClientHeight - ScaleY(16) - Cancel.Height;
    Cancel.Caption := 'Cancel';
    Cancel.ModalResult := mrCancel;
    Cancel.Cancel := True;

    Ok := TNewButton.Create(Form);
    Ok.Parent := Form;
    Ok.Width := ScaleX(88);
    Ok.Height := ScaleY(26);
    Ok.Left := Cancel.Left - ScaleX(10) - Ok.Width;
    Ok.Top := Cancel.Top;
    Ok.Caption := 'Uninstall';
    Ok.ModalResult := mrOk;
    Ok.Default := True;

    Form.ActiveControl := Ok;
    Result := Form.ShowModal = mrOk;
    RemoveSettings := Result and Box.Checked;
  finally
    Form.Free;
  end;
end;

// Inno asks "Remove Dream OS from this PC?" after InitializeUninstall, which would be a second question right after
// this one. So once the viewer has answered here, this uninstaller hands over to a silent one (a progress window, no
// more questions; /DREAMASKED carries the answer) and stops.
function InitializeUninstall: Boolean;
var
  Params: String;
  Code: Integer;
begin
  Result := True;
  RemoveSettings := False;
  if HasParam('/DREAMASKED') then begin
    RemoveSettings := HasParam('/REMOVESETTINGS');
    Exit;
  end;
  if UninstallSilent then begin
    WaitForAppToClose(30);
    Exit;
  end;
  while CheckForMutexes(RunningMutex) do
    if MsgBox('Dream OS is open. Close it, then click OK.', mbInformation, MB_OKCANCEL) <> IDOK then begin
      Result := False;
      Exit;
    end;
  if not AskUninstall then begin
    Result := False;
    Exit;
  end;
  Params := '/SILENT /NORESTART /DREAMASKED';
  if RemoveSettings then Params := Params + ' /REMOVESETTINGS';
  // If the silent run can't start, this one carries on and Inno asks its own question once more.
  if Exec(ExpandConstant('{uninstallexe}'), Params, '', SW_SHOW, ewNoWait, Code) then
    Result := False;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if (CurUninstallStep = usPostUninstall) and RemoveSettings then begin
    Log('Removing settings and cache');
    DelTree(ExpandConstant('{#SettingsDir}'), True, True, True);
    DelTree(ExpandConstant('{#CacheDir}'), True, True, True);
  end;
  // The silent run started above says when it's done; a plain silent uninstall (scripts, tests) stays quiet.
  if (CurUninstallStep = usDone) and HasParam('/DREAMASKED') then
    MsgBox('Dream OS was removed from this PC.', mbInformation, MB_OK);
end;
