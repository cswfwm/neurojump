"""按源码中出现的字符串字面量重新裁剪字体，保证界面文案不会缺字。

用法：python3 tools/subset_font.py
原始字体放在 core/assets/fonts/GameFont.ttf，裁剪后直接覆盖它。
"""
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
chars = set()
for p in (ROOT / "core/src/main/java").rglob("*.java"):
    text = p.read_text(encoding="utf-8")
    # 只收集字符串字面量里的非 ASCII 字符，注释里的字不必进字体
    for literal in re.findall(r'"((?:[^"\\]|\\.)*)"', text):
        chars.update(ch for ch in literal if ord(ch) > 127)
for c in range(0x20, 0x7F):
    chars.add(chr(c))

text = "".join(sorted(chars))
subset_file = ROOT / "build" / "font-chars.txt"
subset_file.parent.mkdir(parents=True, exist_ok=True)
subset_file.write_text(text, encoding="utf-8")

src = ROOT / "core/assets/fonts/GameFont.ttf"
subprocess.run([
    "python3", "-m", "fontTools.subset", str(src),
    "--text-file=" + str(subset_file),
    "--output-file=" + str(src),
    "--layout-features=", "--no-hinting", "--desubroutinize",
], check=True)
print("字体已更新，覆盖字符数:", len(chars))
