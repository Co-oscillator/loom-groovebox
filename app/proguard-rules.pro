# Loom Groovebox ProGuard Rules
# Keep JNI native methods and their containing classes

# Keep the NativeLib class and all its members
-keep class com.groovebox.NativeLib { *; }

# Keep all native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Kotlin coroutine support
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep Compose-related classes
-keep class androidx.compose.** { *; }

# Keep R8 from stripping data classes used across JNI
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep state data classes that may be serialized
-keep class com.groovebox.GrooveboxState { *; }
-keep class com.groovebox.persistence.** { *; }

# Prevent obfuscation of classes accessed via reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
