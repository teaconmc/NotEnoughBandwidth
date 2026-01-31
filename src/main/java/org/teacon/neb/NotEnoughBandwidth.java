package org.teacon.neb;

import com.github.luben.zstd.Zstd;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.teacon.neb.utils.vm.LookupAccess;

import java.util.Arrays;

/**
 * @author USS_Shenzhou
 */
@Mod(NotEnoughBandwidth.MODID)
public final class NotEnoughBandwidth {
    public static final String MODID = "nebw";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static ModContainer MOD_CONTAINER;

    public NotEnoughBandwidth(ModContainer container) {
        try {
            LookupAccess.IMPL_LOOKUP.ensureInitialized(Zstd.class);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (UnsatisfiedLinkError e) {
            throw new UnsupportedOperationException(
                    "NotEnoughBandwidth cannot load ZStandard JNI for your platform. " +
                            "Please report this issue to TeaCon."
            );
        }

        if (Arrays.stream(Heightmap.Types.values()).filter(Heightmap.Types::sendToClient).count() != 3) {
            throw new UnsupportedOperationException("NotEnoughBandwidth assumes that there're only 3 client-synced heightmap.");
        }

        MOD_CONTAINER = container;
        if (!MOD_CONTAINER.getModId().equals(MODID)) {
            throw new AssertionError();
        }
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
