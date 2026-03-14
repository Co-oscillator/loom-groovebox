import re

def fix_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()
    
    def repl(m):
        func_name = m.group(1)
        if func_name.startswith('n') and func_name[1].isupper():
            return m.group(0) # already fixed
        new_name = 'n' + func_name[0].upper() + func_name[1:]
        return "Java_com_groovebox_NativeLib_" + new_name
        
    new_content = re.sub(r'Java_com_groovebox_NativeLib_([a-zA-Z0-9_]+)', repl, content)
    
    with open(filepath, 'w') as f:
        f.write(new_content)

fix_file('app/src/main/cpp/native-lib.cpp')
fix_file('composeApp/native/native-lib-desktop.cpp')
print("Done fixing JNI names")
