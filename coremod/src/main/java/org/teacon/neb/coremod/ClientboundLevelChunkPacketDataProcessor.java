package org.teacon.neb.coremod;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.stream.StreamSupport;

public class ClientboundLevelChunkPacketDataProcessor extends AbstractClassProcessor {
    private static final Type TARGET_FIELD = Type.getType("Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;");

    public ClientboundLevelChunkPacketDataProcessor() {
        super("client_bound_level_chunk_path_data");
    }

    @Override
    public boolean handlesClass(SelectionContext context) {
        return context.type().equals(TARGET_FIELD);
    }

    @Override
    public ComputeFlags processClass(TransformationContext context) {
        MethodNode method = context.node().methods.stream()
                .filter(mn -> mn.name.equals("<init>") && mn.desc.equals("(Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
                .findFirst()
                .orElseThrow();

        AbstractInsnNode target = StreamSupport.stream(method.instructions.spliterator(), false)
                .filter(node -> node.getOpcode() == Opcodes.NEWARRAY && ((IntInsnNode) node).operand == Opcodes.T_BYTE)
                .findFirst()
                .orElseThrow();

        method.instructions.set(target, new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "net/minecraft/network/protocol/game/ClientboundLevelChunkPacketData",
                "nebw$coremod$allocateArray",
                "(I)[B",
                false
        ));

        return ComputeFlags.COMPUTE_MAXS;
    }
}
