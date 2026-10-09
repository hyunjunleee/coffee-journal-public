#!/usr/bin/env python3
"""
Coffee flavor corpus tool (data/coffee-flavor-corpus): fetch pages politely, stage entries, and commit them to the
repository 100 at a time.

    corpus.py fetch URL [--max-chars N]   page text as JSON (robots.txt checked first; refuses when disallowed)
    corpus.py wiki LANG TITLE              a Wikipedia article as plain text (CC BY-SA 4.0), JSON
    corpus.py seen URL                     whether the corpus already has entries from URL
    corpus.py stage LANE FILE              validate candidate entries (JSON lines) and add them to LANE's staging file
    corpus.py commit LANE [--flush]        write 100 staged entries as one shard, commit and push ([skip ci]);
                                           --flush writes whatever is staged (end of a run)
    corpus.py stats [--json]               totals: entries, words (Korean whitespace words), by lane/kind/license

Entries are JSON objects, one per line:
  lang        "ko" | "en" | "mixed"
  kind        "cup_notes" | "tasting_review" | "lexicon" | "flavor_wheel" | "education" | "article" | "forum" |
              "competition"
  text        the content: full text only when the license allows it; otherwise a short quote (excerpt: true)
  excerpt     true when text is a short quote of a page under copyright (at most EXCERPT_MAX_WORDS words)
  cup_notes   the notes the source itself lists for the coffee, verbatim ([] when none)
  subject     optional {"coffee", "origin", "region", "variety", "process", "roast", "roaster"}
  source_url  the exact page; source_title; publisher
  license     one of LICENSES
The tool adds: id, words (whitespace words of text), retrieved (UTC date), lane.
"""
import argparse
import datetime as dt
import fcntl
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.robotparser

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CORPUS = os.path.join(ROOT, "data", "coffee-flavor-corpus")
SHARDS = os.path.join(CORPUS, "shards")
STAGING = os.environ.get("CORPUS_STAGING", "/home/user/corpus-staging")
LOCK = os.path.join(STAGING, ".git.lock")
BRANCH = os.environ.get("CORPUS_BRANCH", "claude/coffee-journal-site-analysis-zo66b0")
UA = "coffee-journal-corpus/1.0 (+https://github.com/hyunjunleee/coffee-journal)"
SHARD_SIZE = 100
EXCERPT_MAX_WORDS = 60
FULLTEXT_MAX_WORDS = 600

LANGS = {"ko", "en", "mixed"}
KINDS = {"cup_notes", "tasting_review", "lexicon", "flavor_wheel", "education", "article", "forum", "competition"}
# full text is allowed under these; anything else must be a short excerpt
OPEN_LICENSES = {
    "CC BY 4.0", "CC BY 3.0", "CC BY 2.0 KR", "CC BY-SA 4.0", "CC BY-SA 3.0", "CC BY-SA 2.0 KR",
    "CC BY-NC 4.0", "CC BY-NC-SA 4.0", "CC BY-NC-SA 2.0 KR", "CC0", "public-domain", "KOGL-1", "KOGL-2",
}
LICENSES = OPEN_LICENSES | {"copyrighted-excerpt"}
SUBJECT_KEYS = {"coffee", "origin", "region", "variety", "process", "roast", "roaster"}

COMMIT_TRAILER = (
    "\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\n"
    "Claude-Session: https://claude.ai/code/session_018QYnCDu2vDaVkATiKmJbxB"
)


def words(text):
    return len(text.split())


def norm(text):
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]", "", text.lower())).strip()


def text_hash(text):
    return hashlib.sha1(norm(text).encode("utf-8")).hexdigest()


def die(msg, code=1):
    print(json.dumps({"ok": False, "error": msg}, ensure_ascii=False))
    sys.exit(code)


# ---------------- fetching ----------------

_robots = {}


def robots_ok(url):
    parts = urllib.parse.urlsplit(url)
    base = f"{parts.scheme}://{parts.netloc}"
    if base not in _robots:
        rp = urllib.robotparser.RobotFileParser()
        try:
            raw = subprocess.run(
                ["curl", "-sS", "-L", "-m", "15", "-A", UA, base + "/robots.txt"],
                capture_output=True, text=True, timeout=20,
            )
            body = raw.stdout if raw.returncode == 0 else ""
            # a missing or broken robots.txt (an HTML error page) allows everything
            if "<html" in body[:500].lower():
                body = ""
            rp.parse(body.splitlines())
        except Exception:
            rp.parse([])
        _robots[base] = rp
    return _robots[base].can_fetch(UA, url) and _robots[base].can_fetch("*", url)


