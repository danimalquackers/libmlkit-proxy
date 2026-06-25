# Maintain line numbers and source file attributes for actionable crash logs
-keepattributes SourceFile,LineNumberTable

# Keep reflection targets safe
-keepattributes *Annotation*,Signature,EnclosingMethod,InnerClasses