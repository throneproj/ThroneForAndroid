"""品牌残留扫描：检查源码注释与字符串字面量中不含姊妹分支品牌字样。

扫描范围：app/、libcore/、buildScript/、buildSrc/ 下的文本源码文件
（Kotlin/Java/Go/Python/Shell/Gradle/XML/properties/proguard 等）。
命中判定：文件内容（含注释与字符串字面量）或文件路径中出现品牌词
（大小写不敏感）即报错。

用法（在仓库根目录执行）：uv run tools/diagnostics/check_no_brand_comments.py
退出码：0 = 无残留；1 = 发现残留或扫描异常。
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

SCAN_DIRS = ("app", "libcore", "buildScript", "buildSrc")

# 品牌词按大小写不敏感匹配，覆盖 OwnBox / ownbox / OWNBOX 等变体
BRAND_PATTERN = re.compile(r"ownbox", re.IGNORECASE)

# 允许扫描的文本源码扩展名
TEXT_EXTENSIONS = {
    ".kt", ".kts", ".java", ".go", ".py", ".sh", ".gradle",
    ".xml", ".properties", ".pro", ".md", ".txt", ".json", ".yml", ".yaml",
    ".sh_", ".conf", ".gitignore", ".gitattributes",
}

# 排除的构建产物与缓存目录名
EXCLUDED_DIR_NAMES = {
    "build", ".gradle", ".idea", ".vscode", "node_modules",
    "executableSo", "libs", "outputs", "tmp", "intermediates",
}

errors: list[str] = []
checked_files = 0


def iter_source_files() -> list[Path]:
    """收集四个扫描目录下的文本源码文件。"""
    files: list[Path] = []
    for scan_dir in SCAN_DIRS:
        root = REPO / scan_dir
        if not root.is_dir():
            errors.append(f"扫描目录不存在: {scan_dir}/")
            continue
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            if any(part in EXCLUDED_DIR_NAMES for part in path.parts):
                continue
            if path.suffix.lower() in TEXT_EXTENSIONS or path.name == ".gitignore":
                files.append(path)
    return files


def main() -> int:
    global checked_files
    for path in iter_source_files():
        rel = path.relative_to(REPO).as_posix()
        # 路径本身含品牌词也视为残留
        if BRAND_PATTERN.search(rel):
            errors.append(f"{rel}: 文件路径含品牌字样")
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            # 非 UTF-8 文本（如 latin-1 资源文件）按宽松编码重试
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError as exc:
                errors.append(f"{rel}: 无法读取 ({exc})")
                continue
        except OSError as exc:
            errors.append(f"{rel}: 无法读取 ({exc})")
            continue
        checked_files += 1
        for lineno, line in enumerate(text.splitlines(), start=1):
            if BRAND_PATTERN.search(line):
                errors.append(f"{rel}:{lineno}: 内容含品牌字样: {line.strip()[:120]}")

    print(f"checked {checked_files} source files in {', '.join(SCAN_DIRS)}")
    if errors:
        print(f"\n{len(errors)} error(s):")
        for e in errors:
            print(" -", e)
        return 1
    print("OK: no brand residue found in comments, strings, or paths")
    return 0


if __name__ == "__main__":
    sys.exit(main())