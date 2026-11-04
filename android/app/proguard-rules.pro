-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class it.ponte.RootBt { *; }

# Motore telecamere Eseecloud: il codice nativo cerca classi e metodi per nome
-keep class com.app.jagles.** { *; }
-keep class com.juan.base.** { *; }
