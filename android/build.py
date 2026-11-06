"""从源码构建两个签名 APK，需要 Android SDK 34、Build Tools 34.0.0 和 JDK 17+。"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parent


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def build(name: str, src: Path, stage: Path, sdk: Path, keystore: Path) -> Path:
    tools = sdk / "build-tools" / "34.0.0"
    android_jar = sdk / "platforms" / "android-34" / "android.jar"
    win = os.name == "nt"
    aapt = tools / ("aapt2.exe" if win else "aapt2")
    d8 = tools / ("d8.bat" if win else "d8")
    align = tools / ("zipalign.exe" if win else "zipalign")
    signer = tools / ("apksigner.bat" if win else "apksigner")
    work = stage / name
    classes = work / "classes"
    dex = work / "dex"
    classes.mkdir(parents=True)
    dex.mkdir()
    resources = work / "res.zip"
    base = work / "base.apk"
    aligned = work / "aligned.apk"
    apk = stage / f"{name}.apk"

    print(f"构建 {name}", flush=True)
    run(aapt, "compile", "--dir", src / "res", "-o", resources)
    assets = ("-A", src / "assets") if (src / "assets").is_dir() else ()
    run(aapt, "link", "-o", base, "-I", android_jar, "--manifest", src / "AndroidManifest.xml",
        "-R", resources, *assets, "--auto-add-overlay")
    # 给 JDK 21 生成的隐式参数保留名字，兼容 Build Tools 34 的 D8。
    run("javac", "-encoding", "UTF-8", "-parameters", "--release", "11", "-classpath", android_jar,
        "-d", classes, *sorted((src / "src").rglob("*.java")))
    run(d8, "--lib", android_jar, "--min-api", "24", "--output", dex,
        *sorted(classes.rglob("*.class")))
    with ZipFile(base, "a", ZIP_DEFLATED) as archive:
        archive.write(dex / "classes.dex", "classes.dex")
    run(align, "-f", "4", base, aligned)
    run(signer, "sign", "--ks", keystore, "--ks-key-alias", "androiddebugkey",
        "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--out", apk, aligned)
    run(signer, "verify", apk)
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sdk_env = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("SDK")
    parser.add_argument("--sdk", type=Path, default=Path(sdk_env) if sdk_env else None)
    parser.add_argument("--out", type=Path, default=ROOT / "build")
    parser.add_argument("--keystore", type=Path, help="现有 Android 调试密钥库，供覆盖安装时保持签名")
    parser.add_argument("--preview", action="store_true", help="构建可与原版并存的独立测试版，不读取原版本机数据")
    args = parser.parse_args()
    if not args.sdk or not (args.sdk / "platforms" / "android-34" / "android.jar").is_file():
        parser.error("需要 Android SDK Platform 34。请设置 ANDROID_HOME 或传入 --sdk。")
    if not (args.sdk / "build-tools" / "34.0.0").is_dir():
        parser.error("需要 Android SDK Build Tools 34.0.0。")

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    keystore = args.keystore.resolve() if args.keystore else out / "debug.keystore"
    if args.keystore and not keystore.is_file():
        parser.error("--keystore 指向的调试密钥库不存在。")
    if not keystore.exists():
        run("keytool", "-genkeypair", "-keystore", keystore, "-storepass", "android",
            "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US")

    with TemporaryDirectory(prefix="compile-", dir=out) as stage:
        if args.preview:
            src = Path(stage) / "preview-src"
            shutil.copytree(ROOT / "app", src)
            manifest = src / "AndroidManifest.xml"
            text = manifest.read_text(encoding="utf-8")
            # Keep compiled class names; only the installed application ID changes.
            text = text.replace('package="com.nopalou"', 'package="com.nopalou.preview"')
            text = text.replace('android:name=".', 'android:name="com.nopalou.')
            text = text.replace('android:label="楼外"', 'android:label="楼外·课表测试"')
            manifest.write_text(text, encoding="utf-8")
            apks = [build("nopalou-preview", src, Path(stage), args.sdk.resolve(), keystore)]
        else:
            apks = [build(name, ROOT / directory, Path(stage), args.sdk.resolve(), keystore)
                    for name, directory in (("nopalou", "app"), ("replay", "replay"))]
        for apk in apks:
            apk.replace(out / apk.name)
            print(out / apk.name)


if __name__ == "__main__":
    main()
