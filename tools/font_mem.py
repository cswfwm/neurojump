import pathlib
import re

# 统计各字号使用次数，估算字体图集内存（RGBA8888）
ROOT = pathlib.Path(__file__).resolve().parent.parent
counts = {}
for p in (ROOT / "core/src/main/java").rglob("*.java"):
    for m in re.finditer(r"Fonts\.of\((\d+)\)", p.read_text(encoding="utf-8")):
        size = int(m.group(1))
        counts[size] = counts.get(size, 0) + 1

print("字号  使用次数  估算图集尺寸   内存")
total = 0
for size in sorted(counts):
    # FreeType 图集大致按字符数与字号平方增长，这里给出经验估算
    # 385 个字符，每格约 (size*1.15) 见方
    cell = int(size * 1.2)
    per_row = 2048 // cell
    rows = (385 + per_row - 1) // per_row
    # 图集宽高取 2 的幂，最小 256
    w = 256
    while w < per_row * cell:
        w *= 2
    h = 256
    while h < rows * cell:
        h *= 2
    mb = w * h * 4 / 1024 / 1024
    total += mb
    print("%4d  %6d    %5dx%-5d  %6.2f MB" % (size, counts[size], w, h, mb))
print("合计约 %.1f MB（RGBA8888）" % total)
print("若改为 ALPHA/Luminance 单通道，可降到约 %.1f MB" % (total / 4))
