package org.teacon.neb;

import com.github.luben.zstd.Zstd;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandles;

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
            MethodHandles.lookup().ensureInitialized(Zstd.class);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (UnsatisfiedLinkError e) {
            throw new UnsupportedOperationException(
                    "NotEnoughBandwidth cannot load ZStandard JNI for your platform. " +
                            "Please report this issue to TeaCon."
            );
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
