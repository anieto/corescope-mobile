# NodeScope R8 rules. kotlinx.serialization, Navigation, OkHttp, DataStore and MapLibre ship
# their own consumer rules; add a rule here only for something R8 was seen to break.

# Readable crash reports: keep line numbers (Play de-obfuscates with the bundle's mapping file).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
