from PIL import Image

def process_icon():
    try:
        # Load the uploaded image
        input_path = "/Users/danielmiller/.gemini/antigravity/brain/5162b39b-581d-4e61-846b-b933812b4ab6/uploaded_image_1767296839597.png"
        img = Image.open(input_path).convert("RGBA")
        
        # Resize to 64x64 using Nearest Neighbor to keep pixel art crisp
        img_resized = img.resize((64, 64), Image.NEAREST)
        
        # Create a new image for transparency
        data = img_resized.getdata()
        new_data = []
        
        # Simple color keying: Assume top-left pixel is background color (grey/white) or just white/grey
        # Let's inspect the corner or just find grey/white pixels.
        # It's safer to just make it transparent if it's white or light grey.
        
        for item in data:
            # Check for white or light grey background
            if item[0] > 240 and item[1] > 240 and item[2] > 240:
                new_data.append((255, 255, 255, 0)) # Transparent
            else:
                new_data.append(item)
                
        img_resized.putdata(new_data)
        
        # Save output
        output_path = "/Users/danielmiller/.gemini/antigravity/brain/5162b39b-581d-4e61-846b-b933812b4ab6/routing_icon_final_64.png"
        img_resized.save(output_path, "PNG")
        print(f"Successfully created: {output_path}")
        
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    process_icon()
