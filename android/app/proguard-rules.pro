# Regras extra do R8. As libs (Ktor, OkHttp, CameraX, MLKit, DataStore,
# Security-Crypto, kotlinx-serialization) já trazem as suas consumer-rules;
# aqui só ficam keeps específicos da app, se algum dia forem precisos.

# Modelos de eventos trocados com o servidor (serialização por nome).
-keep class com.flowtools.session.** { *; }
-keep class com.flowtools.pairing.** { *; }

# ViewModels instanciados pelo framework.
-keep class * extends androidx.lifecycle.ViewModel { *; }

# slf4j só é usado para logging opcional dentro das libs: o binder
# nunca existe em Android e o aviso do R8 é seguro de ignorar.
-dontwarn org.slf4j.**
