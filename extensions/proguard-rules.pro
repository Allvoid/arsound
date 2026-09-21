-dontobfuscate
-dontoptimize
-keepattributes *
-keep class app.revanced.** {
  *;
}
-keep class com.google.** {
  *;
}
# Kotlin function interfaces implemented by extensions are called by the app, not by the extension itself.
# Keep their methods, otherwise R8 strips "invoke" from the extension implementations.
-keep interface kotlin.jvm.functions.** {
  *;
}
# NewPipeExtractor and its libraries (moved into app.arsound.shaded) load parts of themselves by reflection.
-keep class app.arsound.shaded.** {
  *;
}
-dontwarn app.arsound.shaded.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
