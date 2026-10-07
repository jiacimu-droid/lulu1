"""Report actual ZIP storage by category; debug is a same-source baseline."""
import json
import sys
from collections import defaultdict
from pathlib import Path
from zipfile import ZipFile


def report(path):
    totals = defaultdict(int)
    with ZipFile(path) as archive:
        entries = archive.infolist()
        for entry in entries:
            name = entry.filename
            if name.startswith("lib/"):
                category = "/".join(name.split("/")[:2])
            elif name.endswith(".dex"):
                category = "dex"
            elif name.startswith("res/") or name == "resources.arsc":
                category = "resources"
            elif name.startswith("assets/"):
                category = "assets"
            else:
                category = "other"
            totals[category] += entry.compress_size
        return {"apk": str(path), "bytes": Path(path).stat().st_size,
                "compressed_sections": dict(sorted(totals.items())),
                "largest_entries": [{"name": e.filename, "bytes": e.compress_size}
                                    for e in sorted(entries, key=lambda e: e.compress_size, reverse=True)[:12]]}


if __name__ == "__main__":
    reports = [report(path) for path in sys.argv[1:]]
    print(json.dumps(reports, ensure_ascii=False, indent=2))
    if len(reports) == 2:
        saved = reports[0]["bytes"] - reports[1]["bytes"]
        print(f"Saved {saved:,} bytes ({saved / reports[0]['bytes']:.1%})")
