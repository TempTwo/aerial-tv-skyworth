# Accessibility service and activities are referenced from the manifest; AGP keeps them.
# Skyworth config DTOs are serialized by class name into content://com.tianci.provider, so R8 must
# not rename or strip them (see SkyConfig).
-keep class com.tianci.system.data.** { *; }
