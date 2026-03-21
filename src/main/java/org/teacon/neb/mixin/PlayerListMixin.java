package org.teacon.neb.mixin;

import net.minecraft.server.players.PlayerList;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.teacon.neb.NEBConfigs;

@Mixin(PlayerList.class)
public class PlayerListMixin {
    @ModifyVariable(method = "setViewDistance", at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/server/players/PlayerList;viewDistance:I",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER
    ), argsOnly = true)
    private int modifyViewDistance(int viewDistance) {
        try {
            return viewDistance + NEBConfigs.CHUNK_CACHE_DISTANCE.get();
        } catch (IllegalStateException _) {
            return viewDistance;
        }
    }
}
