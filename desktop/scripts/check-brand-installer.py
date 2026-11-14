#!/usr/bin/env python3
"""Run the production NSIS shortcut helpers against isolated native Windows fixtures.

Requires the NSIS/utils.nsh already bootstrapped by the pinned Tauri build. No app
installation, production uninstall key, real shortcut, account or note is touched.
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid


def main():
    if os.name != 'nt': raise SystemExit('This native NSIS test requires Windows')
    import winreg
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--nsis', type=Path, default=Path(os.environ['LOCALAPPDATA'])/'tauri/NSIS/makensis.exe')
    parser.add_argument('--utils', type=Path, default=root/'desktop/src-tauri/target/release/nsis/x64/utils.nsh')
    args = parser.parse_args()
    run_id = str(uuid.uuid4())
    stage = root/'.local/brand-installer-validation'/run_id
    stage.mkdir(parents=True)
    buffer = ctypes.create_unicode_buffer(32768)
    assert ctypes.windll.shell32.SHGetFolderPathW(None, 2, None, 0, buffer) == 0
    programs = Path(buffer.value).resolve()
    shortcuts = programs/('DJHX Memo Brand Validation '+run_id)
    shortcuts.mkdir()
    (shortcuts/'.fixture-owner').write_text(run_id, encoding='ascii')
    registry = 'Software\\DJHXMemo\\BrandValidation010\\'+run_id
    template = (root/'desktop/src-tauri/windows/installer.nsi').read_text(encoding='utf-8')
    helpers = template[template.index('; Store only a shortcut actually owned'):]
    header = r'''
Unicode true
SilentInstall silent
RequestExecutionLevel user
OutFile "@STAGE@\fixture.exe"
!include LogicLib.nsh
!include FileFunc.nsh
!include "Win\COM.nsh"
!include "@UTILS@"
!define MAINBINARYNAME "fixture"
!define UNINSTKEY "@KEY@"
Var PreviousBrandName
Var DiscoveredStartShortcut
Var BrandSearchBudget
Var BrandSearchDepth
!macro AssertEqual actual expected reason
  ${If} "${actual}" != "${expected}"
    FileOpen $9 "$EXEDIR\failure.txt" w
    FileWrite $9 "${reason}"
    FileClose $9
    SetErrorLevel 1
    Quit
  ${EndIf}
!macroend
Section
  SetShellVarContext current
  SetRegView 64
  StrCpy $INSTDIR "$EXEDIR\app"
  CreateDirectory "$INSTDIR"
  FileOpen $9 "$INSTDIR\fixture.exe" w
  FileWrite $9 "synthetic target"
  FileClose $9
  FileOpen $9 "$INSTDIR\other.exe" w
  FileWrite $9 "synthetic other target"
  FileClose $9
  CreateDirectory "@SHORTCUTS@\Custom\Nested"
  CreateDirectory "@SHORTCUTS@\Selected"
  CreateShortCut "@SHORTCUTS@\Custom\Nested\DJHX Memo.lnk" "$INSTDIR\fixture.exe"
  StrCpy $PreviousBrandName "DJHX Memo"
  StrCpy $DiscoveredStartShortcut ""
  StrCpy $BrandSearchBudget 4096
  StrCpy $BrandSearchDepth 0
  Push "@SHORTCUTS@"
  Call DiscoverOwnedStartShortcut
  !insertmacro AssertEqual "$DiscoveredStartShortcut" "@SHORTCUTS@\Custom\Nested\DJHX Memo.lnk" "custom-folder discovery"
  Push "$DiscoveredStartShortcut"
  Push "@SHORTCUTS@\Selected\阳墨随记.lnk"
  Push "StartMenuShortcut"
  Call MigrateOwnedBrandShortcut
  ReadRegStr $R7 HKCU "${UNINSTKEY}" "StartMenuShortcut"
  !insertmacro AssertEqual "$R7" "@SHORTCUTS@\Selected\阳墨随记.lnk" "actual migrated path"
  ReadRegStr $R7 HKCU "${UNINSTKEY}" "ShortcutFolder"
  !insertmacro AssertEqual "$R7" "DJHX Memo Brand Validation @ID@\Selected" "actual folder metadata"
  CreateShortCut "@SHORTCUTS@\Custom\Nested\DJHX Memo.lnk" "$INSTDIR\fixture.exe"
  CreateShortCut "@SHORTCUTS@\Selected\Collision.lnk" "$INSTDIR\other.exe"
  Push "@SHORTCUTS@\Custom\Nested\DJHX Memo.lnk"
  Push "@SHORTCUTS@\Selected\Collision.lnk"
  Push "StartMenuShortcut"
  Call MigrateOwnedBrandShortcut
  ReadRegStr $R7 HKCU "${UNINSTKEY}" "StartMenuShortcut"
  !insertmacro AssertEqual "$R7" "@SHORTCUTS@\Custom\Nested\DJHX Memo.lnk" "conflict retains original path"
  !insertmacro IsShortcutTarget "@SHORTCUTS@\Selected\Collision.lnk" "$INSTDIR\other.exe"
  Pop $R7
  !insertmacro AssertEqual "$R7" "1" "foreign destination untouched"
  CreateShortCut "@SHORTCUTS@\Selected\DJHX Memo.lnk" "$INSTDIR\fixture.exe"
  Push "@SHORTCUTS@\Selected\DJHX Memo.lnk"
  Push "@SHORTCUTS@\Selected\DJHX MEMO.lnk"
  Push "DesktopShortcut"
  Call MigrateOwnedBrandShortcut
  StrCpy $DiscoveredStartShortcut ""
  StrCpy $BrandSearchBudget 0
  Push "@SHORTCUTS@"
  Call DiscoverOwnedStartShortcut
  !insertmacro AssertEqual "$DiscoveredStartShortcut" "" "bounded search exhaustion"
  FileOpen $9 "$EXEDIR\passed.txt" w
  FileWrite $9 "native migration, conflict ownership, folder metadata and budget passed"
  FileClose $9
SectionEnd
'''
    substitutions = {'@STAGE@':str(stage),'@UTILS@':str(args.utils.resolve()),'@KEY@':registry,'@SHORTCUTS@':str(shortcuts),'@ID@':run_id}
    for key,value in substitutions.items(): header=header.replace(key,value)
    source = stage/'fixture.nsi'
    source.write_text(header+helpers, encoding='utf-8-sig')
    startup = subprocess.STARTUPINFO(); startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW; startup.wShowWindow=0
    try:
        compiled = subprocess.run([str(args.nsis),'/V2',str(source)],capture_output=True,timeout=45,startupinfo=startup)
        (stage/'compile.log').write_bytes(compiled.stdout+compiled.stderr)
        assert compiled.returncode == 0, 'NSIS compilation failed; see isolated compile.log'
        result = subprocess.run([str(stage/'fixture.exe')],timeout=45,startupinfo=startup)
        assert result.returncode == 0 and (stage/'passed.txt').is_file(), 'Native shortcut assertion failed; see isolated failure.txt'
        case_names = [p.name for p in (shortcuts/'Selected').iterdir()]
        assert 'DJHX MEMO.lnk' in case_names, 'Case-only brand rename did not update the visible filename'
        receipt={'native_shortcut_helpers':True,'custom_folder':True,'foreign_collision_preserved':True,'case_rename':True,'bounded_search':True}
        (stage/'receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
        print('PASS native NSIS brand helpers; isolated receipt:',stage.relative_to(root).as_posix())
    finally:
        # Exact scratch key, no recursive registry removal or real uninstall identity.
        assert re.fullmatch(r'Software\\DJHXMemo\\BrandValidation010\\[a-f0-9-]{36}',registry)
        try: winreg.DeleteKeyEx(winreg.HKEY_CURRENT_USER,registry,winreg.KEY_WOW64_64KEY)
        except FileNotFoundError: pass
        assert shortcuts.resolve().parent == programs and shortcuts.name == 'DJHX Memo Brand Validation '+run_id
        assert (shortcuts/'.fixture-owner').read_text(encoding='ascii') == run_id
        shutil.rmtree(shortcuts)


if __name__=='__main__': main()
