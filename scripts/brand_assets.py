"""Deterministic adapters for pinned rendered brand packs; Python standard library only."""
import argparse
import base64
import hashlib
import html
import json
from pathlib import Path, PurePosixPath
import re
import struct
import xml.etree.ElementTree as ET
from zipfile import ZipFile
import zlib

ROOT = Path(__file__).resolve().parent.parent
ALLOWED = {
    'assets/brand/'+name for name in ('master.svg','monochrome.svg','design-sheet.svg','design-sheet.png')
} | {'assets/brand/png/icon-'+str(n)+'.png' for n in (16,24,32,48,64,128,256,512,1024)} | {
    'desktop/src-tauri/icons/'+name for name in ('32x32.png','128x128.png','128x128@2x.png','icon.ico','icon.png',
        'tray-black.png','tray-white.png','tray-black.rgba','tray-white.rgba')
} | {'desktop/src/lib/brand.ts','desktop/src-tauri/src/brand.rs','desktop/src-tauri/linux/djhx-memo.desktop.hbs',
     'website/logo.svg','website/brand.json'}
RES = 'android/app/src/main/res/'
ALLOWED |= {RES+'drawable/'+n+'.xml' for n in ('ic_launcher_foreground','ic_launcher_monochrome','ic_memo')}
ALLOWED |= {RES+folder+'/'+name for folder in ('mipmap-anydpi-v26','mipmap-anydpi-v33') for name in ('ic_launcher.xml','ic_launcher_round.xml')}
ALLOWED |= {RES+folder+'/brand.xml' for folder in ('values','values-zh-rCN')}
ALLOWED.add(RES+'values/ic_launcher_colors.xml')
for density in ('mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi'):
    ALLOWED |= {RES+'mipmap-'+density+'/'+n+'.png' for n in ('ic_launcher','ic_launcher_round','ic_launcher_foreground','ic_launcher_background','ic_launcher_monochrome')}
    ALLOWED.add(RES+'drawable-'+density+'/ic_stat_notify.png')


def target(name):
    parsed = PurePosixPath(name)
    if parsed.is_absolute() or parsed.as_posix() != name or any(p in ('.','..') for p in parsed.parts) or '\\' in name or ':' in name:
        raise ValueError('Invalid brand resource path')
    path = (ROOT / name).resolve()
    if not path.is_relative_to(ROOT) or path.relative_to(ROOT).as_posix() != name or name not in ALLOWED:
        raise ValueError('Brand target is outside managed resource roots')
    return path


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()


