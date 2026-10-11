#!/usr/bin/env python3
"""Checks new writing against the house style before a commit.

    tools/style_check.py              what's staged
    tools/style_check.py --all        everything in git
    tools/style_check.py --message F  a commit message

What it looks at: the game's strings, the packs' names and descriptions,
Markdown, and comments in the code and tools. What it catches: dashes in
sentences, US spellings, "X, not Y", bold, the names of real railways, and in
what players see, the games Interchange draws on, read from docs/lineage.md.
French strings are only checked for dashes and names.

It can't tell whether something is plain and short. That's still read by eye.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STRINGS = "shared/src/commonMain/composeResources/values/strings.xml"
STRINGS_FR = "shared/src/commonMain/composeResources/values-fr/strings.xml"

# Canadian English, word by word: these are the US spellings that keep turning up.
US = {
    "color": "colour", "colors": "colours", "colored": "coloured", "colorful": "colourful",
    "center": "centre", "centers": "centres", "centered": "centred",
    "gray": "grey", "harbor": "harbour", "harbors": "harbours",
    "behavior": "behaviour", "behaviors": "behaviours",
    "neighbor": "neighbour", "neighbors": "neighbours", "neighborhood": "neighbourhood",
    "favorite": "favourite", "traveled": "travelled", "traveling": "travelling",
    "labeled": "labelled", "labeling": "labelling", "modeled": "modelled", "modeling": "modelling",
    "catalog": "catalogue", "defense": "defence", "theater": "theatre",
    "liter": "litre", "liters": "litres", "meter": "metre", "meters": "metres",
    "kilometer": "kilometre", "kilometers": "kilometres", "fiber": "fibre", "analyze": "analyse",
    "tyre": "tire", "tyres": "tires", "license": "licence (the noun)",
}

# Our railways have their own names: never the real ones'.
RAILWAYS = re.compile(r"\b(Canadian National|Canadian Pacific|VIA Rail|CN Rail|CP Rail|CNR|CPR|CN|CP|VIA)\b")

# "X, not Y", except where "not" just goes with what follows.
NOT_Y = re.compile(r",\s+not\s+(?!before\b|yet\b|if\b|when\b|to\b|that\b|only\b|all\b|at all\b|even\b|just\b)\w")

DASH = re.compile(r"[—–]")
# A dash on its own, as a mark for nothing.
LONE_DASH = re.compile(r"""^["'>]?\s*[—–]\s*["'<]?$""")


def lineage_names():
    """The games Interchange draws on, from the untracked lineage notes: each bullet's names before its colon."""
    path = ROOT / "docs" / "lineage.md"
    if not path.exists():
        return []
    names = set()
    for line in path.read_text().splitlines():
        m = re.match(r"^- (.+?):", line)
        if not m or "our own" in m.group(1):
            continue
        for part in re.split(r",| and | such as |\bsets\b", re.sub(r"\(.*?\)", "", m.group(1))):
            part = part.strip()
            if len(part) >= 3 and part.lower() not in ("openttd industry",):
                names.add(part)
    return sorted(names, key=len, reverse=True)


def prose(path, line):
    """The part of a line that's writing, or None: a string's text, a pack's words, a comment, Markdown."""
    if path in (STRINGS, STRINGS_FR):
        m = re.search(r"<(?:string|item)[^>]*>(.*?)</", line)
        return m.group(1) if m else None
    if path.startswith("packs/") and path.endswith(".json"):
        found = re.findall(r'"(?:en|fr)": "([^"]*)"', line)
        return " / ".join(found) if found else None
    if path.endswith(".md"):
        return line
    if path.endswith((".kt", ".kts")):
        m = re.search(r"(//|/\*\*?|^\s*\*)(.*)", line)
        return m.group(2) if m else None
    if path.endswith(".py"):
        m = re.search(r"#(.*)", line)
        if m:
            return m.group(1)
        m = re.search(r'^\s*("""|\'\'\')?([A-Z].*)', line)
        return m.group(2) if m and line.strip().endswith(('."""', ".", '"""')) else None
    return None


def check(path, number, text, names, out):
    if text is None:
        return
    # Code inside the writing isn't writing: `names`, [links] and quoted strings.
    words = re.sub(r"`[^`]*`|\[[^\]]*\]|\"[^\"]*\"", "", text) if not path.endswith(".json") else text
    seen_by_players = path in (STRINGS, STRINGS_FR) or path.startswith("packs/")
    if DASH.search(words) and not LONE_DASH.match(text.strip()):
        out.append((path, number, "a dash in a sentence: use a comma, colon or full stop", text))
    if RAILWAYS.search(words):
        out.append((path, number, "a real railway's name: use our own", text))
    if seen_by_players:
        for name in names:
            if re.search(rf"\b{re.escape(name)}\b", text, re.IGNORECASE):
                out.append((path, number, "names a game Interchange draws on", text))
                break
    if path == STRINGS_FR or (path.endswith(".json") and "fr" in text and "en" not in text):
        return
    english = words.split(" / ")[0] if path.endswith(".json") else words
    for us, ca in US.items():
        if re.search(rf"\b{us}\b", english, re.IGNORECASE):
            out.append((path, number, f"US spelling {us}: write {ca}", text))
    if NOT_Y.search(english):
        out.append((path, number, "\"X, not Y\": say what it is", text))
    if path.endswith(".md") and "**" in text:
        out.append((path, number, "bold", text))


def changed_lines(everything):
    """(path, line number, line) for each line to look at: added in the staged change, or every line with [everything]."""
    if everything:
        files = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True).stdout.split()
        for f in files:
            if not f.endswith((".kt", ".kts", ".py", ".md", ".xml", ".json")):
                continue
            try:
                for n, line in enumerate((ROOT / f).read_text().splitlines(), 1):
                    yield f, n, line
            except (UnicodeDecodeError, FileNotFoundError):
                pass
        return
    diff = subprocess.run(["git", "diff", "--cached", "-U0", "--no-color"], cwd=ROOT, capture_output=True, text=True).stdout
    path = None
    number = 0
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("@@"):
            number = int(re.search(r"\+(\d+)", line).group(1))
        elif line.startswith("+") and path:
            yield path, number, line[1:]
            number += 1


def main():
    args = sys.argv[1:]
    names = lineage_names()
    out = []
    if args[:1] == ["--message"]:
        # A commit message is all writing; the lines git adds and the trailers aren't.
        for n, line in enumerate(Path(args[1]).read_text().splitlines(), 1):
            if line.startswith("#") or re.match(r"^[A-Z][\w-]+: ", line):
                continue
            check("commit message", n, line, names, out)
    else:
        for path, n, line in changed_lines("--all" in args):
            if path.startswith("docs/") or path == "tools/style_check.py":
                continue
            check(path, n, prose(path, line), names, out)
    for path, n, why, text in out:
        print(f"{path}:{n}: {why}\n    {text.strip()}")
    if out:
        print(f"\n{len(out)} to fix. Run tools/style_check.py --all to look at everything.")
        sys.exit(1)


if __name__ == "__main__":
    main()
