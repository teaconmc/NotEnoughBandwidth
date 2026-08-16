package org.teacon.neb.network;

public interface IReleasablePacket {
    void release(ReleaseContext context);

    static void releaseIfPossible(Object object) {
        if (object instanceof IReleasablePacket releasable) {
            releasable.release(ReleaseContext.INSTANCE);
        }
    }

    record ReleaseContext() {
        private static final ReleaseContext INSTANCE = new ReleaseContext();
    }
}