def curl(url, max_time=30):
    """GET [url]; a 429 or 503 is retried after a pause (3, 6, 12 s)."""
    for attempt in range(4):
        r = subprocess.run(
            ["curl", "-sS", "-L", "--compressed", "-m", str(max_time), "-A", UA, "-w", "\n%{http_code} %{url_effective}", url],
            capture_output=True, timeout=max_time + 5,
        )
        if r.returncode != 0:
            raise RuntimeError(r.stderr.decode("utf-8", "replace").strip()[:300])
        body, _, tail = r.stdout.rpartition(b"\n")
        code, _, final = tail.decode().partition(" ")
        if int(code) in (429, 503) and attempt < 3:
            time.sleep(3 * 2 ** attempt)
            continue
        return int(code), final, body


def cmd_fetch(args):
    url = args.url
    if not re.match(r"^https?://", url):
        die("not an http(s) URL")
    if not robots_ok(url):
        die("robots.txt disallows this URL: skip this site")
    try:
        code, final, body = curl(url)
    except Exception as e:
        die(f"fetch failed: {e}")
    if code >= 400:
        die(f"HTTP {code}")
    import trafilatura
    html = body.decode("utf-8", "replace")
    text = trafilatura.extract(html, include_comments=False, include_tables=True, favor_recall=True) or ""
    meta = trafilatura.extract_metadata(html)
    title = (meta.title if meta and meta.title else "") or ""
    out = {
        "ok": True, "url": url, "final_url": final, "title": title, "chars": len(text),
        "text": text[: args.max_chars],
        "truncated": len(text) > args.max_chars,
    }
    print(json.dumps(out, ensure_ascii=False))
    time.sleep(1.0)  # one request a second per caller


def cmd_wiki(args):
    api = f"https://{args.lang}.wikipedia.org/w/api.php"
    q = urllib.parse.urlencode({
        "action": "query", "prop": "extracts|info", "explaintext": 1, "inprop": "url", "redirects": 1,
        "titles": args.title, "format": "json", "formatversion": 2,
    })
    code, _, body = curl(f"{api}?{q}")
    if code >= 400:
        die(f"HTTP {code}")
    pages = json.loads(body)["query"]["pages"]
    if not pages or pages[0].get("missing"):
        die("no such article")
    p = pages[0]
    print(json.dumps({
        "ok": True, "title": p["title"], "url": p.get("fullurl", ""), "license": "CC BY-SA 4.0",
        "text": p.get("extract", "")[: args.max_chars], "chars": len(p.get("extract", "")),
    }, ensure_ascii=False))
    time.sleep(0.5)


# ---------------- corpus ----------------

def all_entries():
    for path in sorted(glob.glob(os.path.join(SHARDS, "*.jsonl"))):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    yield json.loads(line)


def existing_keys():
    hashes, urls = set(), set()
    for e in all_entries():
        hashes.add(text_hash(e["text"]))
        urls.add(e["source_url"])
    return hashes, urls


def cmd_seen(args):
    _, urls = existing_keys()
    staged = set()
    for path in glob.glob(os.path.join(STAGING, "*.jsonl")):
        with open(path, encoding="utf-8") as f:
            for line in f:
                try:
                    staged.add(json.loads(line)["source_url"])
                except Exception:
                    pass
    print(json.dumps({"url": args.url, "in_corpus": args.url in urls, "staged": args.url in staged}))


