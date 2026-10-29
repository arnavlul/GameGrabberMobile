import os
import sys
import shutil
import zipfile
import subprocess

def run_cmd(cmd, desc):
    print(f"[*] {desc}...")
    res = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        print(f"[!] Error in {desc}:")
        print(res.stdout)
        print(res.stderr)
        sys.exit(1)
    return res.stdout

def build():
    sdk_dir = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk")
    build_tools = os.path.join(sdk_dir, "build-tools", "36.0.0")
    if not os.path.exists(build_tools):
        build_tools = os.path.join(sdk_dir, "build-tools", "36.1.0")

    android_jar = os.path.join(sdk_dir, "platforms", "android-36", "android.jar")
    aapt2 = os.path.join(build_tools, "aapt2.exe")
    d8 = os.path.join(build_tools, "d8.bat")
    zipalign = os.path.join(build_tools, "zipalign.exe")
    apksigner = os.path.join(build_tools, "apksigner.bat")

    jdk_bin = r"C:\Program Files\Java\jdk-24\bin"
    javac = os.path.join(jdk_bin, "javac.exe")
    keytool = os.path.join(jdk_bin, "keytool.exe")

    print(f"[*] Android SDK Build Tools: {build_tools}")
    print(f"[*] Android Platform JAR   : {android_jar}")

    build_dir = "build"
    if os.path.exists(build_dir):
        shutil.rmtree(build_dir)
    os.makedirs(os.path.join(build_dir, "gen"), exist_ok=True)
    os.makedirs(os.path.join(build_dir, "classes"), exist_ok=True)
    os.makedirs(os.path.join(build_dir, "dex"), exist_ok=True)

    # 1. Compile Resources
    res_zip = os.path.join(build_dir, "compiled_res.zip")
    run_cmd(f'"{aapt2}" compile --dir res -o "{res_zip}"', "Compiling Android resources")

    # 2. Link Resources
    unaligned_apk = os.path.join(build_dir, "unaligned.apk")
    gen_dir = os.path.join(build_dir, "gen")
    run_cmd(
        f'"{aapt2}" link -I "{android_jar}" "{res_zip}" --manifest AndroidManifest.xml '
        f'-o "{unaligned_apk}" --java "{gen_dir}" --auto-add-overlay',
        "Linking resources and generating R.java"
    )

    # 3. Compile Java Source Code
    java_files = [
        os.path.join(gen_dir, "com", "gamegrabber", "mobile", "R.java"),
        os.path.join("src", "com", "gamegrabber", "mobile", "MainActivity.java"),
        os.path.join("src", "com", "gamegrabber", "mobile", "PortalResolver.java"),
        os.path.join("src", "com", "gamegrabber", "mobile", "TwineParser.java"),
        os.path.join("src", "com", "gamegrabber", "mobile", "GameDownloader.java")
    ]
    files_str = " ".join(f'"{f}"' for f in java_files)
    classes_dir = os.path.join(build_dir, "classes")
    run_cmd(
        f'"{javac}" --release 8 -cp "{android_jar}" {files_str} -d "{classes_dir}"',
        "Compiling Java sources to bytecode"
    )

    # 4. Compile Bytecode to Dalvik Executable (classes.dex)
    class_files = []
    for root, _, files in os.walk(classes_dir):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))

    class_files_str = " ".join(f'"{f}"' for f in class_files)
    dex_dir = os.path.join(build_dir, "dex")
    run_cmd(
        f'"{d8}" {class_files_str} --output "{dex_dir}" --lib "{android_jar}"',
        "Generating Dalvik Executable (classes.dex)"
    )

    # 5. Inject classes.dex into unaligned.apk
    print("[*] Adding classes.dex to APK...")
    classes_dex = os.path.join(dex_dir, "classes.dex")
    with zipfile.ZipFile(unaligned_apk, "a") as zf:
        zf.write(classes_dex, "classes.dex")

    # 6. Align APK with zipalign
    aligned_apk = os.path.join(build_dir, "aligned.apk")
    run_cmd(f'"{zipalign}" -f -p 4 "{unaligned_apk}" "{aligned_apk}"', "Aligning APK (4-byte boundary)")

    # 7. Generate Debug Keystore if needed
    keystore = "debug.keystore"
    if not os.path.exists(keystore):
        run_cmd(
            f'"{keytool}" -genkeypair -validity 10000 -dname "CN=GameGrabber,O=Mobile,C=US" '
            f'-keystore "{keystore}" -storepass android -keypass android '
            f'-alias androiddebugkey -keyalg RSA -keysize 2048',
            "Generating debug signing certificate"
        )

    # 8. Sign APK with apksigner
    final_apk = os.path.join("..", "GameGrabber.apk")
    run_cmd(
        f'"{apksigner}" sign --ks "{keystore}" --ks-pass pass:android '
        f'--key-pass pass:android --out "{final_apk}" "{aligned_apk}"',
        "Signing APK with v2/v3 signatures"
    )

    # 9. Verify APK
    verify_out = run_cmd(f'"{apksigner}" verify -v "{final_apk}"', "Verifying signed APK")
    print(verify_out.strip())

    print("\n" + "=" * 60)
    print(f"[+] SUCCESS! APK built and signed successfully:")
    print(f"    -> {os.path.abspath(final_apk)} ({os.path.getsize(final_apk) / 1024:.1f} KB)")
    print("=" * 60)

if __name__ == "__main__":
    build()
