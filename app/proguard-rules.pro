# Pleos SDK는 Gson TypeToken과 리플렉션으로 바인더 응답을 해석한다. 축소되면 제네릭 정보가 지워져 기능 조회가 실패한다
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
