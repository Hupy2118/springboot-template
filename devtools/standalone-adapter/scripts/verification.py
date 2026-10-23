"""Shared strict HTTP/ZIP assertions. Never include request configuration in errors."""
import io
import json
import os
from pathlib import Path
import stat
import time
import urllib.error
import urllib.request
import zipfile
from adapter import require, relative, audit_files


def safe_zip(data):
    require(data.startswith(b'PK\x03\x04'), 'response is not a ZIP')
    result, folded, total = {}, set(), 0
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(len(archive.infolist()) <= 15000, 'too many ZIP entries')
        for info in archive.infolist():
            key = info.filename[:-1] if info.is_dir() else info.filename
            relative(key)
            require(key.casefold() not in folded, 'duplicate/case-colliding ZIP path: ' + key)
            folded.add(key.casefold())
            mode = info.external_attr >> 16
            require(not stat.S_ISLNK(mode) and stat.S_IFMT(mode) in (0,stat.S_IFREG,stat.S_IFDIR), 'unsafe ZIP entry type')
            require(info.file_size <= 32*1024*1024, 'oversized ZIP entry')
            require(info.file_size <= max(1,info.compress_size)*1000, 'excessive ZIP compression ratio')
            total += info.file_size
            require(total <= 128*1024*1024, 'oversized ZIP payload')
            if not info.is_dir():result[key] = archive.read(info)
    for key in result:
        require(not any('/'.join(key.split('/')[:i]) in result for i in range(1,len(key.split('/')))), 'ZIP file/directory collision')
    return result


def post(base, endpoint, body):
    request=urllib.request.Request(base.rstrip('/')+endpoint,data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Accept':'application/zip, application/json'},method='POST')
    try:
        with urllib.request.urlopen(request,timeout=45) as response:
            data=response.read(128*1024*1024+1)
            require(len(data)<=128*1024*1024,'oversized HTTP response')
            return response.status,response.headers.get('Content-Type',''),data
    except urllib.error.HTTPError as error:
        return error.code,error.headers.get('Content-Type',''),error.read(1024*1024)


def zip_response(status,kind,data):
    require(status==200,'expected HTTP 200 ZIP, got '+str(status))
    require('application/zip' in kind,'expected application/zip')
    return safe_zip(data)


def unpack(files, root):
    require(not root.exists(),'refusing to overwrite existing generated workspace: '+str(root))
    root.mkdir(parents=True)
    timestamp = time.mktime((1980, 1, 2, 0, 0, 0, 0, 0, -1))
    for key,data in files.items():
        target=root/key;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
        # Match the engine's fixed ZIP date; current mtimes hid epoch-zero Maven copy failures.
        os.utime(target, (timestamp, timestamp))
        if key.endswith('.sh'):target.chmod(0o755)
