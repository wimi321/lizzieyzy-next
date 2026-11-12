#!/usr/bin/env python3
"""List user-visible strings that are still English in a translated DisplayStrings bundle.

A string counts as untranslated when its value is identical to the English base bundle and reads
like text. Brand names, protocol names, file names and similar values that are meant to stay
English are listed in KEEP_VALUES / KEEP_KEY_SUFFIXES below.

    python3 scripts/list_untranslated_strings.py              # report per locale
    python3 scripts/list_untranslated_strings.py --locale ko  # one locale
    python3 scripts/list_untranslated_strings.py --check      # fail if a locale got worse
    python3 scripts/list_untranslated_strings.py --update-baseline

--check compares with scripts/untranslated_baseline.json, which may only go down: add the
translation together with a new English string, or run --update-baseline after translating.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
L10N_DIR = Path("src/main/resources/l10n")
BASE_BUNDLE = "DisplayStrings.properties"
ENGLISH_BUNDLES = {"DisplayStrings.properties", "DisplayStrings_en_US.properties"}
BASELINE_FILE = Path("scripts/untranslated_baseline.json")

# Values that are correct in every language.
KEEP_VALUES = {
    "% of winDiff", "% stdev", "CFG", "DLL", "Dialog.plain", "Elo", "English", "GitHub",
    "HumanSL AI", "HumanSL CUDA companion", "Java", "KeyGen", "Lcb(%)", "Lizzie", "LizzieYzy",
    "NVIDIA GPU", "OGS", "PDA", "PDA:", "ReadBoard", "ReadBoard/Yike", "SSH", "Tencent", "TensorRT profile",
    "UID", "URL", "URL:", "VIP", "url",
    "LizzieYzyの使い方",  # label of the Japanese manual, Japanese in every language
}
# Keys whose values are file names or identifiers rather than text.
KEEP_KEY_SUFFIXES = ("fileName", "FileName", "openingSGFindex")
# Keys whose values are product or backend names.
KEEP_KEY_PREFIXES = ("AutoSetup.experimental",)
TEXT = re.compile(r"[A-Za-z]{3,}")
ENTRY = re.compile(r"\s*((?:\\.|[^=:\s])+)\s*[=:]\s*(.*)")
UNICODE_ESCAPE = re.compile(r"\\u([0-9a-fA-F]{4})")


def read_bundle(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    lines = path.read_text(encoding="utf-8").splitlines()
    index = 0
    while index < len(lines):
        line = lines[index]
        index += 1
        stripped = line.strip()
        if not stripped or stripped[0] in "#!":
            continue
        match = ENTRY.match(line)
        if not match:
            continue
        value = match.group(2)
        while value.endswith("\\") and index < len(lines):
            value = value[:-1] + lines[index].lstrip()
            index += 1
        values[match.group(1)] = UNICODE_ESCAPE.sub(lambda m: chr(int(m.group(1), 16)), value)
    return values


def is_kept(key: str, value: str) -> bool:
    return (
        value.strip() in KEEP_VALUES
        or key.endswith(KEEP_KEY_SUFFIXES)
        or key.startswith(KEEP_KEY_PREFIXES)
    )


def untranslated(root: Path) -> dict[str, list[tuple[str, str]]]:
    directory = root / L10N_DIR
    base = read_bundle(directory / BASE_BUNDLE)
    report: dict[str, list[tuple[str, str]]] = {}
    for path in sorted(directory.glob("DisplayStrings_*.properties")):
        if path.name in ENGLISH_BUNDLES:
            continue
        locale = path.stem[len("DisplayStrings_"):]
        bundle = read_bundle(path)
        report[locale] = [
            (key, value)
            for key, value in bundle.items()
            if base.get(key) == value and TEXT.search(value) and not is_kept(key, value)
        ]
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--locale", help="Only report this locale, for example ja_JP")
    parser.add_argument("--check", action="store_true", help="Fail if a locale exceeds the baseline")
    parser.add_argument("--update-baseline", action="store_true", help="Store current counts")
    parser.add_argument("--root", default=str(REPO_ROOT), help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    root = Path(args.root)
    report = untranslated(root)
    counts = {locale: len(entries) for locale, entries in report.items()}
    baseline_path = root / BASELINE_FILE

    if args.update_baseline:
        baseline_path.write_text(json.dumps(counts, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"Updated {BASELINE_FILE}: {counts}")
        return 0
    if args.check:
        baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
        worse = {
            locale: (count, baseline.get(locale, 0))
            for locale, count in counts.items()
            if count > baseline.get(locale, 0)
        }
        if worse:
            print("New untranslated strings (current vs baseline):")
            for locale, (count, allowed) in sorted(worse.items()):
                print(f"  {locale}: {count} > {allowed}")
                for key, value in report[locale]:
                    print(f"    {key} = {value[:100]}")
            print("Translate them in the same change, or run --update-baseline with a reason.")
            return 1
        print(f"Untranslated strings within baseline: {counts}")
        return 0

    for locale, entries in report.items():
        if args.locale and locale != args.locale:
            continue
        print(f"{locale}: {len(entries)} untranslated")
        for key, value in entries:
            print(f"  {key} = {value[:120]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
