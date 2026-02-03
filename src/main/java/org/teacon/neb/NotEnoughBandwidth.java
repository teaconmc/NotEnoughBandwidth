package org.teacon.neb;

import com.github.luben.zstd.Zstd;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.teacon.neb.utils.vm.LookupAccess;

/**
 * @author USS_Shenzhou
 */
@SuppressWarnings("NotNullFieldNotInitialized")
@Mod(NotEnoughBandwidth.MODID)
public final class NotEnoughBandwidth {
    public static final String MODID = "nebw";
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

        MOD_CONTAINER = container;
        if (!container.getModId().equals(MODID)) {
            throw new AssertionError();
        }
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
