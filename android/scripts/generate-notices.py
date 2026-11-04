#!/usr/bin/env python3
"""从固定的运行时依赖及已解析制品提取通知；不下载或运行依赖。"""
import hashlib
import io
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
PACKAGES = sorted({line.split("=", 1)[0] for line in (ROOT / "app/gradle.lockfile").read_text().splitlines()
                   if "=" in line and "releaseRuntimeClasspath" in line.split("=", 1)[1].split(",")})
parts = ["Android JVM/AndroidX 第三方软件通知\n\n"
         "依赖来自 app/gradle.lockfile 的 releaseRuntimeClasspath（包括平台约束）。\n"
         "AndroidX: https://android.googlesource.com/platform/frameworks/support\n"
         "Kotlin/coroutines: https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines\n"
         "JNA: https://github.com/java-native-access/jna （本产品选择 Apache-2.0 许可）\n"
         "CommonMark: https://github.com/commonmark/commonmark-java\n"
         "Guava: https://github.com/google/guava\n\n"]
for package in PACKAGES:
    group, artifact, version = package.split(":")
    if package.startswith("org.commonmark:"):
        license_id = "BSD-2-Clause"
    elif group.startswith(("androidx.", "org.jetbrains", "com.google.guava")) or group == "net.java.dev.jna":
        license_id = "Apache-2.0"
    else:
        raise SystemExit(f"依赖未完成许可证复核：{package}")
    parts.append(f"{package} — {license_id}\n")
parts.append("\nApache License 2.0 全文\n\n" + (ROOT / "licenses/Apache-2.0.txt").read_text())
seen = set()
def collect(archive, label):
    with zipfile.ZipFile(archive) as items:
        for name in sorted(items.namelist()):
            basename = Path(name).name.upper()
            if basename.startswith(("LICENSE", "NOTICE", "COPYRIGHT")) and not name.endswith("/"):
                content = items.read(name).decode("utf-8", errors="replace")
                digest = hashlib.sha256(content.encode()).hexdigest()
                if digest not in seen:
                    seen.add(digest)
                    parts.append(f"\n\n原制品通知：{label}/{name}\n\n{content}\n")
            elif name == "classes.jar":
                collect(io.BytesIO(items.read(name)), label + "/classes.jar")
for package in PACKAGES:
    group, artifact, version = package.split(":")
    directory = CACHE / group / artifact / version
    for artifact_file in sorted(directory.glob("*/*")):
        if artifact_file.suffix in (".aar", ".jar") and not artifact_file.name.endswith(("-sources.jar", "-javadoc.jar")):
            collect(artifact_file, package)
(ROOT / "THIRD_PARTY_NOTICES.txt").write_text("".join(parts))
print(f"generated Android notices: {len(PACKAGES)} locked packages, {len(seen)} distinct archive notices")
