#!/usr/bin/env python3
"""Extracts a few files from a zip on a web server without downloading the rest.

    zip-extract.py URL DEST LAYER...

Reads the zip's directory and the wanted files with HTTP range requests, so taking two speed
limit layers from Digiroad's half-gigabyte regional packages costs a few megabytes. Writes the
Shapefile parts (.shp .shx .dbf .prj .cpg) of each LAYER under DEST, keeping the folder inside the
zip (Digiroad's: one per area). The server must support range requests; Fintraffic's does.
Standard library only.
"""
import io
import os
import sys
import urllib.request
import zipfile

PARTS = (".shp", ".shx", ".dbf", ".prj", ".cpg")
BLOCK = 4 << 20  # read ahead: the zip module reads in small pieces


class RemoteFile(io.RawIOBase):
    """A file on a web server, read with range requests and a read-ahead block."""

    def __init__(self, url):
        self.url, self.pos = url, 0
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=60) as response:
            self.size = int(response.headers["Content-Length"])
        self.block_start, self.block = 0, b""

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, offset, whence=io.SEEK_SET):
        self.pos = {io.SEEK_SET: offset, io.SEEK_CUR: self.pos + offset, io.SEEK_END: self.size + offset}[whence]
        return self.pos

    def read(self, size=-1):
        if size is None or size < 0:
            size = self.size - self.pos
        out = bytearray()
        while size > 0 and self.pos < self.size:
            if not self.block_start <= self.pos < self.block_start + len(self.block):
                self.fetch(self.pos, max(size, BLOCK))
            start = self.pos - self.block_start
            piece = self.block[start:start + size]
            out += piece
            self.pos += len(piece)
            size -= len(piece)
        return bytes(out)

    def fetch(self, start, length):
        end = min(start + length, self.size) - 1
        request = urllib.request.Request(self.url, headers={"Range": f"bytes={start}-{end}"})
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=120) as response:
                    if response.status != 206:
                        sys.exit(f"{self.url}: the server doesn't do range requests")
                    self.block_start, self.block = start, response.read()
                    return
            except OSError:
                if attempt == 2:
                    raise


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    url, dest, layers = sys.argv[1], sys.argv[2], set(sys.argv[3:])
    archive = zipfile.ZipFile(RemoteFile(url))
    found = 0
    for info in archive.infolist():
        stem, extension = os.path.splitext(os.path.basename(info.filename))
        if stem not in layers or extension.lower() not in PARTS:
            continue
        # Only the folder and file name, never a path that climbs out of DEST.
        folder = os.path.basename(os.path.dirname(info.filename))
        target_dir = os.path.join(dest, folder)
        os.makedirs(target_dir, exist_ok=True)
        target = os.path.join(target_dir, stem + extension)
        with archive.open(info) as source, open(target + ".part", "wb") as out:
            while chunk := source.read(1 << 20):
                out.write(chunk)
        os.replace(target + ".part", target)
        found += 1
    if not found:
        print(f"  none of {', '.join(sorted(layers))} in {url}", file=sys.stderr)


if __name__ == "__main__":
    main()
