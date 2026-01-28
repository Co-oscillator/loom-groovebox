import sys
import os

def check_version():
    properties_path = "version.properties"
    gradle_path = "app/build.gradle"
    
    if not os.path.exists(properties_path):
        print(f"Error: {properties_path} not found")
        sys.exit(1)
        
    with open(properties_path, 'r') as f:
        props = dict(line.strip().split('=') for line in f if '=' in line)
        
    v_code = props.get('VERSION_CODE')
    v_name = props.get('VERSION_NAME')
    
    print(f"Target Version: {v_name} (Build {v_code})")
    
    # Verify build.gradle is using the dynamic loading logic
    with open(gradle_path, 'r') as f:
        content = f.read()
        if "versionProps['VERSION_CODE']" not in content:
            print("Error: app/build.gradle is not using version.properties!")
            sys.exit(1)
            
    # --- Temporal Check ---
    last_version_path = ".last_version"
    if os.path.exists(last_version_path):
        with open(last_version_path, 'r') as f:
            last_code = f.read().strip()
            if last_code and int(v_code) <= int(last_code):
                print(f"Error: VERSION_CODE {v_code} must be greater than last built version {last_code}!")
                print("Please increment VERSION_CODE in version.properties.")
                sys.exit(1)
    
    # Update last version ONLY after passing consistency checks
    # Note: In a real CI/CD this would happen after a successful build, 
    # but here we'll use it as a 'gate' for the next run.
    with open(last_version_path, 'w') as f:
        f.write(v_code)
            
    print("Version verification passed.")

if __name__ == "__main__":
    check_version()
