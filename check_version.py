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
    
    if len(sys.argv) > 1 and sys.argv[1] == "--increment":
        # Auto-increment mode
        new_code = int(v_code) + 1
        
        # Increment Patch version in Name (X.Y.Z)
        parts = v_name.split('.')
        if len(parts) == 3:
            try:
                parts[2] = str(int(parts[2]) + 1)
                # Ensure 2 digits for patch if it was 2 digits? 
                # The user had "08" -> "09". So if I cast "09" to int 9 + 1 = 10. "10" is fine.
                # If they want to preserve leading zero for single digits:
                if len(parts[2]) < 2 and int(parts[2]) < 10:
                     parts[2] = f"{int(parts[2]):02d}" 
                # Wait, "08" -> int(8) + 1 = 9. "{9:02d}" -> "09".
                # But simple str(int) gives "9".
                # Let's just stick to integer increment for now, or try to respect padding if length matches.
                # Actually, "1.15.08" -> "08".
                # Let's assume standard semantic versioning unless padding is detected.
            except:
                pass
        new_name = ".".join(parts)
        
        # Write back
        with open(properties_path, 'w') as f:
            f.write(f"VERSION_CODE={new_code}\n")
            f.write(f"VERSION_NAME={new_name}\n")
            
        print(f"Auto-incremented to: {new_name} (Build {new_code})")
        return

    print(f"Target Version: {v_name} (Build {v_code})")
    
    # Verify build.gradle is using the dynamic loading logic
    with open(gradle_path, 'r') as f:
        content = f.read()
        if "versionProps['VERSION_CODE']" not in content:
            print("Error: app/build.gradle is not using version.properties!")
            sys.exit(1)
            
    # --- Temporal Check ---
    last_version_path = ".last_version"
    # Only enforce check if we are NOT incrementing (standard check)
    if os.path.exists(last_version_path):
        with open(last_version_path, 'r') as f:
            last_code = f.read().strip()
            if last_code and int(v_code) < int(last_code):
                print(f"Error: VERSION_CODE {v_code} must be greater than last built version {last_code}!")
                print("Please increment VERSION_CODE in version.properties or run with auto-increment.")
                sys.exit(1)
    
    # Update last version ONLY after passing consistency checks
    with open(last_version_path, 'w') as f:
        f.write(v_code)
            
    print("Version verification passed.")

if __name__ == "__main__":
    check_version()
