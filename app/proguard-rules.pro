# NG Scanner — правила ProGuard/R8.
# Пока minify отключён (см. app/build.gradle.kts). При включении обфускации
# сюда добавляются keep-правила для kotlinx.serialization (LLM-провайдеры
# работают через OkHttp — см. ADR-0002).

# kotlinx.serialization — сохранить сгенерированные сериализаторы.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
# @Serializable-модели приложения (гараж, чат, LLM-сообщения) и их сериализаторы.
-keepclassmembers @kotlinx.serialization.Serializable class ru.ngscanner.** {
    *** Companion;
    <fields>;
}
-keep class ru.ngscanner.**$Companion { *; }

# Google Tink — движок EncryptedSharedPreferences (security-crypto). Без keep
# R8 вырежет key-manager'ы, зарегистрированные рефлексией, и шифрованное
# хранилище ключей упадёт при старте.
-keep class com.google.crypto.tink.** { *; }
-keep class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.crypto.tink.**
