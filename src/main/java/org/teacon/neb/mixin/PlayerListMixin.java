package org.teacon.neb.mixin;

import net.minecraft.server.players.PlayerList;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.teacon.neb.NEBConfigs;
import org.teacon.neb.utils.ConfigAccess;

@Mixin(PlayerList.class)
public class PlayerListMixin {
    @ModifyVariable(method = "setViewDistance", at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/server/players/PlayerList;viewDistance:I",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER
    ), argsOnly = true)
    private int modifyViewDistance(int viewDistance) {
        return viewDistance + ConfigAccess.getOrDefault(NEBConfigs.CHUNK_CACHE_DISTANCE, 0);
    }
}
