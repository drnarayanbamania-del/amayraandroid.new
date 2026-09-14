# Maya R8 rules — targeted keeps only. The old blanket -keep on com.amayra.maya.**
# is removed: it disabled shrinking/obfuscation for all first-party code.

# --- kotlinx.serialization (official recommended rules) -----------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.amayra.maya.**$$serializer { *; }
-keepclassmembers class com.amayra.maya.** {
    *** Companion;
}
-keepclasseswithmembers class com.amayra.maya.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- reflective seams ---------------------------------------------------------
# Tools are looked up by name in ToolRegistry (no reflection), but keep tool
# class names stable so log/audit output stays readable.
-keepnames class com.amayra.maya.tools.** { *; }
