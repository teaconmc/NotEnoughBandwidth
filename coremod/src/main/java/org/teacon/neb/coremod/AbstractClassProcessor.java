package org.teacon.neb.coremod;

import net.neoforged.neoforgespi.transformation.ClassProcessor;
import net.neoforged.neoforgespi.transformation.ProcessorName;

public abstract class AbstractClassProcessor implements ClassProcessor {
    private final ProcessorName name;

    protected AbstractClassProcessor(String path) {
        this.name = new ProcessorName("nebw", path);
    }

    @Override
    public final ProcessorName name() {
        return this.name;
    }
}
