# NG Scanner — правила ProGuard/R8.
# Пока minify отключён (см. app/build.gradle.kts). При включении обфускации
# сюда добавляются keep-правила для kotlinx.serialization (LLM-провайдеры
# работают через OkHttp — см. ADR-0002).

# kotlinx.serialization — сохранить сгенерированные сериализаторы.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
