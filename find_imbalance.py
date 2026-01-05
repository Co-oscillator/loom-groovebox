
def find_imbalance(filename):
    with open(filename, 'r') as f:
        lines = f.readlines()
    
    depth = 0
    stack = []
    for i, line in enumerate(lines):
        for char in line:
            if char == '{':
                depth += 1
                stack.append(i + 1)
            elif char == '}':
                depth -= 1
                if stack:
                    stack.pop()
                if depth < 0:
                    print(f"Extra closing brace at line {i + 1}")
                    depth = 0
    
    if depth > 0:
        print(f"Missing {depth} closing braces. Last unclosed opening braces were at lines:")
        for line_num in stack[-10:]:
            print(line_num)

if __name__ == "__main__":
    find_imbalance('app/src/main/java/com/groovebox/MainActivity.kt')
