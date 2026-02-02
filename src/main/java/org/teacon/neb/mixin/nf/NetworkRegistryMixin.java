package org.teacon.neb.mixin.nf;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.teacon.neb.network.indexed.IndexLookup;

import java.util.Map;

@SuppressWarnings("UnstableApiUsage")
@Mixin(NetworkRegistry.class)
public class NetworkRegistryMixin {
    @Shadow
    @Final
    protected static Map<ConnectionProtocol, Map<Identifier, PayloadRegistration<?>>> PAYLOAD_REGISTRATIONS;

    @Inject(method = "setup", at = @At("TAIL"))
    private static void onSetup(CallbackInfo ci) {
        IndexLookup.initialize(PAYLOAD_REGISTRATIONS.get(ConnectionProtocol.PLAY));
    }
}
