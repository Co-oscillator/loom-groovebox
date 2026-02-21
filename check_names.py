import os

urls = [
    "rom1a.syx", "rom1b.syx", "rom2a.syx", "rom2b.syx",
    "rom3a.syx", "rom3b.syx", "rom4a.syx", "rom4b.syx"
]

for filename in urls:
    if not os.path.exists(filename):
        continue
    with open(filename, 'rb') as f:
        syx = f.read()
    
    if len(syx) == 4104:
        payload = syx[6:6+4096]
        names = []
        for v in range(32):
            voice_packed = payload[v*128 : (v+1)*128]
            name_bytes = voice_packed[118:128]
            name = "".join([chr(b) for b in name_bytes if b >= 32 and b <= 126]).strip()
            names.append(name)
        print(f"--- {filename} ---")
        print(names)