def rgba(data):
    """Decode a pinned 32px RGBA8 PNG for Tauri without a runtime image dependency."""
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    assert struct.unpack('>IIBBBBB', data[16:29]) == (32, 32, 8, 6, 0, 0, 0)
    offset = 8; compressed = b''
    while offset < len(data):
        size = struct.unpack('>I', data[offset:offset+4])[0]
        kind = data[offset+4:offset+8]; body = data[offset+8:offset+8+size]
        assert zlib.crc32(kind+body) & 0xffffffff == struct.unpack('>I', data[offset+8+size:offset+12+size])[0]
        if kind == b'IDAT': compressed += body
        offset += size + 12
    raw = zlib.decompress(compressed); assert len(raw) == 32 * 129
    output = bytearray(); previous = bytearray(128)
    for row in range(32):
        mode = raw[row*129]; line = bytearray(raw[row*129+1:(row+1)*129])
        for x in range(128):
            left = line[x-4] if x >= 4 else 0; up = previous[x]; corner = previous[x-4] if x >= 4 else 0
            if mode == 4:
                p = left + up - corner
                prediction = min((left,up,corner), key=lambda c: abs(p-c))
            else:
                prediction = (0,left,up,(left+up)//2)[mode]
            line[x] = (line[x]+prediction) & 255
        output.extend(line); previous = line
    return bytes(output)


def images(profile):
    logo = profile['logo']; output = {}
    if logo['profile'] == 'paper-v1':
        archive = ROOT / 'assets/brand/archive/paper-v1'
        original = json.loads((archive/'manifest-before.json').read_text(encoding="utf-8"))
        for name,digest in original['files'].items():
            target(name)
            body = (archive/'files'/name).read_bytes()
            assert hashlib.sha256(body).hexdigest() == digest
            output[name] = body
        body = (archive/'master.svg').read_bytes()
        assert hashlib.sha256(body).hexdigest() == original['master_sha256']
        output['assets/brand/master.svg'] = body
        tray = rgba(output['assets/brand/png/icon-32.png'])
        for color in ('black','white'):
            output[f'desktop/src-tauri/icons/tray-{color}.rgba'] = tray
        return output
    assert logo['profile'] == 'logo-pack-v1' and logo['colorway'] in ('sunrise','ink-night','ink-paper','vermilion','indigo')
    assert re.fullmatch(r'assets/brand/packs/[a-z0-9-]+\.zip',logo['archive'])
    source = (ROOT/logo['archive']).resolve()
    assert source.parent == (ROOT/'assets/brand/packs').resolve()
    assert hashlib.sha256(source.read_bytes()).hexdigest() == logo['sha256'], 'Brand pack hash mismatch'
    with ZipFile(source) as pack:
        base = 'logo-pack/colorways/' + logo['colorway'] + '/'
        def read(name): return pack.read(base+name)
        output['assets/brand/master.svg'] = read('svg/app-icon.svg')
        output['assets/brand/monochrome.svg'] = pack.read('logo-pack/common/svg/mark-mono-black.svg')
        overview = pack.read('logo-pack/preview/colorways-overview.png')
        output['assets/brand/design-sheet.png'] = overview
        output['assets/brand/design-sheet.svg'] = ('<svg xmlns="http://www.w3.org/2000/svg" width="1560" height="540"><image width="1560" height="540" href="data:image/png;base64,' + base64.b64encode(overview).decode() + '"/></svg>\n').encode()
        for size in (16,24,32,48,64,128,256,512,1024):
            output[f'assets/brand/png/icon-{size}.png'] = read(f'png/app-icon-{size}.png')
        for name in ('32x32.png','128x128.png','128x128@2x.png','icon.ico','icon.png'):
            output['desktop/src-tauri/icons/'+name] = read('desktop/tauri-icons/'+name)
        for color in ('black','white'):
            data = pack.read(f'logo-pack/common/tray/windows/tray-{color}-32.png')
            output[f'desktop/src-tauri/icons/tray-{color}.png'] = data
            output[f'desktop/src-tauri/icons/tray-{color}.rgba'] = rgba(data)
        for info in pack.infolist():
            prefix = base+'android/res/'; common = 'logo-pack/common/android/res/'
            if not info.is_dir() and info.filename.startswith((prefix,common)):
                relative = info.filename[len(prefix if info.filename.startswith(prefix) else common):]
                body = pack.read(info)
                if relative.startswith('mipmap-anydpi-v26/'):
                    xml = ET.fromstring(body)
                    output['android/app/src/main/res/'+relative.replace('-v26/','-v33/')] = body
                    for child in list(xml):
                        if child.tag == 'monochrome': xml.remove(child)
                    body = ET.tostring(xml,encoding='utf-8',xml_declaration=True)+b'\n'
                output['android/app/src/main/res/'+relative] = body
        output['website/logo.svg'] = read('web/favicon.svg')
    return output


def names(profile,output):
    zh = profile['names']['zh-CN']; en = profile['names']['en']
    assert all(0 < len(n) <= 32 and all(c.isalnum() or c in ' .-' for c in n) for n in (zh,en)), 'Invalid display name'
    output['desktop/src/lib/brand.ts'] = ("// Generated by scripts/generate-brand-assets.py.\nexport const brandNames = {\n  'zh-CN': '"+zh+"',\n  en: '"+en+"',\n} as const;\n").encode()
    output['desktop/src-tauri/src/brand.rs'] = ('// Generated by scripts/generate-brand-assets.py.\npub const NAME_ZH: &str = '+json.dumps(zh,ensure_ascii=False)+';\npub const NAME_EN: &str = '+json.dumps(en,ensure_ascii=False)+';\npub const MONO_TRAY: bool = '+str(profile['logo']['profile']!='paper-v1').lower()+';\npub fn name(chinese: bool) -> &\'static str {\n    match chinese {\n        true => NAME_ZH,\n        false => NAME_EN,\n    }\n}\n').encode()
    for folder,value in [('values',en),('values-zh-rCN',zh)]:
        xml = ET.Element('resources'); ET.SubElement(xml,'string',{'name':'app_name'}).text = value
        output[f'android/app/src/main/res/{folder}/brand.xml'] = ET.tostring(xml,encoding='utf-8',xml_declaration=True)+b'\n'
    output['website/brand.json'] = encoded(profile['names'])
    text = (ROOT/'desktop/src-tauri/linux/djhx-memo.desktop.hbs').read_text(encoding="utf-8")
    text = re.sub(r'^Name(?:\[en\]|\[zh_CN\])?=.*\n','',text,flags=re.M)
    output['desktop/src-tauri/linux/djhx-memo.desktop.hbs'] = text.replace('[Desktop Entry]\n',f'[Desktop Entry]\nName={en}\nName[en]={en}\nName[zh_CN]={zh}\n').encode()


def metadata(profile,check):
    en = profile['names']['en']; zh = profile['names']['zh-CN']
    actual = json.loads((ROOT/'desktop/src-tauri/tauri.conf.json').read_text(encoding='utf-8'))
    assert actual['identifier'] == 'app.djhx.memo' and actual['mainBinaryName'] == 'djhx-memo'
    linux = json.loads((ROOT/'desktop/src-tauri/tauri.linux.conf.json').read_text(encoding='utf-8'))
    assert linux['productName'] == 'djhx-memo'
    gradle = (ROOT/'android/app/build.gradle.kts').read_text(encoding='utf-8')
    assert re.search(r'^\s*applicationId\s*=\s*"app\.djhx\.memo"\s*$', gradle, re.M)
    installer = (ROOT/'desktop/src-tauri/windows/installer.nsi').read_text(encoding='utf-8')
    assert re.search(r'^!define INSTALLIDENTITY "简记"$', installer, re.M)
    path = ROOT/'desktop/src-tauri/tauri.conf.json'; text = path.read_text(encoding="utf-8")
    new = text
    for key in ('productName','title','startMenuFolder'):
        new = re.sub(r'("'+key+r'":\s*)"[^"]*"',lambda m:m[1]+json.dumps(en),new,count=1)
    edits = [(path,text,new)]
    path = ROOT/'desktop/index.html'; text = path.read_text(encoding='utf-8')
    assert text.count('<title>') == 1
    edits.append((path,text,re.sub(r'<title>.*?</title>',lambda _:'<title>'+html.escape(en)+'</title>',text)))
    path = ROOT/'desktop/src-tauri/windows/installer.nsi'; text = path.read_text(encoding="utf-8")
    block = '; BEGIN GENERATED BRAND\n!define BRAND_EN "'+en+'"\n!define BRAND_ZH "'+zh+'"\n; END GENERATED BRAND'
    assert '; BEGIN GENERATED BRAND' in text
    edits.append((path,text,re.sub(r'; BEGIN GENERATED BRAND.*?; END GENERATED BRAND',lambda _:block,text,flags=re.S)))
    path = ROOT/'android/app/build.gradle.kts'; text = path.read_text(encoding="utf-8")
    new = re.sub(r'(manifestPlaceholders\["appLabel"\] = )"[^"\n]* Debug"',lambda m:m[1]+json.dumps(en+' Debug'),text)
    edits.append((path,text,new))
    for path,before,after in edits:
        if check and before != after: raise ValueError('Brand metadata differs: '+path.relative_to(ROOT).as_posix())
        if not check and before != after: path.write_text(after,encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check',action='store_true'); args = parser.parse_args()
    profile = json.loads((ROOT/'assets/brand/profile.json').read_text(encoding="utf-8"))
    assert profile['identity'] == {'application_id':'app.djhx.memo','binary':'djhx-memo','windows_install_identity':'简记'}
    outputs = images(profile); names(profile,outputs)
    for name,body in outputs.items():
        target(name)
        if name.endswith('.xml'): ET.fromstring(body)
        if name.endswith('.png'):
            assert body[:8] == b'\x89PNG\r\n\x1a\n'
            width,height = struct.unpack('>II',body[16:24])
            if 'design-sheet' not in name: assert width == height and 16 <= width <= 1024
            match = re.search(r'/icon-(\d+)\.png$',name)
            if match: assert width == int(match[1])
        if name.endswith('.svg'):
            for node in ET.fromstring(body).iter():
                assert node.tag.split('}')[-1] not in ('script','foreignObject')
                for key,value in node.attrib.items():
                    if key.split('}')[-1] == 'href': assert value.startswith(('#','data:image/png;base64,'))
        if name.endswith('.ico'):
            count=struct.unpack('<H',body[4:6])[0]
            assert {body[6+i*16] or 256 for i in range(count)} == {16,24,32,48,64,128,256}
    manifest_path = ROOT/'assets/brand/generated-manifest.json'
    before = json.loads(manifest_path.read_text(encoding="utf-8"))
    stale = []
    for name,digest in before['files'].items():
        path = target(name)
        if name not in outputs and path.exists():
            if args.check: raise ValueError('Stale active output: '+name)
            assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'Refuse to remove hand-edited output'
            stale.append(path)
    metadata(profile,args.check)
    for path in stale: path.unlink()
    for name,body in outputs.items():
        path = target(name)
        if args.check: assert path.is_file() and path.read_bytes() == body, 'Brand mismatch: '+name
        else: path.parent.mkdir(parents=True,exist_ok=True); path.write_bytes(body)
    manifest = {'format_version':2,'profile':profile['logo']['profile'],'names':profile['names'],
                'files':{name:hashlib.sha256(body).hexdigest() for name,body in sorted(outputs.items())}}
    if args.check: assert manifest_path.read_bytes() == encoded(manifest), 'Brand manifest mismatch'
    else: manifest_path.write_bytes(encoded(manifest))
    print(('PASS' if args.check else 'Generated'),len(outputs),'brand outputs; installation identity preserved')
