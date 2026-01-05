
import sys

def check_braces(filename):
    with open(filename, 'r') as f:
        lines = f.readlines()
    
    stack = []
    for i, line in enumerate(lines):
        for char in line:
            if char == '{':
                stack.append(i + 1)
            elif char == '}':
                if not stack:
                    print(f"Extra closing brace at line {i+1}")
                else:
                    stack.pop()
    
    if stack:
        print(f"Unclosed braces opened at lines: {stack}")

if __name__ == "__main__":
    check_braces(sys.argv[1])
