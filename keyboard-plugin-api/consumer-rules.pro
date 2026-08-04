# Binder stubs and parcelables cross process boundaries by reflection-free
# framework machinery, but the AIDL-generated classes must keep their names
# so both sides of the IPC agree on the interface descriptor.
-keep class com.understory.keyboard.plugin.api.** { *; }
