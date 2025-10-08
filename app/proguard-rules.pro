# Сохраняем все классы с @JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Сохраняем EngineWebView и его JavascriptInterface
-keep class com.github.movesense.engine.EngineWebView { *; }
-keep class com.github.movesense.engine.EngineWebView$* { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.github.movesense.**$$serializer { *; }
-keepclassmembers class com.github.movesense.** {
    *** Companion;
}
-keepclasseswithmembers class com.github.movesense.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# OkHttp/Retrofit
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# Chesslib
-keep class com.github.bhlangonijr.chesslib.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# WebView debugging (если нужен remote debugging)
-keepclassmembers class * extends android.webkit.WebView {
   public *;
}