def validate(e):
    """Returns (entry, None) with derived fields filled, or (None, reason)."""
    if not isinstance(e, dict):
        return None, "not an object"
    for k in ("lang", "kind", "text", "source_url", "license"):
        if not isinstance(e.get(k), str) or not e[k].strip():
            return None, f"missing {k}"
    if e["lang"] not in LANGS:
        return None, f"lang must be one of {sorted(LANGS)}"
    if e["kind"] not in KINDS:
        return None, f"kind must be one of {sorted(KINDS)}"
    if e["license"] not in LICENSES:
        return None, f"license must be one of {sorted(LICENSES)}"
    if not re.match(r"^https?://[^\s]+\.[^\s]+", e["source_url"]):
        return None, "source_url must be the page's http(s) URL"
    text = re.sub(r"[ \t]+", " ", e["text"]).strip()
    n = words(text)
    excerpt = e["license"] == "copyrighted-excerpt"
    if excerpt and n > EXCERPT_MAX_WORDS:
        return None, f"a copyrighted page allows a quote of at most {EXCERPT_MAX_WORDS} words (got {n})"
    if not excerpt and n > FULLTEXT_MAX_WORDS:
        return None, f"split long text into entries of at most {FULLTEXT_MAX_WORDS} words (got {n})"
    if n < 3 and not e.get("cup_notes"):
        return None, "text too short"
    notes = e.get("cup_notes", [])
    if not isinstance(notes, list) or not all(isinstance(x, str) and x.strip() for x in notes):
        return None, "cup_notes must be a list of strings"
    subject = e.get("subject", {}) or {}
    if not isinstance(subject, dict) or not set(subject) <= SUBJECT_KEYS or not all(isinstance(v, str) for v in subject.values()):
        return None, f"subject keys must be among {sorted(SUBJECT_KEYS)} with string values"
    out = {
        "lang": e["lang"], "kind": e["kind"], "text": text, "excerpt": excerpt,
        "cup_notes": [x.strip() for x in notes],
        "subject": {k: v.strip() for k, v in subject.items() if v.strip()},
        "source_url": e["source_url"].strip(),
        "source_title": str(e.get("source_title", "")).strip(),
        "publisher": str(e.get("publisher", "")).strip(),
        "license": e["license"],
        "words": n,
        "retrieved": dt.datetime.now(dt.timezone.utc).date().isoformat(),
    }
    return out, None


def staging_path(lane):
    if not re.match(r"^[a-z0-9][a-z0-9-]{1,30}$", lane):
        die("lane must be lowercase letters, digits and dashes")
    os.makedirs(STAGING, exist_ok=True)
    return os.path.join(STAGING, f"{lane}.jsonl")


def read_jsonl(path):
    out = []
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    out.append(json.loads(line))
    return out


def cmd_stage(args):
    path = staging_path(args.lane)
    hashes, _ = existing_keys()
    staged = read_jsonl(path)
    staged_hashes = {text_hash(e["text"]) for e in staged}
    added, rejected = 0, []
    with open(args.file, encoding="utf-8") as f:
        lines = [l for l in f if l.strip()]
    with open(path, "a", encoding="utf-8") as out:
        for i, line in enumerate(lines, 1):
            try:
                cand = json.loads(line)
            except Exception as ex:
                rejected.append({"line": i, "reason": f"bad JSON: {ex}"})
                continue
            e, why = validate(cand)
            if why:
                rejected.append({"line": i, "reason": why})
                continue
            h = text_hash(e["text"])
            if h in hashes or h in staged_hashes:
                rejected.append({"line": i, "reason": "duplicate text"})
                continue
            staged_hashes.add(h)
            out.write(json.dumps(e, ensure_ascii=False) + "\n")
            added += 1
    total = len(read_jsonl(path))
    print(json.dumps({"ok": True, "lane": args.lane, "added": added, "rejected": rejected[:20],
                      "rejected_count": len(rejected), "staged": total,
                      "ready_to_commit": total >= SHARD_SIZE}, ensure_ascii=False))


def git(*a, check=True):
    r = subprocess.run(["git", "-C", ROOT, *a], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"git {' '.join(a)}: {r.stderr.strip()[:500]}")
    return r


def push_with_retry():
    for i in range(5):
        git("-c", "rebase.autoStash=true", "pull", "-q", "--rebase", "origin", BRANCH, check=False)
        r = git("push", "-q", "origin", f"HEAD:{BRANCH}", check=False)
        if r.returncode == 0:
            return True
        time.sleep(2 ** (i + 1))
    return False


