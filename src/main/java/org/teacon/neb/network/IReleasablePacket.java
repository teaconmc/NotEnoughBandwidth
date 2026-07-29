package org.teacon.neb.network;

public interface IReleasablePacket {
    record ReleaseContext() {
        public static final ReleaseContext INSTANCE = new ReleaseContext();
    }

    void release(ReleaseContext context);
}
