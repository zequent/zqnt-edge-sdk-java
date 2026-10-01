-dontwarn
-dontshrink
-dontoptimize

# Keep the complete customer-facing binary API stable. Private implementation
# details can still be renamed by ProGuard.
-keepnames public class com.zqnt.sdk.edge.**
-keepclassmembers public class com.zqnt.sdk.edge.** {
    public protected *;
}
-keep public interface com.zqnt.sdk.edge.** { *; }
-keep public enum com.zqnt.sdk.edge.** { *; }

# The Jandex index (META-INF/jandex.idx) is built BEFORE ProGuard runs, so every class it names
# must keep that name: Quarkus loads classes by the indexed name. The published preview had
# LiveDataServiceImpl$StreamFactory/$StreamState renamed to $a/$b while the index still named them,
# and the same rule set broke every Quarkus app using client-java-sdk on 2026-10-01 (a private
# ClientInterceptor renamed -> ClassNotFoundException at startup, since quarkus-grpc registers each
# indexed interceptor). Keep every class name in the SDK (a public library; renaming private
# classes hides nothing), and keep gRPC interceptors whole.
-keepnames class com.zqnt.sdk.edge.**
-keep class com.zqnt.sdk.edge.** implements io.grpc.ClientInterceptor { *; }
-keep class com.zqnt.sdk.edge.** implements io.grpc.ServerInterceptor { *; }

# Preserve metadata used by generic signatures, annotations, Lombok-generated
# nested builders and framework/runtime inspection.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,MethodParameters,Exceptions

# Do not expose local source paths in stack traces.
-renamesourcefileattribute SourceFile