def cmd_commit(args):
    path = staging_path(args.lane)
    os.makedirs(SHARDS, exist_ok=True)
    with open(LOCK, "w") as lockf:
        fcntl.flock(lockf, fcntl.LOCK_EX)
        staged = read_jsonl(path)
        if not staged:
            print(json.dumps({"ok": True, "committed": 0, "note": "nothing staged"}))
            return
        if len(staged) < SHARD_SIZE and not args.flush:
            print(json.dumps({"ok": True, "committed": 0, "staged": len(staged),
                              "note": f"waiting for {SHARD_SIZE} entries (use --flush at the end of a run)"}))
            return
        hashes, _ = existing_keys()
        batch, rest = [], []
        for e in staged:
            if len(batch) < SHARD_SIZE and text_hash(e["text"]) not in hashes:
                hashes.add(text_hash(e["text"]))
                batch.append(e)
            elif len(batch) >= SHARD_SIZE:
                rest.append(e)
        if not batch:
            with open(path, "w", encoding="utf-8") as f:
                for e in rest:
                    f.write(json.dumps(e, ensure_ascii=False) + "\n")
            print(json.dumps({"ok": True, "committed": 0, "note": "all staged entries were already in the corpus"}))
            return
        n = len(glob.glob(os.path.join(SHARDS, f"{args.lane}-*.jsonl"))) + 1
        while os.path.exists(os.path.join(SHARDS, f"{args.lane}-{n:04d}.jsonl")):
            n += 1
        shard = os.path.join(SHARDS, f"{args.lane}-{n:04d}.jsonl")
        with open(shard, "w", encoding="utf-8") as f:
            for i, e in enumerate(batch, 1):
                e = {"id": f"{args.lane}-{n:04d}-{i:03d}", "lane": args.lane, **e}
                f.write(json.dumps(e, ensure_ascii=False) + "\n")
        w = sum(e["words"] for e in batch)
        rel = os.path.relpath(shard, ROOT)
        git("add", rel)
        msg = f"corpus: {args.lane} +{len(batch)} entries, {w} words [skip ci]" + COMMIT_TRAILER
        git("-c", "user.name=Claude", "-c", "user.email=noreply@anthropic.com", "commit", "-q", "-m", msg, "--", rel)
        pushed = push_with_retry()
        with open(path, "w", encoding="utf-8") as f:
            for e in rest:
                f.write(json.dumps(e, ensure_ascii=False) + "\n")
        print(json.dumps({"ok": True, "committed": len(batch), "words": w, "shard": rel, "pushed": pushed,
                          "staged_left": len(rest)}, ensure_ascii=False))


def stats():
    from collections import Counter
    n, ko_words, en_words = 0, 0, 0
    by_lane, by_kind, by_lic, by_lang = Counter(), Counter(), Counter(), Counter()
    notes, domains = 0, Counter()
    for e in all_entries():
        n += 1
        if e["lang"] in ("ko", "mixed"):
            ko_words += e["words"]
        else:
            en_words += e["words"]
        by_lane[e["lane"]] += e["words"]
        by_kind[e["kind"]] += 1
        by_lic[e["license"]] += 1
        by_lang[e["lang"]] += 1
        notes += bool(e["cup_notes"])
        domains[urllib.parse.urlsplit(e["source_url"]).netloc] += 1
    return {"entries": n, "ko_words": ko_words, "en_words": en_words, "with_cup_notes": notes,
            "by_lang": dict(by_lang), "by_kind": dict(by_kind), "by_license": dict(by_lic),
            "words_by_lane": dict(by_lane), "top_domains": dict(domains.most_common(25)), "domains": len(domains)}


def cmd_stats(args):
    s = stats()
    if args.json:
        print(json.dumps(s, ensure_ascii=False))
    else:
        for k, v in s.items():
            print(f"{k}: {v}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("fetch"); p.add_argument("url"); p.add_argument("--max-chars", type=int, default=20000); p.set_defaults(f=cmd_fetch)
    p = sub.add_parser("wiki"); p.add_argument("lang"); p.add_argument("title"); p.add_argument("--max-chars", type=int, default=60000); p.set_defaults(f=cmd_wiki)
    p = sub.add_parser("seen"); p.add_argument("url"); p.set_defaults(f=cmd_seen)
    p = sub.add_parser("stage"); p.add_argument("lane"); p.add_argument("file"); p.set_defaults(f=cmd_stage)
    p = sub.add_parser("commit"); p.add_argument("lane"); p.add_argument("--flush", action="store_true"); p.set_defaults(f=cmd_commit)
    p = sub.add_parser("stats"); p.add_argument("--json", action="store_true"); p.set_defaults(f=cmd_stats)
    a = ap.parse_args()
    a.f(a)


if __name__ == "__main__":
    main()
