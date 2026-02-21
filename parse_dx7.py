import os

patches = [
    "RECORDERS", "SHIMMER", "FILTER SWP", "FUNKY RISE", "REFS WHISL",
    "STEEL DRUM", "HARMONICA1", "ACCORDION", "SITAR", "LUTE",
    "BANJO", "HARP    1", "HARP    2", "SYN-VOX", "SYN-ORCH"
]

urls = [
    "rom1a.syx", "rom1b.syx", "rom2a.syx", "rom2b.syx",
    "rom3a.syx", "rom3b.syx", "rom4a.syx", "rom4b.syx"
]

def map_algo(dx_algo):
    # dx_algo is 0-31 (which is algo 1-32)
    # 0 = Serial (Loom 0)
    # 1 = Piano (Loom 1)
    # 2 = Organ (Loom 2)
    # 3 = Brass (Loom 3)
    dx_algo += 1
    if dx_algo in [1, 2, 7, 8]: return 0
    if dx_algo in [3, 4, 5, 6, 9, 10, 11]: return 1
    if dx_algo in [12, 13, 14, 15, 16, 17, 18]: return 1 # 2 or 3 carriers
    if dx_algo in [19, 20, 21, 22, 23, 24, 25]: return 2
    if dx_algo >= 26: return 3
    return 1

found = set()

def decode_voice(data, name):
    if name in found: return # avoid duplicates
    found.add(name)
    
    print(f"    // --- {name} ---")
    algo = data[110] & 31
    loom_algo = map_algo(algo)
    if loom_algo == 0: mask = 1
    elif loom_algo == 1: mask = (1<<0) | (1<<3)
    elif loom_algo == 2: mask = (1<<0) | (1<<2) | (1<<4)
    else: mask = 63
    
    print(f"    // DX7 Algo {algo+1}")
    print(f"    setAlgorithm({loom_algo});")
    print(f"    mCarrierMask = {mask};")
    
    for i in range(6):
        base = (5-i) * 17
        lev = data[base+16]
        pmode_pcoarse = data[base+14]
        pfine = data[base+15]
        mode = pmode_pcoarse & 1
        coarse = (pmode_pcoarse >> 1) & 31
        fine = pfine
        
        ratio = coarse + (fine * 0.01) if mode == 0 else (10 ** (coarse & 3)) * (1 + fine * 0.01)
        if mode == 0 and coarse == 0:
            ratio = 0.5
            
        r1, r2, r3, r4 = data[base+0], data[base+1], data[base+2], data[base+3]
        # Approximating DX7 EG to ADSR (in seconds)
        a = (99 - r1) * 0.01 if r1 < 99 else 0.001
        d = (99 - r2) * 0.02
        r = (99 - r4) * 0.02
        s = data[base+6] / 99.0
        
        level = lev / 99.0
        
        print(f"    mOpRatios[{i}] = {ratio:.2f}f;")
        print(f"    mOpLevels[{i}] = {level:.2f}f;")
        print(f"    mOpAttack[{i}] = {a:.3f}f; mOpDecay[{i}] = {d:.3f}f; mOpSustain[{i}] = {s:.2f}f; mOpRelease[{i}] = {r:.3f}f;")

for filename in urls:
    if not os.path.exists(filename):
        continue
    with open(filename, 'rb') as f:
        syx = f.read()
    
    if len(syx) == 4104:
        payload = syx[6:6+4096]
        for v in range(32):
            voice_packed = payload[v*128 : (v+1)*128]
            name_bytes = voice_packed[118:128]
            name = "".join([chr(b) for b in name_bytes if b >= 32 and b <= 126]).strip()
            if name in patches:
                decode_voice(voice_packed, name)
