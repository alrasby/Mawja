#!/usr/bin/env python3
"""Read public publisher pages, prefer published low-bandwidth audio, and audit real samples."""
import concurrent.futures,html,json,pathlib,re,subprocess,urllib.request,urllib.parse
ROOT=pathlib.Path("source-audit");ROOT.mkdir(exist_ok=True)
rows=json.loads(pathlib.Path("doc/radio/news-candidates-47.json").read_text())
def get(url):
    req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0"})
    with urllib.request.urlopen(req,timeout=12) as r:return r.read(2000000).decode(errors="replace"),r.geturl()
def select(row):
    row=dict(row)
    try:
        page,page_url=get(row["sourcePage"])
        found=re.findall(r'https?://[^\s"<>\\]+',html.unescape(page.replace("\\/","/")))
        found=[u for u in found if ".m3u8" in u]
        row["publisherPage"]={"url":page_url,"hlsFound":[u for u in found[:8] if not urllib.parse.urlsplit(u).query]}
    except Exception as e:row["publisherPage"]={"error":str(e)}
    try:
        manifest,final_url=get(row["streamUrl"])
        print("MANIFEST_PREFIX "+row["id"]+" "+repr(manifest[:160]),flush=True)
        if not manifest.lstrip().startswith("#EXTM3U"):raise ValueError("Not HLS")
        audio=re.findall(r'#EXT-X-MEDIA:[^\n]*TYPE=AUDIO[^\n]*URI="([^"]+)"',manifest)
        choices=[]
        lines=manifest.splitlines()
        for i,line in enumerate(lines[:-1]):
            if line.startswith("#EXT-X-STREAM-INF:"):
                m=re.search(r'(?:^|,)BANDWIDTH=(\d+)',line.split(":",1)[1])
                if m and not lines[i+1].startswith("#"):choices.append((int(m.group(1)),urllib.parse.urljoin(final_url,lines[i+1].strip())))
        chosen=urllib.parse.urljoin(final_url,audio[0]) if audio else min(choices)[1] if choices else final_url
        # Never bake temporary signatures or credentials into the application.
        parsed=urllib.parse.urlsplit(chosen)
        if parsed.scheme!="https" or parsed.query or parsed.username:raise ValueError("Requires dynamic or insecure URL")
        row["streamUrl"]=chosen
        row["selection"]="separate_audio" if audio else "lowest_muxed_variant" if choices else "media_playlist"
    except Exception as e:row["selectionError"]=str(e)
    return row
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:rows=list(pool.map(select,rows))
(ROOT/"selected.json").write_text(json.dumps(rows,ensure_ascii=False,indent=2))
subprocess.run(["python","scripts/audit-radio-stations.py",str(ROOT/"selected.json"),str(ROOT/"report.json")],check=True)
report=json.loads((ROOT/"report.json").read_text())
for row in report["stations"]:
    print("AUDIT_RESULT "+json.dumps(row,ensure_ascii=False),flush=True)
