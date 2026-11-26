#!/usr/bin/env python3
"""Fetch bounded stream samples (including HLS segments) and inspect actual audio.
Usage: python scripts/audit-radio-stations.py [catalog.json] [report.json]
Requires ffprobe. Failures are evidence for review, never automatic deletion.
"""
import hashlib,urllib.request,urllib.parse,concurrent.futures,json,pathlib,subprocess,sys,tempfile,time,datetime
catalog = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/radio/reviewed-stations.json")
report = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else "radio-audit.json")
rows = json.loads(catalog.read_text())
samples = tempfile.TemporaryDirectory(prefix="mawja-radio-audit-")
def request(url,limit):
 req=urllib.request.Request(url,headers={'User-Agent':'Mawja-Station-Audit/1.0','Range':'bytes=0-'+str(limit-1)})
 with urllib.request.urlopen(req,timeout=10) as r:
  content_type = r.headers.get('Content-Type', '')
  # read1 prevents a never-ending ICY stream from holding a worker indefinitely.
  deadline = time.monotonic() + 4
  chunks = []
  size = 0
  target = limit if 'mpegurl' in content_type or '.m3u8' in r.geturl().split('?')[0] else 32768
  while size < target and time.monotonic() < deadline:
   chunk = r.read1(min(8192, target-size))
   if not chunk: break
   chunks.append(chunk)
   size += len(chunk)
  return b''.join(chunks),r.geturl(),r.status,content_type
def sample(url,depth=0):
 if depth>4:raise ValueError('Manifest nesting limit')
 b,url,status,ctype=request(url,150000)
 if b.lstrip().startswith(b'#EXTM3U'):
  s=b.decode();lines=[l.strip() for l in s.splitlines() if l.strip()]
  audio=[l for l in lines if l.startswith('#EXT-X-MEDIA:') and 'TYPE=AUDIO' in l and 'URI="' in l]
  uris=[l for l in lines if not l.startswith('#')]
  if audio:
   import re
   target=re.search('URI="([^"]+)"',audio[0]).group(1)
  elif uris:target=uris[-1] if '#EXTINF:' in s else uris[0]
  else:raise ValueError('No media in manifest')
  media_url = urllib.parse.urljoin(url,target)
  if '#EXTINF:' in s:
   import re
   init = re.search(r'#EXT-X-MAP:.*?URI="([^"]+)"', s)
   if init:
    # Fragmented MP4 needs its initialization segment before codec inspection.
    header, _, _, _ = request(urllib.parse.urljoin(url, init.group(1)), 150000)
    media, status, media_type = sample(media_url, depth+1)
    return header + media, status, media_type
  return sample(media_url,depth+1)
 return b,status,ctype

def go(x):
 try:
  b,status,ctype=sample(x['streamUrl']);p=pathlib.Path(samples.name) / (hashlib.sha256(x['id'].encode()).hexdigest()+'.sample');p.write_bytes(b)
  r=subprocess.run(['ffprobe','-v','error','-show_entries','stream=codec_type,codec_name','-of','json',str(p)],capture_output=True,text=True,timeout=5)
  streams=json.loads(r.stdout or '{}').get('streams',[])
  x['audit']={'status':'audio_verified' if any(s.get('codec_type')=='audio' for s in streams) else 'unrecognized_media','http':status,'contentType':ctype,'codecs':streams}
 except urllib.error.HTTPError as e:x['audit']={'status':'http_error','http':e.code,'detail':str(e)}
 except Exception as e:x['audit']={'status':'inconclusive','detail':str(e)}
 print(x['countryCode'],x['name'],x['audit'],flush=True);return x
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as e:results=list(e.map(go,rows))
report.write_text(json.dumps({"checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "stations": results}, ensure_ascii=False, indent=2) + "\n")
samples.cleanup()
