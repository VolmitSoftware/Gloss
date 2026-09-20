-dontwarn lombok.**

-keep class art.arcane.volmlib.integration.** { *; }
-keep class art.arcane.gloss.service.GlossIntegrationService { *; }
-keep class art.arcane.gloss.integrate.ReflectiveContractAdapter { *; }

-keep class art.arcane.volmlib.nativelib.**.proxy.NativeProxyForwardingAccess { public <init>(); }

-keep @art.arcane.volmlib.nativelib.NativeBinding interface * { *; }
-keep class art.arcane.volmlib.nativelib.v26_2_R1.block.NativeBlockEntityAccess { public <init>(); }
-keep class art.arcane.volmlib.nativelib.v26_3_R1.block.NativeBlockEntityAccess { public <init>(); }

-keep class art.arcane.volmlib.nativelib.**.scoreboard.NativeScoreboardPackets { public <init>(); }
