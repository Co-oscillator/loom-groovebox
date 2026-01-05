lines = open('/Users/danielmiller/Documents/Code Projects/ancient-halley/app/src/main/java/com/groovebox/MainActivity.kt').readlines()
balance = 0
for i, line in enumerate(lines):
    clean_line = line.split('//')[0].strip() # Ignore comments
    for char in clean_line:
        if char == '{': balance += 1
        if char == '}': balance -= 1
    if balance < 0:
        print(f"Negative balance at line {i+1}: {line.strip()}")
        break
print(f"Final balance: {balance}")
