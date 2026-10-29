#!/usr/bin/env python3
"""把 assembleDebug/Release 产出的 APK 重新压缩并签名。

本容器的 aarch64 aapt2 在写入 lib/armeabi-v7a/libgdx.so 之后会产生约 15MB 的空洞，
导致 APK 体积虚高。这里按正常 zip 结构重写（.so 不压缩以便系统按页加载），
再用 java 版 apksigner 签名，保证 v1/v2/v3 方案齐全。
"""
import os
import subprocess
import sys
import zipfile

JAVA = "/usr/lib/jvm/java-17-openjdk/bin/java"
APKSIGNER = "/opt/android-sdk/build-tools/34.0.0/lib/apksigner.jar"
KEYSTORE = os.path.expanduser("~/workspace/neurojump/android/keystore.p12")
PASS = "neurojump123"
ALIAS = "neurojump"


def repack(src, dst):
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            zi = zipfile.ZipInfo(info.filename, date_time=(1980, 1, 1, 1, 1, 0))
            zi.external_attr = info.external_attr
            zi.compress_type = (zipfile.ZIP_STORED if info.filename.endswith(".so")
                                else zipfile.ZIP_DEFLATED)
            zout.writestr(zi, data)


def main():
    if len(sys.argv) < 3:
        print("用法: repack_apk.py <输入.apk> <输出.apk>")
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    tmp = dst + ".repacked"
    repack(src, tmp)
    subprocess.run([JAVA, "-jar", APKSIGNER, "sign",
                    "--ks", KEYSTORE, "--ks-type", "PKCS12",
                    "--ks-pass", "pass:" + PASS, "--key-pass", "pass:" + PASS,
                    "--ks-key-alias", ALIAS,
                    "--out", dst, tmp], check=True)
    os.remove(tmp)
    print("已重打包并签名:", dst, os.path.getsize(dst), "字节")
    return 0


if __name__ == "__main__":
    sys.exit(main())
