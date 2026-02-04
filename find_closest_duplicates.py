
import os
import collections

def find_duplicates(directory, extensions, min_lines=10):
    lines_map = collections.defaultdict(list)
    
    for root, _, files in os.walk(directory):
        for file in files:
            if not any(file.endswith(ext) for ext in extensions):
                continue
            
            path = os.path.join(root, file)
            try:
                with open(path, 'r', encoding='utf-8', errors='ignore') as f:
                    lines = [l.strip() for l in f.readlines()]
            except:
                continue
                
            # Rolling window hash
            for i in range(len(lines) - min_lines + 1):
                chunk = tuple(lines[i:i+min_lines])
                if all(not l for l in chunk): # Skip empty chunks
                    continue
                # Skip chunks with only braces/comments simplified
                if all(l in ['}', '{', '};', ')', ''] for l in chunk):
                    continue
                    
                lines_map[chunk].append((path, i + 1))

    # Report duplicates
    print(f"Scanning for duplicates > {min_lines} lines...")
    count = 0
    for chunk, occurrences in lines_map.items():
        if len(occurrences) > 1:
            # Check if all occurrences are in the same file
            paths = set(p for p, _ in occurrences)
            if len(occurrences) > 10: # Skip very common patterns
                 continue
            
            print(f"\nDuplicate Block found ({len(occurrences)} times):")
            print(f"Content Start: {chunk[0][:50]}...")
            for path, line in occurrences:
                print(f"  at {path}:{line}")
            count += 1
            if count > 20:
                print("... limiting output ...")
                break

if __name__ == '__main__':
    find_duplicates('.', ['.kt', '.cpp', '.h'], min_lines=15)
